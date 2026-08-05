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
import com.audioexplainer.tree.TokenMark
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
import java.awt.Point
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
     * Three rows, and the middle one is 1a⁗'s whole lesson.
     *
     * 1a‴ left `chrome` off this toolbar because 1a′ had settled it — and the round came back with "it's a pity the
     * chrome switch was not included and I could not test it", because nesting had changed what the question meant
     * (constraint 16: chrome and leader are one design). **Every visual knob is on the bench now, settled or not.**
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
                JBLabel("chrome × leader: "),
                button("chrome: titled_border") { cycleChrome(it) },
                button("leader: corners") { cycleLeader(it) },
                button("token: box") { cycleTokenMark(it) },
                button("toggle: header") { toggleToggle(it) },
            ),
        )
        rows.add(
            row(
                button("hosts: grow") { toggleGrowHosts(it) },
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
     * The knob 1a‴ was missing, and the reason this bench has three toolbar rows instead of two.
     *
     * Switching it while watching the leader's tips is the fastest way to see constraint 16 rather than read about
     * it: only `TITLED_BORDER` draws its line below its own bounds, so only there does the leader need the
     * overlapping strip to reach it.
     */
    private fun cycleChrome(b: JButton) {
        val values = NodeChrome.entries
        val next = values[(values.indexOf(nodeStyle.chrome) + 1) % values.size]
        restyleNodes(nodeStyle.with(chrome = next))
        b.text = "chrome: ${next.name.lowercase()}"
    }

    private fun cycleLeader(b: JButton) {
        val values = NestLeader.entries
        val next = values[(values.indexOf(nestStyle.leader) + 1) % values.size]
        restyleNest(nestStyle.with(leader = next))
        b.text = "leader: ${next.name.lowercase()}"
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

    private fun cycleTokenMark(b: JButton) {
        val values = TokenMark.entries
        val next = values[(values.indexOf(nestStyle.tokenMark) + 1) % values.size]
        restyleNest(nestStyle.with(tokenMark = next))
        b.text = "token: ${next.name.lowercase()}"
    }

    /** The interaction worth seeing: a host capped at 600 px holding a 500 px child shows two lines of caller. */
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
            append("  ·  ").append(nodeStyle.chrome.name.lowercase()).append(" × ")
                .append(nestStyle.leader.name.lowercase())
            val ns = nest?.nestings
            if (ns != null) {
                append("  ·  nested: ").append(ns.count { it.state == EmbedState.EMBEDDED }).append("/").append(ns.size)
                append(" [").append(ns.joinToString("; ") { "${it.child.spec.title}@${it.callLine}:${it.state}/${it.leaderState}${if (it.collapsed) " ⏷" else ""}" }).append("]")
                // The overlap is the number constraint 16 is about, so it belongs where it can be watched changing
                // as the chrome knob is cycled rather than only in a report written afterwards.
                append("  ·  overlap: ").append(ns.firstOrNull()?.leaderOverlap() ?: 0).append("px")
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
            "nest style: strip=${nestStyle.stripHeight} leader=${nestStyle.leader} " +
                "token=${nestStyle.tokenMark} toggleInHeader=${nestStyle.toggleInHeader} " +
                "gapBelow=${nestStyle.gapBelow} growHosts=${nestStyle.growHosts}",
        )
        rep.line(
            "node style: chrome=${nodeStyle.chrome} path=${nodeStyle.path} elision=${nodeStyle.elision} " +
                "dim=${nodeStyle.deemphasis} maxHeight=${nodeStyle.maxHeight} grow=${nodeStyle.grow}",
        )
        rep.line("tokenPad  : ${LeaderArt.TOKEN_PAD}px (measured in spikes/tree1a4; EffectType.BOXED is 0)")
        rep.line("step      : ${if (steps.isEmpty()) 0 else stepIndex + 1}/${steps.size}")
        rep.line("focus     : ${focused?.spec?.title ?: "<none>"}")
        rep.blank()

        reportEmbedding(rep)
        reportPushdown(rep)
        reportHostHeight(rep)
        reportLeaderLanding(rep)
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
        // The chrome is in the filename now: 1a‴'s captures were indistinguishable once the chrome became a knob,
        // and a PNG whose name does not carry the alternative it shows is a screenshot of nothing in particular.
        captureComponent(
            this, out,
            "nest-${NodeViewRun.CASES[caseIndex].name}" +
                "-${nodeStyle.chrome.name.lowercase()}-${nestStyle.leader.name.lowercase()}.png",
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
     * **Constraint 16, as a number per chrome.**
     *
     * The 1a‴ finding the report could not see and the eye could: with `TITLED_BORDER` the frame's drawn line sits
     * ~8 px below the component's top edge, so diagonals ending at the component's corner stop short. The fix is a
     * strip that extends by exactly that offset and is z-ordered above the child. This prints the offset the node
     * reports and the overlap the frame took, per nesting — the two must agree, and on three of the four chromes
     * both must be zero, which is what makes "pick a different chrome" a real fix rather than a hope.
     *
     * Pre-flight measured the geometry outside the IDE (`spikes/tree1a4/`, with the control that shows the tips
     * disappear when the z-order is reversed); what remains for the sandbox is whether the *real* chrome reports
     * the same numbers the mock did.
     */
    private fun reportLeaderLanding(rep: Report) {
        rep.section("Leader landing per chrome (constraint 16)")
        val ns = nest?.nestings?.filter { it.state == EmbedState.EMBEDDED }
        if (ns.isNullOrEmpty()) {
            rep.skip("leader landing", "nothing embedded — no edge in this case resolved a call-site line")
            return
        }
        val expectOverlap = nodeStyle.chrome == NodeChrome.TITLED_BORDER
        var wrong = 0
        for (n in ns) {
            val drawn = n.child.chromeDrawnTopOffset()
            val overlap = n.leaderOverlap()
            rep.line(
                "── ${n.child.spec.title}: chrome=${nodeStyle.chrome} drawnTopOffset=${drawn}px " +
                    "leaderOverlap=${overlap}px stripHeight=${nestStyle.stripHeight}px leader=${nestStyle.leader}",
            )
            if (drawn != overlap) wrong++
            if (expectOverlap && drawn == 0) wrong++
            if (!expectOverlap && drawn != 0) wrong++
        }
        if (wrong == 0 && expectOverlap) {
            rep.pass(
                "TITLED_BORDER's line is drawn inside the child's bounds and the leader extends by exactly that " +
                    "much — the 1a‴ eye defect, closed as a number",
            )
        } else if (wrong == 0) {
            rep.pass(
                "${nodeStyle.chrome} draws its line on its bounds edge, so the leader needs no overlap and is " +
                    "exact for free — the 'safe fix' the punch list offered, confirmed on the real chrome",
            )
        } else {
            rep.fail(
                "leader landing",
                "$wrong mismatch(es) between the chrome's drawn offset and the leader's overlap, or an offset " +
                    "that contradicts the chrome — the leader will stop short of the frame the reader sees",
            )
        }
        rep.info(
            "switch chrome on the toolbar and re-run: the numbers here should move to 0 for HEADER_STRIP, PLAIN " +
                "and NONE, and back to a positive offset for TITLED_BORDER",
        )
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
     * Where each node opens — **an observation, not an assertion, until §1a⁵ 6 lands.**
     *
     * 1a⁗ made nodes open on their member by *eliding* the context above it, and asserted first-visible-line ==
     * member. The round accepted the effect in a nested node and rejected it in the root ("landing 'member' is
     * better on subeditors, but worse on top editor"), and named a different mechanism: cap a nested child to the
     * height of the excerpt of interest and let it **scroll** to the member, leaving `keep` as authored. The
     * cleanup pass removed the elision version, so there is nothing to assert against yet — and reporting a PASS
     * off a spec field while no mechanism is running would be exactly the kind of green report this project has
     * learned not to trust.
     *
     * What stays is the measurement, because it is mechanism-independent: [CodeNodeView.firstVisibleKeptLine]
     * reads the folding model, so it will score the scroll version without a change. The old check's SKIP also
     * carried a defect of its own — the SKIP read "one range each, **or** landing is off", two causes in one
     * message, and the round hit the second while the message suggested the first. Retired with the branch rather
     * than fixed.
     */
    private fun reportLanding(rep: Report) {
        rep.section("Where each node opens (§1a⁵ 5 — measured, not yet asserted)")
        val all = nodes()
        if (all.isEmpty()) {
            rep.skip("landing", "no nodes were built in this case")
            return
        }
        for (node in all) {
            val keep = node.spec.keep.joinToString(",") { "${it.first}-${it.last}" }
            val member = node.spec.keep.sortedBy { it.first }.getOrNull(1)?.first
            rep.line(
                "── ${node.spec.title}: keep=$keep member≈${member ?: "-"} " +
                    "firstVisible=${node.firstVisibleKeptLine() ?: "-"} " +
                    "content=${node.contentHeight()}px cap=${if (nodeStyle.grow) "grow" else "${nodeStyle.maxHeight}px"} " +
                    "scrolls=${node.scrolls()}",
            )
        }
        rep.info(
            "§1a⁵ 5 will assert firstVisible == the member on every NESTED node and the first kept line on the " +
                "root. `scrolls=false` on all of them is constraint 19 — a node sized to its content has no " +
                "viewport to move, so the cap is what creates the scroll room rather than merely limiting height",
        )
    }

    /**
     * Does the mouse reach a nested editor — and does the leader strip get the click that collapses it?
     *
     * The objective half of "the wheel over the child scrolls the child". 1a″'s equivalent question cost the
     * whole tree when it was wrong (`contains()` on the overlay), and it is exactly as invisible here: an
     * embedded component the platform positions but does not hit-test would look like a picture of an editor.
     */
    private fun reportMouseReach(rep: Report) {
        rep.section("Does the mouse reach the nested editor, and the leader?")
        val ns = nest?.nestings?.filter { it.state == EmbedState.EMBEDDED }
        if (ns.isNullOrEmpty()) {
            rep.skip("mouse reach", "nothing embedded — no edge in this case resolved a call-site line")
            return
        }
        var childOk = 0
        var stripOk = 0
        var measured = 0
        var overlapOk = 0
        var overlapMeasured = 0
        for (n in ns) {
            val hostContent = n.parent.editor.contentComponent
            val childComp = n.child.editor.contentComponent
            if (childComp.width <= 0 || childComp.height <= 0 || !childComp.isShowing) {
                rep.line("── ${n.child.spec.title}: not laid out or not showing (${childComp.width}x${childComp.height}) — skipped")
                continue
            }
            measured++
            val inChild = SwingUtilities.convertPoint(
                childComp, Point(childComp.width / 2, childComp.height / 2), hostContent,
            )
            val deepestInChild = SwingUtilities.getDeepestComponentAt(hostContent, inChild.x, inChild.y)
            val reachesChild = deepestInChild != null && SwingUtilities.isDescendingFrom(deepestInChild, n.child)

            val fb = n.frameBoundsIn(hostContent)
            val deepestInStrip = fb?.let {
                SwingUtilities.getDeepestComponentAt(hostContent, it.x + it.width / 2, it.y + n.stripHeight() / 2)
            }
            val reachesStrip = deepestInStrip != null &&
                !SwingUtilities.isDescendingFrom(deepestInStrip, n.child) &&
                deepestInStrip !== hostContent

            // The rows the strip **overlaps into** still belong to the child. With the default `contains` the strip
            // takes them and the nested editor is dead at the top — 1a″'s `contains()` trap one layer down, and
            // just as silent. Only measurable where there is an overlap, i.e. on TITLED_BORDER.
            val overlap = n.leaderOverlap()
            val inOverlap = if (overlap > 0 && fb != null) {
                SwingUtilities.getDeepestComponentAt(
                    hostContent, fb.x + fb.width / 2, fb.y + n.stripHeight() + overlap / 2,
                )
            } else {
                null
            }
            val overlapReachesChild = inOverlap != null && SwingUtilities.isDescendingFrom(inOverlap, n.child)
            if (overlap > 0 && fb != null) {
                overlapMeasured++
                if (overlapReachesChild) overlapOk++
            }

            rep.line(
                "── ${n.child.spec.title}: at the child's centre → ${deepestInChild?.javaClass?.simpleName} " +
                    "(reaches child=$reachesChild); at the strip → ${deepestInStrip?.javaClass?.simpleName} " +
                    "(reaches leader=$reachesStrip); in the ${overlap}px overlap band → " +
                    "${inOverlap?.javaClass?.simpleName ?: "n/a"} (reaches child=$overlapReachesChild)",
            )
            if (reachesChild) childOk++
            if (reachesStrip) stripOk++
        }
        if (measured == 0) {
            rep.skip("mouse reach", "no nested child is laid out and showing yet")
            return
        }
        if (overlapMeasured == 0) {
            rep.skip(
                "overlap band",
                "this chrome (${nodeStyle.chrome}) draws its line on its bounds edge, so the leader needs no " +
                    "overlap and there is no band to pass through — switch chrome: titled_border and re-run",
            )
        } else if (overlapOk == overlapMeasured) {
            rep.pass(
                "the rows the leader strip reaches into still belong to the nested editor's mouse — the strip's " +
                    "contains() claims only its own band",
            )
        } else {
            rep.fail(
                "overlap band",
                "${overlapMeasured - overlapOk} of $overlapMeasured overlap bands are taken by the leader strip, " +
                    "so the nested editor is dead in its top rows — this is 1a″'s contains() defect one layer down",
            )
        }
        if (childOk == measured) {
            rep.pass("a click or wheel event at a nested editor's centre lands inside that editor, not on the host")
        } else {
            rep.fail(
                "mouse reach",
                "${measured - childOk} of $measured nested editors do not receive events at their own centre — " +
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
