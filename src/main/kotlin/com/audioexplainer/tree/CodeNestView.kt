package com.audioexplainer.tree

import com.audioexplainer.Endpoint
import com.audioexplainer.SpotlightRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
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
import java.awt.geom.GeneralPath
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * **Arch C — a child node drawn *inside* its parent, at the call site.**
 *
 * Phase 1a‴ of `TreeOfCode.md`, and — since the 1a⁗ round — **the only layout.** It was built as the sibling of a
 * flat `CodeTreeView` that put a child *below* its parent with a routed connector to it; both took the same
 * `setTree(specs, edges)` and built the same [CodeNodeView]s over the same real documents, and the only difference
 * was where a child went. The bench put the two one click apart, the verdict was "nested is absolute winner", and
 * the cleanup pass deleted the loser. A child goes *in* its parent, directly under the line that calls it, with
 * two diagonals from the invoking token down to the nested frame's top corners.
 *
 * Sequential, unrelated nodes are unaffected: a root here is a top-level node in a scroll pane exactly as
 * before, so a plan explaining three files with no caller→callee relation between them looks the same in both.
 * Nesting applies to edges, and only to edges that name a call-site line.
 *
 * **What this buys, measured rather than argued:**
 *
 * - **No overlay.** Constraint 7's whole apparatus — a [javax.swing.JLayeredPane] sibling above the content,
 *   non-opaque, `contains() == false`, banded repaints — exists because a connector between two nodes is drawn
 *   in the *container's* space, over editors that repaint themselves. A leader painted inside the nested
 *   component's own top margin ([LeaderStrip]) is drawn in the space it belongs to and repaints with its own
 *   parent, so none of that machinery is needed here. The class has no overlay at all.
 * - **No child-side anchor to choose.** 1a″ carried forward the question of whether a connector should point at
 *   the child's first *kept* line (which is its class header) or at the member the node is about. Containment
 *   answers it by construction: the child is physically at the call site and the leader points at the frame.
 * - **Depth is containment**, so indentation stops being a number to tune — the nested node's own gutter is the
 *   boundary, and the reader spent no width on an indent.
 *
 * **What it costs, also measured, and the cost grew in the cleanup pass:** [EditorEmbeddedComponentManager] lives
 * in `com.intellij.openapi.editor.impl` and is not a stability promise. Verified present and on the compile
 * classpath in RD-261.26222.60, with 6/6 embeddings, no exceptions and clean disposal across 25 rebuilds. The
 * fallback *was* built — the flat tree with the parent split into before-call / after-call nodes, needing no impl
 * API — and the 1a⁗ round scrapped it to reduce scope. **So there is no built fallback now**: if this API moves,
 * the answer is to rebuild that layout from git history, which is a week rather than an afternoon. See the top of
 * `TreeOfCode.md`.
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

    /** One per edge that was actually nested. Carries the whole of what 1a‴ has to measure. */
    val nestings: List<Nesting> get() = mutableNestings

    private val content = NestContent()

    private val scroll = JBScrollPane(content).apply {
        border = null
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
    }

    var activeIndex: Int = -1
        private set

    var onNodeFocused: ((CodeNodeView) -> Unit)? = null

    /**
     * Coalesced re-render on a theme switch: one callback for the whole tree, not one per node.
     *
     * Constraint 11 — a scheme change has to re-*render*, not merely re-measure, because every colour is derived
     * per render. N nodes each asking for a re-render would cost N renders of the same step.
     */
    var onRerender: (() -> Unit)? = null

    private var rerenderRequests = 0
    private var rerenderDispatches = 0
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
     * The signature outlived the flat tree it was shared with, which is why it is shaped for a general graph and
     * not for containment: the 1a‴ bench swapped one layout for the other on the same resolved fixture, the only
     * fair way to answer "which reads better". Two rules follow from containment rather than from choice, and both
     * are why the schema must not promise what this can render:
     *
     * - **An edge with no call-site line cannot be nested at all** — there is no line to put the child under.
     *   Such a child becomes a top-level node and says so ([EmbedState.NO_CALL_LINE]), because degrading to the
     *   flat shape is legible and dropping the node is not.
     * - **A child may be nested in only one parent.** A frame is in one place; a callee called from two sites
     *   is exactly the case the flat tree handles better, and Phase 2's schema decision 1 should know it.
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
        activeIndex = -1
        revalidate()
        repaint()
        scheduleLanding(0)
    }

    /**
     * **Everything about a node that depends on where it sits in the tree**, applied in one pass.
     *
     * Four roles, and none of them is a user preference (§1a⁵ 2, §1a⁵ 4, constraint 16, constraint 8):
     *
     *  - **chrome** — [chromeFor]'s header rule;
     *  - **grow** — a host grows to content rather than being capped, because the nested frame is *inside* its
     *    editor and a 600 px cap on a parent holding a 500 px child leaves two lines of the parent visible behind
     *    one scrollbar. The cap belongs on leaves, where it was measured;
     *  - **which border edges the node draws** — the leader owns the top edge in the joining modes;
     *  - **the landing** — a nested child is sized to its excerpt and scrolled to it; the root is exempt.
     */
    private fun applyRoles() {
        val hosts = mutableNestings.filter { it.state == EmbedState.EMBEDDED }.map { it.parent }.toSet()
        for (node in mutableNodes) {
            node.restyle(
                nodeStyle.with(
                    chrome = chromeFor(node),
                    grow = nodeStyle.grow || (node in hosts && style.growHosts),
                ),
            )
        }
        for (n in mutableNestings) n.applyLeaderRole()
    }

    /**
     * **§1a⁵ 2's header rule**: a caption when the node is top-level *or* in a different file from its parent, and
     * none when a nested child is in the **same** file as its caller.
     *
     * A rule and not a preference, so it does not reach the settings dialog. The cross-file half is the part the
     * previous pass left open and the reader decided outright: a cross-file child keeps its caption because that
     * caption is the only place its file name appears, while a same-file child's path would just repeat its
     * caller's — which is exactly the case `Invokers.cs` exists in the fixture to produce.
     */
    fun chromeFor(node: CodeNodeView): NodeChrome {
        val nesting = mutableNestings.firstOrNull { it.child === node && it.state == EmbedState.EMBEDDED }
            ?: return NodeChrome.HEADER_STRIP
        return if (nesting.parent.spec.file == node.spec.file) NodeChrome.PLAIN else NodeChrome.HEADER_STRIP
    }

    /**
     * Assert every nested child's landing, once the layout has settled enough for a scroll to stick.
     *
     * **Bounded retry rather than one deferred pass, and constraint 17 is why**: `scrollVertically` clamps to
     * `content − viewport`, and inside a nesting chain the viewport is one deferred pass per level behind — so the
     * first attempt at depth 2 can clamp to 0, which looks exactly like the landing not being implemented. The
     * constraint's own lesson is "either walk the chain synchronously or state the settle time"; a scroll cannot be
     * walked synchronously because it depends on a Swing layout, so this states the settle time as a bounded number
     * of passes and stops.
     */
    private fun scheduleLanding(attempt: Int) {
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            var unsettled = false
            for (n in mutableNestings) {
                if (n.state != EmbedState.EMBEDDED) continue
                n.child.landOnExcerpt()
                val wanted = n.child.lastLandingRequest
                if (wanted > 0 && n.child.scrollOffset() != wanted) unsettled = true
            }
            if (unsettled && attempt + 1 < LANDING_PASSES) scheduleLanding(attempt + 1)
        }
    }

    private fun wire(node: CodeNodeView) {
        node.onFocusGained = { onNodeFocused?.invoke(it) }
        node.onRevealRequest = { revealNode(it) }
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
        }
    }

    /**
     * Re-measure a nesting's inlay, then its host's, then *its* host's — **synchronously, innermost first**.
     *
     * Constraint 17's fix. The order is load-bearing rather than tidy: an outer inlay's height comes from its
     * frame's preferred size, which comes from the host node's `contentHeight()`, which is `offsetToXY` over a
     * document that only counts the inner inlay *after* the inner one has been updated. Outermost-first would
     * measure the outer frame against the inner frame's old height and be wrong by exactly the delta — which is
     * the 555 px the 1a‴ report showed.
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

    /** `SubInvoker←MidInvoker←MainInvoker` — the chain a re-measure walks, for a report that has to name it. */
    fun nestingChainReport(): String {
        if (mutableNestings.isEmpty()) return "<no nestings>"
        return mutableNestings.joinToString("; ") { n ->
            val chain = mutableListOf(n.child.spec.title)
            var host: CodeNodeView? = n.parent
            val guard = HashSet<CodeNodeView>()
            while (host != null && guard.add(host)) {
                chain += host.spec.title
                host = mutableNestings.firstOrNull { it.child === host }?.parent
            }
            "depth ${chain.size - 1}: ${chain.joinToString(" ← ")}"
        }
    }

    private fun releaseNodes() {
        // Inlays first, deepest node last: this is an editor inside an inlay inside an editor, and releasing the
        // host editor while its inlay still holds a live child editor is the disposal order Phase 4 is about.
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
        activeIndex = -1
    }

    // ── Attention, reveal, collapse ──

    fun setActiveNode(index: Int) {
        activeIndex = index
        for ((i, node) in mutableNodes.withIndex()) node.setActive(i == index)
        repaint()
    }

    /**
     * Bring a node into view. **Two scroll axes, not one** — a nested node lives inside its host's editor, so
     * revealing it means scrolling that editor to the nesting's line *and* scrolling the outer pane to the host.
     */
    fun revealNode(node: CodeNodeView) {
        val nesting = mutableNestings.firstOrNull { it.child === node && it.state == EmbedState.EMBEDDED }
        if (nesting == null) {
            if (node.parent === content) node.scrollNodeIntoView()
            return
        }
        nesting.callLine?.let { nesting.parent.reveal(it) }
        revealNode(nesting.parent)
    }

    /** True when [node]'s whole slot is inside the outer viewport. Only meaningful for a root. */
    fun viewportShows(node: CodeNodeView): Boolean {
        if (node.parent !== content) return false
        val view = scroll.viewport?.viewRect ?: return false
        return view.contains(Rectangle(node.x, node.y, node.width, node.height))
    }

    fun viewportRect(): Rectangle = scroll.viewport?.viewRect ?: Rectangle()

    /**
     * Collapse or expand every nesting — the behaviour the reader asked for by name: a nested viewer that can be
     * folded away so the lines *after* the call site come back into view.
     *
     * Note what it is not: [NodeElision]/`CustomFoldRegion` folding, which constraint 6 measured as refusing to
     * expand. This is the nested component swapping itself for a one-row stub and the inlay re-measuring, so it
     * re-expands by construction.
     */
    fun collapseAll(collapsed: Boolean) {
        mutableNestings.forEach { it.setCollapsed(collapsed) }
    }

    private fun requestRerender() {
        rerenderRequests++
        if (rerenderPending || disposed) return
        rerenderPending = true
        ApplicationManager.getApplication().invokeLater {
            rerenderPending = false
            if (disposed) return@invokeLater
            rerenderDispatches++
            onRerender?.invoke()
            mutableNestings.forEach { it.restyleLeader() }
        }
    }

    /** `requests=3 dispatches=1` — N nodes noticing one theme switch, coalesced into one re-render. */
    fun rerenderReport(): String = "requests=$rerenderRequests dispatches=$rerenderDispatches"

    // ── Styling ──

    fun restyleNodes(next: CodeNodeStyle) {
        nodeStyle = next
        applyRoles()
        mutableNestings.forEach { it.repaintLeader() }
        scheduleLanding(0)
    }

    /**
     * The leader mode and the strip's geometry — Phase 4's one user setting, and the whole reason a restyle is not
     * just a repaint.
     *
     * **Constraint 16 makes this destructive rather than cosmetic**: the mode decides which border edges the box and
     * the frame draw, so switching `CORNERS → ARROW` has to re-apply the *node's* border and the token box's
     * closedness as well as redraw the leader. A restyle that only repainted the strip would leave a topless frame
     * under an arrow, or a ghost top edge under a diagonal — which is why G1c′ asserts "no stale border segment".
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
     * Everything 1a‴ has to answer about a single nesting is readable from here, because the alternative is a
     * report that trusts the source: whether the platform accepted the component ([state]), whether the frame
     * really landed inside the host editor's content component ([leaderState] — the leader's coordinate
     * conversion depends on it), whether the host's text was pushed down past the frame ([textFollowsFrame]),
     * and whether a collapse actually shrank the inlay ([frameHeight]).
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

        private val frame = NestedFrame(this)

        var collapsed: Boolean = false
            private set

        /** The host's content height before the child was embedded, so the pushdown can be stated as a delta. */
        var hostHeightBeforeEmbed: Int = -1
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
            hostHeightBeforeEmbed = parent.contentHeight()

            val offset = ReadAction.compute<Int, RuntimeException> { doc.getLineEndOffset(line0) }
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
                // An impl-package API on a viewer editor: if this ever throws, the fallback is the flat tree,
                // and the bench has to say so rather than showing an empty parent.
                log.warn("addComponent failed for ${child.spec.file.name} in ${parent.spec.file.name}", t)
                null
            }
            state = if (inlay != null) EmbedState.EMBEDDED else EmbedState.FAILED
            if (state == EmbedState.EMBEDDED) {
                applyLeaderRole()
                frame.addComponentListener(object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent) = scheduleInlayUpdate()
                })
            }
        }

        /**
         * Everything the **leader mode** decides about the two components either side of it, applied together.
         *
         * Constraint 16's "whatever draws them has to be told the mode", as one call: the token box's closedness,
         * the child frame's top edge, the collapse affordance's home, and §1a⁵ 4's landing. They are one call
         * because they are one design — the round that shipped them as independent knobs is the round that produced
         * the constraint.
         */
        internal fun applyLeaderRole() {
            if (state != EmbedState.EMBEDDED) return
            child.setTopEdgeDrawn(LeaderArt.drawsFrameTop(style.leader))
            child.setLandsOnExcerpt(true)
            applyTokenMark()
            installToggle()
        }

        /**
         * **One** collapse affordance, in the nested node's own header band — verdict 4.
         *
         * 1a‴ had two: a triangle painted on the leader strip and a `▸` on the collapsed stub. The strip's is gone
         * (the strip still *accepts* the click, which is a target rather than an affordance) and the stub's is a
         * dashed border now, so this control is the only thing on screen claiming to fold the expansion.
         *
         * It lives in the child rather than in the frame because the child owns its chrome, and where a header band
         * is depends entirely on which chrome that is — [CodeNodeView.setCornerControl] resolves that, including
         * the [NodeChrome.PLAIN] case where there is no band and the control goes just inside the top corner. Which
         * chrome a nested child has is now a rule ([CodeNestView.chromeFor]), so a same-file child is *always* the
         * bandless case rather than only when a knob said so.
         */
        private fun installToggle() {
            if (!style.toggleInHeader) {
                child.setCornerControl(null)
                return
            }
            child.setCornerControl(ToggleControl(this))
        }

        internal fun detach() {
            removeTokenMark()
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
         * The rule itself lives in [LeaderArt.locateCallToken], shared with the split fallback: it is a §4.4
         * rule 2 locator, and the fallback is supposed to be the *same picture*, which it cannot be if the two
         * surfaces disagree about which characters the call is.
         */
        private fun locateToken(line0: Int): IntRange? = ReadAction.compute<IntRange?, RuntimeException> {
            LeaderArt.locateCallToken(parent.editor.document, line0, edge.callToken)
        }

        /**
         * Box the invoking token.
         *
         * **The box is a [LeaderArt.TokenBoxRenderer], not `EffectType.BOXED`** — constraint 18. The effect draws
         * on the glyph bounds, which measured as zero clearance to the token's own first and last character; a
         * renderer can inflate the rectangle, and the padding it uses is the measured one. The colour is
         * [LeaderArt.leaderBase], the leader's own, because the box and the line out of it are one object to the
         * reader's eye and were two different colours until 1a⁗.
         *
         * The box used to be one of four marks — a tinted background and box-plus-background were the others — and
         * then one of two, with an off-switch. **It is neither now**: the 1a⁗ round rejected the tint at every
         * padding ("background still bad, scrap those options — leave the box as only one"), and constraint 16 made
         * the box *structural*, because every leader mode starts at its bottom corners. So there is no `TokenMark`
         * enum and no knob: a leaderless mode would leave a bottomless box above a topless frame, and a boxless one
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
                        LeaderArt.leaderBase(), LeaderArt.closesTokenBox(style.leader),
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
            updateInlay()
        }

        /**
         * Re-measure this inlay **and every inlay above it in the nesting chain** — constraint 17.
         *
         * The measured defect of 1a‴: collapse-then-expand on the three-deep chain restored the outer host to
         * 1186 px instead of 1741, short by exactly the inner frame's height. One level was exact; two drifted,
         * because an inner re-measure reached the *outer* inlay only through the host node's geometry callback,
         * which coalesces onto an `invokeLater` — one deferred pass per level of nesting. Invisible to the reader,
         * because the deferred pass does run; visible to any synchronous read, which is how it was found.
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

        /** True when the child is showing the single collapse control — what a check on verdict 4 asks. */
        fun hasToggle(): Boolean = child.cornerControlComponent() != null

        /** The control's rectangle in [target]'s coordinates, so a hit-test check can aim at it. */
        fun toggleBoundsIn(target: JComponent): Rectangle? {
            val c = child.cornerControlComponent() ?: return null
            val holder = c.parent ?: return null
            if (!SwingUtilities.isDescendingFrom(c, target) && holder !== target) return null
            return SwingUtilities.convertRectangle(holder, c.bounds, target)
        }

        /**
         * **The seam, as two rectangles** — §1a⁵ 1's open question, closed at zero.
         *
         * The old answer to "does the leader reach the frame" was a *number*: the child's drawn-line offset, which
         * the strip overlapped by. That question stopped existing when `TITLED_BORDER` was scrapped — every
         * surviving chrome draws its line at its bounds edge — so what is left to check is adjacency: the leader's
         * last drawable row is `stripHeight - 1` and the frame's side walls start on the child's own row 0, so the
         * two are **adjacent rows** and the outline is continuous with **zero overlap**. Measured in
         * `spikes/tree1a5/`: one 8-connected component from the box's top edge to the frame's bottom.
         *
         * Zero, not "small": it is what removes the strip from the child's rows entirely, and with it constraint 7's
         * `contains()` cost. The rule that produced that cost stays on the books, because it has bitten twice.
         */
        fun seamRows(): Int = frame.seamRows()

        /** The strip's rectangle and the child's, inside the frame — what [seamRows] is the gap between. */
        fun stripBounds(): Rectangle = frame.stripBounds()

        fun childBoundsInFrame(): Rectangle = Rectangle(child.bounds)

        /**
         * What the mode *wants* each edge to be, beside what the node is *actually* drawing.
         *
         * Two numbers rather than one on purpose: they are the same only if [applyLeaderRole] ran, and the failure
         * G1c′ names — "switching modes leaves a stale border segment behind" — is exactly the case where they
         * disagree while the leader has already been repainted.
         */
        fun edgeReport(): String =
            "leader=${style.leader} boxClosed=${LeaderArt.closesTokenBox(style.leader)} " +
                "frameTop wanted=${LeaderArt.drawsFrameTop(style.leader)} drawn=${child.topEdgeDrawn}"

        /** True when the node's border agrees with the leader mode — the stale-segment check, as a boolean. */
        fun edgesAgree(): Boolean = child.topEdgeDrawn == LeaderArt.drawsFrameTop(style.leader)

        // ── What the report reads ──

        /** The token's x range in the host editor's **content component** coordinates, or null. */
        internal fun tokenXRange(): Pair<Int, Int>? {
            val range = tokenOffsets ?: return null
            if (parent.editor.isDisposed) return null
            return ReadAction.compute<Pair<Int, Int>?, RuntimeException> {
                val a = parent.editor.offsetToXY(range.first)
                val b = parent.editor.offsetToXY(range.last + 1)
                if (b.x <= a.x) null else a.x to b.x
            }
        }

        /** `312..488px` — the token's extent in the host editor, for a report that wants to say where it is. */
        fun tokenXRangeReport(): String = tokenXRange()?.let { "${it.first}..${it.second}px" } ?: "<not located>"

        /**
         * **The row the token box's bottom edge occupies**, in the host editor's content coordinates — the one
         * definition [LeaderArt.BOX_BOTTOM_IS_STRIP_ROW_0] is about, exposed so a check can sample the pixels there
         * instead of re-deriving it.
         *
         * Re-derivation is exactly what went wrong in 1a‴, one level down: the same row computed twice by two
         * owners is how a leader ends up anchored to a line's centre while a box is anchored to its bottom.
         */
        fun boxBottomRow(): Int? {
            val line = callLine ?: return null
            if (parent.editor.isDisposed) return null
            return ReadAction.compute<Int?, RuntimeException> {
                val doc = parent.editor.document
                val line0 = line - 1
                if (line0 < 0 || line0 >= doc.lineCount) return@compute null
                parent.editor.offsetToXY(doc.getLineStartOffset(line0)).y + parent.editor.lineHeight
            }
        }

        /**
         * The leader's endpoints in [target]'s coordinates: the token box's bottom-left and bottom-right.
         *
         * The conversion is the whole point. The x comes from `offsetToXY`, which is the editor content
         * component's space; the leader is painted in a strip nested two components deeper and inset by the
         * nesting indent. Measured outside the IDE first (`spikes/tree1a3/`), where skipping it was wrong by
         * exactly the indent.
         */
        internal fun leaderEndpoints(target: JComponent): Pair<Int, Int>? {
            val (x1, x2) = tokenXRange() ?: return null
            val content = parent.editor.contentComponent
            if (!SwingUtilities.isDescendingFrom(target, content)) {
                leaderState = LeaderState.UNCONVERTIBLE
                return null
            }
            val p1 = SwingUtilities.convertPoint(content, Point(x1, 0), target) ?: return null
            val p2 = SwingUtilities.convertPoint(content, Point(x2, 0), target) ?: return null
            return p1.x to p2.x
        }

        /** True when the frame really is inside the host editor's content component — assumed by nothing. */
        fun frameIsInHostContent(): Boolean =
            !parent.editor.isDisposed && SwingUtilities.isDescendingFrom(frame, parent.editor.contentComponent)

        fun frameBounds(): Rectangle = Rectangle(frame.bounds)

        /**
         * The frame's rectangle in [target]'s coordinates, or null if they share no ancestor.
         *
         * The frame's own parent is inside the editor's inlay plumbing — a component this class does not name and
         * should not depend on — so a caller that wants to point at the frame (to ask what a click there would
         * hit, say) needs the conversion done from inside.
         */
        fun frameBoundsIn(target: JComponent): Rectangle? {
            val holder = frame.parent ?: return null
            if (!SwingUtilities.isDescendingFrom(frame, target) && holder !== target) return null
            return SwingUtilities.convertRectangle(holder, frame.bounds, target)
        }

        /** The leader strip's height inside the frame — the band a collapse click is expected to land in. */
        fun stripHeight(): Int = style.stripHeight

        fun frameHeight(): Int = frame.height

        fun framePreferredHeight(): Int = frame.preferredSize.height

        fun inlayIsValid(): Boolean = inlay?.isValid == true

        fun inlayHeight(): Int = inlay?.heightInPixels ?: -1

        fun inlayOffset(): Int = inlay?.offset ?: -1

        /**
         * **The load-bearing question of the whole approach**: does the host's own text get pushed down past
         * the nested frame?
         *
         * If it does not, the frame is drawn *over* the lines after the call site and the host's height is
         * short by the frame's height — which no amount of leader polish would rescue. Stated as the line after
         * the call site landing below the frame's bottom, both in the host editor's content coordinates.
         */
        fun textFollowsFrame(): Boolean {
            val line = callLine ?: return false
            if (state != EmbedState.EMBEDDED || !frameIsInHostContent()) return false
            val next = parent.anchorOfLine(line + 1) ?: return false
            val frameInParent = SwingUtilities.convertRectangle(frame.parent ?: return false, frame.bounds, parent)
            return next.point.y >= frameInParent.y + frameInParent.height - LINE_SLACK
        }

        fun pushdownReport(): String {
            val line = callLine ?: return "no call line"
            val frameInParent = frame.parent?.let { SwingUtilities.convertRectangle(it, frame.bounds, parent) }
            val next = parent.anchorOfLine(line + 1)
            return "frame(in host)=$frameInParent nextLine(${line + 1}).y=${next?.point?.y ?: "-"} " +
                "hostContent=${parent.contentHeight()}px (was $hostHeightBeforeEmbed before embedding)"
        }

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

        private val stub = StubRow(nesting)

        private var collapsed = false

        init {
            // A null layout, kept although the overlap it was for is gone. It was needed because the leader's tips
            // had to reach a `TITLED_BORDER`'s drawn line 7 px *inside* the child's bounds, which no `BorderLayout`
            // region can express; that chrome is scrapped and the strip now ends exactly where the child begins
            // ([seamRows] == 0). What still needs hand-positioning is the collapsed stub swapping in for the child
            // in the same slot, and the z-order below — which a `BorderLayout` would also not give.
            layout = null
            isOpaque = false
            applyBorder()
            add(nesting.child)
            add(stub)
            add(strip)
            // Index 0 paints last. This one line is why the extended tips are visible at all: with the strip
            // behind, the child's opaque background erases them completely (measured, with the control, in
            // `spikes/tree1a4/`).
            setComponentZOrder(strip, 0)
            stub.isVisible = false
        }

        private fun applyBorder() {
            border = BorderFactory.createEmptyBorder(0, 0, style.gapBelow, style.inset)
        }

        /** The gap between the strip's last row and the child's first. Zero means the outline is continuous. */
        fun seamRows(): Int {
            val body = if (collapsed) stub else nesting.child
            return body.y - (strip.y + strip.height)
        }

        fun stripBounds(): Rectangle = Rectangle(strip.bounds)

        override fun getPreferredSize(): Dimension {
            val ins = insets
            val body = if (collapsed) stub else nesting.child
            return Dimension(
                (width.takeIf { it > 0 } ?: MIN_NODE_WIDTH),
                style.stripHeight + body.preferredSize.height + ins.top + ins.bottom,
            )
        }

        override fun doLayout() {
            val ins = insets
            val w = (width - ins.left - ins.right).coerceAtLeast(0)
            val body = if (collapsed) stub else nesting.child
            val bodyY = ins.top + style.stripHeight
            body.setBounds(ins.left, bodyY, w, (height - bodyY - ins.bottom).coerceAtLeast(0))
            // Exactly its own band, with no overlap into the child — which is what makes the leader's last row and
            // the frame's first adjacent rather than overlapping, and what keeps the strip out of the nested
            // editor's mouse without needing a `contains()` override to do it.
            strip.setBounds(ins.left, ins.top, w, style.stripHeight)
        }

        fun setCollapsed(next: Boolean) {
            collapsed = next
            nesting.child.isVisible = !next
            stub.isVisible = next
            strip.repaint()
            revalidate()
            repaint()
        }

        fun repaintLeader() = strip.repaint()

        fun restyle() {
            applyBorder()
            stub.restyle()
            revalidate()
            strip.repaint()
        }
    }

    /**
     * The collapsed nesting: one row, **with a border** so it still reads as a frame (verdict 4).
     *
     * 1a‴ shipped a bare `JBLabel` here and the reader's note was exact — a collapsed expansion looked like a line
     * of grey text in the middle of the caller's code rather than like something folded. Dashed, because dashed is
     * what "there is more of this" already means everywhere else in the surface.
     *
     * No triangle: the affordance is the node's header control, and there is exactly one of it now.
     */
    private inner class StubRow(private val nesting: Nesting) : JPanel() {

        private val label = JBLabel(text())

        init {
            layout = null
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Expand ${nesting.child.spec.title}"
            label.foreground = JBColor.GRAY
            add(label)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = nesting.setCollapsed(false)
            })
        }

        private fun text(): String = "${nesting.child.spec.file.name}  ·  ${nesting.child.spec.title}"

        fun restyle() {
            label.text = text()
            repaint()
        }

        override fun getPreferredSize(): Dimension =
            Dimension(MIN_NODE_WIDTH, label.preferredSize.height + 2 * STUB_PAD)

        override fun doLayout() {
            label.setBounds(STUB_PAD + 4, STUB_PAD, (width - 2 * STUB_PAD - 8).coerceAtLeast(0),
                (height - 2 * STUB_PAD).coerceAtLeast(0))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = leaderBase()
                g2.stroke = DASHED
                g2.drawRoundRect(0, 0, width - 1, height - 1, STUB_ARC, STUB_ARC)
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * The leader: **the stretch of the outline between the token box above and the nested frame below.**
     *
     * Not a decoration that joins two borders — in `CORNERS`/`BRACE` it *is* the border for the length of its run,
     * which is why the box has no bottom edge and the frame no top one (constraint 16). Its row 0 is the box's
     * bottom-edge level and its last row is adjacent to the frame's first, so all three drawings are one path.
     *
     * **No `contains()` override, and that is a measured change rather than an omission.** The strip used to
     * overlap the child by the chrome's drawn-line offset and therefore had to disclaim the rows it covered, or it
     * took every click and wheel notch in the nested editor's top rows — 1a″'s trap one layer down, measured both
     * ways in `spikes/tree1a4/`. With `TITLED_BORDER` scrapped the overlap is zero, so the strip's bounds *are* its
     * band and there is nothing left to disclaim. **Re-introduce the override the moment the strip overlaps
     * anything again**: the rule has bitten twice and its cost is silence.
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

        override fun getPreferredSize(): Dimension = Dimension(MIN_LEADER_WIDTH, style.stripHeight)

        override fun paintComponent(g: Graphics) {
            val (x1, x2) = nesting.leaderEndpoints(this) ?: return
            val w = width
            // A stroke drawn at y == height is clipped out of existence, so the last drawable row is height - 1.
            // Measured twice, and it is the reason the seam is a question at all: this row and the frame's own row
            // 0 are adjacent, which the pre-flight probe measures as continuous.
            val bottom = (height - 1).coerceAtLeast(0)
            if (w <= 2 || bottom <= 0) return

            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = leaderColor(nesting.collapsed)
                g2.stroke = if (nesting.collapsed) DASHED else SOLID
                g2.draw(leaderPath(style.leader, x1, x2, w, bottom, style.arrowTurn))
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * Top-level nodes, stacked. No overlay, no layout by depth — depth is containment here, so the only thing
     * this does that a `BoxLayout` would not is give every root the full width and read its height back rather
     * than caching it (constraint 9).
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
            // A width, not a minimum: with `Scrollable.tracksViewportWidth` this is the threshold below which the
            // pane scrolls horizontally instead of squeezing the nodes — and a squeezed host re-wraps its
            // callout and re-measures every nested child with it (constraint 9).
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

    /**
     * The single collapse control: a small triangle that points down when the expansion is open and right when it
     * is folded, exactly as a tree's disclosure does.
     *
     * A real component rather than something painted into the strip, because "one affordance" is a hit-testing question
     * and the probe answered it as one: three components deep inside an embedded component, a control is reachable
     * — but only when it is z-lifted above the editor's own component, which [CodeNodeView.setCornerControl] does.
     * Behind it, the control is drawn and dead, which is the failure mode that reads as "the frame is broken".
     */
    private inner class ToggleControl(private val nesting: Nesting) : JComponent() {

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Collapse or expand ${nesting.child.spec.title}"
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = nesting.setCollapsed(!nesting.collapsed)
            })
        }

        override fun getPreferredSize(): Dimension = Dimension(TOGGLE_W, TOGGLE_H)

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = LeaderArt.leaderColor(nesting.collapsed)
                val w = width.toFloat()
                val h = height.toFloat()
                val t = GeneralPath()
                if (nesting.collapsed) {
                    t.moveTo(w * 0.32f, h * 0.18f)
                    t.lineTo(w * 0.72f, h * 0.5f)
                    t.lineTo(w * 0.32f, h * 0.82f)
                } else {
                    t.moveTo(w * 0.18f, h * 0.34f)
                    t.lineTo(w * 0.82f, h * 0.34f)
                    t.lineTo(w * 0.5f, h * 0.72f)
                }
                t.closePath()
                g2.fill(t)
            } finally {
                g2.dispose()
            }
        }
    }

    private companion object {
        const val MIN_NODE_WIDTH = 200
        const val PREFERRED_NODE_WIDTH = 640
        const val MIN_LEADER_WIDTH = 40
        const val TOGGLE_W = 13
        const val TOGGLE_H = 11
        const val STUB_PAD = 3
        const val STUB_ARC = 5

        /** How far a "next line is below the frame" comparison may miss by. One line of rounding, no more. */
        const val LINE_SLACK = 4

        /**
         * Above [SpotlightRenderer]'s layer (`SELECTION - 1`), because the token box is what the reader follows
         * from a call into its expansion — it has to survive the active-step glow drawn over the same line.
         */
        val TOKEN_LAYER = HighlighterLayer.SELECTION + 1

        // Shared with the token box, because the reader sees one object (constraint 18).
        val SOLID = LeaderArt.SOLID
        val DASHED = LeaderArt.DASHED

        fun leaderBase(): Color = LeaderArt.leaderBase()

        fun leaderColor(collapsed: Boolean): Color = LeaderArt.leaderColor(collapsed)

        fun leaderPath(leader: NestLeader, x1: Int, x2: Int, w: Int, bottom: Int, arrowTurn: Float): GeneralPath =
            LeaderArt.leaderPath(leader, x1, x2, w, bottom, arrowTurn)

        /**
         * How many deferred passes the landing is allowed before it stops trying.
         *
         * Constraint 17's shape: one deferred pass per level of nesting, and the fixture's chain is two deep, so
         * three passes is the depth plus one. Bounded rather than "until it settles" because a request that exceeds
         * the scrollable extent legitimately never equals the offset, and an unbounded retry there is a loop.
         */
        const val LANDING_PASSES = 3
    }
}

/**
 * One parent→child link, by index into the node list given to [CodeNestView.setTree].
 *
 * [parentLine] lives here rather than on [CodeNodeSpec] because a parent can have several children: the
 * fixture's wide case is one `Dispatch` calling three handlers from three consecutive lines, and a single
 * call-site line on the *parent* cannot say which line goes with which child. Phase 2's schema should carry
 * the same shape — `parent` plus the call-site line, on the edge or on the child, never on the parent. (A
 * `CodeNodeSpec.linkFrom` field carried it on the parent until the cleanup pass, for the 1a′ single-node bench.)
 *
 * Indices rather than ids only because Phase 1's fixture is a list; the schema keys frames by `id`
 * (decision 2 — two frames can share one file, so `file` is not an identity).
 *
 * Named for the flat `CodeTreeView` this was declared in, which the cleanup pass deleted. Kept as `CodeTreeEdge`
 * rather than renamed because Phase 2 rewrites the whole vocabulary anyway, and a rename now would churn every
 * call site for a name with one release to live.
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
     * `_pricing.Handle(order, verdict)` and `_audit.Handle(order, priced)` can share a line, and Arch C marks
     * one of them. Added by 1a‴, and it is a **Phase 2 schema finding**: the edge needs the call site's
     * column extent, not only its row (schema decision 1). Null means "the whole trimmed line".
     */
    val callToken: String? = null,
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
 * the frame therefore draw** (constraint 16).
 *
 * **These three are the user setting** (Phase 4): one choice, three values, and no independent chrome knob beside
 * it. That pairing used to be dangerous — chrome and leader as two free choices would ship the combination where
 * the leader stops short — and it stopped being dangerous when the chrome became a rule (§1a⁵ 2) and the drawn-line
 * offset ceased to exist.
 *
 * **There is no fourth value.** `NONE` was removed as incoherent, not as unwanted: once the leader carries border
 * segments, "no leader" leaves a bottomless box above a topless frame with nothing joining them. A genuine minimal
 * mode would have to *restore* both borders and draw nothing between them — a fourth mode, not an off-switch.
 * `FLARE` never got built: dismissed on the strength of the pre-flight PNGs before the sandbox ever opened.
 *
 * All three are specified to the pixel in `TreeOfCode.md` §1a⁵ 1 and measured in `spikes/tree1a5/OutlineProbe.java`;
 * the shapes live in [LeaderArt.leaderPath], which is the only place they exist.
 */
enum class NestLeader {
    /**
     * **The diagonals.** Two straight runs, box's bottom-left corner → frame's top-left and bottom-right →
     * top-right. Box: left, top, right. Frame: left, right, bottom. One continuous path, and the default.
     */
    CORNERS,

    /**
     * **Two L-shaped halves**, orthogonal rather than curved: outward horizontally from each of the box's bottom
     * corners, at that corner's level, to the frame's side wall — then down into the frame's top corner. One right
     * angle per half, at the *outer* end. Same border suppression as [CORNERS].
     *
     * The built version had a central stem and a spine across the width and was sent back; the probe keeps that
     * shape inlined as the control its emptiness test must catch. Read the horizontal run as the box's bottom edge
     * *redirected outwards* rather than as a missing edge.
     *
     * Its advantage over [CORNERS], and the reason both ship: *"unlike diagonals braces do not overlap with header
     * text"* — the run sits in the strip's own top row, where a diagonal cuts down across the whole band.
     */
    BRACE,

    /**
     * **A reference, not a join.** Box: all four sides. Frame: all four sides. The box's bottom border continues
     * past its own bottom-right corner — collinear, so it reads as the edge extended and not as a new line leaving a
     * corner — runs right to an x between the box's right edge and the frame's, turns down, and ends in an
     * arrowhead pointing at the frame's top border **without touching it**.
     *
     * The probe measures that as two components where the other modes are one, which is the whole difference between
     * pointing at something and being attached to it. The turn-down x is the only free parameter in any of the three
     * shapes — see [NestStyle.arrowTurn].
     */
    ARROW,
}

/**
 * The nest-level style knobs — everything [CodeNodeStyle] is not.
 *
 * An `indentPx` knob was here and is gone: verdict 8 settled it at **0** (the nested node's own gutter already
 * reads as nesting, so no width is spent on an indent), and its non-zero values only reproduced a defect the
 * reader had already rejected — past ~24 px the frame's left edge passes the invoking token and the left diagonal
 * runs backwards. A knob whose other settings are known-bad is not a setting.
 */
class NestStyle(
    /**
     * Height of the leader strip — **the vertical distance from the box's bottom-edge level to the frame's top**,
     * and therefore not a spacing default.
     *
     * All three leaders originate on the box's bottom row and have to arrive at the frame's, so this number *is* a
     * diagonal's slope, a brace's drop and how far `ARROW` descends before its head. Every one of the three shapes
     * changes character with it, which makes it something to judge by eye at a stated width (constraint 9) rather
     * than a gap to pick.
     *
     * **22 px, judged on `spikes/tree1a5/`'s sweep at 640 px wide**: 14 is cramped and steepens the funnel to the
     * point where the diagonals read as a single wedge, 30 is airy but spends a code row and a half of the host's
     * height on a join. On the bench as a knob, because the sweep was PNGs and the round is real code.
     */
    val stripHeight: Int = 22,
    val leader: NestLeader = NestLeader.CORNERS,
    /**
     * Where [NestLeader.ARROW] turns down, as a fraction of the span from the box's right edge to the frame's.
     *
     * The only free parameter in any of the three shapes, so it is the only one with a number to defend: 0.5, the
     * midpoint, judged on the probe's 30 / 50 / 70 % sweep at 640 px. Ignored by the other two modes.
     */
    val arrowTurn: Float = LeaderArt.DEFAULT_ARROW_TURN,
    /**
     * Put the single collapse control in the nested node's header band (1a‴ verdict 4).
     *
     * A knob because "no visible affordance at all" is a real alternative: the leader strip accepts the click
     * whether or not a triangle is drawn on it, and 1a‴'s complaint was about there being *two* marks rather than
     * about there being one.
     */
    val toggleInHeader: Boolean = true,
    /** Space between the nested frame and the host's next line of code. */
    val gapBelow: Int = 6,
    /**
     * Grow a node that hosts a nesting to its content instead of capping it (constraint 8).
     *
     * A knob rather than a constant because the interaction is worth seeing: with it off, a host capped at 600 px
     * holds a 500 px child and the reader gets two lines of the caller behind a scrollbar.
     */
    val growHosts: Boolean = true,
    val gap: Int = 12,
    val inset: Int = 14,
) {
    fun with(
        stripHeight: Int = this.stripHeight,
        leader: NestLeader = this.leader,
        arrowTurn: Float = this.arrowTurn,
        toggleInHeader: Boolean = this.toggleInHeader,
        gapBelow: Int = this.gapBelow,
        growHosts: Boolean = this.growHosts,
        gap: Int = this.gap,
        inset: Int = this.inset,
    ) = NestStyle(stripHeight, leader, arrowTurn, toggleInHeader, gapBelow, growHosts, gap, inset)
}
