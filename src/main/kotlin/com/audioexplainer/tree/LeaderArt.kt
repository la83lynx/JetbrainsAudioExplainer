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
 * **The one outline: the token box, the leader and the child's frame, defined in one place.**
 *
 * Constraint 16, and it is the reason this file is not three files. In the two joining modes the leader does not
 * *land on* a border — it **is** the border for the length of its run: the box gives up its bottom edge, the frame
 * gives up its top edge, and the three drawings form a single continuous path from the frame's bottom-left, up
 * and around the token, and back down to the frame's bottom-right. Measured as exactly that in
 * `spikes/tree1a5/OutlineProbe.java`: **one 8-connected component** in `CORNERS` and `BRACE`, and **two** in
 * `ARROW`, where the frame is a separate component by design because an arrow is a reference and not a join.
 *
 * Two consequences that are not stylistic, and both live here rather than at the call sites:
 *
 * - **A border segment's owner depends on the leader mode**, so [closesTokenBox] and [drawsFrameTop] are the one
 *   place that maps a mode onto which edges each drawing owns. [CodeNodeView] and [TokenBoxRenderer] are *told*;
 *   neither infers it.
 * - **The origin is shared, not just the path.** 1a‴'s defect was two notions of "where the call is" — the split
 *   fallback anchored to a line's *centre*, this surface to the box's *bottom* — and the round caught it
 *   immediately. So [BOX_BOTTOM_IS_STRIP_ROW_0] is a documented identity rather than an arithmetic coincidence:
 *   the row the box's (possibly suppressed) bottom edge sits on **is** the leader strip's row 0. The probe checks
 *   it as its first assertion, because a 1 px disagreement there is invisible on a vertical wall and reads as a
 *   kink in `ARROW`'s collinear run.
 *
 * The corner radius is shared for the same reason. Constraint 16's recorded lesson is that sharing one number was
 * not enough when a second — the radius — was not: [ARC] is the frame's corners, the box's corners and the brace's
 * outer turn.
 */
internal object LeaderArt {

    /**
     * **The identity the whole outline is built on.** `boxBottomRow == callRowY + lineHeight == the strip's row 0`.
     *
     * Stated as a constant so it can be referenced from a comment rather than re-derived: the block inlay holding
     * the nested frame is placed at the call line's end offset with `showAbove = false`, so the frame's first row
     * is the row immediately after the call row — which is where [TokenBoxRenderer] puts the box's bottom edge and
     * where every [leaderPath] starts. Not `lineHeight - 1`: that would put the box's bottom edge one row above the
     * leader's origin, adjacent enough to be invisible on the vertical walls of `CORNERS`/`BRACE` and visible as a
     * 1 px step in `ARROW`'s horizontal extension.
     */
    const val BOX_BOTTOM_IS_STRIP_ROW_0 = true

    /**
     * The leader's colour: constraint 4's settings accent, muted toward the border, and muted further when the
     * expansion is folded away.
     */
    fun leaderColor(collapsed: Boolean = false): Color {
        val base = leaderBase()
        return if (collapsed) SpotlightRenderer.blendToward(base, JBColor.border(), COLLAPSED_MIX) else base
    }

    /**
     * The unmuted base — what the token box, the **frame's own border**, the stub's border and an un-collapsed
     * leader all share.
     *
     * Constraint 18 required the box and the leader to match ("they are the same object to the reader's eye").
     * Constraint 16 extends it to the frame: they are now literally one path, so a divergence would show as a
     * colour seam mid-stroke rather than as two similar lines. [CodeNodeView] therefore derives its border colour
     * from here instead of keeping its own 0.75 mix — one derivation, not two numbers that happen to be close.
     */
    fun leaderBase(): Color =
        SpotlightRenderer.blendToward(SpotlightRenderer.accentColor(), JBColor.border(), LEADER_MIX)

    // ── which drawing owns which edge (constraint 16) ──

    /**
     * True when the token box draws all four sides.
     *
     * Only [NestLeader.ARROW], which points at the frame instead of joining it, so both outlines stay closed. In
     * the joining modes the box's bottom edge is where the leader takes over — 2 px of padding on three sides, and
     * the fourth side is the leader (constraint 18).
     */
    fun closesTokenBox(leader: NestLeader): Boolean = leader == NestLeader.ARROW

    /**
     * True when the child's frame draws its top edge.
     *
     * Only [NestLeader.ARROW]. In `CORNERS`/`BRACE` there is no top run — and **that is what dissolved the
     * rounded-corner defect** rather than working around it: with no top edge there is no top corner to round. The
     * "corner" is just where a side wall begins, and the leader arrives into it.
     */
    fun drawsFrameTop(leader: NestLeader): Boolean = leader == NestLeader.ARROW

    /**
     * One definition of each leader shape, so the painter, the pre-flight probe and any check agree.
     *
     * All coordinates are in the painting component's space: `x1`/`x2` are the token box's bottom corners on
     * **row 0** — the row the box's suppressed bottom edge occupies, see [BOX_BOTTOM_IS_STRIP_ROW_0] — `w` is the
     * component's width, and `bottom` is the last drawable row. `bottom` is `height - 1` and not `height` because
     * a stroke drawn at `y == height` is clipped out of existence (measured, twice).
     *
     * **No overlap is needed at the bottom end**, which is the question §1a⁵ 1 left open: the leader's tip on row
     * `height - 1` and the frame's side wall on its own row 0 are adjacent rows, and the probe measures the result
     * as one connected component. So the strip covers none of the child's rows.
     */
    fun leaderPath(
        leader: NestLeader,
        x1: Int,
        x2: Int,
        w: Int,
        bottom: Int,
        arrowTurn: Float = DEFAULT_ARROW_TURN,
    ): GeneralPath {
        val p = GeneralPath()
        val right = (w - 1).toFloat()
        val b = bottom.toFloat()
        // Clamped to the band: at a small strip height a full-radius turn would consume the whole vertical run.
        val arc = minOf(ARC.toFloat(), bottom / 3f).coerceAtLeast(0f)
        when (leader) {
            // Two straight runs, box's bottom-left → frame's top-left and box's bottom-right → frame's top-right.
            NestLeader.CORNERS -> {
                p.moveTo(x1.toFloat(), 0f)
                p.lineTo(0f, b)
                p.moveTo(x2.toFloat(), 0f)
                p.lineTo(right, b)
            }

            // Two L-shaped halves: outward at the box's bottom-edge level to the side wall, one right angle at
            // that outer end, then down into the frame's top corner. No central stem and no spine across the
            // middle — the built version had both, and the probe's emptiness test uses it as the control.
            NestLeader.BRACE -> {
                if (x1 > arc) {
                    p.moveTo(x1.toFloat(), 0f)
                    p.lineTo(arc, 0f)
                    p.quadTo(0f, 0f, 0f, arc)
                } else {
                    // The token starts at (or left of) the frame's own wall: there is no outward run to make, so
                    // the half degenerates to the wall itself rather than running backwards.
                    p.moveTo(0f, 0f)
                }
                p.lineTo(0f, b)
                if (x2 < right - arc) {
                    p.moveTo(x2.toFloat(), 0f)
                    p.lineTo(right - arc, 0f)
                    p.quadTo(right, 0f, right, arc)
                } else {
                    p.moveTo(right, 0f)
                }
                p.lineTo(right, b)
            }

            // The box's bottom edge continued past its own bottom-right corner — collinear, so it reads as the
            // edge extended and not as a new line leaving a corner — then down, ending in an arrowhead that
            // points at the frame's top border without touching it.
            NestLeader.ARROW -> {
                val turn = arrowTurnX(x2, w, arrowTurn)
                val tip = b - ARROW_TIP_GAP
                val shaftEnd = maxOf(tip - ARROW_HEAD_H, arc)
                p.moveTo(x2.toFloat(), 0f)
                p.lineTo(maxOf(turn - arc, x2.toFloat()), 0f)
                p.quadTo(turn, 0f, turn, arc)
                p.lineTo(turn, shaftEnd)
                // An open chevron rather than a filled triangle, so the head is the same stroke as the rest of
                // the outline and does not read as a second object stuck on the end.
                p.moveTo(turn - ARROW_HEAD_W / 2f, tip - ARROW_HEAD_H)
                p.lineTo(turn, tip)
                p.lineTo(turn + ARROW_HEAD_W / 2f, tip - ARROW_HEAD_H)
            }
        }
        return p
    }

    /**
     * Where [NestLeader.ARROW] turns down: a fraction of the span from the box's right edge to the frame's.
     *
     * **The only free parameter in any of the three shapes.** The built version ran to the frame's far right
     * corner and the round called it unnatural; the specification is a point strictly *between* the two edges.
     * Clamped at both ends so the arc has room going in and half a head has room on either side coming out —
     * which is also what keeps the ordinal guarantee the probe asserts at 30 / 50 / 70 %.
     */
    fun arrowTurnX(x2: Int, w: Int, fraction: Float): Float {
        val right = (w - 1).toFloat()
        val lo = minOf(x2 + ARC + 2f, right)
        val hi = maxOf(lo, right - ARROW_HEAD_W / 2f - 1f)
        return (x2 + (right - x2) * fraction).coerceIn(lo, hi)
    }

    val SOLID: BasicStroke = BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

    val DASHED: BasicStroke = BasicStroke(
        1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(3f, 3f), 0f,
    )

    /**
     * The corner radius of **everything in the outline**: the frame's corners, the box's corners and the brace's
     * outer turn.
     *
     * One number because constraint 16's second lesson is that sharing one (the path) was not enough while a
     * second (the radius) was not shared — that is what produced the rounded-corner defect the scrapped
     * `TITLED_BORDER` introduced.
     */
    const val ARC = 5

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
     * other side, so a wider box buys nothing but width. Closed by eye: "padding ok now."
     */
    const val TOKEN_PAD = 2

    const val DEFAULT_ARROW_TURN = 0.5f

    /**
     * The box round the invoking token: an inflated rounded rectangle, drawn per paint from the highlighter's live
     * offsets — **three-sided in the joining modes, four in [NestLeader.ARROW]** (constraint 16).
     *
     * Nothing is cached, and the reason is measured: `offsetToXY` is what knows where a folded, scrolled,
     * re-fonted document draws, and all three of those move the box without changing anything this class could
     * listen to.
     */
    class TokenBoxRenderer(private val color: Color, private val closed: Boolean) : CustomHighlighterRenderer {

        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            val start = highlighter.startOffset
            val end = highlighter.endOffset
            if (end <= start || end > editor.document.textLength) return
            val a = editor.offsetToXY(start)
            val b = editor.offsetToXY(end)
            // Different rows means the token is split by a fold or a soft wrap. A box spanning both would enclose
            // whatever lies between them, which is a confident lie about where the call is.
            if (a.y != b.y || b.x <= a.x) return

            val x = a.x - TOKEN_PAD
            val w = (b.x - a.x) + 2 * TOKEN_PAD
            // `lineHeight`, not `lineHeight - 1`: the bottom-edge row IS the strip's row 0, which is what makes
            // the walls adjacent to the leader's origin and ARROW's extension collinear
            // ([BOX_BOTTOM_IS_STRIP_ROW_0]). That row belongs to the nested frame rather than to a glyph row, so
            // the vertical clearance constraint 18 measured is unaffected — the box grew away from the text.
            val h = editor.lineHeight.coerceAtLeast(1)

            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.stroke = BOX
                if (closed) {
                    g2.drawRoundRect(x, a.y, w, h, ARC, ARC)
                } else {
                    // Left, top, right — and the side walls run **down to** the bottom-edge row the leader starts
                    // on, so the two meet by construction rather than by agreement.
                    val p = GeneralPath()
                    val fx = x.toFloat()
                    val fy = a.y.toFloat()
                    val fw = w.toFloat()
                    val fh = h.toFloat()
                    val arc = minOf(ARC.toFloat(), fw / 2f, fh / 2f)
                    p.moveTo(fx, fy + fh)
                    p.lineTo(fx, fy + arc)
                    p.quadTo(fx, fy, fx + arc, fy)
                    p.lineTo(fx + fw - arc, fy)
                    p.quadTo(fx + fw, fy, fx + fw, fy + arc)
                    p.lineTo(fx + fw, fy + fh)
                    g2.draw(p)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * Where the invoking token sits on a call line: the token's text if it is there, else the line's trimmed
     * extent.
     *
     * Not a painting detail — it is the project's **locator rule** (§4.4 rule 2: text search only, for Rider
     * parity), and it lives beside the geometry because the box it produces is the leader's origin. Two copies of
     * a locator drift, and a surface that disagrees about which characters the call is stops drawing the same
     * picture.
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
    private const val ARROW_HEAD_H = 6f
    private const val ARROW_HEAD_W = 7f

    /** How far short of the frame's top border the arrowhead stops. "Points at, without touching." */
    private const val ARROW_TIP_GAP = 3f

    private val BOX = BasicStroke(1.2f)
}
