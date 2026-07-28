package com.audioexplainer

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Robotic (espeak) voice parameters for ONE custom variant, persisted as a nested bean (public no-arg
 * ctor + `@JvmField` mutables so the platform XML serializer round-trips it). Two of these live in
 * [AudioExplainerSettings.State] — one per variant (`ae_f`/`ae_m`). Split: **runtime** params
 * (pitch/range/wordGap → `espeak_SetParameter`) + **Layer-2** params (consonants/roughness/flutter/breath →
 * regenerate the variant file). `roughness` is espeak-native (0 = smoothest; the dialog shows it inverted
 * as "Smoothness"). See `ExplainerLanguages.md` §12 and `tts_server.py` `_build_variant`.
 */
class EspeakProfile {
    @JvmField var pitch: Int = 50        // runtime PITCH, 0..100
    @JvmField var range: Int = 50        // runtime RANGE (liveliness), 0..100
    @JvmField var wordGap: Int = 1       // runtime WORDGAP, units of 10 ms
    @JvmField var consonants: Int = 120  // Layer-2 clarity (consonant amplitude)
    @JvmField var roughness: Int = 0     // Layer-2 roughness, 0 = smoothest
    @JvmField var flutter: Int = 2       // Layer-2 pitch flutter (naturalness)
    @JvmField var breath: Int = 0        // Layer-2 breathiness
}

/**
 * Persisted, user-tunable appearance settings for the editor spotlight (Phase 3.5).
 *
 * App-level (IDE-wide, not per-project) so one set of preferences applies wherever a walkthrough runs.
 * Values survive restarts in `audioExplainer.xml`. [EditorHighlighter] reads [state] on every render,
 * so a change takes effect on the next repaint (the settings page also forces a live re-render on apply).
 *
 * Fields are plain public mutables so the platform's XML serializer round-trips them without ceremony;
 * that is also why they use primitives (Int percentages, hex colour strings) rather than `Color`/`Double`.
 */
@Service(Service.Level.APP)
@State(name = "AudioExplainerSettings", storages = [Storage("audioExplainer.xml")])
class AudioExplainerSettings : PersistentStateComponent<AudioExplainerSettings.State> {

    /**
     * "Brightness" percentages are the blend fraction of the syntax foreground that survives:
     * 100 = untouched syntax colour, 0 = fully flattened to the editor background. Lower = more dimmed.
     * (This is the `t` in [EditorHighlighter]'s `blend(fg, bg, t)`.)
     */
    class State {
        /** Lines outside the segment. Should be clearly dimmer than [withinDimPercent]. */
        @JvmField var outsideDimPercent: Int = DEF_OUTSIDE_DIM
        /** Non-active / gap lines inside the segment. */
        @JvmField var withinDimPercent: Int = DEF_WITHIN_DIM

        /** Whole-line glow behind the active highlight: this colour blended [activeGlowPercent]% toward bg. */
        @JvmField var activeGlowColor: String = DEF_ACTIVE_GLOW_COLOR
        @JvmField var activeGlowPercent: Int = DEF_ACTIVE_GLOW

        /** Accent ("gold"): active overview-ruler mark, gap gutter bar, and the non-active tint hue. */
        @JvmField var accentColor: String = DEF_ACCENT_COLOR
        /** Show the accent mark in the overview ruler / error stripe for the active line. */
        @JvmField var overviewRulerMark: Boolean = true

        /**
         * Give non-active highlight lines their OWN cue — a faint [accentColor] background tint — so they
         * no longer read identically to gap lines (which only carry the gutter bar). Addresses the
         * Phase 3 readability complaint directly.
         */
        @JvmField var nonActiveTint: Boolean = true
        @JvmField var nonActiveTintPercent: Int = DEF_NONACTIVE_TINT

        /** Gap (context) lines between highlights: accent-coloured gutter bar. */
        @JvmField var gapGutter: Boolean = true
        @JvmField var gapGutterWidth: Int = DEF_GAP_WIDTH

        // ── TTS / narration (Phase 7) ──────────────────────────────────────────

        /**
         * Narration on/off: [ENGINE_KOKORO] (on — the default; the running Kokoro server also hosts the
         * espeak robotic tier (L6) and, on Windows, the System.Speech tier (L7), chosen **per voice** via
         * [ttsVoice]), or [ENGINE_OFF] (silent: the walkthrough still loads + highlights). [ENGINE_SAPI] is
         * a **retired** value (the old global-SAPI stub, Phase 7b) — kept only so an old persisted "sapi"
         * decodes without error; it's treated as on ([NarrationController] gates solely on off vs. ready).
         */
        @JvmField var ttsEngine: String = ENGINE_KOKORO

        /**
         * Selected voice id (see [Languages]): a Kokoro name (`af_heart`), an espeak spec (`de+ae_f`), or a
         * Windows SAPI id (`sapi:en:Microsoft Aria Online`). The engine is derived from the id per-voice.
         */
        @JvmField var ttsVoice: String = DEF_TTS_VOICE

        /**
         * Default language for **new** "Explain This" generations (see [Languages]); Claude is told to
         * write the walkthrough in it. Does NOT retro-translate already-loaded walkthroughs — a loaded
         * walkthrough's own `language` drives its voices/narration. "en" by default.
         */
        @JvmField var explanationLanguage: String = Languages.DEFAULT_CODE

        /** Playback speed as a percentage (100 = 1.0×); the buttons in the sidebar are 75/100/125/150. */
        @JvmField var ttsSpeedPercent: Int = DEF_TTS_SPEED_PERCENT

        /**
         * Robotic-tier (espeak) voice parameter profiles — one per custom variant ([roboticF] = `ae_f`,
         * [roboticM] = `ae_m`), shared across all languages and applied only to `Engine.ESPEAK` voices
         * (Kokoro ignores them). The sidebar's inline Pitch/Liveliness/Word-gap sliders and the Advanced
         * voice-editor dialog read/write these; narration sends them in the TTS `tune`.
         */
        @JvmField var roboticF: EspeakProfile = EspeakProfile()
        @JvmField var roboticM: EspeakProfile = EspeakProfile()

        /**
         * Model/voices directory for Kokoro; blank = the server default (`~/.cache/audio-explainer`).
         * Stored so a custom install location survives restarts.
         */
        @JvmField var kokoroModelDir: String = ""

        /**
         * Whether the guided first-run TTS setup has been offered already, so triggering audio does
         * not re-prompt on every play. Reset by "Set up TTS" to force the prompt again.
         */
        @JvmField var ttsSetupPrompted: Boolean = false

        // ── Executable overrides (advanced) ─────────────────────────────────────

        /**
         * Explicit path to the `claude` CLI. Blank = auto-detect (PATH + common install dirs). Set this
         * when the IDE's environment doesn't expose the executable the way your terminal does.
         */
        @JvmField var claudeExecutablePath: String = ""

        /** Explicit path to the Python interpreter for the TTS server. Blank = auto-detect. */
        @JvmField var pythonExecutablePath: String = ""
    }

    /** Speed as a multiplier for the TTS request (`ttsSpeedPercent` / 100). */
    fun speed(): Double = (myState.ttsSpeedPercent.coerceIn(25, 400)) / 100.0

    /** The robotic-voice parameter profile for a variant name (`ae_m` → male, anything else → female). */
    fun espeakProfile(variant: String?): EspeakProfile =
        if (variant == "ae_m") myState.roboticM else myState.roboticF

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) = XmlSerializerUtil.copyBean(state, myState)

    companion object {
        // Defaults re-tuned from the original hardcoded 0.35 / 0.55 (a ~20-pt gap that read as identical)
        // to a ~40-pt gap, plus the new non-active tint — the Phase 3.5 "ship better defaults" quick win.
        const val DEF_OUTSIDE_DIM = 22
        const val DEF_WITHIN_DIM = 62
        const val DEF_ACTIVE_GLOW = 10
        const val DEF_NONACTIVE_TINT = 14
        const val DEF_GAP_WIDTH = 3
        const val DEF_ACTIVE_GLOW_COLOR = "FFFFFF"
        const val DEF_ACCENT_COLOR = "FFBE3C"

        // TTS defaults (Phase 7)
        const val ENGINE_KOKORO = "kokoro"
        const val ENGINE_SAPI = "sapi"
        const val ENGINE_OFF = "off"
        const val DEF_TTS_VOICE = "af_heart"
        const val DEF_TTS_SPEED_PERCENT = 100

        fun getInstance(): AudioExplainerSettings = service()
    }
}
