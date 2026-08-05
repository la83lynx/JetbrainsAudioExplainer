package com.audioexplainer.tree

import com.audioexplainer.SpotlightRenderer
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.GeneralPath

/**
 * **The leader and the token box, in one place — because to the reader they are one object.**
 *
 * Constraint 18's second half, which is easy to read as cosmetic and is not: in 1a‴ the box used the raw settings
 * accent while the leader used it blended 0.80 toward the border, so the line the reader follows out of the box
 * was a visibly different colour from the box itself. Two call sites deriving "the accent, muted" independently
 * is the same mistake as two derivations of one rectangle, which the 1a‴ probe reported as a half-drawn box.
 *
 * It became a separate file rather than private to [CodeNestView] because a second surface needed the same
 * vocabulary: the split fallback was to draw this token box and this leader instead of the routed elbow it had
 * inherited from 1a″. That fallback is deleted, so there is one surface again — and the file stays separate,
 * because the round proved the point it was made for. Sharing the *path* was not enough: the fallback anchored its
 * leader to the line's **centre** while this anchors to the token box's **bottom**, and the reader caught it
 * immediately ("leader top corner height calculation is flawed in this mode"). One vocabulary means the origin and
 * the geometry, not only the shape.
 */
internal object LeaderArt {

    /**
     * The leader's colour: constraint 4's settings accent, muted toward the border, and muted further when the
     * expansion is folded away.
     */
    fun leaderColor(collapsed: Boolean = false): Color {
        val base = leaderBase()
        return if (collapsed) SpotlightRenderer.blendToward(base, JBColor.border(), COLLAPSED_MIX) else base
    }

    /** The unmuted base — what the token box, the stub's border and an un-collapsed leader all share. */
    fun leaderBase(): Color =
        SpotlightRenderer.blendToward(SpotlightRenderer.accentColor(), JBColor.border(), LEADER_MIX)

    /**
     * One definition of each leader shape, so the painter, the pre-flight probe and any check agree.
     *
     * All coordinates are in the painting component's space: `x1`/`x2` are the token box's bottom corners on row
     * 0, `w` is the component's width, and `bottom` is the last drawable row — which the caller has already
     * extended by the child chrome's drawn-line offset where there is one (constraint 16).
     */
    fun leaderPath(leader: NestLeader, x1: Int, x2: Int, w: Int, bottom: Int): GeneralPath {
        val p = GeneralPath()
        val right = (w - 1).toFloat()
        val b = bottom.toFloat()
        when (leader) {
            NestLeader.CORNERS -> {
                p.moveTo(x1.toFloat(), 0f)
                p.lineTo(0f, b)
                p.moveTo(x2.toFloat(), 0f)
                p.lineTo(right, b)
            }

            NestLeader.BRACE -> {
                // The spine sits above the tips so the turns have somewhere to happen; at 0.55 of the strip it
                // also stays clear of a `TITLED_BORDER` caption's own text, which starts at the drawn line.
                val spine = b * 0.55f
                val arc = minOf(BRACE_ARC, bottom / 3f)
                val mid = (x1 + x2) / 2f
                p.moveTo(mid, 0f)
                p.lineTo(mid, spine - arc)
                p.moveTo(mid - arc, spine)
                p.lineTo(arc, spine)
                p.quadTo(0f, spine, 0f, spine + arc)
                p.lineTo(0f, b)
                p.moveTo(mid + arc, spine)
                p.lineTo(right - arc, spine)
                p.quadTo(right, spine, right, spine + arc)
                p.lineTo(right, b)
                // The stem's two shoulders, which are what make it a brace rather than a T.
                p.moveTo(mid - arc, spine)
                p.quadTo(mid, spine, mid, spine - arc)
                p.moveTo(mid + arc, spine)
                p.quadTo(mid, spine, mid, spine - arc)
            }

            NestLeader.ARROW -> {
                val arc = minOf(ARROW_ARC, bottom / 3f)
                p.moveTo(x2.toFloat(), 0f)
                p.lineTo(right - arc, 0f)
                p.quadTo(right, 0f, right, arc)
                p.lineTo(right, b)
            }

            NestLeader.NONE -> Unit
        }
        return p
    }

    val SOLID: BasicStroke = BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

    val DASHED: BasicStroke = BasicStroke(
        1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(3f, 3f), 0f,
    )

    /**
     * Horizontal padding round a boxed token, in pixels — **measured, not chosen.**
     *
     * `EffectType.BOXED` draws on the glyph bounds, which `spikes/tree1a4/` measures as **0 px of clearance** to
     * the token's own first and last character: that is what "reads as clipping the text" is, and it is why the
     * effect had to be replaced by a renderer rather than tuned.
     *
     * 2 px is the cheapest padding that gives the glyphs air. Its cost is 2 pixels of stroke falling on a
     * character that immediately follows the token with no space (`…verdict);`) — a stroke crossing a glyph's
     * outline, and the box is painted *behind* the text so the glyph wins the pixel anyway. The cost does not fall
     * away with more padding: it goes 2 → 4 → 3 → 0 as the stroke walks across the adjacent glyph and out the
     * other side, so a wider box buys nothing but width.
     */
    const val TOKEN_PAD = 2

    /**
     * The box round the invoking token: an inflated rounded rectangle, drawn per paint from the highlighter's live
     * offsets.
     *
     * Nothing is cached, and the reason is measured: `offsetToXY` is what knows
     * where a folded, scrolled, re-fonted document draws, and all three of those move the box without changing
     * anything this class could listen to.
     */
    class TokenBoxRenderer(private val color: Color) : CustomHighlighterRenderer {

        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            val start = highlighter.startOffset
            val end = highlighter.endOffset
            if (end <= start || end > editor.document.textLength) return
            val a = editor.offsetToXY(start)
            val b = editor.offsetToXY(end)
            // Different rows means the token is split by a fold or a soft wrap. A box spanning both would enclose
            // whatever lies between them, which is a confident lie about where the call is.
            if (a.y != b.y || b.x <= a.x) return

            val lineHeight = editor.lineHeight
            val x = a.x - TOKEN_PAD
            val w = (b.x - a.x) + 2 * TOKEN_PAD
            // The row minus its last pixel: the probe measured this as clear of the glyph ink above and below, and
            // a taller box would reach the neighbouring rows, which no horizontal padding can compensate for.
            val h = (lineHeight - 1).coerceAtLeast(1)

            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.stroke = BOX
                g2.drawRoundRect(x, a.y, w, h, BOX_ARC, BOX_ARC)
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * Where the invoking token sits on a call line: the token's text if it is there, else the line's trimmed
     * extent.
     *
     * Shared by both surfaces because it is not a painting detail — it is the project's **locator rule** (§4.4
     * rule 2: text search only, for Rider parity), and two copies of a locator drift. [CodeNestView] boxes this to
     * run its leader from; the deleted split fallback boxed the same thing, and any second surface must agree about
     * which characters the call is, or the fallback stops being the same picture.
     *
     * Offsets are absolute in [document]; the caller supplies the read action.
     */
    fun locateCallToken(document: Document, line0: Int, token: String?): IntRange? {
        if (line0 < 0 || line0 >= document.lineCount) return null
        val start = document.getLineStartOffset(line0)
        val end = document.getLineEndOffset(line0)
        if (end <= start) return null
        val text = document.charsSequence.subSequence(start, end).toString()
        if (token != null) {
            val idx = text.indexOf(token)
            if (idx >= 0) return (start + idx) until (start + idx + token.length)
        }
        val lead = text.indexOfFirst { !it.isWhitespace() }
        if (lead < 0) return null
        val trail = text.indexOfLast { !it.isWhitespace() }
        return (start + lead)..(start + trail)
    }

    private const val LEADER_MIX = 0.80
    private const val COLLAPSED_MIX = 0.55
    private const val BRACE_ARC = 6f
    private const val ARROW_ARC = 5f
    private const val BOX_ARC = 5
    private val BOX = BasicStroke(1.2f)
}
