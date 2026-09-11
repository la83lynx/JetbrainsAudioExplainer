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

        // ── Callout appearance ─────────────────────────────────────────────────

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
         * **One colour per link slot** — `LinkArt.SLOTS` of them, in slot order, blank meaning *derive*.
         *
         * This replaced an `arrowUseAccent` flag plus a single `arrowColor`, which could only ever move the
         * *base* of the hue rotation: five links on one step took five hues off it whether the reader liked
         * those hues or not. What a reader actually wants to fix is a specific slot that reads badly against
         * their theme, and that is a picker per slot.
         *
         * **Blank is not "no colour", it is "the derived one"**, and it is the default for every slot, so out
         * of the box this changes nothing: `LinkArt.color(accent, slot)` still answers, slot 0 is still the
         * accent, and `probes/LinkBench.java`'s hue-distance assertions still measure the shipped palette.
         * A stored list shorter than `SLOTS` — an older settings file, or a hand-edited one — reads as blank
         * from there on for the same reason.
         */
        @JvmField var arrowSlotColors: MutableList<String> = MutableList(LinkArt.slots()) { "" }

        /**
         * Link line width in tenths of a px.
         *
         * **10, not the 14 it was.** Until the K1 round this value was parsed into a style object nothing
         * read, so the number here and the 1.0 px `LinkArt` actually strokes had been free to disagree —
         * and had. Wiring the knob up moved the default to the drawing rather than the drawing to the
         * default: 1 px is what shipped and what nine rounds judged.
         */
        @JvmField var arrowThicknessTenths: Int = DEF_ARROW_THICKNESS

        /** Arrowhead length (and width) in px — also the direction indicator's size on the rail. */
        @JvmField var arrowHeadSize: Int = DEF_ARROW_HEAD

        /** Square every turn in a link's run instead of rounding it to `LinkArt`'s 4 px. */
        @JvmField var arrowSquareElbow: Boolean = false

        /**
         * Draw a relation's note beside the arrow's leg. Off leaves that text to the callout box and the
         * portal's tooltip, which carry it anyway — useful on a busy file.
         */
        @JvmField var arrowLabels: Boolean = true

        // **There is no "Step decorations" group any more, and that is deliberate.**
        //
        // `callout`, `calloutExample`, `tokenFlash`, `tokenFlashMs` and `relations` were five switches over
        // four things the *narrator* had already decided to say. Every one of them was inert on a step that
        // carried no `note` / `example` / `token` / `relations` — which is most steps — so what they actually
        // offered was the power to silence a step that did have something to add. A walkthrough's author put
        // it there; hiding it is not a preference, it is a way to be shown less than you were told.
        //
        // They are gone rather than pinned to `true`: a setting that only has one useful value is a row in a
        // dialog, a field in `audioExplainer.xml` and a branch in the renderer, all of them costing reading
        // for nothing. The flash's duration went with them — `DEF_TOKEN_FLASH_MS` is now simply what the
        // fade takes, and it stayed a named constant so `SpotlightRenderer` and its KDoc still agree on one
        // number.

        // ── The code tree (Phase 4) ────────────────────────────────────────────
        //
        // **Every default here is a judged verdict, not a starting point**, which is what makes Reset-to-
        // defaults restore the shape the reader actually approved across G1c′–G1c⁷ rather than whatever a
        // constructor happened to say. `TreeStyle` is the one place these strings become the enums, and
        // `PlanProbe` asserts each default names a real constant *and* agrees with `NestStyle()` /
        // `CodeNodeStyle()`'s own default — two transcriptions of one verdict is exactly the drift this
        // project keeps paying for.
        //
        // Values are the enum constant names (case-insensitively), so there is no second vocabulary to keep
        // in step. An unrecognised one degrades to the default rather than failing the load: a settings file
        // written by a newer build must not stop the tree from drawing.

        /**
         * **The leader mode** — `CORNERS` / `BRACE` / `ARROW`, and the headline setting of this whole group.
         *
         * One choice with three values and **no fourth**: constraint 16 makes the leader carry the token
         * box's bottom edge and the child frame's top edge in the two joining modes, so "no leader" would
         * leave a bottomless box above a topless frame with nothing between them. Chrome travels with it and
         * is *not* offered beside it for the same reason — see [com.audioexplainer.tree.NodeChrome].
         */
        @JvmField var treeLeader: String = DEF_TREE_LEADER

        /**
         * Which shape carries a nesting's collapse/expand gesture: `HORIZONTAL` / `VERTICAL` /
         * `BOTTOM_BORDER` / `NONE`. Asked for outright — *"we should allow other configurations"* — and
         * `NONE` is the control that shows what the click was reaching.
         */
        @JvmField var treeChevron: String = DEF_TREE_CHEVRON

        /**
         * What a step change does to the nestings it is **not** narrating: `NEVER` / `OVER_BUDGET` / `ALWAYS`.
         *
         * The one tree setting whose subject is the *walkthrough* rather than the picture, and it is here rather
         * than in the schema on purpose — no step says which frames are open, because openness is a property of
         * the reader's screen and not of the plan. See [com.audioexplainer.tree.StepFold].
         */
        @JvmField var treeStepFold: String = DEF_TREE_STEP_FOLD

        /** How much of a node's path its caption shows: `FULL` / `PROJECT_RELATIVE` / `BASENAME`. */
        @JvmField var treePath: String = DEF_TREE_PATH

        /**
         * How a node the step is not talking about is pushed back: `NONE` / `TINT` / `BORDER` /
         * `TINT_AND_BORDER`. Every value is a **background** — constraint 1 leaves no foreground option to
         * offer, because a foreground dim flattens each dimmed row to one grey and destroys the semantic
         * colour the whole architecture exists to keep.
         */
        @JvmField var treeDeemphasis: String = DEF_TREE_DEEMPHASIS

        /**
         * How an elided run is drawn, and whether the reader can open it: `OPENABLE` (plain fold +
         * placeholder) or `SEALED` (prettier, and shut for good — constraint 6, measured).
         */
        @JvmField var treeElision: String = DEF_TREE_ELISION

        /**
         * The **ceiling** on a node's height in px, not its size: constraint 8 orders it
         * `min(excerpt, content, this + embedded + callout)`, so a short member gets its own few lines and
         * only a long one meets this number.
         */
        @JvmField var treeMaxHeight: Int = DEF_TREE_MAX_HEIGHT

        /**
         * Let **every** node grow to its content instead of capping it. Off by default, and constraint 19 is
         * why it is worth a warning rather than a shrug: a node with no viewport smaller than its content
         * cannot be scrolled, so switching this on switches off the per-node reveal that Phase 3's attention
         * policy is built out of.
         */
        @JvmField var treeGrowNodes: Boolean = false

        /**
         * Grow a **top-level** node that hosts a nesting, rather than capping it (`NestStyle.growHosts`).
         *
         * Narrowed to the root by the G1c′ round and left on: capping the root gives it an inner scrollbar
         * inside the outer pane — two scrollbars for one node. Off is the comparison it has always been:
         * the root at [treeMaxHeight] plus whatever is embedded, against the whole caller.
         */
        @JvmField var treeGrowHosts: Boolean = true

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
        const val DEF_ARROW_THICKNESS = 10
        const val DEF_ARROW_HEAD = 7

        const val CALLOUT_FRAME_BAR = "bar"
        const val CALLOUT_FRAME_OUTLINE = "outline"
        const val CALLOUT_FRAME_SOLID = "solid"
        const val CALLOUT_FRAME_NONE = "none"
        const val CALLOUT_FRAME_COMMENT = "comment"

        // The tree's shipped defaults (Phase 4). Enum constant names, resolved in `TreeStyle`; each one is
        // asserted against the enum *and* against the style class's own default, so the two cannot drift.
        //
        // Six of the nine are §1c's judged verdicts. **Three are not, and saying so is the point**: the leader
        // and the collapse control were changed to `BRACE` / `BOTTOM_BORDER` by the reader before the Phase
        // 3/4 round, where nine earlier rounds had run on `CORNERS` / `HORIZONTAL`; and the step fold arrives
        // at `ALWAYS` from a K3 observation rather than from a comparison — *"for some steps explainer keeps
        // expanded items, though it's not required and only takes up space"* — so `OVER_BUDGET`, the value that
        // shipped, is on the page as the control that says whether it was the budget doing the work. A
        // preference stated in advance is not the same kind of thing as a verdict a round produced, and Reset
        // restores whichever one is here — so which it is has to be written down.
        const val DEF_TREE_LEADER = "BRACE"
        const val DEF_TREE_CHEVRON = "BOTTOM_BORDER"
        const val DEF_TREE_STEP_FOLD = "ALWAYS"
        const val DEF_TREE_PATH = "PROJECT_RELATIVE"
        const val DEF_TREE_DEEMPHASIS = "TINT"
        const val DEF_TREE_ELISION = "OPENABLE"
        const val DEF_TREE_MAX_HEIGHT = 600

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
