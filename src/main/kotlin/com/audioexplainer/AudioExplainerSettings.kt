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
 * Values survive restarts in `audioExplainer.xml`. [SpotlightRenderer] reads [state] on every render,
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
     * (This is the `t` in [SpotlightRenderer]'s `blend(fg, bg, t)`.)
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

        // ── Step decorations (MoreUI step 1) ───────────────────────────────────

        /**
         * Docked callout above the active highlight, showing that highlight's own short label. Renders
         * nothing when the walkthrough carries no per-highlight label, so plain plans are unaffected.
         */
        @JvmField var callout: Boolean = true

        /** Callout box background: [accentColor] blended this far toward the editor background. */
        @JvmField var calloutTintPercent: Int = DEF_CALLOUT_TINT

        /**
         * How opaque that background is. 100 = solid (the default, and how the box has always looked).
         * Lower it and code or a relation arrow underneath the box reads through it — which matters when an
         * arrow's leg has nowhere to run but across the box, on an editor too narrow to route around it.
         */
        @JvmField var calloutOpacityPercent: Int = DEF_CALLOUT_OPACITY

        /**
         * How the box is drawn *around* its text: [CALLOUT_FRAME_BAR] (accent bar down the left, the
         * original), [CALLOUT_FRAME_OUTLINE] (accent border), [CALLOUT_FRAME_SOLID] (tinted panel alone),
         * [CALLOUT_FRAME_NONE] (text with no panel at all — the lightest thing to put over code) or
         * [CALLOUT_FRAME_COMMENT] (no panel either, but every line prefixed `//` and painted in the scheme's
         * own comment colour, so the aside reads as a comment somebody wrote in the file).
         *
         * Decoration only in the sense that matters: the text, the per-block line caps and the measure/paint
         * path are shared by all five. [CALLOUT_FRAME_COMMENT] does move the wrap, because its prefix takes
         * real width — see `SpotlightRenderer.CalloutRenderer.prefix`.
         */
        @JvmField var calloutFrame: String = CALLOUT_FRAME_BAR

        /**
         * Font family for the callout's *description* text — the prose aside and the relation pointer lines.
         * Blank = the editor's own font, which is what it has always used. The worked example ignores this
         * and stays on the editor font deliberately: it is code, and code that isn't in the code font reads
         * as prose about code.
         *
         * An unknown family costs the family, not the callout — `java.awt.Font` falls back to a default.
         */
        @JvmField var calloutProseFont: String = ""

        /**
         * Draw a short leader from the callout down (or up, when [calloutBelow] is set) to the line it
         * annotates. Costs a few px of the inlay's reserved height, which is why it is a setting rather than
         * always-on: on a dense file that height is the scarce thing.
         */
        @JvmField var calloutLeader: Boolean = true

        /** Corner radius of the callout box in px; 0 is a square box. */
        @JvmField var calloutCornerRadius: Int = DEF_CALLOUT_ARC

        /** Width of the left accent bar in px. Only [CALLOUT_FRAME_BAR] draws one. */
        @JvmField var calloutBarWidth: Int = DEF_CALLOUT_BAR_W

        /**
         * Callout text size as a percentage of the editor font. The wrap follows it, because the renderer
         * measures and paints through the same [java.awt.FontMetrics] either way.
         */
        @JvmField var calloutTextScalePercent: Int = DEF_CALLOUT_TEXT_SCALE

        /**
         * Dock the callout *below* the active lines instead of above them. Anchors to the last active line
         * and relates to the preceding text, so an edit still carries the box with the code it describes.
         */
        @JvmField var calloutBelow: Boolean = false

        /**
         * Relation arrows follow [accentColor] like every other decoration unless this is off, in which case
         * they use [arrowColor]. A flag plus a colour rather than "blank means inherit", so the colour picker
         * always has a real colour to show.
         */
        @JvmField var arrowUseAccent: Boolean = true
        @JvmField var arrowColor: String = DEF_ACCENT_COLOR

        /** Arrow line width in tenths of a px — 14 is the 1.4 px hairline the arrows shipped with. */
        @JvmField var arrowThicknessTenths: Int = DEF_ARROW_THICKNESS

        /** Arrowhead length (and width) in px. */
        @JvmField var arrowHeadSize: Int = DEF_ARROW_HEAD

        /** Square the elbow's corners instead of rounding them. */
        @JvmField var arrowSquareElbow: Boolean = false

        /**
         * Draw a relation's note beside the arrow's leg. Off leaves that text to the callout box and the
         * gutter pointer's tooltip, which carry it anyway — useful on a busy file.
         */
        @JvmField var arrowLabels: Boolean = true

        /**
         * Include a highlight's one-line `example` (an `input → output` illustration) in the callout box,
         * below the prose aside and drawn upright so it reads as code. Gated *inside* [callout] — the example
         * lives in that box, so turning the box off hides it too. Does nothing on highlights with no
         * `example`, which is most of them.
         */
        @JvmField var calloutExample: Boolean = true

        /**
         * Briefly flash the identifier named by a highlight's `token` anchor when that step goes active,
         * fading from [accentColor] into the line's own glow. Does nothing on highlights with no `token`,
         * or when the token can't be found in the highlighted lines.
         */
        @JvmField var tokenFlash: Boolean = true

        /** Total flash duration in milliseconds; the fade is spread over this. */
        @JvmField var tokenFlashMs: Int = DEF_TOKEN_FLASH_MS

        /**
         * Draw the relationships a walkthrough declares (`relations`): an arrow between two lines of the file
         * on screen, and a clickable gutter pointer + callout line for a target the editor can't point at
         * (another file, an off-screen symbol). One toggle covers both forms — they are two renderings of the
         * same authored fact, and a reader who doesn't want arrows over their code doesn't want half of them
         * either. Does nothing on walkthroughs that declare no relations, which is most of them.
         */
        @JvmField var relations: Boolean = true

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
        const val DEF_CALLOUT_TINT = 18

        /** Solid, i.e. the box exactly as it looked before opacity was a setting. */
        const val DEF_CALLOUT_OPACITY = 100

        // Callout/arrow geometry defaults. These were renderer constants until they became settings; the
        // values are unchanged, so a fresh install draws exactly what it drew before.
        const val DEF_CALLOUT_ARC = 6
        const val DEF_CALLOUT_BAR_W = 3
        const val DEF_CALLOUT_TEXT_SCALE = 100
        const val DEF_ARROW_THICKNESS = 14
        const val DEF_ARROW_HEAD = 7

        const val CALLOUT_FRAME_BAR = "bar"
        const val CALLOUT_FRAME_OUTLINE = "outline"
        const val CALLOUT_FRAME_SOLID = "solid"
        const val CALLOUT_FRAME_NONE = "none"
        const val CALLOUT_FRAME_COMMENT = "comment"

        const val DEF_TOKEN_FLASH_MS = 700
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
