package com.audioexplainer.tree

import com.audioexplainer.Endpoint
import com.audioexplainer.SpotlightRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayModel
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.FoldingListener
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
 * Phase 1a′ of `TreeOfCode.rules.md`. This is the single component the tree container, the schema renderer and the
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
     * [SpotlightRenderer.paintStep] ends in a `revealCenter`, which scrolls **this editor**. Inside a
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
     * That rule is `TreeOfCode.rules.md` constraint 11 — a colour-scheme change must re-*render*, not merely
     * re-measure. Settings ▸ Apply closes the same hole through the same door: `TreePlanController.refresh()`
     * re-paints the current step, because a per-render palette leaves nothing to invalidate.
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
        // A read action, because this runs on the EDT and *both* halves touch the file model: `getDocument`
        // reads it directly, and `createViewer` fans out to the platform's own `editorCreated` listeners, one
        // of which (`LineStatusTrackerManager`) calls `getDocument` itself. Both are `softAssertReadAccess` —
        // a SEVERE in the log and nothing on screen, so the omission is invisible until the log is read.
        //
        // Write-intent rather than plain read, because that is what an action-driven path holds implicitly:
        // measured in the G1c′ round's log, the bench's initial build (from `actionPerformed`) was silent and
        // only its *buttons* asserted — a plain Swing listener inherits nothing. Constructing the node under
        // the same lock an action would have held is what makes the caller's context stop mattering.
        //
        // Measured twice: `getDocument` from the 1a′ bench, `createViewer` from the G1c′ round.
        editor = WriteIntentReadAction.compute<EditorEx> {
            val document = FileDocumentManager.getInstance().getDocument(spec.file)
                ?: error("no document for ${spec.file.path} — a node view needs a real file (constraint 3)")
            EditorFactory.getInstance().createViewer(document, project, EditorKind.PREVIEW) as EditorEx
        }
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
        // No `as? FoldingModelEx` here: [editor] is an `EditorEx`, whose `foldingModel` *is* one. The cast was a
        // leftover from when this held a plain `Editor`, and a null-safe call that can never be null reads as a
        // subscription that might silently not happen.
        editor.foldingModel.addListener(
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
        //
        // **Not inside a batch.** Rider's frontend adds its inlays through `InlayModel.execute(batch = true)`, and
        // `offsetToXY` asserts `!isInBatchMode` — so resyncing from `onAdded` threw a SEVERE per inlay (500+ in one
        // sandbox round). The batch's inlays are owed one resync, paid when it finishes; the platform clears the
        // flag before notifying (`InlayModelImpl.execute`), so `offsetToXY` is legal again by then.
        editor.inlayModel.addListener(
            object : InlayModel.Listener {
                private var owed = false
                private fun changed() {
                    if (editor.inlayModel.isInBatchMode) owed = true else resync(TRIGGER_INLAY)
                }
                override fun onAdded(inlay: Inlay<*>) = changed()
                override fun onUpdated(inlay: Inlay<*>) = changed()
                override fun onUpdated(inlay: Inlay<*>, changeFlags: Int) = changed()
                override fun onRemoved(inlay: Inlay<*>) = changed()
                override fun onBatchModeFinish(editor: Editor) {
                    if (!owed) return
                    owed = false
                    resync(TRIGGER_INLAY)
                }
            },
            listeners,
        )
        // Scrolling inside a node changes no height and no colour, so it reaches neither resync nor a
        // re-render — but it moves every line anchor in the node, which is exactly what a connector is drawn
        // between. See [onGeometryChanged].
        editor.scrollingModel.addVisibleAreaListener(
            VisibleAreaListener { e: VisibleAreaEvent ->
                if (e.oldRectangle?.y != e.newRectangle.y) onGeometryChanged?.invoke()
                // The *other* half of the same event, and the one nothing was listening for: see
                // [scheduleFitCheck].
                if (e.oldRectangle?.height != e.newRectangle.height) scheduleFitCheck()
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

        // No layout manager, where 1a′/1a″ used a BorderLayout. It was added for a corner control that had to float
        // *inside* the chrome's own header band — a region BorderLayout does not have — and that control is deleted
        // (the chevron beside the invoking box won verdict 4). The manual layout stays because the two survivors are
        // still stacked inside the chrome's insets, which is what a header band *is*: a `NORTH`/`CENTER` pair would
        // have to re-derive [headerBandHeight] from the strip's placement instead of being told it.
        add(editor.component)
        rebuildChrome()
        applyElision()
    }

    /** Header strip (if any), then the editor — both inside the chrome's insets. */
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
     * went with the chrome the reader scrapped.
     */
    var topEdgeDrawn: Boolean = true
        private set

    /** Told, not inferred: [CodeNestView] owns the leader mode and therefore owns this. */
    fun setTopEdgeDrawn(drawn: Boolean) {
        if (topEdgeDrawn == drawn) return
        topEdgeDrawn = drawn
        rebuildChrome()
    }

    /**
     * **Whether this node rounds its top corners while drawing no top edge** — [LeaderArt.roundsFrameTop], and a
     * second question rather than a second reading of [topEdgeDrawn].
     *
     * The two were one boolean until K3, on the reasoning that a frame with no top edge has no top corner. That
     * held only while the brace kept its own turn inside the leader strip, and keeping it there is what left a
     * radius of transparent band between the drawn line and this node's header — the gap a relation link crossing
     * the nesting made visible. See [LeaderArt.roundsFrameTop] for the whole of it.
     */
    var topCornersRounded: Boolean = false
        private set

    /** Told, not inferred, for the same reason [setTopEdgeDrawn] is. */
    fun setTopCornersRounded(rounded: Boolean) {
        if (topCornersRounded == rounded) return
        topCornersRounded = rounded
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

        elisionPasses++
        val model = editor.foldingModel
        // The numerator the last two rounds guessed at, taken before the batch rather than predicted after it:
        // what `clearFoldRegions()` is about to destroy, split into ours and the daemon's. See
        // [foldRegionsDestroyed] — an installed region and a destroyed one are different populations, and the
        // folding warning is attributable to at most one of them.
        val doomed = model.allFoldRegions
        val doomedOurs = doomed.count { r -> installedFolds.any { it === r } }
        foldRegionsDestroyed += doomed.size
        foldRegionsDestroyedOurs += doomedOurs
        installedFolds.clear()

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
                        foldRegionsInstalled++
                    }

                    NodeElision.SEALED -> {
                        try {
                            // `model` is already a `FoldingModelEx` (see [editor]); the `as? … ?: continue` this
                            // replaces was a silent skip that could never fire.
                            val region: FoldRegion? =
                                model.addCustomLinesFolding(gap.first, gap.last, ElidedLinesRenderer(elided))
                            if (region != null) {
                                installedFolds += region
                                foldRegionsInstalled++
                            }
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

    /**
     * **This step's narrower window**, or null for the frame's own [CodeNodeSpec.keep] — what
     * `TreePlanView.condense` sets for one step ([StepCondense]).
     */
    private var condensedKeep: List<IntRange>? = null

    /**
     * Show only [keep] (1-based ranges, inside [CodeNodeSpec.keep]) until called again, or the frame's own
     * `keep` again with null. Re-elides only on a change, so a fold the reader opened by hand stays open for as
     * long as the window does; true when it re-elided.
     */
    fun condense(keep: List<IntRange>?): Boolean {
        val next = keep?.takeIf { it != spec.keep }
        if (next == condensedKeep) return false
        condensedKeep = next
        applyElision()
        return true
    }

    /** The text of 1-based [line] of the file, null past its end — for [StepCondense.chain]. */
    fun lineText(line: Int): String? {
        val doc = editor.document
        if (line < 1 || line > doc.lineCount) return null
        return doc.charsSequence.subSequence(doc.getLineStartOffset(line - 1), doc.getLineEndOffset(line - 1)).toString()
    }

    /** 0-based line runs *not* covered by the kept window — the complement, which is what gets folded. */
    private fun gapsOutsideKeep(lastLine: Int): List<IntRange> {
        val keep = (condensedKeep ?: spec.keep)
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
        deemphasisTint(SpotlightRenderer.accentColor(), editor.colorsScheme.defaultBackground)

    // ── Geometry: size to content, with a cap ──

    /**
     * The height the folded document actually draws in, which is **not** `lineHeight × lineCount`:
     * `offsetToXY` is the only thing that knows where a folded document draws, and a custom fold region's own
     * height is part of the answer.
     *
     * **Plus [trailingBlockHeight], which is the half `offsetToXY` cannot see.** A block inlay pushes the text
     * *after* it down, so every inlay in the middle of the document is already inside that first term — which
     * is exactly why this term was absent for nine rounds and only shows up on a host whose call site is the
     * document's last line. There is no text after it to be pushed, so the frame hangs below a node sized as if
     * it were not there.
     */
    fun contentHeight(): Int {
        // Not defensive dressing: a listener can fire after teardown, and `offsetToXY` on a released editor
        // throws rather than returning anything. The last published height is the honest answer — the node is
        // gone, so nothing will lay it out again.
        if (disposed || editor.isDisposed) return lastContentHeight.coerceAtLeast(0)
        val h = editor.offsetToXY(editor.document.textLength).y + editor.lineHeight + trailingBlockHeight()
        lastContentHeight = h
        return h
    }

    /**
     * The block inlays drawn **below the document's last line** — the ones `offsetToXY` structurally cannot
     * report, because it answers where *text* is and there is no text below them.
     *
     * **Zero for every node that does not have one**, which is nearly all of them, so this adds nothing to any
     * layout an earlier round judged. It is non-zero in one shape: a host whose `callLine` is the last line of
     * its file. `CodeNestView.Nesting.attach` anchors the frame at `getLineEndOffset(callLine)` with
     * `showAbove = false`, and [CodeNestView.Nesting.textFollowsFrame] — "the load-bearing question of the
     * whole approach", whether the host's text is pushed past the frame — asks about `callLine + 1`, which on
     * the last line does not exist. It returned false and nothing acted on it; this is what acts on it.
     *
     * The same hole swallows a docked callout or a link spacer on the last line, and both are counted here for
     * the same reason: [effectiveCap] and [excerptTargetHeight] already allow for them, and `content` being the
     * one term that did not was what made the allowance unreachable.
     *
     * A collapsed nesting costs nothing here without a special case: `setCollapsed` hides the child component,
     * so the inlay resizes itself down to the leader strip and `heightInPixels` follows. A fold over the call
     * line is excluded outright — the frame is created with `showWhenFolded = false`, so the platform stops
     * drawing it while its line is elided, and counting it would make the node taller by a frame nobody sees.
     */
    fun trailingBlockHeight(): Int {
        if (disposed || editor.isDisposed) return 0
        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        val start = doc.getLineStartOffset(lastLine)
        val end = doc.getLineEndOffset(lastLine)
        var sum = 0
        for (inlay in editor.inlayModel.getBlockElementsInRange(start, end)) {
            if (!inlay.isValid || inlay.placement != Inlay.Placement.BELOW_LINE) continue
            if (editor.foldingModel.getCollapsedRegionAtOffset(inlay.offset) != null) continue
            sum += inlay.heightInPixels.coerceAtLeast(0)
        }
        return sum
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
     * *ceiling* is [effectiveCap], and the *content* is what there actually is to show. The reader's
     * decision was **no floor** — a 6-line member gets six lines — so the answer is simply the smallest of the
     * three that applies. `content` is in the min because a target taller than the folded document would leave
     * blank rows below the last line, which reads as a broken node rather than as a generous one.
     */
    fun publishedHeight(): Int {
        val content = contentHeight()
        // **The drag wins over every rule**, including `grow` — it is the one number a reader stated outright, and a
        // rule that overrode it would make the splitter feel broken rather than opinionated. Still `min`'d with the
        // content, because a body dragged past the end of the folded document is blank rows, which reads as a broken
        // node rather than as a generous one (the same reason `content` is in the min below).
        heightOverride?.let { return minOf(it + calloutAllowance(), content) }
        if (style.grow) return content
        val target = excerptTargetHeight()
        val cap = effectiveCap()
        return if (target == null) minOf(content, cap) else minOf(target, content, cap)
    }

    /**
     * **The reader's own height for this node's body**, set by dragging its bottom edge, or null while the rules size it.
     *
     * The G1c″ round asked for it by name — *"a capability to change subeditor height by dragging its bottom border
     * (like a splitter)"* — and it is the first thing in this class that a *person* decides rather than a measurement.
     * That is why it is a nullable override and not a default: null still means "sized by §1a⁵ 4's three numbers", so
     * a node the reader never touched keeps landing on its excerpt, and one they did keeps what they set through every
     * restyle, fold and step.
     *
     * It does **not** survive a rebuild, and deliberately: [CodeNestView.setTree] builds new node views, and carrying a
     * dragged height across a case change would apply one file's chosen height to another file's excerpt.
     */
    var heightOverride: Int? = null
        private set

    /**
     * Set (or clear, with null) the dragged height. Clamped to [minBodyHeight] — a drag past the top would otherwise
     * leave a node with no rows at all and no edge left to grab.
     */
    fun setHeightOverride(px: Int?) {
        val next = px?.coerceAtLeast(minBodyHeight())
        if (heightOverride == next) return
        heightOverride = next
        resync(TRIGGER_DRAG)
    }

    /** The floor a drag clamps to: [MIN_DRAG_ROWS] rows of the current font. */
    fun minBodyHeight(): Int =
        if (disposed || editor.isDisposed) 1 else (editor.lineHeight * MIN_DRAG_ROWS).coerceAtLeast(1)

    /**
     * **Room for the step's docked callout, margins included** — the G1c″ round's reversal of §1a⁵ 4.
     *
     * §1a⁵ 4 decided the opposite on purpose, and the KDoc on [embeddedHeight] still records the reasoning: counting
     * the callout makes a node change height on every step advance, which is constraint 9's coupling amplified. The
     * round chose the coupling anyway, having seen the alternative on real code — *"when during a step callout lands in
     * a subeditor (or leaves it) — its height should be adjusted by the height of the callout (with margins)"*. What
     * settled it is that the reveal-only fix ([SpotlightRenderer.revealCallout]) buys the box its visibility by
     * spending the *code's*: in a viewport sized to a 9-line excerpt, a three-line callout can only be shown by
     * scrolling three lines of the excerpt out of it.
     *
     * So the node grows by the box instead, and the height-changes-per-step cost is real and accepted: the inlay
     * listener installed in `init` already publishes it, and [resyncCounts]'s `inlay` counter is where it shows up.
     */
    fun calloutAllowance(): Int {
        if (disposed || editor.isDisposed) return 0
        val h = spotlight.calloutHeight()
        return if (h <= 0) 0 else h + 2 * CALLOUT_MARGIN
    }

    /**
     * [CodeNodeStyle.maxHeight] **plus whatever is embedded in this node** — the cap on the node's *own* code.
     *
     * Constraint 8's 600 px was measured on folded *code*, and an embedded child is not code: a host capped at a flat
     * 600 holding a 500 px child shows two lines of the caller behind a scrollbar, which is the interaction the
     * `growHosts` knob existed to escape. Adding what is embedded makes the cap mean the thing it was measured as, so
     * a host is capped exactly as hard as a leaf and its children are never the thing clipped.
     *
     * That is what leaves [CodeNestView]'s `growHosts` reaching **only the root** (`topLevelHost && style.growHosts`),
     * so a nested host lands on its excerpt like any other nested node whatever the knob says — the G1c′ round's
     * item 3.
     *
     * **[calloutAllowance] is in it for the same reason** (G1c″): a docked callout is not code either, and a cap that
     * counted it would give the box its room by taking rows away from the excerpt the node was sized to show.
     */
    private fun effectiveCap(): Int = style.maxHeight + embeddedHeight + calloutAllowance()

    /**
     * Pixels of nested child frame embedded in this node, and how much of it falls **inside this node's excerpt**.
     *
     * Told by [CodeNestView], never inferred: a node knows its own document and nothing about the tree, and the
     * frames are block inlays it did not create. Two numbers because they answer two questions — the total is what
     * [effectiveCap] must not count against the node's own code, and the excerpt's share is what
     * [excerptTargetHeight] has to include for a host to show its member *and* the expansion inside it.
     *
     * Deliberately **not** every inlay: the docked callout is one too, and counting it would make a node change
     * height on every step advance — constraint 9's coupling amplified, which §1a⁵ 4 decided against in favour of
     * letting the reveal move the viewport.
     */
    var embeddedHeight: Int = 0
        private set

    var embeddedInExcerptHeight: Int = 0
        private set

    fun setEmbeddedHeights(total: Int, inExcerpt: Int) {
        if (embeddedHeight == total && embeddedInExcerptHeight == inExcerpt) return
        embeddedHeight = total
        embeddedInExcerptHeight = inExcerpt
        // The one case `offsetToXY` cannot see, reported when it happens rather than left to be inferred from a
        // node that looks too short. Logged here because this method already no-ops when nothing moved, so it is
        // one line per real change and not one per layout pass.
        val trailing = trailingBlockHeight()
        if (trailing > 0) {
            log.info(
                "${spec.file.name}: ${trailing}px of embedded frame sits below the last line, where `offsetToXY` " +
                    "cannot see it — content ${contentHeight()}px, published ${publishedHeight()}px " +
                    "(embedded $total, inExcerpt $inExcerpt, grow=${style.grow}, cap ${effectiveCap()})",
            )
        }
        resync(TRIGGER_EMBEDDED)
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
     * **A page of scrollable room past this node's last line, so the landing's target is reachable at all.**
     *
     * G1c⁶, and it is [NestStyle.bottomSlack]'s argument arriving on a third surface. `scrollVertically` clamps to
     * `content − viewport`, so a member `y` px down the document can only reach the top of the viewport when there are
     * `y` px of content *below* it. Collapsing a nesting **inside** this node removes the entire height of the frame it
     * was holding, and once that leaves less than `y` px below the member the platform clamps — line 1, with the
     * landing correctly re-armed, correctly issued, and honestly unreachable. G1c⁵'s re-arm ([invalidateLandingAsHost])
     * was necessary and is not sufficient: it restored the *asking*, and nothing had ever supplied the *room*.
     *
     * The reader read the cause off the screen: *"This is probably due to limited visible line count after
     * SubInvoker.Apply — I expanded lines 54-170 block in MidInvoker, and tried to reproduce this behaviour again — and
     * it didn't reproduce."* Unfolding real rows below the member is the same fix by hand.
     *
     * **The platform's page is the exact amount and not a generous one**, which is why this is a setting and not a
     * number: the extent becomes `(doc + viewport) − viewport`, the document's own height, so **every** line in the
     * document can reach the top and none can reach past it. A pixel count would have to be re-derived on every
     * collapse against a height that keeps moving — constraint 20's own warning, and how [NestStyle.bottomSlack]'s
     * 240 px came to need a control.
     *
     * It is emphatically **not** [com.intellij.openapi.editor.EditorSettings.setAdditionalLinesCount], which is 0 here
     * for a reason stated where it is set: a phantom *line* is counted by `offsetToXY` and therefore by
     * [contentHeight], so it would make every node taller. A page at the bottom is added to the editor component's
     * preferred height and never to `offsetToXY`, so it moves the scroll extent and leaves [publishedHeight] alone —
     * the whole reason a one-setting fix is available here at all.
     */
    fun setLandingSlack(on: Boolean) {
        if (landingSlackWanted == on) return
        landingSlackWanted = on
        applyLandingSlack()
    }

    /** What [setLandingSlack] was asked for. What is actually *on* is [applyLandingSlack]'s answer. */
    private var landingSlackWanted = false

    /**
     * **The slack is granted only while the node has something to scroll**, and re-asserted from [resync] because
     * that is not a property of the node, it is a property of its current geometry.
     *
     * The page above is the room a landing needs; it is also, for a node whose viewport already holds all of its
     * content, **room to scroll into that holds nothing**. And it is the platform's own clamp that this switches
     * off: with the page gone, `content − viewport` is 0 and every scroller — [landOnExcerpt],
     * [SpotlightRenderer.revealCallout], [showContentBand] — is clamped to the first row without any of them
     * having to ask. That is how the **root** has always behaved, which is why the root was the one node the
     * artefact never touched: `setLandingSlack` is never called for it, so its extent was 0 and every scroll
     * landed at 0.
     *
     * Chasing the scrollers one at a time was the alternative, and the sandbox refuted it inside one run: guarding
     * the landing alone left `revealCallout` doing exactly the same thing two steps later — `content=669
     * viewportH=669 offset=314`, the callout reveal's own pad of room asked for below a box that was already
     * flush with the bottom. One lever, at the thing that makes the room, rather than a
     * guard per caller and a new one owed to every caller added later.
     *
     * Guarded on the value because this runs on every resync, and a settings write the platform may answer with a
     * refresh is not something to issue thousands of times for no change.
     */
    private fun applyLandingSlack() {
        if (disposed || editor.isDisposed) return
        val want = landingSlackWanted && !contentFitsViewport()
        if (editor.settings.isAdditionalPageAtBottom != want) {
            editor.settings.isAdditionalPageAtBottom = want
        }
    }

    /**
     * How far this node's viewport can actually scroll — `content − viewport`, the number `scrollVertically` clamps to.
     *
     * In [landingReport] because its absence is what let a report say `state=SATISFIED offset=66px` about a node the
     * reader was watching sit on line 1: the report measures with every nesting expanded, and the clamp only exists in
     * the collapsed state. `asked=66px extent=40px` says *unreachable by arithmetic*, which no pair of offsets can.
     */
    fun scrollExtent(): Int {
        if (disposed || editor.isDisposed) return -1
        val viewport = editor.scrollingModel.visibleArea.height
        return (editor.contentComponent.preferredSize.height - viewport).coerceAtLeast(0)
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
        val ownRows = (focus.last - focus.first + 1).coerceAtLeast(1) * editor.lineHeight
        // Plus whatever is nested *inside* those rows, at full height — the G1c′ round's item 3: "subhosts with
        // subhosts should be sized to their excerpt size + full sizes of subnodes". Without it a host that lands on
        // its member gets a viewport the size of the member's text and the expansion inside it fills the whole thing,
        // so the member is present and unreadable.
        //
        // Plus the step's callout, which is G1c″'s reversal of the same shape one level in — see [calloutAllowance].
        //
        // Plus the blank rows the step's link fan bought itself ([SpotlightRenderer.reserveLinkSpace]). Same
        // shape again, one round later: a row that grew by 13 px to hold five rails is 13 px of the excerpt
        // this node was sized to show, and not counting it lands the node 13 px short of its own last line.
        return ownRows + embeddedInExcerptHeight + calloutAllowance() + spotlight.linkSpacerHeight()
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
        // **There is nothing to land on when the viewport already holds the whole node**, and scrolling one
        // that does is not a landing but a loss: the excerpt is on screen before anything moves, so the only
        // thing the scroll can do is push the node's own first rows off the top.
        //
        // The case is `grow` — [publishedHeight] returns the content outright, so viewport == content — and
        // the `min` reaches it too whenever `content` is the smallest of the three. What makes it *visible*
        // rather than merely pointless is [setLandingSlack]: the page it adds below the last line makes the
        // offset reachable, and the **gutter cannot follow it there**. `EditorGutterComponentImpl` asks for
        // `EditorImpl.getPreferredHeight()`, page included, so this is not a platform asymmetry in the size
        // — it is one in *when the size is applied*. The gutter is the scroll pane's row header, and
        // `ViewportLayout` clamps a row header's position to `viewSize − extent` of the gutter **as it was
        // last laid out**; a page that arrives and leaves inside one step's turn never reaches that layout.
        // Measured on `build-service-plan`'s nested `SizePrefix` with `treeGrowNodes` on, one EDT turn
        // after the step: `content=874 viewportH=874 offset=471 viewPos=471 rowPos=0 gutH=874 gutPref=1704`
        // — the clamp was `max(0, 874 − 874)`, so twenty-one rows of text scrolled away from a gutter that
        // could not move, the `⋯ N lines ⋯` rows read as code against the wrong line numbers, and no
        // scrollbar existed to get back ([scrolls] is false under `grow`, correctly).
        //
        // Tested on the viewport rather than on `style.grow` for two reasons: it covers the `min`'s edition
        // of the same state, and it leaves the **unlaid-out** pass alone. Before the embedding settles the
        // viewport is 0, and that pass must still run and still clamp — [relandIfOwed] re-arming it is the
        // whole of the deferred-landing machinery, and a guard that swallowed it would land every nested node
        // on line 1.
        if (contentFitsViewport()) return
        val focus = spec.focusRange() ?: return
        val y = ReadAction.computeBlocking<Int?, RuntimeException> {
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
        landingAttempts++
        // **Latched on success, and this is constraint 20's whole point.** The old shape was a bounded number of
        // deferred passes that re-armed while `offset != request` — a bound on *passes*, when what kept moving was the
        // *target*: the embedded heights refresh, the callout's allowance arrives with a step, a drag rewrites the
        // extent. So the retry could agree with a stale target and call itself settled, which is what the G1c‴ round
        // read failing on the three-deep chain. Now the goal outlives the passes: [resync] re-arms it on every event
        // that moves the extent, and only reaching it stops that.
        if (scrollOffset() == y) landingSatisfied = true
    }

    /** The y [landOnExcerpt] last asked for, so a check can say "asked for 380px, got 0" rather than only "wrong". */
    var lastLandingRequest: Int = -1
        private set

    /**
     * True once the landing has actually been achieved, or has been given up on — see [landOnExcerpt].
     *
     * Public because it is the difference between two things a report must not conflate: a node sitting on its class
     * header because the landing *failed*, and one sitting somewhere else because a reveal moved it **on purpose**.
     */
    var landingSatisfied: Boolean = false
        private set

    private var landingAttempts = 0

    /**
     * True when a **reveal** is what retired the landing, rather than the landing succeeding or giving up.
     *
     * Kept separately from [landingSatisfied] because a check must not conflate them: a node sitting away from its
     * excerpt's first line is a *defect* if the landing claims to have got there, and *correct* if a step's reveal moved
     * it since. Conflating the two is what made the landing check report three failures at step 5 and none at step 1 on
     * one fixture — the same reveal, judged as a bug.
     */
    var revealTookOverLanding: Boolean = false
        private set

    /**
     * **Stop re-arming the landing**, because something with a better claim on this viewport has moved it.
     *
     * A step's reveal is that something: it scrolls the node to what the reader is being told about, and a landing
     * re-asserted afterwards would drag them back to the top of the excerpt mid-narration. §1a⁵ 4's landing is where a
     * node *opens*, not where it stays — a distinction that only became expressible when the landing stopped being a
     * one-shot.
     */
    fun releaseLanding() {
        landingSatisfied = true
        revealTookOverLanding = true
    }

    /**
     * **This node's viewport already holds all of its content**, so there is no excerpt to scroll *to* and the
     * landing is moot rather than owed. See [landOnExcerpt] for what scrolling one of these did.
     *
     * Deliberately **not** a latch. The state is a property of the current geometry and it reverses: turning
     * `Grow all nodes` back off, or a nesting inside this node expanding, caps the node below its content again and
     * the landing is owed once more. [landingSatisfied] would have to be un-set by every event that can do that,
     * which is the bookkeeping [invalidateLanding] already exists to get wrong — so this is asked afresh by each
     * caller that cares: [applyLandingSlack], which takes the room away; [relandIfOwed], which puts the offset
     * back; [landOnExcerpt], which must not scroll; [landingState], which must not report it as owed; and
     * [CodeNestView]'s landing passes, which must not keep retrying on its account.
     *
     * `viewport > 0` is what leaves the **unlaid-out** pass alone: before the embedding settles the viewport is 0,
     * and that pass must still run and still clamp exactly as it did, because [relandIfOwed] re-arming it is the
     * whole of the deferred-landing machinery.
     */
    internal fun contentFitsViewport(): Boolean {
        if (disposed || editor.isDisposed) return false
        val viewport = editor.scrollingModel.visibleArea.height
        return viewport > 0 && contentHeight() <= viewport
    }

    /**
     * Re-issue the landing if it is still owed — called by [resync] for every extent-changing trigger, and by
     * [CodeNestView]'s landing passes so that both go through **one** definition of "still owed".
     *
     * The attempt cap is the one guard that is not about correctness: a target inside a document shorter than the
     * viewport is unreachable at any offset, and without a cap that node would re-scroll on every fold the daemon
     * pushes for the rest of the session.
     */
    internal fun relandIfOwed() {
        if (!landsOnExcerpt || disposed || editor.isDisposed) return
        // **Before the latch, and before the drag's exemption**: a node whose viewport holds all of its content
        // belongs at offset 0 whatever its landing bookkeeping says, because every row it has is already on
        // screen and any offset at all is scrolled into [setLandingSlack]'s blank page.
        //
        // Declining to *issue* the landing is not enough, and the sandbox said so: the first pass runs while the
        // embedding is still being laid out, where `visibleArea.height` is 0 and the slack gives
        // `contentPrefH − 0` of extent — so the scroll is neither skipped by a viewport test nor clamped by the
        // platform. It succeeds, [landOnExcerpt] latches on success, and by the time the viewport is real nothing
        // asks again. Measured with `treeGrowNodes` on, `memory0` frame 1: `viewportH=0 asked=66 offset=66` on
        // the mount, still `offset=66` two steps later against `viewportH=594 content=594`.
        //
        // It cannot fight a reader's own scroll, because a node in this state has no scrollbar to have scrolled
        // with: [scrolls] is false exactly when the content fits, so the policy is `VERTICAL_SCROLLBAR_NEVER`
        // and the wheel does nothing — which is also why the reader could not undo it by hand.
        if (contentFitsViewport()) {
            returnToTop()
            return
        }
        if (landingSatisfied || heightOverride != null) return
        if (landingAttempts >= MAX_LANDING_ATTEMPTS) {
            landingSatisfied = true
            return
        }
        landOnExcerpt()
    }

    /**
     * Put this node's viewport back at its first row, animation off — the correction half of [relandIfOwed].
     *
     * Guarded on the offset rather than issued blind: `scrollVertically` fires a [VisibleAreaListener], which is
     * [onGeometryChanged], which re-measures the inlay and can come back here. The equality check is what makes
     * that a no-op on the second turn instead of a cycle.
     */
    private fun returnToTop() {
        if (scrollOffset() == 0) return
        val sm = editor.scrollingModel
        sm.disableAnimation()
        try {
            sm.scrollVertically(0)
        } finally {
            sm.enableAnimation()
        }
    }

    /**
     * **The landing is owed again, because the extent it was measured against was destroyed and rebuilt.**
     *
     * Collapsing a nesting hides the child and takes its frame to 0 px, which clamps this editor's scroll offset to 0;
     * expanding gives the extent back but not the offset. The latch of [landOnExcerpt] is what then made this permanent:
     * a node that had already landed was never re-armed, so a collapse-and-re-expand left the reader looking at line 1
     * of the file instead of the member — read off the G1c⁗ round's `chain` report as the one combination the re-arming
     * could not explain, `satisfied=true byReveal=false asked=66px offset=0px`.
     *
     * So constraint 20 has a second edition: a scroll must be re-armed by the events that move its extent, and a
     * collapse **destroys** the extent rather than moving it. Called on expand only — invalidating on the way down would
     * leave the landing owed while the viewport is 0 px, which burns the attempt cap against an unreachable target and
     * retires the landing for good, which is the very bug this fixes.
     */
    internal fun invalidateLanding() {
        if (!landsOnExcerpt) return
        landingSatisfied = false
        revealTookOverLanding = false
        // Reset too, not just cleared: the cap counts attempts against *one* extent, and this is a new one. The
        // re-scrolling stays bounded because only a reader's collapse gesture gets here.
        landingAttempts = 0
    }

    /**
     * **The host's edition of [invalidateLanding]** — a nesting *inside* this node collapsed or expanded, so this
     * node's own scrollable extent was destroyed and rebuilt while its document did not change at all.
     *
     * G1c⁵ is why this exists, and it is the same constraint-20 argument one level up from where G1c⁗ applied it. That
     * round fixed the **child**: a collapse takes its viewport to 0 and an expand gives the extent back but not the
     * offset. The host has the mirror problem and it is worse, because the host's viewport never goes away — only its
     * *content* shrinks, by the whole height of the frame it was holding. When that leaves the content shorter than the
     * viewport, `content − viewport` is zero and the platform clamps the host's offset to 0: line 1 of the file. The
     * host had latched `landingSatisfied` long before, so nothing re-issued it, and the reader got
     * *"collapsing the SubInvoker node resets the scroll of MidInvoker node moving to line 1, rather than start of
     * method, and putting the line of SubInvoker out of view."* In the same round's `chain` report:
     * `satisfied=true byReveal=false asked=66px offset=0px` — the exact signature [invalidateLanding]'s KDoc names as
     * the one combination the re-arming cannot explain, still standing because it was only ever cleared for the child.
     *
     * **A reveal still outranks it.** If a step's reveal owns this viewport, re-landing would drag the reader from what
     * they are being told about back to the top of the excerpt because something folded elsewhere in the node — so this
     * defers, where [invalidateLanding] does not. That asymmetry is the point of having two entry points rather than a
     * boolean parameter: the child's extent was *destroyed*, and a reveal into a destroyed extent has nothing left to
     * own, while the host's extent merely moved.
     *
     * A reader's own scroll is not distinguished from a landing here, because nothing records one — the same limit the
     * expand path has always had, and it is only reachable through a collapse gesture the reader made themselves.
     */
    internal fun invalidateLandingAsHost() {
        if (revealTookOverLanding) return
        invalidateLanding()
    }

    /**
     * **Why the landing is not where it wanted to be** — one derivation, because the check kept inferring it wrongly.
     *
     * G1c⁵'s reports read *"3 nested node(s) never reached the offset they asked for and have given up retrying"* on
     * nodes sitting exactly on their member, with `attempts=0`. Both halves of that sentence were wrong and the numbers
     * were right: `attempts=0` after an [invalidateLanding] means the landing has not been *issued* yet, not that it
     * was issued eight times and abandoned — and the check had no way to tell those apart because the attempt count
     * was private and the cap is private still. So the distinction is made here, where the cap lives, and the check is
     * told. Which is the same rule [LeaderArt]'s `closesTokenBox` follows for the outline, one subsystem over.
     */
    fun landingState(): LandingState = when {
        !landsOnExcerpt -> LandingState.EXEMPT
        revealTookOverLanding -> LandingState.RELEASED_BY_REVEAL
        landingSatisfied -> LandingState.SATISFIED
        contentFitsViewport() -> LandingState.WHOLLY_VISIBLE
        landingAttempts >= MAX_LANDING_ATTEMPTS -> LandingState.UNREACHABLE
        landingAttempts == 0 -> LandingState.NOT_ISSUED
        else -> LandingState.IN_FLIGHT
    }

    /**
     * `satisfied=true byReveal=false attempts=3 asked=66px offset=66px extent=812px` — the whole landing, for a report
     * that must say.
     *
     * `extent` joined it in G1c⁶ because the round before it produced a passing landing section about a node the reader
     * was watching fail: every number was right and the one that would have shown the clamp was missing. See
     * [scrollExtent].
     */
    fun landingReport(): String =
        "satisfied=$landingSatisfied byReveal=$revealTookOverLanding attempts=$landingAttempts " +
            "asked=${lastLandingRequest}px offset=${scrollOffset()}px extent=${scrollExtent()}px " +
            "state=${landingState()}"

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
        return ReadAction.computeBlocking<Int?, RuntimeException> {
            val doc = editor.document
            for (line in 0 until doc.lineCount) {
                val offset = doc.getLineStartOffset(line)
                if (editor.foldingModel.getCollapsedRegionAtOffset(offset) == null) return@computeBlocking line + 1
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
        return ReadAction.computeBlocking<Int?, RuntimeException> {
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
        // **Constraint 20**: every trigger here is something that moved this node's scrollable extent, which is exactly
        // the set of events that can turn a clamped landing into a reachable one. Cheap and self-limiting — it no-ops
        // the moment the landing is satisfied, which for a node whose viewport was ready on the first pass is
        // immediately. Before the height check rather than after, because a trigger that publishes no new height can
        // still have changed the extent (a fold inside the excerpt is the obvious one).
        // Before [relandIfOwed], because it decides the extent that a re-issued landing clamps against.
        applyLandingSlack()
        relandIfOwed()
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
     * **The viewport's own height moved, so every answer measured against it is stale** — and the two that
     * matter are [applyLandingSlack]'s and [relandIfOwed]'s, both of which are [contentFitsViewport] asked at
     * a moment.
     *
     * The hole this closes is that there was no such moment *after* a layout. [resync]'s triggers are all
     * **model** events — a fold, an inlay, a font, a drag, a restyle — and each of them fires before the
     * layout it causes: `contentHeight` is recomputed from the document there and then, while
     * `visibleArea.height` is still the size the node was laid out at for the **previous** state. Nothing
     * asked again once the new size existed, so the fit could only ever be judged one layout pass late.
     *
     * That is not a rare window. [calloutAllowance] re-sizes the node on **every step advance**, so a step
     * whose callout is taller than the last one's — a `note` arriving is enough — spends that turn with a
     * content height already counting the new box against a viewport that does not yet hold it. The node
     * concludes it must scroll, keeps [setLandingSlack]'s page on that basis, and whatever offset a reveal
     * then takes inside that page outlives the layout that made it wrong: the reader is left looking at a
     * node scrolled off its first row with no scrollbar to undo it ([scrolls] is false whenever the content
     * fits), which is the artefact [landOnExcerpt] describes one cause of. Re-asking here is also why a
     * reader who toggles any fold repairs it by hand — that goes through [resync], which is the same two
     * calls against geometry that has by then settled.
     *
     * **Deferred and coalesced**, for [scheduleWidthResync]'s reason and one of its own: the event arrives
     * from `JViewport.reshape`, i.e. from inside the layout pass, and the settled geometry is the whole point
     * of asking. It cannot re-arm itself — [returnToTop] and [landOnExcerpt] move the viewport's *y*, which
     * is the branch above, and the slack changes the content component's preferred height rather than the
     * viewport's.
     */
    private fun scheduleFitCheck() {
        if (disposed || editor.isDisposed || fitCheckPending) return
        fitCheckPending = true
        ApplicationManager.getApplication().invokeLater {
            fitCheckPending = false
            if (disposed || editor.isDisposed) return@invokeLater
            // The same two calls [resync] makes, in the same order and for the same reason: the slack decides
            // the extent that a re-issued landing — or a reveal that has already run — clamps against.
            applyLandingSlack()
            relandIfOwed()
        }
    }

    /** One deferred fit check per EDT turn, however many resize events the layout storm produced. */
    private var fitCheckPending = false

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
        return ReadAction.computeBlocking<LineAnchor?, RuntimeException> {
            val doc = editor.document
            val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
            val zero = line - 1
            if (zero < 0 || zero > lastLine) return@computeBlocking null
            val offset = doc.getLineStartOffset(zero)
            val xy = editor.offsetToXY(offset)
            val inContent = Point(xy.x, xy.y + editor.lineHeight / 2)
            val point = SwingUtilities.convertPoint(editor.contentComponent, inContent, this)
                ?: return@computeBlocking null
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

    /**
     * **What the current step occupies in this node, in this component's coordinates** — the active rows and the
     * docked callout together, clipped to the part of them the node's own viewport is actually showing.
     *
     * The G1c″ round's item 14 residue is why it exists: *"stepping through last steps the callouts in nested nodes go
     * out of view, because the nested node itself is out of view … node view gets scrolled to show contained callout,
     * but parent node doesn't."* An ancestor cannot ask a renderer two editors down what it is pointing at, and
     * scrolling the ancestor to the *call line* — which is what [CodeNestView.revealNode] used to do — only guarantees
     * the top of a frame that can be 600 px tall. So the node answers with a band and the ancestors scroll to it.
     *
     * **Clipped, not raw**, and that is the load-bearing part: only what is inside this node's viewport can be brought
     * into view by scrolling something *outside* it, so a band the node has itself scrolled away is correctly null.
     */
    /**
     * One row's band in this node's own coordinates — [stepBand]'s shape for a single line, and where a
     * portal's click is scrolled to.
     *
     * Recomputed from the line rather than passed in as a rectangle, because `CodeNestView.revealRow` runs a
     * deferred second pass (constraint 20) and a row's y inside the node moves in between when a callout above
     * it resizes. A line number does not move.
     */
    fun lineBand(line: Int): Rectangle? {
        if (disposed || editor.isDisposed) return null
        val inContent = ReadAction.computeBlocking<Rectangle?, RuntimeException> {
            spotlight.lineBounds(line)
        } ?: return null
        return bandOf(inContent)
    }

    fun stepBand(): Rectangle? {
        if (disposed || editor.isDisposed) return null
        // Constraint 12: `stepBounds` reads the line map through `offsetToXY`. `ReadAction.computeBlocking` like the
        // other twelve in this package — see the cleanup note on the same call in `CodeNestView`.
        val inContent = ReadAction.computeBlocking<Rectangle?, RuntimeException> { spotlight.stepBounds() } ?: return null
        return bandOf(inContent)
    }

    /** A content-coordinate rectangle as a scrollable band of this node, or null when none of it shows. */
    private fun bandOf(inContent: Rectangle): Rectangle? {
        val band = SwingUtilities.convertRectangle(editor.contentComponent, inContent, this)
        val mine = Rectangle(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
        val visible = band.intersection(mine)
        if (visible.isEmpty) return null
        // Full width, degenerate x: every caller scrolls vertically, and a band's own x is the code's indent.
        return Rectangle(0, visible.y, mine.width, visible.height)
    }

    /**
     * **Pixels between the top of this step's band and the top of this node's own viewport** — negative when the band is
     * cut off above, null when there is no step or no viewport.
     *
     * The number G1c‴ finding 9 is about, and it has to be this rather than a boolean: *"places callout partially hidden
     * (scrolled out) from top side"* is a **quantity**, and "is it visible" cannot distinguish a box one pixel under the
     * edge from one scrolled half away. Nor can [stepBand], which clips to what the node shows and so reports a band
     * that is merely *shorter* — a silent failure, since a shorter band is exactly what a smaller step looks like.
     *
     * Read in the node's own editor content coordinates, unclipped, which is the only space where "above the viewport" is
     * expressible at all.
     */
    fun stepTopClearance(): Int? {
        if (disposed || editor.isDisposed) return null
        val inContent = ReadAction.computeBlocking<Rectangle?, RuntimeException> { spotlight.stepBounds() } ?: return null
        val view = editor.scrollingModel.visibleArea
        if (view.height <= 0) return null
        return inContent.y - view.y
    }

    /**
     * **Re-issue this node's own step reveal**, for the case where the step was rendered while the node had no viewport.
     *
     * G1c‴ finding 9: *"stepping into collapsed node expands it, but places callout partially hidden (scrolled out) from
     * top side."* A collapsed nesting hides its child, so a step landing there scrolls an editor whose visible area is
     * 0 px tall — `scrollTo` clamps and `revealCallout`'s own guard returns — and then the expansion hands it a real
     * viewport with the scroll already spent. Nothing outside the node can repair that ([stepBand] clips to what the
     * node shows, and correctly so), which makes this the one place it can be repaired.
     *
     * Returns whether it actually re-revealed, so [CodeNestView.revealNode]'s report can distinguish "re-revealed" from
     * "no step here" instead of implying the first.
     */
    fun reRevealStep(): Boolean {
        if (disposed || editor.isDisposed) return false
        val revealed = spotlight.reRevealStep()
        // Only when a step really was re-revealed. Releasing unconditionally would retire the landing of every node a
        // reveal merely *walked past* — and this runs on the whole nesting chain, so that is most of them.
        if (revealed) releaseLanding()
        return revealed
    }

    /**
     * **Scroll this node's own viewport to [line]** — the row a click named, *inside* this node.
     *
     * [CodeNestView.revealNode]'s chain walk scrolls a node's **ancestors**; nothing in it scrolls the node itself,
     * because a step's reveal has already done that per editor (`SpotlightRenderer.revealCenter`) by the time the
     * walk runs. A row a *link* points at has had no such pass, and [lineBand] answers null for a row that is not
     * showing — so the walk fell back to "show the whole node", which top-aligns it and leaves the named row below
     * the fold.
     *
     * That is the reported defect, exactly: following a portal into a **collapsed** node expanded it at its
     * excerpt's first line and stopped there, and a second click — by which time this scroll had happened as a
     * side effect of [SpotlightRenderer.landOn] on the first — worked. One gesture, one destination.
     *
     * The landing is released for [showContentBand]'s own reason, and here it is not conditional on having moved:
     * expanding a nesting re-arms the landing ([invalidateLanding]), which would otherwise pull this node back to
     * the top of its excerpt a pass later and undo the reveal even when the row was already in view. A row the
     * reader clicked outranks where the node opens — §1a⁵ 4's distinction, applied to a click instead of a step.
     */
    fun showOwnRow(line: Int): Boolean {
        if (disposed || editor.isDisposed) return false
        val inContent = ReadAction.computeBlocking<Rectangle?, RuntimeException> {
            spotlight.lineBounds(line)
        } ?: return false
        showContentBand(inContent)
        releaseLanding()
        return true
    }

    /**
     * Scroll this node's editor the **minimum** needed to show [inContent] (a rectangle in editor content
     * coordinates), and answer where that band then sits in *this component's* coordinates — so the caller can hand
     * the result to the next node up the nesting chain.
     *
     * Minimum rather than centred, because this runs on every ancestor of every step: a centring scroll at each level
     * moves code the reader is reading for no reason, and the thing being revealed is already adjacent to what is on
     * screen. The clamp `min(bottom - viewport, top)` is [SpotlightRenderer.revealCallout]'s, for its reason — a band
     * taller than the viewport must lose its bottom rather than its top.
     *
     * The returned y is computed from the scroll offset **read back after the scroll**, not from a Swing conversion:
     * `scrollVertically` clamps to the scrollable extent, and the component bounds behind a conversion are one layout
     * pass behind. Constraint 17's lesson stated one axis over — the chain is walked synchronously, so every number in
     * it has to be readable synchronously.
     */
    fun showContentBand(inContent: Rectangle): Rectangle {
        val whole = Rectangle(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
        if (disposed || editor.isDisposed) return whole
        val sm = editor.scrollingModel
        val view = sm.visibleArea
        val current = sm.verticalScrollOffset
        var wanted = current
        if (view.height > 0) {
            if (inContent.y < current) {
                wanted = inContent.y
            } else if (inContent.y + inContent.height > current + view.height) {
                wanted = minOf(inContent.y + inContent.height - view.height, inContent.y)
            }
        }
        if (wanted != current) {
            // A reveal that actually moved this viewport outranks the landing, so the landing stops asking: §1a⁵ 4 says
            // where a node *opens*, and pulling the reader back to the top of the excerpt mid-step is not that. Only on
            // a real scroll — this runs on every ancestor of every step, most of which do not need to move.
            releaseLanding()
            // Animation off, so the offset read back below is the one in force and not a frame of an animation.
            sm.disableAnimation()
            try {
                sm.scrollVertically(wanted.coerceAtLeast(0))
            } finally {
                sm.enableAnimation()
            }
        }
        val offset = sm.verticalScrollOffset
        // The **viewport's** origin, converted, plus the band's offset inside the scrolled view — not
        // `editor.component.y`, which would assume the viewport sits flush at the top of the editor's component (it
        // does not when the platform puts a notification or sticky-lines panel above it), and not a conversion of the
        // *content* component, whose own position is what the scroll just moved. A viewport does not move when its view
        // scrolls, so this conversion is the one that cannot be a layout pass stale.
        val viewportTop = editor.contentComponent.parent
            ?.let { SwingUtilities.convertPoint(it, Point(0, 0), this).y }
            ?: editor.component.y
        return Rectangle(0, viewportTop + (inContent.y - offset), whole.width, inContent.height)
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
                border = NodeFrameBorder(line, weight, topEdgeDrawn, topCornersRounded)
                val strip = JBLabel(" " + displayPath()).apply {
                    font = titleFont()
                    foreground = titleColor()
                    isOpaque = true
                    background = headerBandColor(isActive, SpotlightRenderer.accentColor())
                    border = BorderFactory.createEmptyBorder(STRIP_PAD, STRIP_PAD + 2, STRIP_PAD, STRIP_PAD)
                }
                headerStrip = strip
                add(strip)
            }

            // A border and no caption — what 1a‴ verdict 16 actually asked for, and the 1a⁗ round's answer to
            // the borderless variant that overshot it: "no border is strictly worse than plain". §1a⁵ 2 made it
            // the value the *rule* picks for a nested child in the same file as its caller.
            NodeChrome.PLAIN -> {
                border = NodeFrameBorder(line, weight, topEdgeDrawn, topCornersRounded)
            }
        }
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

    private fun titleFont(): Font = titleFont(editor.colorsScheme)

    private fun titleColor(): Color = titleColor(isActive, SpotlightRenderer.accentColor())

    /**
     * The node's label. Three forms because 1c has to look at them: the full path is unambiguous and
     * enormous, the basename is short and ambiguous exactly in the case the same-file fixture exists to test,
     * and the project-relative path is the compromise nobody has photographed yet.
     */
    private fun displayPath(): String =
        captionText(style.path, spec.file.path, projectRelativePath(), spec.file.name, spec.title)

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
     * **The top corners are square when both [drawTop] and [roundTop] are false** — `CORNERS`, whose diagonals
     * land on them, so a radius there would open a gap exactly where a diagonal arrives. That is what dissolved
     * 1a⁗'s rounded-corner defect rather than working around it. K3 split the second corner case back out
     * ([roundTop]): `BRACE`'s run now stops one radius short on the row above and the turn into the wall is drawn
     * here, which is what puts the drawn line on the header instead of a radius above it. The bottom corners keep
     * [LeaderArt.ARC] as they always did — the one radius the brace's turn and the token box also use.
     *
     * The path itself is [LeaderArt.framePath], shared with the collapsed stub — see there for why.
     */
    class NodeFrameBorder(
        private val line: Color,
        private val weight: Int,
        private val drawTop: Boolean,
        private val roundTop: Boolean,
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
                // One path from the left wall round to the right wall, so the corners cannot drift apart the way
                // two `drawArc` calls and four `drawLine`s did — and it lives in [LeaderArt.framePath] rather than
                // here, because the collapsed stub draws the same shape and drew it with its own top edge until the
                // G1c′ round's item 16.
                g2.draw(LeaderArt.framePath(x, y, w, h, drawTop, roundTop))
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
        const val TRIGGER_EMBEDDED = "embedded"
        const val TRIGGER_DRAG = "drag"

        /**
         * Elision passes run, and fold regions installed by them, **across every node since the IDE started**.
         *
         * These exist to be a *denominator*. The bench's log gate counts a Rider folding warning per run — 103
         * across G1c⁷'s four reports — and three rounds of counting it produced no attribution, because a count
         * with no denominator is not a rate. The obvious guess (a per-restyle rebuild) is refuted by this file:
         * [applyElision] is called from the constructor and from [restyle] **only when the elision *mode* changed**,
         * and no restyle in the bench changes it. What does scale with the warning is node *construction*, and a
         * report performs two whole-view rebuilds of its own in its disposal check.
         *
         * So these are printed beside the log's count rather than a fix being written against a guess. They survive
         * a rebuild on purpose — per-node counts ([requestedFolds]) cannot see the nodes a rebuild destroyed, which
         * is exactly the population the warning comes from.
         */
        var elisionPasses = 0
            private set

        /** Fold regions [applyElision] successfully installed since startup. See [elisionPasses]. */
        var foldRegionsInstalled = 0
            private set

        /**
         * Fold regions **destroyed** by [applyElision]'s `clearFoldRegions()` since startup, and how many of them
         * were ours.
         *
         * The verification round's finding 2 is why this exists and why it is a *third* counter rather than an
         * inference from the first two. That round confirmed the denominator exactly — 8 rebuilds × 3 nodes × 3 gaps
         * = 72 regions installed over 24 elision passes, so [applyElision] fires once per node construction and
         * nowhere else — and **refuted the numerator**: the session logged 92 folding warnings against those 72
         * inserts, so the warning cannot be one per insert of ours. The surviving candidate is that
         * `clearFoldRegions()` also destroys the **daemon's** 13–25 regions per node, whose re-add hits the same
         * duplicate complaint. That candidate predicts a number this class was not counting: not what we add, but
         * what we remove.
         *
         * So `foldRegionsDestroyed - foldRegionsDestroyedOurs` is the daemon's share, and it is the quantity to
         * compare the log's count against. **Two attributions have already failed here; this one arrives as a
         * counter rather than as a sentence**, which is the rule that round wrote.
         *
         * Read before the batch opens, because inside it the model is being emptied — a count taken after the clear
         * is zero by construction, which is how a counter comes to agree with any hypothesis at all.
         *
         * **A zero here is a result, not a broken instrument**, and saying so in advance is the discipline the last
         * two attributions skipped: [applyElision] runs from the constructor, against a viewer editor created
         * moments earlier, so the daemon may have had nothing installed yet to destroy.
         *
         * **That is what it measured — `0/0/0` — and it closed the question.** With the destroy candidate refuted,
         * the session's 12 warnings resolved against the ranges this method installs: 6 distinct `(start, end)`
         * pairs, each logged once per view build, **none of them ours** (ours are disjoint by construction, all 9
         * were accepted, and every one of our first gaps starts at offset 0 where none of the rejected ones does).
         * Classified, all six **cross our last gap** — the one running to the end of the document — while
         * *containing* our middle one, and a fold tree takes nesting or disjointness and refuses crossing. So the
         * warnings are **the daemon's own class-body and member-body folds being refused because our elision
         * straddles their ends**: ~2 per node per folding pass, a property of the file's block structure rather than
         * of our insert count, which is why no denominator of ours ever matched it. Benign — nothing of ours is
         * lost, the daemon's other 13–25 regions per node land normally (constraint 5), and the only cost is that
         * those two folds cannot be hand-folded inside a node view, which a read-only excerpt does not want.
         *
         * The counters stay as the **guard** on that answer, not as a search for it: a non-zero here means something
         * now installs folds before the constructor's pass, and the attribution would need re-testing. Aligning our
         * gap boundaries to the enclosing block's would silence the warnings outright — a note for Phase 2's
         * `keep` → gap derivation, not a defect to fix here.
         */
        var foldRegionsDestroyed = 0
            private set

        /** The ours-half of [foldRegionsDestroyed] — subtract it to get the daemon's. */
        var foldRegionsDestroyedOurs = 0
            private set

        /**
         * Ceiling on [landOnExcerpt] re-attempts before the landing gives up and latches.
         *
         * Not a convergence bound — constraint 20's point is that the *goal* outlives the passes — but a stop for the
         * case where the target is unreachable at any offset (a focus range inside a document shorter than the
         * viewport). Without it that node would re-scroll on every fold the daemon pushes, for the session.
         */
        private const val MAX_LANDING_ATTEMPTS = 8

        /**
         * Rows a dragged body may not go below. Two, not one: at one row the node's bottom edge and its header band
         * are the same few pixels and there is nothing left to grab to drag it back open.
         */
        private const val MIN_DRAG_ROWS = 2

        /**
         * Air above and below the docked callout when a node reserves room for it ([calloutAllowance]).
         *
         * The round asked for the box's height *"with margins"* and did not say how much; 4 px is the smallest gap that
         * keeps the box off the frame's own border, and it is doubled because the box can dock above *or* below the
         * active lines (`calloutBelow`), so the allowance cannot know which end it is paying for.
         */
        private const val CALLOUT_MARGIN = 4

        /**
         * Below [SpotlightRenderer]'s own layer (`SELECTION - 1`), so the active glow and the dim roles drawn
         * *inside* a node always win over the node-level wash drawn *around* them.
         */
        private val EMPHASIS_LAYER = HighlighterLayer.SELECTION - 3

        /** How far the non-active wash is pulled toward the background. Faint on purpose: it must not read as a highlight. */
        private const val DEEMPHASIS_MIX = 0.10

        private const val HEADER_ACCENT_MIX = 0.18
        internal const val STRIP_PAD = 3

        internal fun placeholderFor(elidedLines: Int): String = "  ⋯  $elidedLines lines  ⋯  "

        // ── The four style rules, in the form something without an Editor can ask them ──
        //
        // Each is the *only* statement of its rule: the instance methods above are one-line calls into these,
        // and `TreePreview` calls the same five. A settings preview that restated any of them would be the
        // deleted `spikes/` again — a second derivation that agrees until one of the two is edited.

        /**
         * The non-active node's wash: the accent pulled most of the way to the given background.
         *
         * The accent is a parameter and not [SpotlightRenderer.accentColor] because the preview paints the
         * swatch the reader is holding, which the settings have not saved yet.
         */
        internal fun deemphasisTint(accent: Color, background: Color): Color =
            SpotlightRenderer.blendToward(accent, background, DEEMPHASIS_MIX)

        /** The caption band's own background — accent-tinted while the node is the active one. */
        internal fun headerBandColor(active: Boolean, accent: Color): Color =
            if (active) SpotlightRenderer.blendToward(accent, JBColor.PanelBackground, HEADER_ACCENT_MIX)
            else JBColor.PanelBackground

        /** The caption's face: the scheme's code font, bold, one point down, floored at 9. */
        internal fun titleFont(scheme: EditorColorsScheme): Font = scheme.getFont(EditorFontType.PLAIN)
            .deriveFont(Font.BOLD, (scheme.editorFontSize - 1).coerceAtLeast(9).toFloat())

        /** The caption's ink — the shared outline colour on the active node, grey elsewhere. */
        internal fun titleColor(active: Boolean, accent: Color): Color =
            if (active) LeaderArt.leaderBase(accent) else JBColor.GRAY

        /**
         * **The [NodePath] rule**, with the three strings supplied rather than resolved: only
         * `PROJECT_RELATIVE` needs a [Project], and that is the one thing a settings preview cannot have.
         * Passing all three keeps the `when` here, where the node reads it.
         */
        internal fun captionText(
            path: NodePath,
            full: String,
            projectRelative: String?,
            name: String,
            title: String,
        ): String {
            val suffix = title.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""
            return when (path) {
                NodePath.FULL -> full
                NodePath.BASENAME -> name
                NodePath.PROJECT_RELATIVE -> projectRelative ?: name
            } + suffix
        }
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
     * **Supplied by the plan since Phase 2**: a frame marks one `keep` range `"role": "focus"`, and
     * `PlanLoader` decides it once — stated where the plan states it, inferred and *reported* where it does
     * not. `TreePlanView` always passes it, so a production node is told rather than guessing.
     *
     * Still nullable, and [focusRange]'s guess still exists, because the spike benches build specs from text
     * anchors with no plan behind them. That is the only remaining caller of the guess; it goes when the
     * benches do.
     */
    val focus: IntRange? = null,
) {
    /**
     * The excerpt of interest, or null if this node has nothing to land on.
     *
     * **The guess is "the second kept range"**, and it is a guess about *authoring* rather than about code: a node
     * keeps its class header first and the member it is about second, which is what every acceptance plan did
     * and what `plan-schema.md` requires. A single-range node has no separate header,
     * so its one range *is* the excerpt.
     */
    fun focusRange(): IntRange? {
        focus?.let { return it }
        // Fallback only for a spec with no plan behind it (the benches). A plan-built node has [focus] set,
        // and `PlanLoader` has already reported the case where it had to make this same guess itself — so an
        // inferred landing is visible in the report rather than happening quietly down here.
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
 * **Where a node's landing stands** — the six states [CodeNodeView.landingState] distinguishes, and the reason it is
 * an enum and not a pair of booleans.
 *
 * G1c⁵ read three nodes as *"never reached the offset they asked for and have given up retrying"* when they were
 * sitting exactly on their member, and one node as landed when it was on line 1. Both verdicts came from a check
 * inferring the state from [CodeNodeView.landingSatisfied] alone, which cannot separate *not yet* from *not ever*.
 */
enum class LandingState {
    /** No excerpt target — the root, which §1a⁵ 4 exempts. */
    EXEMPT,

    /** Landed where it asked to be. */
    SATISFIED,

    /** A step's reveal moved this viewport on purpose and the landing has stood down — [CodeNodeView.releaseLanding]. */
    RELEASED_BY_REVEAL,

    /**
     * Owed, and **not issued since the last invalidation** — no pass has run yet.
     *
     * The deferred passes and the resync triggers are both still to come, so this is *in flight* rather than failed.
     * Read as a failure it turns every collapse into a red report; read as a success it hides a landing that will
     * never be issued because nothing is left to trigger it. It is a distinct state for exactly that reason.
     */
    NOT_ISSUED,

    /**
     * Nothing to land on: the viewport holds the whole node, so the excerpt is on screen at offset 0.
     *
     * Distinct from [SATISFIED] because it is not latched and reverses — see [CodeNodeView.contentFitsViewport].
     * Distinct from [EXEMPT] because this node *does* land on its excerpt; it just has nowhere to go right now.
     */
    WHOLLY_VISIBLE,

    /** Owed, issued, not there yet, and still under the attempt cap. */
    IN_FLIGHT,

    /** Issued up to the cap and never reached: the target is unreachable at any offset, not a pass that was missed. */
    UNREACHABLE,
}

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
 *
 * Four of its six fields are user settings as of Phase 4 ([com.audioexplainer.tree.TreeStyle]); [chrome] is
 * assigned by role and would be overwritten, and [maxHeight]'s *ordering* against the excerpt and the
 * content is constraint 8 rather than a preference. **Data, so a re-style can compare** — see
 * [TreePlanView.restyle].
 */
data class CodeNodeStyle(
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

    private fun label() = labelFor(elidedLines)

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
        paintSealed(
            g, targetRegion, font(editor), editor.colorsScheme.defaultForeground,
            SpotlightRenderer.accentColor(), elidedLines,
        )
    }

    internal companion object {
        const val PAD = 8
        const val RULE_TAIL = 40
        const val RULE_MIX = 0.55
        const val TEXT_MIX = 0.35

        /** The sealed row's text — [CodeNodeView.placeholderFor] is the openable one's, and they differ. */
        internal fun labelFor(elidedLines: Int): String = "⋯  $elidedLines lines  ⋯"

        /**
         * **The sealed elision row**, drawn from a rectangle, a font and two colours rather than from a
         * [CustomFoldRegion] — so `TreePreview` draws the row the reader will actually get. The renderer above
         * is now this call plus the scheme lookups only a live editor can do.
         */
        internal fun paintSealed(
            g: Graphics2D,
            targetRegion: Rectangle2D,
            font: Font,
            fg: Color,
            accent: Color,
            elidedLines: Int,
        ) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.font = font
                val fm = g2.fontMetrics
                val text = labelFor(elidedLines)
                val x = targetRegion.x.toInt() + PAD
                val baseline = targetRegion.y.toInt() + (targetRegion.height.toInt() + fm.ascent - fm.descent) / 2
                val midY = (targetRegion.y + targetRegion.height / 2).roundToInt()
                val w = fm.stringWidth(text)

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
    }
}
