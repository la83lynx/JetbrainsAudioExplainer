package com.audioexplainer.tree

import com.audioexplainer.SpotlightRenderer
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import javax.swing.JComponent

/**
 * **A still frame of the code tree, for the settings page** — one host node, one nested frame, and the join
 * between them, repainted whenever a *Code tree* control changes.
 *
 * It exists because five of that section's six dropdowns choose a *shape*, and a shape described in a
 * paragraph is a shape the reader has to imagine. `Nesting leader` alone cost 338 characters of prose to say
 * what three strokes say at a glance; `Collapse control` cost 520, most of it explaining that the bottom-edge
 * mode has no mark while a frame is open and puts an accent triangle in the strip instead — which this draws.
 *
 * **Every mark here is production's, and that is the whole design.** The repo deleted its `spikes/` because
 * standalone Swing files re-derived the geometry they drew and then agreed with the renderer only until one
 * of the two was edited — and the edit always lands in the renderer, where the work happens. So this asks
 * [LeaderArt] for the leader path, the token box, the chevron and the collapse hint; [LeaderArt.framePath]
 * for the frame; [CodeNodeView]'s companion for the caption text, the caption band's colour, the title face
 * and the non-active wash; and [ElidedLinesRenderer.paintSealed] for the sealed elision row. It names no
 * enum constant in a `when` except [NodeElision]'s two, which are two different painters rather than one
 * parameterised mark, and it restates no blend: [show] takes the [NestStyle]/[CodeNodeStyle] that
 * [TreeStyle] resolved, exactly as the renderer receives them.
 *
 * **What is the preview's own** is the miniature's layout — where the sample rows sit, how wide the nested
 * frame is, which node is the active one. That is a stage, not a rule: production lays a node out from a live
 * `Editor`'s line heights and an inlay's bounds, neither of which exists in a modal dialog. The stage is
 * arranged so each setting has something to change, and the one identity that *is* load-bearing is honoured
 * rather than approximated — [LeaderArt.BOX_BOTTOM_IS_STRIP_ROW_0], the box's bottom edge and the strip's row
 * 0 being one row, is why the strip starts at the box's bottom and not in a gap below it.
 *
 * **The drawing is a function of [Ink], not of the running IDE**, so `probes/TreeBench.java` can paint every
 * combination into a PNG in seconds without an Application — the same trade `probes/LinkBench.java` makes for
 * the link geometry, and the reason a mis-drawn leader is caught before a four-minute sandbox launch rather
 * than during one. The accent arrives through [Ink] for a second reason: on this page the reader is holding a
 * swatch the settings service has not been told about yet.
 */
internal class TreePreview : JComponent() {

    private var nest: NestStyle = TreeStyle.builtinNest()
    private var node: CodeNodeStyle = TreeStyle.builtinNode()
    private var accent: Color = SpotlightRenderer.accentColor()

    init {
        isOpaque = true
    }

    /** Repaint the frame for a resolved style. Cheap enough to call from every combo listener. */
    fun show(nest: NestStyle, node: CodeNodeStyle, accent: Color) {
        this.nest = nest
        this.node = node
        this.accent = accent
        revalidate()
        repaint()
    }

    private fun ink(): Ink = Ink.of(EditorColorsManager.getInstance().globalScheme, accent)

    override fun getPreferredSize(): Dimension {
        val ink = ink()
        val codeFm = getFontMetrics(ink.code)
        return Dimension(
            preferredWidth(codeFm),
            preferredHeight(codeFm, getFontMetrics(ink.title), nest),
        )
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            paint(g2, width, height, ink(), nest, node)
        } finally {
            g2.dispose()
        }
    }

    /**
     * Everything the frame draws with, in the shape an `EditorColorsScheme` hands it over.
     *
     * A bundle rather than a scheme parameter because the two callers cannot both produce a scheme: the
     * settings page has the global one, and the bench has no Application at all. Both can produce fonts and
     * colours, which is all the drawing ever wanted.
     */
    class Ink(
        val code: Font,
        val title: Font,
        val italic: Font,
        val bg: Color,
        val fg: Color,
        /** The reader's scheme decides how a fold placeholder looks; null keeps the plain code colours. */
        val foldedFg: Color?,
        val foldedBg: Color?,
        val accent: Color,
    ) {
        companion object {
            fun of(scheme: EditorColorsScheme, accent: Color): Ink {
                val folded = scheme.getAttributes(EditorColors.FOLDED_TEXT_ATTRIBUTES)
                return Ink(
                    code = scheme.getFont(EditorFontType.PLAIN),
                    title = CodeNodeView.titleFont(scheme),
                    italic = scheme.getFont(EditorFontType.ITALIC),
                    bg = scheme.defaultBackground,
                    fg = scheme.defaultForeground,
                    foldedFg = folded?.foregroundColor,
                    foldedBg = folded?.backgroundColor,
                    accent = accent,
                )
            }
        }
    }

    companion object {

        /** The stand-in for `editor.lineHeight` — `LinkBench` uses a fixed 20 for the same reason. */
        private fun rowHeight(fm: FontMetrics): Int = fm.height

        private fun bandHeight(fm: FontMetrics): Int = fm.height + 2 * CodeNodeView.STRIP_PAD

        fun preferredWidth(codeFm: FontMetrics): Int =
            codeFm.stringWidth(HOST_CALL_PREFIX + HOST_TOKEN + HOST_CALL_SUFFIX) + 3 * NEST_INSET + 2 * PAD + 140

        fun preferredHeight(codeFm: FontMetrics, titleFm: FontMetrics, nest: NestStyle): Int {
            val row = rowHeight(codeFm)
            val band = bandHeight(titleFm)
            // host band + 2 host rows + the box's own pad + strip + (nested band + 3 nested rows)
            // + the gap production leaves under an embedded frame + the closing row + the host's own floor
            return band + 2 * row + nest.boxPadBelow + nest.stripHeight() + (band + 3 * row) +
                nest.gapBelow + row + FLOOR + 2 * PAD
        }

        /**
         * The whole miniature, onto any `Graphics2D` — a settings widget's, or a `BufferedImage`'s.
         *
         * Order matters twice: the nested frame's wash and caption go down *before* its outline so the outline
         * is not washed over, and the host's outline goes down last so the nesting reads as embedded in it.
         */
        fun paint(g2: Graphics2D, width: Int, height: Int, ink: Ink, nest: NestStyle, node: CodeNodeStyle) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val codeFm = g2.getFontMetrics(ink.code)
            val titleFm = g2.getFontMetrics(ink.title)
            val row = rowHeight(codeFm)
            val band = bandHeight(titleFm)

            g2.color = ink.bg
            g2.fillRect(0, 0, width, height)

            // ── The host node ──────────────────────────────────────────────────
            val hostX = PAD
            val hostW = width - 2 * PAD - 1
            val hostY = PAD
            var y = hostY

            caption(g2, hostX, y, hostW, band, ink, titleFm, active = true, label = HOST_NAME + TITLE_SEP + HOST_TITLE)
            y += band

            text(g2, ink, hostX + TEXT_PAD, y, codeFm, HOST_ROW_0)
            y += row

            // The call row, and the one row whose x's matter: the token's own extent is what the box is
            // inflated from ([LeaderArt.boxEdges]) and therefore where every leader starts.
            val callY = y
            text(g2, ink, hostX + TEXT_PAD, callY, codeFm, HOST_CALL_PREFIX + HOST_TOKEN + HOST_CALL_SUFFIX)
            val tokenX1 = hostX + TEXT_PAD + codeFm.stringWidth(HOST_CALL_PREFIX)
            val tokenX2 = tokenX1 + codeFm.stringWidth(HOST_TOKEN)
            val (boxLeft, boxRight) = LeaderArt.boxEdges(tokenX1, tokenX2)
            val box = Rectangle(boxLeft, callY, boxRight - boxLeft, row + nest.boxPadBelow)

            g2.color = LeaderArt.leaderBase(ink.accent)
            LeaderArt.drawTokenBox(g2, box.x, box.y, box.width, box.height, LeaderArt.closesTokenBox(nest.leader))
            // The frame below is *open*, which is the state the bottom-edge mode has no mark for — so this is
            // also the row that shows `Collapse control` handing its job to the strip's accent triangle.
            LeaderArt.chevronRect(box, nest.chevron, COLLAPSED)?.let {
                LeaderArt.drawChevron(g2, it, nest.chevron, COLLAPSED, ink.bg)
            }

            // ── The leader strip ───────────────────────────────────────────────
            // [LeaderArt.BOX_BOTTOM_IS_STRIP_ROW_0]: row 0 of the strip *is* the box's bottom edge, so the
            // strip starts there rather than below it, and every leader's origin is the box's own corners.
            val stripTop = box.y + box.height
            val stripH = nest.stripHeight()
            val nestX = hostX + NEST_INSET
            val nestW = hostW - 2 * NEST_INSET

            val strip = g2.create() as Graphics2D
            try {
                strip.translate(nestX, stripTop)
                strip.color = LeaderArt.leaderColor(false, ink.accent)
                strip.stroke = LeaderArt.SOLID
                strip.draw(LeaderArt.leaderPath(nest.leader, boxLeft - nestX, boxRight - nestX, nestW, stripH - 1))
                if (nest.chevron == ChevronStyle.BOTTOM_BORDER) {
                    LeaderArt.collapseHintRect(boxLeft - nestX, boxRight - nestX, stripH - 1)?.let {
                        LeaderArt.drawCollapseHint(strip, it, ink.accent)
                    }
                }
            } finally {
                strip.dispose()
            }

            // ── The nested frame, drawn as the NON-active node so de-emphasis has something to show ──
            val nestY = stripTop + stripH
            val nestH = band + 3 * row
            if (node.deemphasis.tints) {
                g2.color = CodeNodeView.deemphasisTint(ink.accent, ink.bg)
                g2.fillRect(nestX, nestY, nestW, nestH)
            }
            caption(g2, nestX, nestY, nestW, band, ink, titleFm, active = false, label = captionFor(node.path))

            var ny = nestY + band
            text(g2, ink, nestX + TEXT_PAD, ny, codeFm, NEST_ROW_0)
            ny += row
            elision(g2, ink, node.elision, nestX + TEXT_PAD, ny, row, codeFm)
            ny += row
            text(g2, ink, nestX + TEXT_PAD, ny, codeFm, NEST_ROW_2)

            // The frame's own outline. Its colour is the node's rule: the shared outline, unless de-emphasis
            // is one of the kinds that dims a non-active border.
            g2.color = if (node.deemphasis.dimsBorder) JBColor.border() else LeaderArt.leaderBase(ink.accent)
            g2.stroke = THIN
            g2.draw(
                LeaderArt.framePath(
                    nestX, nestY, nestW, nestH,
                    LeaderArt.drawsFrameTop(nest.leader), LeaderArt.roundsFrameTop(nest.leader),
                ),
            )

            // ── Back out to the host, so the nesting reads as embedded rather than as two stacked boxes ──
            // `gapBelow` is production's own clearance under an embedded frame; without it the host's closing
            // row starts on the frame's bottom edge and the two draw over each other.
            val lastRowY = nestY + nestH + nest.gapBelow
            text(g2, ink, hostX + TEXT_PAD, lastRowY, codeFm, HOST_ROW_LAST)
            g2.color = LeaderArt.leaderBase(ink.accent)
            g2.stroke = ACTIVE
            g2.draw(LeaderArt.framePath(hostX, hostY, hostW, lastRowY + row + FLOOR - hostY, true, false))
        }

        /** A node's caption band — the band's colour, the face and the ink are all [CodeNodeView]'s rules. */
        private fun caption(
            g2: Graphics2D,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
            ink: Ink,
            fm: FontMetrics,
            active: Boolean,
            label: String,
        ) {
            g2.color = CodeNodeView.headerBandColor(active, ink.accent)
            g2.fillRect(x + 1, y + 1, w - 2, h)
            g2.font = ink.title
            g2.color = CodeNodeView.titleColor(active, ink.accent)
            g2.drawString(label, x + CodeNodeView.STRIP_PAD + 3, y + CodeNodeView.STRIP_PAD + fm.ascent)
        }

        /** The hidden-lines row, in whichever of the two shapes the mode names. */
        private fun elision(
            g2: Graphics2D,
            ink: Ink,
            elision: NodeElision,
            x: Int,
            y: Int,
            row: Int,
            codeFm: FontMetrics,
        ) = when (elision) {
            // Drawn by the production renderer's own routine, off a rectangle instead of a fold region — so
            // the rule segments and the two colour mixes cannot differ from the ones a node gets.
            NodeElision.SEALED -> ElidedLinesRenderer.paintSealed(
                g2, Rectangle2D.Double(x.toDouble(), y.toDouble(), SEALED_W, row.toDouble()),
                ink.italic, ink.fg, ink.accent, ELIDED_LINES,
            )
            // The openable one is a *text* placeholder in the editor's own folded-text attributes, which is
            // why this reads them rather than picking a colour: the reader's scheme decides how a fold looks.
            NodeElision.OPENABLE -> {
                val placeholder = CodeNodeView.placeholderFor(ELIDED_LINES)
                g2.font = ink.code
                ink.foldedBg?.let {
                    g2.color = it
                    g2.fillRect(x, y, codeFm.stringWidth(placeholder), row)
                }
                g2.color = ink.foldedFg ?: ink.fg
                g2.drawString(placeholder, x, y + codeFm.ascent)
            }
        }

        private fun text(g2: Graphics2D, ink: Ink, x: Int, y: Int, fm: FontMetrics, s: String) {
            g2.font = ink.code
            g2.color = ink.fg
            g2.drawString(s, x, y + fm.ascent)
        }

        /** The sample node's path in whichever of the three forms the mode names. */
        private fun captionFor(path: NodePath): String =
            CodeNodeView.captionText(path, SAMPLE_FULL, SAMPLE_RELATIVE, SAMPLE_NAME, "")

        /** The nested frame is drawn open, which is the state that has something to say about every mode. */
        private const val COLLAPSED = false

        private const val PAD = 6

        /** Room under the host's closing row so its own bottom edge is not drawn on the descenders. */
        private const val FLOOR = 3
        private const val TEXT_PAD = 8
        private const val NEST_INSET = 14
        private const val ELIDED_LINES = 12
        private const val SEALED_W = 240.0
        private const val TITLE_SEP = "  ·  "

        private val THIN = BasicStroke(1f)

        /** The active node's border is 2 px in production; the host is the active one here. */
        private val ACTIVE = BasicStroke(2f)

        private const val HOST_NAME = "Checkout.cs"
        private const val HOST_TITLE = "PlaceOrder"
        private const val HOST_ROW_0 = "var cart = Load(id);"
        private const val HOST_CALL_PREFIX = "var total = "
        private const val HOST_TOKEN = "Compute"
        private const val HOST_CALL_SUFFIX = "(cart);"
        private const val HOST_ROW_LAST = "return Confirm(total);"

        private const val NEST_ROW_0 = "decimal Compute(Cart c) {"
        private const val NEST_ROW_2 = "}"

        // A sample file in all three forms, because `File path in captions` is a choice *between* them and a
        // preview that held one form could not demonstrate the other two.
        private const val SAMPLE_NAME = "Rules.cs"
        private const val SAMPLE_RELATIVE = "src/Pricing/Rules.cs"
        private val SAMPLE_FULL: String = if (SystemInfo.isWindows) {
            "C:\\Projects\\Shop\\" + SAMPLE_RELATIVE.replace('/', '\\')
        } else {
            "/home/you/shop/$SAMPLE_RELATIVE"
        }
    }
}
