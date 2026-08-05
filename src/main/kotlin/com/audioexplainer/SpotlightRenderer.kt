package com.audioexplainer

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.GeneralPath
import javax.swing.Icon
import javax.swing.Timer
import kotlin.math.abs

/**
 * The editor "spotlight" **drawing** stack — "given an [Editor], a segment and which of its highlights is
 * active, draw it". Originally the back half of [EditorHighlighter]; split out in Phase 1a of
 * `TreeOfCode.md` so the tree-of-code node views can drive the same decorations without going through the
 * walkthrough listener, which resolves files and opens tabs. Nothing here resolves a file, opens an editor
 * or touches a thread: [EditorHighlighter] still owns all three.
 *
 * A port of code-explainer's `vscode-extension/src/highlight.ts`. Reproduces the four VS Code
 * `TextEditorDecorationType` layers with IntelliJ [RangeHighlighter]s:
 *
 * | upstream decoration | this port |
 * |---|---|
 * | `dimDecoration` (opacity 0.35, outside segment)        | whole-line foreground blended 35 % toward bg |
 * | `segmentDimDecoration` (opacity 0.55, non-active highlights) | whole-line foreground blended 55 % |
 * | `activeDecoration` (soft white bg + gold overview ruler)     | whole-line bg glow + gold error-stripe mark |
 * | `gapDecoration` (opacity 0.55 + dotted gold left border)     | 55 % foreground + gold gutter bar |
 *
 * Two decorations have no upstream counterpart: a **docked callout** — a block [Inlay] above the active
 * lines carrying the highlight's own prose aside (`note`, else the short `explanation` label) and its
 * one-line worked `example`; and **relations** ([Segment.relations]) — a drawn arrow between two lines of
 * this file, or a clickable gutter pointer when the other end is somewhere this editor cannot point at.
 * Both are additive and settings-gated; a plan carrying none of those fields renders exactly as before.
 *
 * (A third, a numbered gutter step badge with click-to-replay, was built and then removed — the number
 * duplicated the sidebar's own step list and the click duplicated its outline, so it cost gutter width for
 * nothing. The sidebar remains the only place that answers "which step is this".)
 *
 * Notes on the mapping: VS Code decorations use CSS `opacity`; IntelliJ has no per-highlighter
 * opacity, so we emulate dimming by overriding the whole-line foreground with a colour blended
 * toward the editor background (this flattens syntax colours on dimmed lines, which is the intended
 * "de-emphasise" effect). The gap layer's dotted CSS left-border becomes a gutter [LineMarkerRenderer]
 * bar — the closest native analogue.
 *
 * **One instance per editor, and that is load-bearing rather than tidy.** Phase 0 measured that
 * decorations are per-*editor*, not per-document (`MultilineTreeFindings1.md` §6.4): two editors over one
 * document shared none of our highlighters. So every piece of decoration state — [tracked],
 * [trackedDisposables], the flash pair, and the segment bounds a step render dims around — lives here
 * against the one [editor] this renderer was built for, and a surface showing N editors builds N of these.
 * Sharing one across editors is what the split exists to prevent.
 *
 * **EDT only.** Every method below mutates editor state; none of them marshals, because the callers
 * ([EditorHighlighter]'s `runOnEdt` bodies, and later the node views' own EDT construction) are already
 * there. The mutable state is therefore touched serially on the EDT and needs no locking.
 *
 * [navigate] is the one thing drawing cannot do for itself: a relation gutter pointer's click has to open
 * a file, which means VFS resolution off the EDT. The owner supplies it — [EditorHighlighter] passes its
 * own resolver, and a node view will pass whatever "go there" means on its surface.
 */
class SpotlightRenderer(
    val editor: Editor,
    private val navigate: (Endpoint) -> Unit = {},
) {

    private val log = Logger.getInstance(SpotlightRenderer::class.java)

    /** Current segment range (0-based, inclusive), tracked so [spotlightStep] can dim around it. */
    private var segStart = 0
    private var segEnd = 0

    /** Highlighters we added to [editor]'s markup model, so we can clear them on transition. */
    private val tracked = mutableListOf<RangeHighlighter>()

    /**
     * Decorations the markup model does **not** own — today block-inlay callouts, later animation timers
     * and custom renderers. Removing a [RangeHighlighter] does not touch these, so they are disposed
     * explicitly in [clear] alongside it. Any future decoration belongs here.
     */
    private val trackedDisposables = mutableListOf<Disposable>()

    /**
     * The token-flash highlighter currently on screen, held **outside** [tracked] on purpose: the fade
     * replaces it once per frame, and pushing a dozen short-lived entries into [tracked] per step would
     * turn that list into churn. Its lifecycle belongs to the flash timer, which is what goes in
     * [trackedDisposables] — see [startTokenFlash].
     */
    private var flashHighlighter: RangeHighlighter? = null

    /**
     * The running fade's cancel handle (also in [trackedDisposables], so a segment transition still stops it).
     * Held separately so [startTokenFlash] can be called unconditionally: a second flash cancels the first
     * instead of leaving two timers writing [flashHighlighter].
     */
    private var flashTimer: Disposable? = null

    // ── Render API (EDT only) — mirrors highlight.ts ──

    /** Dim everything outside `[startLine, endLine]` (1-based, inclusive) — the segment spotlight. */
    fun spotlightSegment(startLine: Int, endLine: Int) {
        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        val zeroStart = (startLine - 1).coerceIn(0, lastLine)
        val zeroEnd = (endLine - 1).coerceIn(zeroStart, lastLine)

        segStart = zeroStart
        segEnd = zeroEnd

        clear()

        val c = colors()
        if (zeroStart - 1 >= 0) addLineRange(0, zeroStart - 1, c.dim)
        if (zeroEnd + 1 <= lastLine) addLineRange(zeroEnd + 1, lastLine, c.dim)

        revealCenter(zeroStart, zeroEnd)
    }

    /**
     * Record the segment's bounds (1-based, inclusive) **without** drawing — for a step render that is not
     * preceded by a [spotlightSegment], which is how [EditorHighlighter.onHighlight] arrives. [spotlightStep]
     * expands its effective range from these, so a step rendered against stale bounds would dim the wrong
     * side of the code.
     */
    fun setSegmentBounds(startLine: Int, endLine: Int) {
        val lastLine = (editor.document.lineCount - 1).coerceAtLeast(0)
        segStart = (startLine - 1).coerceIn(0, lastLine)
        segEnd = (endLine - 1).coerceIn(segStart, lastLine)
    }

    /**
     * Spotlight highlight [index] of [segment]: undim the active lines, apply gold glow, dim the rest of
     * the (possibly expanded) segment, then add the callout and relations. The segment's full highlight
     * list lets us keep highlights that fall outside the declared segment bounds visible, and distinguish
     * non-active highlight lines from context ("gap") lines.
     *
     * Returns false — having drawn nothing — when [index] names no highlight, so an empty `highlights` list
     * leaves the plain [spotlightSegment] spotlight standing, as before. The caller uses that to decide
     * whether its "which step is showing" bookkeeping moved.
     */
    fun spotlightStep(segment: Segment, index: Int): Boolean {
        val allHighlights = segment.highlights
        val activeHl = allHighlights.getOrNull(index) ?: return false

        // Anchors the decoration timeline: everything the flash/callout logs below belongs to this step, and
        // comparing this timestamp against NarrationController's tells you how far ahead of the voice we are.
        if (log.isDebugEnabled) {
            log.debug(
                "render step ${index + 1}/${allHighlights.size} of segment ${segment.id} " +
                    "(lines ${activeHl.start}-${activeHl.end})",
            )
        }

        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        val zeroStart = (activeHl.start - 1).coerceIn(0, lastLine)
        val zeroEnd = (activeHl.end - 1).coerceIn(zeroStart, lastLine)

        // Expand the effective range to encompass every highlight (and the active range).
        var effStart = segStart
        var effEnd = segEnd
        for (hl in allHighlights) {
            effStart = minOf(effStart, (hl.start - 1).coerceAtLeast(0))
            effEnd = maxOf(effEnd, (hl.end - 1).coerceAtLeast(0))
        }
        effStart = minOf(effStart, zeroStart).coerceIn(0, lastLine)
        effEnd = maxOf(effEnd, zeroEnd).coerceIn(effStart, lastLine)

        clear()

        val c = colors()

        // Outer dim: everything outside the effective segment range.
        if (effStart - 1 >= 0) addLineRange(0, effStart - 1, c.dim)
        if (effEnd + 1 <= lastLine) addLineRange(effEnd + 1, lastLine, c.dim)

        val highlightedLines = HashSet<Int>()
        for (hl in allHighlights) {
            val a = (hl.start - 1).coerceIn(0, lastLine)
            val b = (hl.end - 1).coerceIn(a, lastLine)
            for (l in a..b) highlightedLines.add(l)
        }
        // Walk the effective range, coalescing consecutive same-role lines (skipping the active block).
        var i = effStart
        while (i <= effEnd) {
            if (i in zeroStart..zeroEnd) { i = zeroEnd + 1; continue }
            val isGap = i !in highlightedLines
            var j = i
            while (j + 1 <= effEnd && (j + 1) !in zeroStart..zeroEnd && ((j + 1) !in highlightedLines) == isGap) j++
            val h = addLineRange(i, j, if (isGap) c.gap else c.segmentDim)
            if (isGap && h != null && c.gapGutterEnabled) {
                h.lineMarkerRenderer = gutterRenderer(c.accent, c.gapGutterWidth)
            }
            i = j + 1
        }

        // Active gold highlight + overview-ruler mark.
        addLineRange(zeroStart, zeroEnd, c.active)

        // B3/A4 relations touching this step, resolved against the open document and already split into the
        // two pictures the editor can actually draw. Resolved BEFORE the callout because the pointer form is
        // a line *inside* that box — so both halves of the inside/outside decision are made in one place.
        val rel = if (c.relations) resolveRelations(segment, activeHl, lastLine) else NO_RELATIONS

        // Docked callout above the active lines. Added BEFORE revealCenter deliberately: the inlay adds
        // height above `zeroStart`, so scrolling first would leave the block off-centre.
        if (c.callout) {
            addCallout(
                zeroStart, zeroEnd, calloutBlocks(activeHl, rel.pointers, c), c,
                arrowReserve(rel.arrows, c),
            )
        }

        for (spec in rel.arrows) addArrow(spec, c)
        for (spec in rel.pointers) addPointerGutter(spec, c)

        revealCenter(zeroStart, zeroEnd)

        // The flash deliberately does NOT ride this render. Measured, it would fire 1.8–6.0 s before the
        // first audio byte and be over long before the sentence discussing the token is spoken
        // (MoreUI.md §2.8) — so with narration engaged it waits for [flashToken]. Only when no audio
        // will ever arrive is this render the cue.
        if (c.tokenFlash && flashOnRender()) startTokenFlash(activeHl, zeroStart, zeroEnd, c)
        return true
    }

    /**
     * Pulse [hl]'s token anchor now, independently of a step render — the time-sensitive cue, driven by the
     * webview's `playback_started` (see [EditorHighlighter.flashCurrentToken]) or by a settings-page preview.
     * A no-op when the user has the flash switched off; the anchor itself may still fail to resolve, which
     * [startTokenFlash] treats as validate-and-drop rather than an error.
     */
    fun flashToken(hl: Highlight) {
        val c = colors()
        if (!c.tokenFlash) return
        val lastLine = (editor.document.lineCount - 1).coerceAtLeast(0)
        val zeroStart = (hl.start - 1).coerceIn(0, lastLine)
        val zeroEnd = (hl.end - 1).coerceIn(zeroStart, lastLine)
        startTokenFlash(hl, zeroStart, zeroEnd, c)
    }

    /** Scroll [line] (0-based) into view and centre it — a relation pointer's in-file jump target. */
    fun reveal(line: Int) = revealCenter(line, line)

    /**
     * The callout's paragraphs for [hl], in reading order: the prose aside, then the worked example directly
     * above the code it illustrates, then one line per off-target relation ([pointers]) — nearest-to-the-code
     * last, so the box reads top-down from "what this is" to "where it goes". All three are optional, and an
     * empty list means no inlay at all — which is what keeps a bare plan looking exactly as it does today.
     *
     * The prose line prefers the sentence-length `note` when the agent wrote one; the terse `explanation`
     * label is the fallback (it reads thin in a box, hence the preference).
     */
    private fun calloutBlocks(hl: Highlight, pointers: List<PointerSpec>, c: Palette): List<CalloutBlock> {
        val out = mutableListOf<CalloutBlock>()
        (hl.note ?: hl.explanation)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            out += CalloutBlock(it, BlockStyle.PROSE, MAX_CALLOUT_LINES)
        }
        if (c.calloutExample) {
            firstLine(hl.example).takeIf { it.isNotEmpty() }?.let {
                out += CalloutBlock(it, BlockStyle.CODE, MAX_EXAMPLE_LINES)
            }
        }
        for (p in pointers) out += CalloutBlock(p.text, BlockStyle.POINTER, MAX_POINTER_LINES)
        return out
    }

    /**
     * Add the A2 block-inlay callout carrying [blocks] — above [firstLine] of the active range, or below
     * [lastLine] when `calloutBelow` is set. An empty list renders nothing, which is what keeps a bare plan
     * looking exactly as it does today. The [Inlay] is a [Disposable] the markup model does not own, so it
     * goes in [trackedDisposables].
     */
    private fun addCallout(
        firstLine: Int,
        lastLine: Int,
        blocks: List<CalloutBlock>,
        c: Palette,
        reserveRight: Int,
    ) {
        if (blocks.isEmpty()) return
        val below = c.calloutBelow
        val renderer = CalloutRenderer(
            blocks, c.accent, c.calloutBg, c.calloutFg, c.calloutCodeFg, c.calloutPointerFg,
            // Indent to the block's FIRST line in both placements: that is the code's own left edge, whereas
            // the last line of a range is often a closing brace sitting further left.
            indentPx(firstLine), reserveRight, c.calloutStyle,
        )
        val inlay = editor.inlayModel.addBlockElement(
            editor.document.getLineStartOffset(if (below) lastLine else firstLine),
            // The box annotates the code on the other side of itself, so it anchors to that side's text: an
            // edit there should carry the callout along with its code, not strand it against the far line.
            /* relatesToPrecedingText = */ below,
            /* showAbove = */ !below,
            CALLOUT_PRIORITY,
            renderer,
        ) ?: return
        trackedDisposables += inlay
    }

    /**
     * Horizontal strip the step's arrows need to the right of the callout box.
     *
     * [RelationArrowRenderer] routes its leg clear of the box because it cannot paint above it (§2.9), but
     * a box wrapped to the full viewport leaves nowhere to route *to* — the channel clamps back onto it.
     * So the box gives the arrows room up front, and only when the step actually has arrows: a walkthrough
     * with no relations (most of them) wraps exactly as wide as it did before.
     *
     * Label room is included only if some arrow carries a label, since that is drawn right of the channel
     * again. [CalloutRenderer] floors its text width, so an editor too narrow to honour this loses the
     * reserve rather than the text.
     */
    private fun arrowReserve(arrows: List<ArrowSpec>, c: Palette): Int {
        if (arrows.isEmpty()) return 0
        val leg = ARROW_GAP + c.arrow.stroke.toInt() + ARROW_STUB
        return if (arrows.any { it.label != null }) leg + ARROW_LABEL_PAD + MIN_ARROW_LABEL_W else leg
    }

    /**
     * Pixel width of [line]'s leading whitespace, so the callout hangs under the code it describes rather
     * than at the left margin. Uses [Editor.offsetToXY] so tabs and proportional fonts are measured as the
     * editor actually lays them out.
     *
     * Falls back to 0 (flush left) unlogged: this is cosmetic positioning, and a callout at the margin is
     * a fine degradation — not worth a warning that would outnumber real faults.
     */
    private fun indentPx(line: Int): Int = try {
        val doc = editor.document
        val chars = doc.charsSequence
        val start = doc.getLineStartOffset(line)
        val end = doc.getLineEndOffset(line)
        var i = start
        while (i < end && (chars[i] == ' ' || chars[i] == '\t')) i++
        (editor.offsetToXY(i).x - editor.offsetToXY(start).x).coerceAtLeast(0)
    } catch (_: Throwable) {
        0
    }

    // ── B3/A4: relations ──

    /** The two pictures a step's relations resolve to — see [resolveRelations]. */
    private class ResolvedRelations(val arrows: List<ArrowSpec>, val pointers: List<PointerSpec>)

    /** A relation whose ends both anchor to lines of the open document, so it can be drawn as an arrow. */
    private class ArrowSpec(
        val fromLine: Int,
        val toLine: Int,
        val fromOffset: Int,
        val toOffset: Int,
        val doubleHeaded: Boolean,
        val label: String?,
    )

    /** A relation the editor can't draw: it hangs off [line] and names [target] in words instead. */
    private class PointerSpec(val line: Int, val text: String, val target: Endpoint, val incoming: Boolean)

    /** An endpoint resolved to a place in the open document: its line, and the offset an arrow leaves from. */
    private class Anchor(val line: Int, val offset: Int)

    /**
     * Split the relations belonging to this step into the two pictures the editor can actually draw.
     *
     * Two decisions live here, and both are the plugin's rather than the agent's (MoreUI.md §4.3 B3):
     *
     * - **Which relations belong to this step.** Only those with an endpoint inside [active]. `relations`
     *   is a segment-level list, so drawing all of it at once would put every arrow in the segment on screen
     *   during step 1; anchoring to the active highlight keeps a relation in step with the narration.
     * - **Arrow or pointer.** An arrow needs two lines of *this* document, on different rows, close enough to
     *   read as a connection. Everything else — another file, a symbol with no line, a line the document
     *   doesn't have, a token that isn't where the agent said, endpoints 200 lines apart — degrades to a
     *   pointer rather than being dropped, because "the value comes from RateTable.Lookup()" is still worth
     *   saying when it can't be drawn.
     */
    private fun resolveRelations(
        segment: Segment,
        active: Highlight,
        lastLine: Int,
    ): ResolvedRelations {
        if (segment.relations.isEmpty()) return NO_RELATIONS
        val arrows = mutableListOf<ArrowSpec>()
        val pointers = mutableListOf<PointerSpec>()
        var dropped = 0
        for (r in segment.relations) {
            val fromHere = inActive(active, r.from, segment.file)
            val toHere = inActive(active, r.to, segment.file)
            if (!fromHere && !toHere) continue // belongs to a different step of this segment
            if (arrows.size + pointers.size >= MAX_RELATIONS_PER_STEP) {
                dropped++
                continue
            }
            val a = anchor(r.from, segment.file, lastLine)
            val b = anchor(r.to, segment.file, lastLine)
            val label = r.note?.trim()?.takeIf { it.isNotEmpty() }
            if (a != null && b != null && a.line != b.line && abs(a.line - b.line) <= MAX_ARROW_SPAN) {
                arrows += ArrowSpec(
                    a.line, b.line, a.offset, b.offset,
                    doubleHeaded = r.kind == Relation.KIND_BACKFORTH,
                    label = label,
                )
                if (log.isDebugEnabled) {
                    log.debug("relation arrow: line ${a.line + 1} -> line ${b.line + 1} (${r.kind})")
                }
            } else {
                // Hang the pointer on whichever end IS in this step, aimed at the other one.
                val here = if (fromHere) a else b
                val there = if (fromHere) r.to else r.from
                val text = pointerText(there, label, incoming = !fromHere)
                if (text == null) {
                    // Nothing to name and nothing to say — an empty relation, not a rendering failure.
                    if (log.isDebugEnabled) log.debug("relation dropped: no symbol, token, file, line or note")
                    continue
                }
                pointers += PointerSpec(
                    line = here?.line ?: (active.start - 1).coerceIn(0, lastLine),
                    text = text,
                    target = there,
                    incoming = !fromHere,
                )
                if (log.isDebugEnabled) log.debug("relation pointer: $text")
            }
        }
        if (dropped > 0 && log.isDebugEnabled) {
            log.debug("$dropped relation(s) past the cap of $MAX_RELATIONS_PER_STEP per step not drawn")
        }
        return ResolvedRelations(arrows, pointers)
    }

    /** True when [ep] names a line of the active highlight — i.e. this relation belongs to this step. */
    private fun inActive(active: Highlight, ep: Endpoint, segmentFile: String): Boolean =
        ep.line > 0 && ep.line >= active.start && ep.line <= active.end &&
            (ep.file == null || sameFile(ep.file, segmentFile))

    /**
     * Resolve [ep] to a place in the open document, or null when it names somewhere this editor can't point
     * at (another file, no line, a line past the end) — the validate-and-drop rule, degrading to the pointer
     * form rather than failing the render.
     *
     * A `token` is checked here and its absence condemns the endpoint. That is the one cheap test available
     * on a line number the agent worked out by counting, and a *wrong* arrow — confidently joining two
     * unrelated lines — is worse than no arrow at all.
     */
    private fun anchor(ep: Endpoint, segmentFile: String, lastLine: Int): Anchor? {
        if (ep.line <= 0 || ep.line - 1 > lastLine) return null
        if (ep.file != null && !sameFile(ep.file, segmentFile)) return null
        val line = ep.line - 1
        val needle = ep.token?.trim()?.takeIf { it.isNotEmpty() }
        if (needle != null && resolveToken(needle, ep.occurrence, line, line) == null) return null
        return Anchor(line, trimmedLineEnd(editor.document, line))
    }

    /**
     * One line of prose for a relation the editor can't draw: `→ RateTable.Lookup · RateTable.cs:88 — where
     * the rate comes from`. Null when the endpoint names nothing at all and carries no note, which is an
     * empty relation rather than something to render.
     *
     * [incoming] flips the glyph: `→` for a target this step leads to, `←` for a source it came from.
     */
    private fun pointerText(ep: Endpoint, note: String?, incoming: Boolean): String? {
        val fileBase = ep.file?.trim()?.takeIf { it.isNotEmpty() }?.replace('\\', '/')?.substringAfterLast('/')
        val lineLabel = if (ep.line > 0) "line ${ep.line}" else null
        val name = ep.symbol?.trim()?.takeIf { it.isNotEmpty() }
            ?: ep.token?.trim()?.takeIf { it.isNotEmpty() }
            ?: fileBase
            ?: lineLabel
        val where = when {
            fileBase != null && fileBase != name -> if (ep.line > 0) " · $fileBase:${ep.line}" else " · $fileBase"
            fileBase != null -> if (ep.line > 0) ":${ep.line}" else ""
            lineLabel != null && lineLabel != name -> " · $lineLabel"
            else -> ""
        }
        val glyph = if (incoming) "←" else "→"
        val head = if (name == null) null else "$glyph $name$where"
        return when {
            head != null && note != null -> "$head — $note"
            head != null -> head
            note != null -> "$glyph $note"
            else -> null
        }
    }

    /**
     * Draw [spec] as an arrow. The highlighter spans both endpoints so the platform paints the renderer
     * whenever any part of the relation is in view, and carries no [TextAttributes] of its own — the picture
     * is entirely the custom renderer's. Markup-model-owned, so [tracked] clears it like every other layer.
     */
    private fun addArrow(spec: ArrowSpec, c: Palette) {
        val doc = editor.document
        val a = minOf(spec.fromLine, spec.toLine)
        val b = maxOf(spec.fromLine, spec.toLine)
        val h = editor.markupModel.addRangeHighlighter(
            doc.getLineStartOffset(a),
            doc.getLineEndOffset(b),
            ARROW_LAYER,
            null,
            HighlighterTargetArea.LINES_IN_RANGE,
        )
        h.customRenderer = RelationArrowRenderer(spec, c.arrow)
        tracked += h
    }

    /**
     * Hang a clickable pointer icon in the gutter of [spec]'s line. The icon rides a plain (attribute-free)
     * line highlighter, so it needs no disposal of its own — the markup model clears it with the rest. The tooltip
     * carries the full text even when the callout box is switched off or has ellipsised it.
     */
    private fun addPointerGutter(spec: PointerSpec, c: Palette) {
        val h = addLineRange(spec.line, spec.line, TextAttributes()) ?: return
        h.gutterIconRenderer = RelationPointerRenderer(spec, c.accent)
    }

    // ── A3: token flash ──

    /**
     * Pulse the identifier named by [hl]'s `token` anchor, fading it from the accent colour into the active
     * line's own glow over [Palette.tokenFlashMs]. No `token`, or one that can't be found in
     * `[lineStart, lineEnd]`, means no flash at all — the validate-and-drop rule, so a wrong anchor costs
     * nothing but a missing pulse.
     *
     * The fade re-adds an `EXACT_RANGE` highlighter per frame rather than mutating one in place: replacing a
     * highlighter is exactly how every other layer here repaints, so it needs no assumptions about when the
     * editor notices a mutated [TextAttributes]. A dozen frames of that is cheap.
     *
     * The [Timer] (not `Alarm`: EDT-native by construction, and no platform-version surface) is wrapped as a
     * [Disposable] into [trackedDisposables], so a segment transition mid-fade both stops the ticks and
     * removes the half-faded highlighter.
     */
    private fun startTokenFlash(hl: Highlight, lineStart: Int, lineEnd: Int, c: Palette) {
        cancelFlash()
        val range = resolveToken(hl.token, hl.occurrence, lineStart, lineEnd)
        if (range == null) {
            // The whole point of validate-and-drop is that this is NOT an error — but it is invisible, and
            // "my token didn't flash" is unanswerable without knowing whether it resolved. Debug level: an
            // author debugging a plan wants this; a normal user does not.
            if (log.isDebugEnabled && !hl.token.isNullOrBlank()) {
                log.debug(
                    "token anchor dropped: '${hl.token}' (occurrence ${hl.occurrence}) not found in lines " +
                        "${lineStart + 1}-${lineEnd + 1}",
                )
            }
            return
        }
        if (log.isDebugEnabled) {
            val line = editor.document.getLineNumber(range.startOffset) + 1
            log.debug(
                "token flash: '${hl.token}' occurrence ${hl.occurrence} -> line $line " +
                    "offsets ${range.startOffset}-${range.endOffset}, fading over ${c.tokenFlashMs}ms",
            )
        }

        paintFlash(range, c, 1.0)

        val interval = (c.tokenFlashMs / FLASH_STEPS).coerceAtLeast(MIN_FLASH_INTERVAL_MS)
        val timer = Timer(interval, null)
        var step = 0
        timer.addActionListener {
            step++
            when {
                editor.isDisposed -> timer.stop()
                step >= FLASH_STEPS -> {
                    timer.stop()
                    removeFlash()
                }
                // Linear fade of the accent into the active-line glow, so the pulse dissolves into the
                // highlight it belongs to rather than blinking out against a different colour.
                else -> paintFlash(range, c, 1.0 - step.toDouble() / FLASH_STEPS)
            }
        }
        timer.start()
        val cancel = Disposable {
            timer.stop()
            removeFlash()
        }
        flashTimer = cancel
        trackedDisposables += cancel
    }

    /** Stop a fade already in flight (and drop its half-faded highlighter), leaving the rest of the render. */
    private fun cancelFlash() {
        val cancel = flashTimer ?: return
        flashTimer = null
        trackedDisposables -= cancel
        try {
            Disposer.dispose(cancel)
        } catch (_: Throwable) {
            // Editor gone with its timer — unlogged, same reasoning as the teardown catches in clear().
        }
    }

    /** Replace the flash highlighter with one at [intensity] (1.0 = full accent, 0.0 = the active glow). */
    private fun paintFlash(range: TextRange, c: Palette, intensity: Double) {
        removeFlash()
        if (editor.isDisposed) return
        val attrs = TextAttributes().apply { backgroundColor = blend(c.accent, c.activeBg, intensity) }
        flashHighlighter = try {
            editor.markupModel.addRangeHighlighter(
                range.startOffset,
                range.endOffset,
                FLASH_LAYER,
                attrs,
                HighlighterTargetArea.EXACT_RANGE,
            )
        } catch (_: Throwable) {
            null // Document shrank under us mid-fade; the next tick stops the timer.
        }
    }

    private fun removeFlash() {
        val h = flashHighlighter ?: return
        flashHighlighter = null
        try {
            editor.markupModel.removeHighlighter(h)
        } catch (_: Throwable) {
            // Editor already disposed — unlogged for the same reason as the teardown catches below.
        }
    }

    /**
     * Offsets of the [occurrence]-th match of [token] within lines `[lineStart, lineEnd]`, or null if the
     * anchor doesn't resolve. Plain text search over the document — deliberately no PSI, so Rider behaves
     * the same as IntelliJ.
     *
     * Tries **whole-word** matches first and only then falls back to a bare substring: an anchor of `rate`
     * should prefer the standalone variable over the `rate` inside `rateTable`, but a non-identifier anchor
     * (`=>`, `Parse(`) has no word boundaries to honour and must still work.
     */
    private fun resolveToken(
        token: String?,
        occurrence: Int,
        lineStart: Int,
        lineEnd: Int,
    ): TextRange? {
        val needle = token?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        val a = lineStart.coerceIn(0, lastLine)
        val b = lineEnd.coerceIn(a, lastLine)
        val from = doc.getLineStartOffset(a)
        val to = doc.getLineEndOffset(b)
        val text = doc.charsSequence
        val nth = occurrence.coerceAtLeast(1)
        return findNth(text, needle, from, to, nth, wholeWord = true)
            ?: findNth(text, needle, from, to, nth, wholeWord = false)
    }

    /**
     * Remove every decoration this renderer added to [editor] (upstream `clearHighlights`). Called at the
     * head of each render and by the owner when the spotlight leaves this editor; safe to call repeatedly,
     * and safe after the editor has been disposed.
     */
    fun clear() {
        // Unconditional, not merely via the flash timer's disposable: the first frame is painted before
        // that disposable is registered, so this is what makes "no stray flash survives" hold always.
        removeFlash()
        val model = editor.markupModel
        for (h in tracked) {
            try {
                model.removeHighlighter(h)
            } catch (_: Throwable) {
                // Editor/markup already disposed — nothing to remove. Left UNLOGGED on purpose:
                // teardown races are routine and fire per highlighter on every segment transition,
                // so logging here would bury the render failures `runOnEdt` now reports.
            }
        }
        tracked.clear()

        // Inlays (and any future timer/renderer) are NOT owned by the markup model, so removing the
        // highlighters above leaves them on screen. Dispose them explicitly, and unconditionally: a
        // disposed editor has already dropped its own inlays, and disposing twice is a no-op.
        for (d in trackedDisposables) {
            try {
                Disposer.dispose(d)
            } catch (_: Throwable) {
                // Already disposed with its editor — nothing to do. Unlogged, same reasoning as above.
            }
        }
        trackedDisposables.clear()
        // The flash's cancel handle was in that list, so the fade is now stopped; drop the stale reference.
        flashTimer = null
    }

    /** Add a whole-line highlighter over the 0-based inclusive line range; tracked for later clearing. */
    private fun addLineRange(startLine: Int, endLine: Int, attrs: TextAttributes): RangeHighlighter? {
        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        val a = startLine.coerceIn(0, lastLine)
        val b = endLine.coerceIn(a, lastLine)
        val h = editor.markupModel.addRangeHighlighter(
            doc.getLineStartOffset(a),
            doc.getLineEndOffset(b),
            LAYER,
            attrs,
            HighlighterTargetArea.LINES_IN_RANGE,
        )
        tracked += h
        return h
    }

    private fun revealCenter(zeroStart: Int, zeroEnd: Int) {
        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        val caretLine = zeroStart.coerceIn(0, lastLine)
        editor.caretModel.moveToOffset(doc.getLineStartOffset(caretLine))
        editor.selectionModel.removeSelection()
        val mid = ((zeroStart + zeroEnd) / 2).coerceIn(0, lastLine)
        editor.scrollingModel.scrollTo(LogicalPosition(mid, 0), ScrollType.CENTER)
    }

    /**
     * A render's resolved styling. Foreground dims are theme-derived (recomputed per render, so a
     * mid-session light/dark switch is honoured); accent/glow are the user's absolute colours from
     * [AudioExplainerSettings]. Recomputed on every render, so a settings change is picked up.
     */
    private class Palette(
        val dim: TextAttributes,
        val segmentDim: TextAttributes,
        val gap: TextAttributes,
        val active: TextAttributes,
        val accent: Color,
        val gapGutterEnabled: Boolean,
        val gapGutterWidth: Int,
        val callout: Boolean,
        val calloutBg: Color,
        val calloutFg: Color,
        /** Body colour for a [BlockStyle.CODE] block — full scheme foreground, so an example reads crisper. */
        val calloutCodeFg: Color,
        /**
         * Body colour for a [BlockStyle.POINTER] block and an arrow's label — the accent mixed *into* the
         * scheme foreground rather than the accent itself, which on a light theme would be gold-on-gold.
         */
        val calloutPointerFg: Color,
        val calloutExample: Boolean,
        val calloutStyle: CalloutStyle,
        val calloutBelow: Boolean,
        val relations: Boolean,
        val arrow: ArrowStyle,
        /** The active line's resolved glow, held separately so the token flash can fade *into* it. */
        val activeBg: Color,
        val tokenFlash: Boolean,
        val tokenFlashMs: Int,
    )

    private fun colors(): Palette {
        // Read the scheme the editor ACTUALLY renders with, not the global one. The two can disagree
        // (per-editor scheme, presentation mode, a light editor scheme under a dark UI theme, timing),
        // and blending our glow/tint against the wrong background is what makes a dark editor wash out
        // to white. `editor.colorsScheme` is always the colours this editor paints. Fall back to the
        // global scheme only if that is somehow unavailable.
        val scheme = editor.colorsScheme
        val fg = scheme.defaultForeground ?: EditorColorsManager.getInstance().globalScheme.defaultForeground
        val bg = scheme.defaultBackground ?: EditorColorsManager.getInstance().globalScheme.defaultBackground
        val st = AudioExplainerSettings.getInstance().state

        val dimFg = blend(fg, bg, st.outsideDimPercent / 100.0)
        val segDimFg = blend(fg, bg, st.withinDimPercent / 100.0)
        val accent = parseColor(st.accentColor)
        val activeBg = blend(parseColor(st.activeGlowColor), bg, st.activeGlowPercent / 100.0)

        val dim = TextAttributes().apply { foregroundColor = dimFg }
        // Non-active highlight lines: dimmed foreground PLUS an own faint accent-tinted background,
        // so they no longer read identically to gap lines (which carry only the gutter bar).
        val segmentDim = TextAttributes().apply {
            foregroundColor = segDimFg
            if (st.nonActiveTint) backgroundColor = blend(accent, bg, st.nonActiveTintPercent / 100.0)
        }
        val gap = TextAttributes().apply { foregroundColor = segDimFg }
        val active = TextAttributes().apply {
            backgroundColor = activeBg
            if (st.overviewRulerMark) errorStripeColor = accent
        }
        // Callout box: accent-tinted panel with softened body text, both derived from THIS editor's scheme
        // so the box tracks a light/dark switch like every other layer here.
        // Opacity is applied on top of the tint, not instead of it: the tint decides the box's *hue*, the
        // alpha decides how much of what it covers (a clamped arrow leg, a long line of code) still reads
        // through it. At 100 the colour is opaque, i.e. exactly the box as it was before the setting existed.
        val calloutBg = blend(accent, bg, st.calloutTintPercent / 100.0).let {
            if (st.calloutOpacityPercent >= 100) it else Color(it.red, it.green, it.blue, alpha255(st.calloutOpacityPercent))
        }
        val calloutFg = blend(fg, bg, CALLOUT_TEXT_BRIGHTNESS)
        val calloutStyle = CalloutStyle(
            FrameStyle.of(st.calloutFrame),
            st.calloutCornerRadius.coerceAtLeast(0),
            st.calloutBarWidth.coerceAtLeast(0),
            st.calloutTextScalePercent.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
            st.calloutProseFont.trim(),
            st.calloutLeader,
            st.calloutBelow,
            commentFg(scheme, calloutFg),
        )
        // An arrow with its own colour takes its label with it: the label belongs to the arrow, whereas a
        // POINTER block belongs to the callout box and stays on the accent.
        val arrowColor = if (st.arrowUseAccent) accent else parseColor(st.arrowColor)
        val arrowStyle = ArrowStyle(
            arrowColor,
            blend(arrowColor, fg, POINTER_TEXT_ACCENT),
            st.arrowThicknessTenths.coerceAtLeast(1) / 10f,
            st.arrowHeadSize.coerceAtLeast(1),
            if (st.arrowSquareElbow) 0 else DEF_ARROW_ARC,
            st.arrowLabels,
        )
        return Palette(
            dim, segmentDim, gap, active, accent, st.gapGutter, st.gapGutterWidth,
            st.callout, calloutBg, calloutFg, fg, blend(accent, fg, POINTER_TEXT_ACCENT),
            st.calloutExample, calloutStyle, st.calloutBelow, st.relations, arrowStyle,
            activeBg, st.tokenFlash, st.tokenFlashMs,
        )
    }

    // ── Decoration renderers ──

    /**
     * What a callout paragraph *is*, which is the whole visual vocabulary of the box: an aside about the code
     * ([PROSE], italic, softened), the code itself having been run ([CODE], upright, full foreground), or a
     * place this step points at ([POINTER], upright, accent-tinted).
     *
     * All three come from `editor.colorsScheme`, i.e. the editor's own **monospace** family, so they share one
     * metrics contract and one measure/paint path — see MoreUI.md §4.3 B4, where a second renderer was wrongly
     * expected to be needed for anything but prose.
     */
    private enum class BlockStyle { PROSE, CODE, POINTER }

    /**
     * How the callout box is decorated around its text. Anything unrecognised in the persisted setting reads
     * as [BAR] — a stale or hand-edited value should cost the *decoration*, not the callout.
     */
    private enum class FrameStyle {
        BAR, OUTLINE, SOLID, NONE, COMMENT;

        companion object {
            fun of(id: String): FrameStyle = when (id) {
                AudioExplainerSettings.CALLOUT_FRAME_OUTLINE -> OUTLINE
                AudioExplainerSettings.CALLOUT_FRAME_SOLID -> SOLID
                AudioExplainerSettings.CALLOUT_FRAME_NONE -> NONE
                AudioExplainerSettings.CALLOUT_FRAME_COMMENT -> COMMENT
                else -> BAR
            }
        }
    }

    /** The callout box's look, resolved from settings once per render. */
    private class CalloutStyle(
        val frame: FrameStyle,
        val arc: Int,
        val barWidth: Int,
        val textScalePercent: Int,
        /** Family for the description text; blank = the editor's own font. Never applies to the example. */
        val proseFont: String,
        val leader: Boolean,
        /** Which way the leader points — the box is below the code it annotates, so the leader goes up. */
        val below: Boolean,
        /**
         * The scheme's own comment colour, used by [FrameStyle.COMMENT] for every block. A colour on a
         * "style" object because it is this frame's whole point: matching a real comment is not something
         * the callout's own palette can express.
         */
        val commentFg: Color,
    )

    /**
     * The relation arrow's look. Held as one object rather than six [Palette] fields because both the
     * renderer and [arrowReserve] need the whole set — the reserve has to know the stroke it is making room
     * for, or the leg it clears is not the leg that gets drawn.
     */
    private class ArrowStyle(
        val color: Color,
        val labelColor: Color,
        val stroke: Float,
        val head: Int,
        val arc: Int,
        val labels: Boolean,
    )

    /**
     * One independently-wrapped paragraph of a callout box. [maxLines] is per-block, so a long note can't eat
     * the example's room, nor an example the relation pointers'.
     */
    private class CalloutBlock(val text: String, val style: BlockStyle, val maxLines: Int)

    /**
     * A block-inlay callout: an accent-barred box of wrapped text docked above the active highlight.
     *
     * Each of [blocks] is word-wrapped to the viewport at its own line cap and ellipsised past that, so this
     * renderer already handles sentence-length prose — a longer per-highlight note becomes a data change
     * with no rendering work. The wrap is cached per measured width because [calcHeightInPixels] and [paint]
     * must agree on the line count or the box clips.
     */
    private class CalloutRenderer(
        private val blocks: List<CalloutBlock>,
        private val accent: Color,
        private val bg: Color,
        private val fg: Color,
        private val codeFg: Color,
        private val pointerFg: Color,
        private val indent: Int,
        /** Width kept free on the right for this step's arrow legs and labels — see [arrowReserve]. */
        private val reserveRight: Int,
        private val style: CalloutStyle,
    ) : EditorCustomElementRenderer {

        /** The accent bar's width where one is drawn, and 0 where it isn't — it also offsets the text. */
        private val barW: Int = if (style.frame == FrameStyle.BAR) style.barWidth else 0

        /** Frames that paint a panel behind the text, i.e. the ones with a background for the leader to share. */
        private val panelFrame: Boolean =
            style.frame != FrameStyle.NONE && style.frame != FrameStyle.COMMENT

        /**
         * Frames whose leader draws its own border. Bar is the exception: its box already carries an accent
         * edge, and a stroked tail hanging off it would read as a second, competing one. Everywhere else the
         * tail has to define itself — either because the box is stroked too (Outline) and an unstroked tail
         * looks unfinished beside it, or because there is no box at all (None, Comment) and an unstroked tail
         * is a shape with nothing to say where it came from.
         */
        private val leaderBorder: Boolean = style.frame != FrameStyle.BAR

        private var cachedWidth = -1

        /** Wrapped lines per [blocks] entry, index-parallel to it. */
        private var cachedLines: List<List<String>> = emptyList()

        /**
         * The wrap [calcHeightInPixels] last reported a height for. The platform measures an inlay once, so
         * if the viewport is resized afterwards the re-wrapped text would no longer fit the reserved height
         * — [paint] detects the drift and asks for a re-measure instead of overflowing the box.
         */
        private var measuredForWidth = -1
        private var measuredLineCounts: List<Int> = emptyList()

        /** One re-measure in flight at a time, so repaints before it lands don't queue a pile of updates. */
        private var updateScheduled = false

        /**
         * The block's font, scaled by `calloutTextScalePercent`: the editor's own family — italic for prose,
         * upright for code and pointers — unless `calloutProseFont` names one, which applies to the
         * *description* text only. [BlockStyle.CODE] never leaves the editor font, because an example is code
         * and code in a proportional face reads as prose about code.
         *
         * Everything measures through [metrics], which derives from this, so a family or scale change moves
         * the wrap, the reserved height and the painted text together.
         */
        private fun font(editor: Editor, block: CalloutBlock): Font {
            val base = editor.colorsScheme.getFont(
                if (block.style == BlockStyle.PROSE) EditorFontType.ITALIC else EditorFontType.PLAIN,
            )
            // An unknown family falls back to a platform default inside `Font` — the right cost for a stale
            // or hand-edited setting: the family, not the callout.
            val named = style.proseFont
                .takeIf { it.isNotEmpty() && block.style != BlockStyle.CODE }
                ?.let { Font(it, base.style, base.size) }
                ?: base
            val scale = style.textScalePercent
            return if (scale == 100) named else named.deriveFont(named.size2D * scale / 100f)
        }

        private fun metrics(editor: Editor, block: CalloutBlock): FontMetrics =
            editor.contentComponent.getFontMetrics(font(editor, block))

        /** In [FrameStyle.COMMENT] every block takes the scheme's comment colour — that is the whole point. */
        private fun color(block: CalloutBlock): Color =
            if (style.frame == FrameStyle.COMMENT) style.commentFg else when (block.style) {
                BlockStyle.PROSE -> fg
                BlockStyle.CODE -> codeFg
                BlockStyle.POINTER -> pointerFg
            }

        /**
         * Per-line prefix, non-empty only in [FrameStyle.COMMENT]. It is real width, so it comes off the wrap
         * budget in [lines] and goes back on in [textWidth]: a prefix charged on one side only is how a box
         * ends up clipping its own last word.
         */
        private val prefix: String = if (style.frame == FrameStyle.COMMENT) "// " else ""

        private fun prefixW(fm: FontMetrics): Int = if (prefix.isEmpty()) 0 else fm.stringWidth(prefix)

        /** Wrapped text for the current viewport width, one list per block. */
        private fun lines(editor: Editor): List<List<String>> {
            val viewport = editor.scrollingModel.visibleArea.width.takeIf { it > 0 } ?: FALLBACK_VIEWPORT
            val avail = (viewport - indent - barW - 2 * PAD_X - GUTTER_SLACK - reserveRight)
                .coerceAtLeast(MIN_TEXT_W)
            if (avail == cachedWidth) return cachedLines
            cachedWidth = avail
            cachedLines = blocks.map {
                val fm = metrics(editor, it)
                wrap(it.text, fm, (avail - prefixW(fm)).coerceAtLeast(MIN_TEXT_W), it.maxLines)
            }
            return cachedLines
        }

        /** Widest line across every block, each measured in its own font and carrying its own prefix. */
        private fun textWidth(editor: Editor, wrapped: List<List<String>>): Int {
            var w = 0
            for ((i, block) in blocks.withIndex()) {
                val fm = metrics(editor, block)
                val pw = prefixW(fm)
                for (line in wrapped.getOrElse(i) { emptyList() }) w = maxOf(w, pw + fm.stringWidth(line))
            }
            return w
        }

        /**
         * Room above the block at position [drawn] in the drawn sequence: nothing for the first, [BLOCK_GAP]
         * between paragraphs, and extra above a worked example so it reads as a separate exhibit rather than
         * one more sentence. Shared by [textHeight] and [paint] — drift between those two is what clips a box.
         */
        private fun gapBefore(block: CalloutBlock, drawn: Int): Int = when {
            drawn == 0 -> 0
            block.style == BlockStyle.CODE -> BLOCK_GAP + EXAMPLE_GAP
            else -> BLOCK_GAP
        }

        /**
         * Height of the wrapped text alone (no padding), including the inter-block gaps. Both
         * [calcHeightInPixels] and [paint] walk the blocks through this, so the reserved height and the
         * painted height cannot drift apart.
         */
        private fun textHeight(editor: Editor, wrapped: List<List<String>>): Int {
            var h = 0
            var drawn = 0
            for ((i, block) in blocks.withIndex()) {
                val n = wrapped.getOrElse(i) { emptyList() }.size
                if (n == 0) continue
                h += gapBefore(block, drawn)
                h += n * metrics(editor, block).height
                drawn++
            }
            return h
        }

        /** Height reserved beyond the box for the leader, and 0 where it is switched off. */
        private fun leaderLen(): Int = if (style.leader) LEADER_LEN else 0

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            return indent + barW + 2 * PAD_X + textWidth(editor, lines(editor))
        }

        override fun calcHeightInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            val wrapped = lines(editor)
            measuredForWidth = cachedWidth
            measuredLineCounts = wrapped.map { it.size }
            updateScheduled = false
            val h = textHeight(editor, wrapped)
            if (h == 0) return 0
            return h + 2 * PAD_Y + 2 * MARGIN_Y + leaderLen()
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val editor = inlay.editor

            // The editor was resized since we were measured: re-wrapping now could need more lines than the
            // reserved height holds. Draw only what fits and ask for a re-measure (off the paint pass —
            // `update()` re-enters layout), which repaints at the right height.
            val wrapped = lines(editor)
            if (cachedWidth != measuredForWidth && !updateScheduled) {
                updateScheduled = true
                ApplicationManager.getApplication().invokeLater {
                    if (inlay.isValid) runCatching { inlay.update() }
                }
            }
            // Clamp each block to the lines the measure pass reserved room for. If we somehow paint before
            // being measured, honour the full wrap rather than drawing nothing.
            val caps = measuredLineCounts.takeIf { it.size == blocks.size } ?: wrapped.map { it.size }
            val shown = wrapped.mapIndexed { i, ls -> ls.take(caps[i]) }

            val textW = textWidth(editor, shown)
            if (textW == 0) return

            val x = targetRegion.x + indent
            // Docked below, the leader points *up* at the code, so it takes the top of the reserved band and
            // the box moves down past it. Docked above (the default) the box keeps the top.
            val y = targetRegion.y + MARGIN_Y + if (style.below) leaderLen() else 0
            val w = barW + 2 * PAD_X + textW
            val h = textHeight(editor, shown) + 2 * PAD_Y
            val arc = style.arc

            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                // Decoration only — every style below wraps and paints the same text in the same place.
                when (style.frame) {
                    FrameStyle.BAR -> {
                        g2.color = bg
                        g2.fillRoundRect(x, y, w, h, arc, arc)
                        g2.color = accent
                        g2.fillRect(x, y, barW, h) // left accent bar, echoing the gap gutter cue
                    }
                    FrameStyle.OUTLINE -> {
                        g2.color = bg
                        g2.fillRoundRect(x, y, w, h, arc, arc)
                        g2.color = accent
                        // -1: drawRoundRect's stroke is inclusive of the far edge, so the full width would
                        // land one pixel outside the fill it is supposed to trace.
                        g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc)
                    }
                    FrameStyle.SOLID -> {
                        g2.color = bg
                        g2.fillRoundRect(x, y, w, h, arc, arc)
                    }
                    // Text straight onto the code background. The padding still applies, so switching styles
                    // moves the decoration and never the text. COMMENT is NONE plus the `//` prefix that
                    // [prefix] has already charged to the wrap, and the scheme's comment colour.
                    FrameStyle.NONE, FrameStyle.COMMENT -> {}
                }

                // After the frame, never before: the leader's mouth has to be able to erase the box's own
                // edge across it.
                if (style.leader) drawLeader(g2, x, y, w, h)

                var top = y + PAD_Y
                var drawn = 0
                for ((i, block) in blocks.withIndex()) {
                    val ls = shown[i]
                    if (ls.isEmpty()) continue
                    val gap = gapBefore(block, drawn)
                    // The example's separator, drawn in the middle of the extra room [gapBefore] just gave
                    // it. Suppressed in COMMENT, where a rule through a run of `//` lines would break the one
                    // thing that style sells.
                    if (gap > BLOCK_GAP && style.frame != FrameStyle.COMMENT) {
                        g2.color = blend(fg, bg, EXAMPLE_RULE_BRIGHTNESS)
                        g2.fillRect(x + barW + PAD_X, top + gap / 2, textW, 1)
                    }
                    top += gap
                    val fm = metrics(editor, block)
                    g2.font = font(editor, block)
                    g2.color = color(block)
                    var baseline = top + fm.ascent
                    for (line in ls) {
                        g2.drawString(prefix + line, x + barW + PAD_X, baseline)
                        baseline += fm.height
                    }
                    top += ls.size * fm.height
                    drawn++
                }
            } finally {
                g2.dispose()
            }
        }

        /**
         * The callout's leader: a speech-bubble tail grown out of the box's near edge toward the line it
         * annotates. It lives in the [leaderLen] the measure pass reserved, so it is never drawn over code.
         *
         * Skewed rather than isosceles — the tip drops straight down from the base's leading corner and the
         * far edge slants back up to it — so it reads as leaning out of the box toward the code instead of as
         * a pin holding the box up. It grows out of the box at [LEADER_POS_PERCENT], not at the corner, for
         * the same reason.
         *
         * Two frame-dependent parts, because a tail can only inherit what its box actually paints:
         * - **Fill** ([panelFrame]): the box's own tinted background, so the tail is one silhouette with it.
         *   None and Comment paint no panel, so there is no fill to continue and the tail stays open.
         * - **Border** (every frame but Bar): the two free edges stroked, and the shared edge then redrawn in
         *   the tint. That shared edge is the whole trick — it erases the box's own outline across the mouth
         *   of the tail, which is what turns "outlined box with a triangle stuck under it" into one bubble.
         *   Below full opacity the erase is translucent too, so it thins that edge rather than removing it —
         *   the same bargain the box's own fill makes with the code it covers.
         */
        private fun drawLeader(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
            val len = leaderLen()
            // Never wider than the box's text area, and never hanging past either end of it: a one-word
            // callout would otherwise sprout its tail outside the box it belongs to.
            val wide = minOf(LEADER_W, w - barW)
            val left = (x + w * LEADER_POS_PERCENT / 100).coerceIn(x + barW, x + w - wide)
            val right = left + wide
            // The base sits *on* the frame's own edge (hence h - 1, matching the outline's inclusive stroke)
            // so the two fills meet with no antialiasing seam between them.
            val baseY = if (style.below) y else y + h - 1
            val apexY = if (style.below) baseY - len else baseY + len

            if (panelFrame) {
                g2.color = bg
                val tri = GeneralPath()
                tri.moveTo(left.toFloat(), baseY.toFloat())
                tri.lineTo(left.toFloat(), apexY.toFloat())
                tri.lineTo(right.toFloat(), baseY.toFloat())
                tri.closePath()
                g2.fill(tri)
            }
            if (!leaderBorder) return
            g2.stroke = BasicStroke(LEADER_STROKE)
            g2.color = if (style.frame == FrameStyle.COMMENT) style.commentFg else accent
            // Open path: the free edges only. Closing it here is what would draw the mouth shut.
            val edges = GeneralPath()
            edges.moveTo(left.toFloat(), baseY.toFloat())
            edges.lineTo(left.toFloat(), apexY.toFloat())
            edges.lineTo(right.toFloat(), baseY.toFloat())
            g2.draw(edges)
            if (panelFrame) {
                // One row — [baseY] *is* the frame's own edge row in both dock directions, which is why it was
                // chosen — inset a pixel at each end so the two strokes above keep their own corners, and a
                // fillRect rather than a drawLine because drawLine's endpoint is inclusive and would take the
                // pixel where the slanted edge meets the base.
                //
                // Docked above this is nearly redundant: the tail's own fill starts on that same row and has
                // already covered it. Docked below it is the whole job — the fill stops one row short of the
                // base, so without this the frame's edge runs straight across the mouth.
                g2.color = bg
                g2.fillRect(left + 1, baseY, wide - 2, 1)
            }
        }
    }

    /**
     * A4: the drawn arrow for a B3 relation — an elbow that leaves the end of one line, runs down a clear
     * vertical channel to the right of the code, and points back into the end of the other.
     *
     * Everything is computed at **paint** time from the two offsets, not cached: `offsetToXY` returns
     * content-component coordinates, which is the space this [Graphics] is in, so the arrow tracks its code
     * through scrolling, folding and font changes for free. The cost is a handful of `offsetToXY` calls per
     * repaint, which is what indent guides already do.
     *
     * The channel sits clear of the longest line the arrow spans, which is *why* the ends are line ends rather
     * than the endpoint tokens: a leg starting mid-line would be drawn straight over the rest of that line's
     * code. When the code is wide enough that no channel fits inside the viewport, the arrow is skipped
     * rather than scribbled across the text.
     */
    private class RelationArrowRenderer(
        private val spec: ArrowSpec,
        private val style: ArrowStyle,
    ) : CustomHighlighterRenderer {

        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            val doc = editor.document
            val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
            // The document can shrink under a rendered walkthrough (the user is free to edit while it shows).
            if (spec.fromLine > lastLine || spec.toLine > lastLine) return
            if (spec.fromOffset > doc.textLength || spec.toOffset > doc.textLength) return

            val p1 = editor.offsetToXY(spec.fromOffset)
            val p2 = editor.offsetToXY(spec.toOffset)
            val y1 = p1.y + editor.lineHeight / 2
            val y2 = p2.y + editor.lineHeight / 2
            if (y1 == y2) return // both ends collapsed onto one visual row by a fold — nothing to connect

            val visible = editor.scrollingModel.visibleArea
            val room = visible.x + visible.width - GUTTER_SLACK
            if (room - maxOf(p1.x, p2.x) < MIN_ARROW_ROOM) return

            val topLine = minOf(spec.fromLine, spec.toLine)
            val bottomLine = maxOf(spec.fromLine, spec.toLine)

            // Clear of every line the arrow passes, so the vertical leg never crosses code. Clamped to the
            // viewport: with lines longer than the window, a leg over the text beats one off screen.
            var channel = maxOf(p1.x, p2.x)
            for (l in topLine..bottomLine) {
                channel = maxOf(channel, editor.offsetToXY(trimmedLineEnd(doc, l)).x)
            }
            channel = maxOf(channel, inlayRight(editor, topLine, bottomLine, minOf(y1, y2), maxOf(y1, y2)))
            channel = (channel + ARROW_GAP).coerceAtMost(room)

            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = style.color
                g2.stroke = BasicStroke(style.stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                val x1 = (p1.x + ARROW_STUB).toFloat()
                val x2 = (p2.x + ARROW_STUB).toFloat()
                val cx = channel.toFloat()
                val arc = style.arc.toFloat().coerceAtMost(abs(y2 - y1) / 2f)
                val dir = if (y2 > y1) 1f else -1f

                val path = GeneralPath()
                path.moveTo(if (spec.doubleHeaded) x1 + style.head else x1, y1.toFloat())
                path.lineTo(cx - arc, y1.toFloat())
                path.quadTo(cx, y1.toFloat(), cx, y1 + dir * arc)
                path.lineTo(cx, y2 - dir * arc)
                path.quadTo(cx, y2.toFloat(), cx - arc, y2.toFloat())
                path.lineTo(x2 + style.head, y2.toFloat())
                g2.draw(path)

                head(g2, x2, y2.toFloat())
                if (spec.doubleHeaded) head(g2, x1, y1.toFloat())

                // The note goes right of the channel, still clear of the code, vertically centred on the leg.
                val label = spec.label
                val avail = room - channel - ARROW_LABEL_PAD
                if (label != null && style.labels && avail >= MIN_ARROW_LABEL_W) {
                    val font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
                    val fm = editor.contentComponent.getFontMetrics(font)
                    g2.font = font
                    g2.color = style.labelColor
                    val text = if (fm.stringWidth(label) <= avail) label else ellipsize(label, fm, avail)
                    g2.drawString(text, channel + ARROW_LABEL_PAD, (y1 + y2) / 2 + fm.ascent / 2 - fm.descent / 2)
                }
            } finally {
                g2.dispose()
            }
        }

        /**
         * Right edge of the widest block inlay the leg would run through — our own A2 callout, and anyone
         * else's. Block inlays are painted in a **later** pass than custom highlighter renderers, so no
         * [HighlighterLayer] can lift this arrow above the box (MoreUI.md §2.9): the leg has to be routed
         * clear of it instead, exactly as it is routed clear of the code.
         *
         * Only inlays whose band actually crosses the leg count — an inlay above [top] or below [bottom]
         * costs the arrow nothing. A null `bounds` means the inlay is folded away and cannot collide.
         */
        private fun inlayRight(editor: Editor, topLine: Int, bottomLine: Int, top: Int, bottom: Int): Int {
            val doc = editor.document
            var x = 0
            val inlays = editor.inlayModel.getBlockElementsInRange(
                doc.getLineStartOffset(topLine),
                doc.getLineEndOffset(bottomLine),
            )
            for (inlay in inlays) {
                val b = inlay.bounds ?: continue
                if (b.y + b.height < top || b.y > bottom) continue
                x = maxOf(x, b.x + b.width)
            }
            return x
        }

        /** Filled arrowhead whose tip is at ([tipX], [y]), pointing left — back into the line's text. */
        private fun head(g2: Graphics2D, tipX: Float, y: Float) {
            val tri = GeneralPath()
            tri.moveTo(tipX, y)
            tri.lineTo(tipX + style.head, y - style.head / 2f)
            tri.lineTo(tipX + style.head, y + style.head / 2f)
            tri.closePath()
            g2.fill(tri)
        }
    }

    /**
     * Gutter pointer for a relation the editor can't draw — the "outside" half of B3. Clicking opens the
     * target; the tooltip carries the whole sentence, so the information survives the callout being off or
     * the box having ellipsised it. `inner` so the click can reach [navigate], and value-based equality
     * because the platform compares gutter renderers when deciding whether to repaint.
     */
    private inner class RelationPointerRenderer(
        private val spec: PointerSpec,
        private val accent: Color,
    ) : GutterIconRenderer() {

        override fun getIcon(): Icon = PointerIcon(spec.incoming, accent)

        override fun getTooltipText(): String = "${spec.text} — click to open"

        /** Right of the gutter, clear of the breakpoints and VCS marks on the left. */
        override fun getAlignment(): Alignment = Alignment.RIGHT

        override fun getClickAction(): AnAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = navigate(spec.target)
        }

        override fun equals(other: Any?): Boolean =
            other is RelationPointerRenderer && other.spec.line == spec.line &&
                other.spec.text == spec.text && other.spec.incoming == spec.incoming && other.accent == accent

        override fun hashCode(): Int {
            var r = spec.line
            r = 31 * r + spec.text.hashCode()
            r = 31 * r + spec.incoming.hashCode()
            r = 31 * r + accent.hashCode()
            return r
        }
    }

    /** Small accent triangle: pointing left for an incoming relation, right for one that leads elsewhere. */
    private class PointerIcon(private val incoming: Boolean, private val color: Color) : Icon {

        override fun getIconWidth(): Int = JBUI.scale(POINTER_ICON_SIZE)

        override fun getIconHeight(): Int = JBUI.scale(POINTER_ICON_SIZE)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val d = getIconWidth()
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                val mid = y + d / 2f
                val tri = GeneralPath()
                if (incoming) {
                    tri.moveTo(x + 1f, mid)
                    tri.lineTo(x + d - 1f, y + 1f)
                    tri.lineTo(x + d - 1f, y + d - 1f)
                } else {
                    tri.moveTo(x + d - 1f, mid)
                    tri.lineTo(x + 1f, y + 1f)
                    tri.lineTo(x + 1f, y + d - 1f)
                }
                tri.closePath()
                g2.fill(tri)
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        /** Above syntax/semantic highlighting (so the dim foreground wins) but below the selection layer. */
        private val LAYER = HighlighterLayer.SELECTION - 1

        /** Topmost, so the flash reads over the active line's glow instead of being blended under it. */
        private val FLASH_LAYER = HighlighterLayer.LAST

        /** Same reasoning as [FLASH_LAYER]: an arrow drawn under the dim/glow layers would be washed out. */
        private val ARROW_LAYER = HighlighterLayer.LAST

        /**
         * True when nothing will ever signal "audio started", so a step render is the only cue the token
         * flash can have: the user has narration switched off and is stepping through the walkthrough
         * visually.
         *
         * Read from settings rather than tracked as narration state on purpose — it is a user setting, so
         * there is no staleness or cross-thread handoff to get wrong. The one gap it leaves is *engine
         * selected but not yet installed*: narration is silently disabled, so no flash. That state also
         * produces no audio at all and prompts the user to finish setup, so a missing pulse is not the
         * complaint they'd have, and it resolves itself once TTS works.
         *
         * `internal` because the *listener* needs the same rule: [EditorHighlighter.refresh] flashes only
         * when the render did not, and two copies of that condition would be two places to get it wrong.
         */
        internal fun flashOnRender(): Boolean =
            AudioExplainerSettings.getInstance().state.ttsEngine == AudioExplainerSettings.ENGINE_OFF

        /**
         * The user's accent colour, for decoration a **node view** owns rather than this renderer:
         * `CodeNodeView`'s active/non-active node cue and its elision rule.
         *
         * `internal` for the same reason as [flashOnRender] — one rule, one place. `TreeOfCode.md`
         * constraint 4 is what makes it a rule rather than a preference: `JBColor.namedColor("Focus.color",
         * …)` rendered dark red in Rider's default scheme and a node tinted with it read as an error band, so
         * every accent in this plugin comes from settings. A second hex parse with its own fallback in the
         * tree package would be a second place for that to drift.
         */
        internal fun accentColor(): Color = parseColor(AudioExplainerSettings.getInstance().state.accentColor)

        /** [blend], exposed for the same callers as [accentColor]: a node-level tint must mix toward the bg. */
        internal fun blendToward(a: Color, b: Color, t: Double): Color = blend(a, b, t)

        // ── Token flash (A3) ──
        /** Fade frames. 12 over the default 700 ms is ~58 ms/frame — smooth without being a repaint storm. */
        private const val FLASH_STEPS = 12

        /** Floor on the frame interval, so a tiny `tokenFlashMs` can't ask for a 1 ms repaint loop. */
        private const val MIN_FLASH_INTERVAL_MS = 16

        // ── Callout geometry (unscaled px; the editor font already scales with the IDE) ──
        private const val CALLOUT_PRIORITY = 0
        private const val MAX_CALLOUT_LINES = 3

        /**
         * Cap on the B4 `example` block. It is authored as **one** line; 2 is headroom for a long one-liner
         * wrapping in a narrow editor, not licence to write two examples. The matching authoring rule lives
         * in `plan-schema.md` (see MoreUI.md §2.4 — renderer caps ship as authoring rules).
         */
        private const val MAX_EXAMPLE_LINES = 2

        /** Vertical breathing room between two callout blocks, so note and example read as separate lines. */
        private const val BLOCK_GAP = 3
        private const val PAD_X = 8
        private const val PAD_Y = 4
        private const val MARGIN_Y = 2

        /**
         * Extra room *above* a worked example, on top of [BLOCK_GAP], with a faint rule drawn in the middle of
         * it. An example is a different kind of thing from the prose above it — a demonstration, not another
         * sentence — and at one paragraph gap the two read as one run-on block.
         */
        private const val EXAMPLE_GAP = 6

        /** Blend fraction of the foreground kept for that rule: present, not a second border. */
        private const val EXAMPLE_RULE_BRIGHTNESS = 0.3

        // ── Callout leader ──
        /**
         * How far the callout's leader reaches past the box, reserved beyond it by
         * [CalloutRenderer.calcHeightInPixels]. Long enough to read as a connection, short enough that the
         * box still lands next to its code.
         */
        private const val LEADER_LEN = 9

        /** The leader's base along the box edge. Wider than [LEADER_LEN], so the tail reads as a tail. */
        private const val LEADER_W = 14

        /**
         * Where the leader's tip sits along the box, left to right. A tail under the *start* of the box points
         * back at the code's own indent; centred it reads as a pin holding the box up.
         */
        private const val LEADER_POS_PERCENT = 25

        private const val LEADER_STROKE = 1f

        /**
         * Bounds on `calloutTextScalePercent`. Below the floor the box is unreadable, above the ceiling it
         * stops looking like an annotation of the code and starts competing with it.
         */
        private const val MIN_TEXT_SCALE = 60
        private const val MAX_TEXT_SCALE = 160

        /** Cap on relation pointer lines in the callout; the box already carries a note and an example. */
        private const val MAX_POINTER_LINES = 2

        /** Right-edge breathing room so a full-width callout never collides with the scrollbar. */
        private const val GUTTER_SLACK = 40

        // ── Relations (B3) / arrows (A4) ──────────────────────────────────────
        /**
         * Relations drawn for one step. Three arrows over a four-line highlight is already busy, and a step
         * whose point needs four relationships is a step that should have been split. Ships as an authoring
         * rule in `plan-schema.md` (MoreUI.md §2.4); anything past it is logged at debug and not drawn.
         */
        private const val MAX_RELATIONS_PER_STEP = 3

        /**
         * Line distance beyond which an arrow degrades to a pointer. An elbow only reads as a connection when
         * both ends can be seen at once; past a screenful it becomes a line disappearing off the top of the
         * viewport, which says less than "← from line 300" does.
         */
        private const val MAX_ARROW_SPAN = 40

        /** Horizontal clearance between the longest spanned line and the arrow's vertical leg. */
        private const val ARROW_GAP = 16

        /** Gap between the end of a line's text and where its leg starts, so the arrow doesn't touch a glyph. */
        private const val ARROW_STUB = 3

        /** Elbow corner radius when the corners aren't squared off by `arrowSquareElbow`. */
        private const val DEF_ARROW_ARC = 4

        /** Below this much clear space to the right of both ends, there is nowhere to put an elbow at all. */
        private const val MIN_ARROW_ROOM = 20

        private const val ARROW_LABEL_PAD = 8

        /** Narrower than this and a relation note would be ellipsised to nothing, so it isn't drawn. */
        private const val MIN_ARROW_LABEL_W = 60

        /** Fraction of the accent mixed into the scheme foreground for pointer text and arrow labels. */
        private const val POINTER_TEXT_ACCENT = 0.45

        private const val POINTER_ICON_SIZE = 10

        /** Shared no-op result, so the common "no relations at all" path allocates nothing. */
        private val NO_RELATIONS = ResolvedRelations(emptyList(), emptyList())

        /** Used before the editor has a laid-out viewport (visibleArea is 0 on the first paint). */
        private const val FALLBACK_VIEWPORT = 700
        private const val MIN_TEXT_W = 120

        /** Blend fraction of the editor foreground kept for callout body text (softer than code). */
        private const val CALLOUT_TEXT_BRIGHTNESS = 0.85

        /**
         * Greedy word wrap to [maxWidth], capped at [maxLines] with an ellipsis on the last line. A single
         * word longer than the line is left overlong rather than broken — truncating an identifier mid-way
         * reads worse than letting it run.
         */
        private fun wrap(text: String, fm: FontMetrics, maxWidth: Int, maxLines: Int): List<String> {
            val words = text.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
            if (words.isEmpty()) return emptyList()
            val out = mutableListOf<String>()
            var cur = ""
            for (word in words) {
                val candidate = if (cur.isEmpty()) word else "$cur $word"
                when {
                    cur.isEmpty() || fm.stringWidth(candidate) <= maxWidth -> cur = candidate
                    out.size + 1 < maxLines -> {
                        out += cur
                        cur = word
                    }
                    // Out of lines: drop the rest and mark the truncation.
                    else -> return out + ellipsize(cur, fm, maxWidth)
                }
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }

        /**
         * The first non-blank line of [s], marked with an ellipsis when more followed; `""` for null/blank.
         *
         * [wrap] splits on `'\n'` along with spaces, so handing it a multi-line snippet would reflow the
         * whole thing into one run-on paragraph — destroying the line structure that made it a snippet.
         * Keeping line 1 and *showing* that something was cut is the honest degradation, and it matches the
         * one-line rule `plan-schema.md` states for `example`. Real multi-line blocks need their own
         * renderer (MoreUI.md §4.3 B4).
         */
        private fun firstLine(s: String?): String {
            val lines = s?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return ""
            val head = lines.firstOrNull() ?: return ""
            return if (lines.size > 1) "$head …" else head
        }

        /**
         * Offset just past the last non-blank character of [line] — where a relation arrow leaves the code
         * without crossing it. Anchoring at the endpoint *token* instead would run the horizontal leg back
         * over the rest of that line's text, so the token validates and names the endpoint ([Endpoint]) while
         * the arrow always departs from the end of the line.
         */
        private fun trimmedLineEnd(doc: Document, line: Int): Int {
            val start = doc.getLineStartOffset(line)
            var end = doc.getLineEndOffset(line)
            val chars = doc.charsSequence
            while (end > start && chars[end - 1].isWhitespace()) end--
            return end
        }

        /**
         * True when [file] names the same file as [segmentFile], tolerating the relative form an agent is
         * likely to write for a sibling file. Case-insensitive, which is right on Windows and harmless
         * elsewhere: the worst a false negative costs is an arrow degrading to a pointer.
         */
        private fun sameFile(file: String, segmentFile: String): Boolean {
            val a = file.trim().replace('\\', '/').removePrefix("./")
            if (a.isEmpty()) return true
            val b = segmentFile.replace('\\', '/')
            return b.equals(a, ignoreCase = true) || b.endsWith("/$a", ignoreCase = true)
        }

        private fun ellipsize(s: String, fm: FontMetrics, maxWidth: Int): String {
            var t = s
            while (t.isNotEmpty() && fm.stringWidth("$t…") > maxWidth) t = t.dropLast(1)
            return "$t…"
        }

        /**
         * Offsets of the [n]-th occurrence of [needle] in `text[from, to]`, or null. When [wholeWord], a hit
         * must not be flanked by identifier characters, so `rate` doesn't match inside `rateTable`.
         *
         * Occurrences are counted **among qualifying matches only** — with `wholeWord`, `occurrence: 2` means
         * the second standalone `rate`, not the second textual one. That is the reading an author intends.
         */
        private fun findNth(
            text: CharSequence,
            needle: String,
            from: Int,
            to: Int,
            n: Int,
            wholeWord: Boolean,
        ): TextRange? {
            var hits = 0
            var i = from.coerceAtLeast(0)
            while (i <= to) {
                val idx = text.indexOf(needle, i)
                if (idx < 0 || idx + needle.length > to) return null
                if (!wholeWord || isWholeWord(text, idx, needle.length)) {
                    hits++
                    if (hits == n) return TextRange(idx, idx + needle.length)
                }
                i = idx + 1
            }
            return null
        }

        private fun isWholeWord(text: CharSequence, start: Int, len: Int): Boolean {
            val before = if (start > 0) text[start - 1] else ' '
            val after = if (start + len < text.length) text[start + len] else ' '
            return !isIdentifierPart(before) && !isIdentifierPart(after)
        }

        /** Identifier characters across the languages we care about (`_` and `$` included, `-` not). */
        private fun isIdentifierPart(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '$'

        /** Gutter analogue of the gap layer's dotted gold left border; colour/width come from settings. */
        private fun gutterRenderer(color: Color, width: Int): LineMarkerRenderer =
            object : LineMarkerRenderer {
                override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
                    g.color = color
                    g.fillRect(r.x, r.y, width.coerceAtLeast(1), r.height)
                }
            }

        /**
         * The colour this editor's scheme paints comments in, for [FrameStyle.COMMENT]. Line comments first,
         * then block comments (a scheme may colour only one), then [fallback] — the callout's own body
         * colour, so an exotic scheme costs the disguise rather than the text.
         */
        private fun commentFg(scheme: EditorColorsScheme, fallback: Color): Color =
            scheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT)?.foregroundColor
                ?: scheme.getAttributes(DefaultLanguageHighlighterColors.BLOCK_COMMENT)?.foregroundColor
                ?: fallback

        /** Parse a `RRGGBB` (or `#RRGGBB`) hex string; falls back to the default accent on garbage. */
        private fun parseColor(hex: String): Color = try {
            Color(hex.trim().removePrefix("#").toInt(16))
        } catch (_: Exception) {
            Color(AudioExplainerSettings.DEF_ACCENT_COLOR.toInt(16))
        }

        /**
         * A 0-100 opacity setting as an alpha byte. 0 is allowed and is not the same as hiding the callout:
         * only the box's *fill* carries this alpha, so at 0 the text, the accent bar and the outline all
         * still draw — which is the one look no frame style offers on its own.
         */
        private fun alpha255(percent: Int): Int = (percent.coerceIn(0, 100) * 255 / 100)

        /** result = a·t + b·(1-t), component-wise, clamped to [0,255]. */
        private fun blend(a: Color, b: Color, t: Double): Color {
            fun mix(x: Int, y: Int) = (x * t + y * (1 - t)).toInt().coerceIn(0, 255)
            return Color(mix(a.red, b.red), mix(a.green, b.green), mix(a.blue, b.blue))
        }
    }
}
