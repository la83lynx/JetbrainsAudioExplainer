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
 * as "Smoothness"). See `tts_server.py` `_build_variant`.
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
 * Persisted, user-tunable appearance settings for the editor spotlight.
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
         * don't read identically to gap lines (which only carry the gutter bar).
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
         * How opaque that background is. 100 = solid (the default).
         * Lower it and code or a relation arrow underneath the box reads through it — which matters when an
         * arrow's leg has nowhere to run but across the box, on an editor too narrow to route around it.
         */
        @JvmField var calloutOpacityPercent: Int = DEF_CALLOUT_OPACITY

        /**
         * How the box is drawn *around* its text: [CALLOUT_FRAME_BAR] (accent bar down the left, the
         * default), [CALLOUT_FRAME_OUTLINE] (accent border), [CALLOUT_FRAME_SOLID] (tinted panel alone),
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
         * Blank = the editor's own font. The worked example ignores this
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
         * A picker per slot, because what a reader wants to fix is a specific slot that reads badly against
         * their theme; a single base colour could only move the whole hue rotation.
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
         * Defaults to 10 (1 px), the width `LinkArt` was tuned at.
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

        // No switches for a step's note / example / token flash / relations, deliberately: they are what the
        // narrator chose to say, so hiding them only shows the reader less. The token flash's fade time is
        // the fixed `DEF_TOKEN_FLASH_MS`.

        // ── The code tree ──────────────────────────────────────────────────────
        //
        // The defaults here are the tuned tree shape, which is what Reset-to-defaults restores. `TreeStyle`
        // is the one place these strings become the enums, and `PlanProbe` asserts each default names a real
        // constant *and* agrees with `NestStyle()` / `CodeNodeStyle()`'s own default, so the two cannot drift.
        //
        // Values are the enum constant names (case-insensitively), so there is no second vocabulary to keep
        // in step. An unrecognised one degrades to the default rather than failing the load: a settings file
        // written by a newer build must not stop the tree from drawing.

        /**
         * **The leader mode** — `CORNERS` / `BRACE` / `ARROW`, and the headline setting of this whole group.
         *
         * One choice with three values and **no fourth**: the leader carries the token
         * box's bottom edge and the child frame's top edge in the two joining modes, so "no leader" would
         * leave a bottomless box above a topless frame with nothing between them. Chrome travels with it and
         * is *not* offered beside it for the same reason — see [com.audioexplainer.tree.NodeChrome].
         */
        @JvmField var treeLeader: String = DEF_TREE_LEADER

        /**
         * Which shape carries a nesting's collapse/expand gesture: `HORIZONTAL` / `VERTICAL` /
         * `BOTTOM_BORDER` / `NONE`.
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

        /**
         * How much of a frame's `keep` a step folds away for itself: `OFF` / `ANCESTORS` / `LINKS` / `STEP` —
         * see [com.audioexplainer.tree.StepCondense]. Like [treeStepFold], a property of the reader's screen,
         * not of the plan.
         */
        @JvmField var treeCondense: String = DEF_TREE_CONDENSE

        /** How much of a node's path its caption shows: `FULL` / `PROJECT_RELATIVE` / `BASENAME`. */
        @JvmField var treePath: String = DEF_TREE_PATH

        /**
         * How a node the step is not talking about is pushed back: `NONE` / `TINT` / `BORDER` /
         * `TINT_AND_BORDER`. Every value is a **background** — there is no foreground option,
         * because a foreground dim flattens each dimmed row to one grey and destroys the semantic
         * colour the whole architecture exists to keep.
         */
        @JvmField var treeDeemphasis: String = DEF_TREE_DEEMPHASIS

        /**
         * How an elided run is drawn, and whether the reader can open it: `OPENABLE` (plain fold +
         * placeholder) or `SEALED` (prettier, and shut for good).
         */
        @JvmField var treeElision: String = DEF_TREE_ELISION

        /**
         * The **ceiling** on a node's height in px, not its size: a node's height is
         * `min(excerpt, content, this + embedded + callout)`, so a short member gets its own few lines and
         * only a long one meets this number.
         */
        @JvmField var treeMaxHeight: Int = DEF_TREE_MAX_HEIGHT

        /**
         * Let **every** node grow to its content instead of capping it. Off by default, with a warning: a node
         * with no viewport smaller than its content cannot be scrolled, so switching this on switches off the
         * per-node reveal the attention policy is built out of.
         */
        @JvmField var treeGrowNodes: Boolean = false

        /**
         * Grow a **top-level** node that hosts a nesting, rather than capping it (`NestStyle.growHosts`).
         *
         * Root only, and on by default: capping the root gives it an inner scrollbar inside the outer pane —
         * two scrollbars for one node. Off caps the root at [treeMaxHeight] plus whatever is embedded.
         */
        @JvmField var treeGrowHosts: Boolean = true

        // ── TTS / narration ──────────────────────────────────────────────────────

        /**
         * Narration on/off: [ENGINE_KOKORO] (on — the default; the running Kokoro server also hosts the
         * espeak robotic tier and, on Windows, the System.Speech tier, chosen **per voice** via
         * [ttsVoice]), or [ENGINE_OFF] (silent: the walkthrough still loads + highlights). Any other
         * value (an older file may hold `sapi`) counts as on: [NarrationController] gates on off vs. ready.
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
         * not re-prompt on every play.
         */
        @JvmField var ttsSetupPrompted: Boolean = false

        // ── Explanation generation ─────────────────────────────────

        /**
         * `claude --effort` for an Explain This run: "" (inherit the CLI's own default), or one of
         * [EFFORT_LEVELS].
         *
         * **It ships `medium`, and that is the one place this plugin deliberately overrides a setting the
         * user made elsewhere.** Blank-means-inherit is the safe default for [explainModel]; here it
         * is a trap — a `~/.claude/settings.json` `effortLevel` chosen for interactive coding can cost a
         * walkthrough four minutes where `medium` takes two and produces the same plan shape (measured: 255s
         * vs 118s). Blank stays reachable for the two people
         * who want it.
         */
        @JvmField var explainEffort: String = EFFORT_MEDIUM

        /**
         * `claude --model` alias — blank (the default) leaves the user's own configured model alone, which
         * is almost always the right one. [MODEL_ALIASES] are the offered values; any full model name the
         * CLI accepts also works if it is put here by hand.
         */
        @JvmField var explainModel: String = ""

        /**
         * The [ProviderProfile.id] Explain This runs on; blank (the default) is Claude Code's own setup, with
         * nothing injected. With a profile, [explainModel] is not read: the profile's model is the model.
         */
        @JvmField var explainProvider: String = ""

        /**
         * Which agent runs Explain This: [com.audioexplainer.acp.AgentPresets.CLAUDE] (the default) —
         * Claude Code — or an ACP agent: `opencode`, `qwen`, `custom`.
         */
        @JvmField var explainHarness: String = "claude"

        /** Explicit paths for the ACP agents; blank looks on PATH and in the usual install directories. */
        @JvmField var opencodePath: String = ""
        @JvmField var qwenPath: String = ""

        /** The Custom ACP agent: its command line, executable first (quotes for a path with spaces). */
        @JvmField var customAgentCommand: String = ""

        /** The user's provider profiles. **No key in here** — see [ProviderKeys]. */
        @JvmField var providers: MutableList<ProviderProfile> = mutableListOf()

        /**
         * How much walkthrough to ask for: [DEPTH_BRIEF], [DEPTH_STANDARD], [DEPTH_DETAILED] or [DEPTH_DEEP].
         * Prompt text only — nothing else in the pipeline reads it, and it narrows what `docs/plan-schema.md`
         * allows rather than adding to it. **Depth buys back wall-clock time by bounding reach**: at xhigh, Deep's
         * extra time was opening other files and thinking about them, not writing a longer plan (measured)
         * — which is why Detailed keeps Deep's annotations but not its reach.
         */
        @JvmField var explainDepth: String = DEPTH_STANDARD

        /**
         * The last [MAX_INSTRUCTION_HISTORY] non-empty "additional instructions" typed into the per-run
         * dialog, most recent first.
         *
         * **The one thing that dialog persists.** A per-run effort or depth must never become the saved
         * default — that is how a reader ends up wondering why every explanation went terse — but
         * retyping "use simple terms" on every run is the friction the dialog exists to remove.
         */
        @JvmField var explainInstructionHistory: MutableList<String> = mutableListOf()

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

    /**
     * Push [text] onto the per-run instruction history (most recent first, de-duplicated, capped at
     * [MAX_INSTRUCTION_HISTORY]). Blank input is ignored, so an empty box never evicts a remembered entry.
     */
    fun rememberInstruction(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val next = ArrayList<String>(MAX_INSTRUCTION_HISTORY)
        next += trimmed
        myState.explainInstructionHistory.filterTo(next) { it != trimmed }
        myState.explainInstructionHistory = next.take(MAX_INSTRUCTION_HISTORY).toMutableList()
    }

    /** The robotic-voice parameter profile for a variant name (`ae_m` → male, anything else → female). */
    fun espeakProfile(variant: String?): EspeakProfile =
        if (variant == "ae_m") myState.roboticM else myState.roboticF

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) = XmlSerializerUtil.copyBean(state, myState)

    companion object {
        // A ~40-pt gap between the outside and within dims: a ~20-pt gap reads as identical.
        const val DEF_OUTSIDE_DIM = 22
        const val DEF_WITHIN_DIM = 62
        const val DEF_ACTIVE_GLOW = 10
        const val DEF_NONACTIVE_TINT = 14
        const val DEF_GAP_WIDTH = 3
        const val DEF_CALLOUT_TINT = 18

        /** Solid. */
        const val DEF_CALLOUT_OPACITY = 100

        // Callout/arrow geometry defaults.
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

        // The tree's shipped defaults. Enum constant names, resolved in `TreeStyle`; each one is asserted
        // against the enum *and* against the style class's own default, so the two cannot drift.
        // `ALWAYS` folds the nestings a step is not narrating, which otherwise only take up space.
        const val DEF_TREE_LEADER = "BRACE"
        const val DEF_TREE_CHEVRON = "BOTTOM_BORDER"
        const val DEF_TREE_STEP_FOLD = "ALWAYS"
        const val DEF_TREE_CONDENSE = "LINKS"
        const val DEF_TREE_PATH = "PROJECT_RELATIVE"
        const val DEF_TREE_DEEMPHASIS = "TINT"
        const val DEF_TREE_ELISION = "OPENABLE"
        const val DEF_TREE_MAX_HEIGHT = 600

        const val DEF_TOKEN_FLASH_MS = 700
        const val DEF_ACTIVE_GLOW_COLOR = "FFFFFF"
        const val DEF_ACCENT_COLOR = "FFBE3C"

        // Explanation generation. `--effort` exists from CLI 2.1.274; `--model` takes an alias.
        const val EFFORT_LOW = "low"
        const val EFFORT_MEDIUM = "medium"
        const val EFFORT_HIGH = "high"
        const val EFFORT_XHIGH = "xhigh"
        const val EFFORT_MAX = "max"

        /** The five `--effort` stops, in order. The per-run slider's stops are these, by index. */
        val EFFORT_LEVELS = listOf(EFFORT_LOW, EFFORT_MEDIUM, EFFORT_HIGH, EFFORT_XHIGH, EFFORT_MAX)

        const val DEPTH_BRIEF = "brief"
        const val DEPTH_STANDARD = "standard"
        /** Deep's annotations with Standard's reach: frames only from the target. */
        const val DEPTH_DETAILED = "detailed"
        const val DEPTH_DEEP = "deep"
        val DEPTH_LEVELS = listOf(DEPTH_BRIEF, DEPTH_STANDARD, DEPTH_DETAILED, DEPTH_DEEP)

        /** `--model` aliases offered in the combo; blank (inherit) is the first item and is not in here. */
        val MODEL_ALIASES = listOf("fable", "opus", "sonnet")

        const val MAX_INSTRUCTION_HISTORY = 8

        // TTS defaults
        const val ENGINE_KOKORO = "kokoro"
        const val ENGINE_OFF = "off"
        const val DEF_TTS_VOICE = "af_heart"
        const val DEF_TTS_SPEED_PERCENT = 100

        fun getInstance(): AudioExplainerSettings = service()
    }
}
