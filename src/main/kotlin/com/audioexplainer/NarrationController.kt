package com.audioexplainer

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Executors

/**
 * Phase 7 — the narration loop. Turns walkthrough playback into synced audio: for each sub-highlight
 * of the current segment it moves the editor spotlight, pushes a `highlight_advance` to the sidebar,
 * streams the highlight's `ttsText` through [TtsService] as `audio_chunk`s, and — when the webview
 * reports `playback_complete` — advances to the next highlight (then the next segment).
 *
 * This is the JetBrains analogue of the code-explainer VS Code extension's playback driver. It owns
 * only its own small position state (segment/highlight index, suspended flag); the walkthrough remains
 * the source of truth for the plan and status.
 *
 * **Threading.** Every state transition runs on a single-thread executor. Listener callbacks
 * ([onWalkthroughChanged]) and webview messages ([onPlaybackComplete], [userAdvance], …) merely submit
 * a task, so state is touched from exactly one thread — no locks, and no lock-ordering deadlock against
 * the [Walkthrough]'s `synchronized` fan-out (a narration task may call back into the walkthrough, whose
 * listener callbacks re-enter here only by queuing another task, never by re-locking). [TtsService]
 * callbacks only *push* to the webview (no state), so they run off-thread safely.
 *
 * Gated on readiness: if the engine is off / not yet set up, narration stays disengaged and playback
 * is silent (the pre-Phase-7 behaviour) — the walkthrough still loads and the spotlight still follows.
 */
class NarrationController(
    private val project: Project,
    private val walkthrough: Walkthrough,
    private val post: (JsonElement) -> Unit,
    parentDisposable: Disposable,
) {
    private val log = Logger.getInstance(NarrationController::class.java)
    private val tts = TtsService.getInstance()
    private val settings = AudioExplainerSettings.getInstance()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "audio-explainer-narration").apply { isDaemon = true }
    }

    // ── Position state (executor thread only) ──
    private var active = false
    private var suspended = false
    private var segIndex = -1
    private var hlIndex = 0
    /**
     * Set when the user navigated to a different highlight *while paused*: the position + spotlight have
     * moved but no audio was produced, so the next Play must narrate this step **fresh** (via
     * [narrateCurrent]) rather than drain-[resume] the previous step's buffered audio.
     */
    private var restartPending = false
    private var stream: TtsService.TtsStream? = null
    private var ttsErrorNotified = false

    // Voice-editor "Test" preview — plays natively (see [NativePcmPlayer]), separate from narration.
    private var testStream: TtsService.TtsStream? = null
    private var testPlayer: NativePcmPlayer? = null

    @Volatile private var disposed = false

    init {
        com.intellij.openapi.util.Disposer.register(parentDisposable) {
            disposed = true
            runCatching { stream?.cancel() }
            runCatching { testStream?.cancel() }
            testPlayer?.stop()
            executor.shutdownNow()
        }
        // Populate the cached prerequisite status off any UI thread so [narrationEnabled] is accurate
        // by the time the user presses Play (does NOT start the server). refresh=false reuses the
        // app-level cache, so re-opening the tool window won't re-spawn the `--check` probe.
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!disposed) runCatching { tts.status(refresh = false) }
            // Discover Windows SAPI voices (Phase L7) so the voice combo + language menu include them
            // (empty no-op off Windows). Populates the [Languages] registry; the sidebar re-pushes voice
            // options once this returns so freshly-discovered voices show without reopening.
            if (!disposed) runCatching { tts.sapiVoices(refresh = false) }
        }
    }

    /** True while narration is actively driving playback (used by the sidebar to route highlight nav). */
    val isEngaged: Boolean get() = active

    // ── Entry points (all just enqueue) ──────────────────────────────────────────

    /** A walkthrough status/segment change — re-sync narration to it. */
    fun onWalkthroughChanged() = submit { sync() }

    /** A new plan was set (or structurally mutated) — hard-reset narration, then re-sync. */
    fun onPlanChanged() = submit {
        cancelStream()
        if (active) post(msg("audio_stop"))
        active = false
        suspended = false
        restartPending = false
        segIndex = -1
        hlIndex = 0
        sync()
    }

    /** The webview finished playing the current highlight's audio. */
    fun onPlaybackComplete() = submit { advanceAfterPlayback() }

    /** Sidebar "next/prev highlight" within the current segment (falls back to segment nav if idle). */
    fun userAdvance(delta: Int) = submit { userAdvanceInternal(delta) }

    /**
     * Sidebar outline click on a step (segment header → highlight 0) or substep (a specific highlight):
     * jump to that segment+highlight **preserving the current play/pause status** — like the Prev/Next
     * buttons, a click navigates but never force-plays (Phase 7.5 #4, revised). While playing it keeps
     * playing from the chosen step; while paused it moves silently (spotlight + transcript) and arms the
     * next Play to narrate that step fresh. With narration disabled it just moves position + spotlight.
     */
    fun gotoHighlight(segmentId: Int, highlightIndex: Int) = submit { gotoHighlightInternal(segmentId, highlightIndex) }

    /** Speed/voice changed — re-stream the current highlight so it takes effect immediately. */
    fun onRateChanged() = submit { if (active && !suspended) restartCurrent() }

    /** Voice-editor opened: pause narration audio so it doesn't fight the Test previews. */
    fun pauseForEditing() = submit { if (active && !suspended) { cancelStream(); suspend() } }

    /**
     * A short sample line for the voice-editor's Test button — the current highlight's spoken text (in the
     * walkthrough's language, so the preview is representative), or a generic line if none is loaded.
     */
    fun sampleText(): String {
        val segs = walkthrough.getState().segments
        val seg = segs.getOrNull(segIndex) ?: segs.firstOrNull()
        val hl = seg?.highlights?.getOrNull(hlIndex.coerceAtLeast(0))
            ?: seg?.highlights?.firstOrNull { !it.ttsText.isNullOrBlank() }
        return hl?.ttsText?.trim()?.take(200).takeUnless { it.isNullOrEmpty() }
            ?: "This is a preview of the selected robotic voice."
    }

    /**
     * Voice-editor "Test": synth the sample line with [voice] + [tune] and play it natively (independent
     * of the webview, so it works while narration is paused), cancelling any prior test. [onFinished] fires
     * (off-thread) when the preview ends on its own — natural end or error, not a cancel — so the dialog can
     * flip its Test/Stop button back to "play".
     */
    fun previewVoice(voice: String, tune: EspeakTune, onFinished: () -> Unit = {}) = submit {
        runCatching { testStream?.cancel() }
        testPlayer?.stop()
        val player = NativePcmPlayer(onFinished)
        testPlayer = player
        testStream = tts.speak(sampleText(), voice, settings.speed(), tune, player)
    }

    /** Stop any in-flight Test preview (the dialog was closed). */
    fun stopPreview() = submit {
        runCatching { testStream?.cancel() }
        testPlayer?.stop()
        testStream = null
        testPlayer = null
    }

    private fun submit(task: () -> Unit) {
        if (disposed) return
        runCatching {
            executor.submit {
                if (!disposed) runCatching { task() }.onFailure { log.warn("narration task failed", it) }
            }
        }
    }

    // ── Core state machine (executor thread) ──────────────────────────────────────

    private fun sync() {
        val snap = walkthrough.getState()
        when (snap.status) {
            Walkthrough.STATUS_STOPPED -> stopNarration()
            Walkthrough.STATUS_PAUSED -> if (active && !suspended) suspend()
            Walkthrough.STATUS_PLAYING -> {
                if (!narrationEnabled()) {
                    maybeOfferSetup()
                    return // play silently; leave narration disengaged
                }
                when {
                    !active -> startAt(snap.currentIndex, snap.currentHighlightIndex)
                    segIndex != snap.currentIndex -> startAt(snap.currentIndex, 0)
                    // User stepped highlights while paused → start THIS step from the beginning.
                    restartPending -> { restartPending = false; suspended = false; narrateCurrent() }
                    suspended -> resume()
                    // else: already narrating this segment — nothing to do
                }
            }
        }
    }

    private fun startAt(segmentIndex: Int, highlightIndex: Int) {
        cancelStream()
        // Clear any audio the webview still holds (e.g. buffered/suspended chunks from a segment the user
        // paused on before navigating). Without this, a prior suspend() leaves the AudioContext flagged
        // "intentionally suspended", so freshly-streamed chunks would queue silently instead of playing.
        post(msg("audio_stop"))
        active = true
        suspended = false
        restartPending = false
        segIndex = segmentIndex
        hlIndex = highlightIndex.coerceAtLeast(0)
        narrateCurrent()
    }

    /**
     * Reveal the current highlight: move the editor spotlight and push `highlight_advance` to the sidebar,
     * and — unless [speak] is false — stream its narration. Pass `speak = false` to move to a step while
     * paused (spotlight + transcript update, no audio).
     */
    private fun narrateCurrent(speak: Boolean = true) {
        val seg = walkthrough.getState().segments.getOrNull(segIndex) ?: return finish()
        val boundedHl = hlIndex.coerceIn(0, (seg.highlights.size - 1).coerceAtLeast(0))
        hlIndex = boundedHl

        // Move the editor spotlight to this highlight (fires WalkthroughListener.onHighlight).
        // NOTE the ordering: decorations land HERE, before any TTS request below — so every editor
        // decoration (including the token flash) precedes the voice by the whole synthesis latency.
        if (log.isDebugEnabled) log.debug("narrate: spotlight -> segment ${seg.id} step ${boundedHl + 1}/${seg.highlights.size}")
        walkthrough.advanceHighlight(boundedHl)

        val hl = seg.highlights.getOrNull(boundedHl)
        post(msg("highlight_advance") {
            put("highlightIndex", boundedHl)
            put("totalHighlights", seg.highlights.size)
            val expl = hl?.explanation
            if (expl != null) put("explanation", expl) else put("explanation", JsonNull)
        })

        if (!speak) return // paused navigation — spotlight + sidebar only, no audio

        val text = hl?.ttsText?.trim().orEmpty()
        if (text.isEmpty()) {
            // Nothing to speak for this highlight — advance without waiting on audio.
            submit { advanceAfterPlayback() }
            return
        }

        post(msg("server_loading") { put("loading", true) })
        val voice = settings.state.ttsVoice.ifBlank { AudioExplainerSettings.DEF_TTS_VOICE }
        val speed = settings.speed()
        // Robotic voices carry the saved profile for their variant (ae_f/ae_m); Kokoro voices pass null.
        val tune = if (Languages.engineOfVoice(voice) == Engine.ESPEAK)
            EspeakTune.from(settings.espeakProfile(voice.substringAfter('+', "ae_f"))) else null
        // Timestamped so the flash-vs-voice gap is measurable: this line minus the "narrate: spotlight"
        // line above is decoration lead time; the "first audio chunk" line below closes it.
        val requestedAt = System.currentTimeMillis()
        if (log.isDebugEnabled) log.debug("narrate: requesting TTS (voice=$voice, speed=$speed, ${text.length} chars)")
        var firstChunk = true
        stream = tts.speak(text, voice, speed, tune, object : TtsService.TtsListener {
            override fun onChunk(base64: String, sampleRate: Int, sentenceIndex: Int, sentenceWords: Int) {
                if (disposed) return
                if (firstChunk) {
                    firstChunk = false
                    if (log.isDebugEnabled) {
                        log.debug("narrate: first audio chunk after ${System.currentTimeMillis() - requestedAt}ms")
                    }
                }
                post(msg("audio_chunk") {
                    put("data", base64); put("sampleRate", sampleRate)
                    put("si", sentenceIndex); put("sw", sentenceWords)
                })
            }

            override fun onEnd() {
                if (!disposed) post(msg("audio_end"))
            }

            override fun onError(message: String) {
                if (disposed) return
                post(msg("audio_end")) // let the webview settle so it isn't stuck "loading"
                onTtsError(message, voice)
            }
        })
    }

    /** Called by the webview after audio for a highlight finishes; step to the next highlight/segment. */
    private fun advanceAfterPlayback() {
        if (!active || suspended) return
        val seg = walkthrough.getState().segments.getOrNull(segIndex) ?: return finish()
        val next = hlIndex + 1
        if (next < seg.highlights.size) {
            hlIndex = next
            narrateCurrent()
        } else {
            // Segment done → move to the next segment; the walkthrough's status/segment fan-out queues
            // a sync() that will start it. Disengage first so that sync() takes the "start fresh" path.
            active = false
            if (!walkthrough.next()) finish() // reached the end (walkthrough set itself to paused)
        }
    }

    private fun userAdvanceInternal(delta: Int) {
        if (!active) {
            // Narration not engaged — behave like segment nav (pre-Phase-7 fallback).
            if (delta >= 0) walkthrough.next() else walkthrough.prev()
            return
        }
        val snap = walkthrough.getState()
        val seg = snap.segments.getOrNull(segIndex) ?: return
        val playing = snap.status == Walkthrough.STATUS_PLAYING
        cancelStream()
        post(msg("audio_stop"))
        val target = hlIndex + delta
        when {
            target < 0 -> { active = false; walkthrough.prev() }
            target >= seg.highlights.size -> { active = false; if (!walkthrough.next()) finish() }
            playing -> { hlIndex = target; suspended = false; restartPending = false; narrateCurrent() }
            // Paused: move to the step silently (no autoplay); Play will narrate it fresh — Phase 7.5 #3.
            else -> { hlIndex = target; suspended = true; restartPending = true; narrateCurrent(speak = false) }
        }
    }

    private fun gotoHighlightInternal(segmentId: Int, highlightIndex: Int) {
        val snap = walkthrough.getState()
        val idx = snap.segments.indexOfFirst { it.id == segmentId }
        if (idx < 0) return
        val seg = snap.segments[idx]
        val hl = highlightIndex.coerceIn(0, (seg.highlights.size - 1).coerceAtLeast(0))
        val playing = snap.status == Walkthrough.STATUS_PLAYING

        // Move to the chosen segment WITHOUT changing play/pause status — an outline click navigates like
        // the Prev/Next buttons and never force-plays (Phase 7.5 #4, revised). Skipped when already there.
        if (snap.currentIndex != idx) walkthrough.navigateToPreservingStatus(segmentId)

        if (playing && narrationEnabled()) {
            // Was playing → keep playing, from the chosen step. startAt clears stale webview audio first.
            startAt(idx, hl)
            return
        }

        // Paused/stopped (or audio off): reveal the step — spotlight + sidebar transcript/outline — but do
        // NOT start audio. When narration is engaged, arm restartPending so the next Play (see sync()'s
        // PLAYING branch) narrates this step fresh instead of drain-resuming stale buffered audio.
        segIndex = idx
        hlIndex = hl
        if (narrationEnabled()) {
            active = true
            suspended = true
            restartPending = true
            cancelStream()
            post(msg("audio_stop"))
        } else {
            maybeOfferSetup()
        }
        narrateCurrent(speak = false)
    }

    private fun restartCurrent() {
        cancelStream()
        post(msg("audio_stop"))
        narrateCurrent()
    }

    private fun suspend() {
        suspended = true
        post(msg("audio_suspend")) // freeze playback; TTS keeps buffering into the webview
    }

    private fun resume() {
        suspended = false
        post(msg("audio_resume")) // webview drains buffered audio, then reports playback_complete
    }

    private fun stopNarration() {
        cancelStream()
        if (active) post(msg("audio_stop"))
        active = false
        suspended = false
        restartPending = false
    }

    private fun finish() {
        cancelStream()
        active = false
        suspended = false
        restartPending = false
    }

    private fun onTtsError(message: String, voice: String) {
        submit {
            finish()
            if (!ttsErrorNotified) {
                ttsErrorNotified = true
                val (title, body) = diagnoseTtsError(voice, message)
                notify(NotificationType.WARNING, title, body)
            }
        }
    }

    /**
     * Turn a raw TTS-server error into a user-facing (title, HTML body) tailored to the failing voice's
     * engine, so the *cause* is actionable — most importantly, an online Windows (SAPI) voice failing reads
     * as "this voice needs the internet / the endpoint is down", not a bare .NET exception. The raw server
     * message is always appended (escaped, truncated) for diagnosis. [TtsService.SERVER_UNAVAILABLE] is the
     * one engine-independent case (the server never started — bad Kokoro prerequisites).
     */
    private fun diagnoseTtsError(voice: String, message: String): Pair<String, String> {
        val settingsPath = "<b>Settings → Tools → Audio Explainer → Audio engines</b>"
        val raw = "<br/><br/><i>${escape(message)}</i>"
        if (message == TtsService.SERVER_UNAVAILABLE) {
            return "Narration unavailable" to
                ("The TTS server could not start, so the walkthrough is showing silently. Check the setup " +
                    "under $settingsPath → Kokoro.$raw")
        }
        val label = Languages.voiceLabel(voice).ifBlank { voice }
        return when (Languages.engineOfVoice(voice)) {
            Engine.SAPI ->
                if (Languages.sapiVoiceName(voice).contains("Online", ignoreCase = true))
                    "Online voice unavailable" to
                        ("“${escape(label)}” is an <b>online</b> Windows voice — each sentence is synthesized " +
                            "by Microsoft's Edge endpoint (via NaturalVoiceSAPIAdapter), so it needs a working " +
                            "internet connection. That call failed: you may be offline, the endpoint may be " +
                            "down or rate-limiting, or the adapter may not be installed. Pick an offline voice " +
                            "(Kokoro, espeak, or a classic Windows voice) in the sidebar, or check your " +
                            "connection and try again.$raw")
                else
                    "Windows voice failed" to
                        ("The Windows voice “${escape(label)}” could not synthesize this sentence — it may have " +
                            "been uninstalled. Rescan under $settingsPath → Windows SAPI, or choose a different " +
                            "voice in the sidebar.$raw")
            Engine.ESPEAK ->
                "Robotic voice failed" to
                    ("The bundled robotic (espeak) voice could not synthesize this sentence. Try a different " +
                        "voice in the sidebar, or re-check the engines under $settingsPath.$raw")
            Engine.PIPER ->
                "Piper voice failed" to
                    ("The offline-neural (Piper) voice “${escape(label)}” could not synthesize this sentence — " +
                        "its model file may be missing or corrupt. Re-download it under $settingsPath → Piper, " +
                        "or choose a different voice in the sidebar.$raw")
            Engine.KOKORO ->
                "Narration unavailable" to
                    ("The Kokoro TTS server could not synthesize audio, so the walkthrough is showing silently. " +
                        "Check the setup under $settingsPath → Kokoro.$raw")
        }
    }

    private fun cancelStream() {
        stream?.cancel()
        stream = null
    }

    // Narration rides the running Kokoro TTS server; espeak (L6) and Windows SAPI (L7) voices are hosted
    // by that same server process (per-VOICE engine), so readiness is the one gate. "off" is silent.
    private fun narrationEnabled(): Boolean =
        settings.state.ttsEngine != AudioExplainerSettings.ENGINE_OFF && tts.cachedReady()

    /** On the first silent Play with Kokoro selected-but-unconfigured, offer the setup once. */
    private fun maybeOfferSetup() {
        val st = settings.state
        if (st.ttsEngine == AudioExplainerSettings.ENGINE_KOKORO && !tts.cachedReady() && !st.ttsSetupPrompted) {
            st.ttsSetupPrompted = true
            TtsSetup.firstRunPrompt(project)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun msg(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonElement =
        buildJsonObject { put("type", type); build() }

    private fun notify(type: NotificationType, title: String, content: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Audio Explainer")
                .createNotification(title, content, type)
                .notify(project)
        }
    }

    private fun escape(s: String): String =
        com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(
            com.intellij.openapi.util.text.StringUtil.shortenTextWithEllipsis(s, 300, 0),
        )
}
