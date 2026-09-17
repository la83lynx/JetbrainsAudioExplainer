package com.audioexplainer

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * **A still frame of a narrated step, for the settings page** — four dimmed and lit code rows, the callout
 * box above them, the gap gutter bar, the overview mark and one relationship arrow, repainted whenever any
 * *Appearance* control changes.
 *
 * One frame for the whole section rather than one per sub-group, because these settings are not independent:
 * the accent decides the gutter bar, the non-active tint, the callout's hue *and* the arrow palette, and the
 * two dim percentages only mean anything relative to each other and to the glow. A reader tuning
 * `Within-segment brightness` is really asking "is the non-active row still clearly brighter than the
 * outside ones", and that is a question about the picture, not about the number. It sits above the
 * sub-groups so it stays visible while any one of them is open.
 *
 * **Every colour and every mark is production's.** [SpotlightRenderer.palette] is the one derivation of the
 * dims, the glow, the tint and the arrow metrics, and it is asked here with the *pending* settings state so
 * the frame is a step ahead of Apply rather than a step behind it. The box and its tail come from
 * [SpotlightRenderer.paintCalloutFrame] / [SpotlightRenderer.drawCalloutLeader], the bar from
 * [SpotlightRenderer.paintGapGutter], the face from [SpotlightRenderer.calloutFont], and the arrow from
 * [LinkArt] — which needed no change at all, having been extracted for `probes/LinkBench.java` already.
 *
 * **What is the preview's own** is the stage: which row is active, what the sample says, where the arrow
 * runs. Production takes those from a plan and a live editor, neither of which exists in a modal dialog.
 * The drawing is a function of [Ink] and a `Graphics2D` so `probes/AppearanceBench.java` can paint it into
 * a PNG with no Application, exactly as `TreePreview` does.
 */
internal class AppearancePreview : JComponent() {

    private var ink: Ink? = null

    init {
        isOpaque = true
    }

    /** Repaint for a pending settings state. Cheap enough to call from every spinner and swatch. */
    fun show(state: AudioExplainerSettings.State) {
        ink = Ink.of(EditorColorsManager.getInstance().globalScheme, state)
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val i = ink ?: return Dimension(WIDTH, 150)
        return Dimension(WIDTH, preferredHeight(getFontMetrics(i.code), getFontMetrics(i.prose)))
    }

    override fun paintComponent(g: Graphics) {
        val i = ink ?: return
        val g2 = g.create() as Graphics2D
        try {
            paint(g2, width, height, i)
        } finally {
            g2.dispose()
        }
    }

    /**
     * Everything the frame draws with. The [palette] is the production one; the fonts are resolved through
     * the production rule. Held together so the bench can build one without a scheme.
     */
    class Ink(
        val palette: SpotlightRenderer.Palette,
        val code: Font,
        val prose: Font,
        val bg: Color,
    ) {
        companion object {
            fun of(scheme: EditorColorsScheme, st: AudioExplainerSettings.State): Ink {
                val palette = SpotlightRenderer.palette(scheme, st)
                return Ink(
                    palette = palette,
                    code = SpotlightRenderer.calloutFont(
                        scheme, SpotlightRenderer.BlockStyle.CODE, "", 100,
                    ),
                    prose = SpotlightRenderer.calloutFont(
                        scheme, SpotlightRenderer.BlockStyle.PROSE,
                        st.calloutProseFont.trim(), st.calloutTextScalePercent,
                    ),
                    bg = scheme.defaultBackground,
                )
            }
        }
    }

    companion object {

        /**
         * The band the callout needs, measured rather than guessed: it follows `Text size`, which goes to
         * 160%, and a fixed number would clip the box there.
         */
        private fun calloutBand(proseFm: FontMetrics): Int = proseFm.height + 2 * CALLOUT_PAD_Y + LEADER_ROOM

        /**
         * Height is the same whichever way the box is docked — the band is simply above the rows or below
         * them. Reserving it on one side only is what clipped the box off the canvas at `Dock below`.
         */
        fun preferredHeight(codeFm: FontMetrics, proseFm: FontMetrics): Int =
            calloutBand(proseFm) + ROWS.size * codeFm.height + 2 * PAD

        /**
         * The whole frame, onto any `Graphics2D`.
         *
         * Painted back to front: the row backgrounds (glow, non-active tint), then the gutter bar and the
         * overview mark, then the code, then the arrow over it, then the callout last — which is the order
         * the editor composites them in, and the reason a low callout opacity lets an arrow read through.
         */
        fun paint(g2: Graphics2D, width: Int, height: Int, ink: Ink) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val p = ink.palette
            val codeFm = g2.getFontMetrics(ink.code)
            val row = codeFm.height
            val textX = PAD + GUTTER_W

            g2.color = ink.bg
            g2.fillRect(0, 0, width, height)

            // The rows take the half of the canvas the callout is not docked in.
            val top = PAD + if (p.calloutBelow) 0 else calloutBand(g2.getFontMetrics(ink.prose))
            ROWS.forEachIndexed { i, sample ->
                val y = top + i * row
                val attrs = when (sample.role) {
                    Role.OUTSIDE -> p.dim
                    Role.GAP -> p.gap
                    Role.ACTIVE -> p.active
                    Role.NON_ACTIVE -> p.segmentDim
                }
                attrs.backgroundColor?.let {
                    g2.color = it
                    g2.fillRect(PAD, y, width - 2 * PAD - RULER_W - 2, row)
                }
                if (sample.role == Role.GAP && p.gapGutterEnabled) {
                    SpotlightRenderer.paintGapGutter(
                        g2, Rectangle(PAD, y, GUTTER_W, row), p.accent, p.gapGutterWidth,
                    )
                }
                // The overview mark is the accent, verbatim, and only when the row carries one — which for
                // the shipped rule is the active row and only while the setting is on.
                if (attrs.errorStripeColor != null) {
                    g2.color = attrs.errorStripeColor
                    g2.fillRect(width - RULER_W - 1, y, RULER_W, row)
                }
                g2.font = ink.code
                g2.color = attrs.foregroundColor
                g2.drawString(sample.text, textX, y + codeFm.ascent)
            }

            arrow(g2, ink, top, row, width)
            callout(g2, ink, textX, top, row, width)
        }

        /**
         * One relation, from the active row to the row below it.
         *
         * Built through [LinkArt.shape] and painted through [LinkArt.paint], so `Thickness`, `Arrowhead
         * size`, `Square the elbow corners` and the slot colours are the reader's own — `LinkArt.metrics`
         * has already been applied inside the palette's [SpotlightRenderer.ArrowStyle]. The lane axis comes
         * from [LinkArt.laneAxes], which is the same call `LinkBench` makes to pose a node without an editor.
         */
        private fun arrow(g2: Graphics2D, ink: Ink, top: Int, row: Int, width: Int) {
            val p = ink.palette
            val metrics = p.arrow.metrics
            val codeFm = g2.getFontMetrics(ink.code)
            val textX = PAD + GUTTER_W
            val joins = listOf(LinkJoin.LINE)
            // **The channel starts past the longest line**, which is what `LinkBench.demand` computes and
            // what keeps a rail off the code rather than across it. Clamped so a very narrow dialog still
            // leaves the label somewhere to go.
            val longest = ROWS.maxOf { codeFm.stringWidth(it.text) }
            val channel = (textX + longest + LinkArt.channelGap())
                .coerceAtMost(width - RULER_W - LABEL_ROOM)
            val axis = channel + LinkArt.laneAxes(joins, row)[0]

            val fromRow = ROWS.indexOfFirst { it.role == Role.ACTIVE }
            val toRow = ROWS.indexOfFirst { it.role == Role.NON_ACTIVE }
            if (fromRow < 0 || toRow < 0) return

            val from = leg(ink, codeFm, textX, top + fromRow * row + row / 2, axis, row, metrics, false)
            val to = leg(ink, codeFm, textX, top + toRow * row + row / 2, axis, row, metrics, true)
            if (from == null || to == null) return
            LinkArt.paintPair(g2, from, to, p.arrow.colorFor(0), ink.bg, false)

            if (p.arrow.labels) {
                g2.font = ink.prose
                val fm = g2.getFontMetrics(ink.prose)
                LinkArt.paintLabel(
                    g2, to, LABEL, p.calloutPointerFg, ink.prose, fm,
                    width - RULER_W - PAD, axis, LinkArt.channelGap(), ink.bg,
                )
            }
        }

        private fun leg(
            ink: Ink,
            codeFm: FontMetrics,
            textX: Int,
            rowMid: Int,
            axis: Int,
            row: Int,
            metrics: LinkMetrics,
            incoming: Boolean,
        ) = LinkArt.shape(
            LinkAnchor.TOKEN, LinkJoin.LINE, 0, 0,
            textX, textX + codeFm.stringWidth(if (incoming) ROWS[3].text else ROWS[2].text),
            rowMid, axis, row, incoming, !incoming, LinkRoute.DIRECTIONAL, false, metrics,
        )

        /**
         * The callout, docked above the rows by default and below them when the reader says so.
         *
         * One line of sample prose rather than the production wrap engine: the wrap is a function of the
         * editor's viewport, which a settings dialog does not have, and none of the six controls this frame
         * answers is about wrapping. The *box* is production's, which is the part they are about.
         */
        private fun callout(g2: Graphics2D, ink: Ink, x: Int, top: Int, row: Int, width: Int) {
            val p = ink.palette
            val style = p.calloutStyle
            val fm = g2.getFontMetrics(ink.prose)
            val barW = SpotlightRenderer.barWidth(style)
            // Clamped to the room there is, which is what production does too — it wraps the aside to the
            // editor's viewport. The frame answers `Text size` by growing taller; letting it also grow wider
            // than the canvas just pushed the sample off the right edge at 160%.
            val w = (barW + 2 * CALLOUT_PAD_X + fm.stringWidth(ASIDE)).coerceAtMost(width - x - PAD - RULER_W)
            val h = fm.height + 2 * CALLOUT_PAD_Y
            // Docked below, the box moves under the rows and its tail points up at them.
            val y = if (style.below) top + ROWS.size * row + LEADER_ROOM else PAD

            SpotlightRenderer.paintCalloutFrame(g2, x, y, w, h, style, p.accent, p.calloutBg)
            if (style.leader) {
                SpotlightRenderer.drawCalloutLeader(g2, x, y, w, h, style, p.accent, p.calloutBg)
            }
            g2.font = ink.prose
            g2.color = if (style.frame == SpotlightRenderer.FrameStyle.COMMENT) style.commentFg else p.calloutFg
            val prefix = if (style.frame == SpotlightRenderer.FrameStyle.COMMENT) "// " else ""
            // Clipped to the box's text area, so a clamped box truncates its sample instead of spilling it.
            val text = g2.create() as Graphics2D
            try {
                text.clipRect(x + barW, y, w - barW - CALLOUT_PAD_X, h)
                text.font = ink.prose
                text.color = g2.color
                text.drawString(prefix + ASIDE, x + barW + CALLOUT_PAD_X, y + CALLOUT_PAD_Y + fm.ascent)
            } finally {
                text.dispose()
            }
        }

        private enum class Role { OUTSIDE, GAP, ACTIVE, NON_ACTIVE }

        private class Sample(val text: String, val role: Role)

        /** The stage: two rows outside the segment, a context row, the active row and a non-active one. */
        private val ROWS = listOf(
            Sample("public decimal Total(Cart cart) {", Role.OUTSIDE),
            Sample("    var lines = cart.Lines;", Role.GAP),
            Sample("    var total = Compute(lines);", Role.ACTIVE),
            Sample("    Persist(total);", Role.NON_ACTIVE),
            Sample("}", Role.OUTSIDE),
        )

        private const val ASIDE = "The aside about this line reads like this."
        private const val LABEL = "writes"

        private const val PAD = 6
        private const val GUTTER_W = 10
        private const val RULER_W = 4
        private const val LEADER_ROOM = 12

        /** Space kept to the right of the link channel for the relationship note. */
        private const val LABEL_ROOM = 150

        private const val CALLOUT_PAD_X = 8
        private const val CALLOUT_PAD_Y = 4

        private const val WIDTH = 560
    }
}
