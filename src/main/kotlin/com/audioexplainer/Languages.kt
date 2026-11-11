package com.audioexplainer

/**
 * Canonical language / voice registry — the single source of truth for the multi-language feature.
 * The Python TTS server (`scripts/tts_server.py` `PREFIX_LANG`)
 * and the webview voice combo derive from this; keep the voice-prefix → language mapping in
 * [languageOfVoice] in sync with the Python side.
 *
 * Four tiers ([Voice.engine] — engine is per-VOICE, not per-language):
 * - [Engine.KOKORO] — the natural/neural tier, exactly Kokoro-82M's trained set (8 languages / 9 accents).
 *   Each ships a fixed voice list (one French voice to ~20 for US English); the voice-name convention is
 *   **1st letter = language/accent, 2nd letter = gender (`f`/`m`)**. US vs UK English is one *text* language
 *   (`en`) with two voice groups — accent is a voice choice. Japanese and Chinese need the extra
 *   `misaki[ja]` / `misaki[zh]` pip packs ([Language.requiresExtraDeps]).
 * - [Engine.ESPEAK] — the robotic fallback tier: a curated set of the ~100 other languages espeak-ng can
 *   voice (bundled with `kokoro-onnx`, zero extra download). The "voice" is the language code plus an
 *   optional `+ae_f`/`+ae_m` variant (our custom clean variants); the plugin routes these to the
 *   server's `engine=espeak`.
 * - [Engine.SAPI] — the Windows System.Speech tier (Windows only). Unlike the two above this is
 *   **dynamic**: the voice set is whatever SAPI5 voices the machine has, discovered at runtime by
 *   [TtsService] and pushed in via [setSapiVoices]. Reaches the classic offline Windows voices and — once
 *   the user installs NaturalVoiceSAPIAdapter — the neural Edge "Online" voices for ~140 locales, which can
 *   introduce brand-new walkthrough languages the static registry never listed. Ids are `sapi:<code>:<name>`
 *   (see [sapiVoiceName]); the plugin routes these to the server's `engine=sapi`.
 * - [Engine.PIPER] — the offline-neural tier (cross-platform). Also **dynamic**: the voice set is
 *   whichever Piper models the user has downloaded, discovered at runtime by [TtsService] and pushed in via
 *   [setPiperVoices]. Covers the ~40 languages Kokoro can't voice at neural quality, fully offline. Like the
 *   SAPI tier it can introduce brand-new walkthrough languages. Ids are `piper:<code>:<voiceKey>` (see
 *   [piperVoiceKey]); the plugin routes these to the server's `engine=piper`.
 */

/** One selectable TTS voice. `id` is the voice spec: a Kokoro name ("ef_dora"), an espeak spec ("de+ae_f"),
 *  a SAPI id "sapi:<code>:<VoiceName>" ("sapi:en:Microsoft Aria Online"), or a Piper id
 *  "piper:<code>:<voiceKey>" ("piper:de:de_DE-eva_k-x_low"). `label` is UI text; `engine` is the synthesizer
 *  that voices it. Engine is per-VOICE (not per-language) so a Kokoro-tier language can also offer robotic
 *  (espeak), Windows (SAPI) and Piper voices in its list. */
data class Voice(val id: String, val label: String, val engine: Engine = Engine.KOKORO)

/**
 * Which local synthesizer voices a language:
 * - [KOKORO] — the neural, natural-sounding tier (Kokoro-82M's 8 trained languages).
 * - [ESPEAK] — the robotic-but-intelligible fallback tier (espeak-ng's ~100 other languages), bundled
 *   with `kokoro-onnx` (zero extra download). The plugin routes these to the server's `engine=espeak`.
 * - [SAPI] — the Windows System.Speech tier (Windows only) — whatever SAPI5 voices are installed,
 *   discovered at runtime. The plugin routes these to the server's `engine=sapi`.
 * - [PIPER] — the offline-neural tier (cross-platform) — Piper VITS ONNX voices covering the ~40
 *   languages Kokoro can't voice, each a per-voice model downloaded on demand and discovered at runtime
 *   (like SAPI). The plugin routes these to the server's `engine=piper`.
 */
enum class Engine { KOKORO, ESPEAK, SAPI, PIPER }

/**
 * A Windows SAPI5 voice discovered at runtime (by [TtsService]) and its attached walkthrough language.
 * Fed to [Languages.setSapiVoices]. [voiceName] is the raw SAPI name the server synthesizes with;
 * [displayName]/[autonym] carry the language's names (from .NET `CultureInfo`) so a SAPI-only language
 * (one the static registry never listed) can still show a proper menu entry + badge.
 */
data class SapiVoice(
    val voiceName: String,
    val code: String,
    val label: String,
    val displayName: String,
    val autonym: String,
)

/**
 * A Piper voice model the user has downloaded, discovered at runtime (by [TtsService] parsing the server's
 * `--check` `piperVoices`) and its attached walkthrough language. Fed to [Languages.setPiperVoices].
 * [voiceKey] is the bare Piper key the server synthesizes with (e.g. "de_DE-eva_k-x_low"); [code] is the
 * reduced walkthrough code (the locale's family, e.g. "de"); [displayName]/[autonym] carry the language's
 * English + native names (from the model's `.onnx.json`) so a Piper-only language (one the static registry
 * never listed) can still show a proper menu entry + badge.
 */
data class PiperVoice(
    val voiceKey: String,
    val code: String,
    val label: String,
    val displayName: String,
    val autonym: String,
)

/** A supported explanation/narration language and its eligible voices. */
data class Language(
    /** Walkthrough `language` code, e.g. "en","es","ja" (Kokoro) or "de","ru","ko" (espeak). */
    val code: String,
    /** English display name for the (English-only) UI, e.g. "Spanish". */
    val displayName: String,
    /** Native name, shown as the language badge, e.g. "Español". */
    val autonym: String,
    /** Eligible voices; for "en" the US and UK groups are merged; espeak-tier languages get RoboSynth LoQ (F/M),
     *  and Kokoro-tier languages append robotic (espeak) voices so any language can be narrated robotically. */
    val voices: List<Voice>,
    /** Voice selected when a walkthrough of this language loads and no valid voice is remembered. */
    val defaultVoice: String,
    /** True for ja/zh — needs the heavier misaki packs installed before it can narrate. */
    val requiresExtraDeps: Boolean = false,
    /** Synthesizer tier for this language. [Engine.ESPEAK] languages are the robotic ~100-language fallback. */
    val engine: Engine = Engine.KOKORO,
)

object Languages {
    const val DEFAULT_CODE = "en"

    /** The English (default) editor/Solution-Explorer action label; also the base for the fallback form. */
    const val DEFAULT_EXPLAIN_LABEL = "Explain This (Audio)"

    /** Prefix marking a Windows SAPI voice id: `sapi:<langCode>:<VoiceName>`. */
    const val SAPI_PREFIX = "sapi:"

    /** Prefix marking a Piper voice id: `piper:<langCode>:<voiceKey>`. */
    const val PIPER_PREFIX = "piper:"

    val ALL: List<Language> = listOf(
        Language(
            code = "en", displayName = "English", autonym = "English",
            voices = voices(
                // US (af_/am_)
                "af_heart", "af_bella", "af_sarah", "af_alloy", "af_aoede", "af_jessica",
                "af_kore", "af_nicole", "af_nova", "af_river", "af_sky",
                "am_adam", "am_michael", "am_echo", "am_eric", "am_fenrir",
                "am_liam", "am_onyx", "am_puck", "am_santa",
                // UK (bf_/bm_)
                "bf_emma", "bf_alice", "bf_isabella", "bf_lily",
                "bm_george", "bm_daniel", "bm_fable", "bm_lewis",
            ) + roboticVoices("en"),
            defaultVoice = "af_heart",
        ),
        Language(
            code = "es", displayName = "Spanish", autonym = "Español",
            voices = voices("ef_dora", "em_alex", "em_santa") + roboticVoices("es"),
            defaultVoice = "ef_dora",
        ),
        Language(
            code = "fr", displayName = "French", autonym = "Français",
            voices = voices("ff_siwis") + roboticVoices("fr"),
            defaultVoice = "ff_siwis",
        ),
        Language(
            code = "hi", displayName = "Hindi", autonym = "हिन्दी",
            voices = voices("hf_alpha", "hf_beta", "hm_omega", "hm_psi") + roboticVoices("hi"),
            defaultVoice = "hf_alpha",
        ),
        Language(
            code = "it", displayName = "Italian", autonym = "Italiano",
            voices = voices("if_sara", "im_nicola") + roboticVoices("it"),
            defaultVoice = "if_sara",
        ),
        Language(
            code = "pt", displayName = "Portuguese", autonym = "Português",
            voices = voices("pf_dora", "pm_alex", "pm_santa") + roboticVoices("pt"),
            defaultVoice = "pf_dora",
        ),
        Language(
            code = "ja", displayName = "Japanese", autonym = "日本語",
            voices = voices("jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo"),
            defaultVoice = "jf_alpha", requiresExtraDeps = true,
        ),
        Language(
            code = "zh", displayName = "Chinese", autonym = "中文",
            voices = voices(
                "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi",
                "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
            ),
            defaultVoice = "zf_xiaoxiao", requiresExtraDeps = true,
        ),

        // ── espeak-ng robotic tier ──
        // A curated set of widely-used, space-delimited languages Kokoro can't voice. The espeak build
        // ships ~140 codes (see `tts_server.py --check` `espeakLanguages`); this list is the ones with
        // reliable autonyms and word-spacing that the per-word read-along handles. Quality is robotic but
        // intelligible — the settings labels say so. Each offers a Female + Male espeak variant.
        espeak("de", "German", "Deutsch"),
        espeak("ru", "Russian", "Русский"),
        espeak("pl", "Polish", "Polski"),
        espeak("nl", "Dutch", "Nederlands"),
        espeak("tr", "Turkish", "Türkçe"),
        espeak("ar", "Arabic", "العربية"),
        espeak("ko", "Korean", "한국어"),
        espeak("sv", "Swedish", "Svenska"),
        espeak("da", "Danish", "Dansk"),
        espeak("nb", "Norwegian", "Norsk"),
        espeak("fi", "Finnish", "Suomi"),
        espeak("cs", "Czech", "Čeština"),
        espeak("sk", "Slovak", "Slovenčina"),
        espeak("el", "Greek", "Ελληνικά"),
        espeak("uk", "Ukrainian", "Українська"),
        espeak("ro", "Romanian", "Română"),
        espeak("hu", "Hungarian", "Magyar"),
        espeak("vi", "Vietnamese", "Tiếng Việt"),
        espeak("id", "Indonesian", "Bahasa Indonesia"),
        espeak("he", "Hebrew", "עברית"),
        espeak("fa", "Persian", "فارسی"),
        espeak("hr", "Croatian", "Hrvatski"),
        espeak("bg", "Bulgarian", "Български"),
        espeak("sr", "Serbian", "Српски"),
        espeak("ca", "Catalan", "Català"),
        espeak("lt", "Lithuanian", "Lietuvių"),
        espeak("lv", "Latvian", "Latviešu"),
        espeak("et", "Estonian", "Eesti"),
    )

    private val staticByCode: Map<String, Language> = ALL.associateBy { it.code }

    /**
     * A batch of voices discovered at runtime from ONE source (Windows SAPI — [setSapiVoices]; or
     * downloaded Piper models — [setPiperVoices]). Swapped as an immutable snapshot so readers
     * never see a torn state. `byCode` = language code → that source's voices; `names` = code →
     * (englishName, autonym), used to build a dynamic [Language] when the static registry lacks the code.
     */
    private class Discovered(
        val byCode: Map<String, List<Voice>>,
        val names: Map<String, Pair<String, String>>,
    ) {
        companion object { val EMPTY = Discovered(emptyMap(), emptyMap()) }
    }

    @Volatile private var sapi = Discovered.EMPTY
    @Volatile private var piper = Discovered.EMPTY

    /** All runtime discovery sources, in menu/precedence order: SAPI first, then Piper. */
    private fun sources(): List<Discovered> = listOf(sapi, piper)

    /** Group runtime [items] into a [Discovered] snapshot: [code] extracts the language code, [names] the
     *  (englishName, autonym) pair, and [make] builds the [Voice]. First names per code win. */
    private fun <T> discover(
        items: List<T>, code: (T) -> String, names: (T) -> Pair<String, String>, make: (T) -> Voice,
    ): Discovered {
        val byCode = LinkedHashMap<String, MutableList<Voice>>()
        val nm = LinkedHashMap<String, Pair<String, String>>()
        for (item in items) {
            byCode.getOrPut(code(item)) { mutableListOf() }.add(make(item))
            nm.putIfAbsent(code(item), names(item))
        }
        return Discovered(byCode.mapValues { it.value.toList() }, nm)
    }

    /**
     * Install the Windows SAPI voices discovered at runtime. Ids `sapi:<code>:<name>`; a dynamic
     * [Language] is synthesized on demand ([byCode]) for any code the static registry lacks (Tamil, Thai, …)
     * so it appears in the menu + narrates. Idempotent; pass an empty list to clear (e.g. non-Windows). Safe
     * to call from any thread.
     */
    fun setSapiVoices(voices: List<SapiVoice>) {
        sapi = discover(voices, { it.code }, { it.displayName to it.autonym }) {
            Voice("$SAPI_PREFIX${it.code}:${it.voiceName}", it.label, Engine.SAPI)
        }
    }

    /**
     * Install the Piper voices the user has downloaded, discovered at runtime. Ids
     * `piper:<code>:<voiceKey>`; a dynamic [Language] is synthesized on demand ([byCode]) for any code the
     * static registry lacks (the ~40 languages Kokoro can't voice). Idempotent; pass an empty list to clear.
     * Safe to call from any thread.
     */
    fun setPiperVoices(voices: List<PiperVoice>) {
        piper = discover(voices, { it.code }, { it.displayName to it.autonym }) {
            Voice("$PIPER_PREFIX${it.code}:${it.voiceKey}", it.label, Engine.PIPER)
        }
    }

    /**
     * Kokoro voice-name first letter → language code. `a`/`b` both map to English. Mirrors the Python
     * `PREFIX_LANG` (Kokoro voices only) — keep in sync. Used as a fallback for voices not in the registry;
     * espeak voice specs ("de", "de+f3") don't follow this convention and are resolved via [voiceToCode].
     */
    private val prefixToCode: Map<Char, String> = mapOf(
        'a' to "en", 'b' to "en", 'e' to "es", 'f' to "fr",
        'h' to "hi", 'i' to "it", 'p' to "pt", 'j' to "ja", 'z' to "zh",
    )

    /** Every registered voice id → its language code (covers both Kokoro and espeak specs). */
    private val voiceToCode: Map<String, String> =
        ALL.flatMap { lang -> lang.voices.map { it.id to lang.code } }.toMap()

    /** Every registered voice id → the engine that voices it (per-VOICE — a Kokoro language's robotic
     *  voices map to [Engine.ESPEAK]). */
    private val voiceToEngine: Map<String, Engine> =
        ALL.flatMap { lang -> lang.voices.map { it.id to it.engine } }.toMap()

    private val english: Language = staticByCode.getValue("en")

    /** A static language with any runtime-discovered voices (SAPI, then Piper) for its code appended. */
    private fun merged(base: Language): Language {
        val extra = sources().flatMap { it.byCode[base.code].orEmpty() }
        return if (extra.isEmpty()) base else base.copy(voices = base.voices + extra)
    }

    /**
     * Build a dynamic [Language] for a runtime-only [code] (one the static registry lacks — introduced by
     * SAPI and/or Piper), merging the voices from every discovery source in source order; null if
     * no source has voices for it. Names come from whichever source first supplies them.
     */
    private fun dynamicLanguage(code: String): Language? {
        val vs = sources().flatMap { it.byCode[code].orEmpty() }
        if (vs.isEmpty()) return null
        val (dn, an) = sources().firstNotNullOfOrNull { it.names[code] } ?: (code to code)
        return Language(code, dn, an, vs, vs.first().id, engine = vs.first().engine)
    }

    /**
     * Language for [code]; null / unknown / blank falls back to English (the no-op default). A static
     * language gets its runtime SAPI/Piper voices appended ([merged]); a runtime-only language (dynamic)
     * is built on demand from every source's voices for that code ([dynamicLanguage]).
     */
    fun byCode(code: String?): Language {
        val c = code?.takeIf { it.isNotBlank() }
        if (c != null) {
            staticByCode[c]?.let { return merged(it) }
            dynamicLanguage(c)?.let { return it }
        }
        return merged(english)
    }

    /** Eligible voices for [code] (English if unknown) — includes any runtime SAPI/Piper voices for it. */
    fun voicesFor(code: String?): List<Voice> = byCode(code).voices

    /** Default voice id for [code] (English's if unknown). */
    fun defaultVoiceFor(code: String?): String = byCode(code).defaultVoice

    /** All languages for the menus — the static registry (each with its runtime SAPI/Piper voices merged in)
     *  plus any runtime-only languages discovered at runtime, each listed once. */
    fun allLanguages(): List<Language> {
        val dynamicCodes = sources().flatMap { it.byCode.keys }
            .filterNot { staticByCode.containsKey(it) }.distinct()
        return ALL.map { merged(it) } + dynamicCodes.mapNotNull { dynamicLanguage(it) }
    }

    /**
     * Language code for a voice id, e.g. "ef_dora" → "es", "de+f3" → "de", "sapi:ta:Microsoft Valluvar
     * Online" → "ta", "piper:de:de_DE-eva_k-x_low" → "de"; unknown/blank → English. Registered voices
     * (Kokoro/espeak) resolve via the registry; a SAPI or Piper id carries its code inline; anything else
     * falls back to the Kokoro first-letter [prefixToCode].
     */
    fun languageOfVoice(voiceId: String?): String {
        if (voiceId == null) return DEFAULT_CODE
        if (voiceId.startsWith(SAPI_PREFIX)) {
            return voiceId.substring(SAPI_PREFIX.length).substringBefore(':', "").ifBlank { DEFAULT_CODE }
        }
        if (voiceId.startsWith(PIPER_PREFIX)) {
            return voiceId.substring(PIPER_PREFIX.length).substringBefore(':', "").ifBlank { DEFAULT_CODE }
        }
        voiceToCode[voiceId]?.let { return it }
        // An espeak spec is "<code>+<variant>"; if the bare code is a known language, use it. This keeps
        // legacy/unregistered variants (e.g. an old persisted "lt+f3") resolving to their language rather
        // than falling through to English.
        voiceId.substringBefore('+').takeIf { it != voiceId && staticByCode.containsKey(it) }?.let { return it }
        return prefixToCode[voiceId.firstOrNull()] ?: DEFAULT_CODE
    }

    /**
     * Synthesizer tier for a voice id — [Engine.SAPI] for a `sapi:` id (Windows tier), [Engine.PIPER] for a
     * `piper:` id (offline-neural tier), [Engine.ESPEAK] for the robotic tier, else [Engine.KOKORO]. Keyed on
     * the voice itself (not its language), so a robotic/SAPI/Piper voice inside a Kokoro language resolves
     * correctly. Unregistered specs fall back on the espeak `<code>+<variant>` shape.
     */
    fun engineOfVoice(voiceId: String?): Engine = when {
        voiceId == null -> Engine.KOKORO
        voiceId.startsWith(SAPI_PREFIX) -> Engine.SAPI
        voiceId.startsWith(PIPER_PREFIX) -> Engine.PIPER
        else -> voiceToEngine[voiceId] ?: if (voiceId.contains('+')) Engine.ESPEAK else Engine.KOKORO
    }

    /** The raw SAPI5 voice name inside a `sapi:<code>:<name>` id (what the server synthesizes with); "" if
     *  [voiceId] isn't a SAPI id. */
    fun sapiVoiceName(voiceId: String?): String =
        if (voiceId != null && voiceId.startsWith(SAPI_PREFIX))
            voiceId.substring(SAPI_PREFIX.length).substringAfter(':', "") else ""

    /** The bare Piper voice key inside a `piper:<code>:<voiceKey>` id (what the server synthesizes with); ""
     *  if [voiceId] isn't a Piper id. */
    fun piperVoiceKey(voiceId: String?): String =
        if (voiceId != null && voiceId.startsWith(PIPER_PREFIX))
            voiceId.substring(PIPER_PREFIX.length).substringAfter(':', "") else ""

    /** True if [voiceId] is one of the voices eligible for language [code]. */
    fun isVoiceValidFor(code: String?, voiceId: String?): Boolean =
        voiceId != null && voicesFor(code).any { it.id == voiceId }

    /** UI label for a registered voice id (e.g. "Robotic (F)", "RoboSynth LoQ (F)", "Heart (US, F)"); "" if unknown. */
    fun voiceLabel(voiceId: String?): String =
        voiceId?.let { id -> byCode(languageOfVoice(id)).voices.firstOrNull { it.id == id }?.label } ?: ""

    /**
     * Short engine badge for a single voice id, appended to menu entries for consistency (e.g.
     * "Heart (US, F) — kokoro"): `"kokoro"`, `"espeak"`, `"WinSAPI"`, or `"WinSAPI, online"` (the online
     * flavour is detected from the SAPI voice name, which the neural Edge voices suffix with "Online").
     */
    fun engineTag(voiceId: String?): String = when (engineOfVoice(voiceId)) {
        Engine.KOKORO -> "kokoro"
        Engine.ESPEAK -> "espeak"
        Engine.SAPI -> if (sapiVoiceName(voiceId).contains("Online", ignoreCase = true)) "WinSAPI, online" else "WinSAPI"
        Engine.PIPER -> "piper"
    }

    /**
     * The distinct synthesizer tiers a language can be narrated with, as short badges in voice order
     * (e.g. `["kokoro", "espeak", "WinSAPI"]`) — for the settings language menu, so the tiers on offer are
     * visible at selection time. Derived from the (SAPI-merged) voice list, so it stays truthful per machine.
     */
    fun voiceTiers(lang: Language): List<String> =
        lang.voices.map { it.engine }.distinct().map {
            when (it) {
                Engine.KOKORO -> "kokoro"; Engine.ESPEAK -> "espeak"; Engine.SAPI -> "WinSAPI"; Engine.PIPER -> "piper"
            }
        }

    // ── action label (editor + Solution Explorer) ──

    /**
     * Hand-supplied localized "Explain this" verb per built-in language code (the 8 Kokoro + espeak-tier
     * languages that have a reliable autonym). Used by [explainActionText]. RTL languages (ar/he/fa) are
     * intentionally omitted so they take the cleaner fallback form (English label + native-name tail)
     * rather than a mixed-direction "<rtl verb> (Audio Explainer)".
     */
    private val explainThisPhrase: Map<String, String> = mapOf(
        "es" to "Explica esto",
        "fr" to "Explique ceci",
        "hi" to "इसे समझाएँ",
        "it" to "Spiega questo",
        "pt" to "Explique isto",
        "ja" to "これを説明",
        "zh" to "解释这个",
        "de" to "Erkläre dies",
        "ru" to "Объясни это",
        "pl" to "Wyjaśnij to",
        "nl" to "Leg dit uit",
        "tr" to "Bunu açıkla",
        "ko" to "이것을 설명",
        "sv" to "Förklara detta",
        "da" to "Forklar dette",
        "nb" to "Forklar dette",
        "fi" to "Selitä tämä",
        "cs" to "Vysvětli to",
        "sk" to "Vysvetli to",
        "el" to "Εξήγησε αυτό",
        "uk" to "Поясни це",
        "ro" to "Explică asta",
        "hu" to "Magyarázd el",
        "vi" to "Giải thích cái này",
        "id" to "Jelaskan ini",
        "hr" to "Objasni ovo",
        "bg" to "Обясни това",
        "sr" to "Објасни ово",
        "ca" to "Explica això",
        "lt" to "Paaiškink tai",
        "lv" to "Paskaidro šo",
        "et" to "Selgita seda",
    )

    /**
     * The "Explain This" menu label for the selected explanation-language [code] (multi-language feature).
     * English keeps the plain [DEFAULT_EXPLAIN_LABEL]. A language with a localized verb reads
     * "<localized> (Audio Explainer)". Anything else — a downloaded Piper/SAPI-only language with no
     * translation, or an unknown/undiscovered code — falls back to the English label with the language's
     * native name appended (its autonym when known, else the upper-cased code): "Explain This (Audio) · ไทย".
     * Reads only cached registry state, so it is safe to call from an action `update()` on any thread.
     */
    fun explainActionText(code: String?): String {
        val c = code?.takeIf { it.isNotBlank() } ?: DEFAULT_CODE
        if (c == DEFAULT_CODE) return DEFAULT_EXPLAIN_LABEL
        explainThisPhrase[c]?.let { return "$it (Audio Explainer)" }
        val lang = byCode(c)
        val badge = if (lang.code == c) lang.autonym else c.uppercase()
        return "$DEFAULT_EXPLAIN_LABEL · $badge"
    }

    /**
     * The "with…" sibling of [explainActionText] — the same label plus an English tail, so the two entries
     * read as one pair whatever the explanation language is. The tail stays English on purpose: it names a
     * dialog whose own controls are English, and a half-translated menu item is worse than an English one.
     */
    fun explainWithActionText(code: String?): String = explainActionText(code) + " with…"

    // ── label derivation ──

    private fun voices(vararg ids: String): List<Voice> = ids.map { Voice(it, labelFor(it)) }

    /**
     * The two robotic (espeak) voices — `<code>+ae_f` / `<code>+ae_m`, our custom clean variants the
     * server installs. Offered both as the entire voice list of an espeak-tier language and appended to a
     * Kokoro-tier language's list (so any language can be narrated robotically). Labelled by [robotic]:
     * "RoboSynth LoQ (F)"/"(M)" inside a pure-espeak language, "Robotic (F)"/"Robotic (M)" alongside neural voices.
     */
    private fun roboticVoices(code: String, standAlone: Boolean = false): List<Voice> = listOf(
        Voice("$code+ae_f", if (standAlone) "RoboSynth LoQ (F)" else "Robotic (F)", Engine.ESPEAK),
        Voice("$code+ae_m", if (standAlone) "RoboSynth LoQ (M)" else "Robotic (M)", Engine.ESPEAK),
    )

    /**
     * Build an espeak-tier [Language]. The "voice" is the language code plus our custom clean variant
     * (`+ae_f` / `+ae_m`). Female + Male only (no bare default, so every
     * robotic voice is an editable variant profile). No extra deps (espeak ships with `kokoro-onnx`).
     */
    private fun espeak(code: String, displayName: String, autonym: String): Language = Language(
        code = code, displayName = displayName, autonym = autonym,
        voices = roboticVoices(code, standAlone = true),
        defaultVoice = "$code+ae_f",
        engine = Engine.ESPEAK,
    )

    /** "af_heart" → "Heart (US, F)", "em_alex" → "Alex (M)", "bf_emma" → "Emma (UK, F)". */
    private fun labelFor(id: String): String {
        val name = id.substringAfter('_', id).replaceFirstChar { it.uppercase() }
        val gender = when (id.getOrNull(1)) {
            'f' -> "F"
            'm' -> "M"
            else -> "?"
        }
        val accent = when (id.getOrNull(0)) {
            'a' -> "US, "
            'b' -> "UK, "
            else -> ""
        }
        return "$name ($accent$gender)"
    }
}
