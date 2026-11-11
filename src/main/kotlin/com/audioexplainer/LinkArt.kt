package com.audioexplainer

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * **What a link is anchored to** — the first of the link system's two dimensions (the other is [LinkJoin]).
 *
 * Links between tokens leave from the token and run above or below the text line; links between lines leave
 * from the line's end. The two forms differ only in their first few pixels: from the rail onward they are
 * one drawing.
 *
 * Derived **per end**, not per link: a relation may name a token at one end and only a line at the other.
 * A token that does not resolve (a wrong anchor, an edited line) degrades that end to [LINE_END] instead of
 * dropping the link — the renderer's validate-and-drop rule.
 */
enum class LinkAnchor {
    /**
     * The end sits on an identifier. The identifier is tinted, and the connector leaves the tint's own
     * **corner** — top when the other end is below, bottom when it is above — already on the rail.
     */
    TOKEN,

    /**
     * The end sits at the end of the row's text, where the in-file arrow always started.
     *
     * **It runs out along the row's own middle** for the whole horizontal span; what happens at the far end
     * of that run depends on the join:
     *
     * - [LinkJoin.LINE] — **no turn onto a rail at all.** The run stays at the row's middle, turns once down
     *   its own lane, and turns back into the partner's row middle: a staple, with no U-turn.
     * - [LinkJoin.PORTAL] — the `∩`/`∪` **happens just before the portal**. [LinkArt.uRun] px short of its
     *   lane the run hops onto the same rail a [TOKEN] end is already on, carries the direction indicator
     *   along it, and turns into the portal — so the finish into a portal looks the same for every portal link.
     *
     * Hopping onto the rail at the text end instead would put the horizontal in the neighbouring row's
     * leading for the whole width of the node.
     */
    LINE_END,
}

/**
 * **How the two ends are joined** — the second dimension.
 *
 * Links in the same editor connect with a line; links between editors connect with a portal. One exception:
 * two ends in **one** editor but further apart than [SpotlightRenderer]'s arrow span also get [PORTAL],
 * because a leg spanning 200 rows has its far end off screen. So the name says what is *drawn*.
 */
enum class LinkJoin {
    /** One polyline: rail, channel, rail. Both ends are in the same editor and near enough to see at once. */
    LINE,

    /** A portal at each end, in the same colour, on the same shared column. The ends never meet on screen. */
    PORTAL,
}

/**
 * How a link's connector gets from its anchor to the channel.
 *
 * `probes/LinkBench.java` counts the pixels each route puts on top of a glyph: a straight line puts 234, a
 * dashed one 160, and the elbow through the row's leading two orders of magnitude fewer.
 *
 * [STRAIGHT] exists only as the bench's **control**: a measurement that the shipped route crosses almost
 * nothing means little unless something in the same harness crosses a lot.
 */
enum class LinkRoute {
    /**
     * **The shipped route, and its side is derived rather than chosen.** The connector reaches the rail —
     * the row's top leading when the other end is below, its bottom leading when it is above — runs level
     * along it to the channel, then turns and travels toward the other end.
     *
     * So the final leg's direction *is* the answer to "which way is the other end", and a [LinkJoin.PORTAL]
     * sits at the far end of that travel: the bottom of the row for a target below, the top for one above.
     * See [LinkArt.shape].
     */
    DIRECTIONAL,

    /** Straight across the row's middle, crossing whatever code follows the token. The control only. */
    STRAIGHT,
}

/**
 * **The three numbers a link's ink is drawn with**, carried on the shape rather than read from a service.
 *
 * **Passed, never looked up.** [LinkArt] holds no reference to the settings service and must not: the
 * `probes/LinkBench.java` bench imports this file and runs headless, with no application to ask. So the
 * shipped numbers stay here as [LinkArt.defaultMetrics] and `SpotlightRenderer` — which does have settings —
 * hands the configured set in per render. A bench that passes nothing therefore measures exactly what ships.
 *
 * The defaults agree with the settings' own: [LinkArt.HEAD] and `DEF_ARROW_HEAD` are both 7, the corner is 4
 * on both sides, and `DEF_ARROW_THICKNESS` is 10 tenths to match the 1 px resting stroke.
 */
class LinkMetrics(
    /** Arrowhead length, px. Also the direction indicator's size, and the clearance [LinkShape.fromX] leaves. */
    val head: Int,
    /** Radius of every turn in the connector, and of a hovered label's backdrop. 0 squares them off. */
    val corner: Int,
    /** Stroke weight at rest, px. */
    val stroke: Float,
) {
    /**
     * The hovered weight: **one px heavier than at rest**, never a multiple of it.
     *
     * That difference is what the rail spacing is built on — [LinkArt.railStep] leaves a clear pixel between
     * two hovered strokes at the default row — so scaling it would hold at the default weight and quietly
     * close that gap at every other one.
     */
    val hoverStroke: Float get() = stroke + 1f
}

/**
 * One end of one link, resolved to pixels in one editor.
 *
 * @param anchor What this end hangs off — see [LinkAnchor]. Decides where its run sits and how it leaves.
 * @param join How the two ends meet — see [LinkJoin]. Decides whether there is a portal here.
 * @param lane **The link's routing lane within its step**, 0-based. It picks the palette entry and, through
 *   [LinkArt.laneAxes], the exact x this end's vertical is drawn on. Allocated once across every editor on
 *   screen, so the two ends of one link share it.
 * @param fan **Which rail this end takes off its own row**, 0-based *within that row and that side*. Two
 *   ends contend for a rail only when they leave the **same row in the same direction**, so this is counted
 *   per row rather than per step — five links leaving one line use fans 0-4, and the five rows they land on
 *   use fan 0 each. That is what keeps [LinkArt.railSpace] proportional to what a row actually carries
 *   instead of to the step's worst case.
 * @param startX Where the connector leaves the anchor: the token's own edge, or the row's text end.
 * @param textEndX Right edge of the row's own text. Only [LinkRoute.STRAIGHT] can cross it.
 * @param rowMid Vertical centre of the row.
 * @param lineHeight The row's full height.
 * @param axisX **The x this end's vertical is drawn on** — a lane's centre, not a band's left edge, because
 *   the axis is what two links must not share: a portal is 23 px wide and a same-editor vertical is 1, so
 *   lanes are sized by what they carry. See [LinkArt.laneAxes].
 * @param outer The portal's outline, centred on [axisX]. Computed for every end; **painted only** when
 *   [hasPortal].
 * @param inner The portal's **fully transparent** core, which the connector's leg crosses end to end.
 * @param railY The level run's y — the row's leading offset by [fan], or the row's middle for the one form
 *   that never leaves it (a same-editor line-end link).
 * @param legEndY Where a portal end's leg stops — the far edge of [inner], so the line reads as going *into*
 *   the hole rather than stopping on its rim. Equal to [railY] for a [LinkJoin.LINE] end, whose leg length is
 *   not its own to decide: it runs to the partner's rail, which only [LinkArt.paintPair] knows.
 * @param incoming True on the end the link *arrives* at. Turns the two arrowheads round; nothing else.
 * @param targetBelow True when the other end of this link is further down the screen. The one input the
 *   geometry cannot work out for itself — see `SpotlightRenderer.targetBelowFor`.
 */
class LinkShape(
    val anchor: LinkAnchor,
    val join: LinkJoin,
    val lane: Int,
    val fan: Int,
    val startX: Int,
    val textEndX: Int,
    val rowMid: Int,
    val lineHeight: Int,
    val axisX: Int,
    val outer: Rectangle,
    val inner: Rectangle,
    val railY: Int,
    val legEndY: Int,
    val incoming: Boolean,
    val targetBelow: Boolean,
    /**
     * **The link arrives at both ends** — `Relation.KIND_BACKFORTH`, the one thing `kind` draws.
     *
     * Kept beside [incoming] rather than folded into it, because [incoming] still means what it always meant:
     * which end of the *authored* relation this is. That answers the hover's wording and the callout's glyph,
     * and those stay directional even when the picture does not. What this changes is [arrives]; drawing
     * reads only that.
     */
    val mutual: Boolean = false,
    /**
     * The ink's own numbers — see [LinkMetrics]. Defaulted so a bench that only cares about geometry keeps
     * measuring the shipped drawing, and so this stays the last parameter of a long list.
     */
    val metrics: LinkMetrics = LinkArt.defaultMetrics(),
) {

    /**
     * **Does the link arrive here** — the question the ink actually asks, and the only one it asks.
     *
     * A one-way link arrives at one end; a mutual one arrives at both. Three things read it: the arrowhead on
     * the anchor, the direction the rail indicator points, and [fromX], which starts the line past a head that
     * is now there. Deriving all three from one property is what stops a mutual link growing a second head at
     * one end and keeping a tail at the other.
     */
    val arrives: Boolean get() = incoming || mutual
    /**
     * **The axis, as a half-integer** — where the run turns, and the line both ovals are centred on.
     *
     * Deliberately not an integer, so the portal is horizontally symmetric. Its width is always odd (see
     * [LinkArt.outerWidth]), so its centre falls **between** two pixels, and a 1 px stroke placed there covers
     * exactly the middle column of the odd number the oval occupies. [outer] is centred on this.
     */
    val centreXf: Float get() = axisX + 0.5f

    /** The axis floored, for hit-testing only, where half a pixel is well inside [LinkArt.hit]'s slop. */
    val centreX: Int get() = axisX

    /**
     * **Where the connector's ink actually begins**: [startX] for a [LinkAnchor.TOKEN] end, three pixels
     * past it for a [LinkAnchor.LINE_END] one.
     *
     * The asymmetry is the point. A token end starts at the corner of a **tinted token** it is meant to look
     * attached to, so any gap reads as the line coming loose. A line end starts beside the row's **last
     * glyph**, and the stub keeps the ink off it.
     */
    val inkX: Int get() = if (anchor == LinkAnchor.LINE_END) startX + LinkArt.lineEndStub() else startX

    /** [inkX], past the arrowhead on an end the link arrives at — where the *line* starts rather than the ink. */
    val fromX: Int get() = if (arrives) inkX + metrics.head else inkX

    /** The row's own top bound, which is where the tint on a token anchored here stops. */
    val rowTop: Int get() = rowMid - lineHeight / 2

    /** The row's own bottom bound. */
    val rowBottom: Int get() = rowTop + lineHeight

    /**
     * **Has this end's rail left its own row?** — and with it, the token it is supposed to look attached to.
     *
     * Fan 0 sits [LinkArt.railInset] px *inside* the row, on the tint's own corner, which is what makes one
     * link on a token read as growing out of it. Every rail past it is [LinkArt.railStep] further out and
     * already clear of the row — at a 20 px row the four outer rails leave their token by 2, 5, 8 and 11 px —
     * so every fanned token end needs a stem back to its token.
     *
     * Only a [LinkAnchor.TOKEN] end can be detached, because it is the only one with a tint to be detached
     * *from*. A line end leaves its row's own middle beside the last glyph, and [LinkArt.centreOffset] moves
     * it by 2 px at most.
     */
    val hasStem: Boolean get() = anchor == LinkAnchor.TOKEN && (railY < rowTop || railY >= rowBottom)

    /**
     * **Where the stem lands: the fan-0 rail on the side the run left by** — the tint's own corner, and
     * exactly the pixel a link with this row to itself would have started from.
     *
     * Defining it as *"where fan 0 would be"* rather than as *"the row's bound"* is what makes the tick read
     * as the missing part of the drawing instead of as a mark near it: five links leaving one token all reach
     * the same point, and that point is where the reader already knows a link attaches.
     */
    val stemY: Int get() =
        if (railY < rowTop) rowTop + LinkArt.railInset() else rowBottom - LinkArt.railInset()

    /**
     * **The y of the long run out of the anchor** — the row's middle for a line end, the rail for a token.
     *
     * A token end is already on its rail when it leaves the tint, so its run and its rail are one line. A line end leaves the row's *middle* and stays there for the whole span; when it
     * needs a rail at all it hops onto one in the last [LinkArt.uRun] px ([hasHop]).
     */
    val trunkY: Int get() =
        if (anchor == LinkAnchor.LINE_END && join == LinkJoin.PORTAL) rowMid else railY

    /** True when the run has to climb onto a rail before it turns — a line end joined by a portal. */
    val hasHop: Boolean get() = trunkY != railY

    /**
     * Where the hop happens: [LinkArt.uRun] px short of the axis, or at the anchor when the run is shorter
     * than that. Never left of [fromX], so the hop cannot be drawn through the arrowhead.
     */
    val hopX: Float get() =
        if (!hasHop) fromX.toFloat() else max(fromX.toFloat(), centreXf - LinkArt.uRun())

    /** The y the anchor itself sits at, which is where an incoming arrowhead points. */
    val anchorY: Int get() = trunkY

    /**
     * **Where the level rail run really begins** — at the hop for a line end, past the arrowhead otherwise.
     *
     * The distinction is what decides whether a direction indicator fits: a hopped end always has
     * [LinkArt.uRun] px of rail whatever the row's length, which is why the hop sits at the end of the run.
     * A token end on the longest row of a step has only what the channel gap gives it, and [hasIndicator] is
     * where that is admitted.
     */
    val railStartX: Float get() = if (hasHop) hopX else fromX.toFloat()

    /** How much level rail there is to place a direction indicator on. */
    val railRun: Float get() = centreXf - railStartX

    /**
     * **Is there room for a direction indicator at all?** — the head itself, plus its own width of clearance
     * at each end so it reads as an arrow on a line rather than as a blob at a corner.
     */
    val hasIndicator: Boolean get() = railRun >= 2f * metrics.head + metrics.head / 2f

    /**
     * **Centre of the direction indicator**: its leading edge sits [LinkArt.indicatorGap] short of the turn,
     * or as much of that as the rail can spare.
     *
     * Measured to the head's *edge* rather than to its centre, so the head cannot creep into the corner arc as
     * it grows. An arrowhead touching the bend reads as a kink in the line rather than as a direction.
     *
     * **The gap shrinks before the head is dropped**, so a short rail (an end on the step's longest row) still
     * gets an indicator where a fixed 8 px gap plus two heads' clearance would not fit.
     */
    val indicatorX: Float get() {
        val h = metrics.head.toFloat()
        val gap = min(LinkArt.indicatorGap().toFloat(), railRun - 2f * h).coerceAtLeast(h / 2f)
        return centreXf - h / 2f - gap
    }

    /** Is a portal drawn at this end? False for a [LinkJoin.LINE] end, which has a partner to reach instead. */
    val hasPortal: Boolean get() = join == LinkJoin.PORTAL

    /** Which way this end's leg travels out of the rail: down toward a target below, up toward one above. */
    val legSign: Float get() = if (targetBelow) 1f else -1f

    /**
     * **The whole connector as an axis-aligned polyline**, from the anchor to [legTo] — one derivation that
     * [LinkArt.paint] strokes and [LinkArt.hit] tests.
     *
     * One derivation for both ink and hit-testing, so a segment the mouse can find is a segment the editor
     * drew, by construction — a hit band derived separately from the ink drifts from it whenever the route
     * changes.
     *
     * Alternating x, y. Axis-aligned throughout, which is what lets [LinkArt.hit] test a rectangle per
     * segment and [LinkArt.connector] round every corner with one rule.
     */
    fun points(legTo: Int): FloatArray {
        val out = ArrayList<Float>(10)
        fun at(x: Float, y: Float) {
            val n = out.size
            if (n >= 2 && out[n - 2] == x && out[n - 1] == y) return
            out.add(x)
            out.add(y)
        }
        // The stem first, as a prepended vertex rather than a second stroke in [paintHalf], so the polyline
        // the mouse is tested against is the polyline the editor drew.
        if (hasStem) {
            at(startX.toFloat(), stemY.toFloat())
            at(startX.toFloat(), trunkY.toFloat())
        }
        at(fromX.toFloat(), trunkY.toFloat())
        if (hasHop) {
            at(hopX, trunkY.toFloat())
            at(hopX, railY.toFloat())
        }
        at(centreXf, railY.toFloat())
        at(centreXf, legTo.toFloat())
        return out.toFloatArray()
    }
}

/**
 * **How a link is drawn** — the token tint, the connector, the portal it goes into, and the label beside it.
 *
 * Separate from [SpotlightRenderer] so a bench can draw the real thing: `probes/LinkBench.java` imports this
 * and draws with it — both node widths, five links at once, both directions, both anchors, both joins,
 * resting and hovered. There is one derivation of a link's geometry and one of its pixels, so the bench
 * cannot disagree with what the editor paints.
 *
 * Everything is pure: no editor, no settings, no scheme lookup, no EDT. The caller resolves the link's colour
 * and the editor's background **per render** and passes them in, which is what lets both ends of a link land
 * on one colour without either being told what the other picked. Even the cross-editor alignment is pure
 * here — see [alignedLaneAxes].
 *
 * ## One link system, two dimensions
 *
 * A link is two independent choices — [LinkAnchor] × [LinkJoin] — and all four combinations are one drawing:
 *
 * | | [LinkJoin.LINE] | [LinkJoin.PORTAL] |
 * |---|---|---|
 * | [LinkAnchor.TOKEN] | tint, rail, lane, rail, tint | tint, rail, **portal** |
 * | [LinkAnchor.LINE_END] | text end, row middle, lane, row middle, text end | text end, row middle, `∩`/`∪`, rail, **portal** |
 *
 * Everything after the anchor is shared code, so the finish into a portal looks the same for every portal
 * link by construction.
 *
 * - **Routing by lane and by fan.** A **lane** ([laneAxes]) is the column the vertical is drawn on, sized to
 *   what it carries; a **fan** ([railStep]) is which rail an end takes off its own row, counted per row. Two
 *   links on one step share neither, so "verticals and portals never clash" is arithmetic rather than a
 *   search for a free column.
 * - **Resting links are only lightly dimmed** ([REST]): a hue the theme does not favour must stay legible,
 *   and no leg crosses code (they run through the row's leading), so there is nothing to soften.
 * - **The label is on the drawing**, beside the portal, on hover — see [paintLabel].
 */
object LinkArt {

    /**
     * **The whole geometry of one end**, or null when there is no room to draw it.
     *
     * One derivation, and everything else here reads it: [paintHalf] draws what this decided and [hit] tests
     * what this decided, so the hit band cannot drift from the ink. The room check lives here too, so a lane
     * clamped *left* of the token is refused rather than reaching painting as an inverted `coerceIn` that
     * would throw out of a `CustomHighlighterRenderer` and cost the whole node's paint. `probes/LinkBench.java`
     * calls this same factory, so an end the bench draws (or skips) is one the editor draws (or skips).
     *
     * **[fan] is the vertical half of the routing system.** The rail moves [railStep] px per fan *away* from
     * the row — up for a link that dives, down for one that rises — so N links leaving one row leave it on N
     * different rails and none is hidden under another.
     * [lane] is the horizontal half, and [axisX] arrives already resolved because only the caller knows the
     * column the whole step is counting from.
     *
     * The portal is **not** offset vertically: it stays parked against its own row's bound. Two links on one
     * row are already in different lanes, so their portals cannot collide, and a portal that drifted off its
     * row would stop saying which row it belongs to.
     */
    @JvmOverloads
    fun shape(
        anchor: LinkAnchor,
        join: LinkJoin,
        lane: Int,
        fan: Int,
        startX: Int,
        textEndX: Int,
        rowMid: Int,
        axisX: Int,
        lineHeight: Int,
        incoming: Boolean,
        targetBelow: Boolean,
        route: LinkRoute = LinkRoute.DIRECTIONAL,
        mutual: Boolean = false,
        metrics: LinkMetrics = defaultMetrics(),
    ): LinkShape? {
        val outer = outer(axisX, rowMid, lineHeight, targetBelow, route)
        val inner = inner(outer)
        val straight = route == LinkRoute.STRAIGHT
        val rowTop = rowMid - lineHeight / 2
        val inset = JBUI.scale(RAIL_INSET)
        val order = max(0, fan)
        val off = order * railStep(lineHeight)
        val railY = when {
            straight -> rowMid
            // **The one form with no rail.** A same-editor line-end link turns once, at its own lane, and
            // runs to its partner's row middle. Giving it a rail would add a U-turn and cost a row's leading
            // for a link that needs none.
            //
            // [centreOffset] keeps such runs apart: the run stays on the row's middle when it is the only
            // one there, and steps off it by 2 px at a time when it is not, so five runs leaving
            // one row are five lines rather than one. [LinkShape.trunkY] follows the rail for this form, so
            // the offset moves the *whole* run and the end still turns exactly once.
            anchor == LinkAnchor.LINE_END && join == LinkJoin.LINE -> rowMid + centreOffset(order, lineHeight)
            targetBelow -> rowTop + inset - off
            else -> rowTop + lineHeight - inset + off
        }
        // The leg stops on the *far* side of the hole, having crossed all of it. A LINE end has no leg of its
        // own: [paintPair] runs it to the partner's rail, which is a number this end cannot see.
        val legEndY = when {
            join == LinkJoin.LINE -> railY
            straight -> rowMid
            targetBelow -> inner.y + inner.height
            else -> inner.y
        }
        val shape = LinkShape(
            anchor = anchor,
            join = join,
            lane = max(0, lane),
            fan = max(0, fan),
            startX = startX,
            textEndX = textEndX,
            rowMid = rowMid,
            lineHeight = lineHeight,
            axisX = axisX,
            outer = outer,
            inner = inner,
            railY = railY,
            legEndY = legEndY,
            incoming = incoming,
            targetBelow = targetBelow,
            mutual = mutual,
            metrics = metrics,
        )
        if (shape.centreXf - shape.inkX < JBUI.scale(MIN_RUN)) return null
        return shape
    }

    /**
     * The portal's outline: [outerWidth] across, **half as tall**, and parked at the end of the leg's travel —
     * the bottom of the row for a target below, the top for one above.
     *
     * Flattening it is what makes the travel readable: a circle at the bottom of a row and a circle at the
     * top of one look like the same circle moved, where a flat oval reads as a letterbox the line drops through.
     *
     * Computed for a [LinkJoin.LINE] end too, but not to reserve a column (a same-editor lane is only
     * [LINE_LANE_HALF] px wide): a LINE end uses it to place the **label**, clear of where a portal would be,
     * so a step's labels line up whatever their links are joined by.
     */
    fun outer(
        axisX: Int,
        rowMid: Int,
        lineHeight: Int,
        targetBelow: Boolean,
        route: LinkRoute = LinkRoute.DIRECTIONAL,
    ): Rectangle {
        val w = outerWidth(lineHeight)
        val h = odd(max(JBUI.scale(OVAL_MIN_H), w / OVAL_FLATTEN))
        val inset = JBUI.scale(RAIL_INSET)
        val rowTop = rowMid - lineHeight / 2
        val y = when {
            route == LinkRoute.STRAIGHT -> rowMid - h / 2
            targetBelow -> rowTop + lineHeight - inset - h
            else -> rowTop + inset
        }
        // Centred on the axis, and exactly: [outerWidth] is odd, so `w / 2` is `(w - 1) / 2` and
        // `x + w / 2f` comes back to `axisX + 0.5` — the half-integer the leg is stroked on.
        return Rectangle(axisX - w / 2, y, w, h)
    }

    /**
     * **The portal's width: a proportion of the row, and always odd.**
     *
     * Proportional, so the portal scales with the editor font. [OVAL_MAX_W] sits at five times the default,
     * where it only stops a broken font metric from painting a balloon.
     *
     * **Odd** because it is the only way the two ovals can be exactly concentric. `inner.x = outer.x +
     * (outer.width - inner.width) / 2` is integer division: with the widths of *different* parity it loses a
     * half pixel, always in the same direction, and the hole ends up nearer one side of its ring than the
     * other. Odd widths make the difference even and the division exact. See [LinkShape.centreXf] for the
     * other half of the fix — the axis the line is drawn on.
     */
    fun outerWidth(lineHeight: Int): Int =
        odd((lineHeight * OVAL_W_PCT / 100).coerceIn(JBUI.scale(OVAL_MIN_W), JBUI.scale(OVAL_MAX_W)))

    /**
     * The transparent core, [INNER_RATIO] smaller than [outer] in both dimensions and concentric with it.
     *
     * **Odd in both dimensions**, for [outerWidth]'s reason, so the hole is centred in its ring both ways.
     *
     * Floored at [INNER_MIN] each way: below that the hole closes up under antialiasing and the portal is a
     * blob again, which is the whole thing the rings exist to avoid. Capped two pixels inside [outer] for the
     * opposite degenerate case, where the floor would otherwise leave the ring no width at all.
     */
    fun inner(outer: Rectangle): Rectangle {
        val w = fit(outer.width, (outer.width / INNER_RATIO).roundToInt())
        val h = fit(outer.height, (outer.height / INNER_RATIO).roundToInt())
        return Rectangle(outer.x + (outer.width - w) / 2, outer.y + (outer.height - h) / 2, w, h)
    }

    /** One of the hole's dimensions: [want], floored at [INNER_MIN], and kept odd and inside [bound]. */
    private fun fit(bound: Int, want: Int): Int =
        odd(max(JBUI.scale(INNER_MIN), want)).coerceAtMost(odd(max(1, bound - 2)))

    /**
     * [v] rounded **up** to an odd number — what keeps the portal symmetric about its axis.
     *
     * Up rather than to-nearest so a floor stays a floor: [INNER_MIN] of 3 must not come back as 1.
     */
    private fun odd(v: Int): Int = if (v <= 1) 1 else v or 1

    /** Clear space between the widest link row's code and the first channel. */
    fun channelGap(): Int = JBUI.scale(CHANNEL_GAP)

    /** The arrowheads' size: the in-file relation arrow's own default, which is what they are compared to. */
    fun head(): Int = JBUI.scale(HEAD)

    /** How far short of the turn the direction indicator's leading edge sits. See [LinkShape.indicatorX]. */
    fun indicatorGap(): Int = JBUI.scale(INDICATOR_GAP)

    /** The turns' radius — the corner into the channel, and a line end's corner onto the rail. */
    fun corner(): Int = JBUI.scale(CORNER)

    /**
     * **What the link drawing looks like with nobody configuring it** — the shipped defaults.
     *
     * Every one of them is scaled here rather than at the constant, so a caller building [LinkMetrics] from
     * a settings value scales it the same way and the two paths cannot disagree about DPI. See [LinkMetrics]
     * for why this is a default handed *in* rather than a lookup made here.
     */
    fun defaultMetrics(): LinkMetrics = LinkMetrics(head(), corner(), REST_STROKE)

    /**
     * [defaultMetrics] with the three configured numbers substituted, each scaled and floored the same way.
     *
     * [thicknessTenths] is tenths of a px because a link's resting stroke is around 1 px, where whole-number
     * steps are the difference between a hint and a rule. [squareElbow] is the corner as a boolean because
     * that is the only thing a reader ever wanted from it — square or the shipped 4 px, not a radius to tune.
     */
    fun metrics(thicknessTenths: Int, headSize: Int, squareElbow: Boolean): LinkMetrics = LinkMetrics(
        head = JBUI.scale(headSize.coerceAtLeast(1)),
        corner = if (squareElbow) 0 else JBUI.scale(CORNER),
        stroke = JBUI.scale(thicknessTenths.coerceAtLeast(1) / 10f),
    )

    /** How far a [LinkAnchor.LINE_END] connector starts past the row's last glyph. See [LinkShape.inkX]. */
    fun lineEndStub(): Int = JBUI.scale(LINE_END_STUB)

    /**
     * **How far one fan step moves a rail away from its row** — the vertical half of the routing system.
     *
     * Not one pixel: a hovered link is a 2 px stroke, so rails one pixel apart would merge. Three pixels at
     * the default row leaves a clear pixel between two hovered strokes, and deriving it from the row keeps
     * that true at a larger editor font.
     *
     * Fan 4's rail sits 12 px beyond its row's own bound at the default row height — inside the neighbouring
     * row. So the row buys the room instead of borrowing it: [railSpace] is the number and `SpotlightRenderer`
     * spends it on a block inlay, only on the rows and sides that actually carry rails.
     */
    fun railStep(lineHeight: Int): Int = max(JBUI.scale(RAIL_STEP_MIN), lineHeight * RAIL_STEP_PCT / 100)

    /**
     * **How far inside its row the fan-0 rail sits** — the one pixel of leading that keeps a lone link's
     * horizontal off both its own glyphs and the neighbouring row's.
     *
     * Also where a fanned end's stem lands ([LinkShape.stemY]): the tick has to reach the point a *lone* link
     * would have left from, or it is a mark near the token rather than part of the same drawing.
     */
    fun railInset(): Int = JBUI.scale(RAIL_INSET)

    /**
     * **How wide one lane is, either side of its axis.**
     *
     * A portal is [outerWidth] across; a same-editor link's vertical is one stroke. Giving every lane a
     * portal's width would need 124 px of clear width for five links, more than a narrow node has, so each
     * lane is measured by what it actually occupies. Note the `+ 1`: a 23 px oval
     * centred on the axis spans `axis-11 .. axis+12`, and the half that matters for the *next* lane is the
     * wider one.
     */
    fun laneHalfExtent(join: LinkJoin, lineHeight: Int): Int =
        if (join == LinkJoin.PORTAL) outerWidth(lineHeight) / 2 + 1 else JBUI.scale(LINE_LANE_HALF)

    /**
     * **Where every lane of a step's fan sits**, as offsets from the fan's left boundary.
     *
     * [joins] is the step's whole lane layout in lane order, **same-editor links first**: same-editor links
     * turn first, each in its own column, and portal links turn into their portals after them. The order is
     * the tree's to choose (`TreePlanView.paintLinkPairs`); the arithmetic is here, and it is the same in
     * every editor on screen, which keeps the two ends of a portal link on one column even when only one of
     * the two nodes also holds a same-editor link.
     *
     * Adjacent lanes are [LANE_GAP] apart **edge to edge**, so the three routing rules hold by construction
     * rather than by search:
     *
     * - different links may cross but never overlap in sections — no two verticals share an x;
     * - portals never overlap — two portal lanes are at least a whole portal plus [LANE_GAP] apart;
     * - no line passes through a portal that is not its own — a vertical is [LANE_GAP] clear of the widest
     *   part of any portal beside it, whatever the two lanes are carrying.
     */
    fun laneAxes(joins: List<LinkJoin>, lineHeight: Int): IntArray =
        laneAxes(joins, IntArray(joins.size) { NO_DEMAND }, lineHeight)

    /**
     * **Where every lane sits when each one carries its own demand** — routing each lane as near the code as
     * it can while still avoiding columns taken by other links.
     *
     * [demands] is one leftmost **edge** per lane: the x that lane may not start left of, because that is
     * where its own link's code ends. Per lane rather than one demand for the whole step, so a link off a
     * 30-column line is not routed out past a 120-column one (leaving its label no room). Lane *k* answers
     * only for the rows lane *k* actually joins, and the packing then does the rest:
     *
     * - a lane never starts left of its own demand, so it still clears its own code by [channelGap];
     * - a lane never starts nearer than [LANE_GAP] to the lane before it, so the three routing rules
     *   [laneAxes] documents still hold — the packing can only push lanes *right*, never
     *   together;
     * - lanes stay in lane order, so two of them cannot swap and cross.
     *
     * **The columns are filled in demand order, not lane order**, and that is what actually buys the room.
     * Packing left to right in lane order gives a lane with a small demand nothing whenever a larger one
     * precedes it — the fan is as wide as if they had all been the largest. Taking the smallest first puts
     * every lane as near the code as the lanes before it allow, and it is optimal rather than merely better:
     * for a chain of fixed-width columns with lower bounds, ascending order of bound minimises the last
     * column's edge.
     *
     * **Within a join, though, never across one.** Portals stay outside same-editor verticals whatever their
     * demands — that is a routing rule, not an optimisation, and this may not trade it for pixels.
     *
     * Column order is therefore not lane order, and two links may cross; that is allowed. What may not
     * happen is two verticals on one x, and the [LANE_GAP] between consecutive **columns** rules that out —
     * the reorder changes which lane sits in which column, never how far apart two columns are.
     *
     * With every demand [NO_DEMAND] the order is the lane order, which is what the no-argument overload is:
     * the minimum-width layout, and therefore the right thing to measure a viewport against ([minRoom]).
     *
     * **[base] is where a lane with no demand of its own starts.** It cannot be fixed at 0: that is the left
     * edge of a *content* pane but, on a screen basis, a point 2 300 px right of an editor on a monitor left
     * of the primary. A width measurement passes 0 and gets a width; a placement passes the leftmost x
     * anything may occupy ([alignedLaneAxes] passes its own `left`) and gets a placement.
     */
    @JvmOverloads
    fun laneAxes(joins: List<LinkJoin>, demands: IntArray, lineHeight: Int, base: Int = 0): IntArray {
        val out = IntArray(joins.size)
        val gap = JBUI.scale(LANE_GAP)
        // No clamp to 0: a demand is an x on the basis the caller chose, and on a screen basis it is
        // routinely negative. [NO_DEMAND] is the only value that is not an x, and it sorts innermost.
        fun demandOf(i: Int) = if (i < demands.size) demands[i] else NO_DEMAND
        val order = joins.indices.sortedWith(
            compareBy({ if (joins[it] == LinkJoin.PORTAL) 1 else 0 }, { demandOf(it) }, { it }),
        )
        var prevEdge = 0
        for ((n, i) in order.withIndex()) {
            val half = laneHalfExtent(joins[i], lineHeight)
            val floor = if (n == 0) base + half else prevEdge + gap + half
            val d = demandOf(i)
            out[i] = if (d == NO_DEMAND) floor else max(floor, d + half)
            prevEdge = out[i] + half
        }
        return out
    }

    /** The leftmost edge any lane of [axes] occupies. */
    fun laneLeftEdge(joins: List<LinkJoin>, axes: IntArray, lineHeight: Int): Int {
        var x = Int.MAX_VALUE
        for (i in joins.indices) {
            if (i >= axes.size) break
            x = min(x, axes[i] - laneHalfExtent(joins[i], lineHeight))
        }
        return if (x == Int.MAX_VALUE) 0 else x
    }

    /** How much clear width right of the code the whole fan needs. 0 for a step with no links. */
    fun laneSpan(joins: List<LinkJoin>, lineHeight: Int): Int {
        if (joins.isEmpty()) return 0
        val axes = laneAxes(joins, lineHeight)
        return axes[axes.size - 1] + laneHalfExtent(joins[joins.size - 1], lineHeight)
    }

    /**
     * The right edge of the **outermost** lane, which is where a hovered label may start.
     *
     * Lanes do not bunch against the node's right edge, so a label beside lane 0's own portal could be drawn
     * across lanes 1-4's verticals; [paintLabel] uses this to decide whether to clear the whole fan.
     *
     * **Seeded from [Int.MIN_VALUE], not 0**: a max that starts at 0 answers *0* for a fan whose every lane
     * is negative (a monitor left of the primary), which would tell [alignedLaneAxes] the fan overran a
     * viewport it fits in. On a screen basis 0 is a coordinate, never an identity — see [NO_DEMAND].
     */
    fun laneRightEdge(joins: List<LinkJoin>, axes: IntArray, lineHeight: Int): Int {
        var x = Int.MIN_VALUE
        for (i in joins.indices) {
            if (i >= axes.size) break
            x = max(x, axes[i] + laneHalfExtent(joins[i], lineHeight))
        }
        return if (x == Int.MIN_VALUE) 0 else x
    }

    /**
     * **How much blank room a row needs beyond its own bound** to hold [rails] fanned rails on one side.
     *
     * Fan 0 sits [RAIL_INSET] *inside* the row, in the leading, so one rail costs nothing; every rail past it
     * is [railStep] further out and the last one is outside the row altogether. `SpotlightRenderer` spends
     * this on a block inlay so the space is real rather than borrowed from the neighbouring row (where rails
     * would run over a callout or appear to target the wrong line).
     *
     * Includes [RAIL_PAD] so a hovered 2 px stroke on the outermost rail still has a clear pixel outside it —
     * and [hoverStroke] is what makes that hold at a *configured* weight rather than only at the shipped one.
     * A heavier stroke buys its own extra clearance here; the lane **pitch** is deliberately not widened with
     * it, because pitch is the row's leading divided up and widening it would move every link to pay for a
     * weight only some readers set. Past **about 2.5 px** several links leaving one row can intersect; the
     * setting still allows up to 4.0 px on purpose — the case needs several links on one row, which is rare,
     * and hovering tells the two halves of a link apart anyway.
     */
    @JvmOverloads
    fun railSpace(rails: Int, lineHeight: Int, hoverStroke: Float = HOVER_STROKE): Int {
        if (rails <= 1) return 0
        val extra = JBUI.scale(kotlin.math.ceil(hoverStroke - HOVER_STROKE).coerceAtLeast(0f)).toInt()
        return ((rails - 1) * railStep(lineHeight) - JBUI.scale(RAIL_INSET) + JBUI.scale(RAIL_PAD) + extra)
            .coerceAtLeast(0)
    }

    /**
     * **Does a block inlay stand between two rows a leg travels between?** — the rule
     * `SpotlightRenderer.inlayRight` measures its clearance with, kept here so a bench can assert it.
     *
     * [gapTop] is the **bottom of the upper row** and [gapBottom] the **top of the lower one**, so the band is
     * the space strictly *between* the two rows: a block inlay lives between two rows and nowhere else, and
     * both bounds come from `offsetToXY`,
     * which already counts every inlay above them. Two rows with nothing between them give an **empty** band
     * however far apart their line numbers are, and two adjacent rows with a nested node between them give a
     * band exactly that node's height.
     *
     * Not the two rows' **full extent**: a nested child node docked under the lower row starts exactly at that
     * row's bottom edge, and counting it would route a link between two adjacent rows clear of an inlay as
     * wide as a whole editor that the link never travels past.
     */
    fun crossesRowGap(gapTop: Int, gapBottom: Int, inlayY: Int, inlayHeight: Int): Boolean =
        gapBottom > gapTop && inlayY + inlayHeight > gapTop && inlayY < gapBottom

    /**
     * **How much run a line end spends on its hop** onto the rail, and therefore how much rail every portal
     * link has before its turn however long its row is.
     *
     * Two corners, a head and the head's gap: below this the `∩` would be a kink with a triangle on it.
     */
    fun uRun(): Int = JBUI.scale(U_RUN)

    /**
     * **How far off its row's middle a same-editor line-end run sits**, by its order among the runs leaving
     * that row.
     *
     * The one form with no rail ([shape]) runs along the row's *middle* for its whole horizontal span, so
     * five of them leaving one row would be five lines on one y. They cannot be fanned the way railed ends
     * are — a rail leaves the row and buys blank space to do it, and these have nowhere to go — so they are
     * spread **about** the middle instead: 0, −2, +2, −4, +4.
     *
     * Alternating rather than stacking is what keeps the *first* run — the commonest case by far, one link
     * on a row — exactly on the middle where it was, and keeps the worst excursion of five to [CENTRE_STEP]
     * × 2 rather than × 4. Clamped to the row's own half-height less [CENTRE_MARGIN] so a fan on a small
     * font can never put a run on the row above.
     */
    fun centreOffset(order: Int, lineHeight: Int): Int {
        if (order <= 0) return 0
        val rank = (order + 1) / 2
        val sign = if (order % 2 == 1) -1 else 1
        val limit = (lineHeight / 2 - JBUI.scale(CENTRE_MARGIN)).coerceAtLeast(0)
        return (sign * rank * JBUI.scale(CENTRE_STEP)).coerceIn(-limit, limit)
    }

    /**
     * Below this much clear room right of the code there is nowhere to put the fan at all, and the whole
     * step's links are skipped.
     *
     * [joins] is the step's lane layout, so this is [laneSpan] plus slack rather than a constant: the fan a
     * step needs depends on what its links are joined by — three same-editor links need a third of what
     * three portals do.
     */
    fun minRoom(joins: List<LinkJoin>, lineHeight: Int): Int =
        laneSpan(joins, lineHeight) + JBUI.scale(ROOM_SLACK)

    /**
     * Whether a lane pushed clear of an obstruction ending at [rightEdge] still leaves the fan somewhere to
     * be drawn.
     *
     * A [LinkJoin.LINE] leg is routed clear of every **block inlay** it crosses, because an inlay paints in a
     * later pass than a highlighter renderer and no layer can lift the leg above it. That is right for our
     * own callout box, a few hundred pixels wide with the rest of the row beyond it.
     *
     * It is **not** right for a nested code node, which is as wide as the editor less its inset. **There is no
     * x that clears it**, so the demand would buy a fan pinned against the viewport's far-right edge *and* a
     * leg still crossed by the node: two costs and no benefit.
     *
     * So the demand is honoured only when it is **achievable**: the fan's own minimum must fit between the
     * obstruction and [room]. A callout passes and a nested node does not, and neither is named — the test is
     * the geometry, so an inlay nobody has thought of yet gets the right answer for the right reason.
     */
    fun obstructionClearable(rightEdge: Int, joins: List<LinkJoin>, lineHeight: Int, room: Int): Boolean =
        rightEdge + minRoom(joins, lineHeight) <= room

    /**
     * **The columns that put every editor's links in one vertical line** — the arithmetic, which is all
     * of the decision that can be made without an editor.
     *
     * Each end computes its own demand from its own node's code, so two nodes holding different code would
     * put the two ends of one link at different x. The fix is one layout shared by every end on screen, in a
     * basis they all agree on.
     *
     * **Two constraints:**
     *
     * - [demands] — every member's own clear-of-the-code demand, per lane. A lane must be at least the
     *   **max**, or some node's fan is drawn over its own code.
     * - [room] and the fan's span — the narrowest member's right edge, and the width the fan needs. The fan
     *   must end inside it, or the narrowest node cannot separate its lanes and clamps them into each other,
     *   pushing its end of a link off the column the other node uses.
     *
     * **When they disagree, the fit wins.** A column that clears every node's code but leaves the narrowest
     * of them no room to separate five lanes has satisfied the wrong requirement: code under a link is dim
     * and still legible, and five links folded onto one column are not recoverable at all.
     *
     * [demands], [room] and [left] have each editor's own origin already added in, so the basis is the
     * screen and no caller needs to know how the nest indents its children or where each editor is scrolled
     * to. The return converts back into [myOriginX]'s own content coordinates, which is what a renderer
     * paints in.
     *
     * Returns null when there is nothing to lay out or the fan cannot fit at all.
     */
    fun alignedLaneAxes(
        myOriginX: Int,
        joins: List<LinkJoin>,
        demands: IntArray,
        room: Int,
        left: Int,
        lineHeight: Int,
    ): IntArray? {
        if (joins.isEmpty()) return null
        // The minimum-width layout is what decides whether there is any arrangement at all: demands can only
        // push lanes right, so if the fan does not fit with none of them it fits with none of any.
        if (room - laneSpan(joins, lineHeight) <= left) return null
        val axes = laneAxes(joins, demands, lineHeight, left)
        val over = laneRightEdge(joins, axes, lineHeight) - room
        if (over > 0) {
            // **Shift the whole fan, never squeeze it.** One subtraction across every lane keeps every gap
            // exactly as packed, so the routing rules survive the clamp; what is given up is the promise
            // that each lane clears its own code — code under a link is dim and still legible, lanes
            // folded together are not.
            val shift = min(over, max(0, laneLeftEdge(joins, axes, lineHeight) - left))
            for (i in axes.indices) axes[i] -= shift
            if (laneRightEdge(joins, axes, lineHeight) > room) {
                return packed(joins, demands, room, left, lineHeight, myOriginX)
            }
        }
        for (i in axes.indices) axes[i] -= myOriginX
        return axes
    }

    /**
     * **The last resort**: every lane at its minimum pitch, the block placed as far right as the demands want
     * and as far left as the room requires.
     *
     * Per-lane demands can make a fan *wider than the viewport*: two lanes 700 px apart pack 700 px apart, and
     * shifting that block left does not narrow it. Without this the shift-clamp would draw **nothing**, and a
     * minimum-pitch fan is plainly better than a step with no links in it.
     *
     * Null only when even this does not fit, which is the case [alignedLaneAxes] rejects up front anyway.
     */
    private fun packed(
        joins: List<LinkJoin>,
        demands: IntArray,
        room: Int,
        left: Int,
        lineHeight: Int,
        myOriginX: Int,
    ): IntArray? {
        val axes = laneAxes(joins, lineHeight)
        var want = NO_DEMAND
        for (d in demands) if (d != NO_DEMAND && d > want) want = d
        val span = laneSpan(joins, lineHeight)
        // No demand anywhere leaves nothing to clear, so the block goes as far left as it may — `left`,
        // never 0, which on a negative-x screen is a point off the right of the editor.
        val base = if (want == NO_DEMAND) left else min(want, room - span).coerceAtLeast(left)
        if (base + span > room) return null
        for (i in axes.indices) axes[i] += base - myOriginX
        return axes
    }

    /**
     * The link's colour, from the accent and the link's slot.
     *
     * Slot 0 **is** the accent, so the common case — one link on a step — is coloured like every other
     * decoration and adds no new colour to the picture. Past that the hue rotates, and [HUE_OFFSETS] is
     * ordered rather than stepped: five slots have to be spread over the wheel, and taking them in
     * ascending order would put the two links a reader is most likely to compare — 0 and 1, drawn from the
     * same row — a fifth of the wheel apart. Two fifths between *consecutive* slots is the most any ordering
     * of five evenly-spread hues can give, and this is that ordering.
     *
     * The saturation floor is what keeps them apart for an accent close to grey, where slot 0 has no
     * saturation of its own to be distinguished by.
     *
     * Both numbers are measured rather than asserted — `probes/LinkBench.java` reports the worst pairwise
     * distance across a gold, a grey and a blue accent, over all five slots and over consecutive pairs
     * separately, because those are two different questions.
     */
    fun color(accent: Color, index: Int): Color {
        val slot = ((index % SLOTS) + SLOTS) % SLOTS
        if (slot == 0) return accent
        val hsb = Color.RGBtoHSB(accent.red, accent.green, accent.blue, null)
        val hue = ((hsb[0] + HUE_OFFSETS[slot]) % 1f + 1f) % 1f
        return Color(Color.HSBtoRGB(hue, max(hsb[1], MIN_SAT), hsb[2]))
    }

    /**
     * **"This lane asks for nothing from here"** — the one value a lane demand may take that is not an x.
     *
     * Not **0**: 0 is only a sentinel while screen x is positive. On a monitor *left* of the primary every
     * `locationOnScreen` is negative, so every real demand is negative too, and a `max` seeded at 0 would read
     * them all as "asks for nothing" — pinning every fan against the viewport's right edge, with no room left
     * for link labels.
     *
     * [Int.MIN_VALUE] rather than a nullable array because these are passed per paint and the packing sorts
     * by them: absent has to be *orderable*, and it sorts innermost, which is right — a lane with no code to
     * clear is the one that should end up nearest the code.
     */
    const val NO_DEMAND: Int = Int.MIN_VALUE

    /** How many links one step can draw — see [SLOTS]. */
    fun slots(): Int = SLOTS

    /**
     * The token's own tint: the link's colour at low weight over the editor background.
     *
     * Deliberately slight: the step's active range already carries a glow, and a token tint that competed
     * with it would read as a second highlight rather than as the end of a link.
     */
    fun tint(color: Color, bg: Color): Color = SpotlightRenderer.blendToward(color, bg, TINT)

    /**
     * The token's tint while its link is hovered — the same colour, further from the background.
     *
     * The whole link brightens together, token included (see [paintHalf]). It stays short of the connector's
     * own weight: a tint is a background behind live code, and code has to stay readable through it.
     */
    fun tintHovered(color: Color, bg: Color): Color = SpotlightRenderer.blendToward(color, bg, TINT_HOVER)

    /**
     * Paint a [LinkJoin.PORTAL] end: the connector, the direction indicator, and the portal it goes into.
     *
     * **Mutates [g]'s colour, stroke and paint**, so the caller passes a copy.
     */
    fun paint(g: Graphics2D, shape: LinkShape, color: Color, bg: Color, hovered: Boolean) =
        paintHalf(g, shape, color, bg, hovered, shape.legEndY)

    /**
     * Paint a whole [LinkJoin.LINE] link: both ends, joined in the channel they share.
     *
     * Each end's leg runs to the **other's** rail, so the vertical between them is drawn twice — once per
     * end, at identical coordinates. That is deliberate and it is free: the axis is a half-integer
     * ([LinkShape.centreXf]) and the strokes are 1 px and 2 px, so every pixel the vertical covers is covered
     * *fully*, and painting a fully-covered opaque pixel twice is painting it once. The alternative — one
     * path stitched from two ends' worth of corners, one of them reversed — is the same picture with a
     * second geometry to keep in agreement with this one.
     */
    fun paintPair(g: Graphics2D, a: LinkShape, b: LinkShape, color: Color, bg: Color, hovered: Boolean) {
        paintHalf(g, a, color, bg, hovered, b.railY)
        paintHalf(g, b, color, bg, hovered, a.railY)
    }

    /**
     * Paint one end, with its leg running to [legTo].
     *
     * [hovered] is the whole point of a link reading as one thing: the caller brightens **both** ends when
     * either is hovered, so the answer to *"which end is this one's partner"* is a mouse-move rather than a
     * number to match up. Resting is not dim, merely thinner and a shade softer.
     *
     * **Order is load-bearing**: connector first, then the portal over it. The leg is drawn across the whole
     * hole and the annulus painted on top, so the ring is never broken by the line passing under it while the
     * hole still shows the line crossing. Reversing the two leaves a line that stops at an outline.
     */
    fun paintHalf(
        g: Graphics2D,
        shape: LinkShape,
        color: Color,
        bg: Color,
        hovered: Boolean,
        legTo: Int,
    ) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val line = if (hovered) color else SpotlightRenderer.blendToward(color, bg, REST)
        val weight = if (hovered) shape.metrics.hoverStroke else shape.metrics.stroke
        val head = shape.metrics.head

        g.paint = line
        g.stroke = BasicStroke(weight, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(connector(shape, legTo))
        paintIndicator(g, shape, head, line)
        if (shape.hasPortal) paintPortal(g, shape, color, bg, hovered, weight)

        // Both ends of a mutual link, one end of a one-way one — see [LinkShape.arrives].
        if (shape.arrives) {
            val y = shape.anchorY.toFloat()
            val tri = Path2D.Float()
            tri.moveTo(shape.inkX.toFloat(), y)
            tri.lineTo(shape.inkX + head.toFloat(), y - head / 2f)
            tri.lineTo(shape.inkX + head.toFloat(), y + head / 2f)
            tri.closePath()
            g.paint = line
            g.fill(tri)
        }
    }

    /**
     * The connector: [LinkShape.points], with every corner rounded.
     *
     * A single path rather than one draw per segment, so every corner joins cleanly at both stroke weights.
     * It knows nothing about anchors, joins or hops — it rounds whatever polyline the shape hands it.
     */
    private fun connector(shape: LinkShape, legTo: Int): Path2D {
        val pts = shape.points(legTo)
        val p = Path2D.Float()
        val n = pts.size / 2
        p.moveTo(pts[0], pts[1])
        val r0 = shape.metrics.corner.toFloat()
        for (i in 1 until n - 1) {
            val px = pts[2 * i - 2]
            val py = pts[2 * i - 1]
            val cx = pts[2 * i]
            val cy = pts[2 * i + 1]
            val nx = pts[2 * i + 2]
            val ny = pts[2 * i + 3]
            val inLen = hypot(cx - px, cy - py)
            val outLen = hypot(nx - cx, ny - cy)
            if (inLen <= 0f || outLen <= 0f) continue
            // Half of each adjoining segment, so a vertex that turns twice in a short run bends tighter
            // rather than folding back over itself.
            val r = min(r0, min(inLen / 2f, outLen / 2f)).coerceAtLeast(0f)
            p.lineTo(cx - (cx - px) / inLen * r, cy - (cy - py) / inLen * r)
            p.quadTo(cx, cy, cx + (nx - cx) / outLen * r, cy + (ny - cy) / outLen * r)
        }
        if (n > 1) p.lineTo(pts[2 * n - 2], pts[2 * n - 1])
        return p
    }

    /**
     * The direction indicator: a head on the rail, its leading edge [INDICATOR_GAP] short of the turn.
     *
     * It points **into** the channel on the outgoing end and **out** of it on the incoming one, which is the
     * one thing neither the colour nor the shared column can say: whether this end is where the link starts
     * or where it ends. Skipped when the run is too short to hold it clear of both ends — an indicator
     * sitting on a corner reads as a kink in the line rather than as an arrow.
     *
     * Because it lives on the rail, and every form of link reaches the rail, there is one indicator, in one
     * place, for all four combinations — so the finish into a portal looks the same for every portal link.
     */
    private fun paintIndicator(g: Graphics2D, shape: LinkShape, head: Int, line: Color) {
        if (!shape.hasIndicator) return
        val at = shape.indicatorX
        val h = head.toFloat()
        val y = shape.railY.toFloat()
        // Tip toward the channel when the link leaves from here, away from it when the link arrives here —
        // so a mutual link's two indicators both point outward, back at their own ends.
        val tipX = if (shape.arrives) at - h / 2f else at + h / 2f
        val baseX = if (shape.arrives) at + h / 2f else at - h / 2f
        val tri = Path2D.Float()
        tri.moveTo(tipX, y)
        tri.lineTo(baseX, y - h / 2f)
        tri.lineTo(baseX, y + h / 2f)
        tri.closePath()
        g.paint = line
        g.fill(tri)
    }

    /**
     * The link's own words, right of its lane, on the **row's middle**.
     *
     * **On hover only, because of steps with five links**: the channels are a portal's width apart, so every
     * label but the outermost would be drawn over the next lane's portal. Hovering already means *this one*
     * and already lights both ends — so it is where the words go.
     *
     * **Vertically it is centred on the row's own text**, not on [LinkShape.railY]: a rail sits in the
     * leading above or below the code, and the row's middle is where the reader is looking.
     *
     * Returns false when there was not room for it, so a caller can say so rather than assume it drew.
     */
    fun paintLabel(
        g: Graphics2D,
        shape: LinkShape,
        text: String,
        color: Color,
        font: Font,
        fm: FontMetrics,
        roomRight: Int,
        fanRight: Int,
        fanSpan: Int,
        bg: Color,
    ): Boolean {
        val pad = JBUI.scale(LABEL_PAD)
        val mine = shape.outer.x + shape.outer.width + pad
        val clear = max(mine, fanRight + pad)
        // **Beside its own end — and past the whole fan only when that walk costs less than the fan is
        // wide.** Always clearing the fan crosses no line, but a label 300 px from its own lane sits beside
        // somebody else's link, and the words say which link they belong to only if they are next to it.
        //
        // [fanSpan] is the yardstick, because it is what the fan would measure with every lane at minimum
        // pitch — the width of the fan itself, in the same units as the walk to get past it:
        //
        // - a **bunched** fan (a narrow node on the clamp, or a step whose lanes all clear the same code)
        //   is a few dozen px across, so stepping past it lands the words a glance from their own end and
        //   crosses nothing.
        // - a **spread** fan puts the outermost lane hundreds of px out. Then the walk is worth more than
        //   the fan, and the words stay beside the link they name. What that costs is crossing the outer
        //   lanes, which the backdrop below makes survivable — and [mine] never has less room than [clear],
        //   so the label is trimmed less. One label is on screen at a time, so it can never cross another.
        val cheap = clear - mine <= max(0, fanSpan)
        val x = if (cheap && roomRight - clear >= JBUI.scale(MIN_LABEL_W)) clear else mine
        val avail = roomRight - x
        if (avail < JBUI.scale(MIN_LABEL_W)) return false
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = font
        val shown = if (fm.stringWidth(text) <= avail) text else ellipsize(text, fm, avail)
        val w = fm.stringWidth(shown)
        val baseline = shape.rowMid + fm.ascent / 2 - fm.descent / 2
        // **A backdrop, because the label is never over blank canvas.** In a node narrow enough to clamp its
        // fan the lanes sit on the code and the label sits on it too; past the fan it may still cross a
        // vertical. Nearly opaque rather than opaque so it reads as laid over the row rather than as a hole
        // cut in it, and only as tall as the text.
        val box = JBUI.scale(LABEL_BOX_PAD)
        g.paint = alpha(bg, LABEL_BOX_ALPHA)
        g.fillRoundRect(
            x - box, baseline - fm.ascent - box / 2,
            w + box * 2, fm.ascent + fm.descent + box, shape.metrics.corner, shape.metrics.corner,
        )
        g.paint = color
        g.drawString(shown, x, baseline)
        return true
    }

    /** [text] cut to fit [avail] px, with an ellipsis. Empty when even the ellipsis does not fit. */
    private fun ellipsize(text: String, fm: FontMetrics, avail: Int): String {
        val dots = "…"
        if (fm.stringWidth(dots) > avail) return ""
        var n = text.length
        while (n > 0 && fm.stringWidth(text.substring(0, n) + dots) > avail) n--
        return text.substring(0, n) + dots
    }

    /**
     * The portal: an outline, a translucent annulus, and a hole.
     *
     * **The core is left completely untouched**, which is the point — the code, the background and the
     * connector's leg all show through it, so the leg visibly passes *inside* the ring rather than ending on
     * it. That is why the fill is an [Area] subtraction and not an ellipse painted over the line: painting
     * anything at all in the middle, however faint, is what made the gradient that preceded this read as a
     * bullet.
     *
     * **Both bands are [Area] fills, never `g.draw(outline)`.** Java2D's stroker flattens a curve from the
     * ellipse's start point onward, so a stroked 1 px band round an 11 px oval comes out a pixel wider on the
     * right **and** a pixel low; fills mirror exactly. `probes/LinkBench.java` measures the symmetry.
     */
    private fun paintPortal(
        g: Graphics2D,
        shape: LinkShape,
        color: Color,
        bg: Color,
        hovered: Boolean,
        weight: Float,
    ) {
        val o = shape.outer
        val i = shape.inner
        if (o.width < 2 || o.height < 2) return
        val ring = Area(ellipse(o))
        ring.subtract(Area(ellipse(i)))
        g.paint = alpha(color, if (hovered) ANNULUS_HOVER else ANNULUS_REST)
        g.fill(ring)
        g.paint = if (hovered) color else SpotlightRenderer.blendToward(color, bg, RIM_REST)
        g.fill(rim(o, weight))
    }

    /**
     * The rim: the band [weight] wide straddling [o]'s outline, as two concentric ellipses subtracted.
     *
     * Geometrically the same band `BasicStroke` would have produced — half the weight either side of the
     * boundary — but built from shapes that are each symmetric about the portal's axis, so the rasterised
     * result is too. Degenerate portals (a weight thicker than the oval) get the outer ellipse whole rather
     * than an inverted subtraction.
     */
    private fun rim(o: Rectangle, weight: Float): Area {
        val half = weight / 2.0
        val w = weight.toDouble()
        val band = Area(Ellipse2D.Double(o.x - half, o.y - half, o.width + w, o.height + w))
        if (o.width - w <= 0.0 || o.height - w <= 0.0) return band
        band.subtract(Area(Ellipse2D.Double(o.x + half, o.y + half, o.width - w, o.height - w)))
        return band
    }

    private fun ellipse(r: Rectangle): Ellipse2D =
        Ellipse2D.Double(r.x.toDouble(), r.y.toDouble(), r.width.toDouble(), r.height.toDouble())

    /** [c] at [a] of its opacity, clamped. */
    private fun alpha(c: Color, a: Float): Color =
        Color(c.red, c.green, c.blue, (a.coerceIn(0f, 1f) * 255f).toInt())

    /**
     * Is [p] on this end — the portal, or any segment of the connector running to [legTo]?
     *
     * One box per drawn segment rather than one row-wide box, and the extra arithmetic is worth it: a click
     * here **navigates**, so a band covering the whole row between the anchor and the lane would eat clicks
     * meant for the code it passes over. Each box is ink [LinkShape.points] actually placed, widened by
     * [HIT_SLOP] because a 1 px line is not a mouse target.
     *
     * Recomputed from the shape rather than remembered from the last paint, so a hover test cannot go stale
     * against a scroll — and derived from the **same polyline the stroke is**, so it cannot go stale against
     * a change to the route either.
     */
    fun hit(shape: LinkShape, p: Point, legTo: Int = shape.legEndY): Boolean {
        val slop = JBUI.scale(HIT_SLOP)
        if (shape.hasPortal && Rectangle(shape.outer).apply { grow(slop, slop) }.contains(p)) return true
        val pts = shape.points(legTo)
        var i = 0
        while (i + 3 < pts.size) {
            // Every segment is axis-aligned, so its slop-grown bounding box *is* the set of points within
            // [HIT_SLOP] of the ink — no distance to compute, and nothing to keep in step with the drawing.
            val x1 = pts[i]
            val y1 = pts[i + 1]
            val x2 = pts[i + 2]
            val y2 = pts[i + 3]
            if (p.x >= min(x1, x2) - slop && p.x <= max(x1, x2) + slop &&
                p.y >= min(y1, y2) - slop && p.y <= max(y1, y2) + slop
            ) {
                return true
            }
            i += 2
        }
        return false
    }

    /**
     * **The portal's width as a percentage of the row's height** — see [outerWidth].
     *
     * 115 % gives 23 at the default 20 px row — odd, as [outerWidth] requires — and 23 flattened by
     * [OVAL_FLATTEN] gives an 11 px height. Every other font size follows from the ratio instead of a table.
     */
    private const val OVAL_W_PCT = 115

    /**
     * Floor and sanity cap on that width, in unscaled px, and the flattening that makes the height.
     *
     * The cap is five times the default: it only stops a pathological line height from painting a balloon,
     * and never decides the size at a real one.
     */
    private const val OVAL_MIN_W = 13
    private const val OVAL_MAX_W = 115
    private const val OVAL_FLATTEN = 2
    private const val OVAL_MIN_H = 5

    /** The hole, as a divisor of the outline's dimensions, and its floor in px. See [inner]. */
    private const val INNER_RATIO = 1.75
    private const val INNER_MIN = 3

    /**
     * Clear space between the widest link row's code and the first channel.
     *
     * What matters is the **rail run** it produces: an incoming end spends 7 px on its arrowhead and a turn
     * takes 4 more. At 16 the longest row of a step would have only 13 px of usable rail — not enough for a
     * 7 px head with clearance — so 22 keeps the direction indicator on every row. [LinkShape.indicatorX]
     * also shrinks its gap as a fallback; this is why that rarely has to fire.
     */
    private const val CHANNEL_GAP = 22

    /** Room the fan wants beyond its own span, so the outermost lane is not drawn hard against the scrollbar. */
    private const val ROOM_SLACK = 10

    /**
     * **How wide a lane is either side of its axis when it carries no portal**, and the clear gap between
     * two lanes' outer edges — the routing fan's whole horizontal budget. See [laneAxes].
     *
     * 2 and 8 put two same-editor verticals 12 px apart, and a vertical 8 px clear of the widest point of a
     * portal beside it.
     */
    private const val LINE_LANE_HALF = 2
    private const val LANE_GAP = 8

    /** The hop's run, and the clear pixel a hovered rail wants outside it. See [uRun] and [railSpace]. */
    private const val U_RUN = 26

    /**
     * The step between two same-editor line-end runs leaving one row, and how near the row's edge the
     * outermost of them may come — see [centreOffset].
     *
     * 2 px is the smallest step that reads as two lines at a 1 px stroke and still separates at the 2 px
     * hovered weight. Five runs therefore span 8 px about the middle, which fits inside the leading of every
     * row down to a 12 px line height; below that [CENTRE_MARGIN] takes over and they converge again rather
     * than stepping onto the neighbouring row.
     */
    private const val CENTRE_STEP = 2
    private const val CENTRE_MARGIN = 2
    private const val RAIL_PAD = 2

    /**
     * Shortest horizontal run worth drawing, measured from the ink to the **axis** — see [shape].
     *
     * Below it the portal would sit on the anchor rather than lead away from it, and a negative value means
     * the lane got clamped to the *left* of the anchor, which is a node too narrow for the link it is
     * holding.
     *
     * 20 because a same-editor lane is only [LINE_LANE_HALF] px wide, so the widest row of a step sits just
     * `CHANNEL_GAP + 2` from lane 0's axis; a minimum of 24 would come within a pixel of refusing that row.
     */
    private const val MIN_RUN = 20

    /**
     * The arrowheads: the one on the incoming anchor, and the direction indicator on the rail.
     *
     * **7, matching `AudioExplainerSettings.DEF_ARROW_HEAD`.** Not read from the settings: this object is
     * pure by design (which is what lets the bench draw the real pixels); a configured size arrives through
     * [LinkMetrics].
     */
    private const val HEAD = 7

    /**
     * How far short of the turn the direction indicator's leading edge sits.
     *
     * Short of it rather than on it: the corner is already a shape, and a triangle on top of one reads as a
     * kink. Far enough back, and it reads as a direction the line is travelling in.
     */
    private const val INDICATOR_GAP = 8

    /** The turns' radius — the corner into the channel, and a line end's corner onto the rail. */
    private const val CORNER = 4

    /**
     * How far inside the row's own bound the **fan-0** rail sits, and with it the portal.
     *
     * This is why the connector crosses nothing: 1 px inside the bound is inside the *leading*, the gap
     * between the row's height and the font's, where neither this row's glyphs nor the neighbouring row's
     * have ink. Drawing on the bound itself would straddle it and graze both.
     */
    private const val RAIL_INSET = 1

    /** How far a line-end connector starts past the row's last glyph. See [LinkShape.inkX]. */
    private const val LINE_END_STUB = 3

    /** The fan: per-rail offset as a percentage of the row, with a floor. See [railStep]. */
    private const val RAIL_STEP_PCT = 15
    private const val RAIL_STEP_MIN = 3

    /** The hovered label: its gap from the portal, and the width below which it is not drawn at all. */
    private const val LABEL_PAD = 8
    private const val MIN_LABEL_W = 60

    /** The backdrop behind a hovered label — see [paintLabel] for why it is there at all. */
    private const val LABEL_BOX_PAD = 4
    private const val LABEL_BOX_ALPHA = 0.86f

    /** Tolerance around the drawn ink for a hover test. */
    private const val HIT_SLOP = 4

    /** Stroke weights: a hint at rest, a thing you are pointing at when hovered. */
    private const val REST_STROKE = 1.0f
    private const val HOVER_STROKE = 2.0f

    /**
     * **Weight of the link's colour at rest**, `blendToward`'s first argument.
     *
     * Near 1 so every slot stays legible in both themes: around 0.45 a hue the theme does not favour —
     * purple on dark, yellow on light — reads as a smudge. Hovering still does something: it goes to the
     * colour itself **and** doubles the stroke. Stronger dimming buys nothing, because legs never cross code
     * (they run through the row's leading).
     */
    private const val REST = 0.82
    private const val RIM_REST = 0.92

    /**
     * The token tint at rest and while the link is hovered — see [tint] and [tintHovered].
     *
     * Slight at rest, because the step's active range already carries a glow and a tint competing with it
     * would read as a second highlight. **Lifted on hover** because that is when the tint stops being
     * decoration and starts being an answer. Both ends light, in both editors, so the token, the run, the
     * portal and the far token stand out together as one system.
     */
    private const val TINT = 0.34

    /**
     * The lift has to be legible as a *change* without becoming a highlight in its own right; around 0.58 the
     * token text stops being readable. A tint is a background behind live code, so the ceiling is set by the
     * code's own contrast against it, not by how clearly the tint reads.
     */
    private const val TINT_HOVER = 0.44

    /**
     * The annulus, as an alpha on the link's colour. Translucent so the ring reads as glass rather than as a
     * filled shape, and so the two rings stay distinguishable from the hole between them.
     */
    private const val ANNULUS_REST = 0.55f
    private const val ANNULUS_HOVER = 0.75f

    /**
     * **How many links one step draws**, and therefore also the size of the palette **and** the number of
     * lanes: a step cannot run out of colours or of columns, because the cap, the palette and the fan are the
     * same number. `SpotlightRenderer.MAX_RELATIONS_PER_STEP` and `TreePlanView.MAX_LINKS_PER_STEP` both read it.
     *
     * `internal` because `PlanLoader` reports a step that authors more links than there are slots. A `const`
     * is compile-time inlined, so the loader reads the number without this object — which matters, because
     * the loader is driven headless by a probe and this file scales pixels.
     */
    internal const val SLOTS = 5

    /**
     * Hue rotation per slot, **ordered for consecutive separation** — see [color].
     *
     * The set is five evenly-spread fifths of the wheel; the order is what makes slots 0 and 1 two fifths
     * apart instead of one. Slot 0 is 0 by definition: it is the accent itself.
     */
    private val HUE_OFFSETS = floatArrayOf(0f, 0.4f, 0.8f, 0.2f, 0.6f)

    /**
     * Saturation floor for a rotated slot, so a near-grey accent still yields five distinguishable links.
     *
     * **0.70, measured:** at 0.55 a grey accent's two closest of five links come out 59 RGB units apart,
     * which is not a difference; at 0.70 the worst case is 76 and the worst *consecutive* pair 105.
     */
    private const val MIN_SAT = 0.70f
}
