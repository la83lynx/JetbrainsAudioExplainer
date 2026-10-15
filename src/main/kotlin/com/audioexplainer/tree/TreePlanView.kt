package com.audioexplainer.tree

import com.audioexplainer.Beat
import com.audioexplainer.Endpoint
import com.audioexplainer.IssueLevel
import com.audioexplainer.LineRange
import com.audioexplainer.PlanIssue
import com.audioexplainer.LinkAlignGroup
import com.audioexplainer.LinkHalf
import com.audioexplainer.LinkJoin
import com.audioexplainer.ResolvedFrame
import com.audioexplainer.ResolvedPlan
import com.audioexplainer.FrameRole
import com.audioexplainer.inStepBand
import com.audioexplainer.ResolvedStep
import com.audioexplainer.SpotlightRenderer
import com.audioexplainer.StepPaint
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * **The production tree of node views** — a [ResolvedPlan]'s frames rendered as [CodeNestView]'s nested
 * nodes, and its steps rendered by driving each node's own [com.audioexplainer.SpotlightRenderer].
 *
 * This replaced the `com.audioexplainer.spike` benches, since deleted, as the thing that draws a tree. Those
 * built the same [CodeNodeSpec]s from hard-coded text anchors over a bundled C# fixture and synthesised a
 * timeline from range order; here both come from the plan, which is the whole of Phase 2's point: **the agent
 * states the frames and the steps, the plugin picks the picture.**
 *
 * Three things it deliberately keeps from the bench, because each cost a round to learn:
 *
 * - **One live view at a time, disposed on rebuild** ([dispose] tears down inlays before editors, deepest
 *   node first). An editor inside an inlay inside an editor is the layout's stated main risk, and the
 *   bench's 6 → 5 editors across 25 rebuilds is the shape of answer to keep getting.
 * - **A step is applied through the same two entry points the in-place surface uses.** Entering a frame
 *   paints its context and its active ranges; moving inside one repaints only the step. There is one
 *   renderer body behind both ([com.audioexplainer.SpotlightRenderer.paintStep]).
 * - **The resolution half of the diagnostics is measured, not predicted.** Whether a frame's file exists,
 *   whether a nesting actually embedded and whether its call token was found are facts only the built view
 *   has; they are read back off [CodeNestView.nestings] and appended to the plan's own issue list, so a
 *   frame that silently failed to nest says so.
 *
 * **EDT only.** Construction builds N editors; the caller resolves the files first (VFS I/O is not the
 * EDT's work) and hands them in.
 */
class TreePlanView(
    private val project: Project,
    val plan: ResolvedPlan,
    files: Map<Int, VirtualFile>,
    // **The user's settings are the defaults, not the constructor's** (Phase 4). A default argument is
    // evaluated per call, so a tree opened after the reader picked `ARROW` opens as `ARROW` — the settings
    // are read at construction, and [restyle] is what carries a change into a tree that is already up.
    style: NestStyle = TreeStyle.nest(),
    nodeStyle: CodeNodeStyle = TreeStyle.node(),
    private val navigate: (Endpoint) -> Unit = {},
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(TreePlanView::class.java)

    /**
     * **Frame id → the file its node is built over, owned rather than borrowed.**
     *
     * A copy, because it is no longer a fact settled once at open: a file can be deleted, or replaced on
     * disk, while the tree is on screen — Arch A's snapshot could not see that and a tree of live documents
     * can (see [revalidateFiles]). An entry leaves this map when its file is gone and nothing is at its path.
     */
    private val files = LinkedHashMap(files)

    /**
     * **Frames dropped because their file went**, so [issues] can say so and a report can be read for it.
     *
     * Kept beside [buildIssues] rather than in it: a build issue belongs to the scene that measured it and is
     * replaced on the next mount of that scene, where this is a fact about the *walkthrough* — the file does
     * not come back when the reader steps into another scene and out again.
     */
    private val droppedFrames = LinkedHashSet<Int>()

    /**
     * **Constraint 13's explicit child disposable**, and this class is exactly the case it names: a
     * `JPanel` implementing `Disposable` is only torn down by the Disposer *tree*, and the owner here calls
     * [dispose] directly from the tab's `onDispose`. A subscription parented to `this` would therefore stay
     * live after the tab closed and drive VFS events into a released nest.
     */
    private val subscriptions = Disposer.newDisposable("TreePlanView")

    private var disposed = false

    private val nest = CodeNestView(project, style, nodeStyle, navigate)

    /**
     * Frame id → its node's index in [CodeNestView.nodes], for the frames that got one. A frame whose file
     * did not resolve has no entry, which is what makes "render the steps that can be rendered" expressible.
     */
    private val nodeIndexOf = LinkedHashMap<Int, Int>()

    /**
     * Everything wrong that only building the view could reveal, **keyed by the scene that revealed it**.
     *
     * A map rather than a list because a scene is mounted more than once over a walkthrough: appending would
     * report the same unresolvable file twice for one plan and grow without bound as the reader scrubs. Each
     * mount replaces its own scene's entry, so [issues] is "everything measured about the scenes seen so
     * far" — which is the honest claim, and is why a scene never visited contributes nothing rather than
     * being reported as clean.
     */
    private val buildIssues = LinkedHashMap<Int, List<PlanIssue>>()

    /** The step currently painted, or -1 before the first one. */
    var stepIndex: Int = -1
        private set

    /**
     * **The scene whose nodes are on screen**, or -1 before the first mount.
     *
     * The one piece of state the scene model adds to this class, and it is what makes the tree a *viewport*
     * on a plan rather than a picture of all of it. See [mountScene].
     */
    var sceneId: Int = -1
        private set

    /** How many times a step crossing a scene boundary has rebuilt the nest, for a report that must say. */
    var mounts: Int = 0
        private set

    init {
        add(nest, BorderLayout.CENTER)
        nest.onRerender = { repaintStep() }
        // The files under this tree are live, so the tree has to be told when one stops existing — see
        // [revalidateFiles]. Parented to [subscriptions], not to `this`, for the reason given there.
        project.messageBus.connect(subscriptions).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    // Cheap enough to run on every batch: N is the frames of one plan, and `isValid` is a
                    // field read. Filtering the events by path first would mean matching a rename, a move and
                    // a parent-directory delete by hand — three ways to miss one — where asking the files
                    // themselves cannot be wrong about what happened to them.
                    if (files.values.any { !it.isValid }) {
                        // **Never inside the write action that produced these events.** Revalidating builds
                        // editors, and the platform is mid-VFS-commit here.
                        ApplicationManager.getApplication().invokeLater({
                            if (!disposed) revalidateFiles()
                        }, ModalityState.any())
                    }
                }
            },
        )
        // The first scene in step order, so the tree opens on what step 1 talks about rather than on whatever
        // frame happens to be numbered 0.
        mountScene(plan.scenes.firstOrNull()?.id ?: 0)
    }

    /**
     * The plan's issues, the ones the mounted scenes measured, and any frame whose file has since gone.
     *
     * The last group is not scene-keyed like the others: a file that was deleted while the tree was open
     * stays deleted when the reader steps into another scene and back, so re-mounting must not clear it.
     */
    fun issues(): List<PlanIssue> = plan.issues + buildIssues.values.flatten() + droppedFrames.map { id ->
        PlanIssue(
            IssueLevel.ERROR, "frame.file-gone",
            "frame $id: ${plan.frame(id)?.file ?: "its file"} was deleted or replaced while the tree was " +
                "open; the frame is no longer rendered",
        )
    }

    /**
     * **A file under this tree has gone — drop its node and keep the rest.**
     *
     * Reachable in a way Arch A's snapshot never was: a node is a real editor over the real document, so
     * deleting the file out from under it leaves a live view of a document nothing owns. The rule is
     * *validate and drop*, because the alternatives are both worse — a tree that closes itself takes four
     * good nodes with the missing one, and a tree that keeps drawing the stale node is telling the reader
     * something untrue about their own disk.
     *
     * **A file replaced at the same path is re-taken rather than dropped**, which is the common case and not
     * the same event: an external tool, a branch switch or a revert deletes and recreates, and the reader
     * would read a node vanishing as the walkthrough breaking. Only a path with nothing at it is a drop.
     *
     * **The drop is a re-mount, not a removal.** `CodeNestView` has one way for nodes to come into existence
     * ([mountScene] → `setTree`), which releases every editor before building the new ones; adding a
     * remove-one-node path would be a second teardown order to get wrong, on the one operation where getting
     * it wrong leaks an editor holding a document. So the scene is rebuilt around what is left, exactly as a
     * scene crossing rebuilds it.
     *
     * EDT only. Returns the number of frames dropped.
     */
    fun revalidateFiles(): Int {
        val broken = files.filterValues { !it.isValid }
        // `sceneId < 0` cannot happen from the listener — the constructor mounts before any `invokeLater` can
        // run — but the re-mount below reads it as the scene to rebuild, and `plan.scene(-1)` is null, which
        // would empty the tree rather than repair it. Cheaper to say so than to rely on the ordering.
        if (broken.isEmpty() || sceneId < 0) return 0

        val dropped = mutableListOf<Int>()
        for ((frameId, gone) in broken) {
            // `findFileByPath` and not `refreshAndFindFileByNioFile`: a VFS refresh is a slow op and this
            // runs on the EDT. The refresh that produced this event has already happened — if a file is at
            // that path, the platform knows about it by now.
            val replacement = LocalFileSystem.getInstance().findFileByPath(gone.path)?.takeIf { it.isValid }
            if (replacement != null) {
                files[frameId] = replacement
                log.info("tree: frame $frameId's file was replaced at ${gone.path}; re-taken")
            } else {
                files.remove(frameId)
                droppedFrames += frameId
                dropped += frameId
                log.warn("tree: frame $frameId's file ${gone.path} is gone; the frame is dropped")
            }
        }

        // Re-mount whatever scene is showing, around the files that are left. `mountScene` no-ops on the
        // scene it already holds, so the id is cleared first — this is the one caller that means "build it
        // again" rather than "make sure it is up".
        val scene = sceneId
        val step = stepIndex
        sceneId = -1
        mountScene(scene)
        // `enforceBudget = false`: the reader did not step, and a file disappearing is not an occasion to
        // fold away nodes they had opened. Same argument as [repaintStep].
        if (step >= 0) applyStep(step, enforceBudget = false)
        // **Said out loud, because a node that simply vanishes is indistinguishable from a rendering bug.**
        // §1b's rule — a plan rendering three of its four frames says which — applies just as much when the
        // fourth is lost at 11:04 as when it never resolved at open.
        if (dropped.isNotEmpty()) onFramesDropped?.invoke(dropped.toList())
        return dropped.size
    }

    /**
     * Told which frames [revalidateFiles] just dropped, so the owner can raise it with the reader. Set by
     * `TreePlanController`; null in a bench, which reads [droppedFrames] instead.
     */
    var onFramesDropped: ((List<Int>) -> Unit)? = null

    /** Frames no longer rendered because their file went, for a report that has to say which. */
    fun droppedFrames(): Set<Int> = droppedFrames

    /** One line per node, for a report: the frame, its file, its depth and how it landed. */
    fun report(): String = buildString {
        appendLine(
            "scene=$sceneId of ${plan.scenes.size} mounts=$mounts " +
                "frames=${plan.frames.size} rendered=${nodeIndexOf.size} steps=${plan.steps.size}",
        )
        for (scene in plan.scenes) {
            appendLine(
                "scene ${scene.id}${if (scene.id == sceneId) " (mounted)" else ""}: " +
                    "${scene.frames.size} frame(s), ${scene.stepIndices.size} step(s), " +
                    "${scene.keptLines()} kept line(s)",
            )
        }
        for (frame in plan.frames) {
            val idx = nodeIndexOf[frame.id]
            val node = idx?.let { nest.nodes.getOrNull(it) }
            val nesting = nest.nestings.firstOrNull { it.child === node }
            appendLine(
                "frame ${frame.id} s${frame.scene} d${frame.depth} ${frame.file.substringAfterLast('/')} " +
                    "keep=${frame.keep.size} focus=${frame.focus?.let { "${it.start}-${it.end}" } ?: "-"} " +
                    "node=${idx ?: "none"} embed=${nesting?.state ?: "root"} leader=${nesting?.leaderState ?: "-"}",
            )
        }
        for (issue in issues()) appendLine(issue.toString())
    }

    // ── Building ──

    /**
     * **Build the nodes of one scene, replacing whatever was on screen.**
     *
     * This is where the scene decision actually lands, and it is deliberately the *only* way nodes come into
     * existence — the constructor calls it too, so "open the tree" and "cross a scene boundary" are one code
     * path rather than two that have to agree. `CodeNestView.setTree` already releases every node before
     * building the new ones, so the mount is a rebuild by construction and there is no partial-swap mode to
     * get wrong.
     *
     * **What this costs, stated rather than discovered:** N editors released and M created per crossing, which
     * is the operation Phase 1 measured 25 times with clean disposal. What does *not* survive is the reader's
     * own state inside the scene — a collapsed nesting, a dragged node height ([CodeNodeView.heightOverride]
     * documents its own case) — because those belong to node views that no longer exist. Leaving a scene and
     * coming back is therefore a fresh mount, not a restore.
     *
     * No-ops when [sceneId] is already mounted, which is what keeps [applyStep] cheap: stepping *within* a
     * scene must not rebuild anything, and the guard lives here rather than in the caller so every entry
     * point gets it.
     */
    fun mountScene(scene: Int) {
        if (scene == sceneId && mounts > 0) return
        val frames = plan.scene(scene)?.frames.orEmpty()
        val issues = mutableListOf<PlanIssue>()
        nodeIndexOf.clear()

        val specs = mutableListOf<CodeNodeSpec>()
        val rendered = mutableListOf<ResolvedFrame>()
        for (frame in frames) {
            // `isValid` as well as present: a file can go between the controller's off-EDT resolution and
            // this mount, and a scene mounted over an invalidated `VirtualFile` builds an editor on a
            // document nothing owns.
            val file = files[frame.id]?.takeIf { it.isValid }
            if (file == null) {
                // The one diagnostic that cannot be structural: a path that does not resolve is a fact about
                // the disk. Reported rather than thrown — a plan whose fourth frame is missing still renders
                // three, and §1b's requirement is that it says which.
                issues += PlanIssue(
                    IssueLevel.ERROR, "frame.file-unresolved",
                    "frame ${frame.id}: ${frame.file} did not resolve to a file in this project; " +
                        "the frame is not rendered",
                )
                continue
            }
            nodeIndexOf[frame.id] = specs.size
            specs += CodeNodeSpec(
                file = file,
                title = frame.frame.title.ifBlank { file.name },
                depth = frame.depth,
                keep = frame.keep.map { it.lines() },
                // The schema states the excerpt now, so the node is told rather than guessing from range
                // order — which is the whole of what `CodeNodeSpec.focus` was carried for.
                focus = frame.focus?.lines(),
            )
            rendered += frame
        }

        val edges = mutableListOf<CodeTreeEdge>()
        for (frame in rendered) {
            val parentId = frame.parentId ?: continue
            val childIdx = nodeIndexOf[frame.id] ?: continue
            val parentIdx = nodeIndexOf[parentId]
            if (parentIdx == null) {
                // Reachable only for a parent whose *file* did not resolve: the loader has already cut any
                // link to a frame in another scene (`frame.parent-other-scene`), so a cross-scene parent
                // never gets this far.
                issues += PlanIssue(
                    IssueLevel.WARN, "frame.parent-unrendered",
                    "frame ${frame.id} nests in frame $parentId, which is not rendered; " +
                        "it is shown at top level instead",
                )
                continue
            }
            edges += CodeTreeEdge(
                parent = parentIdx,
                child = childIdx,
                parentLine = frame.frame.callLine.takeIf { it > 0 },
                callToken = frame.frame.callToken,
                callOccurrence = frame.frame.callOccurrence,
            )
        }

        nest.setTree(specs, edges)
        sceneId = scene
        mounts++
        // Painted by the caller: [applyStep] mounts and then paints, so a mount alone deliberately leaves the
        // nodes unlit rather than re-showing a step that belonged to the scene just released.
        stepIndex = -1

        // Read the nesting outcomes back: EMBEDDED / NO_CALL_LINE / FAILED, and whether the call token was
        // found. Measured off the built view, because "did the platform accept this embedding" is not
        // something the schema can promise (constraint 15) and a frame that silently landed at top level is
        // indistinguishable from one the plan put there.
        for (nesting in nest.nestings) {
            val frameId = nodeIndexOf.entries.firstOrNull { nest.nodes.getOrNull(it.value) === nesting.child }?.key
            if (nesting.state != EmbedState.EMBEDDED) {
                issues += PlanIssue(
                    if (nesting.state == EmbedState.FAILED) IssueLevel.ERROR else IssueLevel.INFO,
                    "frame.embed-${nesting.state.name.lowercase().replace('_', '-')}",
                    "frame ${frameId ?: "?"} was not embedded in its parent (${nesting.state}); " +
                        "it renders at top level",
                )
            }
            if (nesting.leaderState == LeaderState.MISSING) {
                issues += PlanIssue(
                    IssueLevel.WARN, "frame.call-token-missing",
                    "frame ${frameId ?: "?"}: the call line is empty or its callToken is not on it, so the " +
                        "invoking token is not boxed. The token has to be a literal substring of that line",
                )
            }
        }
        buildIssues[scene] = issues

        log.info(
            "tree scene $scene mounted (mount #$mounts): ${nodeIndexOf.size} node(s), " +
                "${edges.size} nesting(s), ${plan.scene(scene)?.keptLines() ?: 0} kept line(s)",
        )
    }

    // ── Stepping ──

    /**
     * Paint the plan's step [index] across every frame it touches, clearing the ones it does not.
     *
     * **The primary frame is `frames[0]`** — it takes the active node tint, the token flash and the
     * container's reveal, and its callout is the bright one. Phase 3 filled in the rest of the shape: the
     * step's other frames are [com.audioexplainer.FrameRole.HELPER]s, glowed and boxed in a visibly
     * secondary key, capped at [MAX_HELPER_CALLOUTS]; and a frame the step does not touch is cleared, so it
     * carries only its node's own wash. Three tiers, all of them backgrounds (constraint 1).
     */
    @JvmOverloads
    fun applyStep(index: Int, enforceBudget: Boolean = true) {
        val step = plan.steps.getOrNull(index) ?: return
        // **Before anything is painted**: a step in another scene has no node to paint into yet. `mountScene`
        // no-ops when the scene is already up, so stepping inside a scene costs one map lookup and stepping
        // across one costs a rebuild — which is the whole of the scene model's runtime behaviour.
        plan.sceneOfStep(index)?.let { mountScene(it) }
        stepIndex = index
        // Before painting, so every highlight, link half and call box is laid out against this step's folds.
        condense(step)

        val primaryId = step.primaryFrameId
        val touched = step.frames.map { it.frame }.toSet()

        // The frames actually **painted**, which is not the same set as the ones the step names: a frame
        // whose ranges resolve to nothing is cleared like an untouched one. Tracked rather than assumed,
        // because a renderer that was neither painted nor cleared still reports the *previous* step's
        // portal requests — and [paintLinkPairs] would then pair a link this step never mentioned.
        val painted = LinkedHashSet<Int>()

        // Helper callouts are capped rather than one-per-frame: Phase 1 judged **one** box per step to read
        // well, and the Primary/Helper split is there to add a second voice, not a chorus. The frames past
        // the cap keep their glow and lose their box, which is the same degrade a link past its own cap gets.
        var helperBoxes = 0
        for ((frameId, nodeIdx) in nodeIndexOf) {
            val node = nest.nodes.getOrNull(nodeIdx) ?: continue
            if (frameId !in touched) {
                node.spotlight.clear()
                continue
            }
            val isPrimary = frameId == primaryId
            val withBox = isPrimary || helperBoxes < MAX_HELPER_CALLOUTS
            if (paint(frameId, node, step, isPrimary = isPrimary, withCallout = withBox)) {
                painted += frameId
                if (!isPrimary && withBox) helperBoxes++
            } else {
                node.spotlight.clear()
            }
        }

        // **After** that whole loop, and the order is the reason portals are not drawn inside `paintStep`:
        // a pair's far half belongs to whichever node owns the target file, which may be one the step does
        // not touch — and which the loop above has just cleared.
        val linkRows = paintLinkPairs(painted)

        val primaryNode = nodeIndexOf[primaryId]?.let { idx ->
            nest.setActiveNode(idx)
            nest.nodes.getOrNull(idx)
        }

        // **The fold.** Before the reveal, because collapsing changes every height the reveal is about to
        // measure against, and never on a bare re-render (see [repaintStep]).
        if (enforceBudget) foldUntouched(painted, linkRows.keys)

        // **The reveal, and it is the whole of the multi-frame case.** All the touched nodes if they fit,
        // the primary's otherwise — never the midpoint between two frames far apart, which is the elided gap.
        if (primaryNode != null) {
            val touchedNodes = painted.mapNotNull { id -> nodeIndexOf[id]?.let { nest.nodes.getOrNull(it) } }
            nest.revealNodes(primaryNode, touchedNodes)
        }

        // **Last, because it must outrank what the reveal above just did** to the nodes it did not name.
        revealLinkRows(linkRows, painted)
    }

    /**
     * **Scroll a node opened *for* a link to the row that link lands on** — K5, and the third correction in
     * this line of work: *"when next or previous step opens a previously closed subeditor with a link portal
     * target in it, it doesn't scroll to that link, so a link that is not visible on the shown by default
     * subeditor lines is not visible."*
     *
     * The reported case is `tree-links` step 6 → 5 (and 6 → 7): `ValidationHandler` opens showing its focus
     * range, lines 18–24, and the link is on 37 — inside a *context* range further down the same node. The
     * node was open, the pair was drawn, and one end of it was below the fold of a viewport nothing had
     * scrolled: [CodeNestView.revealNodes] scrolls the nodes the **step** names, and this is by definition a
     * node the step does not name.
     *
     * [CodeNodeView.showOwnRow] rather than [CodeNestView.revealRow]: the row belongs inside its own node,
     * and the chain walk a `revealRow` does would scroll the **outer** pane too — dragging the step's own
     * frame off screen to show a link's far end, which is the wrong half of the picture to sacrifice. It also
     * releases the landing, and it has to: expanding a nesting re-arms it, and a re-armed landing pulls the
     * node back to the top of its excerpt a pass later and undoes exactly this scroll.
     *
     * **Twice, the second time deferred**, for constraint 20's reason and the one
     * [CodeNestView.revealNode]'s own follow-up gives: the first pass runs against an extent the expansion
     * has not been laid out at yet. Queued *after* the reveal above, so its follow-up cannot land on top.
     *
     * A node the step **does** paint is left alone — its own step reveal is the right destination, and a link
     * row is not a reason to move it.
     */
    private fun revealLinkRows(linkRows: Map<Int, Int>, painted: Set<Int>) {
        if (linkRows.isEmpty()) return
        val paintedNodes = painted.mapNotNullTo(HashSet()) { nodeIndexOf[it] }
        val rows = linkRows.filterKeys { it !in paintedNodes }
        if (rows.isEmpty()) return
        var shown = 0
        for ((idx, line) in rows) if (nest.nodes.getOrNull(idx)?.showOwnRow(line) == true) shown++
        log.info(
            "step $stepIndex scrolls ${rows.size} node(s) the step does not narrate to their link row " +
                "($shown landed on the first pass; the rest are the deferred one)",
        )
        ApplicationManager.getApplication().invokeLater({
            if (disposed) return@invokeLater
            for ((idx, line) in rows) nest.nodes.getOrNull(idx)?.showOwnRow(line)
        }, ModalityState.any())
    }

    /**
     * **Fold away the nestings this step is not narrating** — [StepFold], and the K3 round is what made it a
     * setting rather than a consequence.
     *
     * It began as the line budget's enforcement (Phase 2 decision 8 moved the cap from per-plan to
     * per-*scene*, and a scene's `keep` total is what the reader takes in), so it only ran when the mounted
     * scene was over [com.audioexplainer.PlanLoader.LINE_BUDGET]. K3's plan was four frames well under the
     * cap and read wrong anyway: *"for some steps explainer keeps expanded items, though it's not required
     * and only takes up space."* The budget was never the reason a nesting the step ignores is worth its
     * height — it only decided whether anything was done about it.
     *
     * So the trigger is now [StepFold] and the budget is one of its three values. **The action is identical
     * in all three**: every nesting whose subtree this step does not touch is collapsed to its stub, which
     * never folds an ancestor of a touched node (that would hide the narration) and never strands the reader
     * — [CodeNestView.setActiveNode] re-expands innermost-first on the way back in, which is the invariant the
     * G1c⁗ round established.
     *
     * **A node holding one end of a drawn link counts as touched** ([linkedNodes], straight from
     * [paintLinkPairs]) — K4, and the one case the first cut got wrong: *"sometimes we treat subnode as not
     * required, even though it contains a visible link target."* The step names the frames it narrates; a
     * relation reaches a frame it does not name, by construction — that is what a relation is for. Folding
     * that frame leaves a pair with one end drawn and the other inside a stub, which reads as the plugin
     * having lost half the link rather than as a tidy screen. The fold runs *after* the pairs are allocated
     * precisely so this set exists to ask.
     *
     * **And it opens, not merely refrains from closing** — K5, which is where the K4 cut fell down.
     * [CodeNestView.collapseAllExcept] only ever sets `collapsed = true`; a nesting some *earlier* step folded
     * stays folded however wanted it now is, and the only thing that reopened anything was
     * [CodeNestView.setActiveNode] walking the **primary's** chain. So on `tree-links` step 2 — three portal
     * links from the root into three children that step 1 had just folded — all three pairs drew one end into
     * a stub. The round found it from the other side too: *"step 3 expands ValidationHandler and going back to
     * step 2 leaves it expanded, so its second end is visible then"*, which is the same fact told as a
     * discrepancy.
     *
     * The step therefore decides the **whole** open set, in both directions: every node in `keep` gets its
     * chain expanded ([CodeNestView.expandTo], innermost-first per constraint 17), and everything else folds.
     * A helper frame nested inside a fold had the same bug and gets the same answer — the set is what matters,
     * not why a node is in it.
     *
     * It runs on a step *change* only ([repaintStep] and [reassertViewport] do not call it), so a reader who
     * opens a node by hand keeps it open for as long as they stay on the step.
     */
    private fun foldUntouched(painted: Set<Int>, linkedNodes: Set<Int>) {
        val mode = nest.style.stepFold
        if (mode == StepFold.NEVER) return
        val scene = plan.scene(sceneId) ?: return
        val sceneLines = scene.keptLines()
        val overBudget = sceneLines > com.audioexplainer.PlanLoader.LINE_BUDGET
        if (mode == StepFold.OVER_BUDGET && !overBudget) return
        val keep = LinkedHashSet<CodeNodeView>()
        for (id in painted) nodeIndexOf[id]?.let { nest.nodes.getOrNull(it) }?.let { keep += it }
        val narrated = keep.size
        for (idx in linkedNodes) nest.nodes.getOrNull(idx)?.let { keep += it }
        val forLinks = keep.size - narrated
        // Nothing painted means nothing to keep open, and folding everything on that would be a blank screen
        // rather than a tidy one.
        if (keep.isEmpty()) return
        // **Open first, then close.** Order is not what makes this correct — `collapseAllExcept` skips every
        // nesting holding a kept node, so neither pass can undo the other — but it puts the expensive
        // re-measure where the reveal that follows will read it.
        var expanded = 0
        for (node in keep) expanded += nest.expandTo(node)
        val collapsed = nest.collapseAllExcept(keep)
        if (collapsed > 0 || expanded > 0) {
            log.info(
                "step fold $mode on step $stepIndex: scene $sceneId keeps $sceneLines line(s) " +
                    "(cap ${com.audioexplainer.PlanLoader.LINE_BUDGET}, over=$overBudget) — collapsed " +
                    "$collapsed nesting(s) the step does not touch and expanded $expanded it needs, " +
                    "leaving ${keep.size} node(s) open ($narrated narrated" +
                    (if (forLinks > 0) ", $forLinks holding a link end" else "") + ")",
            )
        }
    }

    /**
     * **Fold, for this step, the part of each open frame it does not use** — [StepCondense], from the 2026-10-14
     * round: *"all generated walkthroughs make much less use of code collapsing, showing quite a lot of
     * unrelevant code"*. [foldUntouched] folds whole nestings, and can never fold an ancestor of the frame it
     * narrates; this folds *lines*, inside the frames that stay open.
     *
     * **What always stays**, in any frame it touches: the first line of each `keep` range (the class and member
     * headers, by the schema's own advice on writing them), every call line a child hangs from — a call line
     * folded away would bury the child, `PlanLoader.dropUnkeptCallSites`'s whole reason — the step's own ranges,
     * and the ends of the links this step draws, so no link points into a fold. And above each of those, the
     * headers of the blocks it sits in ([StepCondense.chain]) — the 2026-10-15 round: *"it would be best if
     * this method would try to leave the nesting chain visible"*, for a step and for a link's far end alike.
     *
     * - **Ancestors** of the step's frame ([StepCondense.ANCESTORS] and up) fold everything else.
     * - **The step's frame** folds the stretch between a long link's ends ([StepCondense.LINKS]), or everything
     *   else ([StepCondense.STEP]), keeping a line either side of what stays.
     *
     * Every other node gets its whole `keep` back. Runs on a re-render too, which changes nothing unless the
     * setting did: [CodeNodeView.condense] re-elides only on a change, so a fold the reader opened by hand stays
     * open for as long as the step does.
     */
    private fun condense(step: ResolvedStep) {
        val mode = nest.style.condense
        val primary = nodeIndexOf[step.primaryFrameId]?.let { nest.nodes.getOrNull(it) }
        val wanted = HashMap<CodeNodeView, List<IntRange>>()
        if (mode != StepCondense.OFF && primary != null) {
            val links = drawnLinks(step)
            // What stays in [node]'s frame: the anchors, with the margin, and the chain of block headers above
            // each line the step places — without one, so the `if` a step sits in stays and its body folds.
            fun anchorsOf(frameId: Int, node: CodeNodeView): Pair<Set<Int>, Set<Int>> {
                val frame = plan.frame(frameId) ?: return emptySet<Int>() to emptySet()
                val out = HashSet<Int>()
                val placed = HashSet<Int>()
                frame.keep.forEach { placed += it.lines().first }
                nest.nestings.filter { it.parent === node }.forEach { n -> n.callLine?.let { placed += it } }
                step.rangesIn(frameId).forEach { out += it.lines(); placed += it.lines().first }
                for ((from, to) in links) {
                    if (from?.first == frameId) placed += from.second
                    if (to?.first == frameId) placed += to.second
                }
                out += placed
                val chains = HashSet<Int>()
                placed.forEach { chains += StepCondense.chain(it, node::lineText) }
                return out to chains
            }
            for (nesting in nest.nestingsAbove(primary)) {
                val host = nesting.parent
                val frameId = frameIdOf(host) ?: continue
                val frame = plan.frame(frameId) ?: continue
                val (anchors, chains) = anchorsOf(frameId, host)
                wanted[host] = StepCondense.window(frame.keep.map { it.lines() }, anchors, { true }, 0, chains)
            }
            val frameId = step.primaryFrameId
            val frame = plan.frame(frameId)
            if (frame != null && mode >= StepCondense.LINKS) {
                val zone: (Int) -> Boolean = if (mode == StepCondense.STEP) {
                    { true }
                } else {
                    // The stretches between the two ends of a long link inside this frame.
                    val spans = links
                        .mapNotNull { (from, to) ->
                            if (from?.first != frameId || to?.first != frameId) return@mapNotNull null
                            minOf(from.second, to.second) + 1 until maxOf(from.second, to.second)
                        }
                        .filter { span -> span.count { frame.shows(it) } >= StepCondense.LONG_LINK }
                    ({ line -> spans.any { line in it } })
                }
                val (anchors, chains) = anchorsOf(frameId, primary)
                val window = StepCondense.window(frame.keep.map { it.lines() }, anchors, zone, 1, chains)
                wanted[primary] = window
            }
        }
        var changed = 0
        for (node in nest.nodes) if (node.condense(wanted[node])) changed++
        if (changed > 0) {
            log.info(
                "step $stepIndex: condense $mode re-elided $changed node(s); " +
                    wanted.entries.joinToString(", ") { (node, rs) ->
                        "frame ${frameIdOf(node)} shows ${rs.sumOf { it.count() }} line(s)"
                    },
            )
        }
    }

    private fun frameIdOf(node: CodeNodeView): Int? =
        nodeIndexOf.entries.firstOrNull { nest.nodes.getOrNull(it.value) === node }?.key

    /**
     * The links [step] draws, each as `(frame, line)` at both ends — the rule [paintLinkPairs] draws by (a
     * relation shows while the step's first range in its frame holds one of its ends) and [frameOwning] places
     * by. An end in no mounted frame is null: it has no row in this tree to keep, while the other end does.
     */
    private fun drawnLinks(step: ResolvedStep): List<Pair<Pair<Int, Int>?, Pair<Int, Int>?>> {
        val out = mutableListOf<Pair<Pair<Int, Int>?, Pair<Int, Int>?>>()
        for ((ownerId, _) in nodeIndexOf) {
            val owner = plan.frame(ownerId) ?: continue
            val band = step.rangesIn(ownerId).firstOrNull()?.lines() ?: continue
            for (r in owner.frame.relations) {
                if (!r.from.inStepBand(band.first, band.last, owner.file) &&
                    !r.to.inStepBand(band.first, band.last, owner.file)
                ) continue
                fun place(ep: Endpoint): Pair<Int, Int>? {
                    if (ep.line <= 0) return null
                    val path = ep.file?.trim()?.takeIf { it.isNotEmpty() } ?: return ownerId to ep.line
                    return frameOwning(path, ep.line, prefer = ownerId)?.let { it to ep.line }
                }
                out += place(r.from) to place(r.to)
            }
        }
        return out
    }

    /**
     * **Put the current step's viewport back where the plan put it, without repainting it** — what pressing
     * Play does after the reader has scrolled around (Phase 3's scroll policy, via
     * `TreePlanController.onResume`).
     *
     * Deliberately not [repaintStep]. A repaint re-docks the callout, re-runs the link allocator and
     * re-starts the reveal from a clean slate, all to move a scrollbar; and the reader may be mid-hover on a
     * link. This re-issues the reveal alone — each node re-centres its own ranges and the container walks the
     * chain — which is exactly the part a reader's scroll displaced.
     *
     * A no-op before the first step lands.
     */
    fun reassertViewport() {
        val step = plan.steps.getOrNull(stepIndex) ?: return
        val touchedNodes = step.frames
            .mapNotNull { nodeIndexOf[it.frame] }
            .mapNotNull { nest.nodes.getOrNull(it) }
        val primary = nodeIndexOf[step.primaryFrameId]?.let { nest.nodes.getOrNull(it) } ?: return
        nest.revealNodes(primary, touchedNodes)
    }

    /**
     * **Re-shape the open tree to what the settings now say** — Phase 4's Apply, and the half a repaint
     * cannot do.
     *
     * Constraint 11's *"a per-render derivation means re-rendering is re-styling"* covers every **colour**,
     * because [com.audioexplainer.SpotlightRenderer] derives them all per render. It does not cover the
     * tree's own shape: the leader mode decides which border edges the token box and the child frame draw
     * (constraint 16), the height ceiling decides what a node is sized to, and neither is re-derived by
     * painting a step. So Apply comes here first and [repaintStep] second.
     *
     * **Returns false when nothing changed, and that is the point rather than an optimisation.**
     * [CodeNestView.restyleNest] re-applies every nesting's frame inset and re-arms the landing, which moves
     * the reader's own scroll — right when the mode really did change, wrong when the reader pressed Apply
     * after picking a callout colour. It is the same distinction [repaintStep] draws for the line budget:
     * an event that did not change the shape must not act as though it did.
     *
     * **Each half is asked only if its own half changed**, for the same reason the method returns a boolean at
     * all: `restyleNest` re-arms the landing whether or not the mode moved. Order is nodes-then-nest, because
     * `restyleNest` re-runs `applyRoles` itself when `growHosts` moves and must do that against the node style
     * being applied rather than the one being replaced.
     */
    fun restyle(
        nestStyle: NestStyle = TreeStyle.nest(),
        nodeStyle: CodeNodeStyle = TreeStyle.node(),
    ): Boolean {
        val nodeChanged = nodeStyle != nest.nodeStyle
        val nestChanged = nestStyle != nest.style
        if (!nodeChanged && !nestChanged) return false
        log.info(
            "tree restyled (node=$nodeChanged nest=$nestChanged): leader=${nestStyle.leader} " +
                "chevron=${nestStyle.chevron} stepFold=${nestStyle.stepFold} " +
                "path=${nodeStyle.path} elision=${nodeStyle.elision} " +
                "deemphasis=${nodeStyle.deemphasis} maxHeight=${nodeStyle.maxHeight} " +
                "grow=${nodeStyle.grow} growHosts=${nestStyle.growHosts}",
        )
        if (nodeChanged) nest.restyleNodes(nodeStyle)
        if (nestChanged) nest.restyleNest(nestStyle)
        repaintStep()
        return true
    }

    /**
     * Re-paint the current step (constraint 11: re-render, do not merely resync). Two callers: a colour-scheme
     * change, via each node's own subscription; and **Settings ▸ Apply**, via `TreePlanController.refresh()` —
     * which used to reach the deleted in-place surface instead, and would have become a silent no-op.
     *
     * A no-op before the first step lands, so applying settings to a freshly-opened tree does nothing rather
     * than painting step -1.
     */
    fun repaintStep() {
        // **`enforceBudget = false`, and it is the difference between a re-style and a step.** A re-render is
        // a colour change, not a move: folding the reader's own opened nodes shut because a theme switched —
        // or because they pressed Settings ▸ Apply — would be the budget acting on an event that did not
        // change how many lines are on screen. The collapse belongs to a step *change*, which is the only
        // thing that changes which nodes the narration needs.
        if (stepIndex >= 0) applyStep(stepIndex, enforceBudget = false)
    }

    /**
     * Paint [step] into [node], or return false having drawn nothing — see [applyStep]'s `painted` set.
     *
     * **The Primary/Helper split lands here.** A helper frame gets the same ranges in the softened key
     * ([com.audioexplainer.FrameRole.HELPER]) and, if it is inside the cap, a box carrying the step's `note`
     * — one sentence about why *this* frame is also lit. It never takes the `token`: the flash is the
     * primary's, and a token pulsing in two editors at once would say the step is about both.
     */
    private fun paint(
        frameId: Int,
        node: CodeNodeView,
        step: ResolvedStep,
        isPrimary: Boolean,
        withCallout: Boolean,
    ): Boolean {
        val frame = plan.frame(frameId) ?: return false
        val ranges = step.rangesIn(frameId)
        if (ranges.isEmpty()) return false
        return node.spotlight.paintStep(
            StepPaint(
                active = ranges,
                kept = frame.keep,
                // No wider bounds than the frame keeps: in a node everything outside the kept ranges is
                // elided, so there is nothing out there to dim.
                bounds = null,
                label = if (withCallout) step.step.explanation else null,
                // The primary box carries the whole aside; a helper box carries the note only, because two
                // boxes repeating one sentence verbatim is worse than one box.
                note = if (withCallout && isPrimary) step.step.note else null,
                example = if (isPrimary) step.step.example else null,
                // The at-start beat is what a render-time flash draws (TTS off) — see [startBeat].
                token = if (isPrimary) startBeat(step)?.token else null,
                occurrence = startBeat(step)?.occurrence ?: 1,
                tokenLine = startBeat(step)?.line ?: 0,
                relations = frame.frame.relations,
                file = frame.file,
                role = if (isPrimary) FrameRole.PRIMARY else FrameRole.HELPER,
            ),
        )
    }

    /**
     * **The link pair, and the step's slot allocator** — round 2's replacement for the cross-frame pointer,
     * widened in round 6 to own *every* link on screen rather than only the cross-frame ones.
     *
     * Each node's renderer has just reported the relations belonging to this step
     * ([com.audioexplainer.LinkRequest]); only this class can finish them, because only this class knows
     * which frame owns which file. For every report whose target lands in a node of the mounted scene, *both*
     * ends get a half: same pair id, same **slot**, mirrored arrowheads, each one clickable and aimed at the
     * other. That is the pair, and it is what a lone pointer could never be — a link you can see both ends of.
     *
     * **The slot is why this also owns same-editor links now.** A slot picks a colour, a rail offset and a
     * channel offset ([com.audioexplainer.LinkArt]), and those have to be allocated *across the whole tree*
     * or two links a reader sees at once can be given the same one. Before round 6 an in-file relation was
     * drawn by the renderer on the spot with a single colour and its own channel, while a portal was numbered
     * here — so *"same editor-line and out-of editor links nearby should have different colors"* was not
     * merely unimplemented, it was unimplementable. One allocator, one step, one numbering.
     *
     * Five decisions live here:
     *
     * - **One pair per link, however many ends declared it.** `tree-links.json` states its sibling → sibling
     *   relation from *both* ends deliberately, and the pointer form drew both. Pairing is keyed by
     *   *(frame, line)* on each side, order-independent, so a link declared twice is still one pair: the plan
     *   says what is related, the plugin decides how many pictures that is.
     * - **Keyed on frame ids, not paths.** The two ends arrive in different forms — one resolved off the
     *   plan's own frame, one as whatever string the endpoint's `file` holds — so a path key would fail to
     *   match the reverse declaration and the link would draw twice.
     * - **The join comes from the reporting renderer** ([com.audioexplainer.LinkRequest.local]): it is the
     *   only thing that knows whether the far line is in its own document and near enough to reach with a
     *   line. A same-file link too far apart for that gets [LinkJoin.PORTAL] with both halves in one node —
     *   nothing about a portal needs two editors; what it needs is two ends that cannot be joined by a line.
     * - **Lanes are handed out same-editor-first, then in declaration order**, which is the reader's
     *   *"same-editor links should turn (sequentially, each in own column) first, and portal links should
     *   turn/U-turn sequentially into their portals next"*. It is also what makes the fan fit: a same-editor
     *   lane is 4 px wide and a portal lane is 24, so putting the thin ones nearest the code costs the wide
     *   ones nothing and buys the step room it would otherwise clamp away. Within a join the order is the
     *   plan's, so a reader who learns "the blue one is the invoker" keeps being right as long as the step
     *   does not change.
     * - **Capped at the palette's size** ([MAX_LINKS_PER_STEP]). Past it a link keeps its callout line and
     *   loses its drawing, and the log says how many.
     *
     * Cross-*scene* links get nothing, for the reason [revealEndpoint] gives: the other end is not on screen,
     * so there is no second half to draw. Those keep the callout line and the click that opens a file.
     *
     * **Returns each node that ended up holding a half, and the topmost row it holds one on** — neither is
     * derivable from [painted]: a pair's far end belongs to whichever node owns the target file, and that node
     * is very often one the step never names. [foldUntouched] needs the keys (K4) and [revealLinkRows] needs
     * the rows (K5). The topmost when a node holds several, because one viewport cannot show two distant rows
     * and the first one down is the predictable answer.
     */
    private fun paintLinkPairs(painted: Set<Int>): Map<Int, Int> {
        val halves = LinkedHashMap<Int, MutableList<LinkHalf>>()
        val paired = LinkedHashSet<String>()
        val found = mutableListOf<PendingLink>()
        var dropped = 0
        for ((frameId, nodeIdx) in nodeIndexOf) {
            if (frameId !in painted) continue
            val node = nest.nodes.getOrNull(nodeIdx) ?: continue
            val nearFile = plan.frame(frameId)?.file ?: continue
            for (req in node.spotlight.linkRequests()) {
                // A fileless target means "this frame's own file"; with no line there is no row to draw on.
                val farPath = req.target.file?.trim()?.takeIf { it.isNotEmpty() } ?: nearFile
                if (req.target.line <= 0) continue
                val farFrame = frameOwning(farPath, req.target.line, prefer = frameId) ?: continue
                val farIdx = nodeIndexOf[farFrame] ?: continue
                val farLine = req.target.line - 1
                // Both halves on one row of one node would be two chips saying the same thing.
                if (farIdx == nodeIdx && farLine == req.line) continue
                if (!paired.add(pairKey(frameId, req.origin, req.line + 1, farFrame, req.target))) continue
                if (found.size >= MAX_LINKS_PER_STEP) {
                    dropped++
                    continue
                }
                // A link whose two ends are in one document and near enough to see at once is drawn as a
                // line; everything else gets a portal at each end. The renderer decided it — see
                // [com.audioexplainer.LinkRequest.local] — because only it holds the document.
                val join = if (req.local && farIdx == nodeIdx) LinkJoin.LINE else LinkJoin.PORTAL
                // **Which end comes first on screen**, by node order then line: the nest stacks its nodes and
                // a child's rows sit inside its parent's, so this is right for the ordinary parent → child
                // shape. Its real job is to be *consistent* — the two halves get opposite answers, so one
                // dives exactly when the other climbs. The renderer refines it from live screen geometry
                // (`SpotlightRenderer.targetBelowFor`); this is the answer before there is any.
                val nearFirst = nodeIdx < farIdx || (nodeIdx == farIdx && req.line < farLine)
                val near = req.target
                // The far half's click comes back here, so a pair is navigable from either side. Its file has
                // to be spelled out: the plan may have left the near end's `file` implicit, and by then there
                // is no frame in scope to infer it from.
                val far = req.origin.copy(file = req.origin.file ?: nearFile)
                found += PendingLink(nodeIdx, farIdx, farLine, join, nearFirst, near, far, req)
            }
        }
        // **The lane order, decided once the whole step is known** — same-editor links first, declaration
        // order within each join. `sortedBy` is stable, so this is exactly that and nothing more.
        val order = found.indices.sortedBy { if (found[it].join == LinkJoin.LINE) 0 else 1 }
        // The step's lane layout, in lane order. Every half in every editor carries the same list, because
        // the width of lane k depends on what lanes 0..k-1 hold and a node can only see its own links.
        val lanes = order.map { found[it].join }
        for ((lane, idx) in order.withIndex()) {
            val p = found[idx]
            val req = p.req
            // The pair id follows the lane rather than the declaration, so "link 1" and the first colour are
            // the same link — the only thing the id is otherwise used for is naming a pair in the log.
            val id = lane + 1
            // Each half is anchored to the token at **its own** end: the near half tints the origin's token
            // and points at the target, the far one tints the target's token and points back.
            halves.getOrPut(p.nodeIdx) { mutableListOf() } += LinkHalf(
                line = req.line,
                pairId = id,
                lane = lane,
                lanes = lanes,
                join = p.join,
                outgoing = req.outgoing,
                token = req.origin.token,
                occurrence = req.origin.occurrence,
                tooltip = linkTooltip(p.near, req.note, req.outgoing, req.mutual),
                label = req.note,
                target = p.near,
                targetBelow = p.nearFirst,
                mutual = req.mutual,
            )
            halves.getOrPut(p.farIdx) { mutableListOf() } += LinkHalf(
                line = p.farLine,
                pairId = id,
                lane = lane,
                lanes = lanes,
                join = p.join,
                outgoing = !req.outgoing,
                token = req.target.token,
                occurrence = req.target.occurrence,
                tooltip = linkTooltip(p.far, req.note, !req.outgoing, req.mutual),
                label = req.note,
                target = p.far,
                targetBelow = !p.nearFirst,
                mutual = req.mutual,
            )
        }
        if (halves.isEmpty()) {
            if (dropped > 0) log.info("links on step $stepIndex: none drawn, $dropped past the cap")
            return emptyMap()
        }
        var drawn = 0
        // **One column for every link on screen.** Built per step and handed to each node before it paints,
        // so the two ends of a link line up horizontally instead of each sitting clear of its own node's
        // code — which is the other half of "these two are one link", the half a hover should not be needed
        // to answer. See [com.audioexplainer.LinkAlignGroup] for why the basis is the screen.
        val column = LinkAlignGroup()
        val drawing = halves.mapNotNull { (nodeIdx, list) ->
            nest.nodes.getOrNull(nodeIdx)?.let { it to list }
        }
        // **Join first, paint second, reserve third** — three passes over the same few nodes, and each one
        // needs the pass before it finished everywhere. A node cannot pick its column until every member has
        // declared its demand and its room; it cannot work out which side of a row a rail leaves on until the
        // *other* end of that link has a row to compare screen positions with; and it cannot buy that side
        // its blank space until it knows which side that is. Round 6 did all three in one pass, which was
        // survivable only because nothing then depended on the answer.
        for ((node, _) in drawing) {
            node.spotlight.linkAlign = column
            column.join(node.spotlight)
        }
        var foldedOut = 0
        var degraded = 0
        for ((node, list) in drawing) {
            drawn += node.spotlight.paintLinks(list)
            foldedOut += node.spotlight.droppedEnds
            degraded += node.spotlight.degradedPairs
            // **The pair's own answer to "are these two one link".** A hover on either half is relayed to
            // every node, so the other end lights up in its own editor. Set here rather than at node
            // construction because only a step knows which nodes have halves at all.
            node.spotlight.onLinkHover = { pairId -> relayPortalHover(pairId) }
        }
        for ((node, _) in drawing) node.spotlight.reserveLinkSpace()
        val asked = halves.values.sumOf { it.size }
        // One line per step that has a link, and it is the line a judged round reads: `drawn < asked` means a
        // half landed on a line folded away, which is the one way a pair can come out half-drawn. The two
        // joins are counted separately because they are two pictures, and a round that asked for one of each
        // needs to see that it got one of each.
        val byJoin = halves.values.flatten().distinctBy { it.pairId }.groupingBy { it.join }.eachCount()
        log.info(
            "links on step $stepIndex: ${found.size} link(s) " +
                "(${LinkJoin.LINE.name.lowercase()}=${byJoin[LinkJoin.LINE] ?: 0}, " +
                "${LinkJoin.PORTAL.name.lowercase()}=${byJoin[LinkJoin.PORTAL] ?: 0}), " +
                "$drawn of $asked end(s) drawn" +
                // **Folding, which `Item 1′` named as the one link case never verified.** `drawn < asked`
                // says an end was lost; these two say it was a fold rather than a bad line number, and that
                // the survivor degraded to a lone portal instead of a leg to nowhere.
                (if (foldedOut > 0) ", $foldedOut folded away" else "") +
                (if (degraded > 0) ", $degraded degraded to a lone portal" else "") +
                (if (dropped > 0) ", $dropped past the cap of $MAX_LINKS_PER_STEP" else ""),
        )
        return halves.mapValues { (_, list) -> list.minOf { it.line } }
    }

    /**
     * A link both of whose ends have been found, **before the step's lanes are handed out**.
     *
     * It exists because round 7 made the lane order depend on the whole step: same-editor links take the
     * inner lanes, so no link can be numbered until every link on the step has been found and its join
     * decided. Round 6 numbered each one as it arrived, which is why its lanes could not be ordered at all.
     */
    private class PendingLink(
        val nodeIdx: Int,
        val farIdx: Int,
        val farLine: Int,
        val join: LinkJoin,
        val nearFirst: Boolean,
        val near: Endpoint,
        val far: Endpoint,
        val req: com.audioexplainer.LinkRequest,
    )

    /**
     * Light the given pair in **every** node, so hovering one end of a link brightens the other.
     *
     * Broadcast rather than routed to the partner: a node holds at most one half per pair, so telling all
     * of them costs one no-op call each and needs no map from pair to node. `setHoveredPair` repaints only
     * where it actually changed something.
     */
    private fun relayPortalHover(pairId: Int?) {
        for (node in nest.nodes) node.spotlight.setHoveredPair(pairId)
    }

    /** What a hover calls an endpoint: the plan's own words for it, else the file and line it names. */
    private fun shortName(ep: Endpoint): String =
        ep.symbol?.trim()?.takeIf { it.isNotEmpty() }
            ?: ep.token?.trim()?.takeIf { it.isNotEmpty() }
            ?: baseName(ep.file)
            ?: "line ${ep.line}"

    /** The whole sentence, for a hover: where the half goes, and the relation's own note if it has one. */
    /**
     * [mutual] is `Relation.KIND_BACKFORTH`: both ends of the link are arrived at, so neither "goes to" nor
     * "comes from" is true of either — and a hover that insisted on one would contradict the two arrowheads
     * now drawn beside it.
     */
    private fun linkTooltip(
        ep: Endpoint,
        note: String?,
        outgoing: Boolean,
        mutual: Boolean = false,
    ): String = buildString {
        append(if (mutual) "Refers to " else if (outgoing) "Goes to " else "Comes from ")
        append(shortName(ep))
        baseName(ep.file)?.let {
            append(" · ").append(it)
            if (ep.line > 0) append(':').append(ep.line)
        }
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" — ").append(it) }
        append(" · click to go there")
    }

    private fun baseName(path: String?): String? =
        path?.trim()?.replace('\\', '/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

    /**
     * Order-independent identity for a link: its two *(frame, line)* ends, smaller first — see
     * [paintLinkPairs] for why the key is frames rather than paths.
     */
    private fun pairKey(
        frameA: Int,
        endA: Endpoint,
        lineA: Int,
        frameB: Int,
        endB: Endpoint,
    ): String {
        val a = endKey(frameA, lineA, endA)
        val b = endKey(frameB, endB.line, endB)
        return if (a <= b) "$a|$b" else "$b|$a"
    }

    /**
     * One end of a [pairKey]: its frame, its line, **and the token it names**.
     *
     * The token is what round 8 added, and it is the whole of *"steps 1 and 2 in link-fan.json scenario have
     * 4 links only"*. Two relations may legitimately join the same two **rows** and mean different things —
     * `link-fan.json` has two of them on purpose, distinguished only by which identifier each names — and a
     * key of frame and line alone read the second as a redeclaration of the first and dropped it. The step
     * then drew four links, and the fifth lane, its colour and its rail were never allocated at all.
     *
     * It still collapses the case the key exists for: **one relation declared in both frames**, which
     * `tree-links.json` does for its sibling → sibling link. Each declaration names the same token at the
     * same end, and each end is keyed with its own, so the two normalise to one key from either side.
     *
     * `occurrence` is in it for the same reason as the token: a plan may point at the second `total` on a
     * row and the third on the same row, and those are two links.
     */
    private fun endKey(frame: Int, line: Int, end: Endpoint): String =
        "$frame:$line:${end.token?.trim().orEmpty()}@${end.occurrence}"

    /**
     * The frame in the **mounted scene** that shows [path] at 1-based [line], or null.
     *
     * Shared by [revealEndpoint] and [paintLinkPairs] deliberately: "which node owns this endpoint" has to
     * give the same answer to the chip that draws a link and to the click that follows it, or a portal could
     * be drawn pointing at one node and land in another.
     *
     * **The line is half the key, and leaving it out was the defect.** A path alone identifies a *file*, and
     * a file is routinely cut into several frames — two classes in one `.cs`, a caller and its callee. This
     * took the first frame on the path whatever line was asked for, so every far end in the second window
     * routed to the first node. The consequences compounded: [paintLinkPairs] saw `farIdx != nodeIdx` and
     * downgraded a perfectly local relation to [LinkJoin.PORTAL], then planted the far half on a row that
     * node does not display — a portal with no reachable other end, and a click that scrolled to nothing.
     * The near frame wins a tie ([prefer]) because a link whose ends are both in the reporting node is a
     * line, not a portal, and because overlapping windows would otherwise decide by mount order.
     *
     * Falls back to the first frame on the path when no window holds the line: an endpoint naming a line the
     * scene folds away is still better aimed at that file's node than dropped.
     */
    private fun frameOwning(path: String, line: Int = 0, prefer: Int? = null): Int? {
        val onFile = nodeIndexOf.keys.filter { id ->
            plan.frame(id)?.let { SpotlightRenderer.sameFile(path, it.file) } == true
        }
        if (onFile.size <= 1 || line <= 0) return onFile.firstOrNull()
        if (prefer != null && prefer in onFile && showsLine(prefer, line)) return prefer
        return onFile.firstOrNull { showsLine(it, line) } ?: onFile.first()
    }

    /** True when [frameId]'s kept window actually displays 1-based [line] — the row a link half needs.
     *  [com.audioexplainer.ResolvedFrame.shows] is the rule; `PlanLoader` asks it of the same endpoints. */
    private fun showsLine(frameId: Int, line: Int): Boolean = plan.frame(frameId)?.shows(line) == true

    /**
     * **Follow a relation to another node in this tree** — the cross-frame link, and what makes a portal
     * worth clicking.
     *
     * A relation with both ends in one file is drawn as an arrow inside that node. One that crosses frames
     * cannot be: its target is a *different editor*, nested somewhere inside a parent, and a
     * [com.audioexplainer.SpotlightRenderer] can only paint the document it owns. So the renderer degrades it
     * to a portal at each end (see [paintLinkPairs]) — and this is what gives a portal somewhere to go. The
     * tree scrolls to the node whose frame owns the file, that node scrolls to the line, and the token
     * pulses, so a link into a child or a sibling lands the eye on the target instead of describing it.
     *
     * **Not a drawn connector.** A line joining two nodes would have to be painted on the nest rather than in
     * either editor, which is [LeaderArt]'s surface and its own judged question (constraint 16 — one outline,
     * one owner). Deferred deliberately; this is the behaviour that can be built and checked today.
     *
     * Returns false when no frame owns the endpoint's file, which is the caller's cue to fall back to opening
     * it as a file: a plan may legitimately point at code it never made a frame for — the leaf below the
     * deepest node, a helper it only mentions.
     *
     * **The search is scoped to the mounted scene**, and that is a decision rather than an oversight. A frame
     * in another scene is not on screen, so following the link would have to mount that scene — which moves
     * the tree somewhere the walkthrough's own position does not agree with, mid-narration. Falling back to a
     * tab is the honest outcome for that case, and it is legitimate for the reason round 1 established: this
     * is reached from a **click**, not as a side effect of stepping. A link *within* a scene is the case the
     * portal pair (item 1) is being built for.
     */
    fun revealEndpoint(ep: Endpoint): Boolean {
        // A fileless endpoint means "the file already showing", which in a tree does not name one node.
        // It only reaches here at all when the renderer could not resolve it as an arrow, so there is
        // nothing better to do with it than say so.
        val path = ep.file?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val frameId = frameOwning(path, ep.line) ?: return false
        val node = nodeIndexOf[frameId]?.let { nest.nodes.getOrNull(it) } ?: return false
        // **The row the link names, not the step's own band**, and that distinction is the reported defect:
        // `revealNode` scrolls to whatever the current step is looking at and re-centres it on the way, so
        // following a link the reader could already see moved the view for nothing. `revealRow` scrolls the
        // named row into view and only as far as it has to.
        val line = ep.line - 1
        if (ep.line > 0) nest.revealRow(node, line) else nest.revealNode(node)
        // The caret lands on the target's own token, focused so it is visible. No flash on arrival: the token
        // now carries a portal **tint**, which a pulse competed with. Dropped on the reader's word that it
        // need not be kept — [SpotlightRenderer.flashToken] still serves `playback_started`.
        if (ep.line > 0) node.spotlight.landOn(line, ep.token, ep.occurrence)
        log.info("relation followed into the tree: frame $frameId, ${ep.file}:${ep.line}")
        return true
    }

    /**
     * The beat that fires when this step's audio starts — the one with no `say`, of which `PlanLoader`
     * keeps at most one. Null when every pulse this step draws is timed to a phrase.
     *
     * This is also what a **render-time** flash draws, which is the case with no clock at all: narration
     * switched off, where `SpotlightRenderer.flashOnRender` makes the paint itself the cue. Two triggers,
     * one beat — not two competing anchors, which is what `Step.token` alongside `beats` used to be.
     */
    private fun startBeat(step: ResolvedStep): Beat? = step.step.beats.firstOrNull { it.atStart }

    /**
     * Fire this step's at-start beat — the `playback_started` cue, which is deliberately *not* ridden by
     * the render (measured 1.8–6.0 s early, MoreUI.md §2.8).
     */
    fun flashStartBeat() {
        val step = plan.steps.getOrNull(stepIndex) ?: return
        val idx = step.step.beats.indexOfFirst { it.atStart }
        if (idx >= 0) flashBeat(idx)
    }

    /**
     * Pulse one beat (B1b, MoreUI §4.3) — either because the voice reached the sentence it is timed to,
     * or, for the at-start beat, because the audio began.
     *
     * **The window is the beat's own line**, which is what makes this the same anchor a link end and a call
     * site already use: one line, the nth match on it. A beat with `line = 0` is the legacy shape — a plan
     * that predates the field, synthesised out of `Step.token` by the loader — and falls back to the step's
     * **whole span** rather than its first range. That fallback is not cosmetic: a token in a step's second
     * range used to resolve to nothing at all.
     */
    fun flashBeat(index: Int) {
        val step = plan.steps.getOrNull(stepIndex) ?: return
        val beat = step.step.beats.getOrNull(index) ?: return
        val frameId = step.primaryFrameId
        val node = nodeIndexOf[frameId]?.let { nest.nodes.getOrNull(it) } ?: return
        val ranges = step.rangesIn(frameId)
        if (ranges.isEmpty()) return
        val lo = if (beat.line > 0) beat.line else ranges.minOf { it.start }
        val hi = if (beat.line > 0) beat.line else ranges.maxOf { it.end }
        node.spotlight.flashToken(beat.token, beat.occurrence, lo, hi)
        // Every beat that fires says so. A beat the webview could not place never reaches here, and its
        // absence from this run of lines is the only way an author can tell (§2.2).
        log.info(
            "beat $index of step $stepIndex fired " +
                (if (beat.atStart) "at audio start" else "on \"${beat.say.take(40)}\"") +
                " — pulsing '${beat.token}' in line" + (if (lo == hi) " $lo" else "s $lo-$hi"),
        )
    }

    /** Every node in the tree, so a caller can count editors or ask what a node published. */
    fun nodes(): List<CodeNodeView> = nest.nodes

    override fun dispose() {
        if (disposed) return
        disposed = true
        // The subscription first and unconditionally (constraint 13): a VFS event arriving after the nest is
        // released would revalidate against disposed nodes, and it is the failure that shows up long after
        // the tab was closed rather than while anyone is looking at it.
        try {
            Disposer.dispose(subscriptions)
        } catch (_: Throwable) {
            // Already disposed with a parent.
        }
        nest.dispose()
    }

    companion object {
        /**
         * **Links drawn for one step**, read off [com.audioexplainer.LinkArt.slots] rather than restated: the
         * cap, the palette and the number of routing lanes are one number, and a step that could ask for a
         * sixth link would be a step with no colour and no channel left for it.
         *
         * Counted across the whole tree rather than per node, because a reader looking at a scene sees all of
         * them at once — which is also why the allocator lives in [paintLinkPairs] and not in a renderer.
         */
        private val MAX_LINKS_PER_STEP = com.audioexplainer.LinkArt.slots()

        /**
         * **Helper callouts drawn beside the primary's, per step.**
         *
         * Phase 1 judged one box per step to read well and Phase 3's bullet keeps that verdict as the
         * default it is departing from: *"one Primary (brightest, keeps the scroll and the flash) plus
         * visibly secondary Helper callouts, one per other active node, **capped**"*. Two is the cap, so a
         * three-frame step draws at most three boxes and a wide fan of siblings cannot fill the tree with
         * them. Past it a frame keeps its glow, which is the same degrade a link past its own cap gets.
         */
        private const val MAX_HELPER_CALLOUTS = 2
    }
}
