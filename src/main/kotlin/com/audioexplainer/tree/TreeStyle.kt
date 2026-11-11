package com.audioexplainer.tree

import com.audioexplainer.AudioExplainerSettings

/**
 * **The one place a persisted setting becomes a tree style.**
 *
 * Only the tree options that are user settings are read here, out of [AudioExplainerSettings.State]. Chrome,
 * the token box, the nesting indent, the collapse affordance's *place* and the layout are absent on purpose:
 * each is derived by a rule, not a preference.
 *
 * **Why the settings hold enum names rather than a private vocabulary.** `calloutFrame` invented `"bar"` /
 * `"outline"` / `"solid"`, which is a second spelling of a fact the enum already states; here the persisted
 * string *is* [NestLeader.CORNERS]`.name`, matched case-insensitively, so adding a mode adds nothing to keep
 * in step. An unrecognised value degrades to the default instead of failing: a settings file written by a
 * newer build must not stop the tree from drawing, and there is nothing a reader could do about it if it did.
 *
 * **Why every function takes the state.** The no-argument overloads reach the application service, which
 * needs a running IDE; the ones that take a [AudioExplainerSettings.State] need nothing at all, so
 * `PlanProbe` can assert that each shipped default resolves to the same value the style class's own
 * constructor default says. The defaults are written down twice, so the two are checked against each other
 * rather than trusted to agree.
 */
object TreeStyle {

    /** Sane bounds for the height ceiling, wide enough that the spinner is not the thing saying no. */
    const val MIN_MAX_HEIGHT = 100
    const val MAX_MAX_HEIGHT = 4000

    /** The nest-level style the settings currently ask for. Needs a running application. */
    fun nest(): NestStyle = nest(AudioExplainerSettings.getInstance().state)

    /** The per-node style the settings currently ask for. Needs a running application. */
    fun node(): CodeNodeStyle = node(AudioExplainerSettings.getInstance().state)

    /**
     * The nest-level style [state] asks for, leaving every field that is not a setting at its own default —
     * `gapBelow`, `gap`, `inset`, `bottomSlack`, `boxPadBelow` and `nodeSlack` are tuned numbers, not
     * preferences.
     */
    fun nest(state: AudioExplainerSettings.State): NestStyle = NestStyle(
        leader = enumOr(state.treeLeader, NestLeader.entries, builtinNest().leader),
        chevron = enumOr(state.treeChevron, ChevronStyle.entries, builtinNest().chevron),
        stepFold = enumOr(state.treeStepFold, StepFold.entries, builtinNest().stepFold),
        condense = enumOr(state.treeCondense, StepCondense.entries, builtinNest().condense),
        growHosts = state.treeGrowHosts,
    )

    /**
     * The per-node style [state] asks for. [CodeNodeStyle.chrome] is deliberately left alone: it is assigned
     * by node role in [CodeNestView.chromeFor] (top-level, or a different file from its parent) and a value
     * set here would be overwritten on the next `applyRoles` — which would read as a setting that does
     * nothing.
     */
    fun node(state: AudioExplainerSettings.State): CodeNodeStyle = CodeNodeStyle(
        maxHeight = state.treeMaxHeight.coerceIn(MIN_MAX_HEIGHT, MAX_MAX_HEIGHT),
        path = enumOr(state.treePath, NodePath.entries, builtinNode().path),
        elision = enumOr(state.treeElision, NodeElision.entries, builtinNode().elision),
        deemphasis = enumOr(state.treeDeemphasis, NodeDeemphasis.entries, builtinNode().deemphasis),
        grow = state.treeGrowNodes,
    )

    /**
     * **The style classes' own constructor defaults**, so the two copies of the defaults can be compared
     * instead of assumed to agree.
     *
     * There are genuinely two: `NestStyle()` / `CodeNodeStyle()` — which the benches and every internal
     * caller get — and `AudioExplainerSettings.DEF_TREE_*`, which a fresh install gets and Reset restores.
     * They are not derivable from one another (the settings hold strings and cannot import an enum's default
     * without dragging the tree package into the state class), so `PlanProbe` asserts equality. Kotlin default
     * arguments are not callable from a `javac` probe, which is the only reason these are functions.
     *
     * **They are also this object's own fallbacks**, so the degrade path is not a third transcription of the
     * same decisions. Changing a default is then one edit here plus one in `AudioExplainerSettings`, and the
     * probe is what says the two moved together.
     */
    fun builtinNest(): NestStyle = NestStyle()

    /** The per-node half of [builtinNest]. */
    fun builtinNode(): CodeNodeStyle = CodeNodeStyle()

    /**
     * Match [name] against [values] case-insensitively, or hand back [fallback].
     *
     * Takes the constant list rather than being `reified`, so the whole object stays callable from a `javac`
     * probe — the probes' one rule is that they import the production code instead of re-deriving it, and an
     * inline reified helper would have made this the one part they had to copy.
     */
    private fun <T : Enum<T>> enumOr(name: String, values: List<T>, fallback: T): T =
        values.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: fallback
}
