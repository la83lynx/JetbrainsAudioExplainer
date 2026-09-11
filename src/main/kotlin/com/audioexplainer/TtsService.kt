package com.audioexplainer

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Robotic-voice tuning sent in a TTS `tune` request (mirrors [EspeakProfile]). `roughness` is
 * espeak-native (0 = smoothest). Runtime params (pitch/range/wordGap) + Layer-2 params
 * (consonants/roughness/flutter/breath) in one bundle; only used for `Engine.ESPEAK` voices.
 */
data class EspeakTune(
    val pitch: Int = 50,
    val range: Int = 50,
    val wordGap: Int = 1,
    val consonants: Int = 120,
    val roughness: Int = 0,
    val flutter: Int = 2,
    val breath: Int = 0,
) {
    companion object {
        fun from(p: EspeakProfile): EspeakTune =
            EspeakTune(p.pitch, p.range, p.wordGap, p.consonants, p.roughness, p.flutter, p.breath)
    }
}

/**
 * Phase 7 — the local Kokoro TTS backbone.
 *
 * Owns the lifecycle of the bundled `tts_server.py` (a persistent Kokoro-82M server over a TCP
 * loopback socket) and streams synthesized audio to callers. Also detects the runtime prerequisites
 * (Python + `kokoro-onnx` + the model files) and drives the guided setup (pip install + model
 * download) used by the first-run flow.
 *
 * The server is started lazily on the first [speak] and reused (the model loads once, ~seconds).
 * Each synthesis opens a fresh short-lived socket; cancellation closes it, so the Python side aborts
 * generation mid-stream. Audio arrives as base64 float32-LE PCM chunks — forwarded verbatim to the
 * webview, which decodes them straight into Web Audio.
 *
 * App-level so one server serves every project; disposed with the application (the child process is
 * killed on shutdown).
 */
@Service(Service.Level.APP)
class TtsService : Disposable {
    private val log = Logger.getInstance(TtsService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "audio-explainer-tts").apply { isDaemon = true }
    }

    private val serverLock = Any()
    private var serverHandler: OSProcessHandler? = null
    private var serverPort: Int = -1
    private var serverSynthetic: Boolean = false

    @Volatile private var cachedStatus: TtsStatus? = null
    @Volatile private var cachedCatalog: PiperCatalog? = null

    // ── Prerequisite detection ────────────────────────────────────────────────

    enum class Availability { READY, PYTHON_MISSING, KOKORO_MISSING, MODEL_MISSING, SCRIPT_MISSING, UNKNOWN }

    /**
     * One Piper voice model the user has downloaded, as reported by `--check`'s `piperVoices` (Phase L8).
     * [key] is the bare Piper voice key (e.g. "de_DE-eva_k-x_low"); [code] is the reduced walkthrough code
     * (the locale's family, e.g. "de"). The rest is display/diagnostic metadata for the settings panel.
     */
    data class InstalledPiperVoice(
        val key: String,
        val code: String,
        val displayName: String,
        val autonym: String,
        val quality: String?,
        val sampleRate: Int,
        val speakers: Int,
        val sizeBytes: Long,
        /** Speaker names by id for a multi-speaker model (Phase L9); empty for single-speaker. Often opaque
         *  numeric ids ("00737") — the UI falls back to "Speaker N" for those. */
        val speakerNames: List<String> = emptyList(),
    )

    /**
     * One voice in the Piper download catalog (the normalized `voices.json`, Phase L9) — what the voice-
     * manager dialog browses. [key] is the download key (e.g. "de_DE-thorsten-medium"); [code] the
     * walkthrough language code (the locale family); [sizeBytes] the total download size; [md5] the model's
     * published digest. Not necessarily installed — [TtsService.cachedPiperVoices] tells you which are.
     */
    data class PiperCatalogVoice(
        val key: String,
        val code: String,
        val locale: String?,
        val region: String?,
        val nameEnglish: String,
        val nameNative: String,
        val quality: String?,
        val speakers: Int,
        val speakerNames: List<String>,
        val sizeBytes: Long,
        val md5: String?,
    )

    /**
     * The Piper voice catalog plus where it came from (Phase L9): `source` is "network" (freshly fetched),
     * "cache" (served from the on-disk copy — [offline] true), or "none" (couldn't fetch and no cache).
     * [error] carries the fetch failure message when we fell back to cache / returned nothing.
     */
    data class PiperCatalog(
        val voices: List<PiperCatalogVoice>,
        val source: String,
        val error: String?,
    ) {
        /** True when these voices did NOT come from a fresh fetch (cached or empty). */
        val offline get() = source != "network"
    }

    data class TtsStatus(
        val availability: Availability,
        val python: String? = null,
        val onnxruntime: String? = null,
        val modelPath: String? = null,
        val detail: String? = null,
        /** Walkthrough language codes that can narrate right now (from `--check`'s `languagesReady`). */
        val languagesReady: List<String> = emptyList(),
        /** Whether the `misaki[ja]` / `misaki[zh]` packs (Japanese / Chinese) are importable. */
        val misakiJa: Boolean = false,
        val misakiZh: Boolean = false,
        /** espeak-ng robotic-tier language codes the local build supports (from `--check`'s `espeakLanguages`). */
        val espeakLanguages: List<String> = emptyList(),
        /** Whether the `piper-tts` package is importable (from `--check`'s `piperImportable`, Phase L8). */
        val piperImportable: Boolean = false,
        /** Piper voice models the user has downloaded (from `--check`'s `piperVoices`, Phase L8). */
        val piperVoices: List<InstalledPiperVoice> = emptyList(),
    ) {
        val ready get() = availability == Availability.READY
    }

    /** Non-blocking readiness from the last [status] probe (no subprocess). Null status ⇒ false. */
    fun cachedReady(): Boolean = cachedStatus?.ready == true

    /**
     * The last [status] result without probing (null if never probed) — for non-blocking UI (the settings
     * "Audio engines" status labels read this on the EDT; a background "Re-check" calls [status] with
     * `refresh = true` and then re-reads this).
     */
    fun cachedStatusOrNull(): TtsStatus? = cachedStatus

    /**
     * Language codes the last (cached) probe reported ready to narrate — no subprocess. Empty if never
     * probed. Used to annotate ja/zh in the settings combos until their misaki packs are installed (L5).
     */
    fun cachedLanguagesReady(): List<String> = cachedStatus?.languagesReady ?: emptyList()

    /**
     * espeak robotic-tier language codes the last (cached) probe reported — no subprocess. Empty if never
     * probed or espeak is unavailable. Used to confirm the local espeak build (Set up TTS) and, if needed,
     * to gate the robotic-tier menu to what's actually installed (L6).
     */
    fun cachedEspeakLanguages(): List<String> = cachedStatus?.espeakLanguages ?: emptyList()

    /** Whether the last (cached) probe found `piper-tts` importable — no subprocess. Phase L8. */
    fun cachedPiperImportable(): Boolean = cachedStatus?.piperImportable == true

    /** Piper voice models the last (cached) probe reported as installed — no subprocess. Empty if never
     *  probed. Used by the settings Piper panel + to register the voices in [Languages]. Phase L8. */
    fun cachedPiperVoices(): List<InstalledPiperVoice> = cachedStatus?.piperVoices ?: emptyList()

    // ── Windows SAPI voice discovery (Phase L7) ───────────────────────────────────

    @Volatile private var cachedSapiVoices: List<SapiVoice>? = null

    /**
     * Discover the installed Windows SAPI5 voices (Phase L7) via System.Speech and push them into
     * [Languages] (so the language menu + voice combo see them). **Windows-only** — elsewhere it returns
     * empty and clears any prior list. Cached; [refresh] re-probes (a ~0.3–1 s PowerShell call), so call
     * this OFF the EDT. Reaches whatever SAPI5 voices are installed, including the neural Edge "Online"
     * voices surfaced by NaturalVoiceSAPIAdapter (a third-party tool the user installs; we don't bundle it).
     */
    fun sapiVoices(refresh: Boolean = false): List<SapiVoice> {
        if (!refresh) cachedSapiVoices?.let { return it }
        val voices = if (SystemInfo.isWindows) probeSapiVoices() else emptyList()
        cachedSapiVoices = voices
        Languages.setSapiVoices(voices)
        return voices
    }

    /** Last-discovered SAPI voices without probing (empty if never discovered / non-Windows). */
    fun cachedSapiVoices(): List<SapiVoice> = cachedSapiVoices ?: emptyList()

    private fun probeSapiVoices(): List<SapiVoice> {
        val ps = powershellExe() ?: return emptyList()
        val script = runCatching {
            TtsService::class.java.getResourceAsStream("/com/audioexplainer/sapi_enum.ps1")
                ?.readBytes()?.toString(StandardCharsets.UTF_8)
        }.getOrNull() ?: return emptyList()
        // -EncodedCommand takes base64 of UTF-16LE — sidesteps all shell quoting of the script.
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))
        val out = runToCompletion(listOf(ps, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded), 30_000)
            ?: return emptyList()
        // The script prints one compact JSON array (or a bare object if a single voice) on stdout.
        val jsonText = out.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("[") || it.startsWith("{") } ?: return emptyList()
        val el = runCatching { json.parseToJsonElement(jsonText) }.getOrNull() ?: return emptyList()
        val objs = when (el) {
            is JsonArray -> el.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(el)
            else -> emptyList()
        }
        return objs.mapNotNull { toSapiVoice(it) }
    }

    private fun toSapiVoice(o: JsonObject): SapiVoice? {
        val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val culture = o["culture"]?.jsonPrimitive?.contentOrNull ?: return null
        // BCP-47 primary subtag is our language code: "en-US"→"en", "zh-CN"→"zh", "ta-IN"→"ta".
        val code = culture.substringBefore('-').lowercase().takeIf { it.isNotBlank() } ?: return null
        val english = o["english"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: code
        val native = o["native"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: english
        // Bare "Name (F)" label — the tier/online badge is appended uniformly by [Languages.engineTag]
        // (from the voice name), so it isn't baked in here and the menu entry reads "Aria (F) — WinSAPI, online".
        val base = name.removePrefix("Microsoft ").removeSuffix(" Online").removeSuffix(" Desktop").trim()
        val gender = when (o["gender"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "female" -> "F"; "male" -> "M"; else -> ""
        }
        val label = if (gender.isEmpty()) base else "$base ($gender)"
        return SapiVoice(voiceName = name, code = code, label = label, displayName = english, autonym = native)
    }

    /** Windows PowerShell 5.1 path (System.Speech is .NET Framework — pwsh 7 can't reliably load it). */
    private fun powershellExe(): String? {
        if (!SystemInfo.isWindows) return null
        val root = System.getenv("SystemRoot") ?: "C:\\Windows"
        val cand = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        return if (Files.isRegularFile(cand)) cand.toString() else "powershell.exe"
    }

    /** Cached prerequisite status. [refresh] re-runs the (subprocess) probe; cheap thereafter. */
    fun status(refresh: Boolean = false): TtsStatus {
        if (!refresh) cachedStatus?.let { return it }
        val s = probeStatus()
        cachedStatus = s
        syncPiperVoices(s.piperVoices)  // register discovered Piper voices in the language menus (Phase L8)
        return s
    }

    private fun probeStatus(): TtsStatus {
        val python = pythonCommand() ?: return TtsStatus(Availability.PYTHON_MISSING, detail = "No Python interpreter found on PATH.")
        val script = ensureScript() ?: return TtsStatus(Availability.SCRIPT_MISSING, python = python.first(), detail = "TTS server script could not be installed.")
        val out = runToCompletion(python + listOf(script.toString(), "--check") + modelDirArgs(), timeoutMs = 60_000)
            ?: return TtsStatus(Availability.UNKNOWN, python = python.first(), detail = "Prerequisite probe timed out.")
        val obj = out.lineSequence().mapNotNull { runCatching { json.parseToJsonElement(it.trim()).jsonObject }.getOrNull() }
            .lastOrNull { it["python"] != null || it["ready"] != null }
            ?: return TtsStatus(Availability.UNKNOWN, python = python.first(), detail = "Could not parse prerequisite probe output.")
        val kokoro = obj["kokoroImportable"]?.jsonPrimitive?.booleanOrNull ?: false
        val model = (obj["modelPresent"]?.jsonPrimitive?.booleanOrNull ?: false) &&
            (obj["voicesPresent"]?.jsonPrimitive?.booleanOrNull ?: false)
        val availability = when {
            !kokoro -> Availability.KOKORO_MISSING
            !model -> Availability.MODEL_MISSING
            else -> Availability.READY
        }
        val languagesReady = runCatching {
            obj["languagesReady"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        }.getOrDefault(emptyList())
        val espeakLanguages = runCatching {
            obj["espeakLanguages"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        }.getOrDefault(emptyList())
        val piperVoices = runCatching { parsePiperVoices(obj["piperVoices"]?.jsonArray) }.getOrDefault(emptyList())
        return TtsStatus(
            availability,
            python = obj["python"]?.jsonPrimitive?.contentOrNull ?: python.first(),
            onnxruntime = obj["onnxruntime"]?.jsonPrimitive?.contentOrNull,
            modelPath = obj["modelPath"]?.jsonPrimitive?.contentOrNull,
            detail = obj["kokoroError"]?.jsonPrimitive?.contentOrNull,
            languagesReady = languagesReady,
            misakiJa = obj["misakiJa"]?.jsonPrimitive?.booleanOrNull ?: false,
            misakiZh = obj["misakiZh"]?.jsonPrimitive?.booleanOrNull ?: false,
            espeakLanguages = espeakLanguages,
            piperImportable = obj["piperImportable"]?.jsonPrimitive?.booleanOrNull ?: false,
            piperVoices = piperVoices,
        )
    }

    /** Parse `--check`'s `piperVoices` array into [InstalledPiperVoice]s (Phase L8). Reduces the locale to a
     *  walkthrough code (the model's `family`, else the locale prefix, else the key prefix). */
    private fun parsePiperVoices(arr: JsonArray?): List<InstalledPiperVoice> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val key = o["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val locale = o["lang"]?.jsonPrimitive?.contentOrNull
            val code = o["family"]?.jsonPrimitive?.contentOrNull
                ?: locale?.substringBefore('_')?.takeIf { it.isNotBlank() }
                ?: key.substringBefore('_')
            val english = o["nameEnglish"]?.jsonPrimitive?.contentOrNull ?: code
            InstalledPiperVoice(
                key = key,
                code = code,
                displayName = english,
                autonym = o["nameNative"]?.jsonPrimitive?.contentOrNull ?: english,
                quality = o["quality"]?.jsonPrimitive?.contentOrNull,
                sampleRate = o["sampleRate"]?.jsonPrimitive?.intOrNull ?: 22050,
                speakers = o["speakers"]?.jsonPrimitive?.intOrNull ?: 1,
                sizeBytes = o["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                speakerNames = parseStringArray(o["speakerNames"]?.jsonArray),
            )
        }
    }

    /** A JSON array of (possibly null) strings → a `List<String>` with nulls kept as "" so positional index
     *  = speaker id is preserved (Phase L9). */
    private fun parseStringArray(arr: JsonArray?): List<String> =
        arr?.map { it.jsonPrimitive.contentOrNull ?: "" } ?: emptyList()

    /** Parse `--piper-catalog`'s `voices` array into [PiperCatalogVoice]s (Phase L9). */
    private fun parseCatalogVoices(arr: JsonArray?): List<PiperCatalogVoice> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val key = o["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val locale = o["locale"]?.jsonPrimitive?.contentOrNull
            val code = o["family"]?.jsonPrimitive?.contentOrNull
                ?: locale?.substringBefore('_')?.takeIf { it.isNotBlank() }
                ?: key.substringBefore('_')
            val english = o["nameEnglish"]?.jsonPrimitive?.contentOrNull ?: code
            PiperCatalogVoice(
                key = key,
                code = code,
                locale = locale,
                region = o["region"]?.jsonPrimitive?.contentOrNull,
                nameEnglish = english,
                nameNative = o["nameNative"]?.jsonPrimitive?.contentOrNull ?: english,
                quality = o["quality"]?.jsonPrimitive?.contentOrNull,
                speakers = o["speakers"]?.jsonPrimitive?.intOrNull ?: 1,
                speakerNames = parseStringArray(o["speakerNames"]?.jsonArray),
                sizeBytes = o["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                md5 = o["md5"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    /** UI label for a Piper voice key + quality, e.g. "de_DE-eva_k-x_low" → "Eva K (x_low)". */
    private fun piperLabel(key: String, quality: String?): String {
        val name = key.split('-').getOrNull(1)?.split('_')
            ?.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } } ?: key
        return if (quality != null) "$name ($quality)" else name
    }

    /**
     * Push the given installed Piper voices into [Languages] so the menus + voice combo see them (Phase L8;
     * multi-speaker expansion L9). A single-speaker model becomes one voice; a multi-speaker model is expanded
     * into one voice per speaker (id suffix `#<n>`, label "… — Speaker n"), so the dropdown *is* the speaker
     * picker. A model with more than [MAX_EXPANDED_SPEAKERS] speakers (rare research sets — VCTK/libriTTS)
     * would blow up the dropdown, so only its default speaker is offered. Called after each fresh `--check`;
     * safe to call from any thread.
     */
    private fun syncPiperVoices(voices: List<InstalledPiperVoice>) {
        val out = ArrayList<PiperVoice>()
        for (v in voices) {
            val base = piperLabel(v.key, v.quality)
            when {
                v.speakers <= 1 ->
                    out.add(PiperVoice(v.key, v.code, base, v.displayName, v.autonym))
                v.speakers <= MAX_EXPANDED_SPEAKERS ->
                    for (i in 0 until v.speakers) {
                        out.add(PiperVoice(
                            "${v.key}#$i", v.code, "$base — ${speakerLabel(v.speakerNames, i)}",
                            v.displayName, v.autonym))
                    }
                else ->
                    out.add(PiperVoice(
                        v.key, v.code, "$base — ${speakerLabel(v.speakerNames, 0)} of ${v.speakers}",
                        v.displayName, v.autonym))
            }
        }
        Languages.setPiperVoices(out)
    }

    /** Human label for speaker [i]: the model's speaker name if it's meaningful (not a blank / opaque numeric
     *  id), else "Speaker N" (1-based). Phase L9. */
    private fun speakerLabel(names: List<String>, i: Int): String {
        val nm = names.getOrNull(i)?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
        return nm ?: "Speaker ${i + 1}"
    }

    // ── Guided setup (first-run / Tools action) ────────────────────────────────

    /** `python -m pip install …` the TTS dependencies, surfacing progress on [indicator]. */
    fun installDependencies(indicator: ProgressIndicator): Boolean {
        val python = pythonCommand() ?: return false
        indicator.text = "Installing Kokoro TTS dependencies (pip)…"
        val cmd = python + listOf("-m", "pip", "install", "--disable-pip-version-check", "kokoro-onnx", "onnxruntime", "numpy")
        val ok = streamToIndicator(cmd, indicator) { line -> indicator.text2 = tailLine(line) }
        cachedStatus = null
        return ok
    }

    /**
     * `pip install misaki[ja] / misaki[zh]` — the extra packs Japanese/Chinese narration needs (Phase L5).
     * Base `kokoro-onnx` does not pull these; they add large transitive deps (fugashi/unidic for Japanese,
     * jieba for Chinese), so they're an explicit opt-in surfaced via **Set up TTS**. [langs] are language
     * codes (`"ja"`, `"zh"`); unknown codes are ignored. Returns true on a clean `pip` exit.
     */
    fun installLanguagePacks(indicator: ProgressIndicator, langs: List<String>): Boolean {
        val python = pythonCommand() ?: return false
        val specs = langs.mapNotNull { when (it) { "ja" -> "misaki[ja]"; "zh" -> "misaki[zh]"; else -> null } }.distinct()
        if (specs.isEmpty()) return true
        indicator.text = "Installing Japanese/Chinese TTS packs (misaki — large download)…"
        val cmd = python + listOf("-m", "pip", "install", "--disable-pip-version-check") + specs
        val ok = streamToIndicator(cmd, indicator) { line -> indicator.text2 = tailLine(line) }
        cachedStatus = null
        return ok
    }

    /** Download the Kokoro model + voices, surfacing per-file percentage on [indicator]. */
    fun downloadModel(indicator: ProgressIndicator): Boolean {
        val python = pythonCommand() ?: return false
        val script = ensureScript() ?: return false
        indicator.text = "Downloading the Kokoro voice model (~340 MB)…"
        val cmd = python + listOf(script.toString(), "--download") + modelDirArgs()
        val ok = streamToIndicator(cmd, indicator) { line ->
            val obj = runCatching { json.parseToJsonElement(line.trim()).jsonObject }.getOrNull() ?: return@streamToIndicator
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "progress" -> {
                    val file = obj["file"]?.jsonPrimitive?.contentOrNull ?: "model"
                    val pct = obj["pct"]?.jsonPrimitive?.intOrNull ?: 0
                    indicator.text2 = "$file — $pct%"
                    if (!indicator.isIndeterminate) indicator.fraction = pct / 100.0
                }
                "downloaded" -> indicator.text2 = "Saved ${obj["file"]?.jsonPrimitive?.contentOrNull}"
                "error" -> indicator.text2 = "Error: ${obj["message"]?.jsonPrimitive?.contentOrNull}"
            }
        }
        cachedStatus = null
        return ok
    }

    /** `python -m pip install piper-tts` — the offline-neural tier's package (Phase L8). Additive: it reuses
     *  the onnxruntime/numpy Kokoro already pulled, so it won't disturb the Kokoro stack. */
    fun installPiper(indicator: ProgressIndicator): Boolean {
        val python = pythonCommand() ?: return false
        indicator.text = "Installing Piper (pip)…"
        val cmd = python + listOf("-m", "pip", "install", "--disable-pip-version-check", "piper-tts")
        val ok = streamToIndicator(cmd, indicator) { line -> indicator.text2 = tailLine(line) }
        cachedStatus = null
        return ok
    }

    /** Download one Piper voice model into the models dir via `--download-piper <key>`, surfacing per-file
     *  percentage on [indicator] (same frames as [downloadModel]). Phase L8. */
    fun downloadPiperVoice(indicator: ProgressIndicator, voiceKey: String): Boolean {
        val python = pythonCommand() ?: return false
        val script = ensureScript() ?: return false
        indicator.text = "Downloading Piper voice “$voiceKey”…"
        val cmd = python + listOf(script.toString(), "--download-piper", voiceKey) + modelDirArgs()
        val ok = streamToIndicator(cmd, indicator) { line ->
            val obj = runCatching { json.parseToJsonElement(line.trim()).jsonObject }.getOrNull() ?: return@streamToIndicator
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "progress" -> {
                    val file = obj["file"]?.jsonPrimitive?.contentOrNull ?: "model"
                    val pct = obj["pct"]?.jsonPrimitive?.intOrNull ?: 0
                    indicator.text2 = "$file — $pct%"
                    if (!indicator.isIndeterminate) indicator.fraction = pct / 100.0
                }
                "downloaded" -> indicator.text2 = "Saved ${obj["file"]?.jsonPrimitive?.contentOrNull}"
                "error" -> indicator.text2 = "Error: ${obj["message"]?.jsonPrimitive?.contentOrNull}"
            }
        }
        cachedStatus = null
        return ok
    }

    /**
     * The Piper download catalog (Phase L9) via `--piper-catalog`, cached in memory. [refresh] forces a
     * re-fetch (`--refresh`, ~1–3 s network); otherwise the server serves its on-disk cache and this the
     * in-memory copy, so the manager dialog re-opens instantly. Run OFF the EDT (it spawns a subprocess).
     */
    fun piperCatalog(refresh: Boolean = false): PiperCatalog {
        if (!refresh) cachedCatalog?.let { return it }
        val python = pythonCommand()
            ?: return PiperCatalog(emptyList(), "none", "No Python interpreter found on PATH.")
        val script = ensureScript()
            ?: return PiperCatalog(emptyList(), "none", "The TTS server script could not be installed.")
        val args = python + listOf(script.toString(), "--piper-catalog") +
            (if (refresh) listOf("--refresh") else emptyList()) + modelDirArgs()
        val out = runToCompletion(args, timeoutMs = 90_000)
            ?: return PiperCatalog(emptyList(), "none", "The catalog fetch timed out.")
        val obj = out.lineSequence()
            .mapNotNull { runCatching { json.parseToJsonElement(it.trim()).jsonObject }.getOrNull() }
            .lastOrNull { it["voices"] != null }
            ?: return PiperCatalog(emptyList(), "none", "Could not parse the catalog output.")
        val cat = PiperCatalog(
            voices = parseCatalogVoices(obj["voices"]?.jsonArray),
            source = obj["source"]?.jsonPrimitive?.contentOrNull ?: "none",
            error = obj["error"]?.jsonPrimitive?.contentOrNull,
        )
        cachedCatalog = cat
        return cat
    }

    /** Delete one installed Piper voice's model files via `--delete-piper <key>` (Phase L9). Invalidates the
     *  cached status so the next probe re-discovers. Returns true on a clean `done`. Run OFF the EDT. */
    fun deletePiperVoice(voiceKey: String): Boolean {
        val python = pythonCommand() ?: return false
        val script = ensureScript() ?: return false
        val out = runToCompletion(
            python + listOf(script.toString(), "--delete-piper", voiceKey) + modelDirArgs(), 30_000)
            ?: return false
        val ok = out.lineSequence()
            .mapNotNull { runCatching { json.parseToJsonElement(it.trim()).jsonObject }.getOrNull() }
            .any { it["type"]?.jsonPrimitive?.contentOrNull == "done" }
        cachedStatus = null
        return ok
    }

    // ── Synthesis ───────────────────────────────────────────────────────────────

    interface TtsListener {
        fun onMeta(sampleRate: Int) {}
        /**
         * A PCM frame, plus its sentence index, that sentence's word count, and the sentence **as spoken**
         * (all three for the webview: the first two drive the read-along, the third lets a B1b beat match a
         * phrase against real output instead of counting sentences nobody counts the same way).
         *
         * [sentenceText] is empty against a `tts_server.py` older than the field — the installed copy is
         * refreshed only on a VERSION difference and is editable on disk, so its absence is a normal state,
         * not an error.
         */
        fun onChunk(base64: String, sampleRate: Int, sentenceIndex: Int, sentenceWords: Int, sentenceText: String)
        fun onEnd() {}
        fun onError(message: String) {}
    }

    /** Handle to an in-flight synthesis; [cancel] aborts it (closes the socket → Python stops). */
    class TtsStream {
        @Volatile var isCancelled = false
            private set
        private var socket: Socket? = null

        @Synchronized fun attach(s: Socket) {
            socket = s
            if (isCancelled) runCatching { s.close() }
        }

        @Synchronized fun cancel() {
            isCancelled = true
            runCatching { socket?.close() }
        }
    }

    /**
     * Synthesize [text] and stream chunks to [listener]. Non-blocking: returns a [TtsStream] handle
     * immediately; all I/O (server start, socket, read loop) runs on a pooled thread. Callbacks fire
     * off the EDT — the narration controller marshals webview pushes itself.
     */
    fun speak(
        text: String,
        voice: String,
        speed: Double,
        tune: EspeakTune?,
        listener: TtsListener,
    ): TtsStream {
        val stream = TtsStream()
        executor.submit {
            try {
                val port = ensureServer()
                if (port == null) {
                    if (!stream.isCancelled) listener.onError(SERVER_UNAVAILABLE)
                    return@submit
                }
                if (stream.isCancelled) return@submit
                val socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                stream.attach(socket)
                if (stream.isCancelled) return@submit

                val req = buildJsonObject {
                    put("text", text)
                    put("speed", speed)
                    // Engine is per-VOICE. Kokoro voices omit "engine" and use the primary engine; espeak
                    // (robotic tier, L6), SAPI (Windows System.Speech tier, L7) and Piper (offline-neural
                    // tier, L8) each set their own.
                    when (Languages.engineOfVoice(voice)) {
                        Engine.SAPI -> {
                            // The Windows tier's `voice` is the bare SAPI5 name (the `sapi:<code>:` prefix is
                            // plugin-side only); `lang` is a hint. No tune (SAPI has no espeak knobs).
                            put("engine", "sapi")
                            put("voice", Languages.sapiVoiceName(voice))
                            put("lang", Languages.languageOfVoice(voice))
                        }
                        Engine.ESPEAK -> {
                            // The voice spec ("de", "de+ae_f") is what espeak synthesizes with; `lang` is a
                            // redundant hint. Robotic tuning: runtime params (pitch/range/wordgap) + Layer-2
                            // (consonants/roughness/flutter/breath) — the server clamps + regenerates the variant.
                            put("voice", voice)
                            put("engine", "espeak")
                            put("lang", Languages.languageOfVoice(voice))
                            val t = tune ?: EspeakTune()
                            putJsonObject("tune") {
                                put("pitch", t.pitch)
                                put("range", t.range)
                                put("wordgap", t.wordGap)
                                put("consonants", t.consonants)
                                put("roughness", t.roughness)
                                put("flutter", t.flutter)
                                put("breath", t.breath)
                            }
                        }
                        Engine.PIPER -> {
                            // The offline-neural tier's `voice` is the bare Piper voice key (the
                            // `piper:<code>:` prefix is plugin-side only); `lang` is a hint. No tune.
                            put("engine", "piper")
                            put("voice", Languages.piperVoiceKey(voice))
                            put("lang", Languages.languageOfVoice(voice))
                        }
                        Engine.KOKORO -> put("voice", voice)
                    }
                }
                socket.getOutputStream().apply {
                    write((req.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
                    flush()
                }
                readStream(socket.getInputStream().bufferedReader(StandardCharsets.UTF_8), stream, listener)
            } catch (e: Exception) {
                if (!stream.isCancelled) {
                    log.warn("TTS synthesis failed", e)
                    listener.onError(e.message ?: "TTS synthesis failed")
                }
            } finally {
                stream.cancel()
            }
        }
        return stream
    }

    private fun readStream(reader: BufferedReader, stream: TtsStream, listener: TtsListener) {
        while (!stream.isCancelled) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val obj = runCatching { json.parseToJsonElement(line.trim()).jsonObject }.getOrNull() ?: continue
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "meta" -> listener.onMeta(obj["sampleRate"]?.jsonPrimitive?.intOrNull ?: 24_000)
                "chunk" -> {
                    val b64 = obj["audio"]?.jsonPrimitive?.contentOrNull ?: continue
                    listener.onChunk(
                        b64,
                        obj["sampleRate"]?.jsonPrimitive?.intOrNull ?: 24_000,
                        obj["si"]?.jsonPrimitive?.intOrNull ?: 0,
                        obj["sw"]?.jsonPrimitive?.intOrNull ?: 1,
                        obj["stext"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                }
                "end" -> { if (!stream.isCancelled) listener.onEnd(); return }
                "error" -> { if (!stream.isCancelled) listener.onError(obj["message"]?.jsonPrimitive?.contentOrNull ?: "TTS error"); return }
            }
        }
    }

    /** A liveness ping to the running server (starts it if needed). Returns true on a `pong`. */
    fun ping(): Boolean {
        val port = ensureServer() ?: return false
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                s.getOutputStream().apply { write("{\"ping\":true}\n".toByteArray()); flush() }
                val line = s.getInputStream().bufferedReader().readLine()
                val obj = line?.let { runCatching { json.parseToJsonElement(it.trim()).jsonObject }.getOrNull() }
                obj?.get("type")?.jsonPrimitive?.contentOrNull == "pong"
            }
        } catch (_: Exception) {
            false
        }
    }

    // ── Server lifecycle ─────────────────────────────────────────────────────────

    /**
     * Ensure the TTS server process is up and return its port, or null if it can't start (bad
     * prerequisites). Reuses a live process. Synthetic mode (for internal testing) is off by default;
     * production always launches the real Kokoro engine.
     */
    private fun ensureServer(synthetic: Boolean = false): Int? {
        synchronized(serverLock) {
            val h = serverHandler
            if (h != null && !h.isProcessTerminated && serverPort > 0 && serverSynthetic == synthetic) {
                return serverPort
            }
            stopServerLocked()
            return startServerLocked(synthetic)
        }
    }

    private fun startServerLocked(synthetic: Boolean): Int? {
        val python = pythonCommand() ?: return null
        val script = ensureScript() ?: return null
        val params = buildList {
            add(script.toString())
            add("--host"); add("127.0.0.1")
            add("--port"); add("0")
            addAll(modelDirArgs())
            if (synthetic) add("--synthetic")
        }
        val cmd = try {
            GeneralCommandLine(python)
                .withParameters(params)
                .withCharset(StandardCharsets.UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        } catch (e: Exception) {
            log.warn("failed to build TTS server command", e)
            return null
        }

        val portRef = AtomicReference<Int?>(null)
        val ready = CountDownLatch(1)
        val handler = OSProcessHandler(cmd)
        val stdoutBuf = StringBuilder()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType != ProcessOutputTypes.STDOUT) return
                if (portRef.get() != null) return // handshake already consumed
                stdoutBuf.append(event.text)
                var nl = stdoutBuf.indexOf("\n")
                while (nl >= 0) {
                    val line = stdoutBuf.substring(0, nl).trim()
                    stdoutBuf.delete(0, nl + 1)
                    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    if (obj != null && obj.containsKey("ready")) {
                        if (obj["ready"]?.jsonPrimitive?.booleanOrNull == true) {
                            portRef.set(obj["port"]?.jsonPrimitive?.intOrNull ?: -1)
                        }
                        ready.countDown()
                        return
                    }
                    nl = stdoutBuf.indexOf("\n")
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                ready.countDown()
            }
        })
        handler.startNotify()

        // First launch pays the model-load cost (onnxruntime init + weights); be patient.
        if (!ready.await(120, TimeUnit.SECONDS)) {
            log.warn("TTS server did not signal ready within timeout")
            runCatching { handler.destroyProcess() }
            return null
        }
        val port = portRef.get()
        if (port == null || port <= 0) {
            log.warn("TTS server failed to start (no port in handshake)")
            runCatching { handler.destroyProcess() }
            return null
        }
        serverHandler = handler
        serverPort = port
        serverSynthetic = synthetic
        log.info("TTS server ready on 127.0.0.1:$port (synthetic=$synthetic)")
        return port
    }

    private fun stopServerLocked() {
        serverHandler?.let { runCatching { it.destroyProcess() } }
        serverHandler = null
        serverPort = -1
    }

    /** Stop the server (e.g. after a settings change); it restarts on the next [speak]. */
    fun restart() {
        synchronized(serverLock) { stopServerLocked() }
        cachedStatus = null
    }

    override fun dispose() {
        synchronized(serverLock) { stopServerLocked() }
        executor.shutdownNow()
    }

    // ── Process helpers ──────────────────────────────────────────────────────────

    private fun modelDirArgs(): List<String> {
        val dir = AudioExplainerSettings.getInstance().state.kokoroModelDir.trim()
        return if (dir.isEmpty()) emptyList() else listOf("--model-dir", dir)
    }

    /** Ensure the skill (which carries `scripts/tts_server.py`) is installed; return the script path. */
    private fun ensureScript(): Path? {
        val skill = ClaudeSkillInstaller.ensureInstalled() ?: return null
        val script = skill.resolve("scripts").resolve("tts_server.py")
        return if (Files.isRegularFile(script)) script else null
    }

    /**
     * Locate a Python interpreter robustly (see [ExecutableFinder]): an explicit settings path, then
     * `python`/`python3` on PATH (trying `.exe` on Windows), then the `py` launcher. Returns the argv
     * prefix (usually one element, or `["…\\py.exe", "-3"]`).
     */
    private fun pythonCommand(): List<String>? {
        val override = AudioExplainerSettings.getInstance().state.pythonExecutablePath
        ExecutableFinder.find("python", override, emptyList())?.let { return listOf(it.toString()) }
        ExecutableFinder.find("python3", null, emptyList())?.let { return listOf(it.toString()) }
        ExecutableFinder.find("py", null, emptyList())?.let { return listOf(it.toString(), "-3") }
        return null
    }

    /** Run a command to completion, returning combined stdout+stderr, or null on timeout/failure. */
    private fun runToCompletion(argv: List<String>, timeoutMs: Long): String? {
        return try {
            val cmd = GeneralCommandLine(argv)
                .withCharset(StandardCharsets.UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            val handler = OSProcessHandler(cmd)
            val buf = StringBuilder()
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    synchronized(buf) { buf.append(event.text) }
                }
            })
            handler.startNotify()
            if (!handler.waitFor(timeoutMs)) {
                runCatching { handler.destroyProcess() }
                return null
            }
            synchronized(buf) { buf.toString() }
        } catch (e: Exception) {
            log.warn("subprocess failed: ${argv.firstOrNull()}", e)
            null
        }
    }

    /** Run a command, feeding each stdout/stderr line to [onLine]; returns true on exit code 0. */
    private fun streamToIndicator(argv: List<String>, indicator: ProgressIndicator, onLine: (String) -> Unit): Boolean {
        return try {
            val cmd = GeneralCommandLine(argv)
                .withCharset(StandardCharsets.UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            val handler = OSProcessHandler(cmd)
            val buf = StringBuilder()
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    synchronized(buf) {
                        buf.append(event.text)
                        var nl = buf.indexOf("\n")
                        while (nl >= 0) {
                            val line = buf.substring(0, nl)
                            buf.delete(0, nl + 1)
                            if (line.isNotBlank()) runCatching { onLine(line) }
                            nl = buf.indexOf("\n")
                        }
                    }
                }
            })
            handler.startNotify()
            while (!handler.waitFor(200)) {
                if (indicator.isCanceled) {
                    runCatching { handler.destroyProcess() }
                    return false
                }
            }
            (handler.exitCode ?: -1) == 0
        } catch (e: Exception) {
            log.warn("streamed subprocess failed: ${argv.firstOrNull()}", e)
            false
        }
    }

    private fun tailLine(s: String, max: Int = 90): String {
        val t = s.trim()
        return if (t.length <= max) t else "…" + t.substring(t.length - max)
    }

    companion object {
        fun getInstance(): TtsService = service()

        /**
         * Sentinel [TtsListener.onError] message for "the server never started" (bad Kokoro prerequisites),
         * as opposed to a per-voice synthesis failure. Callers diagnosing an error distinguish this from an
         * engine-specific failure (e.g. an online SAPI voice with no network) by matching this exact string.
         */
        const val SERVER_UNAVAILABLE = "Kokoro TTS server is not available."

        /** Max speakers a multi-speaker Piper model is expanded into individual dropdown voices (Phase L9).
         *  Above this (rare research sets like VCTK/libriTTS with 50–900 speakers) only the default speaker
         *  is offered, to keep the voice dropdown usable. */
        const val MAX_EXPANDED_SPEAKERS = 24

        @Suppress("unused")
        private val counter = AtomicInteger(0) // reserved for future per-request tracing
    }
}
