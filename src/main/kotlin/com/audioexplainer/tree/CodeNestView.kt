package com.audioexplainer.tree

import com.audioexplainer.Endpoint
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * **The code tree: a child node drawn *inside* its parent, at the call site.**
 *
 * A child goes *in* its parent, directly under the line that calls it, with a leader from the invoking token down
 * to the nested frame's top. Sequential, unrelated nodes are unaffected: a root is a top-level node in a scroll
 * pane. Nesting applies to edges, and only to edges that name a call-site line.
 *
 * **What this buys:**
 *
 * - **No overlay.** A connector between two separate nodes would be drawn in the *container's* space, over editors
 *   that repaint themselves, needing a [javax.swing.JLayeredPane] overlay with banded repaints. A leader painted
 *   inside the nested component's own top margin ([LeaderStrip]) is drawn in the space it belongs to and repaints
 *   with its own parent, so none of that machinery is needed.
 * - **No child-side anchor to choose.** The child is physically at the call site and the leader points at the
 *   frame, not at a line inside it.
 * - **Depth is containment**, so indentation is not a number to tune — the nested node's own gutter is the
 *   boundary, and the reader spends no width on an indent.
 *
 * **What it costs:** [EditorEmbeddedComponentManager] lives in `com.intellij.openapi.editor.impl` and is not a
 * stability promise (verified in RD-261.26222.60: no exceptions, clean disposal across 25 rebuilds). There is no
 * fallback layout: if this API moves, the tree needs a layout that does not embed editors in inlays.
 *
 * **EDT only**, like everything it composes. **Disposal is the caller's job** — [dispose] tears down inlays
 * before editors, deepest node first, because this is an editor inside an inlay inside an editor.
 */
class CodeNestView(
    private val project: Project,
    style: NestStyle = NestStyle(),
    nodeStyle: CodeNodeStyle = CodeNodeStyle(),
    private val navigate: (Endpoint) -> Unit = {},
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(CodeNestView::class.java)

    var style: NestStyle = style
        private set

    var nodeStyle: CodeNodeStyle = nodeStyle
        private set

    private val mutableNodes = mutableListOf<CodeNodeView>()

    /** Every node, in the order given to [setTree] — nested and top-level alike. */
    val nodes: List<CodeNodeView> get() = mutableNodes

    private val mutableRoots = mutableListOf<CodeNodeView>()

    /** The nodes that are **not** nested in another, laid out top to bottom in the scroll pane. */
    val roots: List<CodeNodeView> get() = mutableRoots

    private val mutableNestings = mutableListOf<Nesting>()

    /** One per edge that was nested, whether or not the embedding succeeded — see [Nesting.state]. */
    val nestings: List<Nesting> get() = mutableNestings

    private val content = NestContent()

    private val scroll = JBScrollPane(content).apply {
        border = null
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
    }

    /**
     * Coalesced re-render on a theme switch: one callback for the whole tree, not one per node.
     *
     * A scheme change has to re-*render*, not merely re-measure, because every colour is derived
     * per render. N nodes each asking for a re-render would cost N renders of the same step.
     */
    var onRerender: (() -> Unit)? = null

    private var rerenderPending = false

    private var inlayUpdatePending = false

    private var disposed = false

    init {
        add(scroll, BorderLayout.CENTER)
    }

    // ── Building ──

    /**
     * Replace the whole nest: one [CodeNodeView] per spec, each edge's child embedded in its parent's editor at
     * [CodeTreeEdge.parentLine].
     *
     * The signature is shaped for a general graph rather than for containment. Two rules follow from containment
     * rather than from choice, and both are why the schema must not promise what this cannot render:
     *
     * - **An edge with no call-site line cannot be nested at all** — there is no line to put the child under.
     *   Such a child becomes a top-level node and says so ([EmbedState.NO_CALL_LINE]), because degrading to the
     *   flat shape is legible and dropping the node is not.
     * - **A child may be nested in only one parent.** A frame is in one place; a second edge to the same child
     *   is dropped with a warning.
     */
    fun setTree(specs: List<CodeNodeSpec>, edges: List<CodeTreeEdge>) {
        releaseNodes()

        val usable = edges.filter { it.parent in specs.indices && it.child in specs.indices && it.parent != it.child }
        if (usable.size != edges.size) {
            log.warn("dropped ${edges.size - usable.size} edge(s) with out-of-range or self-referential indices")
        }
        val nestable = LinkedHashMap<Int, CodeTreeEdge>()
        for (e in usable) {
            if (nestable.putIfAbsent(e.child, e) != null) {
                log.warn("node ${e.child} is already nested in ${nestable[e.child]?.parent}; edge from ${e.parent} dropped")
            }
        }
        for (spec in specs) {
            val node = CodeNodeView(project, spec, nodeStyle, navigate)
            wire(node)
            mutableNodes += node
        }

        for ((childIndex, edge) in nestable) {
            val parent = mutableNodes.getOrNull(edge.parent) ?: continue
            val child = mutableNodes.getOrNull(childIndex) ?: continue
            val nesting = Nesting(parent, child, edge)
            mutableNestings += nesting
            nesting.attach()
        }

        for ((i, node) in mutableNodes.withIndex()) {
            val nested = mutableNestings.any { it.child === node && it.state == EmbedState.EMBEDDED }
            if (!nested) mutableRoots += node
            if (!nested && i in nestable) {
                log.debug("node $i could not be nested (${mutableNestings.firstOrNull { it.child === node }?.state}); shown top-level")
            }
        }
        // After the nestings exist, because every role below is a question about them: which chrome a node gets,
        // whether it grows, which border edges it owns and whether it lands on its excerpt.
        applyRoles()
        content.setRoots(mutableRoots)
        revalidate()
        repaint()
        scheduleLanding(0)
    }

    /**
     * **Everything about a node that depends on where it sits in the tree**, applied in one pass.
     *
     * Four roles, and none of them is a user preference:
     *
     *  - **chrome** — [chromeFor]'s header rule;
     *  - **grow** — a host grows to content rather than being capped, because the nested frame is *inside* its
     *    editor and a 600 px cap on a parent holding a 500 px child leaves two lines of the parent visible behind
     *    one scrollbar. The cap belongs on leaves;
     *  - **which border edges the node draws** — the leader owns the top edge in the joining modes;
     *  - **the landing** — a nested child is sized to its excerpt and scrolled to it; the root is exempt.
     */
    private fun applyRoles() {
        val embedded = mutableNestings.filter { it.state == EmbedState.EMBEDDED }
        val hosts = embedded.map { it.parent }.toSet()
        val nested = embedded.map { it.child }.toSet()
        for (node in mutableNodes) {
            // **A top-level host grows; a nested one does not** (a nested host is sized to its excerpt plus its
            // subnodes — see [refreshEmbeddedHeights]). A root capped below its content grows an *inner* scrollbar
            // inside the outer pane, which is two scrollbars for one node; the pane it sits in already scrolls.
            val topLevelHost = node in hosts && node !in nested
            node.restyle(
                nodeStyle.with(
                    chrome = chromeFor(node),
                    grow = nodeStyle.grow || (topLevelHost && style.growHosts),
                ),
            )
        }
        for (n in mutableNestings) n.applyLeaderRole()
        refreshEmbeddedHeights()
    }

    /**
     * **Tell every node how much embedded child frame it carries**, so a host is sized to its excerpt plus the
     * full size of its subnodes.
     *
     * A node cannot work this out: the frames are block inlays it did not create, and it knows nothing about the tree.
     * So the two numbers [CodeNodeView.setEmbeddedHeights] takes are computed here — the total, which its cap must not count
     * against its own code, and the part inside its excerpt, which its landing target has to include.
     *
     * **Children first, and that is load-bearing rather than tidy**: a frame's preferred height comes from the child
     * node's published height, which now depends on what *that* child hosts. Parent-first would size an outer host
     * against its inner frame's pre-update height and be wrong by exactly the delta. Post-order, with a `seen` guard
     * because the caller supplies the edges.
     */
    private fun refreshEmbeddedHeights() {
        val seen = HashSet<CodeNodeView>()
        fun visit(node: CodeNodeView) {
            if (!seen.add(node)) return
            val mine = mutableNestings.filter { it.state == EmbedState.EMBEDDED && it.parent === node }
            for (n in mine) visit(n.child)
            val focus = node.spec.focusRange()
            var total = 0
            var inExcerpt = 0
            for (n in mine) {
                val h = n.framePreferredHeight()
                total += h
                // Inside the excerpt means the *call* is on one of the excerpt's lines: that is where the frame is
                // drawn, so that is when its height is part of what the landing has to fit.
                if (focus != null && n.callLine != null && n.callLine in focus) inExcerpt += h
            }
            node.setEmbeddedHeights(total, inExcerpt)
        }
        for (node in mutableNodes) visit(node)
    }

    /**
     * **The header rule**: a caption when the node is top-level *or* in a different file from its parent, and
     * none when a nested child is in the **same** file as its caller.
     *
     * A rule and not a preference, so it does not reach the settings dialog. A cross-file child keeps its caption
     * because that caption is the only place its file name appears, while a same-file child's path would just
     * repeat its caller's.
     */
    fun chromeFor(node: CodeNodeView): NodeChrome {
        val nesting = mutableNestings.firstOrNull { it.child === node && it.state == EmbedState.EMBEDDED }
            ?: return NodeChrome.HEADER_STRIP
        return if (nesting.parent.spec.file == node.spec.file) NodeChrome.PLAIN else NodeChrome.HEADER_STRIP
    }

    /**
     * Assert every nested child's landing, once the layout has settled enough for a scroll to stick.
     *
     * **Bounded retry rather than one deferred pass**: `scrollVertically` clamps to `content − viewport`, and inside a
     * nesting chain the viewport is one deferred pass per level behind — so the first attempt at depth 2 can clamp to
     * 0, which looks exactly like the landing not being implemented.
     *
     * **Bounded passes alone are not enough**, because the thing that keeps moving is the *target*: the embedded
     * heights refresh, a step's callout allowance resizes the node, a drag rewrites the extent. These passes land the
     * common case in the first few EDT turns, but the goal outlives them: [CodeNodeView.landOnExcerpt] latches only on
     * success and [CodeNodeView] re-arms it from every resync trigger.
     */
    private fun scheduleLanding(attempt: Int) {
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            // Before the scroll, not after: a host's target height includes the frames inside its excerpt, and the
            // scrollable extent a `scrollVertically` clamps to is `content − viewport`.
            refreshEmbeddedHeights()
            var unsettled = false
            for (n in mutableNestings) {
                if (n.state != EmbedState.EMBEDDED) continue
                // `relandIfOwed`, not `landOnExcerpt`: a pass that scrolled unconditionally would drag a node a step's
                // reveal had deliberately moved back to the top of its excerpt — and since a collapse schedules
                // these passes, that would happen mid-narration rather than only at build time.
                n.child.relandIfOwed()
                // `contentFitsViewport` for the same reason `landsOnExcerpt` guards the host below: a node whose
                // viewport already holds all of its content never satisfies, because there is nothing for it to
                // scroll to — counting it here would burn every remaining pass on every build and every collapse.
                if (!n.child.landingSatisfied && !n.child.contentFitsViewport()) unsettled = true
                // **And the host** — a collapse moves the extent of the node the frame is *in*, so the
                // node owed a landing is not always the one whose collapse state changed. Guarded on `landsOnExcerpt`
                // rather than on the latch alone: a root is exempt and never satisfies, so counting it here would keep
                // `unsettled` true and burn every pass on every collapse for the rest of the session.
                n.parent.relandIfOwed()
                if (n.parent.landsOnExcerpt && !n.parent.landingSatisfied && !n.parent.contentFitsViewport()) {
                    unsettled = true
                }
            }
            if (unsettled && attempt + 1 < LANDING_PASSES) scheduleLanding(attempt + 1)
        }
    }

    private fun wire(node: CodeNodeView) {
        node.onRerenderNeeded = { requestRerender() }
        node.onGeometryChanged = { onNodeGeometryChanged(node) }
    }

    /**
     * A node moved, folded, scrolled or re-measured. Two different consequences, depending on which side of a
     * nesting the node is on:
     *
     * - it is a **host**: the invoking token has moved, so its leader points at the wrong place until repainted;
     * - it is a **child**: its height changed, so the inlay holding it is now the wrong height. The child's own
     *   `resync` revalidates its Swing parent, which is the frame — but nothing re-measures the *inlay*, and
     *   [Inlay.update] is the only thing that does.
     */
    private fun onNodeGeometryChanged(node: CodeNodeView) {
        if (disposed) return
        var needsInlayUpdate = false
        for (n in mutableNestings) {
            if (n.parent === node) n.repaintLeader()
            if (n.child === node) needsInlayUpdate = true
        }
        if (needsInlayUpdate) scheduleInlayUpdate()
    }

    /** One `Inlay.update()` pass per EDT turn, however many nested children re-measured in it. */
    private fun scheduleInlayUpdate() {
        if (inlayUpdatePending || disposed) return
        inlayUpdatePending = true
        ApplicationManager.getApplication().invokeLater {
            inlayUpdatePending = false
            if (disposed) return@invokeLater
            mutableNestings.forEach { it.updateInlay() }
            // A re-measured frame is a different number of pixels inside its host's excerpt, so the host's target
            // moved with it. Idempotent — `setEmbeddedHeights` no-ops when nothing changed — which is what stops this
            // and the geometry callback from taking turns.
            refreshEmbeddedHeights()
        }
    }

    /**
     * Re-measure a nesting's inlay, then its host's, then *its* host's — **synchronously, innermost first**.
     *
     * The order is load-bearing rather than tidy: an outer inlay's height comes from its frame's preferred size,
     * which comes from the host node's `contentHeight()`, which is `offsetToXY` over a document that only counts the
     * inner inlay *after* the inner one has been updated. Outermost-first would measure the outer frame against the
     * inner frame's old height and be wrong by exactly the delta.
     *
     * The `seen` set is not defensive dressing: [setTree] keeps only one edge per child, so the chain is a path by
     * construction — but it is a path derived from caller-supplied indices, and a cycle here would be an infinite
     * loop on the EDT rather than a wrong number.
     */
    private fun updateInlayChain(from: Nesting) {
        val seen = HashSet<Nesting>()
        var current: Nesting? = from
        while (current != null && seen.add(current)) {
            current.updateInlaySelf()
            val host = current.parent
            current = mutableNestings.firstOrNull { it.child === host && it.state == EmbedState.EMBEDDED }
        }
    }

    private fun releaseNodes() {
        // Inlays first, deepest node last: this is an editor inside an inlay inside an editor, and releasing the
        // host editor while its inlay still holds a live child editor leaks the child.
        mutableNestings.forEach {
            try {
                it.detach()
            } catch (t: Throwable) {
                log.warn("detaching nesting ${it.child.spec.file.name} failed", t)
            }
        }
        mutableNestings.clear()
        for (node in mutableNodes.asReversed()) {
            try {
                node.dispose()
            } catch (t: Throwable) {
                // Never abort the teardown of the other N-1 nodes; a leaked editor holds a document.
                log.warn("disposing node ${node.spec.file.name} failed", t)
            }
        }
        mutableNodes.clear()
        mutableRoots.clear()
        content.setRoots(emptyList())
    }

    // ── Attention, reveal, collapse ──

    /**
     * Make [index] the node being talked about — **expanding whatever is folded between it and the root first**.
     *
     * Stepping into a collapsed node expands it, and every collapsed ancestor too. That belongs here rather than in
     * the caller because "which nestings are between this node and the root"
     * is the one question only this class can answer, and because every path that moves attention comes through here.
     */
    fun setActiveNode(index: Int) {
        mutableNodes.getOrNull(index)?.let { expandTo(it) }
        for ((i, node) in mutableNodes.withIndex()) node.setActive(i == index)
        repaint()
    }

    /**
     * Expand every collapsed nesting between [node] and the root, **innermost first**.
     *
     * Innermost-first, for [updateInlayChain]'s reason: expanding an inner nesting changes its frame's height, and
     * [Nesting.setCollapsed] re-measures the whole chain above it — so going outermost-first would re-measure the outer
     * inlay against the inner frame's still-collapsed height and be short by exactly its content.
     *
     * Returns how many it expanded, which the caller needs: a node that was hidden a moment ago has no meaningful
     * geometry yet, so a reveal that expanded something has to come back for a second pass.
     */
    fun expandTo(node: CodeNodeView): Int {
        var expanded = 0
        for (nesting in nestingsAbove(node)) {
            if (nesting.collapsed) {
                nesting.setCollapsed(false)
                expanded++
            }
        }
        return expanded
    }

    /**
     * The nestings between [node] and the root, **innermost first** — exactly the ones that have to be open for a step
     * in [node] to be visible, and nothing else.
     *
     * Public so a check asks the same derivation [expandTo] walks: "nothing is collapsed" is the wrong assertion
     * when sibling nestings off the path are correctly left folded.
     */
    fun nestingsAbove(node: CodeNodeView): List<Nesting> {
        val chain = ArrayList<Nesting>()
        var current: CodeNodeView = node
        val guard = HashSet<CodeNodeView>()
        while (guard.add(current)) {
            val nesting = nestingOf(current) ?: break
            chain += nesting
            current = nesting.parent
        }
        return chain
    }

    /** The nesting [node] is the child of, or null when it is a root (or was not embedded). */
    private fun nestingOf(node: CodeNodeView): Nesting? =
        mutableNestings.firstOrNull { it.child === node && it.state == EmbedState.EMBEDDED }

    /**
     * Bring a node's **current step** into view at every level it is nested in — the whole chain, not the innermost
     * editor and the outer pane.
     *
     * Scrolling each host to the nesting's *call line* would reveal only the top of the child's frame, and a frame is
     * up to 600 px tall plus whatever *it* hosts — so a step near the bottom of a nested node, or a callout docked
     * above it, would sit below the host's viewport.
     *
     * So a **band** is propagated instead of a line: [CodeNodeView.stepBand] says what the step occupies inside the
     * node, each host scrolls the minimum needed to show it ([CodeNodeView.showContentBand]) and answers where that
     * band now sits in *its* own coordinates, and the walk repeats one level out. The outermost node hands the band to
     * the Swing scroll pane. Every level therefore scrolls to the thing the reader is meant to read rather than to a
     * landmark near it.
     *
     * A collapsed ancestor is expanded on the way ([expandTo]); the walk needs it: a band inside a hidden component
     * converts to a stale rectangle.
     *
     * **And the node re-reveals its own step first.** Nothing above a node can fix what the node itself has scrolled
     * away ([CodeNodeView.stepBand] clips to what it shows), and a step rendered while the node was collapsed scrolled
     * against a viewport of height 0: both halves of `SpotlightRenderer`'s reveal degrade silently there. So the
     * innermost scroll is re-issued before the walk that depends on it.
     */
    fun revealNode(node: CodeNodeView) = revealNode(node, followUp = false, row = -1)

    /**
     * **Reveal one named row of [node]** rather than whatever its current step is looking at.
     *
     * Where a portal's click goes. [revealNode] exists to put a *step* on screen, so it re-centres the step
     * inside the node and then scrolls the chain to the step's band — both correct for stepping and both
     * wrong for following a link, which lands somewhere the step has nothing to say about. This walks the same
     * chain with the row's own band and without the re-centring, so a row already on screen costs no scroll at
     * all (`scrollRectToVisible` moves the minimum, and nothing if the rect already fits).
     */
    fun revealRow(node: CodeNodeView, line: Int) = revealNode(node, followUp = false, row = line)

    private fun revealNode(node: CodeNodeView, followUp: Boolean, row: Int) {
        if (disposed) return
        // Before any geometry is read: an ancestor that is folded away has laid nothing out at its real size.
        val expanded = expandTo(node)
        // **Then the node's own viewport, when a row was named** — and before the band is read, because the band
        // is what the row scroll decides. See [CodeNodeView.showOwnRow]: the walk below moves a node's ancestors
        // and never the node, so a row below its own fold read back as null and degraded the whole reveal to
        // "show this node". It runs on the follow-up pass too, which is what makes the collapsed case land in one
        // click: the first pass scrolls against an extent the expansion has not laid out yet.
        val ownRow = row >= 0 && node.showOwnRow(row)
        // Innermost first, and before the band is read: the band is *derived* from where this scroll leaves the step.
        // Skipped for a row reveal, which has its own destination and must not have the step's imposed on it.
        val reRevealed = row < 0 && node.reRevealStep()
        var current = node
        // Read **once**: the walk scrolls the node's ancestors, so the band moves under it.
        val ownBand = if (row >= 0) node.lineBand(row) else node.stepBand()
        var band = ownBand ?: Rectangle(0, 0, node.width.coerceAtLeast(1), node.height.coerceAtLeast(1))
        var levels = 0
        val guard = HashSet<CodeNodeView>()
        while (guard.add(current)) {
            val nesting = nestingOf(current) ?: break
            band = nesting.showBandInHost(band) ?: break
            current = nesting.parent
            levels++
        }
        if (current.parent === content) current.scrollRectToVisible(band)
        log.debug("reveal: levels=$levels expanded=$expanded reRevealed=$reRevealed ownRow=$ownRow " +
            "band=${band.y}..${band.y + band.height}px" +
            (if (ownBand == null) " (whole node — no step band)" else "") +
            (if (followUp) " (follow-up pass)" else ""))
        // **One deferred follow-up, always.** Every scroll above was issued against geometry that other rules are
        // still moving in this same turn: an expanded frame is re-measured synchronously but laid out by Swing on the
        // next validate, the callout's allowance resizes the node, and each level of nesting is a deferred pass
        // behind. One extra
        // pass is what makes the last of those the one that decides. Terminates by construction: the follow-up does not
        // schedule another, and by then nothing is collapsed and no height is pending.
        if (!followUp) {
            ApplicationManager.getApplication().invokeLater {
                if (!disposed) revealNode(node, followUp = true, row = row)
            }
        }
    }

    /**
     * **Reveal every node a step touches at once, or fall back to the primary's** — the scroll policy for a step
     * whose ranges live in more than one frame.
     *
     * Reveal all of them if they fit, otherwise reveal the primary frame's. An unconditional reveal of the
     * midpoint between two frames far apart would land the viewport on the elided gap between them, showing
     * neither.
     *
     * Fitting is measured on the nodes' own rectangles in the scroll content's coordinates, which is the
     * honest question ("would both nodes be on screen together"). Each node has already scrolled *itself* to
     * its ranges — that is `SpotlightRenderer.revealCenter`, which runs per editor in
     * [com.audioexplainer.tree.TreePlanView.applyStep] — so what is left for this to decide is only where the
     * outer pane goes.
     *
     * Returns true when it showed them all, false when it fell back.
     */
    fun revealNodes(primary: CodeNodeView, touched: List<CodeNodeView>): Boolean {
        if (disposed) return false
        val others = touched.filter { it !== primary }
        if (others.isEmpty()) {
            revealNode(primary)
            return false
        }
        // A folded ancestor has laid nothing out at its real size, so expand before any geometry is read —
        // the same precondition [revealNode] opens with, and for the same reason.
        for (node in touched) expandTo(node)
        val view = viewportRect()
        val union = unionInContent(touched)
        if (view.height <= 0 || union == null || union.height > view.height) {
            // They do not fit (or the geometry is not there yet). The primary is the one frame the step is
            // *about*, so it is what the reader gets — never the midpoint, which is nobody's code.
            revealNode(primary)
            log.debug("reveal: multi-frame: " + touched.size + " node(s) did not fit " +
                "(" + (union?.height ?: -1) + "px of " + view.height + "px) — revealed the primary")
            return false
        }
        for (node in touched) node.reRevealStep()
        content.scrollRectToVisible(union)
        log.debug("reveal: multi-frame: " + touched.size + " node(s) fitted " +
            "(" + union.height + "px of " + view.height + "px) band=" +
            union.y + ".." + (union.y + union.height) + "px")
        // The same deferred second pass [revealNode] takes, for the same reason: every
        // measurement above was read against geometry other rules are still moving this turn.
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) {
                unionInContent(touched)?.let { content.scrollRectToVisible(it) }
            }
        }
        return true
    }

    /**
     * The smallest rectangle in [content]'s coordinates holding all of [nodes], or null when any of them has
     * no geometry yet.
     *
     * `convertRectangle` walks whatever hierarchy the node is actually in, which for a nested child is an
     * inlay's component inside its parent's editor — so this works at every depth without the caller
     * knowing the depth.
     */
    private fun unionInContent(nodes: List<CodeNodeView>): Rectangle? {
        var union: Rectangle? = null
        for (node in nodes) {
            if (node.width <= 0 || node.height <= 0 || !node.isShowing) return null
            val r = SwingUtilities.convertRectangle(node, Rectangle(0, 0, node.width, node.height), content)
            union = union?.union(r) ?: r
        }
        return union
    }

    /**
     * **Fold away every nesting that holds none of [keep]** — what [StepFold] does on screen.
     *
     * The line budget is *lines on screen at once*. A scene is the frames on screen together, so the one lever
     * that changes the number without changing what the author asked for is **which of those frames are
     * expanded** — and a step that is narrating one node has no need of its siblings' bodies.
     *
     * So a step collapses the nestings whose subtree it is not talking about, and stepping into one expands it
     * again ([setActiveNode] does that, innermost-first). Nothing is dropped and no `keep` range is
     * second-guessed; the reader can re-open any of them by hand, and the next step re-collapses only what it
     * is not using.
     *
     * Returns how many nestings it collapsed, for the log line that has to say.
     */
    fun collapseAllExcept(keep: Set<CodeNodeView>): Int {
        var collapsed = 0
        for (nesting in mutableNestings) {
            if (nesting.state != EmbedState.EMBEDDED) continue
            // Keep a nesting open when the step touches its child or anything nested inside it: collapsing an
            // ancestor of the active node would hide the very thing being narrated.
            val wanted = subtreeOf(nesting.child).any { it in keep }
            if (wanted || nesting.collapsed) continue
            nesting.setCollapsed(true)
            collapsed++
        }
        return collapsed
    }

    /** [node] and everything nested inside it, at any depth. */
    fun subtreeOf(node: CodeNodeView): List<CodeNodeView> {
        val out = mutableListOf(node)
        var i = 0
        while (i < out.size) {
            val current = out[i++]
            for (nesting in mutableNestings) {
                if (nesting.parent === current && nesting.child !in out) out += nesting.child
            }
        }
        return out
    }


    /** The outer scroll pane's visible rectangle, in [content]'s coordinates; empty before layout. */
    fun viewportRect(): Rectangle = scroll.viewport?.viewRect ?: Rectangle()

    private fun requestRerender() {
        if (rerenderPending || disposed) return
        rerenderPending = true
        ApplicationManager.getApplication().invokeLater {
            rerenderPending = false
            if (disposed) return@invokeLater
            onRerender?.invoke()
            mutableNestings.forEach { it.restyleLeader() }
        }
    }

    // ── Styling ──

    fun restyleNodes(next: CodeNodeStyle) {
        nodeStyle = next
        applyRoles()
        mutableNestings.forEach { it.repaintLeader() }
        scheduleLanding(0)
    }

    /**
     * The leader mode and the strip's geometry — the reason a restyle is not just a repaint.
     *
     * **This is destructive rather than cosmetic**: the mode decides which border edges the box and the frame draw,
     * so switching `CORNERS → ARROW` has to re-apply the *node's* border and the token box's closedness as well as
     * redraw the leader. A restyle that only repainted the strip would leave a topless frame under an arrow, or a
     * ghost top edge under a diagonal.
     */
    fun restyleNest(next: NestStyle) {
        val hostsChanged = next.growHosts != style.growHosts
        style = next
        // Node roles first when they changed, then every nesting — one path rather than two, because
        // `restyleLeader` is what re-applies the frame's inset and the strip's height and `applyRoles` is not.
        if (hostsChanged) restyleNodes(nodeStyle)
        mutableNestings.forEach { it.restyleLeader() }
        content.revalidate()
        content.repaint()
        scheduleLanding(0)
    }

    // ── Teardown ──

    override fun dispose() {
        if (disposed) return
        disposed = true
        releaseNodes()
    }

    /**
     * One child nested in one parent: the inlay that holds it, the token that invokes it, and the state of both.
     *
     * Whether the platform accepted the component is [state]; whether the invoking token was found, and whether the
     * leader's coordinate conversion into the frame worked, is [leaderState].
     */
    inner class Nesting internal constructor(
        val parent: CodeNodeView,
        val child: CodeNodeView,
        private val edge: CodeTreeEdge,
    ) {
        /** 1-based line in the host's document holding the call, or null if the edge did not name one. */
        val callLine: Int? = edge.parentLine

        var state: EmbedState = EmbedState.NO_CALL_LINE
            private set

        var leaderState: LeaderState = LeaderState.MISSING
            private set

        /** The invoking token's offsets in the host document, or null when it could not be located on the line. */
        private var tokenOffsets: IntRange? = null

        private var tokenMark: RangeHighlighter? = null

        private var inlay: Inlay<*>? = null

        /** The host-editor listeners behind the collapsed chevron, kept so [detach] can take them off again. */
        private var chevronClicks: EditorMouseListener? = null

        private var chevronMotion: EditorMouseMotionListener? = null

        /** Whether the pointer is currently over the chevron, so the cursor is requested on the transition only. */
        private var chevronHovered = false

        private val frame = NestedFrame(this)

        var collapsed: Boolean = false
            private set

        internal fun attach() {
            val line = callLine ?: return
            val doc = parent.editor.document
            val line0 = line - 1
            if (line0 < 0 || line0 >= doc.lineCount) {
                state = EmbedState.NO_CALL_LINE
                return
            }
            tokenOffsets = locateToken(line0)
            leaderState = when {
                tokenOffsets == null -> LeaderState.MISSING
                edge.callToken == null -> LeaderState.WHOLE_LINE
                else -> LeaderState.ON_TOKEN
            }

            val offset = ReadAction.computeBlocking<Int, RuntimeException> { doc.getLineEndOffset(line0) }
            val props = EditorEmbeddedComponentManager.Properties(
                // The frame is sized by its content, not by the reader dragging its edge.
                EditorEmbeddedComponentManager.ResizePolicy.none(),
                null,
                /* relatesToPrecedingText = */ true,
                /* showAbove = */ false,
                // Deliberately false: the frame belongs to the call line, so if that line is ever folded away
                // the expansion should go with it rather than float over an unrelated place. The report states
                // whether every inlay is still valid, which is how a surprise here becomes visible.
                /* showWhenFolded = */ false,
                /* fullWidth = */ true,
                /* priority = */ 0,
                offset,
            )
            inlay = try {
                EditorEmbeddedComponentManager.getInstance().addComponent(parent.editor, frame, props)
            } catch (t: Throwable) {
                // An impl-package API on a viewer editor: if this ever throws, the child renders at top level
                // (EmbedState.FAILED) and the issue list says so, rather than showing an empty parent.
                log.warn("addComponent failed for ${child.spec.file.name} in ${parent.spec.file.name}", t)
                null
            }
            state = if (inlay != null) EmbedState.EMBEDDED else EmbedState.FAILED
            if (state == EmbedState.EMBEDDED) {
                applyLeaderRole()
                frame.addComponentListener(object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent) = scheduleInlayUpdate()
                })
                installChevronHandlers()
            }
        }

        /**
         * **The collapsed chevron's click and cursor**, on the *host editor* rather than on a component.
         *
         * There is no component to hang them on: the box is a [RangeHighlighter]'s custom renderer painted into the
         * host's own text, so the only thing that can hear a click on it is an [EditorMouseListener].
         *
         * Both handlers hit-test against [chevronRectInContent], which derives from [LeaderArt.boxRect] like the
         * painter does — one derivation, so the chevron cannot be drawn in one place and click in another.
         */
        private fun installChevronHandlers() {
            val clicks = object : EditorMouseListener {
                override fun mousePressed(event: EditorMouseEvent) {
                    val hit = chevronRectInContent() ?: return
                    val p = pointInContent(event) ?: return
                    if (!hit.contains(p)) return
                    // Consumed so the click toggles the nesting instead of also moving the host's caret into the
                    // token — a caret jump the reader did not ask for and cannot see the reason for.
                    event.mouseEvent.consume()
                    // A toggle, not an expand: the chevron is drawn in both states, and an affordance that is
                    // visible in a state where it does nothing is worse than no affordance at all.
                    setCollapsed(!collapsed)
                }
            }
            val motion = object : EditorMouseMotionListener {
                override fun mouseMoved(event: EditorMouseEvent) {
                    val hit = chevronRectInContent()
                    val p = pointInContent(event)
                    val over = hit != null && p != null && hit.contains(p)
                    // **On the transition only.** This fires for every mouse move over the host editor, and
                    // `setCustomCursor` is a global request rather than a local assignment — re-asserting `null` on
                    // every move would fight every *other* cursor provider in the editor (the ctrl-hover hand over a
                    // reference is the obvious one) for as long as the pointer is inside a host.
                    if (over == chevronHovered) return
                    chevronHovered = over
                    // `setCustomCursor` rather than `contentComponent.cursor`: the editor re-asserts its own cursor on
                    // every move, so a plain assignment is overwritten within the same gesture.
                    parent.editor.setCustomCursor(this@Nesting, if (over) HAND else null)
                }
            }
            parent.editor.addEditorMouseListener(clicks)
            parent.editor.addEditorMouseMotionListener(motion)
            chevronClicks = clicks
            chevronMotion = motion
        }

        private fun pointInContent(event: EditorMouseEvent): Point? {
            val src = event.mouseEvent.component ?: return null
            return SwingUtilities.convertPoint(src, event.mouseEvent.point, parent.editor.contentComponent)
        }

        /**
         * The chevron's rectangle in the host editor's content coordinates, or null when there is none to hit — no
         * token located, the expansion open, or the affordance switched off.
         *
         * Recomputed per call and never cached, for [LeaderArt.TokenBoxRenderer]'s reason: `offsetToXY` is what knows
         * where a folded, scrolled, re-fonted document draws, and all three move the box without an event this class
         * could listen to.
         */
        internal fun chevronRectInContent(): Rectangle? {
            // No `collapsed` test of its own — the chevrons are drawn in both states, so they are clickable in both. The
            // state still goes *into* the derivation, because `BOTTOM_BORDER` has a target in one state only and this is
            // the same call the painter makes.
            if (state != EmbedState.EMBEDDED || parent.editor.isDisposed) return null
            val range = tokenOffsets ?: return null
            // `computeBlocking`, not the `compute(ThrowableComputable)` overload RD-2026.1 deprecates (whose own body
            // calls this one).
            return ReadAction.computeBlocking<Rectangle?, RuntimeException> {
                // **The pad belongs here too**: this is the rectangle a *click* is tested against, and
                // [LeaderArt.TokenBoxRenderer] paints from a box that is `boxPadBelow` taller. Without it, for
                // [ChevronStyle.BOTTOM_BORDER] — whose band sits at the box's *bottom* — the whole target would be off
                // by the pad.
                LeaderArt.boxRect(parent.editor, range.first, range.last + 1, style.boxPadBelow)
                    ?.let { LeaderArt.chevronRect(it, style.chevron, collapsed) }
            }
        }

        /**
         * Everything the **leader mode** decides about the two components either side of it, applied together.
         *
         * Whatever draws them has to be told the mode, in one call: the token box's closedness, the child frame's top
         * edge, the collapse affordance's home, and the landing. They are one call because they are one design;
         * applied independently they drift into stale border segments.
         */
        internal fun applyLeaderRole() {
            if (state != EmbedState.EMBEDDED) return
            child.setTopEdgeDrawn(LeaderArt.drawsFrameTop(style.leader))
            // The second half of the top edge, and it moves with the mode exactly as the first does: in `BRACE` the
            // frame draws its own rounded top corners (see [LeaderArt.roundsFrameTop]).
            child.setTopCornersRounded(LeaderArt.roundsFrameTop(style.leader))
            child.setLandsOnExcerpt(true)
            // Beside the landing rather than anywhere else, because it is the landing's precondition and not a
            // decoration: this is the one call that decides a node must be able to put a member at the top of its own
            // viewport, so it is the one place that can also make sure the room to do so exists.
            child.setLandingSlack(style.nodeSlack)
            applyTokenMark()
        }

        internal fun detach() {
            removeTokenMark()
            // Before the inlay, because these hang off the *host's* editor and therefore outlive the child: a
            // motion listener left on a live editor keeps calling `setCustomCursor` for a nesting that is gone.
            if (!parent.editor.isDisposed) {
                chevronClicks?.let { parent.editor.removeEditorMouseListener(it) }
                chevronMotion?.let { parent.editor.removeEditorMouseMotionListener(it) }
                parent.editor.setCustomCursor(this, null)
            }
            chevronClicks = null
            chevronMotion = null
            inlay?.let {
                try {
                    if (it.isValid) Disposer.dispose(it)
                } catch (t: Throwable) {
                    log.debug("disposing inlay for ${child.spec.file.name} failed: $t")
                }
            }
            inlay = null
        }

        /**
         * Find the invoking token on the call line, falling back to the line's trimmed extent.
         *
         * The rule itself lives in [LeaderArt.locateCallToken], so every surface agrees about which characters the
         * call is.
         */
        private fun locateToken(line0: Int): IntRange? = ReadAction.computeBlocking<IntRange?, RuntimeException> {
            LeaderArt.locateCallToken(parent.editor.document, line0, edge.callToken, edge.callOccurrence)
        }

        /**
         * Box the invoking token.
         *
         * **The box is a [LeaderArt.TokenBoxRenderer], not `EffectType.BOXED`**: the effect draws on the glyph
         * bounds, with zero clearance to the token's own first and last character; a renderer can inflate the
         * rectangle. The colour is [LeaderArt.leaderBase], the leader's own, because the box and the line out of it
         * are one object to the reader's eye.
         *
         * The box is *structural*, not an option: every leader mode starts at its bottom corners. A boxless mode
         * would leave a leader starting at corners that do not exist.
         *
         * **Three-sided in `CORNERS`/`BRACE`, four in `ARROW`** — [LeaderArt.closesTokenBox] is the one place that
         * says which, and the renderer is told rather than asked to work it out.
         */
        private fun applyTokenMark() {
            removeTokenMark()
            val range = tokenOffsets ?: return
            tokenMark = try {
                parent.editor.markupModel.addRangeHighlighter(
                    range.first, range.last + 1, TOKEN_LAYER, TextAttributes(), HighlighterTargetArea.EXACT_RANGE,
                ).also {
                    it.customRenderer = LeaderArt.TokenBoxRenderer(
                        LeaderArt.leaderBase(),
                        // Closed in `ARROW` always — and **in every mode while collapsed**: the bottom edge belongs to
                        // whatever continues the outline, and with the expansion hidden nothing does, so the box closes
                        // it itself. The collapsed box is therefore identical in all three leader modes.
                        LeaderArt.closesTokenBox(style.leader) || collapsed,
                        // **In both states**: same rectangle either way — only the direction changes — so the reader's
                        // hand learns one target for both actions.
                        style.chevron,
                        /* collapsed = */ collapsed,
                        // The same number as the frame's top inset (expanded) or its whole height (collapsed), so the
                        // box's bottom edge lands on the strip's row 0 in one state and on clear margin in the other.
                        style.boxPadBelow,
                    )
                }
            } catch (t: Throwable) {
                log.debug("token mark failed on ${parent.spec.file.name}:$callLine: $t")
                null
            }
        }

        private fun removeTokenMark() {
            tokenMark?.let {
                try {
                    parent.editor.markupModel.removeHighlighter(it)
                } catch (_: Throwable) {
                    // Markup already gone with the editor.
                }
            }
            tokenMark = null
        }

        // ── Collapse ──

        fun setCollapsed(next: Boolean) {
            if (collapsed == next || state != EmbedState.EMBEDDED) return
            collapsed = next
            frame.setCollapsed(next)
            // **The box carries the whole collapsed state** — its bottom edge comes back and the chevron turns round.
            // Re-applied rather than repainted: the renderer is constructed with both the mode and the chevron's
            // direction, so a repaint would redraw the old shape perfectly.
            applyTokenMark()
            // The hand cursor is deliberately *not* cleared here: the affordance survives the toggle in place, and
            // clearing the cursor would make it look dead until the next mouse move.
            updateInlay()
            // **Both directions owe a landing, and they owe it to two different nodes.** After `updateInlay`, because a
            // landing clamps to `content − viewport` and neither node's extent is final until the frame has its height.
            //
            // *Expanding* owes it to the **child** — see [CodeNodeView.invalidateLanding]. Collapsing does not: the
            // child's viewport is 0 px while folded, so re-arming on the way down would spend the attempt cap on a
            // target unreachable at any offset and retire the landing for good.
            //
            // *Both* owe it to the **host** — see [CodeNodeView.invalidateLandingAsHost]. The host's viewport never
            // goes away; its content shrinks by the frame's whole height, which can leave it shorter than the viewport,
            // and then the platform clamps the host to offset 0 and nothing re-issues the landing it had already
            // latched. Expand is included because the extent moves back by the same amount.
            if (!next) child.invalidateLanding()
            parent.invalidateLandingAsHost()
            scheduleLanding(0)
        }

        /**
         * One rung of [CodeNestView.revealNode]'s walk: take a band in the child's coordinates into the host's editor,
         * scroll the host the minimum needed to show it, and answer where it landed in the **host node's** coordinates.
         *
         * Null when the conversion cannot be trusted — a detached or failed nesting, a disposed host, or a frame that
         * is not inside the host's content component.
         */
        internal fun showBandInHost(bandInChild: Rectangle): Rectangle? {
            if (state != EmbedState.EMBEDDED || parent.editor.isDisposed) return null
            val content = parent.editor.contentComponent
            if (!SwingUtilities.isDescendingFrom(child, content)) return null
            return parent.showContentBand(SwingUtilities.convertRectangle(child, bandInChild, content))
        }

        /**
         * Re-measure this inlay **and every inlay above it in the nesting chain**.
         *
         * Otherwise an inner re-measure reaches the *outer* inlay only through the host node's geometry callback,
         * which coalesces onto an `invokeLater` — one deferred pass per level of nesting, so any synchronous read of
         * a two-deep chain is short by exactly the inner frame's height.
         *
         * Walking the chain here rather than waiting for the coalesced pass makes the whole chain settle in one
         * EDT turn, which is also what lets a check stay synchronous. See [updateInlayChain].
         */
        internal fun updateInlay() = updateInlayChain(this)

        /**
         * This inlay alone.
         *
         * The one call that cannot be left to Swing: `revalidate()` on the frame walks up to the nearest validate
         * root, which for an embedded component is inside the editor's own plumbing, and nothing there knows the
         * *inlay* should now be a different number of pixels tall.
         */
        internal fun updateInlaySelf() {
            val i = inlay ?: return
            if (!i.isValid) return
            frame.revalidate()
            // No `frame.validate()` here: forcing layout first does not remove the occasional 1–2 px gap between the
            // frame's preferred height and the inlay's on the last sibling of a chain. Whatever moves the preferred
            // height moves it *after* the inlay is told, so the cause is not layout ordering.
            try {
                i.update()
            } catch (t: Throwable) {
                log.debug("inlay update failed for ${child.spec.file.name}: $t")
            }
        }

        internal fun repaintLeader() = frame.repaintLeader()

        internal fun restyleLeader() {
            frame.restyle()
            applyLeaderRole()
            updateInlay()
        }

        // ── What the report reads ──

        /** The token's x range in the host editor's **content component** coordinates, or null. */
        internal fun tokenXRange(): Pair<Int, Int>? {
            val range = tokenOffsets ?: return null
            if (parent.editor.isDisposed) return null
            return ReadAction.computeBlocking<Pair<Int, Int>?, RuntimeException> {
                val a = parent.editor.offsetToXY(range.first)
                val b = parent.editor.offsetToXY(range.last + 1)
                if (b.x <= a.x) null else a.x to b.x
            }
        }

        /**
         * The leader's endpoints in [target]'s coordinates: the token box's bottom-left and bottom-right.
         *
         * The conversion is the whole point. The x comes from `offsetToXY`, which is the editor content
         * component's space; the leader is painted in a strip nested two components deeper and inset by the
         * nesting indent. Skipping the conversion is wrong by exactly the indent.
         */
        internal fun leaderEndpoints(target: JComponent): Pair<Int, Int>? {
            val (tx1, tx2) = tokenXRange() ?: return null
            // The **box's** corners, not the token's glyph bounds: the box is inflated by `TOKEN_PAD`, and starting
            // the leader at the glyph would leave it 2 px inside the box's own wall ([LeaderArt.boxEdges]).
            val (x1, x2) = LeaderArt.boxEdges(tx1, tx2)
            val content = parent.editor.contentComponent
            if (!SwingUtilities.isDescendingFrom(target, content)) {
                leaderState = LeaderState.UNCONVERTIBLE
                return null
            }
            val p1 = SwingUtilities.convertPoint(content, Point(x1, 0), target) ?: return null
            val p2 = SwingUtilities.convertPoint(content, Point(x2, 0), target) ?: return null
            return p1.x to p2.x
        }

        /** The leader strip's height inside the frame — the band a collapse click is expected to land in. */
        fun stripHeight(): Int = style.stripHeight()

        fun framePreferredHeight(): Int = frame.preferredSize.height

        fun inlayIsValid(): Boolean = inlay?.isValid == true

        fun inlayHeight(): Int = inlay?.heightInPixels ?: -1

        override fun toString(): String =
            "${parent.spec.title} → ${child.spec.title} @line ${callLine ?: "-"} " +
                "[$state/$leaderState]${if (collapsed) " collapsed" else ""} " +
                "frame=${frame.width}x${frame.height} inlay=${inlayHeight()}px valid=${inlayIsValid()}"
    }

    /**
     * The component handed to [EditorEmbeddedComponentManager]: the leader strip on top, the nested node below.
     *
     * The empty border is the nesting indent, and it is why this approach needs no overlay — the strip lives in
     * the frame's own top margin, so the diagonals are painted by a component that repaints with the thing they
     * point into.
     */
    private inner class NestedFrame(private val nesting: Nesting) : JPanel() {

        private val strip = LeaderStrip(nesting)

        private val grip = ResizeGrip(nesting)

        private var collapsed = false

        init {
            // A null layout: the grip band inside the bottom inset, the collapse to a near-zero-height frame, and
            // the z-order below all need hand-positioning, none of which a `BorderLayout` gives.
            layout = null
            isOpaque = false
            applyBorder()
            add(nesting.child)
            add(grip)
            add(strip)
            // Index 0 paints last. This one line is why the extended tips are visible at all: with the strip
            // behind, the child's opaque background erases them completely.
            setComponentZOrder(strip, 0)
        }

        private fun applyBorder() {
            // Collapsed gives up its margins, because while collapsed the frame *is* one margin: its whole height is
            // [NestStyle.collapsedFrameHeight] and an inset on top of that would be padding on padding. Expanded, the
            // top inset is where [NestStyle.boxPadBelow] lives — `doLayout` already places the strip at `ins.top` and
            // `getPreferredSize` already counts it, so the pad reaches the geometry through a term both already had.
            // That is what keeps the shared-origin identity ([LeaderArt]) true rather than approximately true: the strip's
            // row 0 descends by exactly what the box's bottom edge descends by.
            border = if (collapsed) {
                BorderFactory.createEmptyBorder()
            } else {
                BorderFactory.createEmptyBorder(style.boxPadBelow, 0, style.gapBelow, style.inset)
            }
        }

        /**
         * The band at the frame's bottom that [ResizeGrip] occupies — the frame's own bottom inset, floored at
         * [GRIP_H] so the grip survives a `gapBelow` of 0.
         *
         * It is the inset rather than an extra row because a component overlapping the child takes the nested editor's
         * clicks and wheel notches. A grip inside the gap below the frame's border overlaps nothing, so nothing has to
         * be disclaimed with a `contains()` override. At the default `gapBelow` of 6 the grip costs no extra pixels.
         */
        private fun gripBand(): Int = maxOf(insets.bottom, GRIP_H)

        override fun getPreferredSize(): Dimension {
            val w = width.takeIf { it > 0 } ?: MIN_NODE_WIDTH
            // **The box's bottom margin while collapsed, and nothing else** — [NestStyle.collapsedFrameHeight]. No row
            // of the *child* survives and none is reserved for one; the margin is there because the box needs somewhere
            // to put its own bottom edge — the row below the call row belongs to the next statement, which may be the
            // next call site, and two sequential boxes would touch. [Nesting.inlayHeight] reports what the platform
            // actually honoured.
            if (collapsed) return Dimension(w, style.collapsedFrameHeight())
            val ins = insets
            return Dimension(w, style.stripHeight() + nesting.child.preferredSize.height + ins.top + gripBand())
        }

        override fun doLayout() {
            if (collapsed) {
                // Nothing is drawn and nothing is hit-testable: the chevron on the host's token box is the only
                // affordance while folded, and it is not this component's.
                nesting.child.setBounds(0, 0, 0, 0)
                strip.setBounds(0, 0, 0, 0)
                grip.setBounds(0, 0, 0, 0)
                return
            }
            val ins = insets
            val w = (width - ins.left - ins.right).coerceAtLeast(0)
            val band = gripBand()
            val bodyY = ins.top + style.stripHeight()
            nesting.child.setBounds(ins.left, bodyY, w, (height - bodyY - band).coerceAtLeast(0))
            // Exactly its own band, with no overlap into the child — which is what makes the leader's last row and
            // the frame's first adjacent rather than overlapping, and what keeps the strip out of the nested
            // editor's mouse without needing a `contains()` override to do it.
            strip.setBounds(ins.left, ins.top, w, style.stripHeight())
            grip.setBounds(ins.left, (height - band).coerceAtLeast(0), w, band)
        }

        fun setCollapsed(next: Boolean) {
            collapsed = next
            nesting.child.isVisible = !next
            strip.isVisible = !next
            grip.isVisible = !next
            applyBorder()
            revalidate()
            repaint()
        }

        fun repaintLeader() = strip.repaint()

        fun restyle() {
            applyBorder()
            revalidate()
            strip.repaint()
        }
    }

    /**
     * **The nested node's bottom edge, as a splitter**: drag it to change the nested editor's height.
     *
     * It sits in the frame's bottom inset, immediately below the drawn border, and never overlaps the child — see
     * [NestedFrame.gripBand] for why that is a rule here and not a coincidence.
     *
     * **Deltas are taken in screen coordinates**, which is the one non-obvious part: the grip moves *with* the edge it
     * is dragging, so a delta measured against this component's own origin is measured against a moving datum and the
     * drag either runs away or stalls depending on which direction it is going.
     */
    private inner class ResizeGrip(private val nesting: Nesting) : JComponent() {

        private var startScreenY = 0

        private var startHeight = 0

        private var hovered = false

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
            toolTipText = "Drag to resize ${nesting.child.spec.title}"
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    startScreenY = e.locationOnScreen.y
                    startHeight = nesting.child.publishedHeight()
                }

                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    nesting.child.setHeightOverride(startHeight + (e.locationOnScreen.y - startScreenY))
                    // Synchronously, not through the coalesced geometry callback: the inlay is what holds the frame's
                    // height, and one `invokeLater` per drag event is one frame of lag on every pixel of the gesture.
                    nesting.updateInlay()
                }
            })
        }

        override fun getPreferredSize(): Dimension = Dimension(MIN_LEADER_WIDTH, GRIP_H)

        /**
         * A short centred bar, faint when idle and solid under the pointer.
         *
         * Always-visible rather than hover-only because a gesture with no mark is a gesture nobody finds; faint because
         * the alternative — a full-width divider — would draw a second horizontal line directly under the frame's own
         * border — a doubled line.
         */
        override fun paintComponent(g: Graphics) {
            if (width <= 0 || height <= 0) return
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (hovered) LeaderArt.leaderBase() else LeaderArt.leaderColor(true)
                g2.stroke = LeaderArt.SOLID
                val cx = width / 2
                val cy = height / 2
                g2.drawLine(cx - GRIP_MARK_W / 2, cy, cx + GRIP_MARK_W / 2, cy)
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * The leader: **the stretch of the outline between the token box above and the nested frame below.**
     *
     * Not a decoration that joins two borders — in `CORNERS`/`BRACE` it *is* the border for the length of its run,
     * which is why the box has no bottom edge and the frame no top one. Its row 0 is the box's bottom-edge level and
     * its last row is adjacent to the frame's first, so all three drawings are one path.
     *
     * **No `contains()` override**: the strip does not overlap the child, so its bounds *are* its band and there is
     * nothing to disclaim. **Add one the moment the strip overlaps anything**: an overlapping strip silently takes
     * every click and wheel notch in the nested editor's top rows.
     *
     * Clicking anywhere on it collapses or re-expands the nesting. That is deliberate rather than a hidden
     * gesture: the strip is the visual join between the call and its expansion, so it is the thing a reader
     * points at when they mean "fold this away".
     */
    private inner class LeaderStrip(private val nesting: Nesting) : JComponent() {

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = nesting.setCollapsed(!nesting.collapsed)
            })
        }

        override fun getPreferredSize(): Dimension = Dimension(MIN_LEADER_WIDTH, style.stripHeight())

        override fun paintComponent(g: Graphics) {
            val (x1, x2) = nesting.leaderEndpoints(this) ?: return
            val w = width
            // A stroke drawn at y == height is clipped out of existence, so the last drawable row is height - 1.
            // This row and the frame's own row 0 are adjacent, which draws as continuous.
            val bottom = (height - 1).coerceAtLeast(0)
            if (w <= 2 || bottom <= 0) return

            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // No collapsed variant: the whole frame is hidden when folded, so the strip is not visible then.
                // What carries the folded state is the token box ([Nesting.applyTokenMark]).
                g2.color = LeaderArt.leaderColor(false)
                g2.stroke = LeaderArt.SOLID
                g2.draw(LeaderArt.leaderPath(style.leader, x1, x2, w, bottom))
                // **The expanded state's affordance, in the one mode that has none on the box.** Drawn after the
                // leader so it can never come out half under a stroke — see [LeaderArt.collapseHintRect].
                //
                // Only this mode, though it would be one line fewer to draw it in all three: the two chevrons
                // already carry an affordance in *both* states, in one rectangle that only changes direction, so
                // the reader's hand learns one target for both actions. A second mark in the strip would give
                // those modes two.
                if (style.chevron == ChevronStyle.BOTTOM_BORDER) {
                    LeaderArt.collapseHintRect(x1, x2, bottom)?.let { LeaderArt.drawCollapseHint(g2, it) }
                }
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * Top-level nodes, stacked. No overlay, no layout by depth — depth is containment here, so the only thing
     * this does that a `BoxLayout` would not is give every root the full width and read its height back rather
     * than caching it.
     */
    private inner class NestContent : JPanel(), Scrollable {

        init {
            layout = null
            isOpaque = true
        }

        fun setRoots(next: List<CodeNodeView>) {
            removeAll()
            next.forEach { add(it) }
            revalidate()
            repaint()
        }

        override fun doLayout() {
            var y = style.gap
            for (node in mutableRoots) {
                val w = (width - 2 * style.inset).coerceAtLeast(MIN_NODE_WIDTH)
                val h = node.preferredSize.height
                node.setBounds(style.inset, y, w, h)
                y += h + style.gap
            }
        }

        override fun getPreferredSize(): Dimension {
            var y = style.gap
            for (node in mutableRoots) y += node.preferredSize.height + style.gap
            // **The bottom slack**, and it belongs here rather than in `doLayout`: it is scrollable *room*, not a
            // gap between nodes, so it has to be in the height the viewport scrolls over and in nothing that positions
            // a component. See [NestStyle.bottomSlack] for why a drag needs it and why no cleverness at the grip can
            // substitute.
            y += style.bottomSlack
            // A width, not a minimum: with `Scrollable.tracksViewportWidth` this is the threshold below which the
            // pane scrolls horizontally instead of squeezing the nodes — and a squeezed host re-wraps its
            // callout and re-measures every nested child with it.
            return Dimension(PREFERRED_NODE_WIDTH + 2 * style.inset, y)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 24

        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width

        override fun getScrollableTracksViewportWidth(): Boolean {
            val vp = parent as? JViewport ?: return false
            return vp.width >= preferredSize.width
        }

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    private companion object {
        const val MIN_NODE_WIDTH = 200
        const val PREFERRED_NODE_WIDTH = 640
        const val MIN_LEADER_WIDTH = 40

        /**
         * The drag band's height — and the floor under `gapBelow`, whose default (6) it matches exactly so the grip
         * costs no pixels at the shipping setting.
         */
        const val GRIP_H = 6

        /** Width of the grip's centred mark. Short, because it is a hint and not a divider. */
        const val GRIP_MARK_W = 22

        val HAND: Cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        /**
         * Above [SpotlightRenderer]'s layer (`SELECTION - 1`), because the token box is what the reader follows
         * from a call into its expansion — it has to survive the active-step glow drawn over the same line.
         */
        val TOKEN_LAYER = HighlighterLayer.SELECTION + 1

        /**
         * How many deferred passes the landing is allowed before it stops trying.
         *
         * One deferred pass per level of nesting; a typical chain is two deep, so three passes is the depth plus
         * one. Bounded rather than "until it settles" because a request that exceeds
         * the scrollable extent legitimately never equals the offset, and an unbounded retry there is a loop.
         */
        const val LANDING_PASSES = 3
    }
}

/**
 * One parent→child link, by index into the node list given to [CodeNestView.setTree].
 *
 * [parentLine] lives here rather than on [CodeNodeSpec] because a parent can have several children — one method
 * calling three handlers from three consecutive lines — and a single call-site line on the *parent* cannot say
 * which line goes with which child.
 *
 * Indices into the node list rather than frame ids; the plan keys frames by `id` (two frames can share one file,
 * so `file` is not an identity) and [TreePlanView] maps one to the other.
 */
class CodeTreeEdge(
    val parent: Int,
    val child: Int,
    /** 1-based line **in the parent's file** where the call into [child] sits, or null if unknown. */
    val parentLine: Int? = null,
    /**
     * The **invoking token** on [parentLine] — the text [CodeNestView] boxes and runs its leader from.
     *
     * A line is enough to place a child *under* a call and not enough to point at the call *inside* the line:
     * `_pricing.Handle(order, verdict)` and `_audit.Handle(order, priced)` can share a line, and the tree marks
     * one of them. Null means "the whole trimmed line".
     */
    val callToken: String? = null,
    /**
     * Which match of [callToken] on [parentLine], 1-based — the schema's `callOccurrence`. Only needed when
     * the same token appears twice on the call line; past the last match the leader falls back to the whole
     * trimmed line, exactly as a null token does.
     */
    val callOccurrence: Int = 1,
)

/** Whether a child was actually nested, and if not, why — so degrading to top-level is visible, not silent. */
enum class EmbedState {
    /** The platform accepted the component and returned an inlay. */
    EMBEDDED,

    /** The edge named no call-site line, or one outside the host document. Nothing to nest under. */
    NO_CALL_LINE,

    /** `addComponent` refused or threw. The impl-package risk, arriving as a fact rather than as a footnote. */
    FAILED,
}

/** How much to believe the leader's origin: whether the boxed token was really found on a really visible line. */
enum class LeaderState {
    /** The invoking token was found on the call line and boxed exactly. */
    ON_TOKEN,

    /** No token text was given, so the whole (trimmed) call line is the origin. */
    WHOLE_LINE,

    /** The call line is empty or the token is not on it. No box, no diagonals. */
    MISSING,

    /**
     * The frame is not inside the host editor's content component, so the token's x cannot be converted into
     * the strip's coordinates. Recorded rather than guessed: the leader is simply not drawn.
     */
    UNCONVERTIBLE,
}

/**
 * How the nesting is drawn from the invoking token down to the nested frame — **and which border edges the box and
 * the frame therefore draw**.
 *
 * **These three are the user setting**: one choice, three values, and no independent chrome knob beside it — the
 * chrome is a rule ([CodeNestView.chromeFor]), so no combination can leave the leader stopping short.
 *
 * **There is no "none".** Once the leader carries border segments, "no leader" leaves a bottomless box above a
 * topless frame with nothing joining them. A genuine minimal mode would have to *restore* both borders and draw
 * nothing between them — a fourth mode, not an off-switch.
 *
 * The shapes live in [LeaderArt.leaderPath], which is the only place they exist.
 */
enum class NestLeader {
    /**
     * **The diagonals.** Two straight runs, box's bottom-left corner → frame's top-left and bottom-right →
     * top-right. Box: left, top, right. Frame: left, right, bottom. One continuous path.
     */
    CORNERS,

    /**
     * **Two stepped halves**: down along each of the box's side walls, a rounded turn outward, a horizontal run on
     * the strip's last row, and the frame's own rounded top corner down into its side wall. No central stem and no
     * spine across the width. Same border suppression as [CORNERS]. Read the horizontal run as the box's bottom edge
     * *redirected outwards* rather than as a missing edge.
     *
     * **The default**: unlike the diagonals, the brace does not cut across the header text.
     */
    BRACE,

    /**
     * **A reference, not a join.** Box: all four sides. Frame: all four sides. The box's bottom border continues
     * past its own bottom-right corner — collinear, so it reads as the edge extended and not as a new line leaving a
     * corner — runs right to an x between the box's right edge and the frame's, turns down, and ends in an arrowhead
     * landing on the frame's top border.
     *
     * The head touches the frame (a floating arrowhead reads as misdrawn), and the outline is one component here as in
     * the other two modes. What makes it a reference is that **both outlines stay closed** — the suppressed-edge count,
     * not a gap and not the component count. The turn-down x is fixed at the midpoint ([LeaderArt.ARROW_TURN]).
     */
    ARROW,
}

/**
 * **Which shape carries the collapse/expand gesture on the invoking token box.**
 *
 * A collapsed nesting leaves no row of its own, only the invoking token's box, so the gesture lives on the box. Every
 * chevron is a double stroke; what varies is the axis it points along and whether it is a chevron at all.
 *
 * The two chevron modes float past a gap to the right of the box, so they never cover the token's own glyphs.
 * [BOTTOM_BORDER] is the exception, on purpose.
 *
 * Geometry lives in [LeaderArt.chevronRect] and [LeaderArt.chevronWidth] — one place, because the same call positions
 * the painter and hit-tests the click ([CodeNestView.Nesting] installs that on the *host* editor, a custom highlighter
 * renderer having nothing to attach a listener to).
 */
enum class ChevronStyle {
    /** No affordance on the box at all; the leader strip still toggles the nesting. */
    NONE,

    /**
     * `»` beside the box, pointing **right to expand and left to collapse**.
     *
     * A horizontal chevron reads as *"there is more of this along the line"*, which is where the callee's code actually
     * is (in another file, reached by following the call).
     */
    HORIZONTAL,

    /**
     * The same double stroke turned a quarter: `⌄` to expand, `⌃` to collapse, in the same rectangle.
     *
     * Reads as *"there is more of this below"*, which is where the expansion physically opens. Same target as
     * [HORIZONTAL] so the reader's hand does not have to learn a second place — only the glyph turns.
     */
    VERTICAL,

    /**
     * **No chevron: the box's own bottom border, thickened to `LeaderArt.BOX_BOTTOM_THICK` and clickable, with a tiny
     * down triangle set into it.** Clicking it expands the nesting.
     *
     * **This mode is deliberately asymmetric.** The bottom border exists only while collapsed — the box has no bottom
     * edge once the nesting is open, because that edge *is* the leader — so there is nothing to thicken and nothing to
     * click in the expanded state. Collapsing back falls to the leader strip above the child's header
     * ([CodeNestView.LeaderStrip]), and [LeaderArt.collapseHintRect] marks it with an upward accent triangle under the
     * invoking token. A hint and not a target: the strip's whole band stays the click, so there is still exactly one
     * place to press and nothing new to hit-test.
     *
     * The thickening is on the **bottom** edge, not the right wall, so it never covers the invoking token's own
     * glyphs; [NestStyle.boxPadBelow] keeps it clear of the row's descenders.
     *
     * **The default.** Expanding is a click *on the box* and collapsing is a click *below* it, one row apart, and both
     * states say where.
     */
    BOTTOM_BORDER,
}

/**
 * **What a step change does to the nestings it is not narrating** — `NestStyle.stepFold`.
 *
 * The schema has nothing to say here and deliberately so: no [com.audioexplainer.Step] field states which frames
 * are open, because openness is a property of the reader's screen and not of the walkthrough. The author says
 * which frames a step *touches*; which of the others are worth their height is the surface's judgement, and this
 * is that judgement as one setting.
 *
 * `collapseAllExcept` is the whole mechanism in every mode, so the difference between these three is only *when*
 * it runs — never what it does. An ancestor of a touched node is never folded (it would hide the narration), and
 * stepping back into a folded node re-expands it innermost-first, so no value here can strand the reader in code
 * they cannot see.
 */
enum class StepFold {
    /** Never fold on a step change: the tree only ever moves when the reader moves it. */
    NEVER,

    /** Fold only while the mounted scene is over `PlanLoader.LINE_BUDGET`. */
    OVER_BUDGET,

    /**
     * Fold on every step change. The default: a nesting the step does not touch is spending height on code the
     * narration is not about, whatever the scene's size.
     */
    ALWAYS,
}

/**
 * The nest-level style knobs — everything [CodeNodeStyle] is not.
 *
 * There is no indent knob: the nested node's own gutter already reads as nesting, and past ~24 px the frame's left
 * edge passes the invoking token and the left diagonal runs backwards.
 *
 * **Data, so that a re-style can ask whether anything actually changed.** [CodeNestView.restyleNest] re-applies
 * every nesting's frame inset and re-arms the landing, which moves the reader's own scroll — right when the leader
 * mode really did change, wrong when Settings ▸ Apply merely repainted a callout colour. See [TreePlanView.restyle].
 */
data class NestStyle(
    /** The leader mode — a user setting (*Code tree ▸ Nesting leader*); `BRACE` by default. */
    val leader: NestLeader = NestLeader.BRACE,
    /**
     * Which shape carries the box's collapse/expand gesture — a user setting (*Settings ▸ Tools ▸ Audio Explainer ▸
     * Code tree ▸ Collapse control*), resolved from the persisted name by [TreeStyle].
     */
    val chevron: ChevronStyle = ChevronStyle.BOTTOM_BORDER,
    /** **Which nestings a step change folds away** — see [StepFold]. */
    val stepFold: StepFold = StepFold.ALWAYS,
    /** **What of an open frame's `keep` a step folds for itself** — see [StepCondense] and `TreePlanView.condense`. */
    val condense: StepCondense = StepCondense.LINKS,
    /** Space between the nested frame and the host's next line of code. Also the drag grip's band — `GRIP_H`. */
    val gapBelow: Int = 6,
    /**
     * Grow a **top-level** node that hosts a nesting to its content instead of capping it.
     *
     * A *nested* host lands on its excerpt like any other nested node, with the embedded frames counted at full
     * height into both its target and its cap ([CodeNodeView.excerptTargetHeight] and `effectiveCap`). Capping the
     * root would give it an inner scrollbar inside the outer pane — two scrollbars for one node — so by default it
     * grows. Off, the root gets the usual ceiling plus whatever is embedded.
     */
    val growHosts: Boolean = true,
    val gap: Int = 12,
    val inset: Int = 14,
    /**
     * **Scrollable room below the last root node.**
     *
     * Without it, dragging a nested node's bottom edge up while the pane is scrolled to its maximum moves the node's
     * *top* down instead: shrinking the content by `d` **forces** the offset down by `d`, so the dragged edge's position
     * on screen is unchanged by construction, and no fix at the grip can help. Keeping this much room past the last node
     * means the clamp only bites once a single gesture has consumed it. It also lets the last node's own bottom edge be
     * read somewhere other than the very bottom of the pane.
     *
     * Too small and a long drag stalls again; too large and the pane scrolls into emptiness, which reads as content
     * missing.
     */
    val bottomSlack: Int = 240,
    /**
     * **Rows the token box owns below the call row** — one number behind two needs.
     *
     * Without it the box's bottom border overlaps descenders and commas, and sequential collapsed boxes touch. A text
     * row is `lineHeight` tall and its descenders reach the bottom of it, and the shared-origin identity ([LeaderArt])
     * puts the box's bottom edge on the row *after* the call row — which while collapsed is the **next statement's
     * own row**, possibly the next call site.
     *
     * The room is taken twice over, and only one of the two halves is the padding:
     *
     * - **expanded** — the frame's *top inset* ([NestedFrame.applyBorder]), so the strip's row 0 moves down with the
     *   box's bottom edge and the identity above stays literally true;
     * - **collapsed** — [collapsedFrameHeight], which is `2 ×` this: the pad under the box, then the same again as
     *   clear space before the next row of code. That second half is what stops sequential boxes touching, and it is
     *   why the collapsed frame is not 0 px.
     *
     * `0` puts the box flush to the row (boxes touch); `5` is [LeaderArt.BOX_BOTTOM_THICK], the value that puts the
     * *whole* clickable band clear of the text rather than only its lower half.
     */
    val boxPadBelow: Int = 3,
    /**
     * **Whether a landing node's editor gets a page of scrollable room past its last line** — [bottomSlack]'s argument,
     * one surface in. See [CodeNodeView.setLandingSlack] for the arithmetic; this is only the switch, and it is a switch
     * rather than a number for the reason given there.
     *
     * **A knob because it has a visible cost.** `false`: collapsing a nesting inside a node that landed on a member
     * drops the node to line 1. `true` fixes that, at the price of a node that can be wheeled down into a page of blank
     * space.
     */
    val nodeSlack: Boolean = true,
) {
    /**
     * **The collapsed frame's height** — `2 × boxPadBelow`.
     *
     * One derivation shared by the frame that publishes it and the check that asserts it. Nothing of the child
     * survives a collapse and no row is reserved for one; what survives is the box's own bottom margin. Two of these
     * rows, because the box needs one below it and the next line of code needs one below that — see [boxPadBelow].
     */
    fun collapsedFrameHeight(): Int = boxPadBelow * 2

    /**
     * The strip's height — **the vertical distance from the box's bottom-edge level to the frame's top**, and
     * therefore a property of the leader mode rather than a spacing default.
     *
     * All three leaders originate on the box's bottom row and have to arrive at the frame's, so this number *is* a
     * diagonal's slope, a brace's drop and how far `ARROW` descends before its head — so it belongs to the mode
     * ([LeaderArt.defaultStripHeight]).
     */
    fun stripHeight(): Int = LeaderArt.defaultStripHeight(leader)
}
