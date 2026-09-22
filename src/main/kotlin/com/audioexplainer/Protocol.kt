package com.audioexplainer

import kotlinx.serialization.Serializable

/**
 * Wire types for the `set_plan` message — **`frames` (structure) + `steps` (timeline)**, Phase 2 of
 * `TreeOfCode.rules.md`.
 *
 * Line numbers are **1-based** and ranges are **inclusive** on both ends, everywhere in this file.
 *
 * **What changed and why the old shape could not stay.** Until Phase 2 the wire format was
 * `Segment{file,start,end,highlights[]}`, mirroring code-explainer's `vscode-extension/src/types.ts`. That
 * shape *fuses* structure and narration: a step cannot span files, one file cannot host two steps in
 * unrelated places, and there is nowhere to say that this frame is called from that line of that other
 * frame. The tree surface needs all three. So structure and timeline are split
 * (the pre-Phase-1 evaluation's shape, amended by every measurement in Phase 1), and this file is no longer a
 * mirror of anything upstream.
 *
 * **The runtime types below the split are gone.** `Segment` and `Highlight` were the in-place editor
 * surface's vocabulary, kept through Phase 2 as `PlanProjection`'s output so five components that Phase 3
 * was going to rewrite anyway did not have to be converted twice. Phase 3 did the rewrite: position is one
 * plan step index, the surface renders `frames`/`steps` directly, and what the sidebar needs is a *view* of
 * the plan rather than a second model of it — [Outline] and [StepView], built by [PlanOutline]. A stale
 * `segments` key in a saved walkthrough is ignored like `view` was, not rejected.
 */

// ─────────────────────────── The schema: frames + steps ───────────────────────────

/**
 * A 1-based inclusive line range, with the **role** that decides how it is drawn.
 *
 * Roles are Phase 2 decision 3, and they are a property of a [Frame]'s `keep` ranges only:
 *
 * - [ROLE_CONTEXT] (the default) — kept so the reader can place the code: the class header, the method
 *   signature, a field the focus refers to. Drawn de-emphasised.
 * - [ROLE_FOCUS] — **the excerpt of interest**: the range a nested node is sized to and lands on
 *   (§1a⁵ 4). At most one per frame; [PlanLoader] reports the rest and infers one when a frame states none.
 *
 * A role on a [Step]'s ranges is **ignored** — a step's ranges are active by definition, which is not
 * something an author can vary. Kept as one type all the same, because two types differing by one ignored
 * field is how a field ends up meaning two things.
 */
@Serializable
data class LineRange(
    val start: Int = 0,
    val end: Int = 0,
    val role: String = ROLE_CONTEXT,
) {
    /** The range as an [IntRange], ordered and never inverted. */
    fun lines(): IntRange = if (end >= start) start..end else end..start

    val isFocus: Boolean get() = role.equals(ROLE_FOCUS, ignoreCase = true)

    companion object {
        const val ROLE_CONTEXT = "context"
        const val ROLE_FOCUS = "focus"
    }
}

/**
 * **One file, plus the lines kept visible in it** — which is exactly one node's constructor arguments
 * ([com.audioexplainer.tree.CodeNodeSpec]), and the reason the tree made this half of the schema *more*
 * natural rather than less.
 *
 * @param id Identity, and the **only** identity: two frames may share one [file] (a caller and a callee in
 *   one document is legal per constraint 2 and the `same-file` fixture exists to prove it reads), so
 *   nothing may key a frame by its path. Referenced by [Step.frame] and by a child's [parent].
 * @param file The real file on disk. Semantic colour follows the file being real (constraint 3), so this is
 *   a path to resolve through the VFS, never a synthetic name.
 * @param title Short label — the member this frame is about (`"MidInvoker.InvokeOn"`). Shown in the node's
 *   caption band and in the sidebar outline.
 * @param explanation Markdown overview of the frame, for the sidebar. Prose, not narration.
 * @param scene **Which set of nodes this frame belongs to.** Frames sharing a `scene` are built as one
 *   tree and are on screen together; a step moves the reader inside its scene, and a step whose scene
 *   differs from the one showing **replaces the whole tree**. Default `0`, so a plan that never mentions
 *   scenes is one scene and behaves exactly as before.
 *
 *   This is what makes a long walkthrough expressible without keeping every file it ever mentions open:
 *   three steps over three files, then five steps over five others, is two scenes rather than eight nodes.
 *   It is also why *"explain this one method"* needs no second surface — a one-frame scene is a one-node
 *   tree. **A [parent] must be in the same scene**; a cross-scene parent is degraded to top level and
 *   reported, because the parent's node does not exist while this one is showing.
 *
 *   The cost is stated in `TreeOfCode.rules.md`'s caps: a scene change releases N editors and creates M, so it is
 *   one nest rebuild — the operation Phase 1 measured 25 times with clean disposal — and the reader's own
 *   collapse and dragged-height state does not survive it.
 * @param parent The frame this one is **called from**, or null for a root. Phase 2 decision 1: the link
 *   lives on the child because a nested child has exactly one parent *by construction* — which is what makes
 *   a second parent unexpressable rather than merely rejected. There is no edge list.
 * @param callLine 1-based line **in the parent's file** where the call into this frame sits. `0` = not
 *   stated, and the child then renders at top level ([com.audioexplainer.tree.EmbedState.NO_CALL_LINE]) —
 *   degrading to the flat shape is legible and dropping the node is not. **It must be a line the parent
 *   keeps**: a call line the parent elided would put this whole frame inside a collapsed fold region, where
 *   it is invisible. [PlanLoader] degrades that case to top level rather than let it disappear.
 * @param callToken The **invoking token** on [callLine] — the text the renderer boxes and runs the leader
 *   from. A line places a child *under* a call; it cannot point at the call *inside* the line, and
 *   `_pricing.Handle(order, verdict)` / `_audit.Handle(order, priced)` can share one. Null ⇒ the whole
 *   trimmed line. A token, not a column pair, because §4.4 rule 2 committed this project to text-search
 *   locators for Rider parity — and because the agent can copy a token out of code it just read.
 * @param callOccurrence Which match of [callToken] on that line, 1-based. Rarely needed.
 * @param keep The lines to show; everything else in the file is elided. The agent states *what to show* and
 *   the plugin picks the placeholder — the same division of labour as [relations] (MoreUI.md §4.3 B3).
 *   **Class/method header rows are listed here as [LineRange.ROLE_CONTEXT] ranges**, by the agent: deriving
 *   an enclosing declaration needs PSI, and measured `firstKept` ran 9–18 across the fixture, so an inferred
 *   header would land the reader on a line the plan never named (Phase 2 decision 6).
 * @param relations Links between two places in this frame's code, drawn per *step* exactly as before. On the
 *   frame rather than the step because a relation spans steps. Endpoint `file`s take part in
 *   [WalkthroughStorage]'s relative-path rewrite, like [file] itself.
 */
@Serializable
data class Frame(
    val id: Int = 0,
    val file: String = "",
    val title: String = "",
    val explanation: String = "",
    val scene: Int = 0,
    val parent: Int? = null,
    val callLine: Int = 0,
    val callToken: String? = null,
    val callOccurrence: Int = 1,
    val keep: List<LineRange> = emptyList(),
    val relations: List<Relation> = emptyList(),
)

/** One frame a [Step] touches, and the ranges it lights up there. */
@Serializable
data class StepFrame(
    val frame: Int = 0,
    val ranges: List<LineRange> = emptyList(),
)

/**
 * **One beat of the narration** — the timeline half of the schema.
 *
 * The tree stays still while the narration walks it, which is the whole point of the split: **the step order
 * is the plan's, not the tree's**, so a step may return to a frame the walk has already left. No synthesised
 * node-major walk can express that (§1b's first requirement).
 *
 * A step may light ranges in **several** frames at once — the original multi-file ask. [frames] is the
 * canonical spelling and `frame` + [ranges] is sugar for the one-frame case, normalised by [frameRefs];
 * **[frames]`[0]` is the primary frame**, which is where the single callout goes and what the editor surface
 * projects to. Ordering expresses that rather than a separate `primary` field, because a second spelling of
 * one fact is a second thing to get wrong.
 *
 * @param ttsText Spoken narration for this step. Plain text: no markdown, no line refs, no paths.
 * @param explanation Optional 2–5 word sidebar label. A tooltip, not prose.
 * @param note Optional one-sentence aside for the in-editor callout — the margin note a reader would want
 *   next to this code. Preferred over [explanation] in the callout, which reads thin there.
 * @param example Optional **one-line** worked example (`Parse("2024-01") → DateOnly(2024, 1, 1)`), drawn
 *   under [note] in the editor's upright font. One line is a hard limit: the callout word-wraps, so a
 *   multi-line snippet would be reflowed into a run-on paragraph and lose the structure that made it a
 *   snippet. The renderer keeps the first line and marks the cut.
 * @param token **Legacy.** The pulse anchor before it grew a line and a time; superseded by [beats] and
 *   no longer documented in `plan-schema.md`. Still decoded, because saved walkthroughs and older plans
 *   carry it: `PlanLoader` reads a step with a [token] and no [beats] as one [Beat] with no line and no
 *   `say`, which fires at audio start and resolves over the step's whole span. Nothing downstream of the
 *   loader reads this field.
 * @param occurrence Which occurrence of the legacy [token], 1-based.
 * @param beats **The pulses this step draws**, each `{ line, token, occurrence?, say? }`. Empty means the
 *   step draws none, which is the normal case. See [Beat].
 */
@Serializable
data class Step(
    val frame: Int? = null,
    val ranges: List<LineRange> = emptyList(),
    val frames: List<StepFrame> = emptyList(),
    val ttsText: String = "",
    val explanation: String? = null,
    val note: String? = null,
    val example: String? = null,
    val token: String? = null,
    val occurrence: Int = 1,
    val beats: List<Beat> = emptyList(),
) {
    /**
     * The frames this step touches, in order, with the one-frame sugar normalised away — so every consumer
     * reads one shape. Empty when the step names no frame at all, which [PlanLoader] reports and drops.
     */
    fun frameRefs(): List<StepFrame> = when {
        frames.isNotEmpty() -> frames
        frame != null -> listOf(StepFrame(frame, ranges))
        else -> emptyList()
    }
}

/**
 * **A pulse timed to a point in the narration** — "when she says *this*, flash *that*".
 *
 * Today a step gets one flash, at the moment its audio starts (`playback_started`). A step that walks
 * through three things says three things and can only point at one. [Step.beats] lets it point at each as
 * it is spoken.
 *
 * **[line] + [token] + [occurrence] is the anchor shape this whole plugin uses** — the same one an
 * [Endpoint] uses for a link end and a [Frame] uses for its call site. There is only ever one mechanism
 * underneath: a plain text scan of one line for the nth match of a string, never PSI and never a column
 * (§4.4 rule 2, Rider parity). What used to make the step's pulse the odd one out was not the mechanism
 * but the missing line: with nothing to search but a range, a token a line or two outside it resolved to
 * nothing, silently. A line makes the pulse checkable at load, where a token never can be.
 *
 * @param line 1-based line in the step's primary frame holding [token]. **0 means the legacy shape** — a
 *   plan that predates this field, read from `Step.token` — and is resolved over the step's whole span
 *   instead of one line.
 * @param token The word to pulse, copied verbatim out of [line]. A beat with no token has nothing to do
 *   and `PlanLoader` drops it.
 * @param occurrence Which match of [token] on [line], 1-based. Only needed when it repeats.
 * @param say A phrase from this step's own [Step.ttsText], deciding **when** the pulse fires. Matching
 *   collapses whitespace and ignores case, and is otherwise a plain substring test. **Blank means "when
 *   the audio starts"**, which is what a step with one pulse and no interest in timing wants — and what
 *   the old `Step.token` did.
 *
 *   Never a sentence *number*: the author, the synthesizer and the player each split sentences differently
 *   (the server splits on an ASCII ender plus whitespace, so `e.g.` splits, and re-chops anything over 320
 *   chars), and nothing can detect the disagreement because only the index ever crossed the wire. A phrase
 *   needs no agreement.
 *
 * **A beat with [say] fires at the start of the sentence containing it, not at the phrase itself.** No TTS
 * engine this plugin uses reports word timings — Kokoro, the primary one, has none to give — so a word's
 * position inside its sentence is interpolated from the sentence's duration, worth about a second of error
 * against a 700 ms pulse. A sentence's start is a sum of real decoded buffer durations and is accurate to a
 * frame. Quoting a phrase from late in a long sentence is therefore legal and early; quote the sentence's
 * opening words when the timing matters.
 */
@Serializable
data class Beat(
    val line: Int = 0,
    val token: String? = null,
    val occurrence: Int = 1,
    val say: String = "",
) {
    /** Whether this beat fires the instant the step's audio starts, rather than at a sentence. */
    val atStart: Boolean get() = say.isBlank()

    /** Whether [say] can be found in [ttsText] at all — what `PlanLoader` validate-and-drops on. */
    fun spokenIn(ttsText: String): Boolean =
        say.isNotBlank() && normalize(ttsText).contains(normalize(say))

    companion object {
        /**
         * The one matching rule: trim, collapse whitespace runs, lowercase. **`sidebar.js`'s `beatKey()`
         * must do the same thing** — this side decides whether to warn an author at load, that side decides
         * whether the beat ever fires, and a phrase accepted here but unmatched there is the one failure
         * mode with no symptom at all. Kept to operations both languages spell identically.
         */
        fun normalize(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")
    }
}

/**
 * The walkthrough payload: the body of a `set_plan` message (minus its `type`) and the on-disk saved-file
 * shape are both this.
 *
 * [language] is the walkthrough's single language code (see [Languages]); every human-facing string in the
 * plan (title, frame titles & explanations, step `ttsText` & labels & notes) is written in it. Missing/blank
 * ⇒ `"en"`.
 *
 * **There is no `view` field.** A plan used to choose between this tree and an in-place spotlight over the
 * user's own editor; round 3 judged a one-frame scene rendered as a one-node tree as good as the in-place
 * surface for the commonest case, so that surface, this field and its `wantsTree()` accessor were deleted
 * together. A stale `"view"` key in a saved walkthrough or an old plan is ignored, not rejected — every
 * decoder here sets `ignoreUnknownKeys`.
 */
@Serializable
data class Plan(
    val title: String = "",
    val language: String = Languages.DEFAULT_CODE,
    val frames: List<Frame> = emptyList(),
    val steps: List<Step> = emptyList(),
    /**
     * **More steps are coming** — the agent will `append_steps` (`Stream.md`). Narration starts on what is
     * here and parks at the end of it instead of settling to paused, until the plan is closed.
     *
     * Defaults to false, so a plan that never mentions it behaves exactly as every plan did before: the
     * end of `steps` is the end of the walkthrough. `frames` is **not** streamed and this does not open it
     * — the tree is built once, from this message, because rebuilding it would close the tab the reader is
     * watching.
     *
     * Deliberately not persisted as meaningful: [WalkthroughStorage] saves whatever plan is current, and a
     * saved walkthrough is by definition finished, so a `true` read back off disk is closed by
     * [Walkthrough.setPlan]'s own load path rather than leaving a file that parks forever.
     */
    val streaming: Boolean = false,
)

// ─────────────────────────── Relations (unchanged, now on the frame) ───────────────────────────

/**
 * One end of a [Relation] — a **text-search locator**, the same shape [Step.token] establishes and for the
 * same reason: the agent can copy a name out of code it just read, whereas a column number is arithmetic it
 * would get wrong. Never PSI, so Rider behaves like IntelliJ.
 *
 * @param file The endpoint's file. Absent ⇒ the frame's own file, which is the common case. A *different*
 *   file is what makes an endpoint "outside": no on-screen arrow can point at it, so the relation renders as
 *   a matched pair of portals instead — one at each end (MoreUI.md §4.3 B3).
 * @param line 1-based line within [file]. `0` = unspecified, which is legitimate for an off-screen target
 *   named only by [symbol] — it just can't anchor an arrow.
 * @param token A literal substring of [line]. Optional, and it does real work: the plugin requires it to be
 *   present on that line, so a token that isn't there condemns the endpoint (a cheap check on the line
 *   number the agent guessed). It also names the endpoint in the pointer form.
 * @param occurrence Which match of [token] on that line, 1-based. Rarely needed.
 * @param symbol Human-readable label for an off-screen target (`"RateTable.Lookup"`). Display only — it is
 *   never resolved as a symbol, because that would mean PSI.
 */
@Serializable
data class Endpoint(
    val file: String? = null,
    val line: Int = 0,
    val token: String? = null,
    val occurrence: Int = 1,
    val symbol: String? = null,
)

/**
 * A relationship between two places in the code, drawn by [SpotlightRenderer] as an arrow when both ends
 * are lines of the file on screen, and as a matched pair of portals — the token tinted and a connector run
 * out into a coloured portal at each end, in whichever node owns it — plus a callout line when they are not.
 *
 * The division of labour is deliberate: **the agent describes the semantics, the plugin picks the picture.**
 * An agent cannot know whether both endpoints are visible, how far apart they land, or whether the target
 * file is even open, so it never chooses between "arrow" and "pointer" — it states the relation and the
 * renderer degrades gracefully (MoreUI.md §4.3 B3).
 *
 * @param kind [KIND_FLOW] (A feeds B) or [KIND_BACKFORTH] (they refer to each other). Anything else is read
 *   as [KIND_FLOW]: `ignoreUnknownKeys` already makes the wire format tolerant, and an unfamiliar kind should
 *   cost a nuance, not the whole relation.
 *
 *   **It draws again.** For a while it did not: round 6's unified link system takes direction from the
 *   geometry — which end the link *arrives* at — so both kinds rendered identically and `kind` reached only the
 *   log. The agent-authored-plan gate found it, reading the docs' promise of two arrowheads against a drawing
 *   that had stopped keeping it. It was documented rather than repaired, because the drawing had just been
 *   accepted at round 11 and changing an accepted design unasked was not that phase's business.
 *
 *   What it draws is the promise and no more than it: [KIND_BACKFORTH] means the link **arrives at both ends**,
 *   so both ends get the arrowhead an arrived-at end already had, and both rail indicators point *out* of the
 *   channel instead of one in and one out. Nothing else moves — same colour, same lane, same routing, same
 *   hover, same hit test — because the two kinds are one relation drawn two ways and not two drawings. That
 *   distinction is what round 6 bought and what this is careful not to spend.
 * @param note Optional short phrase naming what flows or why they're linked ("parsed value"). Drawn beside
 *   the arrow, or after the target in the pointer form.
 */
@Serializable
data class Relation(
    val kind: String = KIND_FLOW,
    val from: Endpoint = Endpoint(),
    val to: Endpoint = Endpoint(),
    val note: String? = null,
) {
    companion object {
        const val KIND_FLOW = "flow"
        const val KIND_BACKFORTH = "backforth"
    }
}

/**
 * **Does [file] name the same file as [frameFile]** — tolerating the relative form an agent is likely to write
 * for a sibling file. Case-insensitive, which is right on Windows and harmless elsewhere: the worst a false
 * negative costs is an arrow degrading to a pointer.
 *
 * **It lives here, in the file with no UI in it, because three callers want it and one of them runs headless.**
 * `SpotlightRenderer` asks it of an endpoint, `TreePlanView` asks whether a node is showing that file, and
 * `PlanLoader` asks which frame owns a relation's far end — and the loader is driven by a probe with no IDE
 * around it, so reaching into a renderer's companion for a string comparison would have made a class full of
 * colours and scaled pixels a load-bearing dependency of plan validation.
 */
internal fun sameFilePath(file: String, frameFile: String): Boolean {
    val a = file.trim().replace('\\', '/').removePrefix("./")
    if (a.isEmpty()) return true
    val b = frameFile.replace('\\', '/')
    return b.equals(a, ignoreCase = true) || b.endsWith("/$a", ignoreCase = true)
}

/**
 * **True when this endpoint names a line of a step's active band** — i.e. the relation holding it is one this
 * step draws. [startLine] and [endLine] are 1-based and inclusive.
 *
 * Measured against the step's **primary** range — its first — which is the one the callout and the links are
 * drawn around. A fileless endpoint means "this frame's own file", which is the usual case.
 *
 * **One owner, deliberately.** This is the rule that decides which step a relation appears on, and `PlanLoader`
 * now reports relations no step will ever draw. A validator carrying its own copy of the rule agrees with the
 * drawing right up until one of the two is edited, and then reports a defect that is not there or misses one
 * that is — either of which is worse than not reporting at all.
 */
internal fun Endpoint.inStepBand(startLine: Int, endLine: Int, frameFile: String): Boolean =
    line > 0 && line >= startLine && line <= endLine &&
        (file == null || sameFilePath(file, frameFile))

// ─────────────────────────── The outline: what the sidebar draws ───────────────────────────

/**
 * **One step of the plan, as one row of the sidebar outline.**
 *
 * [index] is the plan's own step index and it is the whole position model — Phase 3 flattened the two-level
 * `(segment, highlightIndex)` pair that four components had to keep in agreement into this one number, so a
 * click, a narration advance and a tree paint all name a step the same way.
 *
 * **There is no third field, and that is the point.** The row used to carry `start`–`end` line numbers; they
 * were dropped on the reader's word — *"putting line numbers/FileNames in outline is actually redundant …
 * it should only show which step/substep we're currently in"*. A file and a line still exist, on
 * [StepView], which is what the Now Playing location link reads.
 */
@Serializable
data class OutlineStep(val index: Int, val label: String)

/**
 * A run of consecutive steps sharing a primary frame — the outline's middle level, and the only one it
 * invents rather than reads off the plan.
 *
 * It is the rule the deleted `PlanProjection` used to make a segment, kept because a reader wants to see
 * which frame a run of steps is about and the schema does not spell that out anywhere else. A frame that
 * non-adjacent steps return to produces **two** of these, never one merged run.
 */
@Serializable
data class OutlineGroup(val frame: Int, val title: String, val steps: List<OutlineStep>)

/**
 * **One mount of one scene** — the outline's top level, supplied by decision 11 rather than invented.
 *
 * One row per *crossing*, not per distinct scene: a plan that leaves a scene and comes back rebuilds the
 * tree twice, so it gets two rows. That is what lets a reader see **why** the tree is about to be replaced,
 * which round 3 asked as a question and this answers structurally.
 *
 * [label] is `"Scene 2"`, numbered on first appearance and reused on a revisit, so the number names a set of
 * nodes. It carries no file count: the outline says which step you are on and nothing else.
 */
@Serializable
data class OutlineScene(val id: Int, val label: String, val groups: List<OutlineGroup>)

/**
 * The whole outline, as pushed to the sidebar.
 *
 * @param scened Whether to draw the scene level at all. False for a single-scene plan — three of the four
 *   acceptance plans — where a lone `"Scene 1"` header over everything is an indent that separates nothing.
 *   The level appears exactly when it starts carrying information, which is why the commonest plan's outline
 *   is unchanged by Phase 3 and `scenes-tour.json`'s gains a level.
 * @param steps How many steps the plan has, for the global progress counter.
 */
@Serializable
data class Outline(
    val scenes: List<OutlineScene> = emptyList(),
    val scened: Boolean = false,
    val steps: Int = 0,
)

/**
 * **What the sidebar shows for the step now playing** — derived per step by [PlanOutline.stepView], stored
 * nowhere.
 *
 * The Now Playing panel needs the frame's identity and prose beside the step's own narration, and it is the
 * one surface where a file and a line still belong: [file]/[start]/[end] back the clickable location link,
 * which `TreePlanController.revealInTree` turns into a scroll inside the tree.
 */
@Serializable
data class StepView(
    val index: Int,
    /** The primary frame's title — the member this step is about. */
    val title: String,
    /** The primary frame's markdown overview. Prose about the frame, not about this step. */
    val explanation: String = "",
    /** The step's spoken narration, which is also the read-along transcript. */
    val ttsText: String = "",
    /** The step's own short label, as the outline row spells it. */
    val label: String = "",
    val file: String = "",
    /** The enclosing span of the step's ranges in its primary frame. Location link only. */
    val start: Int = 0,
    val end: Int = 0,
    /**
     * The step's [Step.beats], carried verbatim. The webview owns beat timing (§4.2 invariant 5): it holds
     * the sentence map and the only clock that knows what is being *heard*, so it decides when each beat is
     * due and posts the index back. The plugin never resolves a phrase.
     */
    val beats: List<Beat> = emptyList(),
)

/**
 * Extension → agent action delivered over `/api/actions` long-poll. `type` is fixed to `"user_action"`.
 * (Produced by the sidebar in Phase 4.)
 */
@Serializable
data class UserAction(
    val action: String,
    val segmentId: Int,
    val question: String? = null,
    val type: String = "user_action",
)
