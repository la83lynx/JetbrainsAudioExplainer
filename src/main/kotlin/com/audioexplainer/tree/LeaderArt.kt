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
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.GeneralPath

/**
 * **The one outline: the token box, the leader and the child's frame, defined in one place.**
 *
 * Constraint 16, and it is the reason this file is not three files. In the two joining modes the leader does not
 * *land on* a border — it **is** the border for the length of its run: the box gives up its bottom edge, the frame
 * gives up its top edge, and the three drawings form a single continuous path from the frame's bottom-left, up
 * and around the token, and back down to the frame's bottom-right. Measured as exactly that in
 * `spikes/tree1a5/OutlineProbe.java`: **one 8-connected component**, in all three modes since the G1c′ round —
 * `ARROW` was two while its head stopped short of the frame, and the round judged that gap a misdrawn arrow rather
 * than a distinction. What still makes `ARROW` a reference and not a join is that **both outlines stay closed**: the
 * box keeps its bottom edge and the frame its top, so the arrow lands *on* a border instead of *becoming* one.
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
     * **The identity the whole outline is built on.**
     * `boxBottomRow == callRowY + lineHeight + padBelow == the strip's row 0`.
     *
     * Stated as a constant so it can be referenced from a comment rather than re-derived: the block inlay holding
     * the nested frame is placed at the call line's end offset with `showAbove = false`, so the frame's first row
     * is the row immediately after the call row — which is where [TokenBoxRenderer] puts the box's bottom edge and
     * where every [leaderPath] starts. Not `lineHeight - 1`: that would put the box's bottom edge one row above the
     * leader's origin, adjacent enough to be invisible on the vertical walls of `CORNERS`/`BRACE` and visible as a
     * 1 px step in `ARROW`'s horizontal extension.
     *
     * **The `padBelow` term is constraint 22's, and it is inside the identity rather than beside it.** The round asked for
     * bottom padding on the box — *"the border overlaps letter bottoms and commas"* — which is height the call row
     * does not have: a row is `lineHeight` tall and its glyphs' descenders reach the bottom of it, so an edge drawn
     * on the last rows of the row is drawn on the text. The room comes from the frame's **top inset**
     * (`NestedFrame.applyBorder`), so the strip's row 0 moves down by exactly the same number the box's bottom edge
     * does and the two stay the same row *by construction*. Adding the pad to the box alone would have detached the
     * leader's origin from the box's bottom corner — 1a‴'s two-notions-of-where-the-call-is defect, re-entered
     * through the one axis constraint 16 had left alone.
     */
    const val BOX_BOTTOM_IS_STRIP_ROW_0 = true

    /**
     * **The box's left and right edge x for a token spanning `[tokenX1, tokenX2)`** — the leader's origin corners,
     * and the second half of the shared-origin rule.
     *
     * The G1c′ round found the first half held and this one did not: *"diagonals top don't meet invoker box ends —
     * off by 2px because of invoker box padding"*. The box is drawn inflated by [TOKEN_PAD] on each side, so a
     * leader started at the *glyph's* x leaves the outline 2 px inside its own wall — a T-junction, at exactly the
     * scale the eye reads as a mistake rather than as a style. The pre-flight probe had it right
     * (`spikes/tree1a5/` pads its endpoints); the Kotlin transcription lost it, which is the same class of defect
     * [BOX_BOTTOM_IS_STRIP_ROW_0] exists to prevent one axis over.
     *
     * So the padding is applied here and nowhere else: [TokenBoxRenderer] draws the box from this, and
     * `CodeNestView.leaderEndpoints` starts the leader from it.
     */
    fun boxEdges(tokenX1: Int, tokenX2: Int): Pair<Int, Int> = (tokenX1 - TOKEN_PAD) to (tokenX2 + TOKEN_PAD)

    /**
     * **The whole token box as one rectangle**, in the host editor's content coordinates — or null when the token does
     * not draw as a single box (split by a fold or a soft wrap, or zero-width).
     *
     * One derivation, because the G1c″ round added a *clickable* thing to the box: the collapse/expand affordance
     * ([chevronRect]) is positioned from this rectangle and hit-tested from this rectangle, and the renderer paints
     * from it. The alternative — a renderer that derives the box and a mouse listener that derives it again — is
     * exactly the shape of 1a‴'s two-notions-of-where-the-call-is defect, one axis over and with the failure mode
     * "the chevron is drawn here and clicks there".
     *
     * The height is `lineHeight`, not `lineHeight - 1`, per [BOX_BOTTOM_IS_STRIP_ROW_0] — plus [padBelow], the
     * G1c⁵ bottom padding, which the same identity says the frame's top inset gives back.
     */
    fun boxRect(editor: Editor, startOffset: Int, endOffset: Int, padBelow: Int = 0): Rectangle? {
        if (endOffset <= startOffset || endOffset > editor.document.textLength) return null
        val a = editor.offsetToXY(startOffset)
        val b = editor.offsetToXY(endOffset)
        // Different rows means the token is split by a fold or a soft wrap. A box spanning both would enclose
        // whatever lies between them, which is a confident lie about where the call is.
        if (a.y != b.y || b.x <= a.x) return null
        val (left, right) = boxEdges(a.x, b.x)
        return Rectangle(left, a.y, right - left, (editor.lineHeight + padBelow).coerceAtLeast(1))
    }

    /**
     * **Where the nesting's collapse/expand chevron lives**, given the token box — or null when the affordance is off.
     *
     * The G1c″ round replaced the collapsed stub row with nothing at all (*"we should just hide it totally in collapsed
     * view — leaving only the invoker box"*), which removed the only thing carrying the expand gesture: the stub was
     * clickable and the strip that accepted the click went with it. So the affordance moved onto the box.
     *
     * **Always outside the box, past a gap — G1c‴ settled that.** The round built the alternative too, a thickened
     * right wall with the glyph inside it, and rejected it in one line: *"floating chevron is best — doesn't hinder
     * code in box."* Which is the box's whole purpose losing an argument it should never have been in — an affordance
     * inside the box spends the invoking token's own glyphs on itself. The edge-button shape is deleted rather than
     * kept as a knob, because a rejected option that still compiles reads as a supported one.
     *
     * **And it is placed in both states, not only while collapsed** (G1c‴ finding 7: *"it feels like reverse
     * 'collapsing' chevron is missing in the same place in expanded mode"*). Same rectangle either way — what changes
     * is which way the glyph points — so a reader's hand learns one target for both directions.
     *
     * **[ChevronStyle.BOTTOM_BORDER] is the one mode whose rectangle depends on the state**, and returns null while
     * expanded: its target is the box's own bottom edge, which constraint 16 removes the moment the nesting opens,
     * because that edge becomes the leader. Its collapse gesture lives on the leader strip instead — see the enum.
     */
    fun chevronRect(box: Rectangle, chevron: ChevronStyle, collapsed: Boolean): Rectangle? = when (chevron) {
        ChevronStyle.NONE -> null
        ChevronStyle.HORIZONTAL, ChevronStyle.VERTICAL ->
            Rectangle(box.x + box.width + CHEVRON_GAP, box.y, chevronWidth(chevron), box.height)
        // A band on the box's bottom edge, drawn and hit-tested *inside* the box so it never reaches the row below.
        ChevronStyle.BOTTOM_BORDER ->
            if (!collapsed) null
            else Rectangle(box.x, box.y + box.height - BOX_BOTTOM_THICK, box.width, BOX_BOTTOM_THICK)
    }

    /**
     * The affordance's own width, by mode — what a caller has to leave clear to the right of the box.
     *
     * Zero for [ChevronStyle.BOTTOM_BORDER] because that mode costs no horizontal room at all: it is drawn *on* the box's
     * outline rather than beside it, which is the whole of its appeal and the whole of its asymmetry.
     */
    fun chevronWidth(chevron: ChevronStyle): Int = when (chevron) {
        ChevronStyle.NONE, ChevronStyle.BOTTOM_BORDER -> 0
        // Both chevrons are the double stroke since G1c⁗ — the horizontal pair steps sideways and so needs the extra
        // width; the vertical pair steps down inside the row it already has.
        ChevronStyle.HORIZONTAL -> CHEVRON_W + CHEVRON_DOUBLE_STEP
        ChevronStyle.VERTICAL -> CHEVRON_W
    }

    /**
     * **[ChevronStyle.BOTTOM_BORDER]'s missing half** — the hint that an *expanded* nesting can be folded back up.
     *
     * That mode draws its affordance *on* the box's bottom edge, and constraint 16 gives that edge to the leader
     * for as long as the nesting is open: [chevronRect] returns null there because there is genuinely no edge to
     * thicken. What that left is the asymmetry the Phase 3/4 round was asked to look at — an expanded nesting has
     * no affordance on the box at all, and the only thing that still collapses it is the leader strip, which is
     * clickable but says nothing about being so.
     *
     * So the hint goes where the reader is already being pointed: **centred under the invoking token, in the strip
     * between the call row and the child's header**, which is the band [CodeNestView.LeaderStrip] already owns and
     * already toggles on. It is a hint and not a second target — the whole strip stays the click, so this adds no
     * hit-testing and cannot disagree with any.
     *
     * **Pointing up**, because direction is how this project encodes the two states (G1c‴'s `»` / `«`, and the
     * notch [drawCollapseHint]'s collapsed counterpart cuts *downward* out of the thickened edge): folded shows a
     * downward notch meaning *unfold*, open shows an upward triangle meaning *fold back into the token*.
     *
     * Null when the token box is too narrow to centre it in, or the strip too short to clear the leader. Both of
     * those are the reader's own style choices ([NestStyle.leader] sets the strip height), and a cramped hint
     * reads as a rendering fault rather than as a control.
     */
    fun collapseHintRect(x1: Int, x2: Int, stripBottom: Int): Rectangle? {
        val w = minOf(BOTTOM_TRIANGLE_W, x2 - x1 - 2)
        if (w < COLLAPSE_HINT_MIN_W || stripBottom < COLLAPSE_HINT_H + 2) return null
        return Rectangle((x1 + x2) / 2 - w / 2, (stripBottom - COLLAPSE_HINT_H) / 2, w, COLLAPSE_HINT_H)
    }

    /**
     * Fill [r] as an upward triangle in the **unmuted** accent — see [collapseHintRect] for where and why.
     *
     * Unmuted deliberately. [leaderBase] mutes the accent toward the border because the leader, the box and the
     * frame are one outline (constraint 18) and an outline should recede; this is not part of that outline. It is
     * the one thing in the strip asking to be clicked, and constraint 4's accent is what this plugin uses to say
     * so. It follows the reader's accent setting rather than being a literal yellow — a control that ignored the
     * one colour they set would be the odd one out.
     */
    fun drawCollapseHint(g2: Graphics2D, r: Rectangle) = drawCollapseHint(g2, r, SpotlightRenderer.accentColor())

    /** [drawCollapseHint] in a given accent — see [leaderColor]'s overload. */
    fun drawCollapseHint(g2: Graphics2D, r: Rectangle, accent: Color) {
        g2.color = accent
        g2.fill(
            GeneralPath().apply {
                moveTo(r.x.toFloat(), (r.y + r.height).toFloat())
                lineTo((r.x + r.width).toFloat(), (r.y + r.height).toFloat())
                lineTo(r.x + r.width / 2f, r.y.toFloat())
                closePath()
            },
        )
    }

    /**
     * **The strip's height, by leader mode** — judged on real code in the G1c′ round, one number per shape.
     *
     * It stopped being one number for the same reason it stopped being cosmetic: the strip's height *is* the
     * vertical distance every leader has to cross, and the three shapes do not spend it the same way. A diagonal
     * and a brace need only enough drop to read as a turn (**14 px**); `ARROW` spends 6 px of it on its head plus an
     * arc going in, so 14 leaves it no shaft at all (**22 px**). The round's words: *"corners and brace — 14px
     * enough, arrow needs 22px."*
     *
     * [NestStyle.stripHeight] overrides it for the bench's sweep; `null` there means this.
     */
    fun defaultStripHeight(leader: NestLeader): Int = when (leader) {
        NestLeader.CORNERS, NestLeader.BRACE -> 14
        NestLeader.ARROW -> 22
    }

    /**
     * The leader's colour: constraint 4's settings accent, muted toward the border, and muted further when the
     * expansion is folded away.
     */
    fun leaderColor(collapsed: Boolean = false): Color = leaderColor(collapsed, SpotlightRenderer.accentColor())

    /**
     * The same rule against a **given** accent — for the settings preview, which has to paint the swatch the
     * reader is holding rather than the one they last saved. Nothing else differs; the no-arg form above is this
     * one asked with the persisted accent, so the two cannot drift.
     */
    fun leaderColor(collapsed: Boolean, accent: Color): Color {
        val base = leaderBase(accent)
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
    fun leaderBase(): Color = leaderBase(SpotlightRenderer.accentColor())

    /** [leaderBase] against a given accent — see [leaderColor]'s overload for why there is one. */
    fun leaderBase(accent: Color): Color = SpotlightRenderer.blendToward(accent, JBColor.border(), LEADER_MIX)

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
     * True when the child's frame draws its top **edge** — the horizontal run between the corners.
     *
     * Only [NestLeader.ARROW]. In `CORNERS`/`BRACE` the leader owns that run, which is what dissolved the
     * rounded-corner defect rather than working around it: there is no top edge here for a corner to belong to.
     *
     * **It stopped answering the corner question when the K3 round moved the corner** — see [roundsFrameTop].
     */
    fun drawsFrameTop(leader: NestLeader): Boolean = leader == NestLeader.ARROW

    /**
     * True when the child's frame rounds its top **corners** although it draws no top edge — [NestLeader.BRACE]
     * only, and the K3 round is why this is a second question rather than a second reading of [drawsFrameTop].
     *
     * G1c⁗ asked for the brace's horizontal run to sit *"exactly on top of the header, not above it"*, with the
     * outer ends reading as *"rounding of top corners of subeditor"*. What shipped kept the run one radius **above**
     * the last row, because that is the room the turn needed to arrive at the frame's wall from inside the strip's
     * own band. So the picture kept a 5 px transparent gap between the drawn top line and the header — invisible
     * until K3 put a relation link across it, which then read as a line crossing the border and stopping short of
     * the header.
     *
     * The room was being taken from the wrong component. A rounded top corner is a corner **of the frame**, so the
     * frame draws it: the leader's run ends flat at `ARC` from either wall on the strip's last row, and the arc that
     * turns down into the wall begins on the child's own row 0 — adjacent rows, which is the same adjacency
     * `seamRows() == 0` already measures for the side walls.
     *
     * Not `CORNERS`: its diagonals **land on** the frame's top corners, so rounding them would open a gap exactly
     * where a diagonal arrives. The two questions differ by mode, which is the whole reason there are two.
     */
    fun roundsFrameTop(leader: NestLeader): Boolean = leader == NestLeader.BRACE

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
    fun leaderPath(leader: NestLeader, x1: Int, x2: Int, w: Int, bottom: Int): GeneralPath {
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

            // Two stepped halves, **each leaving the box's bottom corner going down**: a run along the box's own side
            // wall, a rounded turn outward, the horizontal run, then a second rounded turn down into the frame's side
            // wall. Two turns per half and nothing between them — no central stem and no spine across the middle,
            // which the built version had and the probe's emptiness test uses as its control.
            //
            // The descent is the G1c′ round's correction: *"it stems immediately sideways from the invocation token
            // box, I expected smooth roundings span down from sides of box"*. A run leaving at row 0 exactly is a
            // T-junction at the end of the box's wall however well the ends meet; leaving *along* the wall and then
            // curving out is the wall continuing, which is what one outline means.
            //
            // **The horizontal run is on the last row, and the outer turns are not in this band at all — K3.**
            // G1c⁗ asked for that in as many words: *"let brace horizontal line be exactly on top of the header, not
            // above it, and brace outer edges would then be somewhat like rounding of top corners of subeditor."* The
            // first attempt put `runY` at `b - arc`, because a turn ending on the frame's wall needs a radius of
            // vertical room and the only room the strip has is its own. That left the run 5 px clear of the header —
            // the gap K3 saw a relation link cross, which read as a line through the border stopping short of it.
            //
            // The room was being taken from the wrong component. A rounded top corner is a corner **of the frame**,
            // so the frame draws it ([roundsFrameTop]): the run ends flat at one radius from either wall on this
            // band's last row, and the turn down into the wall starts on the child's own row 0 — the adjacent row,
            // which is the adjacency `seamRows() == 0` already measures for the side walls.
            NestLeader.BRACE -> {
                val runY = b
                // Where the wall's descent ends and the outward turn begins. Never above row 0, and the arc clamp
                // (`arc <= bottom/3`) is what guarantees there is a descent left to make at all.
                val turnY = (runY - arc).coerceAtLeast(0f)
                // Where the **frame's** corner takes over, and therefore [ARC] rather than `arc`: this run has to
                // stop exactly where the other path starts, and the frame clamps its radius against its own size
                // while `arc` is clamped against this band's height. Two clamps of one radius is the seam constraint
                // 16 exists to prevent, so the meeting point is spelled the frame's way.
                val corner = ARC.toFloat()
                if (x1 - arc > corner) {
                    p.moveTo(x1.toFloat(), 0f)
                    p.lineTo(x1.toFloat(), turnY)
                    p.quadTo(x1.toFloat(), runY, x1 - arc, runY)
                    p.lineTo(corner, runY)
                } else {
                    // No room between the box's corner and the frame's for a rounded turn, so the box's wall meets
                    // the run square. It no longer degenerates to the frame's own wall: that wall now starts a
                    // radius below this row, so a line drawn down it would end in a gap instead of in a corner.
                    p.moveTo(x1.toFloat(), 0f)
                    p.lineTo(x1.toFloat(), runY)
                    p.lineTo(corner, runY)
                }
                if (x2 + arc < right - corner) {
                    p.moveTo(x2.toFloat(), 0f)
                    p.lineTo(x2.toFloat(), turnY)
                    p.quadTo(x2.toFloat(), runY, x2 + arc, runY)
                    p.lineTo(right - corner, runY)
                } else {
                    p.moveTo(x2.toFloat(), 0f)
                    p.lineTo(x2.toFloat(), runY)
                    p.lineTo(right - corner, runY)
                }
            }

            // The box's bottom edge continued past its own bottom-right corner — collinear, so it reads as the
            // edge extended and not as a new line leaving a corner — then down, ending in an arrowhead that lands
            // on the frame's top border. Both outlines stay closed, which is what keeps it a reference.
            NestLeader.ARROW -> {
                val turn = arrowTurnX(x2, w)
                // The last drawable row, which is **adjacent to the frame's top border** and therefore reads as
                // touching it. It used to stop `ARROW_TIP_GAP` short — "points at, without touching" — and the G1c′
                // round rejected the result as a defect rather than as a distinction: *"arrow does not reach header
                // top per 2px."* An arrowhead floating short of the line it points at reads as a misdrawn arrow, not
                // as a reference; the reference is carried by the box and the frame both staying closed.
                val tip = b
                val shaftEnd = maxOf(tip - ARROW_HEAD_H, arc)
                p.moveTo(x2.toFloat(), 0f)
                p.lineTo(maxOf(turn - arc, x2.toFloat()), 0f)
                p.quadTo(turn, 0f, turn, arc)
                p.lineTo(turn, shaftEnd)
                // An open chevron rather than a filled triangle, so the head is the same stroke as the rest of
                // the outline and does not read as a second object stuck on the end. Its tip lands on the frame's
                // border; the chevron's own arms are what keep it reading as a head and not as a corner.
                p.moveTo(turn - ARROW_HEAD_W / 2f, tip - ARROW_HEAD_H)
                p.lineTo(turn, tip)
                p.lineTo(turn + ARROW_HEAD_W / 2f, tip - ARROW_HEAD_H)
            }
        }
        return p
    }

    /**
     * Where [NestLeader.ARROW] turns down: [ARROW_TURN] of the span from the box's right edge to the frame's.
     *
     * **It was the only free parameter in any of the three shapes, and it is not free any more.** The built version ran
     * to the frame's far right corner and the round called it unnatural; the specification was a point strictly
     * *between* the two edges, swept at 30 / 50 / 70 % and settled at the midpoint — *"confirmed unchanged"* by G1c′,
     * the one number the pre-flight PNGs did answer. So the fraction is a constant here rather than a parameter
     * threaded through `NestStyle`; the bench's sweep is deleted with it.
     *
     * Clamped at both ends so the arc has room going in and half a head has room on either side coming out.
     */
    fun arrowTurnX(x2: Int, w: Int): Float {
        val right = (w - 1).toFloat()
        val lo = minOf(x2 + ARC + 2f, right)
        val hi = maxOf(lo, right - ARROW_HEAD_W / 2f - 1f)
        return (x2 + (right - x2) * ARROW_TURN).coerceIn(lo, hi)
    }

    /**
     * **The frame's outline — one path, and the one place `drawTop` becomes square-vs-rounded top corners.**
     *
     * Used by the expanded child's border ([CodeNodeView.NodeFrameBorder]) *and* by the collapsed stub, which is the
     * G1c′ round's item 16: *"collapsed view in corners and braces mode — collapsed subeditor should also have no
     * top border."* The stub drew a plain `drawRoundRect` and so kept a top edge the leader already owned, which
     * left a doubled line across the collapsed row in the two joining modes. Two drawings of one shape is how that
     * happens; there is one now.
     *
     * **[drawTop] and [roundTop] are separate on purpose.** They were one boolean until K3, on the reasoning that a
     * frame with no top edge has no top corner; that held only while the leader kept its own turn, and it is exactly
     * the assumption that left a 5 px gap above the header. `drawTop` is the horizontal run, `roundTop` is the pair
     * of turns into the side walls, and [NestLeader.BRACE] wants the second without the first.
     *
     * `x`/`y` are the top-left and `w`/`h` the full size, so `right`/`bottom` are the last drawable column and row —
     * a stroke at `x + w` is clipped exactly as one at `y + h` is (measured in `spikes/tree1a5/`).
     */
    fun framePath(x: Int, y: Int, w: Int, h: Int, drawTop: Boolean, roundTop: Boolean): GeneralPath {
        val arc = minOf(ARC.toFloat(), w / 2f, h / 2f).coerceAtLeast(0f)
        val left = x.toFloat()
        val right = (x + w - 1).toFloat()
        val top = y.toFloat()
        val bottom = (y + h - 1).toFloat()
        // The walls start at `top` only when nothing rounds the top. Both other cases own a corner and therefore
        // start a radius down: `drawTop` because the top run is this path's, `roundTop` because the run is the
        // leader's but the **turn** into the wall is this path's (see [roundsFrameTop]).
        val wallTop = if (drawTop || roundTop) top + arc else top
        val p = GeneralPath()
        if (roundTop && !drawTop) {
            // The corner alone, with no run between the pair of them: the leader's horizontal line ends directly
            // above `left + arc` on the row above this one, and this arc is the turn it used to make inside its
            // own band.
            p.moveTo(left + arc, top)
            p.quadTo(left, top, left, wallTop)
        } else {
            p.moveTo(left, wallTop)
        }
        p.lineTo(left, bottom - arc)
        p.quadTo(left, bottom, left + arc, bottom)
        p.lineTo(right - arc, bottom)
        p.quadTo(right, bottom, right, bottom - arc)
        p.lineTo(right, wallTop)
        if (drawTop) {
            p.quadTo(right, top, right - arc, top)
            p.lineTo(left + arc, top)
            p.quadTo(left, top, left, wallTop)
        } else if (roundTop) {
            p.quadTo(right, top, right - arc, top)
        }
        return p
    }

    val SOLID: BasicStroke = BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

    // `DASHED` lived here and is deleted with its last caller: dashed was the collapsed state's whole vocabulary — the
    // stub row's border and the folded leader — and the G1c″ round removed both by hiding the collapsed frame outright.
    // Kept as a comment rather than as a stroke, because a stroke nothing draws with is indistinguishable from an
    // option someone may still choose (the cleanup pass's rule).

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

    /** `ARROW`'s turn-down x as a fraction of box-right → frame-right. Settled at the midpoint — see [arrowTurnX]. */
    const val ARROW_TURN = 0.5f

    /**
     * The box round the invoking token: an inflated rounded rectangle, drawn per paint from the highlighter's live
     * offsets — **three-sided in the joining modes, four in [NestLeader.ARROW]** (constraint 16).
     *
     * Nothing is cached, and the reason is measured: `offsetToXY` is what knows where a folded, scrolled,
     * re-fonted document draws, and all three of those move the box without changing anything this class could
     * listen to.
     */
    class TokenBoxRenderer(
        private val color: Color,
        private val closed: Boolean,
        /**
         * The nesting's collapse/expand affordance, or [ChevronStyle.NONE] when there is none.
         *
         * It rides the box renderer rather than being a second decoration because it is positioned off the box and has
         * to move with it: the box is re-derived from live offsets on every paint (see below), so anything drawn beside
         * it has to be derived in the same pass or it lags a fold behind.
         */
        private val chevron: ChevronStyle = ChevronStyle.NONE,
        /**
         * Whether the nesting is folded — which decides the glyph's direction (`»` to expand, `«` to collapse, G1c‴
         * finding 7) and, for [ChevronStyle.BOTTOM_BORDER], whether there is an affordance to draw at all.
         *
         * For the chevrons the direction is the *only* difference between the two states, which is what makes the
         * reader's target one target rather than two.
         *
         * **Passed as the state and not as the direction**, though the renderer only ever wanted the direction. G1c⁗ is
         * why: [closed] is `true` while collapsed *and* in [NestLeader.ARROW] always, so it cannot stand in for "folded"
         * — reading the state off it would have given `ARROW` a thickened bottom border on an open nesting. Two notions
         * of one fact, which is 1a‴'s two-notions-of-where-the-call-is defect wearing its third hat.
         */
        private val collapsed: Boolean = true,
        /**
         * G1c⁵'s bottom padding: rows the box owns *below* the call row, so its bottom edge and the
         * [ChevronStyle.BOTTOM_BORDER] band drawn inside it clear the row's descenders.
         *
         * Told, not derived — [BOX_BOTTOM_IS_STRIP_ROW_0] is the whole reason. The same number is the frame's top
         * inset, so a renderer that computed its own would be the second owner of one row.
         */
        private val padBelow: Int = 0,
    ) : CustomHighlighterRenderer {

        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            // [boxRect], not the arithmetic inline: the leader starts from the same call and the chevron is
            // hit-tested from it, which is what stops the three from disagreeing — the padding half of that was
            // the G1c′ round's 2 px.
            val box = boxRect(editor, highlighter.startOffset, highlighter.endOffset, padBelow) ?: return
            val x = box.x
            val w = box.width
            val h = box.height

            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                drawTokenBox(g2, x, box.y, w, h, closed)
                chevronRect(box, chevron, collapsed)?.let {
                    drawChevron(g2, it, chevron, collapsed, editor.colorsScheme.defaultBackground)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * **The token box itself** — a rounded rect when the leader leaves it closed, three sides when the leader
     * takes the fourth (constraint 16). Lifted out of [TokenBoxRenderer] so the settings preview draws the reader's
     * box rather than a second one that looks like it; the renderer is now this function plus a [boxRect] call.
     *
     * The closed branch is still `drawRoundRect` and not the path: the two produce subtly different corners at
     * [ARC], and the shipped picture is the one `drawRoundRect` makes.
     */
    fun drawTokenBox(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int, closed: Boolean) {
        g2.stroke = BOX
        if (closed) {
            g2.drawRoundRect(x, y, w, h, ARC, ARC)
            return
        }
        // Left, top, right — and the side walls run **down to** the bottom-edge row the leader starts
        // on, so the two meet by construction rather than by agreement.
        val p = GeneralPath()
        val fx = x.toFloat()
        val fy = y.toFloat()
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

    /**
     * The affordance, in whichever of the three shapes the mode names.
     *
     * **Weight is no longer a variable here** — G1c⁗ kept the double stroke and scrapped the single and the circled
     * ring, so every chevron this draws is two glyphs. What varies is the *axis*, and it varies in one place: `dx`
     * and `dy` are the step between the pair and the opening's direction, so the horizontal and vertical shapes are
     * the same four lines of path with two numbers swapped rather than two shapes to keep in agreement.
     *
     * Both pairs are **centred as a group**, so switching axis or state does not move the target the reader is
     * aiming at.
     *
     * Hoisted out of [TokenBoxRenderer] with [collapsed] as a parameter rather than as enclosing state, so the
     * settings preview draws the reader's affordance instead of a lookalike. The renderer passes its own field.
     */
    fun drawChevron(
        g2: Graphics2D,
        r: Rectangle,
        chevron: ChevronStyle,
        collapsed: Boolean,
        background: Color,
    ) {
        if (chevron == ChevronStyle.BOTTOM_BORDER) {
            drawBottomBorder(g2, r, background)
            return
        }
        g2.stroke = CHEVRON
        // Sized off the row height rather than fixed, because the row is `lineHeight` and follows the font
        // (constraint 9's shape as a font).
        val half = (r.height * CHEVRON_SCALE / 2f).coerceAtMost(r.width / 2.5f)
        val vertical = chevron == ChevronStyle.VERTICAL
        // Folded points *away* — right for the horizontal pair, down for the vertical one — and both reverse once
        // the nesting is open, so the glyph always names the action rather than the state.
        val dir = if (collapsed) 1f else -1f
        val cx0 = r.x + r.width / 2f
        val cy0 = r.y + r.height / 2f
        for (i in 0 until 2) {
            val step = (i - 0.5f) * CHEVRON_DOUBLE_STEP
            val cx = if (vertical) cx0 else cx0 + step
            val cy = if (vertical) cy0 + step * dir else cy0
            g2.draw(GeneralPath().apply {
                if (vertical) {
                    moveTo(cx - half, cy - dir * half / 2f)
                    lineTo(cx, cy + dir * half / 2f)
                    lineTo(cx + half, cy - dir * half / 2f)
                } else {
                    moveTo(cx - dir * half / 2f, cy - half)
                    lineTo(cx + dir * half / 2f, cy)
                    lineTo(cx - dir * half / 2f, cy + half)
                }
            })
        }
    }

    /**
     * [ChevronStyle.BOTTOM_BORDER]: the box's bottom edge thickened into a clickable band, with a small filled
     * triangle pointing down out of it.
     *
     * Drawn as a thick line **inside** the box's own outline rather than centred on it, so the band cannot spill into
     * the row of code below — the box is an inflated token rect, and the line under it belongs to the next statement.
     *
     * **The triangle is knocked out of the band in the editor's own background colour, not drawn on top of it.** Drawn
     * in the accent colour it would be invisible — same paint on same paint — and drawn as a stroked outline it would
     * be a chevron inside a box, which is the rejected edge-button all over again. A notch cut through a 5 px band is
     * the only mark that stays legible at *"tiny"*, and it makes the band read as a thing that opens downward.
     */
    private fun drawBottomBorder(g2: Graphics2D, r: Rectangle, background: Color) {
        val cy = r.y + r.height / 2f
        g2.stroke = BOX_BOTTOM
        g2.draw(
            GeneralPath().apply {
                moveTo(r.x.toFloat(), cy)
                lineTo((r.x + r.width).toFloat(), cy)
            },
        )
        val w = BOTTOM_TRIANGLE_W.toFloat()
        val h = BOX_BOTTOM_THICK.toFloat()
        val cx = r.x + r.width / 2f
        val top = cy - h / 2f
        g2.color = background
        g2.fill(
            GeneralPath().apply {
                moveTo(cx - w / 2f, top)
                lineTo(cx + w / 2f, top)
                lineTo(cx, top + h)
                closePath()
            },
        )
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
    fun locateCallToken(
        document: Document,
        line0: Int,
        token: String?,
        occurrence: Int = 1,
    ): IntRange? {
        if (line0 < 0 || line0 >= document.lineCount) return null
        val start = document.getLineStartOffset(line0)
        val end = document.getLineEndOffset(line0)
        if (end <= start) return null
        val text = document.charsSequence.subSequence(start, end).toString()
        if (token != null) {
            // [occurrence] is 1-based and the schema carries it, so a call line with the same token twice
            // (`Handle(Handle(x))`) can name which one is the call. Past the last match this falls through to
            // the trimmed line rather than drawing nothing: the frame is still nested there, and a leader on
            // the whole line is a worse picture than no picture only in the sense that it is less precise.
            var idx = -1
            var found = 0
            var from = 0
            while (from <= text.length) {
                idx = text.indexOf(token, from)
                if (idx < 0) break
                if (++found >= occurrence.coerceAtLeast(1)) break
                from = idx + 1
            }
            if (idx >= 0) return (start + idx) until (start + idx + token.length)
        }
        val lead = text.indexOfFirst { !it.isWhitespace() }
        if (lead < 0) return null
        val trail = text.indexOfLast { !it.isWhitespace() }
        return (start + lead)..(start + trail)
    }

    /**
     * One chevron glyph's box: wide enough for a `>` that reads at editor font size.
     *
     * A number to judge, not a measured one, and **G1c‴ judged it light** — which G1c⁗ answered by making the pair the
     * only shape (*"out of all chevrons the 'double' is best"*) rather than by growing this, because a wider thin glyph
     * is not a heavier one.
     */
    const val CHEVRON_W = 11

    /** Clearance between the box's right wall and the chevron. */
    const val CHEVRON_GAP = 3

    /** Step between the two glyphs of a pair — sideways in [ChevronStyle.HORIZONTAL], downward in the vertical one. */
    private const val CHEVRON_DOUBLE_STEP = 5

    /** The chevron's height as a fraction of the row it sits in. */
    private const val CHEVRON_SCALE = 0.46f

    /**
     * [ChevronStyle.BOTTOM_BORDER]'s band: the reader's *"thicker (~5px) clickable bottom border"*, and also the
     * knocked-out triangle's height, so the notch cuts the band cleanly through instead of leaving a hairline.
     */
    const val BOX_BOTTOM_THICK = 5

    /** The knocked-out triangle's width. *"Tiny"*, and wider than tall so it reads as pointing down and not as a gap. */
    private const val BOTTOM_TRIANGLE_W = 9

    /**
     * The expanded state's collapse hint — [collapseHintRect]. Its height, and the narrowest token box worth
     * centring one in; the width itself is [BOTTOM_TRIANGLE_W], so the two states are the same size mark.
     */
    const val COLLAPSE_HINT_H = 5
    private const val COLLAPSE_HINT_MIN_W = 5

    private const val LEADER_MIX = 0.80
    private const val COLLAPSED_MIX = 0.55
    private const val ARROW_HEAD_H = 6f
    private const val ARROW_HEAD_W = 7f

    private val BOX = BasicStroke(1.2f)

    // A `BOX_THICK` stroke lived here for the edge-button shape's thickened *right wall*. G1c‴ rejected that shape
    // (*"floating chevron is best"*) and it stayed deleted; `BOX_BOTTOM` below is not its return, because the objection
    // was to spending the token's own glyphs on a control and the bottom edge spends none.

    /** Butt caps and mitred joins so the band ends square on the box's corners rather than bulging past them. */
    private val BOX_BOTTOM = BasicStroke(BOX_BOTTOM_THICK.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)

    private val CHEVRON = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
}
