package com.audioexplainer.tree

import com.audioexplainer.Endpoint
import com.audioexplainer.SpotlightRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayModel
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.geom.Rectangle2D
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.border.AbstractBorder
import kotlin.math.roundToInt

/**
 * One node of a tree-of-code view: **a real document, one viewer editor over it, chrome around it.**
 *
 * Phase 1a′ of `TreeOfCode.md`. This is the single component the tree container, the schema renderer and the
 * narration attention logic all compose, so everything that is *per node* lives here and nothing that is
 * *per tree* does — no connectors, no overlay, no layout by depth ([CodeNodeSpec.depth] is carried for the
 * container's benefit and never read here).
 *
 * Four of the plan's measured constraints are structural to this class rather than advice to its callers:
 *
 * - **Constraint 3 — semantic colour follows the file being real on disk.** The editor is built with
 *   `createViewer(FileDocumentManager.getDocument(realFile), project, EditorKind.PREVIEW)`, the exact
 *   configuration that scored 78–321 ReSharper colour markers where a `LightVirtualFile` scored **zero**.
 *   That is why a node is a view of a live document and not of a copied excerpt, and why this class refuses
 *   to construct without a document.
 * - **Constraint 2 — decorations are per editor, not per document.** Hence one [spotlight] per node,
 *   constructed here. Two nodes over one file are two editors with two independent decoration sets, which is
 *   what makes the same-file case (caller and callee in one file) legal at all.
 * - **Constraint 5 — a node does not own its folding model.** The daemon pushes its own regions in after
 *   ours; it *adds* rather than replaces, so [applyElision] does not re-assert in a loop. What it does do is
 *   listen, because a foreign fold changes our height ([resync]).
 * - **Constraint 1 — de-emphasis acts on the background, never the foreground.** [setActive] tints and
 *   changes border contrast. A foreground dim would flatten exactly the semantic colour constraint 3 is
 *   about, which is what made the Phase 0 prototype read as plain text.
 *
 * **EDT only**, like [SpotlightRenderer]: every method mutates editor or Swing state and none of them
 * marshals.
 *
 * **Disposal is the caller's job**: register this with a parent `Disposable` (a `FileEditor`, a dialog) or
 * call [dispose] directly. It releases the editor, and a leaked editor holds a document and its markup —
 * Phase 4's stated main integration risk, which is why [dispose] is idempotent and never throws.
 */
class CodeNodeView(
    private val project: Project,
    val spec: CodeNodeSpec,
    style: CodeNodeStyle = CodeNodeStyle(),
    navigate: (Endpoint) -> Unit = {},
) : JPanel(null), Disposable {

    private val log = Logger.getInstance(CodeNodeView::class.java)

    val editor: EditorEx

    /** This node's decoration set. One per editor, per constraint 2 — see [SpotlightRenderer]'s own KDoc. */
    val spotlight: SpotlightRenderer

    var style: CodeNodeStyle = style
        private set

    /**
     * True when this node is the one being talked about. Phase 3 decides the visual language; the mechanism
     * has to exist here because the node owns both its markup model and its border.
     */
    var isActive: Boolean = false
        private set

    /**
     * Notified when this node takes keyboard focus, so an owner of N nodes can answer "which one has it".
     *
     * With N editors on screen there are N carets and N focus targets, and neither the platform nor
     * [SpotlightRenderer] arbitrates between them — the flip to Arch B is what created the question. The
     * policy implemented here: **a non-focused node has its caret switched off entirely**
     * ([EditorEx.setCaretEnabled]), so `revealCenter`'s unconditional caret move leaves nothing blinking,
     * and the node that the user clicks into gets a visible caret and reports itself here.
     */
    var onFocusGained: ((CodeNodeView) -> Unit)? = null

    /**
     * "This node wants to be visible in the container's viewport."
     *
     * [SpotlightRenderer.spotlightStep] ends in a `revealCenter`, which scrolls **this editor**. Inside a
     * capped node that is exactly right; inside a grow-to-content node there is nothing to scroll, and in
     * neither case does anything bring the *node* into the tree's own scroll pane. The renderer cannot know
     * — it has an editor and no container — so the node asks, and the owner obliges. [reveal] fires it.
     */
    var onRevealRequest: ((CodeNodeView) -> Unit)? = null

    /**
     * "The colours this node was drawn with are stale — re-render the current step."
     *
     * A node can refresh what it owns (its tint, its chrome) but not what [SpotlightRenderer] drew: that
     * palette is computed *per render* from the scheme and the settings, so after a theme switch the glow,
     * the dim roles and the callout box are all still the old scheme's colours until something renders again,
     * and only the owner knows which step is showing. Measured in the 1a′ bench: flipping dark→light left
     * dark tints and a white callout font on screen until a repaint was forced by hand.
     *
     * The in-place walkthrough has the same hole and closes it the same way — `EditorHighlighter` subscribes
     * to the scheme topic and calls its own `refresh()`.
     *
     * Fires **once per node**, because each node has its own subscription. An owner of N nodes that re-renders
     * the whole tree in response will do it N times for one theme switch (measured: three identical renders in
     * the same millisecond band). Harmless at N=3, worth coalescing before N is large.
     */
    var onRerenderNeeded: ((CodeNodeView) -> Unit)? = null

    /**
     * "Anything anchored to a line of this node is now in the wrong place."
     *
     * Added for the 1a″ tree container, which draws connectors between line anchors in *different* nodes and
     * therefore cares about a class of change [onRerenderNeeded] does not cover: a node scrolling **inside
     * itself** moves every anchor in it and changes no colour, no height and no step. So does a fold, an inlay
     * appearing, and a font change.
     *
     * Fires for all of those, and deliberately **more often than the height changes** — [resync] coalesces on
     * the published height because a layout pass is expensive, but a connector is stale the moment the anchor
     * moves whether the node's outline moved or not.
     */
    var onGeometryChanged: (() -> Unit)? = null

    /** Fold regions this view installed. Kept so the owner can ask whether they are still ours (constraint 5). */
    private val installedFolds = mutableListOf<FoldRegion>()

    /** The node-level background tint of [setActive]; outside [spotlight]'s tracked set, so a step keeps it. */
    private var emphasisTint: RangeHighlighter? = null

    private var headerStrip: JComponent? = null

    /**
     * A small control the owner puts in this node's **header band** — 1a⁗'s single collapse affordance.
     *
     * The node owns the position because the node owns the chrome, and the two are the same question: a titled
     * border's caption row, a header strip's trailing edge and a `PLAIN`/`NONE` node's top corner are three
     * different y's for one control. What it *does* is the owner's business ([CodeNestView] collapses a nesting
     * with it); where it goes is not.
     *
     * Measured in `spikes/tree1a4/`: such a control is hit-testable three components deep inside an embedded
     * component **only** if it is z-lifted above the editor's own component — behind it, it is drawn and
     * unreachable, which is verdict 4's failure mode ("reads as broken when it is merely invisible").
     */
    private var cornerControl: JComponent? = null


    /** Last height [resync] published, so an event that changes nothing costs no layout pass. */
    private var lastPublishedHeight = -1

    /** Last computed content height, kept only so [contentHeight] can answer after the editor is released. */
    private var lastContentHeight = -1

    private val resyncStats = LinkedHashMap<String, IntArray>()

    private var disposed = false

    /** Last width a resize resync was scheduled for, so a layout storm costs one resync and not one per pass. */
    private var lastResyncedWidth = -1

    private var widthResyncPending = false

    /**
     * Parent of every listener this view installs, disposed by [dispose].
     *
     * `this` is a [Disposable] and would look like the natural parent — but only the Disposer tree calls
     * [dispose], and an owner is equally entitled to call it directly (the 1a′ bench does). Measured: with
     * the listeners parented to `this`, closing a node left the application-level scheme subscription alive,
     * and the next theme switch drove `resync` into `offsetToXY` on a released editor —
     * `SEVERE MessageBusImpl - Editor is already disposed`, once per node ever built. An explicitly disposed
     * child makes teardown independent of *how* [dispose] was reached.
     */
    private val listeners = Disposer.newDisposable("CodeNodeView")

    init {
        // A read action, because `getDocument` reads the file model and this runs on the EDT: the platform's
        // `softAssertReadAccess` logs a SEVERE without failing, so the omission is invisible until the log is
        // read. Measured here, from the 1a′ bench.
        val document = ReadAction.compute<Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(spec.file)
        } ?: error("no document for ${spec.file.path} — a node view needs a real file (constraint 3)")

        editor = EditorFactory.getInstance().createViewer(document, project, EditorKind.PREVIEW) as EditorEx
        editor.settings.apply {
            // The document IS the file, so the gutter shows real line numbers with no converter — one of the
            // things the flip to Arch B bought outright, and the reason no LineNumberConverter appears here.
            isLineNumbersShown = true
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = true
            isIndentGuidesShown = false
            isRightMarginShown = false
            isCaretRowShown = false
            isUseSoftWraps = false
            // A node is sized to its content; a phantom line past the end would be counted as content.
            additionalLinesCount = 0
            additionalColumnsCount = 1
        }
        editor.setHighlighter(EditorHighlighterFactory.getInstance().createEditorHighlighter(project, spec.file))
        editor.setHorizontalScrollbarVisible(false)
        // N carets, N focus owners: off until this node is focused. See [onFocusGained].
        editor.setCaretEnabled(false)
        editor.setCaretVisible(false)

        spotlight = SpotlightRenderer(editor, navigate)

        // Constraint 5: not a re-assert loop — a resync. The daemon's folds are additive, but they move where
        // our kept lines draw, so the node's height stops being what it last published.
        (editor.foldingModel as? FoldingModelEx)?.addListener(
            object : FoldingListener {
                override fun onFoldProcessingEnd() = resync(TRIGGER_FOLD)
            },
            listeners,
        )
        // The other two things that move `lineHeight`, both of which the fold listener never sees. Named
        // separately because the report distinguishes them: a resync count of fold=12 font=0 scheme=0 after a
        // theme change would mean this subscription is the broken one.
        editor.addPropertyChangeListener({ e ->
            if (e.propertyName == EditorEx.PROP_FONT_SIZE) resync(TRIGGER_FONT)
        }, listeners)
        ApplicationManager.getApplication().messageBus.connect(listeners)
            .subscribe(EditorColorsManager.TOPIC, EditorColorsListener { onSchemeChanged() })

        // The fourth thing that moves this node's height, and the one nothing else can see: a block inlay
        // arriving, leaving, or being re-measured. `SpotlightRenderer`'s docked callout is one, so **rendering a
        // step changes the node's height** — and the callout re-wraps when the node's width changes, which is
        // constraint 9's coupling arriving as an event rather than as a guess. Both 1a′ benches hid this by
        // revalidating their own container after every render; a caller that does not would have kept a stale
        // slot around a taller node.
        editor.inlayModel.addListener(
            object : InlayModel.Listener {
                override fun onAdded(inlay: Inlay<*>) = resync(TRIGGER_INLAY)
                override fun onUpdated(inlay: Inlay<*>) = resync(TRIGGER_INLAY)
                override fun onUpdated(inlay: Inlay<*>, changeFlags: Int) = resync(TRIGGER_INLAY)
                override fun onRemoved(inlay: Inlay<*>) = resync(TRIGGER_INLAY)
            },
            listeners,
        )
        // Scrolling inside a node changes no height and no colour, so it reaches neither resync nor a
        // re-render — but it moves every line anchor in the node, which is exactly what a connector is drawn
        // between. See [onGeometryChanged].
        editor.scrollingModel.addVisibleAreaListener(
            VisibleAreaListener { e: VisibleAreaEvent ->
                if (e.oldRectangle?.y != e.newRectangle.y) onGeometryChanged?.invoke()
            },
            listeners,
        )
        // Constraint 9 the other way round: the node's *width* is set by its container, and the callout's wrap
        // — hence the node's height — follows it. Deferred rather than immediate because the inlay is
        // re-measured after the resize, so a synchronous read here would publish the pre-resize height.
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = scheduleWidthResync()
        })

        editor.contentComponent.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                editor.setCaretEnabled(true)
                editor.setCaretVisible(true)
                onFocusGained?.invoke(this@CodeNodeView)
            }

            override fun focusLost(e: FocusEvent) {
                editor.setCaretEnabled(false)
                editor.setCaretVisible(false)
            }
        })

        // No layout manager, where 1a′/1a″ used a BorderLayout, for one reason: [cornerControl] has to sit *inside
        // the chrome's own header band*, and BorderLayout has no region for "floating in my top inset" — a second
        // component added without a constraint silently takes the editor's CENTER slot and leaves it 0×0. Three
        // children, positioned by hand in [doLayout].
        add(editor.component)
        rebuildChrome()
        applyElision()
    }

    /**
     * Header strip (if any), then the editor, then the corner control — all inside the chrome's insets.
     *
     * The control is placed last and z-lifted, because the probe measured that a control merely *positioned* over
     * the editor component is drawn and unclickable.
     */
    override fun doLayout() {
        val ins = insets
        var top = ins.top
        val innerWidth = (width - ins.left - ins.right).coerceAtLeast(0)
        headerStrip?.let { strip ->
            val h = strip.preferredSize.height
            strip.setBounds(ins.left, top, innerWidth, h)
            top += h
        }
        editor.component.setBounds(
            ins.left, top, innerWidth, (height - top - ins.bottom).coerceAtLeast(0),
        )
        cornerControl?.let { c ->
            val size = c.preferredSize
            val band = headerBandHeight()
            // Trailing edge: every chrome puts its caption text at the leading edge, so the control never
            // collides with the path it sits beside. Centred in the band when there is one, and just inside the
            // top corner when there is not, which is the `PLAIN` case.
            val x = (width - ins.right - size.width - CONTROL_GAP).coerceAtLeast(ins.left)
            val y = if (band > 0) ((band - size.height) / 2).coerceAtLeast(0) else ins.top + CONTROL_GAP
            c.setBounds(x, y, size.width, size.height)
        }
    }

    /**
     * Put a control in this node's header band, or remove the one that is there.
     *
     * Idempotent, and it re-lifts on every call: `setComponentZOrder` is what makes the control reachable, and a
     * `rebuildChrome` that re-adds the header strip changes the child order under it.
     */
    /** The control currently in the header band, if any — so a hit-test check can aim at the real component. */
    fun cornerControlComponent(): JComponent? = cornerControl

    fun setCornerControl(control: JComponent?) {
        cornerControl?.let { if (it !== control) remove(it) }
        cornerControl = control
        if (control != null) {
            if (control.parent !== this) add(control)
            setComponentZOrder(control, 0)
        }
        revalidate()
        repaint()
    }

    /**
     * The height of the band this node's chrome reserves at the top — where a corner control goes.
     *
     * Zero for [NodeChrome.PLAIN], which has no header at all — which is why the single collapse affordance needed
     * a fallback position:
     * there is nothing to put a control *in*, so it goes just inside the top corner.
     */
    fun headerBandHeight(): Int = when (style.chrome) {
        NodeChrome.HEADER_STRIP -> headerStrip?.preferredSize?.height ?: 0
        NodeChrome.PLAIN -> 0
    }

    /**
     * **Whether this node's frame draws its own top edge, or the leader draws it instead** — constraint 16.
     *
     * The node cannot work this out: which border segments it owns is a property of the *leader mode* above it, so
     * whatever draws them has to be told. [CodeNestView] sets it per nesting — false in `CORNERS`/`BRACE`, true in
     * `ARROW` — and a top-level node never has a leader, so it keeps its top edge.
     *
     * There is no `chromeDrawnTopOffset()` any more, and its absence is the point: every surviving chrome draws its
     * line **at** its bounds edge, so the drawn-line offset is 0 by construction rather than by agreement. The
     * whole apparatus that existed to make a non-zero offset queryable — `NodeChrome.TITLED_BORDER`, its
     * `CaptionBorder`, the 7-vs-9 px font-dependent measurement, the rounded-corner defect its fix introduced —
     * went with the chrome the reader scrapped. See `TreeOfCode.completed.md`.
     */
    var topEdgeDrawn: Boolean = true
        private set

    /** Told, not inferred: [CodeNestView] owns the leader mode and therefore owns this. */
    fun setTopEdgeDrawn(drawn: Boolean) {
        if (topEdgeDrawn == drawn) return
        topEdgeDrawn = drawn
        rebuildChrome()
    }

    // ── Content: what the node shows ──

    /**
     * Collapse everything outside [CodeNodeSpec.keep] — the elision that makes a whole file readable as a node.
     *
     * `clearFoldRegions()` first, then ours. Not a loop: constraint 5 measured the daemon *adding* 19–59
     * regions of its own after this ran, and never removing ours.
     *
     * The two [NodeElision] mechanisms are not cosmetic variants of each other (constraint 6):
     * [NodeElision.OPENABLE] uses plain `addFoldRegion` with a text placeholder and the reader can click it
     * open; [NodeElision.SEALED] uses `addCustomLinesFolding`, which paints a proper `⋯ N lines ⋯` row and
     * **refuses to expand** — `shouldNeverExpand` is true and `setExpanded(true)` is a no-op. Which of those
     * a narration surface wants is a 1c question, so both are built.
     */
    fun applyElision() {
        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        installedFolds.clear()

        val model = editor.foldingModel
        model.runBatchFoldingOperation {
            model.clearFoldRegions()
            for (gap in gapsOutsideKeep(lastLine)) {
                val elided = gap.last - gap.first + 1
                when (style.elision) {
                    NodeElision.OPENABLE -> {
                        val start = doc.getLineStartOffset(gap.first)
                        val end = doc.getLineEndOffset(gap.last)
                        if (end <= start) continue
                        val region = model.addFoldRegion(start, end, placeholderFor(elided))
                        if (region == null) {
                            log.debug("node ${spec.title}: addFoldRegion declined lines ${gap.first + 1}-${gap.last + 1}")
                            continue
                        }
                        region.isExpanded = false
                        installedFolds += region
                    }

                    NodeElision.SEALED -> {
                        val fm = model as? FoldingModelEx ?: continue
                        try {
                            val region: FoldRegion? =
                                fm.addCustomLinesFolding(gap.first, gap.last, ElidedLinesRenderer(elided))
                            if (region != null) installedFolds += region
                        } catch (t: Throwable) {
                            // Custom line foldings are rejected for ranges the platform considers unfoldable
                            // (an empty span, an overlap with a region added moments ago). Dropping one
                            // elision leaves that run visible, which is legible; aborting the batch would
                            // leave the node showing the whole file.
                            log.debug("node ${spec.title}: addCustomLinesFolding refused lines ${gap.first + 1}-${gap.last + 1}: $t")
                        }
                    }
                }
            }
        }
        // A grow-to-content node has nothing to scroll, and an always-visible scrollbar would eat width for
        // nothing. Re-evaluated here because folding is what decides whether the node exceeds its cap.
        editor.setVerticalScrollbarVisible(scrolls())
        resync(TRIGGER_ELISION)
    }

    /** 0-based line runs *not* covered by [CodeNodeSpec.keep] — the complement, which is what gets folded. */
    private fun gapsOutsideKeep(lastLine: Int): List<IntRange> {
        val keep = spec.keep
            .map { (it.first - 1).coerceIn(0, lastLine)..(it.last - 1).coerceIn(0, lastLine) }
            .sortedBy { it.first }
        val gaps = mutableListOf<IntRange>()
        var cursor = 0
        for (r in keep) {
            if (r.first > cursor) gaps += cursor..(r.first - 1)
            cursor = maxOf(cursor, r.last + 1)
        }
        if (cursor <= lastLine) gaps += cursor..lastLine
        return gaps
    }

    /** How many of our fold regions are still installed *and* still collapsed (constraint 5's measurement). */
    fun survivingFolds(): Int = installedFolds.count { it.isValid && !it.isExpanded }

    /**
     * Our regions that are **gone** — invalidated by the daemon or by a document change. A real loss.
     *
     * Split out from [survivingFolds] because that count alone is ambiguous, which 1a″ learned the hard way:
     * `2/3 ours` was read as an elision being destroyed when it was the reader having *opened* one. A check
     * that fails on `surviving < requested` fails on a reader clicking `⋯ 30 lines ⋯`; only this number is a
     * defect (constraint 5).
     */
    fun invalidFolds(): Int = installedFolds.count { !it.isValid }

    /** Our regions still installed but expanded — a reader opened them ([NodeElision.OPENABLE] working). */
    fun expandedFolds(): Int = installedFolds.count { it.isValid && it.isExpanded }

    fun requestedFolds(): Int = installedFolds.size

    /** Fold regions in this editor that are **not** ours — the daemon's, per constraint 5. */
    fun foreignFolds(): Int =
        editor.foldingModel.allFoldRegions.count { r -> installedFolds.none { it === r } }

    // ── Attention: which node, and which lines inside it ──

    /**
     * Mark this node as the one being talked about, or not.
     *
     * Background tint and border contrast only, per constraint 1 — the constraint exists because the obvious
     * implementation (a whole-node foreground dim) destroys the semantic colour that justified Arch B.
     *
     * Deliberately *not* a step render: [spotlight] draws the attention *inside* the active node, and this
     * draws the distinction *between* nodes. Two levels of attention now exist where the in-place editor had
     * one, and they are separate calls so Phase 3 can move them on different schedules.
     */
    fun setActive(active: Boolean) {
        isActive = active
        applyEmphasisTint()
        rebuildChrome()
    }

    private fun applyEmphasisTint() {
        emphasisTint?.let { h ->
            try {
                editor.markupModel.removeHighlighter(h)
            } catch (_: Throwable) {
                // Markup already gone with the editor.
            }
        }
        emphasisTint = null

        val tinted = !isActive && style.deemphasis.tints
        if (!tinted) return
        val doc = editor.document
        if (doc.textLength == 0) return
        emphasisTint = editor.markupModel.addRangeHighlighter(
            0, doc.textLength, EMPHASIS_LAYER,
            TextAttributes().apply { backgroundColor = deemphasisTint() },
            HighlighterTargetArea.LINES_IN_RANGE,
        )
    }

    /**
     * The non-active node's wash: the accent pulled most of the way to the editor background.
     *
     * The accent comes from `AudioExplainerSettings` like every other decoration in the plugin, **not** from
     * `JBColor.namedColor("Focus.color", …)` — constraint 4, measured: that named colour rendered dark red in
     * Rider's default scheme and the tinted node read as an error band.
     */
    private fun deemphasisTint(): Color =
        SpotlightRenderer.blendToward(SpotlightRenderer.accentColor(), editor.colorsScheme.defaultBackground, DEEMPHASIS_MIX)

    // ── Geometry: size to content, with a cap ──

    /**
     * The height the folded document actually draws in, which is **not** `lineHeight × lineCount`:
     * `offsetToXY` is the only thing that knows where a folded document draws, and a custom fold region's own
     * height is part of the answer.
     */
    fun contentHeight(): Int {
        // Not defensive dressing: a listener can fire after teardown, and `offsetToXY` on a released editor
        // throws rather than returning anything. The last published height is the honest answer — the node is
        // gone, so nothing will lay it out again.
        if (disposed || editor.isDisposed) return lastContentHeight.coerceAtLeast(0)
        val h = editor.offsetToXY(editor.document.textLength).y + editor.lineHeight
        lastContentHeight = h
        return h
    }

    /**
     * The height the node actually asks its container for: the content, the **excerpt target**, or the cap,
     * whichever binds.
     *
     * The distinction from [contentHeight] is what [resync] coalesces on, and it is not pedantry — measured
     * on the 1a′ bench: toggling grow⇄cap changes this and leaves [contentHeight] untouched, so coalescing on
     * the content height suppressed the very layout pass the toggle needed. The bench hid it by revalidating
     * its own container as well; a caller that trusted [restyle] alone would have seen nothing move.
     *
     * **Three numbers now, and the plan settled the order** (§1a⁵ 4): the *target* is the excerpt of interest, the
     * *ceiling* is [CodeNodeStyle.maxHeight], and the *content* is what there actually is to show. The reader's
     * decision was **no floor** — a 6-line member gets six lines — so the answer is simply the smallest of the
     * three that applies. `content` is in the min because a target taller than the folded document would leave
     * blank rows below the last line, which reads as a broken node rather than as a generous one.
     */
    fun publishedHeight(): Int {
        val content = contentHeight()
        if (style.grow) return content
        val target = excerptTargetHeight()
        return if (target == null) minOf(content, style.maxHeight) else minOf(target, content, style.maxHeight)
    }

    fun unfoldedContentHeight(): Int =
        if (disposed || editor.isDisposed) 0 else editor.lineHeight * editor.document.lineCount

    /**
     * True when the node's viewport is smaller than its content and it therefore keeps its own vertical
     * scrollbar — the independently-scrollable behaviour the brief asked for, which falls out of the node being a
     * real editor rather than having to be built.
     *
     * **Constraint 19 is why this is computed and not assumed**: a node sized to its content has nothing to
     * scroll, so "reveal the active range in *its* node" is not expressible for it. That is what §1a⁵ 4's excerpt
     * target buys — it is not merely a landing fix, it is what creates the viewport attention can be moved inside.
     */
    fun scrolls(): Boolean = !style.grow && contentHeight() > publishedHeight()

    override fun getPreferredSize(): Dimension {
        val body = publishedHeight()
        val ins = insets
        val header = headerStrip?.preferredSize?.height ?: 0
        // Computed rather than delegated: with `layout = null` there is no layout manager to ask, and
        // `Container.preferredSize()` would answer with the current size. The width is nearly always overridden by
        // the container anyway — nodes take the viewport's width — but a wrong one here would break the one case
        // where it is not (constraint 9: the height follows the width).
        val bodyWidth = maxOf(
            editor.component.preferredSize.width,
            headerStrip?.preferredSize?.width ?: 0,
        )
        return Dimension(bodyWidth + ins.left + ins.right, body + header + ins.top + ins.bottom)
    }

    // ── Landing: where the node opens ──

    /**
     * **§1a⁵ 4 — size this node to its excerpt of interest and open scrolled to it.** Nested children only.
     *
     * The mechanism the reader asked for by name, after the elision version was judged better in a nested node and
     * *worse* in the root: *"leave the class line as before, but inside subeditor SCROLL by default to a member …
     * This requires subeditor with initial height capped exactly to height of exerpt of interest."*
     *
     * **The root is exempt**, and that is the answer to why the elision version read worse at the top: the root is
     * the reader's entry point and its class header is the one place the file's identity is not already implied by
     * a caller. So this is off by default and [CodeNestView] switches it on per embedded child.
     *
     * It is also constraint 19's precondition rather than only a landing: a node sized to its content cannot be
     * scrolled, so until something caps a node below its content, "reveal the active range in *its* node" is not
     * expressible at all.
     */
    var landsOnExcerpt: Boolean = false
        private set

    fun setLandsOnExcerpt(on: Boolean) {
        if (landsOnExcerpt == on) return
        landsOnExcerpt = on
        resync(TRIGGER_LANDING)
    }

    /**
     * The height of the excerpt of interest, or null when this node does not land on one.
     *
     * In lines × `lineHeight` rather than in pixels, because the same excerpt is a different number of pixels at a
     * different font size — constraint 9's shape, arriving as a font rather than as a width.
     *
     * The excerpt is [CodeNodeSpec.focusRange], which Phase 1 **guesses from range order** and Phase 2's schema
     * will state outright (decision 3). The guess is named there rather than here so there is one place to change.
     */
    fun excerptTargetHeight(): Int? {
        if (!landsOnExcerpt || disposed || editor.isDisposed) return null
        val focus = spec.focusRange() ?: return null
        return (focus.last - focus.first + 1).coerceAtLeast(1) * editor.lineHeight
    }

    /**
     * Scroll this node's viewport to the excerpt of interest — the second half of the landing.
     *
     * **Deferred by the caller, deliberately.** `scrollVertically` clamps to the scrollable extent, which is
     * `content − viewport`, and the viewport is 0 until the node has been laid out inside its inlay inside its
     * host: called too early it silently clamps to 0 and the node opens at the top of the file, which looks
     * exactly like the landing not being implemented. [CodeNestView] schedules it after the embedding settles.
     *
     * Animation off, so the offset is readable by the check that runs immediately after — B3b's retraction was
     * partly an async scroll being measured before it finished.
     */
    fun landOnExcerpt() {
        if (!landsOnExcerpt || disposed || editor.isDisposed) return
        val focus = spec.focusRange() ?: return
        val y = ReadAction.compute<Int?, RuntimeException> {
            val doc = editor.document
            val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
            val line0 = (focus.first - 1).coerceIn(0, lastLine)
            editor.offsetToXY(doc.getLineStartOffset(line0)).y
        } ?: return
        val sm = editor.scrollingModel
        sm.disableAnimation()
        try {
            sm.scrollVertically(y)
        } finally {
            sm.enableAnimation()
        }
        lastLandingRequest = y
    }

    /** The y [landOnExcerpt] last asked for, so a check can say "asked for 380px, got 0" rather than only "wrong". */
    var lastLandingRequest: Int = -1
        private set

    /** Where the viewport actually is. Paired with [lastLandingRequest]: a clamp to 0 is the failure mode. */
    fun scrollOffset(): Int =
        if (disposed || editor.isDisposed) -1 else editor.scrollingModel.verticalScrollOffset

    /**
     * The first 1-based line of this node's document that is **not** inside a collapsed region — the top of the
     * node's *content*, which is not the same thing as the top of its *viewport*.
     *
     * It reads the folding model rather than the spec, because the daemon adds folds of its own (constraint 5) and
     * "what the first kept range says" is a different claim from "what survived the folding". Both matter, and
     * **the plan expected this one to score the new landing and it cannot** — §1a⁗ landed on the member by
     * *eliding* everything above it, so the first unfolded line *was* what the reader saw; §1a⁵ 4 leaves `keep` as
     * authored and **scrolls**, so the first unfolded line is the class header again while the reader is looking at
     * the member. That is [firstVisibleLineInViewport]'s job now, and this stays as the measurement of the elision.
     */
    fun firstVisibleKeptLine(): Int? {
        if (disposed || editor.isDisposed) return null
        return ReadAction.compute<Int?, RuntimeException> {
            val doc = editor.document
            for (line in 0 until doc.lineCount) {
                val offset = doc.getLineStartOffset(line)
                if (editor.foldingModel.getCollapsedRegionAtOffset(offset) == null) return@compute line + 1
            }
            null
        }
    }

    /**
     * The first 1-based line actually **in the viewport** — what the reader sees at the top of this node.
     *
     * The honest measurement of §1a⁵ 4's landing, and the one that can distinguish it from a node that merely
     * *wanted* to scroll: a `scrollVertically` that clamped to 0 for want of a laid-out viewport leaves this at the
     * first kept line, which is precisely what "the landing is not implemented" looks like.
     */
    fun firstVisibleLineInViewport(): Int? {
        if (disposed || editor.isDisposed) return null
        return ReadAction.compute<Int?, RuntimeException> {
            val y = editor.scrollingModel.verticalScrollOffset
            editor.xyToLogicalPosition(Point(0, y)).line + 1
        }
    }

    /**
     * Re-publish the node's height after something moved it.
     *
     * Three triggers, and the plan names all three: a fold (ours or the daemon's, constraint 5), an editor
     * font-size change, and a colour-scheme change — the last two move `lineHeight` without touching the
     * folding model, so a fold-only listener would silently leave every node the wrong height after a theme
     * switch.
     *
     * Coalesced on the computed height rather than on the event, because the daemon's folding passes fire
     * far more often than they change anything, and each un-coalesced pass would cost a layout of every node
     * in the tree. [resyncReport] keeps both numbers so "the listener never fired" and "the listener fired
     * and the height was already right" stay distinguishable.
     */
    private fun resync(trigger: String) {
        if (disposed || editor.isDisposed) return
        val stat = resyncStats.getOrPut(trigger) { IntArray(2) }
        stat[0]++
        // Before the height check, not after: a fold or an inlay moves the lines a connector is anchored to
        // whether or not the node's own outline ends up a different size.
        onGeometryChanged?.invoke()
        val h = publishedHeight()
        if (h == lastPublishedHeight) return
        lastPublishedHeight = h
        stat[1]++
        editor.setVerticalScrollbarVisible(scrolls())
        revalidate()
        // The container lays nodes out from their preferred heights, so it has to re-lay-out too — a
        // revalidate() on the node alone leaves the node the right size inside a stale slot.
        parent?.revalidate()
        parent?.repaint()
    }

    /** One deferred resync per width, however many resize events the layout storm produced. */
    private fun scheduleWidthResync() {
        if (disposed || editor.isDisposed) return
        if (width == lastResyncedWidth || width <= 0) return
        if (widthResyncPending) return
        widthResyncPending = true
        ApplicationManager.getApplication().invokeLater {
            widthResyncPending = false
            if (disposed || editor.isDisposed) return@invokeLater
            lastResyncedWidth = width
            resync(TRIGGER_WIDTH)
        }
    }

    /**
     * A colour scheme (theme) change: everything derived from the scheme has to be recomputed, and a height
     * resync is only the *last* of those.
     *
     * The node's own two — the de-emphasis tint (blended toward `defaultBackground`) and the chrome (title
     * font and colour from the scheme) — are re-applied here. The third is not this class's to fix: the step
     * decorations belong to [SpotlightRenderer], whose palette is per-render, so the owner is asked to
     * re-render via [onRerenderNeeded]. Without that call the node comes out of a dark→light switch with a
     * correct height and the previous theme's colours, which is exactly what the 1a′ bench showed.
     */
    private fun onSchemeChanged() {
        if (disposed || editor.isDisposed) return
        applyEmphasisTint()
        rebuildChrome()
        onRerenderNeeded?.invoke(this)
        resync(TRIGGER_SCHEME)
    }

    /** `fold=12/3 font=2/2 scheme=1/1` — events / of those, ones that actually changed the height. */
    fun resyncReport(): String =
        resyncStats.entries.joinToString(" ") { (k, v) -> "$k=${v[0]}/${v[1]}" }.ifEmpty { "<no resync events>" }

    /**
     * The same counters as [resyncReport], machine-readable: trigger → (events, of which published a height).
     *
     * Exists because the numbers turned out to be the *only* evidence for one class of defect. `restyle=4/0`
     * is what a coalescing bug looks like — four toggles, no new size published — and it is invisible on
     * screen whenever the owner also revalidates its own container. A check can assert on this; a human
     * cannot see it.
     */
    fun resyncCounts(): Map<String, Pair<Int, Int>> = resyncStats.mapValues { (_, v) -> v[0] to v[1] }

    // ── Anchors and scrolling ──

    /**
     * Where a 1-based line of this node's file draws, in **this component's** coordinates, or null if the
     * line is outside the document.
     *
     * Fold-aware because `offsetToXY` is, which is what makes it usable as a connector anchor by the tree
     * container (1a″). [LineAnchor.visible] is the part a caller must not skip: a line inside a collapsed
     * region has a perfectly good point — the placeholder row's — and drawing a connector to it says
     * "this call is here" while pointing at `⋯ 30 lines ⋯`.
     */
    fun anchorOfLine(line: Int): LineAnchor? {
        // Called from a paint — once per connector, per repaint — so it has to be safe on a node whose editor
        // has already gone, and it reads the line map, which is the document model (constraint 12).
        if (disposed || editor.isDisposed) return null
        return ReadAction.compute<LineAnchor?, RuntimeException> {
            val doc = editor.document
            val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
            val zero = line - 1
            if (zero < 0 || zero > lastLine) return@compute null
            val offset = doc.getLineStartOffset(zero)
            val xy = editor.offsetToXY(offset)
            val inContent = Point(xy.x, xy.y + editor.lineHeight / 2)
            val point = SwingUtilities.convertPoint(editor.contentComponent, inContent, this)
                ?: return@compute null
            val collapsed = editor.foldingModel.getCollapsedRegionAtOffset(offset) != null
            LineAnchor(point, !collapsed)
        }
    }

    /**
     * Bring a 1-based line into view — inside this node, and then this node inside its container.
     *
     * The second half is the one that does not happen by itself; see [onRevealRequest].
     */
    fun reveal(line: Int) {
        val lastLine = (editor.document.lineCount - 1).coerceAtLeast(0)
        spotlight.reveal((line - 1).coerceIn(0, lastLine))
        onRevealRequest?.invoke(this)
    }

    /** Scroll this whole node into the enclosing scroll pane's viewport — the default [onRevealRequest]. */
    fun scrollNodeIntoView() {
        scrollRectToVisible(Rectangle(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1)))
    }

    // ── Chrome and restyling ──

    /**
     * Swap the whole visual style at runtime — how Phase 1c photographs alternatives without rebuilding the
     * tree, and therefore without re-measuring on a differently-settled backend.
     */
    fun restyle(newStyle: CodeNodeStyle) {
        val elisionChanged = newStyle.elision != style.elision
        style = newStyle
        rebuildChrome()
        applyEmphasisTint()
        if (elisionChanged) applyElision() else resync(TRIGGER_RESTYLE)
    }

    private fun rebuildChrome() {
        headerStrip?.let { remove(it) }
        headerStrip = null

        val line = if (isActive || !style.deemphasis.dimsBorder) activeBorderColor() else JBColor.border()
        val weight = if (isActive) 2 else 1

        when (style.chrome) {
            NodeChrome.HEADER_STRIP -> {
                border = NodeFrameBorder(line, weight, topEdgeDrawn)
                val strip = JBLabel(" " + displayPath()).apply {
                    font = titleFont()
                    foreground = titleColor()
                    isOpaque = true
                    background = if (isActive) {
                        SpotlightRenderer.blendToward(
                            SpotlightRenderer.accentColor(), JBColor.PanelBackground, HEADER_ACCENT_MIX,
                        )
                    } else {
                        JBColor.PanelBackground
                    }
                    border = BorderFactory.createEmptyBorder(STRIP_PAD, STRIP_PAD + 2, STRIP_PAD, STRIP_PAD)
                }
                headerStrip = strip
                add(strip)
            }

            // A border and no caption — what 1a‴ verdict 16 actually asked for, and the 1a⁗ round's answer to
            // the borderless variant that overshot it: "no border is strictly worse than plain". §1a⁵ 2 made it
            // the value the *rule* picks for a nested child in the same file as its caller.
            NodeChrome.PLAIN -> {
                border = NodeFrameBorder(line, weight, topEdgeDrawn)
            }
        }
        // The corner control is a child like the others, so re-adding the header strip changed the child order it
        // was lifted above.
        cornerControl?.let { if (it.parent === this) setComponentZOrder(it, 0) }
        revalidate()
        repaint()
    }

    /**
     * The frame's line colour — **[LeaderArt.leaderBase], not a second derivation of it.**
     *
     * It used to be the accent blended 0.75 toward the border while the leader used 0.80: two numbers that happened
     * to look alike. Constraint 16 makes them one path, so a divergence would read as a colour seam in the middle
     * of a continuous stroke rather than as two similar lines — the same mistake constraint 18 caught between the
     * box and the leader, one level up.
     */
    private fun activeBorderColor(): Color = LeaderArt.leaderBase()

    private fun titleFont(): Font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        .deriveFont(Font.BOLD, (editor.colorsScheme.editorFontSize - 1).coerceAtLeast(9).toFloat())

    private fun titleColor(): Color = if (isActive) activeBorderColor() else JBColor.GRAY

    /**
     * The node's label. Three forms because 1c has to look at them: the full path is unambiguous and
     * enormous, the basename is short and ambiguous exactly in the case the same-file fixture exists to test,
     * and the project-relative path is the compromise nobody has photographed yet.
     */
    private fun displayPath(): String {
        val suffix = spec.title.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""
        val path = when (style.path) {
            NodePath.FULL -> spec.file.path
            NodePath.BASENAME -> spec.file.name
            NodePath.PROJECT_RELATIVE -> projectRelativePath() ?: spec.file.name
        }
        return path + suffix
    }

    private fun projectRelativePath(): String? {
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return null
        return VfsUtilCore.getRelativePath(spec.file, base)
    }

    // ── Teardown ──

    /**
     * Release the editor and everything hanging off it.
     *
     * Idempotent and silent on failure by design: this runs from a `FileEditor.dispose()` or a project
     * teardown, where the editor may already be gone, and a throw there would abort the disposal of the
     * *other* N-1 nodes.
     */
    override fun dispose() {
        if (disposed) return
        disposed = true
        // First, and unconditionally: an application-level subscription that outlives its editor is the one
        // teardown bug that shows up long after the node is gone, on the next theme switch.
        try {
            Disposer.dispose(listeners)
        } catch (_: Throwable) {
            // Already disposed via a parent Disposable.
        }
        try {
            spotlight.clear()
        } catch (_: Throwable) {
            // Already torn down with the editor.
        }
        try {
            if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
        } catch (t: Throwable) {
            log.warn("releasing node editor for ${spec.file.name} failed", t)
        }
    }

    /** Where a line draws, and whether the reader can actually see that line there. */
    class LineAnchor(val point: Point, val visible: Boolean)

    /**
     * The node's frame: a rounded rectangle that **can leave its top edge to the leader** (constraint 16).
     *
     * Every edge is drawn *at* the component's bounds edge, which is the whole reason this replaced
     * `CaptionBorder`: the drawn-line offset is now 0 by construction rather than by agreement, so there is no
     * number for a leader to query and nothing for two implementations to disagree about.
     *
     * **The top corners are square when [drawTop] is false**, and that is what dissolved 1a⁗'s rounded-corner
     * defect rather than working around it: with no top run there is no top corner to round — the "corner" is just
     * where a side wall begins, and the leader arrives into it. The bottom corners keep [LeaderArt.ARC], the one
     * radius the brace's turn and the token box also use.
     */
    class NodeFrameBorder(
        private val line: Color,
        private val weight: Int,
        private val drawTop: Boolean,
    ) : AbstractBorder() {

        override fun getBorderInsets(c: Component?, insets: Insets): Insets {
            insets.set(weight, weight + SIDE_PAD, weight, weight + SIDE_PAD)
            return insets
        }

        override fun isBorderOpaque(): Boolean = false

        override fun paintBorder(c: Component?, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = line
                g2.stroke = BasicStroke(weight.toFloat())
                val arc = LeaderArt.ARC.toFloat()
                val left = x.toFloat()
                val right = (x + w - 1).toFloat()
                val top = y.toFloat()
                val bottom = (y + h - 1).toFloat()
                val p = java.awt.geom.GeneralPath()
                // One path from the left wall round to the right wall, so the corners cannot drift apart the way
                // two `drawArc` calls and four `drawLine`s did. The walls start at `top` when the leader owns the
                // top edge and at `top + arc` when this border draws it — which is the square-vs-rounded top
                // corner, expressed once.
                val wallTop = if (drawTop) top + arc else top
                p.moveTo(left, wallTop)
                p.lineTo(left, bottom - arc)
                p.quadTo(left, bottom, left + arc, bottom)
                p.lineTo(right - arc, bottom)
                p.quadTo(right, bottom, right, bottom - arc)
                p.lineTo(right, wallTop)
                if (drawTop) {
                    p.quadTo(right, top, right - arc, top)
                    p.lineTo(left + arc, top)
                    p.quadTo(left, top, left, wallTop)
                }
                g2.draw(p)
            } finally {
                g2.dispose()
            }
        }

        private companion object {
            const val SIDE_PAD = 1
        }
    }

    companion object {
        /** Trigger names used as keys by [resyncCounts], so a check can name the one it means. */
        const val TRIGGER_FOLD = "fold"
        const val TRIGGER_FONT = "font"
        const val TRIGGER_SCHEME = "scheme"
        const val TRIGGER_ELISION = "elision"
        const val TRIGGER_RESTYLE = "restyle"
        const val TRIGGER_INLAY = "inlay"
        const val TRIGGER_WIDTH = "width"
        const val TRIGGER_LANDING = "landing"

        /**
         * Below [SpotlightRenderer]'s own layer (`SELECTION - 1`), so the active glow and the dim roles drawn
         * *inside* a node always win over the node-level wash drawn *around* them.
         */
        private val EMPHASIS_LAYER = HighlighterLayer.SELECTION - 3

        /** How far the non-active wash is pulled toward the background. Faint on purpose: it must not read as a highlight. */
        private const val DEEMPHASIS_MIX = 0.10

        private const val HEADER_ACCENT_MIX = 0.18
        private const val STRIP_PAD = 3

        /** Space between a corner control and the chrome's trailing edge. */
        private const val CONTROL_GAP = 3

        private fun placeholderFor(elidedLines: Int): String = "  ⋯  $elidedLines lines  ⋯  "
    }
}

/**
 * One node's content contract: a real file, and the 1-based inclusive line ranges kept visible in it.
 *
 * Ranges are **lines of the real file**, resolved by the caller — from text anchors today, from the Phase 2
 * schema's `keep` ranges later. Never from PSI: §4.4 rule 2 committed this project to text-search-only
 * locators for Rider parity, and that rule is why the schema will have to carry class/method header lines
 * explicitly rather than deriving the enclosing declaration.
 */
class CodeNodeSpec(
    val file: VirtualFile,
    /** What the chrome shows next to the path — usually the member this node is about. */
    val title: String,
    /** Depth in the call tree. Carried for the container; [CodeNodeView] never reads it. */
    val depth: Int,
    val keep: List<IntRange>,
    /**
     * The **excerpt of interest** — the range §1a⁵ 4 sizes a nested node to and scrolls it to.
     *
     * Optional because Phase 1's fixture cannot state it: the schema decision that will (Phase 2 decision 3 —
     * `context` vs `focus` roles on a frame's ranges) is not taken yet, so [focusRange] falls back to a guess from
     * range order. Carrying the field now means the guess has exactly one home and Phase 2 deletes it rather than
     * hunting for it.
     */
    val focus: IntRange? = null,
) {
    /**
     * The excerpt of interest, or null if this node has nothing to land on.
     *
     * **The guess is "the second kept range"**, and it is a guess about *authoring* rather than about code: a node
     * keeps its class header first and the member it is about second, which is what every case in
     * `fixtures/TreeSpike` does and what `plan-schema.md` will require. A single-range node has no separate header,
     * so its one range *is* the excerpt.
     */
    fun focusRange(): IntRange? {
        focus?.let { return it }
        val sorted = keep.sortedBy { it.first }
        return sorted.getOrNull(1) ?: sorted.firstOrNull()
    }
}

/**
 * Whether a node shows its caption band. **Two values, and they are picked by a rule rather than by a knob.**
 *
 * §1a⁵ 2 decided the rule: show the header when the node is **top-level** *or* **in a different file from its
 * parent**; hide it when a nested child is in the same file as its caller. A cross-file child keeps its caption
 * because that caption is the only place its file name appears. [CodeNestView.chromeFor] is the one implementation,
 * and Phase 4 must **not** offer this in the settings dialog — there is nothing left to choose.
 *
 * Two values because the other two are gone and neither is coming back: `TITLED_BORDER` was **scrapped** (it was
 * the only chrome whose drawn line was not its bounds edge, and the entire drawn-line-offset problem went with it —
 * constraint 16), and a borderless variant was **rejected** earlier as "no border is strictly worse than plain".
 *
 * What is *not* independent of the leader is the frame's top edge, and that is a separate switch on the node
 * ([CodeNodeView.setTopEdgeDrawn]) precisely so the two questions cannot be confused again.
 */
enum class NodeChrome {
    /** A background-tinted caption band with the path and the member, and no line below it. */
    HEADER_STRIP,

    /** A frame and no caption. What a nested child in its caller's own file gets. */
    PLAIN,
}

/** How much of the file's path the chrome shows. */
enum class NodePath { FULL, PROJECT_RELATIVE, BASENAME }

/**
 * How an elided run is drawn — and whether the reader can open it.
 *
 * Constraint 6, measured: a `CustomFoldRegion` refuses to expand (`shouldNeverExpand` is true and
 * `setExpanded(true)` is a no-op), so this is a behavioural choice and not a cosmetic one.
 */
enum class NodeElision {
    /** Plain `addFoldRegion` + placeholder text. Reader-openable. */
    OPENABLE,

    /** `addCustomLinesFolding` + [ElidedLinesRenderer]. Better looking, sealed shut. */
    SEALED,
}

/** How a non-active node is pushed back. Never a foreground dim — constraint 1. */
enum class NodeDeemphasis(val tints: Boolean, val dimsBorder: Boolean) {
    NONE(false, false),
    TINT(true, false),
    BORDER(false, true),
    TINT_AND_BORDER(true, true),
}

/**
 * Everything about a node that Phase 1c has to compare by eye, in one object, so an alternative is a
 * [CodeNodeView.restyle] call rather than a rebuild.
 *
 * [maxHeight]'s default is the plan's constraint 8: folded nodes measured 286–550 px against unfolded
 * 858–3740 px, and Phase 0b's 220 px cap was so aggressive that every node exceeded it and grow-vs-scroll
 * never got compared. 600 px is the starting point for that comparison, not a settled answer.
 */
class CodeNodeStyle(
    val maxHeight: Int = DEFAULT_MAX_HEIGHT,
    /**
     * **Assigned by role, not chosen** — see [NodeChrome] and [CodeNestView.chromeFor]. It stays on the style
     * object because that is how a node is restyled, not because it is a preference.
     */
    val chrome: NodeChrome = NodeChrome.HEADER_STRIP,
    val path: NodePath = NodePath.PROJECT_RELATIVE,
    val elision: NodeElision = NodeElision.OPENABLE,
    /** [NodeDeemphasis.TINT] rather than `TINT_AND_BORDER`: chosen by eye on the 1a′ bench, over real code. */
    val deemphasis: NodeDeemphasis = NodeDeemphasis.TINT,
    /**
     * True: ignore [maxHeight] and grow to content, so the node never scrolls. The other half of the 1c A/B, and
     * **constraint 19 is the thing to know before choosing it** — a node with no viewport smaller than its content
     * cannot be scrolled, so choosing grow switches off half of Phase 3's per-node attention policy.
     */
    val grow: Boolean = false,
) {
    fun with(
        maxHeight: Int = this.maxHeight,
        chrome: NodeChrome = this.chrome,
        path: NodePath = this.path,
        elision: NodeElision = this.elision,
        deemphasis: NodeDeemphasis = this.deemphasis,
        grow: Boolean = this.grow,
    ) = CodeNodeStyle(maxHeight, chrome, path, elision, deemphasis, grow)

    companion object {
        const val DEFAULT_MAX_HEIGHT = 600
    }
}

/**
 * The `⋯ N lines ⋯` row painted over a sealed elision ([NodeElision.SEALED]).
 *
 * A hairline rule broken around the text, rather than plain text: the point of an elision is that something
 * is *missing* here, and a rule reads as a seam where a comment-coloured string reads as a comment. Colours
 * come from the scheme and the settings accent (constraint 4), so it follows a theme switch.
 */
class ElidedLinesRenderer(private val elidedLines: Int) : CustomFoldRegionRenderer {

    private fun label() = "⋯  $elidedLines lines  ⋯"

    private fun font(editor: Editor): Font = editor.colorsScheme.getFont(EditorFontType.ITALIC)

    override fun calcWidthInPixels(region: CustomFoldRegion): Int {
        val editor = region.editor
        val fm = editor.contentComponent.getFontMetrics(font(editor))
        return fm.stringWidth(label()) + 2 * PAD + RULE_TAIL
    }

    override fun calcHeightInPixels(region: CustomFoldRegion): Int = region.editor.lineHeight

    override fun paint(
        region: CustomFoldRegion,
        g: Graphics2D,
        targetRegion: Rectangle2D,
        textAttributes: TextAttributes,
    ) {
        val editor = region.editor
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = font(editor)
            val fm = g2.fontMetrics
            val text = label()
            val x = targetRegion.x.toInt() + PAD
            val baseline = targetRegion.y.toInt() + (targetRegion.height.toInt() + fm.ascent - fm.descent) / 2
            val midY = (targetRegion.y + targetRegion.height / 2).roundToInt()
            val w = fm.stringWidth(text)
            val fg = editor.colorsScheme.defaultForeground
            val accent = SpotlightRenderer.accentColor()

            g2.color = SpotlightRenderer.blendToward(accent, fg, RULE_MIX)
            g2.stroke = BasicStroke(1f)
            g2.drawLine(x - PAD, midY, x - 2, midY)
            g2.drawLine(x + w + 2, midY, x + w + PAD + RULE_TAIL, midY)

            g2.color = SpotlightRenderer.blendToward(accent, fg, TEXT_MIX)
            g2.drawString(text, x, baseline)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val PAD = 8
        const val RULE_TAIL = 40
        const val RULE_MIX = 0.55
        const val TEXT_MIX = 0.35
    }
}
