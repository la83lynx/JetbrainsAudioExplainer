package com.audioexplainer

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Color
import java.nio.file.Path
import javax.swing.JComponent

/**
 * The JCEF-backed sidebar (Phase 4) — the JetBrains analogue of code-explainer's VS Code webview.
 *
 * Hosts a [JBCefBrowser] loading the reused `sidebar.{html,css,js}` (assembled into one document),
 * and bridges the two directions the webview expects:
 *  - **webview → plugin**: a [JBCefJSQuery] shim provides `acquireVsCodeApi().postMessage(msg)`, whose
 *    JSON is routed to [onWebviewMessage] and drives the [Walkthrough] state machine in-process.
 *  - **plugin → webview**: registered as a [WalkthroughListener], each change pushes a full `update`
 *    (and `saved_list` on request) by calling `window.__audioExplainerReceive(json)` — the same shape
 *    the upstream `window.addEventListener("message", …)` handler consumes.
 *
 * Audio is driven by a [NarrationController] (Phase 7): walkthrough status/segment changes are relayed
 * to it, and it pushes `highlight_advance` + `audio_chunk`/`audio_end` back through [postToWebview] while
 * streaming Kokoro TTS. The code rendering is driven independently by [TreePlanController], the
 * walkthrough's other listener.
 */
class AudioExplainerSidebar(
    private val project: Project,
    parentDisposable: Disposable,
) : WalkthroughListener {

    private val log = Logger.getInstance(AudioExplainerSidebar::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val server = service<ExplainerServerService>().server
    private val walkthrough get() = server.walkthrough

    private val browser = JBCefBrowser()
    private val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    /** Phase 7 audio driver. Pushes audio/highlight messages through [postToWebview]. */
    private val narration = NarrationController(project, walkthrough, ::postToWebview, parentDisposable)

    /** The open robotic-voice editor dialog, if any (closed/replaced when a new one opens). */
    private var voiceDialog: EspeakVoiceDialog? = null

    /** Phase 7.5 #5 — in-flight generation signal, rendered as the loading banner. */
    private val tracker = ExplainSessionTracker.getInstance(project)
    private val trackerListener = ExplainSessionTracker.Listener { pushGenerationStatus(it) }

    @Volatile private var disposed = false
    @Volatile private var loaded = false

    val component: JComponent get() = browser.component

    init {
        Disposer.register(parentDisposable, jsQuery)
        Disposer.register(parentDisposable, browser)
        Disposer.register(parentDisposable, Disposable {
            disposed = true
            walkthrough.removeListener(this)
            tracker.removeListener(trackerListener)
        })
        tracker.addListener(trackerListener)

        // Phase 7.5 #10 — follow live IDE theme changes: when the LAF (light/dark, or a new theme) switches
        // while the tool window is open, re-inject the CSS variables so the panel tracks it without a reload.
        ApplicationManager.getApplication().messageBus.connect(parentDisposable)
            .subscribe(LafManagerListener.TOPIC, object : LafManagerListener {
                override fun lookAndFeelChanged(source: LafManager) = pushTheme()
            })

        jsQuery.addHandler { request ->
            try {
                onWebviewMessage(request)
            } catch (t: Throwable) {
                log.warn("sidebar message handling failed: $request", t)
            }
            null
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                // Only the main frame; ignore sub-frame loads.
                if (frame != null && !frame.isMain) return
                loaded = true
                pushUpdate()
                // Restore the persisted voice/speed into the webview controls so they match the engine.
                pushSettings()
                // Populate the voice combo for the loaded walkthrough's language (Phase L3); authoritative
                // over pushSettings' voice, so it comes after.
                pushVoiceOptions()
                // Runtime-discovered voices — Windows SAPI (L7) and downloaded Piper models (L8) — arrive via
                // async probes that may finish after this first push; re-push once they're in so they appear
                // without reopening the tool window.
                scheduleDiscoveredVoiceRefresh()
                // Push the saved-walkthroughs list now that we're loaded. The webview's own initial
                // request_saved_list is fired from its first render() BEFORE `loaded` flips true, so that
                // request is dropped by postToWebview's guard; this authoritative push is what populates the
                // idle-view list on first open (Phase 7.5 #16).
                pushSavedList()
                // Catch up on a generation already in flight (the tool window may have just been opened
                // by ClaudeExplainer to show this banner) and any pending undo offer.
                pushGenerationStatus(tracker.current)
                if (walkthrough.hasPrevious()) pushPlanReplaced()
            }
        }, browser.cefBrowser)

        // Point per-workspace storage at THIS project BEFORE the webview loads, so the first pushSettings()
        // / pushSavedList() in onLoadEnd already have the save directory and saved list available. It's also
        // set by ServerStartupActivity on project open, but that ProjectActivity coroutine can race the tool
        // window's creation (e.g. a tool window restored at startup) — setting it here removes that race for
        // the sidebar, which is what fixes the missing save-dir tooltips + empty saved-list on first load.
        project.basePath?.let { server.setWorkspaceRoot(Path.of(it)) }

        browser.loadHTML(buildHtml())
        walkthrough.addListener(this)
    }

    // ── WalkthroughListener → push a fresh snapshot to the webview ──

    override fun onPlan(state: WalkthroughSnapshot) {
        pushUpdate()
        // A newly loaded plan may be a different language → repopulate the voice combo and reconcile the
        // voice BEFORE narration (re)streams, so playback uses a voice valid for the new language (Phase L3).
        pushVoiceOptions()
        narration.onPlanChanged()
        // A non-empty plan was replaced → offer a one-level Undo instead of silently clobbering it.
        if (walkthrough.hasPrevious()) pushPlanReplaced() else postToWebview(buildJsonObject { put("type", "plan_replaced_clear") })
    }

    /**
     * **The position moved — the one navigation callback, and the sidebar consumes it.**
     *
     * Round 3's one defect was this method's ancestor not existing: the sidebar learned its sub-step position
     * from the narration controller's `highlight_advance` alone, so the outline tracked sub-steps only while
     * a TTS server was engaged. There is one event now and one position in it, so an outline row cannot
     * survive a move it did not hear about and no listener can consume half the walk.
     */
    override fun onStep(index: Int) {
        pushUpdate()
        narration.onWalkthroughChanged()
    }

    override fun onStatus(status: String) {
        pushUpdate()
        narration.onWalkthroughChanged()
    }

    /**
     * **Saved, or its unsaved content discarded** (`Saving.md` C) — the Save button's tooltip follows the new
     * identity, and a save may have added a row to the saved list. Narration is not told: nothing it plays
     * changed.
     */
    override fun onSaveState(state: WalkthroughSnapshot) {
        pushUpdate()
        pushSavedList()
    }

    private fun pushUpdate() {
        val state = walkthrough.getState()
        val lang = Languages.byCode(state.language)
        val now = walkthrough.currentStepView()
        postToWebview(buildJsonObject {
            put("type", "update")
            put("title", state.title)
            // The row model and the position, separately: the outline is rebuilt only when the *content*
            // changes (the webview's own signature check), and `currentStep` moves on every navigation.
            put("outline", json.encodeToJsonElement(state.outline))
            put("currentStep", state.stepIndex)
            put("stepCount", state.stepCount)
            // What Now Playing shows for that step — the frame's identity and prose, the step's narration,
            // and the one place a file and a line survive (the location link).
            put("now", if (now != null) json.encodeToJsonElement(now) else JsonNull)
            put("status", state.status)
            // The Save split button's state (`Saving.md` C): which file Save writes, and whether it has
            // anything to write.
            put("savedAs", state.savedAs?.let { JsonPrimitive(it) } ?: JsonNull)
            put("unsaved", state.unsaved)
            put("language", lang.code)
            put("languageName", lang.displayName)
            put("autonym", lang.autonym)
        })
    }

    /**
     * Push the current walkthrough language's eligible voices + the effective selection to the webview
     * (Phase L3). Reconciles the persisted voice to the language first (see [reconcileVoice]), so the
     * combo and the narration engine agree. Called on (re)load and whenever a new plan lands.
     */
    private fun pushVoiceOptions() {
        val lang = Languages.byCode(walkthrough.getState().language)
        val selected = reconcileVoice(lang.code)
        postToWebview(buildJsonObject {
            put("type", "voice_options")
            put("language", lang.code)
            put("languageName", lang.displayName)
            put("autonym", lang.autonym)
            put("requiresExtraDeps", lang.requiresExtraDeps)
            // Each voice carries whether it is robotic (espeak) so the webview can toggle the robotic
            // controls purely client-side when the dropdown changes — engine is per-voice now.
            putJsonArray("voices") {
                lang.voices.forEach { v ->
                    add(buildJsonObject {
                        put("id", v.id); put("label", v.label)
                        put("robotic", v.engine == Engine.ESPEAK)
                        // Engine badge shown after the label for consistency across tiers
                        // ("Heart (US, F) — kokoro", "Robotic (F) — espeak", "Aria (F) — WinSAPI, online").
                        put("engineTag", Languages.engineTag(v.id))
                    })
                }
            }
            put("selectedVoice", selected)
            // Show the robotic controls iff the *selected* voice is robotic, and seed the inline
            // Pitch/Liveliness/Word-gap sliders from that voice's saved profile.
            put("robotic", Languages.engineOfVoice(selected) == Engine.ESPEAK)
            putRoboticProfile(selected)
        })
    }

    /**
     * Runtime-discovered voices — Windows SAPI (Phase L7, a PowerShell probe) and downloaded Piper models
     * (Phase L8, which ride the `--check` probe) — may be found after the initial [pushVoiceOptions]. Kick
     * both off (cached → at most one probe app-wide each) and re-push the voice options on the EDT if either
     * added voices for the current language, so they appear without a reopen. No churn when nothing changed.
     */
    private fun scheduleDiscoveredVoiceRefresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed) return@executeOnPooledThread
            val langCode = walkthrough.getState().language
            val before = Languages.voicesFor(langCode).size
            val tts = TtsService.getInstance()
            runCatching { tts.sapiVoices(refresh = false) }  // Windows SAPI (no-op off Windows)
            runCatching { tts.status(refresh = false) }        // Piper rides --check → Languages.setPiperVoices
            if (Languages.voicesFor(langCode).size != before) {
                ApplicationManager.getApplication().invokeLater {
                    if (loaded && !disposed && !project.isDisposed) pushVoiceOptions()
                }
            }
        }
    }

    /** Add the current profile's inline-slider values (pitch/range/wordGap) for [voice]'s variant. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putRoboticProfile(voice: String) {
        val p = AudioExplainerSettings.getInstance().espeakProfile(voice.substringAfter('+', "ae_f"))
        put("espeakPitch", p.pitch)
        put("espeakRange", p.range)
        put("espeakWordGap", p.wordGap)
    }

    /** Push just the inline-slider values for [voice]'s variant (after a voice switch or a dialog Save). */
    private fun pushRoboticProfile(voice: String) {
        postToWebview(buildJsonObject {
            put("type", "robotic_profile")
            putRoboticProfile(voice)
        })
    }

    /**
     * Open the Advanced voice-editor dialog for robotic [voice]. Pauses narration first (so Test previews
     * don't fight it), seeds the dialog from the saved variant profile, and on Save persists it, refreshes
     * the inline sliders, and re-streams. Non-modal; a prior editor is closed so only one is open.
     */
    private fun openVoiceEditor(voice: String) {
        if (Languages.engineOfVoice(voice) != Engine.ESPEAK) return
        narration.pauseForEditing()
        val settings = AudioExplainerSettings.getInstance()
        val variant = voice.substringAfter('+', "ae_f")
        val label = Languages.voiceLabel(voice)
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
            voiceDialog?.let { runCatching { it.close(DialogWrapper.CANCEL_EXIT_CODE) } }
            val dialog = EspeakVoiceDialog(
                project = project,
                voiceLabel = label,
                initial = settings.espeakProfile(variant),  // read-only seed; dialog builds a fresh profile
                onTest = { tune, onFinished ->
                    // onFinished flips the dialog's ■→▶ button; marshal it back onto the EDT.
                    narration.previewVoice(voice, tune) {
                        ApplicationManager.getApplication().invokeLater {
                            if (!disposed && !project.isDisposed) onFinished()
                        }
                    }
                },
                onStop = { narration.stopPreview() },
                onSave = { edited ->
                    applyProfile(variant, edited)
                    pushRoboticProfile(voice)
                    narration.onRateChanged()
                },
                onClosed = { narration.stopPreview() },
            )
            voiceDialog = dialog
            dialog.show()
        }
    }

    /** Copy an edited profile's fields into the persisted profile for [variant]. */
    private fun applyProfile(variant: String, p: EspeakProfile) {
        val target = AudioExplainerSettings.getInstance().espeakProfile(variant)
        target.pitch = p.pitch
        target.range = p.range
        target.wordGap = p.wordGap
        target.consonants = p.consonants
        target.roughness = p.roughness
        target.flutter = p.flutter
        target.breath = p.breath
    }

    /**
     * Baseline voice-memory policy (ExplainerLanguages.md §5): keep the single "last voice"; if it isn't
     * valid for [langCode], reset it to that language's default so a Spanish walkthrough can't be spoken
     * by an English voice. Persists the fallback (narration reads `ttsVoice` at speak-time) and returns
     * the effective voice. Pure persist — the live re-stream on a *user* voice change stays in `voice_change`.
     */
    private fun reconcileVoice(langCode: String): String {
        val st = AudioExplainerSettings.getInstance().state
        if (Languages.isVoiceValidFor(langCode, st.ttsVoice)) return st.ttsVoice
        val fallback = Languages.defaultVoiceFor(langCode)
        st.ttsVoice = fallback
        return fallback
    }

    /** Push the current generation phase to the webview's loading banner (Phase 7.5 #5). */
    private fun pushGenerationStatus(state: ExplainSessionState) {
        postToWebview(buildJsonObject {
            put("type", "generation_status")
            put("phase", state.phase.name.lowercase())
            put("displayName", state.displayName)
            put("activity", state.activity)
            put("message", state.message)
        })
    }

    /** Tell the webview to show the "New walkthrough loaded — Keep / Undo" banner (Phase 7.5 #5). */
    private fun pushPlanReplaced() {
        postToWebview(buildJsonObject {
            put("type", "plan_replaced")
            put("previousTitle", walkthrough.previousTitle() ?: "")
        })
    }

    /** Re-push the live IDE theme colours into the webview's `#ae-theme-vars` style element (Phase 7.5 #10). */
    private fun pushTheme() {
        postToWebview(buildJsonObject { put("type", "theme"); put("css", themeVars()) })
    }

    /**
     * Re-push the theme because a **setting** changed rather than the IDE's LAF — Settings ▸ Apply.
     *
     * [themeVars] has always read `accentColor`, so the panel was designed to follow it; what was missing is
     * anyone telling it. K6 found that from the other side: *"changing accent color only changed tree of
     * code default color, not the color of audio explainer window accents"* — the play button, the playback
     * fill, the volume slider and the read-along wash all kept the old gold until the tool window was
     * rebuilt or the IDE theme changed, because `pushTheme`'s only caller was `lookAndFeelChanged`. A
     * setting labelled *Accent colour* that reaches half the plugin's surfaces is a defect in the half that
     * does not move, not a narrower setting than it looks.
     */
    fun refreshTheme() = pushTheme()

    /**
     * Push the persisted voice + speed so the webview controls reflect them on (re)load (Phase 7.5), plus
     * the walkthroughs save directory so the Save button / "Saved Walkthroughs" label can show it on hover
     * (Phase 7.5 #14 task 5).
     */
    private fun pushSettings() {
        val st = AudioExplainerSettings.getInstance().state
        postToWebview(buildJsonObject {
            put("type", "settings")
            put("voice", st.ttsVoice)
            put("speedPercent", st.ttsSpeedPercent)
            server.savedWalkthroughsDir()?.let { put("walkthroughsDir", it) }
        })
    }

    private fun pushSavedList() {
        val entries = server.listSavedWalkthroughs()
        postToWebview(buildJsonObject {
            put("type", "saved_list")
            putJsonArray("walkthroughs") {
                entries.forEach { e ->
                    add(buildJsonObject {
                        put("name", e.name)
                        put("title", e.title)
                        // Language id badge (e.g. "[DE]") + full name for the tooltip. Uses the raw stored
                        // code for the tag so a dynamic Piper/SAPI language still tags correctly.
                        put("langCode", e.language)
                        put("langTag", e.language.uppercase())
                        put("langName", Languages.byCode(e.language).displayName)
                    })
                }
            }
        })
    }

    // ── webview → plugin ──

    private fun onWebviewMessage(request: String) {
        val obj = json.parseToJsonElement(request).jsonObject
        when (obj["type"]?.jsonPrimitive?.content) {
            "play_pause" -> walkthrough.togglePlayPause()
            // **By plan step, not by segment** (G2 round 2). These used to call `next`/`prev`, which move by
            // *segment* — a run of consecutive steps in one frame — so each press skipped whatever sub-steps
            // the current frame held. Routed through the narration controller rather than straight at
            // `walkthrough.nextStep()` so a *playing* walkthrough re-narrates the step it lands on; the
            // controller falls back to the same `nextStep`/`prevStep` when narration is not engaged.
            "next" -> narration.userAdvance(+1)
            "prev" -> narration.userAdvance(-1)
            // Outline navigation (Phase 7.5 #4, revised): jump to a plan step, PRESERVING play/pause
            // status — a click navigates, it doesn't force-play (mirrors Prev/Next). One message where
            // there were two: with a flat position a group header and a step row both name a step index,
            // so a header click is simply its first step.
            "goto_step" -> obj.intField("index")?.let { narration.gotoStep(it) }
            // Clicking the "file:lines" location under Now Playing re-opens that file and scrolls to it —
            // useful after the user navigated elsewhere in the editor. Does not touch playback.
            "reveal_location" -> obj.stringField("file")?.let { revealLocation(it, obj.intField("start") ?: 1) }
            "restart" -> walkthrough.restart()
            // Asked about first when it holds unsaved work (`Saving.md` D); Cancel leaves it showing.
            "close_walkthrough" -> onClose()
            // Phase 7.5 #5 — undo/keep the walkthrough that a generation just replaced.
            "restore_previous" -> walkthrough.restorePrevious()
            "dismiss_replace" -> walkthrough.clearPrevious()
            "request_saved_list" -> pushSavedList()
            // The split button (`Saving.md` C): Save writes the file this walkthrough already is, or asks once;
            // the caret's Save As… always asks.
            "save" -> onSave(saveAs = false)
            "save_as" -> onSave(saveAs = true)
            // **No unsaved prompt here, deliberately** (`Saving.md` D): loading from the saved list is the one
            // replacement made on the list itself, and the replace-and-Undo banner already covers it.
            "load" -> obj.stringField("name")?.let { onLoad(it) }
            // Saved-list management: delete one (confirmed) / delete all (confirmed). Confirmation is a
            // native Messages dialog — JCEF's off-screen renderer doesn't reliably show `window.confirm`.
            "delete_walkthrough" -> obj.stringField("name")?.let { onDelete(it, obj.stringField("title") ?: it) }
            "delete_all_walkthroughs" -> onDeleteAll()
            // Narration feedback + TTS knobs (Phase 7).
            "playback_complete" -> narration.onPlaybackComplete()
            // The webview's Web Audio playhead has reached this step's first buffer — i.e. the user is NOW
            // hearing it. Only the webview knows this moment: the plugin's own "first chunk" callback is
            // synthesis/enqueue time, measured 1.8–6.0 s early (MoreUI.md §2.8). Routed straight to the
            // surface, which is where the time-sensitive decoration lives. The flash is checked against the
            // step showing before it fires, so a stale one from a replaced plan is dropped.
            "playback_started" -> {
                val stepIndex = obj.intField("index") ?: -1
                forEachOwnTreeController { it.flashStartBeat(stepIndex) }
            }
            // A beat came due: the playhead has reached the start of the sentence holding this beat's
            // phrase. Same channel and same staleness rule as `playback_started` above — the webview is
            // still the only side that knows what is being heard, and a beat crossing the wire after the
            // step moved on must not decorate the step that replaced it.
            "beat" -> {
                val stepIndex = obj.intField("index") ?: -1
                val beat = obj.intField("beat") ?: -1
                forEachOwnTreeController { it.flashBeat(stepIndex, beat) }
            }
            "speed_change" -> obj.doubleField("speed")?.let {
                AudioExplainerSettings.getInstance().state.ttsSpeedPercent = (it * 100).toInt().coerceIn(25, 400)
                narration.onRateChanged()
            }
            // Robotic-voice (espeak) inline sliders (pitch / liveliness / word-gap) — update the CURRENT
            // voice's variant profile and re-stream, exactly like speed_change. Only Engine.ESPEAK voices.
            "espeak_tune" -> {
                val settings = AudioExplainerSettings.getInstance()
                val profile = settings.espeakProfile(settings.state.ttsVoice.substringAfter('+', "ae_f"))
                obj.intField("pitch")?.let { profile.pitch = it.coerceIn(0, 100) }
                obj.intField("range")?.let { profile.range = it.coerceIn(0, 100) }
                obj.intField("wordGap")?.let { profile.wordGap = it.coerceIn(0, 20) }
                narration.onRateChanged()
            }
            // Open the Advanced voice-editor dialog for the current robotic voice (stops playback first).
            "advanced_open" -> obj.stringField("voice")?.let { openVoiceEditor(it) }
            "voice_change" -> obj.stringField("voice")?.let { v ->
                // Only accept a voice valid for the current walkthrough's language (the webview only offers
                // such voices; this guards against a stale/rogue message). Then re-stream in the new voice.
                val langCode = walkthrough.getState().language
                if (Languages.isVoiceValidFor(langCode, v)) {
                    AudioExplainerSettings.getInstance().state.ttsVoice = v
                    // Reseed the inline sliders from the new voice's variant profile.
                    if (Languages.engineOfVoice(v) == Engine.ESPEAK) pushRoboticProfile(v)
                    narration.onRateChanged()
                }
            }
            // Phase 7.5 #6 — the webview asks us to force a native CEF repaint after a layout change (or via
            // the manual "Refresh UI" button), to defeat JCEF's flaky partial invalidation leaving ghost pixels.
            "repaint" -> forceBrowserRepaint()
            // Volume + mute act locally in the webview; chunk_played is a fine-grained progress ping.
            "volume_change", "mute_toggle", "chunk_played" -> Unit
        }
    }

    /**
     * Apply [action] to the [TreePlanController] driving the walkthrough. It registers itself as a walkthrough
     * listener at project open (see `ServerStartupActivity`), which is the only handle on it — the same
     * discovery [HighlightSettingsConfigurable] uses for its live re-style.
     */
    private fun forEachOwnTreeController(action: (TreePlanController) -> Unit) {
        runCatching {
            walkthrough.getListeners()
                .filterIsInstance<TreePlanController>()
                .forEach(action)
        }
    }

    /**
     * Open [filePath] (relative to this project's base path, or absolute) and scroll to [line1] (1-based),
     * focusing the editor — the Now-Playing location link (Phase 7.5). File resolution (a VFS refresh) is
     * a slow op forbidden on the EDT, so it runs on a pooled thread; only the editor open is marshalled back.
     */
    private fun revealLocation(filePath: String, line1: Int) {
        // While a tree is showing, this file is already on screen inside a node — so reveal it there rather
        // than opening a tab in front of the tree (the round-1 defect, reached from a different click).
        // Asked on the EDT because it touches editors, and before any VFS work because the tree needs none.
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
            var handled = false
            forEachOwnTreeController { if (!handled && it.revealInTree(filePath, line1)) handled = true }
            if (!handled) openLocationInEditor(filePath, line1)
        }
    }

    /** The file-tab fallback for [revealLocation]: no tree, or no node owns the file. */
    private fun openLocationInEditor(filePath: String, line1: Int) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed || project.isDisposed) return@executeOnPooledThread
            val vFile = try {
                val nio = Path.of(filePath)
                val abs = if (nio.isAbsolute) nio else project.basePath?.let { Path.of(it).resolve(nio) } ?: nio
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(abs.normalize())
            } catch (_: Exception) {
                null
            } ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed) return@invokeLater
                try {
                    FileEditorManager.getInstance(project)
                        .openTextEditor(OpenFileDescriptor(project, vFile, (line1 - 1).coerceAtLeast(0), 0), true)
                } catch (t: Throwable) {
                    log.warn("reveal_location failed for $filePath:$line1", t)
                }
            }
        }
    }

    /**
     * Force the JCEF surface to repaint (Phase 7.5 #6). CEF's off-screen invalidation is flaky — after a
     * layout change (a docked banner appearing/disappearing, a view switch, the outline shrinking) it can
     * leave stale "ghost" pixels until an input event forces a repaint. The webview requests this via the
     * `repaint` message (both from automatic layout transitions and the manual "Refresh UI" button); we hit
     * both native levers — CEF's own `invalidate()` and a Swing `repaint()` of the host component — on the EDT.
     */
    private fun forceBrowserRepaint() {
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            try {
                browser.cefBrowser.invalidate()
            } catch (t: Throwable) {
                log.warn("cefBrowser.invalidate failed", t)
            }
            try {
                browser.component.repaint()
            } catch (t: Throwable) {
                log.warn("browser component repaint failed", t)
            }
        }
    }

    /** Save / Save As… — the dialogs are [SaveFlow]'s; a successful save re-pushes through [onSaveState]. */
    private fun onSave(saveAs: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
            if (saveAs) SaveFlow.saveAs(project) else SaveFlow.save(project)
        }
    }

    /** The header's Close and the done view's Finish: ask about unsaved work, then end the walkthrough. */
    private fun onClose() {
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
            if (SaveFlow.confirmLoss(project, "Closing it discards it.")) walkthrough.stop()
        }
    }

    private fun onLoad(name: String) {
        if (!server.loadSavedWalkthrough(name)) {
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && !project.isDisposed) {
                    Messages.showErrorDialog(project, "Could not load \"$name\".", "Load Walkthrough")
                }
            }
        }
        // On success, walkthrough.setPlan fires our listener → pushUpdate re-renders the sidebar.
    }

    /** Delete one saved walkthrough after a native confirmation, then re-push the (now shorter) list. */
    private fun onDelete(name: String, title: String) {
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
            val choice = Messages.showYesNoDialog(
                project,
                "Delete the saved walkthrough \"$title\"?\nThis cannot be undone.",
                "Delete Walkthrough",
                "Delete", "Cancel",
                Messages.getWarningIcon(),
            )
            if (choice != Messages.YES) return@invokeLater
            server.deleteSavedWalkthrough(name)
            pushSavedList()
        }
    }

    /** Delete every saved walkthrough in this project after a native confirmation, then re-push the list. */
    private fun onDeleteAll() {
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
            val count = server.listSavedWalkthroughs().size
            if (count == 0) return@invokeLater
            val choice = Messages.showYesNoDialog(
                project,
                "Delete all $count saved walkthrough(s) in this project?\nThis cannot be undone.",
                "Delete All Walkthroughs",
                "Delete All", "Cancel",
                Messages.getWarningIcon(),
            )
            if (choice != Messages.YES) return@invokeLater
            server.deleteAllSavedWalkthroughs()
            pushSavedList()
        }
    }

    // ── plugin → webview transport ──

    /** Deliver a `ToWebviewMessage` by dispatching a synthetic `message` event, as the webview expects. */
    private fun postToWebview(msg: JsonElement) {
        if (!loaded || disposed) return
        val literal = JsonPrimitive(msg.toString()).toString() // safely-escaped JS string literal
        val jsCode = "window.__audioExplainerReceive($literal);"
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            try {
                browser.cefBrowser.executeJavaScript(jsCode, browser.cefBrowser.url ?: "", 0)
            } catch (t: Throwable) {
                log.warn("pushing to sidebar failed", t)
            }
        }
    }

    // ── HTML assembly ──

    private fun buildHtml(): String {
        val css = readResource("/sidebar/sidebar.css")
        val body = readResource("/sidebar/sidebar.html")
        val js = readResource("/sidebar/sidebar.js")
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<style>$css</style>
<style id="ae-theme-vars">${themeVars()}</style>
</head>
<body>
$body
<script>${bridgeJs()}</script>
<script>$js</script>
</body>
</html>"""
    }

    /**
     * The bridge shim, injected BEFORE sidebar.js runs. Provides the `acquireVsCodeApi()` the webview
     * calls at load, routing `postMessage` through the [JBCefJSQuery], and a `__audioExplainerReceive`
     * that re-dispatches plugin→webview messages as `message` events.
     */
    private fun bridgeJs(): String = """
(function () {
    function send(payload) { ${jsQuery.inject("payload")} }
    window.acquireVsCodeApi = function () {
        return {
            postMessage: function (m) { send(JSON.stringify(m)); },
            getState: function () { return null; },
            setState: function () {},
        };
    };
    window.__audioExplainerReceive = function (jsonStr) {
        try { window.dispatchEvent(new MessageEvent('message', { data: JSON.parse(jsonStr) })); }
        catch (e) { console.error('audioExplainer receive failed', e); }
    };
})();
""".trim()

    /**
     * Build the live IDE theme as a `:root{…}` block. Injected LAST in the document head (after sidebar.css'
     * own `:root` fallback) so these live values win the cascade, and re-pushed on LAF changes via [pushTheme]
     * so the panel tracks light/dark switches while open (Phase 7.5 #10).
     */
    private fun themeVars(): String {
        fun hex(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
        val bg = UIUtil.getPanelBackground()
        val fg = UIUtil.getLabelForeground()
        val dim = UIUtil.getContextHelpForeground()
        val border = UIUtil.getBoundsColor()
        val accent = try {
            val h = AudioExplainerSettings.getInstance().state.accentColor.trim().removePrefix("#")
            Color(h.toInt(16))
        } catch (_: Exception) {
            Color(0xFFBE3C)
        }
        val panel = if (isDark(bg)) bg.darker() else bg.brighter()
        val btn = if (isDark(bg)) bg.brighter() else bg.darker()
        val btnHover = if (isDark(bg)) btn.brighter() else btn.darker()
        // A soft wash of the accent over the panel — used for the transcript read-along word highlight so the
        // moving cursor stays gentle and the text stays readable on top of it (Phase 7.5 #12).
        val accentSoft = blend(accent, panel, 0.34)
        return ":root{" +
            "--ae-bg:${hex(bg)};" +
            "--ae-panel:${hex(panel)};" +
            "--ae-fg:${hex(fg)};" +
            "--ae-fg-dim:${hex(dim)};" +
            "--ae-border:${hex(border)};" +
            "--ae-btn-bg:${hex(btn)};" +
            "--ae-btn-hover:${hex(btnHover)};" +
            "--ae-accent:${hex(accent)};" +
            "--ae-accent-soft:${hex(accentSoft)};" +
            "--ae-completed:#57965c;" +
            "}"
    }

    private fun isDark(c: Color): Boolean = (c.red * 0.299 + c.green * 0.587 + c.blue * 0.114) < 128

    /** Linear blend of two colours: [t] of [a] over (1-[t]) of [b], per channel. */
    private fun blend(a: Color, b: Color, t: Double): Color {
        fun ch(x: Int, y: Int) = (x * t + y * (1 - t)).toInt().coerceIn(0, 255)
        return Color(ch(a.red, b.red), ch(a.green, b.green), ch(a.blue, b.blue))
    }

    private fun readResource(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() } ?: ""

    private fun JsonObject.intField(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { if (!it.isString) it.int else null }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

    private fun JsonObject.doubleField(key: String): Double? =
        (this[key] as? JsonPrimitive)?.let { if (!it.isString) it.content.toDoubleOrNull() else null }
}
