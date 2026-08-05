package com.audioexplainer.spike

import com.audioexplainer.Segment
import com.audioexplainer.tree.CodeNodeSpec
import com.audioexplainer.tree.CodeNodeStyle
import com.audioexplainer.tree.CodeNodeView
import com.audioexplainer.tree.NodeChrome
import com.audioexplainer.tree.NodeDeemphasis
import com.audioexplainer.tree.NodeElision
import com.audioexplainer.tree.NodePath
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
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * **Phase 1a′ spike vehicle** for `TreeOfCode.md` — an interactive bench for the real
 * [CodeNodeView], over the real `fixtures/TreeSpike` solution.
 *
 * Unlike the Phase 0/0b probes this one is **driven by hand**, and that is the point rather than a shortcut.
 * 1a′'s open questions divide into two kinds and the vehicle has to serve both:
 *
 * - *measurable* — do our folds survive, does the height resync fire and on which trigger, does a node scroll
 *   at the 600 px cap, does semantic colour still arrive, do N editors come back on teardown. Those go in
 *   `report.txt` with PNGs, exactly as before.
 * - *judgeable only by eye* — which chrome reads as a node, whether a scrolled-away active range is worse
 *   than a tall node, whether the callout fits at node width. Those need a human clicking, so every
 *   alternative in the 1c list is a button here rather than a constant to edit and rebuild for.
 *
 * Deliberately **not** the 1a″ tree container: nodes are stacked and indented by depth with no overlay and no
 * connectors. Anything that looks like a missing connector here is missing on purpose.
 *
 * THROWAWAY with the rest of `com.audioexplainer.spike`. Output: `<project>/scratchpad/spike-nodeview/`.
 */
class SpikeNodeViewAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        NodeViewRun(project).open()
    }
}

/** One node of a spike case, anchored by text so a fixture edit cannot silently rot it (§4.4 rule 2). */
internal class SpikeNodePlan(
    val fileName: String,
    val title: String,
    val depth: Int,
    /** (anchor text, how many further lines to keep after the anchor line). */
    val anchors: List<Pair<String, Int>>,
    /**
     * Text of the call into *this* node, in its **parent's** file — the nesting edge's anchor.
     *
     * The call site is carried per *edge*, never per node: a parent can have several children, and the `wide` case
     * has one `Dispatch` calling three handlers from three consecutive lines. An outgoing anchor on the parent
     * (which this class had, as `linkAnchor`, for the 1a′ bench) cannot say which line goes with which child, so it
     * went in the cleanup pass with `CodeNodeSpec.linkFrom`. Phase 2's schema should know the same — decision 1.
     */
    val callSiteAnchor: String? = null,
)

/** The three fixture cases `TreeOfCode.md` 1b says the prototype has to show. */
internal class SpikeCase(val name: String, val nodes: List<SpikeNodePlan>)

/** Which node, and which highlight inside it, a step is on. The flattened `stepIndex` Phase 3 will adopt. */
internal class StepRef(val nodeIndex: Int, val highlightIndex: Int)

internal class NodeViewRun(private val project: Project) {

    private val log = Logger.getInstance(NodeViewRun::class.java)
    private val out: Path = outDir(project)

    private var tabFile: SpikeTreeVirtualFile? = null
    private var bench: NodeViewBench? = null
    private val editorsAtStart = EditorFactory.getInstance().allEditors.size

    fun open() {
        // The VFS walk is disk I/O; the platform asserts against slow operations on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = findFixtureFiles()
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                try {
                    openBench(found)
                } catch (t: Throwable) {
                    log.warn("node-view bench failed to open", t)
                    notify("Spike 1a′ failed to open", "${t.javaClass.simpleName}: ${t.message}", NotificationType.ERROR)
                }
            }
        }
    }

    private fun openBench(found: Map<String, VirtualFile>) {
        val missing = CASES.flatMap { it.nodes }.map { it.fileName }.distinct().filterNot { found.containsKey(it) }
        if (missing.isNotEmpty()) {
            notify(
                "Spike 1a′ skipped",
                "Fixture files not found under the project root: ${missing.joinToString(", ")}.<br/>" +
                    "Open <b>fixtures/TreeSpike/TreeSpike.sln</b> as the sandbox project — a node view is a view " +
                    "of a real document in a loaded solution, and nothing here means anything without one.",
                NotificationType.WARNING,
            )
            return
        }

        val b = NodeViewBench(project, found, out, editorsAtStart) { notify(it.first, it.second, it.third) }
        bench = b
        val file = SpikeTreeVirtualFile(
            "Node view bench (1a′)",
            componentSupplier = { b },
            onDispose = {
                b.releaseAll()
                bench = null
                tabFile = null
            },
        )
        tabFile = file
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Audio Explainer")
            .createNotification(title, content, type)
            .notify(project)
    }

    /** Pooled thread only. Finds the fixture's files by name anywhere under the project root. */
    private fun findFixtureFiles(): Map<String, VirtualFile> =
        findFixtureFilesByName(project, CASES.flatMap { it.nodes }.map { it.fileName }.toHashSet())

    internal companion object {
        /** Same anchors as the Phase 0b probes, so a difference in what is shown is a difference in the component. */
        val CASES: List<SpikeCase> = listOf(
            SpikeCase(
                "chain",
                listOf(
                    SpikeNodePlan(
                        "MainInvoker.cs", "MainLoop", 0,
                        listOf("public static class MainInvoker" to 0, "public static Money MainLoop()" to 19),
                    ),
                    SpikeNodePlan(
                        "Invokers.cs", "MidInvoker.InvokeOn", 1,
                        listOf("public static class MidInvoker" to 0, "public static Money InvokeOn(" to 17),
                        callSiteAnchor = "MidInvoker.InvokeOn(subtotal, someParam, Options)",
                    ),
                    SpikeNodePlan(
                        "SubInvoker.cs", "SubInvoker.Apply", 2,
                        listOf("public static class SubInvoker" to 0, "public static Money Apply(" to 20),
                        callSiteAnchor = "SubInvoker.Apply(normalised",
                    ),
                ),
            ),
            // Two nodes over ONE document — legal per constraint 2, and the case that makes BASENAME chrome
            // ambiguous on purpose.
            SpikeCase(
                "same-file",
                listOf(
                    SpikeNodePlan(
                        "Invokers.cs", "MidInvoker.InvokeOn", 0,
                        listOf("public static class MidInvoker" to 0, "public static Money InvokeOn(" to 17),
                    ),
                    SpikeNodePlan(
                        "Invokers.cs", "MidInvoker.Normalize", 1,
                        listOf("public static class MidInvoker" to 0, "public static Money Normalize(" to 14),
                        callSiteAnchor = "Normalize(amount, scaleFactor)",
                    ),
                ),
            ),
            // One parent, three children, three short files — the case 1c needs to judge grow-to-content
            // against a cap on nodes that actually differ in size.
            SpikeCase(
                "wide",
                listOf(
                    SpikeNodePlan(
                        "Dispatcher.cs", "Dispatch", 0,
                        listOf("public sealed class Dispatcher" to 0, "public DispatchResult Dispatch(" to 9),
                    ),
                    // Three children, three DIFFERENT call-site lines in the one parent — which is why the
                    // 1a″ container carries the call site on the edge and not on the node.
                    SpikeNodePlan(
                        "ValidationHandler.cs", "ValidationHandler.Handle", 1,
                        listOf("public sealed class ValidationHandler" to 0, "public Verdict Handle(" to 8),
                        callSiteAnchor = "_validation.Handle(order)",
                    ),
                    SpikeNodePlan(
                        "PricingHandler.cs", "PricingHandler.Handle", 1,
                        listOf("public sealed class PricingHandler" to 0, "public Money Handle(" to 12),
                        callSiteAnchor = "_pricing.Handle(order, verdict)",
                    ),
                    SpikeNodePlan(
                        "AuditHandler.cs", "AuditHandler.Handle", 1,
                        listOf("public sealed class AuditHandler" to 0, "public IReadOnlyList<string> Handle(" to 14),
                        callSiteAnchor = "_audit.Handle(order, priced)",
                    ),
                ),
            ),
        )

        fun outDir(project: Project): Path {
            System.getProperty("audioexplainer.spike.out")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
            val base = project.basePath ?: System.getProperty("java.io.tmpdir")
            return Paths.get(base, "scratchpad", "spike-nodeview")
        }
    }
}

/**
 * The bench itself: a toolbar of every 1c alternative, a status line of every 1a′ measurement, and the nodes.
 *
 * Every button is one of the plan's open questions made clickable. The status line is the falsifiable half —
 * in particular the resync counters, which are how "the `FoldingListener` fired" is told apart from "the
 * height happened to be right anyway".
 */
internal class NodeViewBench(
    private val project: Project,
    private val files: Map<String, VirtualFile>,
    private val out: Path,
    private val editorsAtStart: Int,
    private val notify: (Triple<String, String, NotificationType>) -> Unit,
) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(NodeViewBench::class.java)

    private val status = JBLabel(" ")
    private val stack = NodeStack()
    private val scroll = JBScrollPane(stack)

    private var caseIndex = 0
    private var style = CodeNodeStyle()
    private val nodes = mutableListOf<CodeNodeView>()
    private val segments = mutableListOf<Segment>()
    private val steps = mutableListOf<StepRef>()
    private var stepIndex = 0
    private var focused: CodeNodeView? = null
    private var reportCount = 0

    init {
        add(toolbar(), BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(status.apply { border = BorderFactory.createEmptyBorder(4, 8, 4, 8) }, BorderLayout.SOUTH)
        buildCase()
    }

    // ────────────────────────────── toolbar ──────────────────────────────

    private fun toolbar(): JComponent {
        val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        rows.add(
            row(
                button("◂ step") { step(-1) },
                button("step ▸") { step(+1) },
                button("case: chain") { cycleCase(it) },
                button("size: cap 600") { toggleGrow(it) },
                button("elision: openable") { cycleElision(it) },
            ),
        )
        rows.add(
            row(
                button("chrome: header_strip") { cycleChrome(it) },
                button("path: relative") { cyclePath(it) },
                button("dim: tint") { cycleDeemphasis(it) },
                button("font −") { fontDelta(-1) },
                button("font +") { fontDelta(+1) },
                button("write report") { writeReport() },
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

    // ────────────────────────────── case build / teardown ──────────────────────────────

    private fun buildCase() {
        releaseNodes()
        val case = NodeViewRun.CASES[caseIndex]
        for (plan in case.nodes) {
            val file = files[plan.fileName] ?: continue
            // Document access and the anchor scan both read the file model, and this runs on the EDT — the
            // platform's soft read-access assert logs a SEVERE without failing anything, so it is invisible
            // until someone reads idea.log. It did, and this is the fix.
            val spec = ReadAction.compute<CodeNodeSpec?, RuntimeException> {
                val doc = FileDocumentManager.getInstance().getDocument(file)
                    ?: return@compute null
                val text = doc.charsSequence
                val keep = plan.anchors.mapNotNull { (anchor, extra) ->
                    lineOfAnchor(text, anchor)?.let { it..(it + extra) }
                }
                if (keep.isEmpty()) return@compute null
                CodeNodeSpec(file, plan.title, plan.depth, keep)
            }
            if (spec == null) {
                log.warn("no document or no anchor resolved in ${plan.fileName}; node skipped")
                continue
            }
            val node = CodeNodeView(project, spec, style)
            node.onFocusGained = { focused = it; refreshStatus() }
            node.onRevealRequest = { it.scrollNodeIntoView() }
            // A theme switch invalidates the step decorations, and only the owner knows which step is showing.
            node.onRerenderNeeded = { applyStep(enteringNode = true) }
            nodes += node
            segments += syntheticSegment(spec)
        }
        stack.setNodes(nodes)
        steps.clear()
        for ((i, seg) in segments.withIndex()) {
            for (h in seg.highlights.indices) steps += StepRef(i, h)
        }
        stepIndex = 0
        applyStep(enteringNode = true)
    }

    private fun releaseNodes() {
        nodes.forEach {
            try {
                it.dispose()
            } catch (_: Throwable) {
                // Already released.
            }
        }
        nodes.clear()
        segments.clear()
        focused = null
    }

    fun releaseAll() {
        releaseNodes()
        // Read late enough that the tab's own teardown has run — the count is the whole point of the check.
        val after = EditorFactory.getInstance().allEditors.size
        log.info("1a′ bench closed: getAllEditors() $editorsAtStart → $after")
        if (after > editorsAtStart) {
            notify(
                Triple(
                    "Spike 1a′: node editors leaked",
                    "${after - editorsAtStart} editor(s) still alive after the bench closed — Phase 4's disposal audit starts here.",
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

    /**
     * Render the current step, mirroring `EditorHighlighter`'s two entry points so both renderer paths are
     * exercised: entering a node is `spotlightSegment` + `spotlightStep` (its `onSegment`), moving inside one
     * is `setSegmentBounds` + `spotlightStep` (its `onHighlight`).
     */
    private fun applyStep(enteringNode: Boolean) {
        if (steps.isEmpty()) {
            refreshStatus()
            return
        }
        val ref = steps[stepIndex]
        for ((i, node) in nodes.withIndex()) {
            val active = i == ref.nodeIndex
            node.setActive(active)
            if (!active) node.spotlight.clear()
        }
        val node = nodes.getOrNull(ref.nodeIndex) ?: return
        val seg = segments.getOrNull(ref.nodeIndex) ?: return
        if (enteringNode) {
            node.spotlight.spotlightSegment(seg.start, seg.end)
        } else {
            node.spotlight.setSegmentBounds(seg.start, seg.end)
        }
        node.spotlight.spotlightStep(seg, ref.highlightIndex)
        // The renderer scrolled the node's own editor; nothing has brought the node into the bench's
        // viewport, which is exactly the gap `CodeNodeView.onRevealRequest` exists to close.
        node.scrollNodeIntoView()
        refreshStatus()
    }

    // ────────────────────────────── alternatives ──────────────────────────────

    private fun cycleCase(b: JButton) {
        caseIndex = (caseIndex + 1) % NodeViewRun.CASES.size
        b.text = "case: ${NodeViewRun.CASES[caseIndex].name}"
        buildCase()
    }

    private fun toggleGrow(b: JButton) {
        restyle(style.with(grow = !style.grow))
        b.text = if (style.grow) "size: grow" else "size: cap ${style.maxHeight}"
    }

    private fun cycleElision(b: JButton) {
        val next = if (style.elision == NodeElision.OPENABLE) NodeElision.SEALED else NodeElision.OPENABLE
        restyle(style.with(elision = next))
        b.text = "elision: ${next.name.lowercase()}"
    }

    private fun cycleChrome(b: JButton) {
        val values = NodeChrome.entries
        val next = values[(values.indexOf(style.chrome) + 1) % values.size]
        restyle(style.with(chrome = next))
        b.text = "chrome: ${next.name.lowercase()}"
    }

    private fun cyclePath(b: JButton) {
        val values = NodePath.entries
        val next = values[(values.indexOf(style.path) + 1) % values.size]
        restyle(style.with(path = next))
        b.text = "path: ${next.name.lowercase()}"
    }

    private fun cycleDeemphasis(b: JButton) {
        val values = NodeDeemphasis.entries
        val next = values[(values.indexOf(style.deemphasis) + 1) % values.size]
        restyle(style.with(deemphasis = next))
        b.text = "dim: ${next.name.lowercase()}"
    }

    private fun restyle(newStyle: CodeNodeStyle) {
        style = newStyle
        nodes.forEach { it.restyle(newStyle) }
        // A restyle can drop the decorations' surroundings (elision changes what is folded), so re-render the
        // step rather than leaving a half-lit node.
        applyStep(enteringNode = true)
        stack.revalidate()
        stack.repaint()
    }

    /**
     * Change every node's editor font size — the trigger a `FoldingListener` cannot see. If the status line's
     * `font=` counter moves and the heights follow, the second half of the resync story holds.
     */
    private fun fontDelta(delta: Int) {
        for (node in nodes) {
            val size = node.editor.colorsScheme.editorFontSize + delta
            node.editor.setFontSize(size.coerceIn(6, 40))
        }
        stack.revalidate()
        stack.repaint()
        refreshStatus()
    }

    // ────────────────────────────── status + report ──────────────────────────────

    private fun refreshStatus() {
        val ref = steps.getOrNull(stepIndex)
        val heights = nodes.joinToString(", ") { "${it.contentHeight()}${if (it.scrolls()) "↕" else ""}" }
        val resync = nodes.firstOrNull()?.resyncReport() ?: "-"
        status.text = buildString {
            append("step ${if (steps.isEmpty()) 0 else stepIndex + 1}/${steps.size}")
            append("  ·  active: ")
            append(ref?.let { nodes.getOrNull(it.nodeIndex)?.spec?.title } ?: "-")
            append("  ·  focus: ").append(focused?.spec?.title ?: "<none>")
            append("  ·  heights: ").append(heights)
            append(if (style.grow) " (grow)" else " (cap ${style.maxHeight}; ↕ = own scrollbar)")
            append("  ·  folds: ")
            append(nodes.joinToString(",") { "${it.survivingFolds()}/${it.requestedFolds()}+${it.foreignFolds()}f" })
            append("  ·  resync[node 1]: ").append(resync)
        }
    }

    /**
     * Everything a human cannot judge by looking, written to disk: fold survival, heights, semantic-colour
     * classification per distinct document, resync counters per trigger, and PNGs of the bench and each node.
     */
    private fun writeReport() {
        val name = if (reportCount == 0) "report.txt" else "report-${reportCount}.txt"
        reportCount++
        val rep = Report(out.resolve(name))
        rep.title("Phase 1a′ — CodeNodeView bench")
        rep.line("IDE build : ${ApplicationInfo.getInstance().build.asString()} (${ApplicationInfo.getInstance().fullVersion})")
        rep.line("project   : ${project.name}  base=${project.basePath}")
        rep.line("case      : ${NodeViewRun.CASES[caseIndex].name}  (${nodes.size} node(s))")
        rep.line(
            "style     : chrome=${style.chrome} path=${style.path} elision=${style.elision} " +
                "dim=${style.deemphasis} maxHeight=${style.maxHeight} grow=${style.grow}",
        )
        rep.line("step      : ${if (steps.isEmpty()) 0 else stepIndex + 1}/${steps.size}")
        rep.line("focus     : ${focused?.spec?.title ?: "<none>"}")
        rep.blank()

        rep.section("Per node")
        for (node in nodes) {
            rep.line("── ${node.spec.title}  (${node.spec.file.name}, depth ${node.spec.depth})")
            rep.line("   keep lines      : ${node.spec.keep.joinToString(", ") { "${it.first}-${it.last}" }}")
            rep.line(
                "   folds           : ${node.survivingFolds()}/${node.requestedFolds()} ours still collapsed, " +
                    "${node.foreignFolds()} foreign",
            )
            rep.line(
                "   height          : folded=${node.contentHeight()}px unfolded=${node.unfoldedContentHeight()}px " +
                    "cap=${style.maxHeight}px scrolls=${node.scrolls()}",
            )
            rep.line("   component       : ${node.width}x${node.height}px, preferred ${node.preferredSize.height}px")
            rep.line("   resync          : ${node.resyncReport()}")
            rep.line("   inlays          : ${inlayCount(node)} block inlay(s) — the docked callout at node width")
            if (node.survivingFolds() < node.requestedFolds()) {
                rep.fail(
                    "fold survival on ${node.spec.title}",
                    "${node.requestedFolds() - node.survivingFolds()} of our regions are gone or re-expanded — " +
                        "constraint 5 says the daemon only ADDS, so this would be new",
                )
            }
        }

        rep.section("Semantic colour per distinct document (constraint 3)")
        val seen = HashSet<String>()
        var withSemantic = 0
        for (node in nodes) {
            if (!seen.add(node.spec.file.path)) continue
            if (classifyMarkersTo(project, node.editor.document, node.spec.file.name, rep) > 0) withSemantic++
        }
        if (seen.isNotEmpty() && withSemantic == seen.size) {
            rep.pass("every distinct document carries ReSharper.* colour categories in a CodeNodeView")
        } else {
            rep.info(
                "$withSemantic of ${seen.size} document(s) carry ReSharper.* colour categories — if this is 0, " +
                    "check the solution finished loading before blaming the component",
            )
        }

        rep.section("Captures")
        captureComponent(this, out, "bench-${NodeViewRun.CASES[caseIndex].name}.png", rep)
        for (node in nodes) {
            captureComponent(node, out, "node-${node.spec.title.replace('.', '_')}.png", rep)
        }

        rep.section("Editors")
        rep.line("getAllEditors(): $editorsAtStart at bench open, ${EditorFactory.getInstance().allEditors.size} now")

        rep.section("End")
        rep.line(rep.summary())
        rep.flush()
        notify(
            Triple(
                "Spike 1a′ report written",
                "${rep.summary()}<br/>$out",
                if (rep.failures == 0) NotificationType.INFORMATION else NotificationType.WARNING,
            ),
        )
    }

    private fun inlayCount(node: CodeNodeView): Int = try {
        node.editor.inlayModel.getBlockElementsInRange(0, node.editor.document.textLength).size
    } catch (_: Throwable) {
        -1
    }

    /**
     * Nodes stacked and indented by depth. **Not** the 1a″ container: no overlay, no connectors, no
     * `JLayeredPane`. Its only job is to give each node a width and a slot so the node itself can be judged.
     */
    private class NodeStack : JPanel() {

        private var nodes: List<CodeNodeView> = emptyList()

        init {
            layout = null
            isOpaque = true
        }

        fun setNodes(next: List<CodeNodeView>) {
            removeAll()
            nodes = next
            next.forEach { add(it) }
            revalidate()
            repaint()
        }

        override fun doLayout() {
            var y = GAP
            for (node in nodes) {
                val x = INSET + node.spec.depth * INDENT_PX
                val w = (width - x - INSET).coerceAtLeast(120)
                val h = node.preferredSize.height
                node.setBounds(x, y, w, h)
                y += h + GAP
            }
        }

        override fun getPreferredSize(): Dimension {
            var y = GAP
            var right = 0
            for (node in nodes) {
                y += node.preferredSize.height + GAP
                right = maxOf(right, INSET + node.spec.depth * INDENT_PX + MIN_NODE_W)
            }
            return Dimension(right, y)
        }

        private companion object {
            const val GAP = 12
            const val INSET = 10

            /** Phase 0 measured 4 text spaces per depth far too weak; component indentation is a different mechanism. */
            const val INDENT_PX = 28
            const val MIN_NODE_W = 640
        }
    }
}
