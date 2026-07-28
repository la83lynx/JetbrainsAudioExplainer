package com.audioexplainer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.nio.file.Path

/**
 * Editor "spotlight" highlighting — a port of code-explainer's `vscode-extension/src/highlight.ts`.
 *
 * Reproduces the four VS Code `TextEditorDecorationType` layers with IntelliJ [RangeHighlighter]s:
 *
 * | upstream decoration | this port |
 * |---|---|
 * | `dimDecoration` (opacity 0.35, outside segment)        | whole-line foreground blended 35 % toward bg |
 * | `segmentDimDecoration` (opacity 0.55, non-active highlights) | whole-line foreground blended 55 % |
 * | `activeDecoration` (soft white bg + gold overview ruler)     | whole-line bg glow + gold error-stripe mark |
 * | `gapDecoration` (opacity 0.55 + dotted gold left border)     | 55 % foreground + gold gutter bar |
 *
 * Notes on the mapping: VS Code decorations use CSS `opacity`; IntelliJ has no per-highlighter
 * opacity, so we emulate dimming by overriding the whole-line foreground with a colour blended
 * toward the editor background (this flattens syntax colours on dimmed lines, which is the intended
 * "de-emphasise" effect). The gap layer's dotted CSS left-border becomes a gutter [LineMarkerRenderer]
 * bar — the closest native analogue.
 *
 * Registered as the [Walkthrough]'s [WalkthroughListener]. Callbacks arrive on the server's HTTP
 * worker thread while the walkthrough lock is held, so each one returns immediately: file resolution
 * (which may hit disk / run a VFS refresh — forbidden on the EDT and undesirable under the lock) is
 * handed to a pooled thread, and only the editor mutations are then marshalled to the EDT.
 *
 * Threading of the mutable state below ([segStart]/[segEnd]/[tracked]/[trackedEditor]): only ever
 * touched inside the EDT bodies scheduled here, i.e. serially on the EDT — so no additional locking.
 */
class EditorHighlighter(private val project: Project) : WalkthroughListener {

    /** Current segment range (0-based, inclusive), tracked so [highlightSubRange] can dim around it. */
    private var segStart = 0
    private var segEnd = 0

    /** Highlighters we added, plus the editor whose markup model owns them, so we can clear on transition. */
    private val tracked = mutableListOf<RangeHighlighter>()
    private var trackedEditor: Editor? = null

    /** The segment currently spotlit (set once its editor is open), so [refresh] can re-render in place. */
    private var currentSegment: Segment? = null

    // ── WalkthroughListener ──

    override fun onSegment(segment: Segment) {
        resolveThenEdt(segment.file) { vFile ->
            val editor = openEditor(vFile, (segment.start - 1).coerceAtLeast(0)) ?: return@resolveThenEdt
            currentSegment = segment
            highlightSegmentRange(editor, segment.start, segment.end)
            // Upstream defers the sub-highlight to playback (it stays on the plain spotlight while
            // paused). We reveal highlight 0 immediately so a single `set_plan` demonstrates the full
            // four-layer treatment; the Phase 4 playback loop will re-drive sub-ranges as audio advances.
            segment.highlights.firstOrNull()?.let { hl ->
                highlightSubRange(editor, hl.start, hl.end, segment.highlights)
            }
        }
    }

    override fun onStatus(status: String) {
        if (status == Walkthrough.STATUS_STOPPED) {
            runOnEdt { clearHighlights() }
        }
    }

    /**
     * Move the spotlight to a specific sub-highlight of [segment] (Phase 7 narration). Opens the file
     * if needed and re-applies the four-layer treatment with [index] as the active highlight.
     */
    override fun onHighlight(segment: Segment, index: Int) {
        resolveThenEdt(segment.file) { vFile ->
            val editor = openEditor(vFile, (segment.start - 1).coerceAtLeast(0)) ?: return@resolveThenEdt
            val lastLine = (editor.document.lineCount - 1).coerceAtLeast(0)
            currentSegment = segment
            trackedEditor = editor
            segStart = (segment.start - 1).coerceIn(0, lastLine)
            segEnd = (segment.end - 1).coerceIn(segStart, lastLine)
            val hl = segment.highlights.getOrNull(index) ?: segment.highlights.firstOrNull() ?: return@resolveThenEdt
            highlightSubRange(editor, hl.start, hl.end, segment.highlights)
        }
    }

    /**
     * Re-apply the current segment's spotlight with freshly read [AudioExplainerSettings] — called by
     * the settings page so an appearance change re-styles the live walkthrough immediately. No-op if
     * nothing is currently shown. Safe to call from any thread (marshals to the EDT).
     */
    fun refresh() {
        runOnEdt {
            val seg = currentSegment ?: return@runOnEdt
            val editor = trackedEditor ?: return@runOnEdt
            if (editor.isDisposed) return@runOnEdt
            highlightSegmentRange(editor, seg.start, seg.end)
            seg.highlights.firstOrNull()?.let { hl ->
                highlightSubRange(editor, hl.start, hl.end, seg.highlights)
            }
        }
    }

    // ── Highlight primitives (EDT only) — mirror highlight.ts ──

    /** Dim everything outside `[startLine, endLine]` in [editor] (segment spotlight). */
    private fun highlightSegmentRange(editor: Editor, startLine: Int, endLine: Int) {
        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        val zeroStart = (startLine - 1).coerceIn(0, lastLine)
        val zeroEnd = (endLine - 1).coerceIn(zeroStart, lastLine)

        segStart = zeroStart
        segEnd = zeroEnd

        clearTracked()
        trackedEditor = editor

        val c = colors(editor)
        if (zeroStart - 1 >= 0) addLineRange(editor, 0, zeroStart - 1, c.dim)
        if (zeroEnd + 1 <= lastLine) addLineRange(editor, zeroEnd + 1, lastLine, c.dim)

        revealCenter(editor, zeroStart, zeroEnd)
    }

    /**
     * Spotlight a sub-range: undim the active lines, apply gold glow, dim the rest of the (possibly
     * expanded) segment. `allHighlights` lets us keep highlights that fall outside the declared
     * segment bounds visible, and distinguish non-active highlight lines from context ("gap") lines.
     */
    private fun highlightSubRange(editor: Editor, startLine: Int, endLine: Int, allHighlights: List<Highlight>) {
        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        val zeroStart = (startLine - 1).coerceIn(0, lastLine)
        val zeroEnd = (endLine - 1).coerceIn(zeroStart, lastLine)

        // Expand the effective range to encompass every highlight (and the active range).
        var effStart = segStart
        var effEnd = segEnd
        for (hl in allHighlights) {
            effStart = minOf(effStart, (hl.start - 1).coerceAtLeast(0))
            effEnd = maxOf(effEnd, (hl.end - 1).coerceAtLeast(0))
        }
        effStart = minOf(effStart, zeroStart).coerceIn(0, lastLine)
        effEnd = maxOf(effEnd, zeroEnd).coerceIn(effStart, lastLine)

        clearTracked()
        trackedEditor = editor

        val c = colors(editor)

        // Outer dim: everything outside the effective segment range.
        if (effStart - 1 >= 0) addLineRange(editor, 0, effStart - 1, c.dim)
        if (effEnd + 1 <= lastLine) addLineRange(editor, effEnd + 1, lastLine, c.dim)

        if (allHighlights.isNotEmpty()) {
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
                val h = addLineRange(editor, i, j, if (isGap) c.gap else c.segmentDim)
                if (isGap && h != null && c.gapGutterEnabled) {
                    h.lineMarkerRenderer = gutterRenderer(c.accent, c.gapGutterWidth)
                }
                i = j + 1
            }
        } else {
            // Fallback (no highlight metadata): lightly dim the segment lines around the active range.
            if (zeroStart - 1 >= effStart) addLineRange(editor, effStart, zeroStart - 1, c.segmentDim)
            if (zeroEnd + 1 <= effEnd) addLineRange(editor, zeroEnd + 1, effEnd, c.segmentDim)
        }

        // Active gold highlight + overview-ruler mark.
        addLineRange(editor, zeroStart, zeroEnd, c.active)
        revealCenter(editor, zeroStart, zeroEnd)
    }

    /** Remove all decorations (upstream `clearHighlights`). */
    private fun clearHighlights() {
        clearTracked()
        segStart = 0
        segEnd = 0
        currentSegment = null
    }

    // ── Threading / file resolution ──

    /**
     * Resolve the file OFF the EDT (VFS refresh is a prohibited slow op on the EDT, and we don't want
     * disk I/O on the HTTP thread that still holds the walkthrough lock), then run [action] with the
     * resolved [VirtualFile] on the EDT.
     */
    private fun resolveThenEdt(filePath: String, action: (VirtualFile) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val vFile = resolveFile(filePath) ?: return@executeOnPooledThread
            runOnEdt { action(vFile) }
        }
    }

    private fun runOnEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                try {
                    block()
                } catch (_: Throwable) {
                    // A closed editor / vanished file must not take down the HTTP server's caller.
                }
            }
        }
    }

    /** Off-EDT only: performs a synchronous VFS refresh so newly written files are found. */
    private fun resolveFile(filePath: String): VirtualFile? = try {
        val nio = Path.of(filePath)
        val abs = if (nio.isAbsolute) nio else project.basePath?.let { Path.of(it).resolve(nio) } ?: nio
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(abs.normalize())
    } catch (_: Exception) {
        null
    }

    /** EDT only. */
    private fun openEditor(vFile: VirtualFile, line: Int): Editor? =
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vFile, line, 0), true)

    /** Add a whole-line highlighter over the 0-based inclusive line range; tracked for later clearing. */
    private fun addLineRange(editor: Editor, startLine: Int, endLine: Int, attrs: TextAttributes): RangeHighlighter? {
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

    private fun clearTracked() {
        val editor = trackedEditor
        if (editor != null) {
            val model = editor.markupModel
            for (h in tracked) {
                try {
                    model.removeHighlighter(h)
                } catch (_: Throwable) {
                    // Editor/markup already disposed — nothing to remove.
                }
            }
        }
        tracked.clear()
    }

    private fun revealCenter(editor: Editor, zeroStart: Int, zeroEnd: Int) {
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
    )

    private fun colors(editor: Editor): Palette {
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
        return Palette(dim, segmentDim, gap, active, accent, st.gapGutter, st.gapGutterWidth)
    }

    companion object {
        /** Above syntax/semantic highlighting (so the dim foreground wins) but below the selection layer. */
        private val LAYER = HighlighterLayer.SELECTION - 1

        /** Gutter analogue of the gap layer's dotted gold left border; colour/width come from settings. */
        private fun gutterRenderer(color: Color, width: Int): LineMarkerRenderer =
            object : LineMarkerRenderer {
                override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
                    g.color = color
                    g.fillRect(r.x, r.y, width.coerceAtLeast(1), r.height)
                }
            }

        /** Parse a `RRGGBB` (or `#RRGGBB`) hex string; falls back to the default accent on garbage. */
        private fun parseColor(hex: String): Color = try {
            Color(hex.trim().removePrefix("#").toInt(16))
        } catch (_: Exception) {
            Color(AudioExplainerSettings.DEF_ACCENT_COLOR.toInt(16))
        }

        /** result = a·t + b·(1-t), component-wise, clamped to [0,255]. */
        private fun blend(a: Color, b: Color, t: Double): Color {
            fun mix(x: Int, y: Int) = (x * t + y * (1 - t)).toInt().coerceIn(0, 255)
            return Color(mix(a.red, b.red), mix(a.green, b.green), mix(a.blue, b.blue))
        }
    }
}
