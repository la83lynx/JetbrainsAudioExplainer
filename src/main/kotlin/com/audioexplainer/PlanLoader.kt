package com.audioexplainer

import com.intellij.openapi.diagnostic.Logger

/**
 * How loud a [PlanIssue] is, and the three levels mean three different things to whoever reads the report.
 *
 * The rule is that **nothing resolves silently**: a plan that renders three of its four nodes must say so, or
 * the fourth reads as a renderer defect. A report that cannot separate "this was dropped" from "this is over a
 * soft cap" is a report the reader learns to ignore.
 */
enum class IssueLevel {
    /** Something in the plan was **dropped** — a frame, a step, a step's whole frame reference. */
    ERROR,

    /** It renders, and it renders *differently from what was authored* — a degrade, not a loss. */
    WARN,

    /** Over a soft cap, or a value the loader had to derive because the plan did not state it. */
    INFO,
}

/**
 * One thing wrong with a plan, in the plan's own vocabulary.
 *
 * [code] is a stable kebab-case identifier (`frame.cycle`, `step.range-outside-keep`) so a bench check or a
 * grep can match on it, and [message] is what a human reads. Both reach three places: the log, the
 * `set_plan` HTTP response — which is the only channel that reaches **the agent that authored the plan** —
 * and the tree view's own report.
 */
data class PlanIssue(val level: IssueLevel, val code: String, val message: String) {
    override fun toString(): String = "[${level.name.lowercase()}] $code — $message"
}

/**
 * One frame after validation: its parent link resolved (or removed), its depth known, its `keep` ranges
 * ordered, and its focus range decided rather than guessed at render time.
 *
 * @param parentId The frame this one nests in, or null for a root. **Null here can mean "authored as a
 *   root" or "degraded to one"** — the difference is in [ResolvedPlan.issues], never silent.
 * @param focus The excerpt of interest: what a nested node is sized to and lands on. Stated by the
 *   plan when it carries a `focus` role, inferred from range order otherwise (and the inference is
 *   reported).
 */
class ResolvedFrame(
    val frame: Frame,
    val parentId: Int?,
    val depth: Int,
    val keep: List<LineRange>,
    val focus: LineRange?,
) {
    val id: Int get() = frame.id
    val file: String get() = frame.file

    /** Which set of nodes this frame is mounted with — see [Frame.scene]. */
    val scene: Int get() = frame.scene

    /** The lines this frame shows — every kept range, ordered. */
    fun keptLines(): Int = keep.sumOf { it.lines().count() }

    /**
     * **True when this frame's window actually displays 1-based [line]** — the row a link half, a reveal or
     * an endpoint check needs to land on.
     *
     * One derivation, because three callers ask it: [checkRelations] (is this endpoint on a fold
     * placeholder?), `TreePlanView.frameOwning` (which of several frames over one file owns this line?) and
     * the reveal behind a portal click. It is the predicate that makes "which frame owns this file" a
     * question about a *line* rather than a path — see `frameOwning` for what answering it path-only cost.
     */
    fun shows(line: Int): Boolean = keep.any { line in it.lines() }

    /** Whether the renderer will try to embed this frame in its parent at a call site. */
    fun nests(): Boolean = parentId != null && frame.callLine > 0
}

/** One step after validation: only frame references that name a frame the plan actually renders. */
class ResolvedStep(val step: Step, val frames: List<StepFrame>) {
    /** The primary frame's id — where the single callout goes and what the editor surface projects to. */
    val primaryFrameId: Int get() = frames.first().frame

    /** This step's ranges in [frameId], or empty if it does not touch that frame. */
    fun rangesIn(frameId: Int): List<LineRange> =
        frames.firstOrNull { it.frame == frameId }?.ranges ?: emptyList()
}

/**
 * **One mounted set of nodes, and the steps narrated while it is up.**
 *
 * A scene is the unit the tree is built from: [frames] go into one `CodeNestView.setTree`, [stepIndices] are
 * the steps that play against it, and crossing out of it rebuilds the nest. It exists because the alternative
 * two models do not:
 *
 * - **One node per frame for the whole plan** cannot express a long walkthrough — a plan explaining twelve
 *   files would keep twelve editors open.
 * - **Retargeting one node's document per step is not available.** Verified against the platform rather than
 *   assumed: `Editor`, `EditorEx` and `EditorImpl` expose `getDocument()` and no setter, and every
 *   `EditorFactory` factory method takes the document as a *creation* parameter with `releaseEditor` as the
 *   only teardown verb. So "show another file in this node" **is** "release this editor and create another",
 *   which is what a rebuild already does. The third model collapses into this one, and there is no cheaper
 *   path hiding behind it.
 *
 * Contiguity is not required and not assumed: scenes are keyed by [Frame.scene], so a plan may return to a
 * scene it left. Returning **rebuilds** it — the nest holds one scene at a time by design, so nothing is
 * cached and the reader's collapse/drag state does not survive the round trip.
 */
class Scene(
    val id: Int,
    val frames: List<ResolvedFrame>,
    val stepIndices: List<Int>,
) {
    /** Lines of code this scene puts on screen at once — what [PlanLoader.LINE_BUDGET] is measured against. */
    fun keptLines(): Int = frames.sumOf { it.keptLines() }
}

/**
 * A plan that has been checked and is safe to render, plus everything that was wrong with it.
 *
 * [frames] and [steps] are what survived; anything dropped or degraded is in [issues]. A caller renders
 * [frames]/[steps] and *reports* [issues] — it never has to decide what to do about a malformed plan,
 * because that decision was taken here and written down.
 */
class ResolvedPlan(
    val plan: Plan,
    val frames: List<ResolvedFrame>,
    val steps: List<ResolvedStep>,
    val issues: List<PlanIssue>,
) {
    private val byId: Map<Int, ResolvedFrame> = frames.associateBy { it.id }

    fun frame(id: Int): ResolvedFrame? = byId[id]

    /**
     * **The scenes, in the order the steps first reach them** — the mount order, not [Frame.scene]'s numeric
     * order, because the reader meets them in step order and a plan is free to number them however it likes.
     *
     * A scene with frames but no step still appears: it renders as structure, which is the same treatment
     * `frame.no-step` gives a lone frame.
     */
    val scenes: List<Scene> = buildScenes(frames, steps)

    /** The scene a step plays in — its primary frame's. Null only for a step index that does not exist. */
    fun sceneOfStep(stepIndex: Int): Int? =
        steps.getOrNull(stepIndex)?.let { byId[it.primaryFrameId]?.scene }

    fun scene(id: Int): Scene? = scenes.firstOrNull { it.id == id }

    /**
     * How many times stepping straight through this plan rebuilds the tree.
     *
     * Not `scenes.size - 1`: a plan that leaves a scene and comes back pays for both crossings. This is the
     * number the scene model's cost is actually stated in — one nest rebuild each — so it is measured rather
     * than described.
     */
    fun sceneTransitions(): Int =
        steps.indices.mapNotNull { sceneOfStep(it) }.zipWithNext().count { (a, b) -> a != b }

    /** Frames that are not nested in another — the tree's top level, in plan order. */
    fun roots(): List<ResolvedFrame> = frames.filter { it.parentId == null }

    fun isEmpty(): Boolean = frames.isEmpty() || steps.isEmpty()

    fun issues(level: IssueLevel): List<PlanIssue> = issues.filter { it.level == level }

    /** Human-readable one-line-per-issue summary, for a report file or a notification. */
    fun issueReport(): String = issues.joinToString("\n") { it.toString() }
}

/**
 * Group frames into scenes, ordered by when the steps first reach them.
 *
 * Step order rather than [Frame.scene] order, because the mount order is what the reader experiences and a
 * plan is entitled to number its scenes arbitrarily. Scenes no step names are appended in frame order, so a
 * scene that renders as structure only is still present and countable rather than quietly absent.
 */
private fun buildScenes(frames: List<ResolvedFrame>, steps: List<ResolvedStep>): List<Scene> {
    val framesOf = frames.groupBy { it.scene }
    val sceneOfFrame = frames.associate { it.id to it.scene }
    val stepsOf = LinkedHashMap<Int, MutableList<Int>>()
    for ((i, step) in steps.withIndex()) {
        val scene = sceneOfFrame[step.primaryFrameId] ?: continue
        stepsOf.getOrPut(scene) { mutableListOf() } += i
    }
    val order = LinkedHashSet(stepsOf.keys)
    order += framesOf.keys
    return order.map { id -> Scene(id, framesOf[id].orEmpty(), stepsOf[id].orEmpty()) }
}

/**
 * **Validation, and the only place a malformed plan is interpreted.**
 *
 * Every check here exists because the failure has no sensible render-time recovery: a cycle would land as
 * [com.audioexplainer.tree.EmbedState.FAILED] by edge order, an over-deep frame would put a few hundred
 * pixels of code inside two thousand, a step lighting an elided line would highlight a fold placeholder and
 * read as a lost step. So the plan is checked and **the plan is wrong** — the renderer is never asked to
 * guess.
 *
 * Two things this deliberately does **not** do:
 *
 * - **No VFS, no documents, no read action.** This is pure structure over line numbers, so it runs on
 *   whatever thread the HTTP request arrived on. Resolution — does the file exist, is the call token really
 *   on that line, is the line past the end of the document — needs the VFS and belongs to whoever is about
 *   to draw. Both halves produce the same [PlanIssue] shape and end up in the same report.
 * - **No clamping of what it cannot see.** A range past the end of a file is not detectable here and is
 *   clamped by the renderer as it always has been.
 */
object PlanLoader {

    private val log = Logger.getInstance(PlanLoader::class.java)

    // ── Caps (documented for authors in `plan-schema.md`). Soft unless said otherwise. ──

    /**
     * **Nesting depth, 0-based: a root plus two levels of nesting.** The binding cap — depth 3 is proven,
     * depth 4 has never been run, and depth
     * compounds where nothing else does (a node's height is its own excerpt *plus* its children at full
     * height: measured 1584 / 954 / 510 px). Past it the frame renders **at top level**, which is the same
     * answer the renderer already gives a child whose edge names no call line.
     */
    const val MAX_NEST_DEPTH: Int = 2

    /**
     * Sibling frames in one host. Additive, not nested — a taller host and nothing worse. Soft. Six still read
     * cleanly as siblings; unlike [MAX_NEST_DEPTH], this cap does not compound.
     *
     * **[LINE_BUDGET] is still what binds**: six siblings at ~12 kept lines each is 72 before the parent's own
     * header rows, and the remedy is to split the scene. The step fold means siblings cost the reader only the
     * one the step is in, but the budget still counts them all, because a reader who expands two of them by
     * hand is owed a scene that fits when they do.
     */
    const val MAX_SIBLINGS: Int = 6

    /** `keep` ranges per frame. Elision is per-gap, so the mechanism does not care; nothing above 3 has been run. */
    const val MAX_KEEP_RANGES: Int = 3

    /**
     * **Lines the loader will add to a kept range** so a step's stray lines land on code — the gap plus the
     * unkept step lines. The one measured case was 3 (a member's last lines); 6 covers a closing brace and a
     * blank or two past that, and stays well under a placeholder's worth of code the author chose to fold.
     */
    const val WIDEN_MAX_LINES: Int = 6

    /**
     * **Lines of code on screen, across every frame.** The tree lives in a tool window beside a sidebar, at
     * whatever zoom and monitor the reader keeps it; measured viewports there range from 25 to 55 lines, and
     * the cap sits at the top of that range so it flags only what fails on every screen. (A three-deep chain
     * of 26 + 26 + 14 = 66 kept lines is flagged, correctly: it scrolls.)
     *
     * **Reported once per scene**, because a scene is exactly "the frames on screen at once". The fold
     * ([com.audioexplainer.tree.StepFold]) collapses every nesting a step does not use, so the lines this cap
     * is really about are **the frames one step needs open together**. [com.audioexplainer.tree.StepCondense]
     * folds a nested step's ancestors to their headers and the call line, but the count still sums them, so a
     * chain is the one shape it overstates.
     */
    const val LINE_BUDGET: Int = 60

    /**
     * Validate [plan] and return what is renderable plus everything wrong with it.
     *
     * The order of the passes is load-bearing: frames are admitted first (so a step can be checked against
     * the frames that survive), parent links are resolved before depth (so a re-rooted frame's children get
     * the depth they will actually have), and the soft caps are counted last, on the resolved tree rather
     * than on what was authored.
     */
    fun resolve(plan: Plan): ResolvedPlan {
        val issues = mutableListOf<PlanIssue>()

        // Before anything reads a `keep`: the focus, the call-site checks and the relation endpoints all see
        // the widened ranges, so a stray step line fixed here is fixed for every one of them.
        val admitted = widenKeepForSteps(admitFrames(plan.frames, issues), plan.steps, issues)
        val links = resolveLinks(admitted, issues)
        val nesting = resolveDepths(admitted, links, issues)

        val resolvedFrames = admitted.map { frame ->
            // One derivation of the ordered keep list, shared with the focus decision below: "the second
            // kept range" has to mean the same thing in both, and a fact derived twice is how a change
            // reaches one copy.
            val keep = orderedKeep(frame, issues)
            ResolvedFrame(
                frame = frame,
                parentId = nesting.links[frame.id],
                depth = nesting.depth[frame.id] ?: 0,
                keep = keep,
                focus = focusOf(frame.id, keep, issues),
            )
        }
        val byId = resolvedFrames.associateBy { it.id }

        checkCallLines(resolvedFrames, byId, issues)
        val relinked = dropUnkeptCallSites(resolvedFrames, byId, issues)
        val steps = resolveSteps(plan.steps, byId, issues)

        // After the steps, because half of what it reports is about which step draws a relation — and against
        // `relinked`, because a frame re-rooted by `dropUnkeptCallSites` keeps its relations and its keep.
        val drawable = checkRelations(relinked, steps, issues)
        countSoftCaps(drawable, steps, issues)

        val resolved = ResolvedPlan(plan, drawable, steps, issues)
        report(resolved)
        return resolved
    }

    // ── Frames ──

    /**
     * Frames with an identity, a file and something to show. **First id wins** on a duplicate, because a
     * later frame silently replacing an earlier one would move every step that named it.
     */
    private fun admitFrames(frames: List<Frame>, issues: MutableList<PlanIssue>): List<Frame> {
        val out = mutableListOf<Frame>()
        val seen = HashSet<Int>()
        for (f in frames) {
            if (!seen.add(f.id)) {
                issues += PlanIssue(
                    IssueLevel.ERROR, "frame.duplicate-id",
                    "frame id ${f.id} appears more than once (${f.file}); the later one is dropped — " +
                        "frames are keyed by id, so a duplicate would move every step that names it",
                )
                continue
            }
            if (f.file.isBlank()) {
                issues += PlanIssue(
                    IssueLevel.ERROR, "frame.no-file",
                    "frame ${f.id} has no file; dropped",
                )
                continue
            }
            if (f.keep.isEmpty()) {
                issues += PlanIssue(
                    IssueLevel.ERROR, "frame.no-keep",
                    "frame ${f.id} (${f.file}) keeps no lines; dropped — a frame is a file plus the lines " +
                        "kept visible in it, and there is no picture without them",
                )
                continue
            }
            out += f
        }
        if (frames.isNotEmpty() && out.isEmpty()) {
            issues += PlanIssue(IssueLevel.ERROR, "plan.no-frames", "no frame in the plan is renderable")
        }
        return out
    }

    /**
     * Every frame's parent link, degraded to null where it cannot stand: unknown id, itself, a dropped
     * frame, or a cycle.
     *
     * **The cycle is broken at the frame that closes it.** `parent` on the child makes two parents per frame
     * unexpressable by construction; a cycle is not, so it is cut here.
     */
    private fun resolveLinks(frames: List<Frame>, issues: MutableList<PlanIssue>): Map<Int, Int?> {
        val ids = frames.map { it.id }.toHashSet()
        val sceneOf = frames.associate { it.id to it.scene }
        val direct = HashMap<Int, Int?>()
        for (f in frames) {
            val p = f.parent
            direct[f.id] = when {
                p == null -> null
                p == f.id -> {
                    issues += PlanIssue(
                        IssueLevel.WARN, "frame.self-parent",
                        "frame ${f.id} names itself as its parent; rendered at top level",
                    )
                    null
                }
                p !in ids -> {
                    issues += PlanIssue(
                        IssueLevel.WARN, "frame.unknown-parent",
                        "frame ${f.id} names parent $p, which no renderable frame has; rendered at top level",
                    )
                    null
                }
                // A parent in another scene is not on screen when this frame is, so there is no editor to
                // embed into and no call line to sit under. Same shape as `unknown-parent` — the node the
                // link names does not exist at the moment it would be needed — and the same degrade.
                sceneOf[p] != f.scene -> {
                    issues += PlanIssue(
                        IssueLevel.WARN, "frame.parent-other-scene",
                        "frame ${f.id} (scene ${f.scene}) names parent $p, which is in scene " +
                            "${sceneOf[p]}; only one scene is mounted at a time, so there is no node to " +
                            "nest into and the frame renders at top level. Put both in one scene, or drop " +
                            "the parent link",
                    )
                    null
                }
                else -> p
            }
        }
        // Break cycles: walk up from each frame, and cut the link of the frame that closes the loop.
        for (f in frames) {
            val seen = linkedSetOf(f.id)
            var cur = direct[f.id]
            while (cur != null) {
                if (!seen.add(cur)) {
                    issues += PlanIssue(
                        IssueLevel.WARN, "frame.cycle",
                        "frame ${f.id} is part of a parent cycle (${seen.joinToString(" → ")} → $cur); " +
                            "its parent link is cut and it is rendered at top level — nesting a cycle would " +
                            "land the second embedding as FAILED by edge order",
                    )
                    direct[f.id] = null
                    break
                }
                cur = direct[cur]
            }
        }
        return direct
    }

    /**
     * Depth per frame, with **over-deep frames re-rooted** rather than refused. Processed shallowest-first
     * so a re-rooted frame's own children measure from where they
     * actually land.
     */
    private fun resolveDepths(
        frames: List<Frame>,
        links: Map<Int, Int?>,
        issues: MutableList<PlanIssue>,
    ): Nesting {
        val mutableLinks = HashMap(links)
        val depth = HashMap<Int, Int>()
        // Resolve in dependency order: a frame whose parent has no depth yet is deferred.
        var pending = frames.map { it.id }
        while (pending.isNotEmpty()) {
            val next = mutableListOf<Int>()
            var progressed = false
            for (id in pending) {
                val p = mutableLinks[id]
                if (p == null) {
                    depth[id] = 0
                    progressed = true
                    continue
                }
                val pd = depth[p]
                if (pd == null) {
                    next += id
                    continue
                }
                if (pd + 1 > MAX_NEST_DEPTH) {
                    issues += PlanIssue(
                        IssueLevel.WARN, "frame.depth-exceeded",
                        "frame $id would nest at depth ${pd + 1}, past the cap of $MAX_NEST_DEPTH " +
                            "(a root plus two levels); rendered at top level instead. Depth compounds — a " +
                            "host is its own excerpt plus its children at full height",
                    )
                    mutableLinks[id] = null
                    depth[id] = 0
                } else {
                    depth[id] = pd + 1
                }
                progressed = true
            }
            if (!progressed) {
                // Unreachable once cycles are cut, but a silent infinite loop is the one outcome worse
                // than a wrong depth.
                for (id in next) depth[id] = 0
                break
            }
            pending = next
        }
        return Nesting(mutableLinks, depth)
    }

    /**
     * Depth and the **final** parent link, together, because one pass decides both: re-rooting an over-deep
     * frame changes its link, and its children's depth follows from where it actually landed. Returning them
     * separately is how the two would come to disagree.
     */
    private class Nesting(val links: Map<Int, Int?>, val depth: Map<Int, Int>)

    /**
     * **A step lighting a few lines just past its frame's `keep` widens the nearest kept range** instead of
     * being a `[warn]`.
     *
     * A `[warn]` sends the agent back to re-send the whole plan (~25s at xhigh) to fix what is almost always
     * an off-by-a-few at the end of a member. For a handful of lines next to what the author kept, their own
     * step is the better statement of what they meant to show.
     *
     * The nearest range is grown to cover the step range and any gap between them, keeping its role (so a
     * widened focus is still the focus). Only when that adds at most [WIDEN_MAX_LINES] lines not already kept;
     * further away it is a new excerpt the author never chose, and stays [resolveSteps]'s `[warn]`.
     */
    private fun widenKeepForSteps(
        frames: List<Frame>,
        steps: List<Step>,
        issues: MutableList<PlanIssue>,
    ): List<Frame> {
        val keeps = frames.associate { it.id to it.keep.toMutableList() }
        for ((i, step) in steps.withIndex()) {
            for (ref in step.frameRefs()) {
                val keep = keeps[ref.frame]?.takeIf { it.isNotEmpty() } ?: continue
                for (r in ref.ranges) {
                    val lines = r.lines()
                    if (lines.first <= 0) continue
                    fun kept(line: Int) = keep.any { line in it.lines() }
                    if (lines.all(::kept)) continue
                    val nearest = keep.indices.minBy { k -> gapBetween(keep[k].lines(), lines) }
                    val old = keep[nearest]
                    val grown = minOf(old.lines().first, lines.first)..maxOf(old.lines().last, lines.last)
                    val added = grown.count { !kept(it) }
                    if (added > WIDEN_MAX_LINES) continue
                    keep[nearest] = LineRange(grown.first, grown.last, old.role)
                    issues += PlanIssue(
                        IssueLevel.INFO, "step.keep-widened",
                        "step ${i + 1} lights lines ${r.start}-${r.end} of frame ${ref.frame}, just past what it " +
                            "keeps, so its kept range ${old.start}-${old.end} is widened to " +
                            "${grown.first}-${grown.last} ($added line(s)). Nothing to re-send for",
                    )
                }
            }
        }
        return frames.map { f -> keeps[f.id]?.takeIf { it != f.keep }?.let { f.copy(keep = it) } ?: f }
    }

    /** Lines strictly between two ranges; 0 when they touch or overlap. */
    private fun gapBetween(a: IntRange, b: IntRange): Int = maxOf(0, maxOf(a.first, b.first) - minOf(a.last, b.last) - 1)

    /** Kept ranges in reading order, so "the second range" means the same thing everywhere. */
    private fun orderedKeep(frame: Frame, issues: MutableList<PlanIssue>): List<LineRange> {
        val bad = frame.keep.filter { it.start <= 0 || it.end <= 0 }
        if (bad.isNotEmpty()) {
            issues += PlanIssue(
                IssueLevel.WARN, "frame.keep-nonpositive",
                "frame ${frame.id} has ${bad.size} kept range(s) with a line number below 1; " +
                    "lines are 1-based and these are clamped",
            )
        }
        return frame.keep
            .map { LineRange(it.start.coerceAtLeast(1), it.end.coerceAtLeast(1), it.role) }
            .sortedBy { it.lines().first }
    }

    /**
     * The frame's focus range — **stated, or inferred and reported.**
     *
     * The inference guesses at authoring: a frame keeps its class header first and the member it is about
     * second. The schema can state the focus, so a frame that does not is one whose landing the plan never
     * chose — hence the report.
     */
    private fun focusOf(frameId: Int, ordered: List<LineRange>, issues: MutableList<PlanIssue>): LineRange? {
        val stated = ordered.filter { it.isFocus }
        if (stated.size > 1) {
            issues += PlanIssue(
                IssueLevel.WARN, "frame.multiple-focus",
                "frame $frameId marks ${stated.size} ranges as focus; the first " +
                    "(${stated.first().start}-${stated.first().end}) is used — a node lands on one excerpt",
            )
        }
        if (stated.isNotEmpty()) return stated.first()
        val inferred = ordered.getOrNull(1) ?: ordered.firstOrNull()
        if (inferred != null) {
            issues += PlanIssue(
                IssueLevel.INFO, "frame.focus-inferred",
                "frame $frameId marks no range as focus; using ${inferred.start}-${inferred.end} " +
                    "(the second kept range) as the excerpt it is sized to and lands on",
            )
        }
        return inferred
    }

    /** A nested frame with no call line renders at top level — the renderer's own degrade, said out loud. */
    private fun checkCallLines(
        frames: List<ResolvedFrame>,
        byId: Map<Int, ResolvedFrame>,
        issues: MutableList<PlanIssue>,
    ) {
        for (f in frames) {
            val parent = f.parentId?.let { byId[it] } ?: continue
            if (f.frame.callLine <= 0) {
                issues += PlanIssue(
                    IssueLevel.INFO, "frame.no-call-line",
                    "frame ${f.id} names parent ${parent.id} but no callLine; it renders at top level " +
                        "(NO_CALL_LINE) — there is no line to put it under",
                )
            }
            if (f.frame.callToken.isNullOrBlank() && f.frame.callLine > 0) {
                issues += PlanIssue(
                    IssueLevel.INFO, "frame.no-call-token",
                    "frame ${f.id} gives callLine ${f.frame.callLine} without a callToken; the whole " +
                        "trimmed line is boxed. Two calls can share a line, so name the token where they do",
                )
            }
        }
    }

    /**
     * **A call line the parent elided cannot host a child.** The inlay would land inside a collapsed fold
     * region and the whole nested frame would be invisible — a loss, not a degrade, and the one failure mode
     * that looks exactly like the renderer having dropped a node. So the link is cut and the frame renders
     * at top level, where it can at least be read.
     */
    private fun dropUnkeptCallSites(
        frames: List<ResolvedFrame>,
        byId: Map<Int, ResolvedFrame>,
        issues: MutableList<PlanIssue>,
    ): List<ResolvedFrame> = frames.map { f ->
        val parent = f.parentId?.let { byId[it] }
        val line = f.frame.callLine
        if (parent == null || line <= 0) return@map f
        val kept = parent.keep.any { line in it.lines() }
        if (kept) return@map f
        issues += PlanIssue(
            IssueLevel.WARN, "frame.call-line-not-kept",
            "frame ${f.id} is called from line $line of frame ${parent.id}, which frame ${parent.id} does " +
                "not keep; nesting it there would hide it inside a fold placeholder, so it renders at top " +
                "level. Add $line to frame ${parent.id}'s keep ranges to nest it",
        )
        ResolvedFrame(f.frame, parentId = null, depth = 0, keep = f.keep, focus = f.focus)
    }

    // ── Relations ──

    /**
     * **What the loader can say about `relations` without opening a file** — which is not everything.
     *
     * **Every check here is structural** — the plan against itself — because that is what this class is. It
     * never touches the filesystem: `frame.call-line-not-kept` compares a line against the frame's own `keep`,
     * not against the file, and so does everything below. So the errors that need the *code* — a line past the
     * end of the file, a `token` that is not on its line, a `file` that does not exist — are still not
     * reported, and the schema doc still says so. What is caught is every way a relation can fail to be
     * **drawn**, which is the half an author cannot see went wrong: an end on an elided line looks like a
     * rendering fault, a relation on no step looks like the plugin dropped it, and a sixth link on one step
     * looks like nothing at all.
     *
     * The band rule is [inStepBand] and the path rule is [sameFilePath], both shared with the renderer that
     * draws these — see [inStepBand] for why a validator must not carry its own copy.
     *
     * **An end on an elided line drops the relation** rather than drawing it into the placeholder, and says so
     * at `info`: as a `warn`, an agent fixes it by re-sending the whole plan (~20s for one arrow). A link into
     * nothing is worse than no link, so the plugin takes the author's cheaper fix itself. Returns the frames with only
     * the relations left that can be drawn.
     */
    private fun checkRelations(
        frames: List<ResolvedFrame>,
        steps: List<ResolvedStep>,
        issues: MutableList<PlanIssue>,
    ): List<ResolvedFrame> {
        // Counted across every frame, matching `TreePlanView`'s cap rather than the renderer's: the renderer
        // caps one frame's relations and the tree caps the step's, and it is the step's the reader meets.
        val perStep = IntArray(steps.size)
        return frames.map { f -> checkFrameRelations(f, frames, steps, perStep, issues) }.also {
            for ((s, n) in perStep.withIndex()) {
                if (n <= LinkArt.SLOTS) continue
                issues += PlanIssue(
                    IssueLevel.WARN, "relation.step-exceeded",
                    "step ${s + 1} carries $n relations, past the ${LinkArt.SLOTS} routing lanes a step has; " +
                        "the ones past the ${LinkArt.SLOTS}th are not drawn at all. Spread them over more steps " +
                        "— and note that links leaving one line cost that line room, where links leaving " +
                        "different lines cost nothing",
                )
            }
        }
    }

    private fun checkFrameRelations(
        f: ResolvedFrame,
        frames: List<ResolvedFrame>,
        steps: List<ResolvedStep>,
        perStep: IntArray,
        issues: MutableList<PlanIssue>,
    ): ResolvedFrame {
        val kept = mutableListOf<Relation>()
        for ((i, r) in f.frame.relations.withIndex()) {
            val where = "relation ${i + 1} of frame ${f.id}"
            val kind = r.kind.trim()
            if (kind.isNotEmpty() &&
                !kind.equals(Relation.KIND_FLOW, ignoreCase = true) &&
                !kind.equals(Relation.KIND_BACKFORTH, ignoreCase = true)
            ) {
                issues += PlanIssue(
                    IssueLevel.INFO, "relation.unknown-kind",
                    "$where has kind \"$kind\", which is neither \"${Relation.KIND_FLOW}\" nor " +
                        "\"${Relation.KIND_BACKFORTH}\", so it is drawn as ${Relation.KIND_FLOW}. " +
                        "A ${Relation.KIND_BACKFORTH} relation draws an arrowhead at BOTH ends, which " +
                        "is what a typo here costs",
                )
            }
            var unkept = false
            for ((end, ep) in listOf("from" to r.from, "to" to r.to)) {
                if (ep.line <= 0 && ep.symbol.isNullOrBlank()) {
                    issues += PlanIssue(
                        IssueLevel.WARN, "relation.endpoint-unnamed",
                        "$where gives a `$end` with no line and no symbol: there is nowhere to draw that " +
                            "end and nothing to call it, so the relation is dropped. Give it a line, or " +
                            "a symbol if it names something off screen",
                    )
                    continue
                }
                if (ep.line <= 0) continue
                val owner = ownerOf(ep, f, frames) ?: continue
                if (owner.shows(ep.line)) continue
                // The likely cause, when there is one: the line IS kept, by a frame that is never on screen
                // with this one.
                val elsewhere = frames.firstOrNull {
                    it.scene != f.scene && sameFilePath(owner.file, it.file) && it.shows(ep.line)
                }
                issues += PlanIssue(
                    IssueLevel.INFO, "relation.endpoint-not-kept",
                    "$where puts its `$end` on line ${ep.line} of frame ${owner.id}, which frame " +
                        "${owner.id} does not keep, so the relation is dropped: that end would land on a " +
                        "`⋯ N lines ⋯` placeholder and read as a link into nothing. " +
                        (elsewhere?.let {
                            "Frame ${it.id} keeps that line, but it is in scene ${it.scene}, and a link " +
                                "cannot reach another scene. "
                        } ?: "") +
                        "Nothing to re-send for; next time keep line ${ep.line} in frame ${owner.id} to have it",
                )
                unkept = true
                break
            }
            if (unkept) continue
            kept += r
            var drawn = false
            for ((s, step) in steps.withIndex()) {
                val band = step.frames.firstOrNull { it.frame == f.id }?.ranges?.firstOrNull()?.lines()
                    ?: continue
                if (!r.from.inStepBand(band.first, band.last, f.file) &&
                    !r.to.inStepBand(band.first, band.last, f.file)
                ) {
                    continue
                }
                drawn = true
                perStep[s]++
            }
            if (!drawn) {
                issues += PlanIssue(
                    IssueLevel.INFO, "relation.no-step",
                    "$where is never drawn: a relation shows only while a step whose FIRST range holds " +
                        "one of its ends is active, and no step of frame ${f.id} does. Move an end onto " +
                        "a step's opening range, or drop the relation",
                )
            }
        }
        if (kept.size == f.frame.relations.size) return f
        return ResolvedFrame(f.frame.copy(relations = kept), f.parentId, f.depth, f.keep, f.focus)
    }

    /**
     * The frame an endpoint's line should be read against: the one whose file it names **and whose window
     * holds its line**, or [own] when it names no file — a fileless endpoint means "this frame's own file".
     *
     * **The line is part of the key**, because one file is routinely cut into several frames — two classes in
     * one `.cs`, a caller and its callee. Matching on the path alone took the first frame on that path
     * whatever line was asked for, so an endpoint in the *second* window was measured against the *first*
     * window's `keep` and reported as landing on a fold placeholder when it is plainly visible. `own` wins a
     * tie for the same reason it is the fileless default: the frame that declared the relation is the one the
     * author was looking at. [ResolvedFrame.shows] is the predicate, shared with the tree.
     *
     * Null when a real path matches no frame, which is **not** a defect: a plan may legitimately point at code
     * it never made a frame for, and that end becomes a callout line rather than a drawn one.
     *
     * **Only frames of [own]'s scene**, because that is all the tree searches (`TreePlanView.frameOwning` looks
     * at the mounted nodes). Searching the whole plan would find a frame in *another* scene that keeps the
     * line, call the endpoint fine, while the tree draws it into this scene's frame on that file — onto a
     * `⋯ N lines ⋯` placeholder.
     */
    private fun ownerOf(ep: Endpoint, own: ResolvedFrame, frames: List<ResolvedFrame>): ResolvedFrame? {
        val path = ep.file?.trim()?.takeIf { it.isNotEmpty() } ?: return own
        val onFile = frames.filter { it.scene == own.scene && sameFilePath(path, it.file) }
        if (onFile.size <= 1 || ep.line <= 0) return onFile.firstOrNull()
        if (onFile.any { it.id == own.id } && own.shows(ep.line)) return own
        return onFile.firstOrNull { it.shows(ep.line) } ?: onFile.first()
    }

    // ── Steps ──

    /**
     * Steps whose frame references name frames that survived, with the one-frame sugar normalised away.
     *
     * A step's ranges are checked against its frame's `keep`: a step lighting an unkept line still renders,
     * but as a highlight on a `⋯ N lines ⋯` placeholder, indistinguishable from a lost step. A few
     * lines next to a kept range were already widened by [widenKeepForSteps]; what is left here is a step
     * pointing well away from anything kept, where growing `keep` would invent an excerpt and dropping the
     * step loses narration — so a report and nothing else.
     */
    private fun resolveSteps(
        steps: List<Step>,
        byId: Map<Int, ResolvedFrame>,
        issues: MutableList<PlanIssue>,
    ): List<ResolvedStep> {
        val out = mutableListOf<ResolvedStep>()
        for ((i, step) in steps.withIndex()) {
            val refs = mutableListOf<StepFrame>()
            for (ref in step.frameRefs()) {
                val frame = byId[ref.frame]
                if (frame == null) {
                    issues += PlanIssue(
                        IssueLevel.ERROR, "step.unknown-frame",
                        "step ${i + 1} names frame ${ref.frame}, which the plan does not render; " +
                            "that reference is dropped",
                    )
                    continue
                }
                if (ref.ranges.isEmpty()) {
                    issues += PlanIssue(
                        IssueLevel.ERROR, "step.no-ranges",
                        "step ${i + 1} lights no lines in frame ${ref.frame}; that reference is dropped",
                    )
                    continue
                }
                for (r in ref.ranges) {
                    val outside = r.lines().filterNot { line -> frame.keep.any { line in it.lines() } }
                    if (outside.isNotEmpty()) {
                        issues += PlanIssue(
                            IssueLevel.WARN, "step.range-outside-keep",
                            "step ${i + 1} lights lines ${r.start}-${r.end} of frame ${ref.frame}, but " +
                                "${outside.size} of them are not kept; those land on a fold placeholder and " +
                                "read as a dropped highlight. Keep them, or move the step",
                        )
                    }
                }
                refs += StepFrame(ref.frame, ref.ranges)
            }
            // **A multi-frame step cannot span scenes**, because only one scene is mounted at a time: the
            // off-scene references would ask for glow inside nodes that do not exist, which is a silently
            // unpainted step rather than a degraded picture. The primary's scene wins — it is the frame that
            // takes the callout and the reveal — and the strays are dropped and named.
            val primaryScene = refs.firstOrNull()?.let { byId[it.frame]?.scene }
            if (primaryScene != null && refs.any { byId[it.frame]?.scene != primaryScene }) {
                val strays = refs.filter { byId[it.frame]?.scene != primaryScene }.map { it.frame }
                issues += PlanIssue(
                    IssueLevel.WARN, "step.frames-cross-scene",
                    "step ${i + 1} also lights frame(s) ${strays.joinToString(", ")}, which are not in " +
                        "scene $primaryScene where its primary frame ${refs.first().frame} lives; only one " +
                        "scene is on screen at a time, so those references are dropped",
                )
                refs.retainAll { byId[it.frame]?.scene == primaryScene }
            }
            if (refs.isEmpty()) {
                issues += PlanIssue(
                    IssueLevel.ERROR, "step.dropped",
                    "step ${i + 1} names no renderable frame; dropped" +
                        (step.ttsText.take(40).takeIf { it.isNotBlank() }?.let { " (\"$it…\")" } ?: ""),
                )
                continue
            }
            // ── Pulses ──
            // **One shape downstream.** A plan carrying the legacy `token` and no `beats` is read here as a
            // single beat with no line and no `say` — fires at audio start, resolved over the step's whole
            // span. Nothing past this point reads `Step.token`, so the two spellings cannot drift.
            val declared = if (step.beats.isNotEmpty()) {
                step.beats
            } else {
                listOfNotNull(
                    step.token?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { Beat(token = it, occurrence = step.occurrence) },
                )
            }
            val lit = refs.firstOrNull()?.ranges.orEmpty()
            val pulses = mutableListOf<Beat>()
            var atStart = 0
            for ((b, beat) in declared.withIndex()) {
                if (beat.token.isNullOrBlank()) {
                    issues += PlanIssue(
                        IssueLevel.WARN, "step.beat-no-token",
                        "step ${i + 1} beat ${b + 1} names no token, so there is nothing to pulse; dropped",
                    )
                    continue
                }
                // A phrase the narration never says can never fire — the webview matches beats against what
                // was actually spoken. Named rather than left silent, because "my beat didn't flash" is
                // otherwise unanswerable. Validate-and-drop.
                if (!beat.atStart && !beat.spokenIn(step.ttsText)) {
                    issues += PlanIssue(
                        IssueLevel.WARN, "step.beat-unspoken",
                        "step ${i + 1} times a beat to \"${beat.say.take(30)}\", which its narration never " +
                            "says, so it could never fire; dropped. Quote the ttsText",
                    )
                    continue
                }
                if (beat.atStart && ++atStart > 1) {
                    issues += PlanIssue(
                        IssueLevel.WARN, "step.beat-many-at-start",
                        "step ${i + 1} gives $atStart beats no `say`, so they would all fire the instant the " +
                            "audio starts and cancel each other; only the first is kept",
                    )
                    continue
                }
                // **The check a token alone could never get.** This is pure arithmetic — no file, no VFS —
                // so it runs at load and reaches the agent in the `set_plan` reply rather than only the log.
                // Kept rather than dropped: the author named that line, and honouring it beats guessing.
                if (beat.line > 0 && lit.none { r -> beat.line in r.lines() }) {
                    issues += PlanIssue(
                        IssueLevel.WARN, "step.beat-line-outside-ranges",
                        "step ${i + 1} pulses line ${beat.line}, which is not among the lines it lights; the " +
                            "pulse will land away from the highlight",
                    )
                }
                pulses += beat
            }
            out += ResolvedStep(step.copy(beats = pulses), refs)
        }
        if (steps.isNotEmpty() && out.isEmpty()) {
            issues += PlanIssue(IssueLevel.ERROR, "plan.no-steps", "no step in the plan is renderable")
        }
        return out
    }

    // ── Soft caps, counted on the resolved tree ──

    private fun countSoftCaps(
        frames: List<ResolvedFrame>,
        steps: List<ResolvedStep>,
        issues: MutableList<PlanIssue>,
    ) {
        for (f in frames) {
            if (f.keep.size > MAX_KEEP_RANGES) {
                issues += PlanIssue(
                    IssueLevel.INFO, "frame.keep-exceeded",
                    "frame ${f.id} keeps ${f.keep.size} ranges, above the tested limit of " +
                        "$MAX_KEEP_RANGES — elision is per-gap so it will render, but nothing above " +
                        "$MAX_KEEP_RANGES has been run",
                )
            }
        }
        val hosts = frames.mapNotNull { it.parentId }.groupingBy { it }.eachCount()
        for ((host, n) in hosts) {
            if (n > MAX_SIBLINGS) {
                issues += PlanIssue(
                    IssueLevel.INFO, "plan.siblings-exceeded",
                    "frame $host hosts $n nested frames, above the comfortable $MAX_SIBLINGS; siblings are " +
                        "additive rather than nested, so this costs height and nothing worse",
                )
            }
        }
        // **Per scene, not per plan**: the budget is "lines on screen at once", and a plan of forty frames puts
        // only one scene's worth on screen. A whole-plan sum would flag every long walkthrough for a crowding
        // that never happens.
        for (scene in buildScenes(frames, steps)) {
            val visible = scene.keptLines()
            if (visible > LINE_BUDGET) {
                issues += PlanIssue(
                    IssueLevel.INFO, "scene.line-budget-exceeded",
                    "scene ${scene.id} keeps $visible lines across ${scene.frames.size} frame(s), above the " +
                        "~$LINE_BUDGET a reader takes in at once — every frame of a scene is on screen " +
                        "together, so this is the budget that binds rather than any one node's height. Keep " +
                        "less per frame, or split the scene where the nesting allows",
                )
            }
        }
        val named = steps.flatMap { s -> s.frames.map { it.frame } }.toHashSet()
        for (f in frames) {
            if (f.id !in named) {
                issues += PlanIssue(
                    IssueLevel.INFO, "frame.no-step",
                    "frame ${f.id} (${f.file}) is never narrated by any step; it renders as structure only",
                )
            }
        }
    }

    /**
     * The log half of "nothing resolves silently" — one line per issue, at the level the issue claims.
     *
     * Deliberately not just a summary count: a log line that says *"4 issues"* tells nobody what went wrong.
     */
    private fun report(resolved: ResolvedPlan) {
        for (issue in resolved.issues) {
            when (issue.level) {
                IssueLevel.ERROR, IssueLevel.WARN -> log.warn("plan: $issue")
                IssueLevel.INFO -> log.info("plan: $issue")
            }
        }
        log.info(
            "plan resolved: ${resolved.frames.size} frame(s) in ${resolved.scenes.size} scene(s) " +
                "(${resolved.sceneTransitions()} rebuild(s) end to end), " +
                "${resolved.steps.size} step(s), ${resolved.issues.size} issue(s) — " +
                "${resolved.issues(IssueLevel.ERROR).size} error / " +
                "${resolved.issues(IssueLevel.WARN).size} warn / " +
                "${resolved.issues(IssueLevel.INFO).size} info",
        )
    }
}
