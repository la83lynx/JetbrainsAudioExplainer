package com.audioexplainer.spike

import com.audioexplainer.Segment
import com.audioexplainer.tree.CodeNestView
import com.audioexplainer.tree.CodeNodeSpec
import com.audioexplainer.tree.CodeNodeStyle
import com.audioexplainer.tree.CodeNodeView
import com.audioexplainer.tree.CodeTreeEdge
import com.audioexplainer.tree.EmbedState
import com.audioexplainer.tree.LeaderArt
import com.audioexplainer.tree.LeaderState
import com.audioexplainer.tree.NestLeader
import com.audioexplainer.tree.NestStyle
import com.audioexplainer.tree.NodeChrome
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Rectangle
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * **Phase 1a‴/1a⁗ spike vehicle** for `TreeOfCode.md` — the bench that judges **Arch C**, a child node drawn
 * inside its parent at the call site via [CodeNestView] and `EditorEmbeddedComponentManager`.
 *
 * It used to rebuild three layouts one click apart — nested, the flat 1a″ tree, and a split fallback that needed
 * no impl-package API — because "which reads better" is not answerable across two launches. That comparison is
 * over: the 1a‴ round's verdict was "nested is absolute winner" and the 1a⁗ round scrapped the other two to
 * reduce scope, so the cleanup pass deleted them and their `CodeTreeView` with them. **The cost is stated where
 * it belongs, at the top of `TreeOfCode.md`: the split layout *was* the built fallback for the impl-package risk,
 * and there is no longer one.**
 *
 * The eye questions are buttons (chrome, leader shape, token mark, the collapse affordance, host sizing);
 * everything invisible on screen goes in a numbered `report*.txt`. Four of 1a‴'s questions were answered before
 * this file existed, by a `javac` probe — the coordinate conversion, the clipped last pixel row, the two leader
 * shapes and the collapse resize are pure Swing, and each carried a control that must fail. Its 1a⁗ successor
 * `spikes/tree1a4/NestPolishProbe.java` is still on disk, because §1a⁵'s brace and arrow geometry is exactly
 * what it measures in two seconds instead of a 4–7 minute sandbox launch.
 *
 * THROWAWAY with the rest of `com.audioexplainer.spike`. Output: `<project>/scratchpad/spike-nestview/`.
 */
class SpikeNestedNodeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        NestedNodeRun(project).open()
    }
}

internal class NestedNodeRun(private val project: Project) {

    private val log = Logger.getInstance(NestedNodeRun::class.java)

    private val out: Path = run {
        System.getProperty("audioexplainer.spike.out")?.takeIf { it.isNotBlank() }?.let { return@run Paths.get(it) }
        Paths.get(project.basePath ?: System.getProperty("java.io.tmpdir"), "scratchpad", "spike-nestview")
    }

    private val editorsAtStart = EditorFactory.getInstance().allEditors.size

    fun open() {
        // VFS traversal is disk I/O; the platform asserts against slow operations on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val wanted = NodeViewRun.CASES.flatMap { it.nodes }.map { it.fileName }.toHashSet()
            val found = findFixtureFilesByName(project, wanted)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                try {
                    openBench(found, wanted)
                } catch (t: Throwable) {
                    log.warn("nested-node bench failed to open", t)
                    notify("Spike 1a‴ failed to open", "${t.javaClass.simpleName}: ${t.message}", NotificationType.ERROR)
                }
            }
        }
    }

    private fun openBench(found: Map<String, VirtualFile>, wanted: Set<String>) {
        val missing = wanted.filterNot { found.containsKey(it) }
        if (missing.isNotEmpty()) {
            notify(
                "Spike 1a‴ skipped",
                "Fixture files not found under the project root: ${missing.joinToString(", ")}.<br/>" +
                    "Open <b>fixtures/TreeSpike/TreeSpike.sln</b> as the sandbox project — a node is a view of a " +
                    "real document in a loaded solution, and nothing here means anything without one.",
                NotificationType.WARNING,
            )
            return
        }
        val bench = NestBench(project, found, out, editorsAtStart) { notify(it.first, it.second, it.third) }
        val file = SpikeTreeVirtualFile(
            "Nested node bench (1a‴)",
            componentSupplier = { bench },
            onDispose = { bench.releaseAll() },
        )
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Audio Explainer")
            .createNotification(title, content, type)
            .notify(project)
    }
}

/**
 * The bench: a toolbar of the Arch C alternatives, a status line of the measurements, and the nested view.
 *
 * The view is rebuilt from scratch on every case or layout-affecting switch and the old one disposed. That is
 * not economy, it is the disposal check: an editor inside an inlay inside an editor is 1a‴'s stated main risk,
 * and rebuilding repeatedly is exactly the open-close-repeat that B6 used to find leaks. Measured across the
 * 1a⁗ round: `getAllEditors()` **6 → 5 across 25 rebuilds** — it went down.
 */
internal class NestBench(
    private val project: Project,
    private val files: Map<String, VirtualFile>,
    private val out: Path,
    private val editorsAtStart: Int,
    private val notify: (Triple<String, String, NotificationType>) -> Unit,
) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(NestBench::class.java)

    private val status = JBLabel(" ")

    private var nestStyle = NestStyle()
    private var nodeStyle = CodeNodeStyle()

    private var nest: CodeNestView? = null
    private var centre: JComponent? = null

    /** Held because the strip's height follows the leader mode: cycling the mode restates this button's label. */
    private var stripButton: JButton? = null

    private var caseIndex = 0
    private var built: Built = Built(emptyList(), emptyList())
    private val segments = mutableListOf<Segment>()
    private val steps = mutableListOf<StepRef>()
    private var stepIndex = 0
    private var focused: CodeNodeView? = null
    private var reportCount = 0
    private var rebuilds = 0

    init {
        add(toolbar(), BorderLayout.NORTH)
        add(status.apply { border = BorderFactory.createEmptyBorder(4, 8, 4, 8) }, BorderLayout.SOUTH)
        buildCase()
    }

    // ────────────────────────────── toolbar ──────────────────────────────

    /**
     * Three rows, and the middle one has changed shape twice for the same reason.
     *
     * 1a‴ left `chrome` off because 1a′ had settled it, and the round came back with "it's a pity the chrome switch
     * was not included and I could not test it" — nesting had changed what the question meant. 1a⁗ put it back, and
     * §1a⁵ **removed it again**, this time correctly: the chrome is now a *rule* (header iff top-level or a
     * different file from the parent), so there is nothing for the eye to choose and a knob would only let the round
     * photograph a combination that cannot ship. The report asserts the rule instead.
     *
     * What replaced it are the two numbers §1a⁵ 1 says to judge by eye at a stated width: the strip's height, which
     * *is* every leader's slope, and `ARROW`'s turn-down x, the only free parameter in any of the three shapes.
     * `token:` is gone for a different reason — constraint 16 made the box structural, so there is no off-switch to
     * offer.
     */
    private fun toolbar(): JComponent {
        val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        rows.add(
            row(
                button("◂ step") { step(-1) },
                button("step ▸") { step(+1) },
                button("case: chain") { cycleCase(it) },
                button("collapse all") { toggleCollapseAll(it) },
                button("write report") { writeReport() },
            ),
        )
        rows.add(
            row(
                JBLabel("the one outline: "),
                button("leader: corners") { cycleLeader(it) },
                button(stripLabel()) { cycleStripHeight(it) }.also { stripButton = it },
                button("arrow turn: 50%") { cycleArrowTurn(it) },
                button("toggle: header") { toggleToggle(it) },
            ),
        )
        rows.add(
            row(
                button(if (nestStyle.growHosts) "hosts: grow" else "hosts: cap ${nodeStyle.maxHeight}") {
                    toggleGrowHosts(it)
                },
                button("scroll host ▾") { scrollHost() },
                button("reveal deepest") { revealDeepest() },
            ),
        )
        rows.border = BorderFactory.createEmptyBorder(4, 4, 2, 4)
        return rows
    }

    private fun row(vararg comps: JComponent): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { comps.forEach { add(it) } }

    private fun button(text: String, onClick: (JButton) -> Unit): JButton {
        val b = JButton(text)
        b.addActionListener { onClick(b) }
        return b
    }

    // ────────────────────────────── case build ──────────────────────────────

    internal class Built(val specs: List<CodeNodeSpec>, val edges: List<CodeTreeEdge>)

    /**
     * Resolve one fixture case into specs plus edges, exactly as the 1a″ bench does — plus the **invoking
     * token**, which is new in 1a‴.
     *
     * The token is the child's own `callSiteAnchor`: the text of the call, which is what Arch C boxes. A call
     * *line* is not enough to point inside a line, and the fixture's wide case proves it — three handlers are
     * called from three consecutive lines, and nothing stops two of them sharing one.
     */
    private fun buildCase() {
        val case = NodeViewRun.CASES[caseIndex]
        // One read action around every document access and every anchor scan (constraint 12).
        built = ReadAction.compute<Built, RuntimeException> {
            val fdm = FileDocumentManager.getInstance()
            val texts = HashMap<String, CharSequence>()
            val specs = mutableListOf<CodeNodeSpec>()
            val plans = mutableListOf<SpikeNodePlan>()
            for (plan in case.nodes) {
                val file = files[plan.fileName] ?: continue
                val doc = fdm.getDocument(file) ?: continue
                val text = texts.getOrPut(plan.fileName) { doc.charsSequence }
                val keep = plan.anchors.mapNotNull { (anchor, extra) ->
                    lineOfAnchor(text, anchor)?.let { it..(it + extra) }
                }
                if (keep.isEmpty()) {
                    log.warn("no anchor resolved in ${plan.fileName}; node ${plan.title} skipped")
                    continue
                }
                specs += CodeNodeSpec(file, plan.title, plan.depth, keep)
                plans += plan
            }
            val edges = mutableListOf<CodeTreeEdge>()
            for (i in specs.indices) {
                val depth = specs[i].depth
                if (depth == 0) continue
                val parent = (i - 1 downTo 0).firstOrNull { specs[it].depth == depth - 1 } ?: continue
                val token = plans[i].callSiteAnchor
                val line = token?.let { a -> texts[plans[parent].fileName]?.let { lineOfAnchor(it, a) } }
                edges += CodeTreeEdge(parent, i, line, token)
            }
            Built(specs, edges)
        }
        rebuild()
    }

    /**
     * Build (or rebuild) the nested view from [built], disposing whatever was there.
     *
     * Deliberately destructive: one live view at a time means the editor count is a number that can be checked
     * rather than a budget nobody tracks.
     */
    private fun rebuild() {
        rebuilds++
        centre?.let { remove(it) }
        nest?.dispose()
        nest = null

        val view: JComponent = CodeNestView(project, nestStyle, nodeStyle).also { v ->
            nest = v
            v.onNodeFocused = { focused = it; refreshStatus() }
            v.onRerender = { applyStep(enteringNode = true) }
            v.setTree(built.specs, built.edges)
        }
        centre = view
        add(view, BorderLayout.CENTER)

        segments.clear()
        nodes().forEach { segments += syntheticSegment(it.spec) }
        steps.clear()
        for ((i, seg) in segments.withIndex()) for (h in seg.highlights.indices) steps += StepRef(i, h)
        stepIndex = 0
        focused = null
        applyStep(enteringNode = true)
        revalidate()
        repaint()
        // Nothing is laid out until after this returns, so a status line taken now would report zeroes and mean
        // only "not measured yet" — the same trap the 1a″ bench documented for connectors.
        ApplicationManager.getApplication().invokeLater { refreshStatus() }
    }

    // ────────────────────────────── accessors ──────────────────────────────

    private fun nodes(): List<CodeNodeView> = nest?.nodes ?: emptyList()

    private fun setActiveNode(i: Int) {
        nest?.setActiveNode(i)
    }

    private fun revealNode(node: CodeNodeView) {
        nest?.revealNode(node)
    }

    private fun rerenderReport(): String = nest?.rerenderReport() ?: "-"

    fun releaseAll() {
        nest?.dispose()
        // Late enough that the tab's own teardown has run — the count is the whole point of the check.
        val after = EditorFactory.getInstance().allEditors.size
        log.info("Arch C bench closed: getAllEditors() $editorsAtStart → $after after $rebuilds view build(s)")
        if (after > editorsAtStart) {
            notify(
                Triple(
                    "Spike 1a‴: node editors leaked",
                    "${after - editorsAtStart} editor(s) still alive after the bench closed, over $rebuilds " +
                        "view build(s) — an editor inside an inlay inside an editor is Arch C's stated main risk.",
                    NotificationType.WARNING,
                ),
            )
        }
    }

    // ────────────────────────────── stepping ──────────────────────────────

    private fun step(delta: Int) {
        if (steps.isEmpty()) return
        val previous = steps[stepIndex].nodeIndex
        stepIndex = (stepIndex + delta + steps.size) % steps.size
        applyStep(enteringNode = steps[stepIndex].nodeIndex != previous)
    }

    /** Mirrors `EditorHighlighter`'s two entry points, so both renderer paths are exercised as they ship. */
    private fun applyStep(enteringNode: Boolean) {
        val ref = steps.getOrNull(stepIndex)
        if (ref == null) {
            refreshStatus()
            return
        }
        setActiveNode(ref.nodeIndex)
        val all = nodes()
        for ((i, node) in all.withIndex()) if (i != ref.nodeIndex) node.spotlight.clear()
        val node = all.getOrNull(ref.nodeIndex) ?: return
        val seg = segments.getOrNull(ref.nodeIndex) ?: return
        if (enteringNode) {
            node.spotlight.spotlightSegment(seg.start, seg.end)
        } else {
            node.spotlight.setSegmentBounds(seg.start, seg.end)
        }
        node.spotlight.spotlightStep(seg, ref.highlightIndex)
        revealNode(node)
        refreshStatus()
    }

    // ────────────────────────────── alternatives ──────────────────────────────

    private fun cycleCase(b: JButton) {
        caseIndex = (caseIndex + 1) % NodeViewRun.CASES.size
        b.text = "case: ${NodeViewRun.CASES[caseIndex].name}"
        buildCase()
    }

    /**
     * **The setting**, and the only one of the three §1a⁵ knobs that ships: one choice, three values.
     *
     * Switching it is also the fastest way to see constraint 16 rather than read about it — the box's bottom edge and
     * the frame's top edge appear and disappear with the mode, which is what "one outline" means and what a restyle
     * that only repainted the leader would leave behind as a ghost.
     */
    private fun cycleLeader(b: JButton) {
        val values = NestLeader.entries
        val next = values[(values.indexOf(nestStyle.leader) + 1) % values.size]
        restyleNest(nestStyle.with(leader = next))
        b.text = "leader: ${next.name.lowercase()}"
        // The strip's height follows the mode now, so the other button's label is stale the moment this one changes.
        stripButton?.text = stripLabel()
    }

    /**
     * The strip's height *is* every leader's slope, so it is judged on real code and not only on the probe's PNGs.
     *
     * **`auto` is a value now, and it is the first one** — the G1c′ round split the answer by mode (14 for the two
     * joining shapes, 22 for `ARROW`), so the shipping number comes from the leader and the fixed ones are the sweep.
     * The button says which is in force either way, because "auto" alone would hide the thing being judged.
     */
    private fun cycleStripHeight(b: JButton) {
        val values = listOf<Int?>(null, 14, 22, 30)
        val i = values.indexOfFirst { it == nestStyle.stripHeightOverride }
        restyleNest(nestStyle.with(stripHeightOverride = values[(if (i < 0) 0 else i + 1) % values.size]))
        b.text = stripLabel()
    }

    private fun stripLabel(): String = nestStyle.stripHeightOverride
        ?.let { "strip: ${it}px" } ?: "strip: auto (${nestStyle.stripHeight()}px)"

    /** `ARROW`'s one free parameter. Visible only in `ARROW`, which the button says by staying available anyway. */
    private fun cycleArrowTurn(b: JButton) {
        val values = floatArrayOf(0.30f, 0.50f, 0.70f)
        val i = values.indexOfFirst { kotlin.math.abs(it - nestStyle.arrowTurn) < 0.01f }
        val next = values[(if (i < 0) 0 else i + 1) % values.size]
        restyleNest(nestStyle.with(arrowTurn = next))
        b.text = "arrow turn: ${Math.round(next * 100)}%"
    }

    /** One affordance or none: the strip takes the click either way, so this asks whether a mark is needed. */
    private fun toggleToggle(b: JButton) {
        restyleNest(nestStyle.with(toggleInHeader = !nestStyle.toggleInHeader))
        b.text = if (nestStyle.toggleInHeader) "toggle: header" else "toggle: none"
    }

    private fun restyleNodes(next: CodeNodeStyle) {
        nodeStyle = next
        nest?.restyleNodes(next)
        refreshStatus()
    }

    /**
     * Cap the **root** host instead of growing it — the only thing this reaches since the G1c′ round narrowed it.
     *
     * The comparison it now offers is the honest version of the one it always claimed: a top-level host at
     * `600 + embedded` against the whole caller. Nested hosts are sized to their excerpt either way.
     */
    private fun toggleGrowHosts(b: JButton) {
        restyleNest(nestStyle.with(growHosts = !nestStyle.growHosts))
        b.text = if (nestStyle.growHosts) "hosts: grow" else "hosts: cap ${nodeStyle.maxHeight}"
    }

    private fun toggleCollapseAll(b: JButton) {
        val collapsing = nest?.nestings?.none { it.collapsed } ?: return
        nest?.collapseAll(collapsing)
        b.text = if (collapsing) "expand all" else "collapse all"
        refreshStatus()
    }

    private fun restyleNest(next: NestStyle) {
        nestStyle = next
        nest?.restyleNest(next)
        refreshStatus()
    }

    /**
     * Scroll a **host** inside itself, with a tall component embedded in it.
     *
     * The question is not whether the editor scrolls — it is whether the embedded component travels with the
     * text it is anchored to, since it is a Swing child positioned by the inlay and not painted content.
     */
    private fun scrollHost() {
        val host = nest?.nestings?.firstOrNull()?.parent ?: nodes().firstOrNull { it.scrolls() } ?: nodes().firstOrNull() ?: return
        val sm = host.editor.scrollingModel
        val step = host.editor.lineHeight * 5
        val next = if (sm.verticalScrollOffset + step > host.unfoldedContentHeight()) 0 else sm.verticalScrollOffset + step
        // Animation off, so the offset is readable immediately — B3b's retraction was partly an async scroll.
        sm.disableAnimation()
        try {
            sm.scrollVertically(next)
        } finally {
            sm.enableAnimation()
        }
        refreshStatus()
    }

    private fun revealDeepest() {
        nodes().lastOrNull()?.let { revealNode(it) }
        refreshStatus()
    }

    // ────────────────────────────── status + report ──────────────────────────────

    private fun refreshStatus() {
        val ref = steps.getOrNull(stepIndex)
        status.text = buildString {
            append("step ${if (steps.isEmpty()) 0 else stepIndex + 1}/${steps.size}")
            append("  ·  active: ").append(ref?.let { nodes().getOrNull(it.nodeIndex)?.spec?.title } ?: "-")
            append("  ·  focus: ").append(focused?.spec?.title ?: "<none>")
            append("  ·  ").append(nestStyle.leader.name.lowercase()).append(" strip ")
                .append(nestStyle.stripHeight()).append("px")
                .append(if (nestStyle.stripHeightOverride == null) " (by mode)" else " (forced)")
            val ns = nest?.nestings
            if (ns != null) {
                append("  ·  nested: ").append(ns.count { it.state == EmbedState.EMBEDDED }).append("/").append(ns.size)
                append(" [").append(ns.joinToString("; ") { "${it.child.spec.title}@${it.callLine}:${it.state}/${it.leaderState}${if (it.collapsed) " ⏷" else ""}" }).append("]")
                // The seam replaces the overlap the status line used to carry, and it is the same discipline: the
                // number constraint 16 is about, where it can be watched not moving as the leader is cycled.
                append("  ·  seam: ").append(ns.firstOrNull()?.seamRows() ?: 0).append("px")
                append("  ·  roots: ").append(nest?.roots?.size ?: 0)
            } else {
                append("  ·  slots: ").append(nodes().joinToString(", ") { "${it.x}+${it.width}x${it.height}" })
            }
            append("  ·  ").append(rerenderReport())
        }
    }

    private fun writeReport() {
        val name = if (reportCount == 0) "report.txt" else "report-$reportCount.txt"
        reportCount++
        val rep = Report(out.resolve(name))
        rep.title("Phase 1a‴ — Arch C nested nodes (CodeNestView) bench")
        rep.line("IDE build : ${ApplicationInfo.getInstance().build.asString()} (${ApplicationInfo.getInstance().fullVersion})")
        rep.line("project   : ${project.name}  base=${project.basePath}")
        rep.line("case      : ${NodeViewRun.CASES[caseIndex].name}  (${nodes().size} node(s))")
        rep.line("rebuilds  : $rebuilds")
        rep.line(
            "nest style: strip=${nestStyle.stripHeight()}px (override=${nestStyle.stripHeightOverride ?: "auto, by mode"}) " +
                "leader=${nestStyle.leader} " +
                "arrowTurn=${Math.round(nestStyle.arrowTurn * 100)}% toggleInHeader=${nestStyle.toggleInHeader} " +
                "gapBelow=${nestStyle.gapBelow} growHosts=${nestStyle.growHosts}",
        )
        rep.line(
            "node style: path=${nodeStyle.path} elision=${nodeStyle.elision} " +
                "dim=${nodeStyle.deemphasis} maxHeight=${nodeStyle.maxHeight} grow=${nodeStyle.grow} " +
                "(chrome is per node, by §1a⁵ 2's rule — see the header-rule section)",
        )
        rep.line("tokenPad  : ${LeaderArt.TOKEN_PAD}px (measured in spikes/tree1a4; EffectType.BOXED is 0)")
        rep.line("step      : ${if (steps.isEmpty()) 0 else stepIndex + 1}/${steps.size}")
        rep.line("focus     : ${focused?.spec?.title ?: "<none>"}")
        rep.blank()

        reportEmbedding(rep)
        reportPushdown(rep)
        reportHostHeight(rep)
        reportOutline(rep)
        reportHeaderRule(rep)
        reportCollapse(rep)
        reportMultiLevelCollapse(rep)
        reportAffordance(rep)
        reportLanding(rep)
        reportMouseReach(rep)
        reportFolding(rep)
        reportSemanticColour(rep)

        rep.section("Re-render coalescing")
        rep.line(rerenderReport())
        rep.line("(one theme switch should show requests=<node count> and dispatches=1 — flip the IDE theme, then re-run)")

        rep.section("Captures")
        // The alternative is in the filename: 1a‴'s captures were indistinguishable once the shape became a knob,
        // and a PNG whose name does not carry what it shows is a screenshot of nothing in particular. The chrome is
        // no longer part of the name because it is no longer a choice — the leader mode and the two numbers §1a⁵ 1
        // says to judge by eye are.
        captureComponent(
            this, out,
            "nest-${NodeViewRun.CASES[caseIndex].name}-${nestStyle.leader.name.lowercase()}" +
                "-strip${nestStyle.stripHeight()}-turn${Math.round(nestStyle.arrowTurn * 100)}.png",
            rep,
        )

        // Last, because it rebuilds the whole view: everything above reads the view the user was looking at,
        // and the PNG is of that view rather than of a freshly rebuilt one.
        reportDisposal(rep)

        rep.section("End")
        rep.line(rep.summary())
        rep.flush()
        notify(
            Triple(
                "Spike 1a‴ report written",
                "${rep.summary()}<br/>$out",
                if (rep.failures == 0) NotificationType.INFORMATION else NotificationType.WARNING,
            ),
        )
    }

    /** Did the platform accept an editor-hosting component inside a viewer editor at all? */
    private fun reportEmbedding(rep: Report) {
        rep.section("Embedding (EditorEmbeddedComponentManager, an impl-package API)")
        val ns = nest?.nestings
        if (ns == null) {
            rep.skip("embedding", "the nested view is not built yet — nothing to measure")
            return
        }
        if (ns.isEmpty()) {
            rep.skip("embedding", "no edges in this case, or no call-site line resolved")
            return
        }
        for (n in ns) {
            rep.line("── $n")
            rep.line("   frame           : ${n.frameBounds()} pref=${n.framePreferredHeight()}px inHostContent=${n.frameIsInHostContent()}")
            rep.line("   inlay           : offset=${n.inlayOffset()} height=${n.inlayHeight()}px valid=${n.inlayIsValid()}")
            rep.line("   token           : ${n.leaderState} xRange=${n.tokenXRangeReport()}")
        }
        val embedded = ns.count { it.state == EmbedState.EMBEDDED }
        if (embedded == ns.size) {
            rep.pass("all ${ns.size} child node(s) embedded in their parent's editor — addComponent accepts a viewer editor")
        } else {
            rep.fail(
                "embedding",
                "${ns.size - embedded} of ${ns.size} did not embed (${ns.filter { it.state != EmbedState.EMBEDDED }
                    .joinToString { "${it.child.spec.title}=${it.state}" }}) — if this is FAILED, Arch C's " +
                    "impl-API risk has arrived, and the cleanup pass deleted the built fallback — see the top of TreeOfCode.md",
            )
        }
        val inContent = ns.count { it.frameIsInHostContent() }
        if (inContent == ns.size) {
            rep.pass("every nested frame really is inside its host editor's content component (the leader's precondition)")
        } else {
            rep.fail(
                "frame parentage",
                "${ns.size - inContent} frame(s) are not descendants of the host's content component, so the " +
                    "token's x cannot be converted into the leader's coordinates (LeaderState.UNCONVERTIBLE)",
            )
        }
        val located = ns.count { it.leaderState == LeaderState.ON_TOKEN }
        if (located == ns.size) {
            rep.pass("every leader starts at the invoking token itself, not at the whole line")
        } else {
            rep.info(
                "$located of ${ns.size} leaders start at a located token; the rest fall back to the trimmed " +
                    "line, which is legible but points at the statement rather than at the call",
            )
        }
    }

    /**
     * **The load-bearing measurement of the whole approach.**
     *
     * If the host's own text is not pushed down past the nested frame, the frame is drawn over the lines after
     * the call site and the host's height is short by the frame's height. No amount of leader polish would
     * rescue that, and it is the one thing that cannot be judged from a screenshot of a *short* host.
     */
    private fun reportPushdown(rep: Report) {
        rep.section("Does the host's text move down past the nested frame?")
        val ns = nest?.nestings?.filter { it.state == EmbedState.EMBEDDED }
        if (ns.isNullOrEmpty()) {
            rep.skip("pushdown", "nothing embedded — no edge in this case resolved a call-site line")
            return
        }
        var bad = 0
        for (n in ns) {
            rep.line("── ${n.parent.spec.title} → ${n.child.spec.title}")
            rep.line("   ${n.pushdownReport()}")
            if (!n.textFollowsFrame()) bad++
        }
        if (bad == 0) {
            rep.pass("the line after each call site sits below its nested frame — the host reflows around the child")
        } else {
            rep.fail(
                "pushdown",
                "$bad nested frame(s) overlap the host's following line: the embedded component is painted over " +
                    "code instead of displacing it, which makes Arch C unusable as drawn",
            )
        }
    }

    /**
     * Does the host's own height include what is embedded in it — and does the *tree* then size its slot to it?
     *
     * Two hops, and both are needed: `offsetToXY` has to count the inlay (constraint 9's "a node's height
     * depends on what is drawn in it"), and the host's `resync` has to publish it. 1a″ wired
     * `CodeNodeView`'s `InlayModel` listener for the docked callout; a nested frame is the same mechanism at a
     * much larger size, which is why the number is worth printing rather than assuming.
     */
    private fun reportHostHeight(rep: Report) {
        rep.section("Host height, and the slot around it (constraint 9)")
        val all = nodes()
        if (all.isEmpty()) {
            rep.skip("host height", "no nodes built — is the fixture solution the open project?")
            return
        }
        val hostsOf = nest?.nestings?.groupBy { it.parent }.orEmpty()
        for (node in all) {
            val mine = hostsOf[node].orEmpty()
            val nestedPx = mine.sumOf { it.frameHeight() }
            rep.line("── ${node.spec.title}  (${node.spec.file.name}, depth ${node.spec.depth})")
            rep.line(
                "   size            : slot=${node.width}x${node.height} content=${node.contentHeight()}px " +
                    "published=${node.preferredSize.height}px cap=${nodeStyle.maxHeight} scrolls=${node.scrolls()}",
            )
            if (mine.isNotEmpty()) {
                rep.line(
                    "   hosts           : ${mine.size} nesting(s), ${nestedPx}px of embedded frame; " +
                        "host content was ${mine.first().hostHeightBeforeEmbed}px before embedding",
                )
            }
            rep.line("   resync          : ${node.resyncReport()}")
        }
        val roots = nest?.roots ?: all
        val slotMismatch = roots.count { it.parent != null && it.height != it.preferredSize.height }
        if (slotMismatch == 0) {
            rep.pass("every top-level node sits in a slot exactly its published height, nested children included")
        } else {
            rep.fail(
                "slot heights",
                "$slotMismatch top-level node(s) are in a slot that is not their published height — the " +
                    "embedded frame's size did not reach the enclosing layout",
            )
        }
        val hosts = hostsOf.keys
        val short = hosts.count { h -> h.contentHeight() < (hostsOf[h]?.sumOf { it.frameHeight() } ?: 0) }
        if (hosts.isEmpty()) {
            rep.skip("host content height", "no hosts — nothing in this case embedded a child")
        } else if (short == 0) {
            rep.pass("every host's content height exceeds the frames embedded in it (offsetToXY counts the inlay)")
        } else {
            rep.fail(
                "host content height",
                "$short host(s) report a content height smaller than what is embedded in them — offsetToXY is " +
                    "not counting the block inlay, so the host will clip its own child",
            )
        }
    }

    /**
     * Collapse and re-expand, and the thing the user asked for by name: **the parent's scroll position survives.**
     *
     * Also the strongest available check that a nested child's re-measure reaches its host, since a collapse is a
     * large height change with a known sign: the host must get shorter, then exactly as tall as it was.
     */
    private fun reportCollapse(rep: Report) {
        rep.section("Collapse and re-expand the nested viewer")
        val view = nest
        val ns = view?.nestings?.filter { it.state == EmbedState.EMBEDDED }
        if (view == null || ns.isNullOrEmpty()) {
            rep.skip("collapse", "nothing embedded — no edge in this case resolved a call-site line")
            return
        }
        val wereCollapsed = ns.map { it.collapsed }
        val host = ns.first().parent
        val scrollBefore = host.editor.scrollingModel.verticalScrollOffset
        val heightBefore = host.contentHeight()
        val framesBefore = ns.map { it.framePreferredHeight() }

        // Synchronous on purpose: `Nesting.setCollapsed` calls `Inlay.update()` itself rather than deferring it,
        // so the host's `offsetToXY` already counts the new inlay height by the time this line runs. The
        // *actual* frame bounds are a layout pass behind, which is why the preferred height is what is compared.
        view.collapseAll(true)
        val heightCollapsed = host.contentHeight()
        val framesCollapsed = ns.map { it.framePreferredHeight() }
        val scrollCollapsed = host.editor.scrollingModel.verticalScrollOffset

        view.collapseAll(false)
        val heightAfter = host.contentHeight()
        val scrollAfter = host.editor.scrollingModel.verticalScrollOffset

        rep.line("host '${host.spec.title}': content ${heightBefore}px → collapsed ${heightCollapsed}px → expanded ${heightAfter}px")
        rep.line("host scroll offset      : $scrollBefore → $scrollCollapsed → $scrollAfter")
        rep.line("frame preferred heights : $framesBefore → $framesCollapsed")

        when {
            heightCollapsed >= heightBefore -> rep.fail(
                "collapse",
                "collapsing every nesting did not make the host shorter (${heightBefore}px → ${heightCollapsed}px): " +
                    "the frame shrank and the inlay was not re-measured, or Inlay.update() did nothing",
            )

            heightAfter != heightBefore -> rep.fail(
                "re-expand",
                "the host came back at ${heightAfter}px instead of ${heightBefore}px — a collapse is not reversible, " +
                    "which is the failure constraint 6 predicts for a fold and this mechanism exists to avoid",
            )

            else -> rep.pass("collapse shrinks the host and re-expanding restores it exactly (${heightBefore}px)")
        }
        if (scrollAfter == scrollBefore) {
            rep.pass("the host's own scroll position survived the collapse/re-expand cycle")
        } else {
            rep.fail(
                "collapse scroll",
                "the host's scroll offset moved $scrollBefore → $scrollAfter across the cycle, so the reader " +
                    "loses their place by collapsing an expansion",
            )
        }
        // Leave the nest as the user had it.
        for ((i, n) in ns.withIndex()) n.setCollapsed(wereCollapsed[i])
        rep.info("this check collapses and re-expands everything and restores the original state")
    }

    /**
     * **The one outline (constraint 16, §1a⁵ 1) — measured on the real thing, in three ways that fail differently.**
     *
     * The check this replaces asserted that the chrome's *drawn-line offset* and the leader's *overlap* agreed. That
     * question stopped existing when `TITLED_BORDER` was scrapped: every surviving chrome draws its line at its
     * bounds edge, so the offset is 0 by construction. What is left is what the outline rule actually claims.
     *
     *  1. **Adjacency** — the strip ends exactly where the child begins ([Nesting.seamRows] == 0) and spans the same
     *     columns, so the leader's tips land on the frame's top corners. §1a⁵ 1 left "1 px overlap or exact
     *     adjacency" open; `spikes/tree1a5/` measured the outline as one 8-connected component at zero overlap, and
     *     this is that answer holding in the real component hierarchy rather than in a mock.
     *  2. **No stale border segment** — the node's drawn top edge agrees with what the mode wants
     *     ([Nesting.edgesAgree]). This is the G1c′ criterion that catches a restyle which repainted the leader and
     *     forgot the borders, and it is invisible to the eye in exactly one direction: an `ARROW` frame missing its
     *     top edge looks like a `CORNERS` frame that happens to have an arrow.
     *  3. **Pixels** — the box's bottom edge and the frame's top edge, counted in a snapshot of the host editor.
     *     This is the half the probe *cannot* answer: the box's bottom row is one row below its own text line, and
     *     whether a `CustomHighlighterRenderer` may paint there is an IDE question, not a Java2D one. If the answer
     *     is no, `ARROW`'s box will measure three-sided here while the source says four.
     */
    private fun reportOutline(rep: Report) {
        rep.section("The one outline: which edges are drawn, and where they meet (constraint 16, §1a⁵ 1)")
        val ns = nest?.nestings?.filter { it.state == EmbedState.EMBEDDED }
        if (ns.isNullOrEmpty()) {
            rep.skip("outline", "nothing embedded — no edge in this case resolved a call-site line")
            return
        }
        var seams = 0
        var misaligned = 0
        var stale = 0
        for (n in ns) {
            val strip = n.stripBounds()
            val child = n.childBoundsInFrame()
            rep.line("── ${n.child.spec.title}: ${n.edgeReport()}")
            rep.line(
                "   junction        : strip=$strip child=$child seam=${n.seamRows()}px " +
                    "stripHeight=${nestStyle.stripHeight()}px",
            )
            if (n.seamRows() != 0) seams++
            if (strip.x != child.x || strip.width != child.width) misaligned++
            if (!n.edgesAgree()) stale++
        }
        if (seams == 0) {
            rep.pass(
                "every leader's last row is adjacent to its frame's first — seam 0px, no overlap needed, which is " +
                    "§1a⁵ 1's open question closed and constraint 7's contains() cost gone with it",
            )
        } else {
            rep.fail(
                "seam",
                "$seams nesting(s) leave a gap between the strip and the child, so the outline visibly breaks " +
                    "where the leader meets the frame's walls — the 1px stagger §1a⁵ 1 predicted, arrived",
            )
        }
        if (misaligned == 0) {
            rep.pass("the strip spans exactly the child's columns, so the leader's tips land on the frame's top corners")
        } else {
            rep.fail(
                "tip alignment",
                "$misaligned strip(s) are a different width or x from their child, so the tips cannot coincide " +
                    "with the frame's top corners however the path is drawn",
            )
        }
        if (stale == 0) {
            rep.pass("every node's border agrees with the leader mode — no stale border segment after a restyle")
        } else {
            rep.fail(
                "stale border",
                "$stale node(s) draw a top edge the current leader mode does not want (or omit one it does) — " +
                    "switching modes repainted the leader and left the border behind, which is what G1c′ names",
            )
        }
        reportOutlinePixels(rep, ns)
    }

    /**
     * The two suppressed edges, counted in a snapshot rather than trusted from the source.
     *
     * Ink is measured **adaptively**: a pixel counts if it is closer to the outline's colour than half way back to
     * the editor's background. A fixed tolerance would either miss every antialiased pixel of a 1.2 px stroke or
     * count the caption band, and the answer has to hold in both themes.
     */
    private fun reportOutlinePixels(rep: Report, ns: List<CodeNestView.Nesting>) {
        val host = ns.first().parent
        val img = snapshotOf(host.editor.contentComponent)
        if (img == null) {
            rep.skip("outline pixels", "the host editor's content component is not laid out yet")
            return
        }
        val ink = LeaderArt.leaderBase()
        val bg = host.editor.colorsScheme.defaultBackground
        val closed = nestStyle.leader == NestLeader.ARROW
        var wrong = 0
        var measured = 0
        for (n in ns) {
            val xr = n.tokenXRange()
            val row = n.boxBottomRow()
            if (xr == null || row == null || row >= img.height) {
                rep.line("── ${n.child.spec.title}: box bottom row not measurable (x=$xr row=$row)")
                continue
            }
            measured++
            val from = xr.first + LeaderArt.ARC + 3
            val to = xr.second - LeaderArt.ARC - 3
            val fraction = inkFractionAlongRow(img, from, to, row, ink, bg)
            val ok = if (closed) fraction > 0.80 else fraction < 0.10
            if (!ok) wrong++
            rep.line(
                "── ${n.child.spec.title}: box bottom row y=$row x=$from..$to → " +
                    "${Math.round(fraction * 100)}% inked (expected ${if (closed) "an edge" else "no edge"})",
            )
        }
        if (measured == 0) {
            rep.skip("outline pixels", "no token box could be located in the host's painted content")
            return
        }
        if (wrong == 0 && closed) {
            rep.pass(
                "ARROW's token box really is closed in the painted host — a markup renderer may draw on the row " +
                    "below its own line, which is the one thing spikes/tree1a5 could not answer",
            )
        } else if (wrong == 0) {
            rep.pass("the token box's bottom edge is absent where the leader owns it, measured in pixels")
        } else {
            rep.fail(
                "outline pixels",
                "$wrong of $measured token boxes disagree with the mode. If this fails only in ARROW, the box's " +
                    "bottom edge sits on the row the nested frame starts on and the editor is clipping the " +
                    "renderer there — LeaderArt's one-row definition is the thing to change, not the leader",
            )
        }
    }

    /** The fraction of columns in [from]..[to] carrying outline ink within one row of [y]. See [reportOutlinePixels]. */
    private fun inkFractionAlongRow(
        img: java.awt.image.BufferedImage,
        from: Int,
        to: Int,
        y: Int,
        ink: java.awt.Color,
        bg: java.awt.Color,
    ): Double {
        if (to <= from) return 0.0
        val half = colourDistance(ink.rgb, bg.rgb) / 2
        var hit = 0
        var total = 0
        for (x in from.coerceAtLeast(0)..to.coerceAtMost(img.width - 1)) {
            total++
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= img.height) continue
                if (colourDistance(img.getRGB(x, yy), ink.rgb) < half) {
                    hit++
                    break
                }
            }
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    private fun colourDistance(a: Int, b: Int): Int {
        val dr = Math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = Math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = Math.abs((a and 0xFF) - (b and 0xFF))
        return dr + dg + db
    }

    /**
     * **§1a⁵ 2's header rule, asserted**: a caption when the node is top-level or in a different file from its
     * parent, and nowhere else.
     *
     * A rule rather than a knob means the check is the only thing standing between it and a silent regression — with
     * the chrome button gone from the toolbar, nobody will notice by clicking. The same-file case is what
     * `Invokers.cs` is in the fixture for, so this section is only meaningful on `case: same-file`, and it says so
     * rather than skipping.
     */
    private fun reportHeaderRule(rep: Report) {
        rep.section("The header rule (§1a⁵ 2 — a rule, not a setting)")
        val view = nest ?: run {
            rep.skip("header rule", "the nested view is not built yet")
            return
        }
        var wrong = 0
        var sameFile = 0
        for (node in view.nodes) {
            val nesting = view.nestings.firstOrNull { it.child === node && it.state == EmbedState.EMBEDDED }
            val parent = nesting?.parent
            val expected = when {
                parent == null -> NodeChrome.HEADER_STRIP
                parent.spec.file == node.spec.file -> NodeChrome.PLAIN
                else -> NodeChrome.HEADER_STRIP
            }
            if (parent != null && parent.spec.file == node.spec.file) sameFile++
            val actual = node.style.chrome
            rep.line(
                "── ${node.spec.title}: ${if (parent == null) "top-level" else "nested in ${parent.spec.title}"}" +
                    ", ${if (parent == null || parent.spec.file != node.spec.file) "different file" else "SAME file"}" +
                    " → expected $expected, has $actual (header band ${node.headerBandHeight()}px)",
            )
            if (actual != expected) wrong++
        }
        if (wrong == 0) {
            rep.pass("every node's chrome follows the rule: header iff top-level or a different file from its parent")
        } else {
            rep.fail(
                "header rule",
                "$wrong node(s) have the wrong chrome for their role — with the chrome knob gone from the toolbar " +
                    "this check is the only thing that would notice",
            )
        }
        if (sameFile == 0) {
            rep.info(
                "no nesting in this case has caller and callee in one file, so the PLAIN half of the rule is " +
                    "untested here — switch to case: same-file, where InvokeOn calls Normalize 87 lines away",
            )
        }
    }

    /**
     * **The 1a‴ defect, measured at the depth it actually failed at** — constraint 17.
     *
     * Collapse-then-expand on the three-deep chain restored the outer host to 1186 px instead of 1741, short by
     * exactly the inner frame's height, because an inner re-measure reached the *outer* inlay only through a
     * coalesced `invokeLater`: one deferred pass per level of nesting.
     *
     * Deliberately **synchronous**, and that is the check rather than an implementation detail of it: the fix is
     * `Nesting.updateInlay` walking the chain in the same EDT turn, so if the walk is ever lost this reads the
     * intermediate state and fails — which is exactly what the unfixed code did. The ledger's lesson says to make
     * the chain synchronous *or* to measure after it settles and to say which; this makes it synchronous.
     */
    private fun reportMultiLevelCollapse(rep: Report) {
        rep.section("Collapse at two levels of nesting (constraint 17 — the 1a‴ defect)")
        val view = nest
        val ns = view?.nestings?.filter { it.state == EmbedState.EMBEDDED }
        if (view == null || ns.isNullOrEmpty()) {
            rep.skip("multi-level collapse", "nothing embedded — no edge in this case resolved a call-site line")
            return
        }
        rep.line("chains: ${view.nestingChainReport()}")
        // The outermost host: a node that hosts a nesting and is nested in nothing itself.
        val outer = ns.map { it.parent }.firstOrNull { host -> ns.none { it.child === host } }
        val deepest = ns.firstOrNull { inner -> ns.any { it.child === inner.parent } }
        if (outer == null || deepest == null) {
            rep.skip(
                "multi-level collapse",
                "this case nests only one level deep (${ns.size} nesting(s)) — switch to case: chain, where " +
                    "MainInvoker hosts MidInvoker which hosts SubInvoker",
            )
            return
        }
        val wereCollapsed = ns.map { it.collapsed }
        val before = outer.contentHeight()
        // Collapse the *inner* nesting only: the outer inlay learns about it through the host node, which is the
        // hop that used to be one EDT turn late.
        deepest.setCollapsed(true)
        val collapsed = outer.contentHeight()
        deepest.setCollapsed(false)
        val after = outer.contentHeight()

        rep.line(
            "outer host '${outer.spec.title}' content height: ${before}px → ${collapsed}px (inner " +
                "'${deepest.child.spec.title}' collapsed) → ${after}px",
        )
        when {
            collapsed >= before -> rep.fail(
                "multi-level collapse",
                "collapsing the inner nesting did not shorten the outer host (${before}px → ${collapsed}px): the " +
                    "inner re-measure is not reaching the outer inlay at all",
            )

            after != before -> rep.fail(
                "multi-level collapse",
                "the outer host came back at ${after}px instead of ${before}px, short by ${before - after}px — " +
                    "this is constraint 17 exactly as 1a‴ measured it (555px = the inner frame's 602 − 47), which " +
                    "means Nesting.updateInlay has stopped walking up the nesting chain",
            )

            else -> rep.pass(
                "an inner collapse propagates to the outer inlay in the same EDT turn: ${before}px → " +
                    "${collapsed}px → ${before}px, exact at two levels",
            )
        }
        for ((i, n) in ns.withIndex()) n.setCollapsed(wereCollapsed[i])
    }

    /**
     * **One** collapse affordance, and can it be clicked? — 1a‴ verdict 4, which found two marks where one was wanted.
     *
     * Two halves, and the second is the one that reads as a broken frame when it is wrong: that the control exists
     * in the node's header band, and that a click there actually reaches *it* rather than the editor beneath or the
     * host above. The probe measured that this needs the control z-lifted above the editor component
     * (`spikes/tree1a4/`, with the buried-control control that fails); this checks the real hierarchy, which is
     * three components deeper than the mock's.
     */
    private fun reportAffordance(rep: Report) {
        rep.section("The single collapse affordance (verdict 4)")
        val ns = nest?.nestings?.filter { it.state == EmbedState.EMBEDDED }
        if (ns.isNullOrEmpty()) {
            rep.skip("affordance", "nothing embedded — no edge in this case resolved a call-site line")
            return
        }
        if (!nestStyle.toggleInHeader) {
            rep.skip("affordance", "toggle is switched off on the toolbar — the strip still takes the click")
            return
        }
        var present = 0
        var reachable = 0
        var measured = 0
        for (n in ns) {
            val hostContent = n.parent.editor.contentComponent
            val bounds = n.toggleBoundsIn(hostContent)
            if (n.hasToggle()) present++
            if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
                rep.line("── ${n.child.spec.title}: control ${if (n.hasToggle()) "present but not laid out" else "absent"}")
                continue
            }
            measured++
            val deepest = SwingUtilities.getDeepestComponentAt(
                hostContent, bounds.x + bounds.width / 2, bounds.y + bounds.height / 2,
            )
            val hits = deepest != null && SwingUtilities.isDescendingFrom(deepest, n.child) &&
                deepest !== n.child.editor.contentComponent
            if (hits) reachable++
            rep.line(
                "── ${n.child.spec.title}: control at $bounds (chrome=${nodeStyle.chrome}, header band " +
                    "${n.child.headerBandHeight()}px) → deepest is ${deepest?.javaClass?.simpleName} (reaches it=$hits)",
            )
        }
        if (present == ns.size) {
            rep.pass("every nesting shows exactly one collapse control, in the nested node's own header band")
        } else {
            rep.fail(
                "affordance",
                "$present of ${ns.size} nestings have a collapse control — a nesting the reader cannot fold is " +
                    "the thing verdict 4 was about",
            )
        }
        if (measured == 0) {
            rep.skip("affordance hit test", "no control is laid out yet")
        } else if (reachable == measured) {
            rep.pass("a click on the control lands on the control, three components deep inside an embedded editor")
        } else {
            rep.fail(
                "affordance hit test",
                "${measured - reachable} of $measured controls are drawn but unreachable — the editor component " +
                    "is hit-testing over them, which reads as the collapse affordance being broken rather than " +
                    "as it being behind something (the probe's buried-control case, arrived for real)",
            )
        }
    }

    /**
     * **Where each node opens — now an assertion (§1a⁵ 4).**
     *
     * A nested child is sized to its excerpt of interest and opens *scrolled* to it; the root is exempt and opens on
     * its first kept line. Three things are checked, and the middle one is the one that would otherwise pass for the
     * wrong reason:
     *
     *  1. The nested child's published height is its excerpt's, subject to `min(target, content, cap)` — the
     *     reader's decision was **no floor**, so a 6-line member is a six-line node.
     *  2. The viewport really moved. The honest measurement is [CodeNodeView.firstVisibleLineInViewport], **not**
     *     `firstVisibleKeptLine`: the plan expected the old measurement to score this mechanism, and it cannot —
     *     §1a⁗ elided everything above the member, so the first *unfolded* line was what the reader saw; this
     *     leaves `keep` as authored and scrolls, so the first unfolded line is the class header again. A check on
     *     the old number would report the class header and call the landing broken.
     *  3. `scrolls()` is true on a landed child — constraint 19, which is the whole point: a node sized to its
     *     content has no viewport to move, so this is what makes Phase 3's per-node reveal expressible at all.
     */
    private fun reportLanding(rep: Report) {
        rep.section("Where each node opens (§1a⁵ 4 — capped to the excerpt, scrolled to the member)")
        val view = nest
        val all = nodes()
        if (view == null || all.isEmpty()) {
            rep.skip("landing", "no nodes were built in this case")
            return
        }
        var wrongLine = 0
        var wrongHeight = 0
        var noRoom = 0
        var landed = 0
        for (node in all) {
            val nested = view.nestings.any { it.child === node && it.state == EmbedState.EMBEDDED }
            val keep = node.spec.keep.joinToString(",") { "${it.first}-${it.last}" }
            val focus = node.spec.focusRange()
            val target = node.excerptTargetHeight()
            val visible = node.firstVisibleLineInViewport()
            rep.line("── ${node.spec.title}${if (nested) " (nested)" else " (root)"}: keep=$keep focus=$focus")
            rep.line(
                "   landing         : firstVisibleInViewport=${visible ?: "-"} firstKept=${node.firstVisibleKeptLine() ?: "-"} " +
                    "asked=${node.lastLandingRequest}px offset=${node.scrollOffset()}px",
            )
            rep.line(
                "   size            : target=${target ?: "-"} content=${node.contentHeight()}px " +
                    "published=${node.preferredSize.height}px cap=${if (node.style.grow) "grow" else
                        "${node.style.maxHeight}+${node.embeddedHeight}px"} scrolls=${node.scrolls()}",
            )
            rep.line(
                "   embedded        : ${node.embeddedHeight}px of child frame, ${node.embeddedInExcerptHeight}px of " +
                    "it inside the excerpt (§G1c′ 3 — a host's target is its member plus what is nested in it)",
            )
            if (!nested) {
                // The root is exempt, and that is a claim worth checking rather than assuming: it must NOT have a
                // target, or the reader's "worse on top editor" verdict has been re-introduced silently.
                if (target != null) wrongHeight++
                continue
            }
            landed++
            if (focus != null && visible != null && visible != focus.first) wrongLine++
            if (target != null) {
                // The cap computed from the *rule* — `maxHeight` plus what is embedded, since a child frame is not
                // the node's own code — rather than read back from the node, which would only prove it agrees with
                // itself. §G1c′ 3 is what put the second term in.
                val expected = minOf(target, node.contentHeight(), node.style.maxHeight + node.embeddedHeight)
                if (node.style.grow) {
                    // Since the round, `growHosts` reaches only *top-level* hosts, so a landed node can be grown
                    // only by the global `nodeStyle.grow` — and then there is no viewport to land in.
                    rep.info("${node.spec.title} is grown to content, so it carries no excerpt viewport to land in")
                } else if (node.publishedHeight() != expected) {
                    wrongHeight++
                }
            }
            if (!node.scrolls() && !node.style.grow) noRoom++
        }
        if (landed == 0) {
            rep.skip("landing", "nothing is embedded in this case, so no node lands on an excerpt")
            return
        }
        if (wrongLine == 0) {
            rep.pass("every nested node opens with its excerpt's first line at the top of its own viewport")
        } else {
            rep.fail(
                "landing line",
                "$wrongLine nested node(s) do not open on their member. `asked=…px offset=0px` above means the " +
                    "scroll clamped for want of a laid-out viewport — one deferred pass per level of nesting " +
                    "(constraint 17), which is what CodeNestView's bounded landing retry is for",
            )
        }
        if (wrongHeight == 0) {
            rep.pass("nested heights are min(excerpt, content, cap) and the root carries no target — it is exempt")
        } else {
            rep.fail(
                "landing height",
                "$wrongHeight node(s) are not the height §1a⁵ 4 specifies (or the root grew a target it must not " +
                    "have) — the excerpt target is what creates the viewport, so a wrong height is a missing reveal",
            )
        }
        if (noRoom == 0) {
            rep.pass("every landed node has a viewport smaller than its content, so constraint 19's scroll room exists")
        } else {
            rep.fail(
                "scroll room",
                "$noRoom landed node(s) still size to their content, so there is nothing to scroll in them and " +
                    "Phase 3's 'reveal the active range in its node' is not expressible for them (constraint 19)",
            )
        }
    }

    /**
     * Does the mouse reach a nested editor — and does the leader strip get the click that collapses it?
     *
     * The objective half of "the wheel over the child scrolls the child". 1a″'s equivalent question cost the
     * whole tree when it was wrong (`contains()` on the overlay), and it is exactly as invisible here: an
     * embedded component the platform positions but does not hit-test would look like a picture of an editor.
     *
     * **Rewritten per §1a⁵ 5, because the old version measured before a depth-2 frame was placed.** It guarded on
     * the *child's* `isShowing` and size, which pass while the ancestor chain is still settling, so the first
     * report of a session failed on the doubly-nested node and a later one passed. Two changes, and they are
     * different in kind:
     *
     *  - the sample point is the centre of the child's frame **intersected with the host's bounds**, so a frame the
     *    host has not made room for yet cannot be aimed at;
     *  - a point outside the host is **not measured** rather than failed. A stolen event returns the host's own
     *    component and is a real defect; a `null` means the check aimed into space and has measured nothing. The
     *    real assertion survives intact: a point genuinely inside the child that resolves to the host is a FAIL.
     *
     * This is constraint 17 recurring in a new check rather than once, which is what the constraint says to expect.
     */
    private fun reportMouseReach(rep: Report) {
        rep.section("Does the mouse reach the nested editor, and the leader? (§1a⁵ 5)")
        val ns = nest?.nestings?.filter { it.state == EmbedState.EMBEDDED }
        if (ns.isNullOrEmpty()) {
            rep.skip("mouse reach", "nothing embedded — no edge in this case resolved a call-site line")
            return
        }
        var childOk = 0
        var stripOk = 0
        var measured = 0
        var unplaced = 0
        var topRowsOk = 0
        var topRowsMeasured = 0
        for (n in ns) {
            val hostContent = n.parent.editor.contentComponent
            val fb = n.frameBoundsIn(hostContent)
            // The frame, clipped to the part of it the host has actually made room for. An empty intersection is
            // the depth-2 case the old check failed on: the frame has bounds, and they are nowhere yet.
            val room = fb?.intersection(Rectangle(0, 0, hostContent.width, hostContent.height))
            if (fb == null || room == null || room.isEmpty) {
                unplaced++
                rep.line(
                    "── ${n.child.spec.title}: frame=$fb host=${hostContent.width}x${hostContent.height} → " +
                        "not placed inside the host yet, NOT MEASURED (the §1a⁵ 5 defect: this used to FAIL)",
                )
                continue
            }
            measured++
            val cx = room.x + room.width / 2
            // Below the strip by a whole strip's height, so the point is inside the child rather than in the band
            // above it — and clamped into the room the host has made, which is the fix.
            val childY = (room.y + n.stripHeight() + n.stripHeight() / 2).coerceAtMost(room.y + room.height - 1)
            val deepestInChild = SwingUtilities.getDeepestComponentAt(hostContent, cx, childY)
            val reachesChild = deepestInChild != null && SwingUtilities.isDescendingFrom(deepestInChild, n.child)

            val deepestInStrip = SwingUtilities.getDeepestComponentAt(hostContent, cx, room.y + n.stripHeight() / 2)
            val reachesStrip = deepestInStrip != null &&
                !SwingUtilities.isDescendingFrom(deepestInStrip, n.child) &&
                deepestInStrip !== hostContent

            // The child's **first rows**, which the strip used to overlap and disclaim via `contains()`. With the
            // overlap at zero there is nothing above them any more, so this now checks the absence of the problem
            // rather than the fix for it — kept because the rule has bitten twice and its failure is silence.
            val firstRowY = room.y + n.stripHeight() + 1
            if (firstRowY < room.y + room.height) {
                topRowsMeasured++
                val inTopRows = SwingUtilities.getDeepestComponentAt(hostContent, cx, firstRowY)
                if (inTopRows != null && SwingUtilities.isDescendingFrom(inTopRows, n.child)) topRowsOk++
            }

            rep.line(
                "── ${n.child.spec.title}: room=$room; inside the child → ${deepestInChild?.javaClass?.simpleName} " +
                    "(reaches child=$reachesChild); in the strip → ${deepestInStrip?.javaClass?.simpleName} " +
                    "(reaches leader=$reachesStrip); seam=${n.seamRows()}px",
            )
            if (reachesChild) childOk++
            if (reachesStrip) stripOk++
        }
        if (measured == 0) {
            rep.skip(
                "mouse reach",
                "$unplaced nested frame(s) are not placed inside their host yet — not measured rather than failed, " +
                    "which is the §1a⁵ 5 fix; re-run the report once the view has settled",
            )
            return
        }
        if (unplaced > 0) {
            rep.info("$unplaced nesting(s) were not measured because their frame is not placed in the host yet")
        }
        if (topRowsMeasured > 0 && topRowsOk == topRowsMeasured) {
            rep.pass(
                "the nested editor's first rows belong to its own mouse — with the strip's overlap at zero there is " +
                    "nothing above them, which is why the contains() override could go",
            )
        } else if (topRowsMeasured > 0) {
            rep.fail(
                "child top rows",
                "${topRowsMeasured - topRowsOk} of $topRowsMeasured nested editors are dead in their first rows — " +
                    "something above them is taking the events, which is 1a″'s contains() defect returning by a " +
                    "different route now that the strip no longer disclaims anything",
            )
        }
        if (childOk == measured) {
            rep.pass("a click or wheel event inside a nested editor lands inside that editor, not on the host")
        } else {
            rep.fail(
                "mouse reach",
                "${measured - childOk} of $measured nested editors do not receive events inside themselves — " +
                    "the host editor is hit-testing over them, which reads as the nested node being a picture",
            )
        }
        if (stripOk == measured) {
            rep.pass("the leader strip receives the click that collapses the nesting")
        } else {
            rep.info(
                "$stripOk of $measured leader strips are hit-testable; where they are not, collapsing needs the " +
                    "stub row or a toolbar button rather than clicking the leader",
            )
        }
    }

    /**
     * Constraint 5 at one more level of containment — and with the count **split**, which 1a″ asked for: an
     * expanded region is a reader, an invalid one is a loss, and only the second is a defect.
     */
    private fun reportFolding(rep: Report) {
        rep.section("Elision inside a nested editor (constraint 5, counted properly)")
        val all = nodes()
        if (all.isEmpty()) {
            rep.skip("folding", "no nodes")
            return
        }
        var lost = 0
        var opened = 0
        for (node in all) {
            val nested = nest?.nestings?.any { it.child === node && it.state == EmbedState.EMBEDDED } == true
            rep.line(
                "── ${node.spec.title}${if (nested) " [nested]" else ""}: " +
                    "${node.survivingFolds()}/${node.requestedFolds()} collapsed, ${node.expandedFolds()} opened by " +
                    "the reader, ${node.invalidFolds()} lost, ${node.foreignFolds()} foreign",
            )
            lost += node.invalidFolds()
            opened += node.expandedFolds()
        }
        if (lost == 0) {
            rep.pass("no elision was lost in any node, nested or not — the daemon still only *adds* folds")
        } else {
            rep.fail("elision", "$lost of our fold regions were invalidated, which is a real loss (constraint 5)")
        }
        if (opened > 0) {
            rep.info(
                "$opened region(s) are installed but expanded: that is the reader having opened an elision " +
                    "(NodeElision.OPENABLE working), not a failure — the distinction 1a″ was misread on",
            )
        }
    }

    private fun reportSemanticColour(rep: Report) {
        rep.section("Semantic colour per distinct document, nested editors included (constraint 3)")
        val seen = HashSet<String>()
        var withSemantic = 0
        for (node in nodes()) {
            if (!seen.add(node.spec.file.path)) continue
            val nested = nest?.nestings?.any { it.child === node && it.state == EmbedState.EMBEDDED } == true
            val label = node.spec.file.name + if (nested) " [nested]" else ""
            if (classifyMarkersTo(project, node.editor.document, label, rep) > 0) withSemantic++
        }
        when {
            seen.isEmpty() -> rep.skip("semantic colour", "no documents")
            withSemantic == seen.size ->
                rep.pass("every distinct document carries ReSharper.* colour categories, at every nesting level")

            else -> rep.info(
                "$withSemantic of ${seen.size} document(s) carry ReSharper.* colour categories — if this is 0, " +
                    "check the solution finished loading before blaming the container",
            )
        }
    }

    /**
     * An editor inside an inlay inside an editor, opened and closed repeatedly — 1a‴'s stated main risk, in the
     * shape B6 used: build, tear down, build again, and compare the count against the baseline.
     */
    private fun reportDisposal(rep: Report) {
        rep.section("Disposal: an editor inside an inlay inside an editor")
        val before = EditorFactory.getInstance().allEditors.size
        rebuild()
        val mid = EditorFactory.getInstance().allEditors.size
        rebuild()
        val after = EditorFactory.getInstance().allEditors.size
        rep.line("getAllEditors(): $editorsAtStart at bench open, $before before this check, $mid, $after after two rebuilds")
        rep.line("nodes now: ${nodes().size}, nestings: ${nest?.nestings?.size ?: 0}, rebuilds: $rebuilds")
        if (after <= before) {
            rep.pass("two rebuilds of the whole view left no extra editors ($before → $after)")
        } else {
            rep.fail(
                "disposal",
                "${after - before} editor(s) survived two rebuilds — a nested editor is only reachable through " +
                    "its inlay, and an inlay disposed with its host editor may release in the wrong order",
            )
        }
        // The step decorations were rebuilt along with the nodes; put the visible state back.
        applyStep(enteringNode = true)
    }

    private companion object {
        /** 0 = the shipped default (containment alone); 18 was 1a‴'s; 28/44 push the frame past the token. */
    }
}
