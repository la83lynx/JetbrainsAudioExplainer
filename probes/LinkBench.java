import com.audioexplainer.LinkAnchor;
import com.audioexplainer.LinkArt;
import com.audioexplainer.LinkJoin;
import com.audioexplainer.LinkRoute;
import com.audioexplainer.LinkShape;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * The link bench: mock nodes, up to five links at once, both anchors, both joins, both directions, resting
 * and hovered.
 *
 * This is the spike the reader asked for in round 3 — *"another dedicated spike to test how it should look,
 * with several simultaneously visible links on the screen"* — but it is not a standalone the way the deleted
 * {@code spikes/} were. It imports {@link LinkArt} and draws with it, so it cannot disagree with what the
 * editor paints; the spikes re-derived their geometry and agreed with production only until one of the two was
 * edited.
 *
 * <p><b>What it is for.</b> One question is a matter of taste and one is a matter of fact, and they need
 * different instruments:
 *
 * <ul>
 *   <li><b>Taste, answered by the PNGs.</b> Does a link that dives read differently from one that climbs? Do
 *       two rings and a hole read as a portal at 23×11? Do five links off one row read as five links? Is a
 *       resting link legible now, in both themes?</li>
 *   <li><b>Fact, answered by the assertions.</b> Five links separable by colour alone; the portal flattened,
 *       floored and capped; the hole <b>measurably transparent</b> and the leg <b>measurably inside it</b>;
 *       the fan actually separating rails and channels; the four anchor × join combinations actually
 *       differing; and the ink each route puts on top of a glyph.</li>
 * </ul>
 *
 * <p><b>Round 7 — routing that fits.</b> Round 6 stepped every lane by a whole portal, which needed 124 px of
 * clear width for five links and got clamped into one column in every node that had less — *"vertical
 * connections are not separated horizontally, all 5 overlap"*. Five claims are new, and all five are
 * measurable here:
 *
 * <ul>
 *   <li><b>Lanes are sized to what they carry</b> ({@link LinkArt#laneAxes}): 4 px for a same-editor
 *       vertical, a whole portal for a portal, {@code LANE_GAP} between the outer edges. The three routing
 *       rules the reader gave — no two verticals on one x, no two portals overlapping, no line through
 *       another's portal — are asserted directly against the drawn geometry, for every layout of five.</li>
 *   <li><b>Same-editor lanes come first</b>, so the thin ones sit nearest the code and the fan fits.</li>
 *   <li><b>A line end runs along the row's middle</b> for the whole span, and hops onto its rail only in the
 *       last {@code uRun} px — so a same-editor line link never leaves the row's middle at all, and a portal
 *       one always has the same amount of rail whatever its row's length.</li>
 *   <li><b>The fan is counted per row, not per step</b>, and the row buys the space
 *       ({@link LinkArt#railSpace}) rather than borrowing it from its neighbour.</li>
 *   <li><b>The tint brightens on hover</b>, so the whole link lights as one object.</li>
 * </ul>
 *
 * <p><b>Round 6 is still measured</b>: a {@link LinkJoin#LINE} pair paints no portal and runs its leg to the
 * partner's rail; the four anchor × join combinations really differ; and a resting link is <b>much</b> nearer
 * its own colour than the background, which is *"reduce default dimming"* as a number rather than an opinion.
 *
 * <p><b>Rounds 4 and 5 are still measured here</b>, because a fix nobody keeps measuring is a fix that comes
 * back: the hole is proved transparent by painting over a colour nothing else uses; the leg is proved to cross
 * it; both ovals are proved odd-width and exactly concentric on the <i>rendered pixels</i>, column by mirrored
 * column, with an even-width control that fails; the width is proved a real proportion of the row; and a token
 * end's ink is proved to begin on the token's own edge.
 *
 * <p>What it cannot answer needs a live editor: whether {@code offsetToXY} puts the channel where this bench
 * assumes inside a nested node, whether the cross-editor hover relay fires, whether the align group's
 * screen-origin basis survives a real nest, whether {@code targetBelowFor} agrees with what the reader sees,
 * and whether a link survives a fold. Those are the judged round's.
 *
 * <p>Node widths come from the caps in {@code TreeOfCode.rules.md}: 1584 px at the widest, 550 px at the narrowest.
 *
 * <p><b>Usage:</b> {@code java … LinkBench [outputDir]} — the PNGs go to {@code build/probe} unless a
 * directory is named, which is how a judged round gets them somewhere it can actually open them.
 */
public final class LinkBench {

    private static final int NARROW_NODE = 550;
    private static final int WIDE_NODE = 1584;
    private static final int ROW_H = 20;
    private static final Color BG = new Color(0x1E, 0x1F, 0x22);
    private static final Color FG = new Color(0xBC, 0xBE, 0xC4);
    private static final Color LIGHT_BG = new Color(0xFF, 0xFF, 0xFF);
    private static final Color LIGHT_FG = new Color(0x08, 0x08, 0x08);
    private static final Color GOLD = new Color(0xE0, 0xA8, 0x30);
    private static final Color GREY = new Color(0x80, 0x80, 0x80);

    /** A colour nothing in the drawing uses, so "still exactly this" means "nothing was painted here". */
    private static final Color PROBE_BG = new Color(0xFF, 0x00, 0xFF);

    /** Ink threshold: how far from the background a pixel must be to count as drawn on. */
    private static final int INK = 30;

    /** The route production ships, mirroring {@code SpotlightRenderer.LINK_ROUTE}. */
    private static final LinkRoute SHIPPED = LinkRoute.DIRECTIONAL;

    private static int pass = 0;
    private static int fail = 0;

    /** Ends the room check rejected. Reported, because a silently missing link looks like a bug. */
    private static int skipped = 0;

    /** What to put in an image: the code, the decorations, or both. Two passes is how the overlap is counted. */
    private enum Mode { TEXT, LINK, BOTH }

    /** One mock editor row: its code, and the token a link is anchored to (null = no link on this row). */
    private record Row(String code, String token, int pairId, int slot, boolean outgoing) {}

    /** Where the PNGs go: {@code build/probe}, or wherever the caller asked for. */
    private static String out = "build/probe";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && !args[0].isBlank()) out = args[0].replace('\\', '/').replaceAll("/+$", "");
        System.setProperty("java.awt.headless", "true");
        // Touch AWT before anything calls JBUI.scale. JBUIScale.computeSystemScaleFactor asserts
        // "Must be precomputed" if the graphics environment has not been initialised, and every measurement
        // in LinkArt goes through JBUI.scale — so without this the first assertion dies rather than fails.
        new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).createGraphics().dispose();

        header("the palette — colour is still the ONLY thing telling two links apart, and now there are five");
        check("the cap and the palette are one number", LinkArt.INSTANCE.slots() == 5,
                LinkArt.INSTANCE.slots() + " slots");
        for (Color accent : new Color[]{GOLD, GREY, new Color(0x4A, 0x90, 0xD9)}) {
            Color[] slots = palette(accent);
            double min = minPairwise(slots);
            double consec = minConsecutive(slots);
            check("five links separable from accent " + hex(accent), min >= 60.0,
                    String.format("worst of all ten pairs %.0f, worst consecutive %.0f", min, consec));
            // Consecutive slots are the ones a reader compares — two links off one row are 0 and 1 — so the
            // hue OFFSETS are ordered rather than stepped, and this is the claim that ordering makes.
            check("and the two a reader compares first are the furthest apart", consec >= min,
                    String.format("consecutive %.0f >= overall worst %.0f", consec, min));
        }
        check("slot 0 is the accent unchanged", LinkArt.INSTANCE.color(GOLD, 0).equals(GOLD),
                hex(LinkArt.INSTANCE.color(GOLD, 0)));
        check("slots wrap rather than running out",
                LinkArt.INSTANCE.color(GOLD, 5).equals(LinkArt.INSTANCE.color(GOLD, 0)), "slot 5 == slot 0");
        // The control: at the three-slot palette's 0.55 saturation floor a grey accent collapses, which is
        // both why the floor exists and why round 6 had to raise it when the palette grew to five.
        Color[] weak = new Color[5];
        float[] hsb = Color.RGBtoHSB(GREY.getRed(), GREY.getGreen(), GREY.getBlue(), null);
        float[] offs = {0f, 0.4f, 0.8f, 0.2f, 0.6f};
        for (int i = 0; i < 5; i++) {
            weak[i] = i == 0 ? GREY
                    : new Color(Color.HSBtoRGB((hsb[0] + offs[i]) % 1f, 0.55f, hsb[2]));
        }
        check("and WOULD NOT at the old 0.55 floor (so raising it was load-bearing)",
                minPairwise(weak) < 60.0, String.format("%.0f", minPairwise(weak)));

        header("DIMMING — 'too dim default link/portal tint … bring much closer to hovered version'");
        // Measured on the drawn pixels of the rail, over both themes, against the hovered version of the same
        // pixel: how much of the link's own colour survives at rest.
        for (Object[] theme : new Object[][]{{"dark", BG}, {"light", LIGHT_BG}}) {
            Color themeBg = (Color) theme[1];
            for (int slot = 0; slot < 5; slot++) {
                Color c = LinkArt.INSTANCE.color(GOLD, slot);
                Color rest = railColor(c, themeBg, false);
                Color hover = railColor(c, themeBg, true);
                double toHover = dist(rest, hover);
                double toBg = dist(rest, themeBg);
                check("slot " + slot + " at rest on " + theme[0] + " reads as the link, not as the background",
                        toBg > toHover * 2.0,
                        String.format("%.0f from background vs %.0f from hovered", toBg, toHover));
            }
        }
        // The control, and the reason this section exists: the previous 0.45 blend put a resting link nearer
        // the background than its own colour for at least one slot in at least one theme.
        int wouldFail = 0;
        for (Color themeBg : new Color[]{BG, LIGHT_BG}) {
            for (int slot = 0; slot < 5; slot++) {
                Color c = LinkArt.INSTANCE.color(GOLD, slot);
                Color old = blend(c, themeBg, 0.45);
                if (dist(old, themeBg) <= dist(old, c) * 2.0) wouldFail++;
            }
        }
        check("(the old 0.45 blend WOULD have failed that — this is the control)", wouldFail > 0,
                wouldFail + " of 10 slot/theme combinations");
        check("hovering still does something: it doubles the stroke as well as the colour",
                railInkThickness(GOLD, true) > railInkThickness(GOLD, false),
                railInkThickness(GOLD, false) + "px at rest, " + railInkThickness(GOLD, true) + "px hovered");

        header("the portal — an oval half as tall as the disc it replaces, plus a hole");
        Rectangle below = LinkArt.INSTANCE.outer(400, 30, ROW_H, true, SHIPPED);
        Rectangle above = LinkArt.INSTANCE.outer(400, 30, ROW_H, false, SHIPPED);
        check("is CENTRED on its lane's axis, not parked left of it (round 7)",
                below.x + below.width / 2 == 400 && above.x + above.width / 2 == 400,
                below.toString());
        check("round 5's size: 23x11 on a 20px row", below.width == 23 && below.height == 11,
                below.width + "x" + below.height);
        check("is flattened ~2x — an oval, not the round disc", below.width / 2 == below.height,
                below.width + "x" + below.height);
        check("a target BELOW parks it against the row's bottom bound",
                below.y + below.height == 30 + ROW_H / 2 - 1,
                "portal bottom " + (below.y + below.height) + ", row bottom " + (30 + ROW_H / 2));
        check("a target ABOVE parks it against the row's top bound", above.y == 30 - ROW_H / 2 + 1,
                "portal top " + above.y + ", row top " + (30 - ROW_H / 2));
        check("and either way it stays inside its own row, off the neighbours",
                above.y >= 30 - ROW_H / 2 && below.y + below.height <= 30 + ROW_H / 2,
                "above " + above.y + ".." + (above.y + above.height)
                        + ", below " + below.y + ".." + (below.y + below.height));
        check("so the two are in different places — the position IS the direction", below.y != above.y,
                "below y=" + below.y + " vs above y=" + above.y);
        Rectangle hole = LinkArt.INSTANCE.inner(below);
        check("the hole is ~1.75x smaller and concentric",
                hole.width == Math.round(below.width / 1.75f) && hole.getCenterX() == below.getCenterX(),
                hole.width + "x" + hole.height + " inside " + below.width + "x" + below.height);
        check("and never closes up: floored so the rings stay two rings",
                LinkArt.INSTANCE.inner(new Rectangle(0, 0, 4, 2)).width >= 3,
                "4x2 outer -> hole " + LinkArt.INSTANCE.inner(new Rectangle(0, 0, 4, 2)).width);
        // The room check, both sides of it, so a green tick cannot come from a check that says null to
        // everything. Round 6 measures it on the AXIS rather than the band's left edge — see MIN_RUN.
        check("an axis too close to the anchor is refused, not drawn on top of it",
                token(300, 300, 30, 310, true, false, LinkJoin.PORTAL, 0) == null, "10px of run");
        check("and one with room is drawn (so the refusal is a decision, not a constant)",
                token(300, 300, 30, 330, true, false, LinkJoin.PORTAL, 0) != null, "30px of run");
        // The everyday case: the LONGEST row of a step sits exactly one channelGap from the base column, and
        // its lane's axis is that plus the lane's own half-width — the thinnest of which is a same-editor
        // lane's 2px. Both joins have to clear MIN_RUN there, or the widest row of every step loses its link.
        for (LinkJoin join : LinkJoin.values()) {
            int axis = laneAxis(300 + LinkArt.INSTANCE.channelGap(), 0, lanes(join));
            check("a line end on the step's longest row still draws (" + join + ")",
                    lineEnd(300, 300, 30, axis, true, false, join, 0) != null,
                    "axis " + (axis - 300) + "px past the text end");
        }

        header("THE FAN — which rail an end takes off its own row, counted PER ROW");
        int step = LinkArt.INSTANCE.railStep(ROW_H);
        System.out.printf("       railStep(20)=%dpx   a fan of 5 reaches %dpx past the row's bound%n",
                step, step * 4 - 1);
        check("one pixel per fan is NOT what it does — a 2px hovered stroke needs more", step >= 3,
                step + "px per rail");
        check("and it scales with the row, like everything else",
                LinkArt.INSTANCE.railStep(40) > step, "row 40 -> " + LinkArt.INSTANCE.railStep(40) + "px");
        int[] dives = new int[5];
        int[] climbs = new int[5];
        for (int fan = 0; fan < 5; fan++) {
            dives[fan] = token(120, 200, 30, 400, true, false, LinkJoin.PORTAL, fan).getRailY();
            climbs[fan] = token(120, 200, 30, 400, false, false, LinkJoin.PORTAL, fan).getRailY();
        }
        boolean divesRise = true, climbsFall = true, allDistinct = true;
        for (int i = 1; i < 5; i++) {
            if (dives[i] != dives[i - 1] - step) divesRise = false;
            if (climbs[i] != climbs[i - 1] + step) climbsFall = false;
            for (int j = 0; j < i; j++) if (dives[i] == dives[j]) allDistinct = false;
        }
        check("a link that DIVES is routed x px higher per rail", divesRise, java.util.Arrays.toString(dives));
        check("a link that RISES is routed x px lower per rail", climbsFall, java.util.Arrays.toString(climbs));
        check("so five links off one row are on five different rails", allDistinct,
                "spread " + (dives[0] - dives[4]) + "px");
        check("and a diver's rail never lands on a riser's", disjoint(dives, climbs),
                "dives " + dives[4] + ".." + dives[0] + " vs climbs " + climbs[0] + ".." + climbs[4]);

        header("SPACE FOR THE FAN — 'add space above/below the line to make space for routes'");
        int rowTop = 30 - ROW_H / 2;
        int reach = rowTop - dives[4];
        System.out.printf("       a fan of 5 reaches %dpx above the row; railSpace(5) buys %dpx%n",
                reach, LinkArt.INSTANCE.railSpace(5, ROW_H));
        check("one rail costs the row nothing — it sits in the row's own leading",
                LinkArt.INSTANCE.railSpace(1, ROW_H) == 0, "0px for a single link");
        check("and five buy exactly what the outermost rail reaches, plus a clear pixel for a hovered stroke",
                LinkArt.INSTANCE.railSpace(5, ROW_H) >= reach + 2,
                LinkArt.INSTANCE.railSpace(5, ROW_H) + "px >= " + reach + "px + 2");
        check("nothing is bought that is not needed — the space grows one rail at a time",
                LinkArt.INSTANCE.railSpace(2, ROW_H) < LinkArt.INSTANCE.railSpace(5, ROW_H)
                        && LinkArt.INSTANCE.railSpace(2, ROW_H) > 0,
                "2 rails -> " + LinkArt.INSTANCE.railSpace(2, ROW_H) + "px, 5 -> "
                        + LinkArt.INSTANCE.railSpace(5, ROW_H) + "px");
        check("and it scales with the row", LinkArt.INSTANCE.railSpace(5, 40) > LinkArt.INSTANCE.railSpace(5, 20),
                "row 40 -> " + LinkArt.INSTANCE.railSpace(5, 40) + "px");
        // The control: round 6 reserved nothing at all, so the outermost rail was drawn inside the row above.
        check("(round 6 bought none of it, which is why a rail landed in the neighbouring row — the control)",
                reach > 0, "the fan really does leave the row by " + reach + "px");

        header("THE LANES — 'cannot overlap in sections … portals cannot overlap … not their portal'");
        int[] portalAxes = LinkArt.INSTANCE.laneAxes(lanes(LinkJoin.PORTAL, 5), ROW_H);
        int[] lineAxes = LinkArt.INSTANCE.laneAxes(lanes(LinkJoin.LINE, 5), ROW_H);
        System.out.printf("       5 portal lanes: %s  span %dpx%n", java.util.Arrays.toString(portalAxes),
                LinkArt.INSTANCE.laneSpan(lanes(LinkJoin.PORTAL, 5), ROW_H));
        System.out.printf("       5 line   lanes: %s  span %dpx%n", java.util.Arrays.toString(lineAxes),
                LinkArt.INSTANCE.laneSpan(lanes(LinkJoin.LINE, 5), ROW_H));
        boolean lineGap = true;
        for (int i = 1; i < 5; i++) if (lineAxes[i] - lineAxes[i - 1] < 10) lineGap = false;
        check("two same-editor verticals are 10-12px apart, as asked", lineGap,
                (lineAxes[1] - lineAxes[0]) + "px apart");
        boolean portalGap = true;
        for (int i = 1; i < 5; i++) {
            if (portalAxes[i] - portalAxes[i - 1] < LinkArt.INSTANCE.outerWidth(ROW_H)) portalGap = false;
        }
        check("two portals are more than a whole portal apart, so they cannot overlap", portalGap,
                (portalAxes[1] - portalAxes[0]) + "px apart, portal is "
                        + LinkArt.INSTANCE.outerWidth(ROW_H) + "px");
        // **Every layout of five**, because the mixed ones are the cases round 6 got wrong — and the rules
        // are the reader's own three, checked against the drawn rectangles rather than against the pitch.
        int layouts = 0, worstClear = Integer.MAX_VALUE;
        boolean noneOverlap = true, noneThrough = true;
        for (int bits = 0; bits < 32; bits++) {
            LinkJoin[] js = new LinkJoin[5];
            for (int i = 0; i < 5; i++) js[i] = (bits >> i & 1) == 0 ? LinkJoin.LINE : LinkJoin.PORTAL;
            java.util.List<LinkJoin> layout = lanes(js);
            layouts++;
            int[] axes = LinkArt.INSTANCE.laneAxes(layout, ROW_H);
            Rectangle[] boxes = new Rectangle[5];
            for (int i = 0; i < 5; i++) {
                LinkShape sh = token(10, 10, 30, laneAxis(0, i, layout) + 200, true, false, js[i], 0);
                boxes[i] = js[i] == LinkJoin.PORTAL ? sh.getOuter() : null;
            }
            for (int i = 0; i < 5; i++) {
                int axisI = axes[i] + 200;
                for (int j = 0; j < 5; j++) {
                    if (i == j) continue;
                    if (axes[i] == axes[j]) noneOverlap = false;
                    // Rule 3: a vertical may cross another link, but never pass through its portal.
                    if (boxes[j] != null && axisI >= boxes[j].x && axisI <= boxes[j].x + boxes[j].width) {
                        noneThrough = false;
                    }
                    // Rule 2: two portals never share a pixel column.
                    if (boxes[i] != null && boxes[j] != null && i < j
                            && boxes[i].x + boxes[i].width > boxes[j].x && boxes[j].x > boxes[i].x) {
                        noneOverlap = false;
                    }
                }
                if (i > 0) worstClear = Math.min(worstClear, axes[i] - axes[i - 1]);
            }
        }
        check("no two lanes share an x, in any of the 32 layouts of five", noneOverlap,
                layouts + " layouts, closest pair " + worstClear + "px");
        check("and no link's vertical passes through a portal that is not its own", noneThrough,
                "checked every ordered pair of every layout");
        // The width, and why the ordering the tree uses is worth having: the fan a five-link step needs
        // depends entirely on what its links are joined by.
        int span5line = LinkArt.INSTANCE.laneSpan(lanes(LinkJoin.LINE, 5), ROW_H);
        int span5portal = LinkArt.INSTANCE.laneSpan(lanes(LinkJoin.PORTAL, 5), ROW_H);
        int span3plus2 = LinkArt.INSTANCE.laneSpan(
                lanes(LinkJoin.LINE, LinkJoin.LINE, LinkJoin.LINE, LinkJoin.PORTAL, LinkJoin.PORTAL), ROW_H);
        System.out.printf("       fan width: 5 lines %dpx   3 lines + 2 portals %dpx   5 portals %dpx%n",
                span5line, span3plus2, span5portal);
        check("a lane costs what it carries — five same-editor links need a third of five portals",
                span5line * 2 < span5portal,
                String.format("%dpx vs %dpx — %.1fx", span5line, span5portal,
                        span5portal / (double) span5line));
        // The control, and the round's whole reason: round 6 gave every lane a portal's width, so the
        // five-link steps of link-fan.json — all of them same-editor — asked for three times what they need
        // and got clamped into one column in a 550px node.
        int oldPitch = LinkArt.INSTANCE.outerWidth(ROW_H) + 8;
        int oldSpan = oldPitch * 4 + LinkArt.INSTANCE.outerWidth(ROW_H);
        int codeEnds = 380, roomIn550 = NARROW_NODE - 40;
        int base = codeEnds + LinkArt.INSTANCE.channelGap();
        check("five same-editor links FIT in a 550px node past 380px of code",
                base + span5line <= roomIn550,
                "needs " + (base + span5line) + "px, room is " + roomIn550 + "px");
        check("(and WOULD NOT at round 6's uniform pitch — this is 'all 5 overlap' as arithmetic)",
                base + oldSpan > roomIn550,
                "would have needed " + (base + oldSpan) + "px, room is " + roomIn550 + "px");

        header("THE FOUR FORMS — anchor x join, and everything after the rail is one drawing");
        LinkShape tokenPortal = token(120, 200, 30, 400, true, false, LinkJoin.PORTAL, 0);
        LinkShape endPortal = lineEnd(120, 200, 30, 400, true, false, LinkJoin.PORTAL, 0);
        LinkShape tokenLine = token(120, 200, 30, 400, true, false, LinkJoin.LINE, 0);
        LinkShape endLine = lineEnd(120, 200, 30, 400, true, false, LinkJoin.LINE, 0);
        check("a TOKEN end starts already on the rail", tokenPortal.getAnchorY() == tokenPortal.getRailY(),
                "anchorY " + tokenPortal.getAnchorY() + " == railY " + tokenPortal.getRailY());
        check("a LINE_END end joined by a PORTAL starts on the row's MIDDLE and hops onto the rail",
                endPortal.getAnchorY() == endPortal.getRowMid() && endPortal.getHasHop()
                        && endPortal.getRailY() != endPortal.getRowMid(),
                "anchorY " + endPortal.getAnchorY() + ", railY " + endPortal.getRailY());
        check("and one joined by a LINE never leaves it — 'just turn to target', no U-turn (round 7)",
                endLine.getRailY() == endLine.getRowMid() && !endLine.getHasHop(),
                "railY " + endLine.getRailY() + " == rowMid " + endLine.getRowMid());
        check("both share one rail, so the finish into the portal is the same drawing",
                endPortal.getRailY() == tokenPortal.getRailY()
                        && endPortal.getCentreXf() == tokenPortal.getCentreXf(),
                "rail " + tokenPortal.getRailY() + ", axis " + tokenPortal.getCentreXf());
        check("a TOKEN end is flush with its tint; a LINE_END end keeps 3px off the last glyph",
                tokenPortal.getInkX() == 120 && endPortal.getInkX() == 120 + LinkArt.INSTANCE.lineEndStub(),
                "token ink " + tokenPortal.getInkX() + ", line-end ink " + endPortal.getInkX());
        check("a PORTAL end has a portal and a leg into it",
                tokenPortal.getHasPortal() && tokenPortal.getLegEndY() != tokenPortal.getRailY(),
                "leg " + tokenPortal.getRailY() + " -> " + tokenPortal.getLegEndY());
        check("a LINE end has neither — its leg length belongs to the pair, not to one end",
                !tokenLine.getHasPortal() && tokenLine.getLegEndY() == tokenLine.getRailY(),
                "legEndY == railY");
        check("a LINE end's portal rectangle is still computed — it is what the label clears",
                tokenLine.getCentreXf() == tokenPortal.getCentreXf(), "axis " + tokenLine.getCentreXf());
        check("and the anchor rule is independent of the join — all four combinations exist",
                endLine.getAnchor() == LinkAnchor.LINE_END && endLine.getJoin() == LinkJoin.LINE
                        && tokenPortal.getAnchor() == LinkAnchor.TOKEN,
                "TOKEN/LINE_END x LINE/PORTAL");
        header("CENTRE ROUTING — 'route them in the center of the line for the whole horizontal span'");
        // On the pixels: a line-end end must run along the row's MIDDLE for the whole span, where a token end
        // must place no ink there at all. Round 6 hopped at the text end and ran the span on the rail, so
        // this measurement is what tells the two rounds apart.
        int endRun = inkAlongRowMid(GOLD, LinkAnchor.LINE_END, LinkJoin.PORTAL);
        int tokenRun = inkAlongRowMid(GOLD, LinkAnchor.TOKEN, LinkJoin.PORTAL);
        check("a LINE_END end really runs along the row's middle, the whole way to its hop",
                endRun > 100, endRun + "px of ink on the row's middle");
        check("and a TOKEN end places none there — the code's own row stays clear",
                tokenRun == 0, tokenRun + "px");
        check("a same-editor line link runs the middle ALL the way to its lane and never climbs",
                inkAlongRowMid(GOLD, LinkAnchor.LINE_END, LinkJoin.LINE) > endRun,
                inkAlongRowMid(GOLD, LinkAnchor.LINE_END, LinkJoin.LINE) + "px vs " + endRun + "px");
        // The hop, and the whole point of moving it: it is the LAST uRun px, so every portal link has the
        // same run of rail before its turn whatever its row's length — 'the finishing part of a turn into
        // portal looks the same for all portal links'.
        LinkShape shortRow = lineEnd(60, 60, 30, 400, true, false, LinkJoin.PORTAL, 0);
        LinkShape longRow = lineEnd(340, 340, 30, 400, true, false, LinkJoin.PORTAL, 0);
        check("the hop happens uRun px short of the lane, however long the row is",
                Math.round(shortRow.getCentreXf() - shortRow.getHopX()) == LinkArt.INSTANCE.uRun()
                        && Math.round(longRow.getCentreXf() - longRow.getHopX()) == LinkArt.INSTANCE.uRun(),
                String.format("%.0fpx on a 60px row, %.0fpx on a 340px row",
                        shortRow.getCentreXf() - shortRow.getHopX(),
                        longRow.getCentreXf() - longRow.getHopX()));
        check("so a token end and a line end finish into the portal identically",
                shortRow.getRailY() == tokenPortal.getRailY()
                        && shortRow.getIndicatorX() == tokenPortal.getIndicatorX()
                        && shortRow.getLegEndY() == tokenPortal.getLegEndY(),
                "same rail, same head, same leg");

        header("THE POLYLINE — one derivation, so the hit band cannot drift from the ink");
        float[] tp = tokenPortal.points(tokenPortal.getLegEndY());
        float[] ep = endPortal.points(endPortal.getLegEndY());
        float[] el = endLine.points(endLine.getRailY() + ROW_H * 4);
        check("a token portal link is three points: out, turn, into the hole", tp.length == 6,
                (tp.length / 2) + " points");
        check("a line-end portal link is five — the trunk, the hop, the rail, the leg", ep.length == 10,
                (ep.length / 2) + " points");
        check("a same-editor line link is three — out, down, and that is the staple", el.length == 6,
                (el.length / 2) + " points");
        boolean axisAligned = true;
        for (float[] pts : new float[][]{tp, ep, el}) {
            for (int i = 0; i + 3 < pts.length; i += 2) {
                if (pts[i] != pts[i + 2] && pts[i + 1] != pts[i + 3]) axisAligned = false;
            }
        }
        check("every segment is axis-aligned, which is what lets hit() be exact", axisAligned,
                "no diagonals in any of the four forms");
        boolean everySegmentHit = true;
        for (int i = 0; i + 3 < ep.length; i += 2) {
            Point mid = new Point(Math.round((ep[i] + ep[i + 2]) / 2), Math.round((ep[i + 1] + ep[i + 3]) / 2));
            if (!LinkArt.INSTANCE.hit(endPortal, mid, endPortal.getLegEndY())) everySegmentHit = false;
        }
        check("and the middle of EVERY segment is hittable, including the hop round 7 added",
                everySegmentHit, "4 segments");

        header("SYMMETRY — round 5's 'it looks unsymmetric in horizontal direction', still measured");
        check("BOTH widths are odd, so (outer - inner) / 2 divides exactly",
                below.width % 2 == 1 && hole.width % 2 == 1,
                "outer " + below.width + ", hole " + hole.width);
        check("and both heights too", below.height % 2 == 1 && hole.height % 2 == 1,
                "outer " + below.height + ", hole " + hole.height);
        check("the ring is the same thickness left and right, to the pixel",
                hole.x - below.x == (below.x + below.width) - (hole.x + hole.width),
                (hole.x - below.x) + "px left, " + ((below.x + below.width) - (hole.x + hole.width)) + "px right");
        check("the axis is a HALF-integer, which is where a 1px stroke covers one whole column",
                tokenPortal.getCentreXf() % 1f == 0.5f, "centreXf=" + tokenPortal.getCentreXf());
        int worst = mirrorMismatch(GOLD, false);
        check("the RENDERED portal mirrors about that axis — column ink matches both sides", worst == 0,
                "worst column mismatch " + worst + "px");
        int evenWorst = mirrorMismatch(GOLD, true);
        check("and WOULD NOT at an even width (so the odd rule is load-bearing)", evenWorst > 0,
                "worst column mismatch " + evenWorst + "px with a 22px oval");

        header("FONT SCALING — round 5's 'scaling along with main editor font scaling', still measured");
        int w20 = LinkArt.INSTANCE.outerWidth(20);
        int w30 = LinkArt.INSTANCE.outerWidth(30);
        int w40 = LinkArt.INSTANCE.outerWidth(40);
        System.out.printf("       row 20 -> %dpx    row 30 -> %dpx    row 40 -> %dpx%n", w20, w30, w40);
        check("a bigger editor font gets a bigger portal", w30 > w20 && w40 > w30, w20 + " < " + w30 + " < " + w40);
        check("and it tracks the row rather than creeping — ~115% of the line height each time",
                Math.abs(w30 - 30 * 115 / 100) <= 1 && Math.abs(w40 - 40 * 115 / 100) <= 1,
                "row 30 wants " + (30 * 115 / 100) + ", got " + w30);
        check("every one of them is still odd", w20 % 2 == 1 && w30 % 2 == 1 && w40 % 2 == 1,
                w20 + ", " + w30 + ", " + w40);
        java.util.List<LinkJoin> one = lanes(LinkJoin.PORTAL);
        check("the room check grew with it, so a large font falls back instead of clipping",
                LinkArt.INSTANCE.minRoom(one, 40) > LinkArt.INSTANCE.minRoom(one, 20)
                        && LinkArt.INSTANCE.minRoom(one, 20) > w20,
                "row 20 needs " + LinkArt.INSTANCE.minRoom(one, 20)
                        + ", row 40 needs " + LinkArt.INSTANCE.minRoom(one, 40));
        check("(the previous cap of 18 was BELOW the default, which is why nothing scaled)", w20 > 18,
                "default width " + w20 + " > old cap 18");
        check("a pathological line height is still bounded", LinkArt.INSTANCE.outerWidth(400) <= 117,
                "row 400 -> w=" + LinkArt.INSTANCE.outerWidth(400));

        header("AN OBSTRUCTION THE FAN CANNOT GET PAST — K5's far-right routing");
        // A LINE leg is routed clear of every block inlay it crosses, because an inlay paints over it. K5
        // found the rule paying that price where it cannot buy anything: a nested code node is an inlay as
        // wide as the editor, so no lane clears it and the demand only pins the fanWidth against the viewport.
        //
        // The two obstructions are the two real ones, at the geometry each actually has, and the assertion is
        // that they come out DIFFERENTLY — the predicate has to keep the case it was written for.
        java.util.List<LinkJoin> threeLanes = lanes(LinkJoin.LINE, LinkJoin.LINE, LinkJoin.PORTAL);
        int channelRoom = 1200;                 // a node's usable right edge, in its own content coordinates
        int calloutRight = 420;                 // our own box: sized to its text, most of the row beyond it
        int nestedRight = 1190;                 // a nested node: the editor's width, less its inset
        int fanWidth = LinkArt.INSTANCE.minRoom(threeLanes, ROW_H);
        System.out.printf("       room %d, fan needs %d — callout ends %d, nested node ends %d%n",
                channelRoom, fanWidth, calloutRight, nestedRight);
        check("a callout box is cleared, because the fan still fits past it",
                LinkArt.INSTANCE.obstructionClearable(calloutRight, threeLanes, ROW_H, channelRoom),
                calloutRight + " + " + fanWidth + " <= " + channelRoom);
        check("a nested node is NOT, because nothing fits past it",
                !LinkArt.INSTANCE.obstructionClearable(nestedRight, threeLanes, ROW_H, channelRoom),
                nestedRight + " + " + fanWidth + " > " + channelRoom);
        // The boundary, from both sides and one pixel apart — so the predicate is the arithmetic it claims to
        // be rather than a threshold that happens to sort these two numbers.
        check("the boundary is exactly 'the fan fits': the last x that clears",
                LinkArt.INSTANCE.obstructionClearable(channelRoom - fanWidth, threeLanes, ROW_H, channelRoom),
                "x=" + (channelRoom - fanWidth));
        check("and the first that does not, one pixel on",
                !LinkArt.INSTANCE.obstructionClearable(channelRoom - fanWidth + 1, threeLanes, ROW_H, channelRoom),
                "x=" + (channelRoom - fanWidth + 1));
        // And it is not a constant dressed up: a wider fanWidth clears less, which is what makes it a *fanWidth* test.
        java.util.List<LinkJoin> fiveWide = lanes(LinkJoin.PORTAL, LinkJoin.PORTAL, LinkJoin.PORTAL,
                LinkJoin.PORTAL, LinkJoin.PORTAL);
        check("CONTROL: the same x that clears for three lanes need not clear for five portals",
                LinkArt.INSTANCE.minRoom(fiveWide, ROW_H) > fanWidth
                        && !LinkArt.INSTANCE.obstructionClearable(
                                channelRoom - fanWidth, fiveWide, ROW_H, channelRoom),
                "five portals need " + LinkArt.INSTANCE.minRoom(fiveWide, ROW_H) + ", three need " + fanWidth);

        header("the rail — the side the connector runs down is the direction it points");
        LinkShape sb = token(120, 200, 30, 400, true, false, LinkJoin.PORTAL, 0);
        LinkShape sa = token(120, 200, 30, 400, false, false, LinkJoin.PORTAL, 0);
        check("a target BELOW runs the rail along the row's TOP", sb.getRailY() < 30,
                "railY=" + sb.getRailY() + " rowMid=30");
        check("a target ABOVE runs it along the BOTTOM", sa.getRailY() > 30, "railY=" + sa.getRailY());
        check("neither runs it through the row's middle, where the code is",
                sb.getRailY() != 30 && sa.getRailY() != 30, "both off-centre");
        check("the leg travels toward the portal and stops inside the hole",
                sb.getLegEndY() > sb.getRailY() && sa.getLegEndY() < sa.getRailY(),
                "below " + sb.getRailY() + "->" + sb.getLegEndY() + ", above " + sa.getRailY() + "->" + sa.getLegEndY());
        check("and it stops at the hole's FAR edge, not on the rim",
                sb.getLegEndY() == sb.getInner().y + sb.getInner().height
                        && sa.getLegEndY() == sa.getInner().y,
                "below ends at hole bottom, above at hole top");

        header("the hole is REALLY a hole, and the leg is REALLY inside it");
        BufferedImage over = onProbeBackground(GOLD, true, false);
        LinkShape ps = token(4, 4, 30, 40, true, false, LinkJoin.PORTAL, 0);
        Rectangle po = ps.getOuter();
        Rectangle ph = ps.getInner();
        int cx = ps.getCentreX();
        int holeX = ph.x + 1;
        check("the hole's own pixels are untouched — 'central fully transparent oval'",
                over.getRGB(holeX, (int) ph.getCenterY()) == PROBE_BG.getRGB(),
                "hole pixel " + hex(new Color(over.getRGB(holeX, (int) ph.getCenterY()))));
        check("but the annulus around it IS painted — so there are two rings",
                over.getRGB(po.x + 1, (int) po.getCenterY()) != PROBE_BG.getRGB(),
                "annulus pixel " + hex(new Color(over.getRGB(po.x + 1, (int) po.getCenterY()))));
        check("and the leg crosses the hole's centre — the line goes INTO the portal",
                over.getRGB(cx, (int) ph.getCenterY()) != PROBE_BG.getRGB(),
                "centre pixel " + hex(new Color(over.getRGB(cx, (int) ph.getCenterY()))));
        check("(the same two facts cannot both hold for a filled portal — this is the control)",
                over.getRGB(holeX, (int) ph.getCenterY()) == PROBE_BG.getRGB()
                        && over.getRGB(cx, (int) ph.getCenterY()) != PROBE_BG.getRGB(),
                "hole clear + leg present");

        header("the direction indicator — one head, on the rail, for all four forms");
        int outAhead = railThickness(GOLD, false, +2, LinkAnchor.TOKEN);
        int outBehind = railThickness(GOLD, false, -2, LinkAnchor.TOKEN);
        int inAhead = railThickness(GOLD, true, +2, LinkAnchor.TOKEN);
        int inBehind = railThickness(GOLD, true, -2, LinkAnchor.TOKEN);
        check("there is a head on the rail, thicker than the 1px line it sits on",
                Math.max(outBehind, outAhead) >= 3, "widest column " + Math.max(outBehind, outAhead) + "px");
        check("OUTGOING points at the channel — its base is the side away from it", outBehind > outAhead,
                "behind=" + outBehind + "px, ahead=" + outAhead + "px");
        check("INCOMING points away from it — mirrored", inAhead > inBehind,
                "behind=" + inBehind + "px, ahead=" + inAhead + "px");
        check("it is the in-file arrow's 7px, not the timid 5", Math.max(outBehind, outAhead) >= 6,
                "base measures " + Math.max(outBehind, outAhead) + "px tall");
        // The reader's 'the top (or the bottom) of the U-turn contains the direction indicator': a line-end
        // form must carry the same head in the same place, because the rail is shared.
        int endBehind = railThickness(GOLD, false, -2, LinkAnchor.LINE_END);
        check("a LINE_END link carries the same head on the same rail — the U-turn's apex", endBehind >= 6,
                "base measures " + endBehind + "px tall");
        check("its leading edge sits ~8px short of the turn, so a bigger head cannot reach the bend",
                Math.round(sb.getCentreXf() - (sb.getIndicatorX() + 7 / 2f)) == 8,
                "turn at " + sb.getCentreXf() + ", head edge at " + (sb.getIndicatorX() + 3.5f));
        // **The defect the taxonomy panel showed**, and the reason CHANNEL_GAP moved: a line end on the
        // LONGEST row of a step sits one channel gap from its own channel and spends the first few pixels of
        // rail turning onto it, so a fixed 8px gap plus two heads of clearance did not fit — and the one form
        // the reader asked to be made uniform was the one form with no indicator at all. Both directions,
        // because the incoming end also spends 7px on the arrowhead at its anchor.
        for (boolean incoming : new boolean[]{false, true}) {
            int text = 300;
            LinkShape tight = lineEnd(text, text, 30,
                    laneAxis(text + LinkArt.INSTANCE.channelGap(), 0, lanes(LinkJoin.PORTAL)), true, incoming,
                    LinkJoin.PORTAL, 0);
            check("a line end on the step's LONGEST row still gets a head ("
                            + (incoming ? "incoming" : "outgoing") + ")",
                    tight != null && tight.getHasIndicator(),
                    tight == null ? "no shape" : String.format("%.1fpx of rail", tight.getRailRun()));
            check("  and it stays clear of both the turn onto the rail and the turn into the channel",
                    tight != null
                            && tight.getIndicatorX() - 3.5f >= tight.getRailStartX()
                            && tight.getIndicatorX() + 3.5f <= tight.getCentreXf(),
                    tight == null ? "no shape" : String.format("rail %.1f..%.1f, head %.1f..%.1f",
                            tight.getRailStartX(), tight.getCentreXf(),
                            tight.getIndicatorX() - 3.5f, tight.getIndicatorX() + 3.5f));
        }
        // The control: a run genuinely too short still refuses, so the shrink is a decision and not "always".
        // An INCOMING line end at the shortest run MIN_RUN allows — it spends 7px on the arrowhead at its
        // anchor and 4 more turning onto the rail, which is what leaves it too little to put a head on.
        LinkShape cramped = lineEnd(300, 300, 30, 324, true, true, LinkJoin.PORTAL, 0);
        check("(a genuinely cramped rail still refuses a head — the shrink has a floor)",
                cramped != null && !cramped.getHasIndicator(),
                cramped == null ? "no shape" : String.format("%.1fpx of rail", cramped.getRailRun()));

        header("FLUSH against the token — round 5's 'remove the space between the line and the highlight'");
        check("an outgoing token end's connector starts on the token's own edge",
                inkStartsAt(GOLD, false, LinkAnchor.TOKEN) == 30,
                "first ink at x=" + inkStartsAt(GOLD, false, LinkAnchor.TOKEN) + ", asked for 30");
        check("and an incoming one's arrowhead tip lands on it too",
                inkStartsAt(GOLD, true, LinkAnchor.TOKEN) == 30,
                "first ink at x=" + inkStartsAt(GOLD, true, LinkAnchor.TOKEN) + ", asked for 30");
        check("a line end deliberately does NOT — 3px keeps the turn off the last glyph",
                inkStartsAt(GOLD, false, LinkAnchor.LINE_END) == 30 + LinkArt.INSTANCE.lineEndStub(),
                "first ink at x=" + inkStartsAt(GOLD, false, LinkAnchor.LINE_END));

        header("the hit band — four regions, because a click NAVIGATES");
        LinkShape h = token(120, 200, 30, 400, true, false, LinkJoin.PORTAL, 0);
        check("the portal is hittable",
                LinkArt.INSTANCE.hit(h, new Point(h.getOuter().x + 4, (int) h.getOuter().getCenterY()), h.getLegEndY()),
                "on the portal");
        check("the rail is hittable along its length",
                LinkArt.INSTANCE.hit(h, new Point(260, h.getRailY()), h.getLegEndY()), "(260," + h.getRailY() + ")");
        check("and the leg between them",
                LinkArt.INSTANCE.hit(h, new Point(h.getCentreX(), 30), h.getLegEndY()),
                "(" + h.getCentreX() + ",30)");
        LinkShape he = lineEnd(120, 200, 30, 400, true, false, LinkJoin.PORTAL, 0);
        check("a line end's trunk along the row's middle is hittable",
                LinkArt.INSTANCE.hit(he, new Point(he.getInkX() + 40, he.getRowMid()), he.getLegEndY()),
                "(" + (he.getInkX() + 40) + "," + he.getRowMid() + ")");
        check("and so is the hop it makes onto the rail",
                LinkArt.INSTANCE.hit(he, new Point(Math.round(he.getHopX()),
                        (he.getRowMid() + he.getRailY()) / 2), he.getLegEndY()),
                String.format("(%.0f,%d)", he.getHopX(), (he.getRowMid() + he.getRailY()) / 2));
        // A joined pair's leg runs to the PARTNER's rail, and that is the region a hover has to find.
        LinkShape ja = token(120, 200, 30, 400, true, false, LinkJoin.LINE, 0);
        LinkShape jb = token(130, 200, 30 + ROW_H * 6, 400, false, true, LinkJoin.LINE, 0);
        check("a joined pair's leg is hittable all the way to the partner's rail",
                LinkArt.INSTANCE.hit(ja, new Point(ja.getCentreX(), 30 + ROW_H * 3), jb.getRailY()),
                "midway between the two rows");
        check("and NOT past it (the region is the ink, not the column)",
                !LinkArt.INSTANCE.hit(ja, new Point(ja.getCentreX(), 30 + ROW_H * 9), jb.getRailY()),
                "three rows below the partner");
        check("the row's MIDDLE over the code is NOT hit — clicks on code still reach the code",
                !LinkArt.INSTANCE.hit(h, new Point(260, 30), h.getLegEndY()),
                "(260,30) between token and channel");
        check("the row below is NOT hit",
                !LinkArt.INSTANCE.hit(h, new Point(260, 30 + ROW_H + 2), h.getLegEndY()), "one row down");
        check("left of the anchor is NOT hit",
                !LinkArt.INSTANCE.hit(h, new Point(60, h.getRailY()), h.getLegEndY()), "(60,rail)");
        check("right of the portal is NOT hit",
                !LinkArt.INSTANCE.hit(h, new Point(h.getOuter().x + h.getOuter().width + 20, 30), h.getLegEndY()),
                "past it");

        header("the hovered label — 'the label text for the portal links could be visible near the portal'");
        Font labelFont = new Font(Font.SANS_SERIF, Font.ITALIC, 12);
        FontMetrics labelFm = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
                .createGraphics().getFontMetrics(labelFont);
        BufferedImage labelImg = new BufferedImage(700, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D lg = labelImg.createGraphics();
        boolean drew = LinkArt.INSTANCE.paintLabel(lg, h, "the amount handed down", GOLD,
                labelFont, labelFm, 690, 0, 0, BG);
        boolean refused = LinkArt.INSTANCE.paintLabel(lg, h, "the amount handed down", GOLD,
                labelFont, labelFm, h.getOuter().x + h.getOuter().width + 20, 0, 0, BG);
        lg.dispose();
        check("it draws when there is room right of the portal", drew, "690px of room");
        check("and refuses rather than ellipsising to nothing when there is not", !refused, "20px of room");
        // Round 7's item 7: it used to sit on the RAIL, so a diving link labelled itself a row's leading
        // above the code it named. Measured on the drawn glyphs, not on the call.
        int[] band = labelInkBand(h, "the amount handed down", labelFont, labelFm);
        int labelMid = (band[0] + band[1]) / 2;
        check("and its text is centred on the row's MIDDLE, not on the rail it turned off",
                Math.abs(labelMid - h.getRowMid()) < Math.abs(labelMid - h.getRailY()),
                "ink " + band[0] + ".." + band[1] + ", rowMid " + h.getRowMid() + ", rail " + h.getRailY());
        check("(the rail is far enough from the middle for that to be a real distinction — the control)",
                Math.abs(h.getRowMid() - h.getRailY()) >= 8,
                Math.abs(h.getRowMid() - h.getRailY()) + "px apart");
        // Item 7's other half: a same-editor link is drawn by one renderer for both its ends, and it draws
        // the same label through the same call — nothing about paintLabel is portal-only.
        BufferedImage lineLabelImg = new BufferedImage(700, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D llg = lineLabelImg.createGraphics();
        boolean drewLine = LinkArt.INSTANCE.paintLabel(llg, token(120, 200, 30, 400, true, false,
                LinkJoin.LINE, 0), "the amount handed down", GOLD, labelFont, labelFm, 690, 0, 0, BG);
        llg.dispose();
        check("a SAME-EDITOR link labels itself through the same call — 'only portal links do' was the bug",
                drewLine, "LinkJoin.LINE draws its label too");
        // **Where the label goes** — round 8 chose it, round 11 reweighed it; see THE LABEL'S OWN END
        // below for the rule and LinkArt.paintLabel for why. Here: it draws in both positions, and in
        // neither does it vanish.
        LinkShape inner = token(120, 200, 30, 300, true, false, LinkJoin.PORTAL, 0);
        int farFan = 560;
        int wide = 900, narrow = 600;
        int shownWide = labelInkWidth(inner, "the order this is priced for", labelFont, labelFm, wide,
                farFan, 1000);
        int shownNarrow = labelInkWidth(inner, "the order this is priced for", labelFont, labelFm, narrow,
                farFan, 1000);
        System.out.printf("       label ink: %dpx of room past the fan -> %dpx drawn;  %dpx -> %dpx drawn%n",
                wide - farFan, shownWide, narrow - farFan, shownNarrow);
        check("with room past the fan the label can be drawn clear of every lane", shownWide > 0,
                shownWide + "px of glyph ink");
        check("with no room past the fan it falls back beside its own end rather than vanishing",
                shownNarrow > 0, shownNarrow + "px of glyph ink at " + narrow + "px of room");
        check("(refusing to leave its own lane WOULD have lost it — the control)",
                narrow - (farFan + 8) < 60, (narrow - farFan - 8) + "px is under the 60px minimum");
        // The backdrop: the label is never over blank canvas — in a clamped node it lies on the code.
        check("the label carries a backdrop, so it reads wherever it lands",
                labelBackdrop(inner, "the order this is priced for", labelFont, labelFm),
                "pixels behind the glyphs differ from the canvas");

        header("THE LABEL'S OWN END — 'short link text feels detached'");
        // The reader's round-11 numbers: two same-editor links on one step, one turning ~520px out and the
        // other ~820px, and BOTH labels started at ~820 — beside the furthest link rather than beside their
        // own. The note is a real one, off `link-fan.json`.
        String note = "the shortest reach of the five - two 49-column rows, so this lane routes furthest left";
        LinkShape near = token(120, 200, 30, 520, true, false, LinkJoin.LINE, 0);
        int ownEnd = near.getOuter().x + near.getOuter().width + 8;
        int spreadSpan = LinkArt.INSTANCE.laneSpan(lanes(LinkJoin.LINE, 2), ROW_H);
        int farLane = 822;                 // the other lane's own right edge, 300px further out
        int atOwn = labelInkLeft(near, note, labelFont, labelFm, 1300, farLane, spreadSpan);
        int atFan = labelInkLeft(near, note, labelFont, labelFm, 1300, farLane, 1000);
        System.out.printf("       label x: own end %d, spread fan -> %d, cheap fan -> %d, far lane %d%n",
                ownEnd, atOwn, atFan, farLane);
        check("a short link's label starts beside its OWN end, not beside the furthest lane's",
                atOwn > 0 && Math.abs(atOwn - ownEnd) <= 8,
                "ink starts at " + atOwn + ", own end at " + ownEnd + ", far lane at " + farLane);
        check("(the same call, told the fan is cheap to clear, still goes out past it — the control)",
                atFan > farLane, "ink starts at " + atFan);
        // Why it also answers round 8's own report: `mine` can never have less room than `clear`.
        int shownOwn = labelInkWidth(near, note, labelFont, labelFm, 1000, farLane, spreadSpan);
        int shownFan = labelInkWidth(near, note, labelFont, labelFm, 1000, farLane, 1000);
        check("  and beside its own end it shows MORE of its words — 'labels are always trimmed' again",
                shownOwn > shownFan, shownOwn + "px of glyph ink beside its end vs " + shownFan + "px past the fan");

        // A BUNCHED fan is the case round 8 was right about: five lanes at minimum pitch are 52px across,
        // so getting past all of them costs 38px and crosses no line at all.
        java.util.List<LinkJoin> bunchedFan = lanes(LinkJoin.LINE, 5);
        int bunchedSpan = LinkArt.INSTANCE.laneSpan(bunchedFan, ROW_H);
        int bunchedRight = 520 + LinkArt.INSTANCE.laneRightEdge(
                bunchedFan, LinkArt.INSTANCE.laneAxes(bunchedFan, ROW_H), ROW_H) - 2;
        int atBunched = labelInkLeft(near, note, labelFont, labelFm, 1300, bunchedRight, bunchedSpan);
        check("a bunched fan is still stepped past, because that is cheaper than the fan is wide",
                atBunched > bunchedRight,
                "ink starts at " + atBunched + ", fan ends at " + bunchedRight + ", span " + bunchedSpan);
        check("  unless stepping past leaves under the 60px minimum, when it goes back beside its own end",
                labelInkLeft(near, note, labelFont, labelFm, bunchedRight + 40, bunchedRight, bunchedSpan)
                        < bunchedRight, "40px past the fan is not a label");
        check("the OUTERMOST lane is unmoved either way — its own end is already past every lane",
                labelInkLeft(near, note, labelFont, labelFm, 1300, 522, 0)
                        == labelInkLeft(near, note, labelFont, labelFm, 1300, 522, 1000),
                "one lane, and 'past the fan' and 'beside its end' are the same x");

        header("THE HOVERED TINT — 'the tint should ALSO become bit brighter on link-hover'");
        for (Object[] theme : new Object[][]{{"dark", BG}, {"light", LIGHT_BG}}) {
            Color themeBg = (Color) theme[1];
            boolean allBrighter = true, allReadable = true;
            for (int lane = 0; lane < 5; lane++) {
                Color c = LinkArt.INSTANCE.color(GOLD, lane);
                Color rest = LinkArt.INSTANCE.tint(c, themeBg);
                Color hot = LinkArt.INSTANCE.tintHovered(c, themeBg);
                if (dist(hot, themeBg) <= dist(rest, themeBg)) allBrighter = false;
                // Still a background behind live code: it must not reach the connector's own weight.
                if (dist(hot, themeBg) >= dist(railColor(c, themeBg, true), themeBg)) allReadable = false;
            }
            check("every lane's token tint moves further from the " + theme[0] + " background on hover",
                    allBrighter, "5 lanes");
            check("  and still stays behind the connector's own weight, so the code reads through it",
                    allReadable, "tint < link");
            // **Round 8 turned it down**: *"Highlight brightening on-hover is too bright … in this
            // brightness level makes token text not visible."* The measurable form of "still a background"
            // is that the hovered tint stays nearer the background than the link's own colour — under half
            // of the way. Round 7's 0.58 was past that mark by construction, so this check is the one that
            // would have caught it, and it needs no reconstruction of the old constant to say so.
            Color lane0 = LinkArt.INSTANCE.color(GOLD, 0);
            double full = dist(lane0, themeBg);
            double rest0 = dist(LinkArt.INSTANCE.tint(lane0, themeBg), themeBg);
            double hot0 = dist(LinkArt.INSTANCE.tintHovered(lane0, themeBg), themeBg);
            System.out.printf("       %-5s tint: rest %.0f%%  hover %.0f%%  of the link's own colour%n",
                    theme[0], 100 * rest0 / full, 100 * hot0 / full);
            check("  the " + theme[0] + " hovered tint stays under half way to the link's own colour",
                    hot0 < full / 2,
                    String.format("%.0f%% of %.0f — round 7's 0.58 blend was 58%%", hot0, full));
            check("  and it is still a visible lift over the resting tint", hot0 > rest0 * 1.15,
                    String.format("%.0f -> %.0f", rest0, hot0));
        }

        header("vertical alignment — two editors, two origins, ONE column");
        int originA = 100, originB = 128, demandA = 300, demandB = 260;
        int[] demands = {originA + demandA, originB + demandB};
        // Both nodes wide enough for the fan, so the fit constraint is slack and only the clear one bites.
        int[] rooms = {originA + 1200, originB + 1200};
        java.util.List<LinkJoin> fanLayout = lanes(LinkJoin.PORTAL, 5);
        int fanSpan = LinkArt.INSTANCE.laneSpan(fanLayout, ROW_H);
        int[] axesA = groupAxes(originA, fanLayout, demands, rooms, 0);
        int[] axesB = groupAxes(originB, fanLayout, demands, rooms, 0);
        boolean sameColumn = true;
        for (int lane = 0; lane < 5; lane++) {
            if (originA + axesA[lane] != originB + axesB[lane]) sameColumn = false;
        }
        check("every lane lands on the same screen x in both editors", sameColumn,
                "lane0 A=" + (originA + axesA[0]) + " B=" + (originB + axesB[0]));
        check("and WOULD NOT without the group (so the group is load-bearing)",
                originA + demandA != originB + demandB,
                "own: A=" + (originA + demandA) + " B=" + (originB + demandB));
        check("no lane is left of what a node asked to be clear of",
                axesA[0] - LinkArt.INSTANCE.laneHalfExtent(LinkJoin.PORTAL, ROW_H) >= demandA,
                "lane 0 edge " + (axesA[0] - LinkArt.INSTANCE.laneHalfExtent(LinkJoin.PORTAL, ROW_H))
                        + " >= " + demandA);
        check("an empty layout says so rather than answering 0",
                LinkArt.INSTANCE.alignedLaneAxes(originA, lanes(), new int[0], 9999, 0, ROW_H) == null, "null");
        // **Round 7's addition, and round 6's item 8**: the narrow node could not fit the fan at the wide
        // node's demand, so it clamped — its portal moved left and the two ends stopped lining up. The
        // column now takes the narrow node's room into account, so BOTH ends move together or neither does.
        int[] tightRooms = {originA + 1200, originB + demandB + fanSpan - 30};
        int[] fitA = groupAxes(originA, fanLayout, demands, tightRooms, 0);
        int[] fitB = groupAxes(originB, fanLayout, demands, tightRooms, 0);
        check("a node too narrow for the fan pulls the SHARED fan left, rather than clamping alone",
                originA + fitA[0] == originB + fitB[0] && fitA[0] < axesA[0],
                "shared " + (originA + fitA[0]) + ", was " + (originA + axesA[0]));
        check("and the columns it lands on are ones the narrow node can hold the whole fan in",
                originB + LinkArt.INSTANCE.laneRightEdge(fanLayout, fitB, ROW_H) <= tightRooms[1],
                (originB + LinkArt.INSTANCE.laneRightEdge(fanLayout, fitB, ROW_H)) + " <= " + tightRooms[1]);
        // The control: clamping per node — which is what round 6 did — puts the two ends on different columns.
        int clampedB = Math.min(originB + demandB, tightRooms[1] - fanSpan);
        check("(clamping each node on its own WOULD have split them — this is the control)",
                originA + axesA[0] != clampedB,
                "A at " + (originA + axesA[0]) + ", B clamped to " + clampedB);
        // And the case round 6 could not express at all: a node holding a same-editor link AND a portal, and
        // a node holding only that portal. The layout is the STEP's, so both put the portal on lane 1.
        java.util.List<LinkJoin> mixed = lanes(LinkJoin.LINE, LinkJoin.PORTAL);
        int[] mixedA = groupAxes(originA, mixed, demands, rooms, 0);
        int[] mixedB = groupAxes(originB, mixed, demands, rooms, 0);
        check("a node with an extra same-editor link still puts the shared portal on the shared column",
                originA + mixedA[1] == originB + mixedB[1], "both at " + (originA + mixedA[1]));

        header("PER-LANE ROUTING — 'route them nearer to the left … evading columns taken by other links'");
        // Round 7 gave the whole step ONE demand — the widest row any of its links touched — so a link off a
        // short row was routed out past a long one and the hovered label after it had nothing left to be
        // drawn in. Here each lane answers for its own link's rows.
        java.util.List<LinkJoin> mix5 =
                lanes(LinkJoin.LINE, LinkJoin.LINE, LinkJoin.LINE, LinkJoin.PORTAL, LinkJoin.PORTAL);
        int[] perLane = {520, 180, 300, 210, 210};
        int[] uniform = new int[5];
        java.util.Arrays.fill(uniform, 520);              // what round 7 would have used: the widest row
        int[] axesNew = LinkArt.INSTANCE.laneAxes(mix5, perLane, ROW_H);
        int[] axesOld = LinkArt.INSTANCE.laneAxes(mix5, uniform, ROW_H);
        int rightNew = LinkArt.INSTANCE.laneRightEdge(mix5, axesNew, ROW_H);
        int rightOld = LinkArt.INSTANCE.laneRightEdge(mix5, axesOld, ROW_H);
        System.out.printf("       per-lane %s  right edge %d%n", java.util.Arrays.toString(axesNew), rightNew);
        System.out.printf("       one demand %s  right edge %d%n", java.util.Arrays.toString(axesOld), rightOld);
        check("a link off a short row routes left of one off a long row", axesNew[1] < axesNew[0],
                "lane 1 (demand 180) at " + axesNew[1] + ", lane 0 (demand 520) at " + axesNew[0]);
        check("the fan ends further left than one demand for the whole step would have put it",
                rightNew < rightOld,
                rightNew + "px vs " + rightOld + "px — " + (rightOld - rightNew) + "px more label room");
        boolean clearsOwn = true, portalsOutside = true;
        for (int i = 0; i < 5; i++) {
            int half = LinkArt.INSTANCE.laneHalfExtent(mix5.get(i), ROW_H);
            if (axesNew[i] - half < perLane[i]) clearsOwn = false;
            if (mix5.get(i) != LinkJoin.PORTAL) continue;
            for (int j = 0; j < 5; j++) {
                if (mix5.get(j) == LinkJoin.PORTAL) continue;
                int lh = LinkArt.INSTANCE.laneHalfExtent(mix5.get(j), ROW_H);
                if (axesNew[i] - half <= axesNew[j] + lh) portalsOutside = false;
            }
        }
        check("and no lane is routed left of its own code, whatever the reorder", clearsOwn,
                "all 5 clear their own demand");
        check("portals stay outside every same-editor lane, whatever the demands say", portalsOutside,
                "the judged turn order survives the reorder");
        // **The three rules again, this time with demands that reorder the columns.** The 32-layout sweep
        // above runs at zero demand, where column order IS lane order; a reorder that broke a rule would
        // pass it and fail here.
        int reordered = 0, worstDemandClear = Integer.MAX_VALUE;
        boolean dOverlap = false, dThrough = false;
        for (int bits = 0; bits < 32; bits++) {
            LinkJoin[] js = new LinkJoin[5];
            for (int i = 0; i < 5; i++) js[i] = (bits >> i & 1) == 0 ? LinkJoin.LINE : LinkJoin.PORTAL;
            java.util.List<LinkJoin> layout = lanes(js);
            for (int v = 0; v < 8; v++) {
                int[] d = new int[5];
                for (int i = 0; i < 5; i++) d[i] = 200 + (i * 97 + v * 61 + bits * 13) % 400;
                int[] a = LinkArt.INSTANCE.laneAxes(layout, d, ROW_H);
                // Did this vector actually reorder the columns? If none ever did, the sweep proves nothing.
                for (int i = 1; i < 5; i++) if (a[i] < a[i - 1]) { reordered++; break; }
                Rectangle[] boxes = new Rectangle[5];
                for (int i = 0; i < 5; i++) {
                    LinkShape sh = token(10, 10, 30, a[i] + 200, true, false, js[i], 0);
                    boxes[i] = js[i] == LinkJoin.PORTAL ? sh.getOuter() : null;
                }
                for (int i = 0; i < 5; i++) {
                    for (int j = 0; j < 5; j++) {
                        if (i == j) continue;
                        if (a[i] == a[j]) dOverlap = true;
                        if (boxes[j] != null && a[i] + 200 >= boxes[j].x
                                && a[i] + 200 <= boxes[j].x + boxes[j].width) {
                            dThrough = true;
                        }
                        if (boxes[i] != null && boxes[j] != null && boxes[i].x < boxes[j].x
                                && boxes[i].x + boxes[i].width > boxes[j].x) {
                            dOverlap = true;
                        }
                    }
                }
                int[] sorted = a.clone();
                java.util.Arrays.sort(sorted);
                for (int i = 1; i < 5; i++) worstDemandClear = Math.min(worstDemandClear, sorted[i] - sorted[i - 1]);
            }
        }
        check("the three routing rules still hold once demands reorder the columns", !dOverlap && !dThrough,
                "32 layouts x 8 demand vectors, closest pair " + worstDemandClear + "px");
        check("(and the demands really do reorder them — otherwise the sweep proves nothing)",
                reordered > 0, reordered + " of 256 vectors put a later lane left of an earlier one");
        // **The degradation round 8 introduced, and has to answer for.** Per-lane demands can make a fan
        // WIDER than the viewport where one demand for the whole step could not: two lanes 700 px apart pack
        // 700 px apart, and shifting that block left does not narrow it. Falling back to round 7's layout is
        // plainly better than the alternative the shift-clamp reaches on its own, which is to draw nothing.
        java.util.List<LinkJoin> pair = lanes(LinkJoin.LINE, LinkJoin.LINE);
        int[] farApart = {100, 800};
        int tightRoom = 600;
        int[] tooWide = LinkArt.INSTANCE.laneAxes(pair, farApart, ROW_H);
        int[] fallback = LinkArt.INSTANCE.alignedLaneAxes(0, pair, farApart, tightRoom, 0, ROW_H);
        check("a fan too wide for the viewport falls back to the minimum layout rather than vanishing",
                fallback != null, fallback == null ? "null" : java.util.Arrays.toString(fallback));
        check("(and it really was too wide — the control)",
                LinkArt.INSTANCE.laneRightEdge(pair, tooWide, ROW_H) > tightRoom,
                LinkArt.INSTANCE.laneRightEdge(pair, tooWide, ROW_H) + "px into " + tightRoom + "px");
        if (fallback != null) {
            check("  the fallback still separates the two lanes and fits",
                    fallback[1] - fallback[0] >= 10
                            && LinkArt.INSTANCE.laneRightEdge(pair, fallback, ROW_H) <= tightRoom,
                    java.util.Arrays.toString(fallback) + " in " + tightRoom + "px");
        }
        check("a viewport too narrow for even the minimum layout still draws nothing",
                LinkArt.INSTANCE.alignedLaneAxes(0, lanes(LinkJoin.PORTAL, 5), new int[5], 40, 0, ROW_H) == null,
                "5 portals need " + LinkArt.INSTANCE.laneSpan(lanes(LinkJoin.PORTAL, 5), ROW_H) + "px");

        header("THE CENTRE RUNS — 'horizontal lines from line ending center overlap'");
        java.util.List<LinkJoin> five = lanes(LinkJoin.LINE, 5);
        int[] fiveAxes = LinkArt.INSTANCE.laneAxes(five, ROW_H);
        java.util.TreeSet<Integer> ys = new java.util.TreeSet<>();
        java.util.TreeSet<Integer> flat = new java.util.TreeSet<>();
        boolean insideRow = true;
        for (int i = 0; i < 5; i++) {
            LinkShape sh = lineEnd(20, 20, 30, 200 + fiveAxes[i], true, false, LinkJoin.LINE, i);
            ys.add(sh.getRailY());
            if (Math.abs(sh.getRailY() - 30) > ROW_H / 2) insideRow = false;
            flat.add(lineEnd(20, 20, 30, 200 + fiveAxes[i], true, false, LinkJoin.LINE, 0).getRailY());
        }
        check("five runs leaving one row sit on five different y", ys.size() == 5, "y = " + ys);
        check("(all five on ONE y is what round 7 drew — the control)", flat.size() == 1,
                "control y = " + flat);
        check("and every one of them stays inside its own row", insideRow,
                "worst |dy| " + Math.max(Math.abs(ys.first() - 30), Math.abs(ys.last() - 30))
                        + "px, half-row is " + (ROW_H / 2));
        check("one run on a row is still exactly on the row's middle",
                lineEnd(20, 20, 30, 300, true, false, LinkJoin.LINE, 0).getRailY() == 30, "rowMid = 30");
        LinkShape offRun = lineEnd(20, 20, 30, 300, true, false, LinkJoin.LINE, 2);
        check("the offset moves the WHOLE run, so the end still turns exactly once",
                offRun.getTrunkY() == offRun.getRailY() && !offRun.getHasHop(),
                "trunk " + offRun.getTrunkY() + " = rail " + offRun.getRailY() + ", no hop");
        check("(a line end into a PORTAL still makes its U-turn onto the rail — the control)",
                lineEnd(20, 20, 30, 300, true, false, LinkJoin.PORTAL, 0).getHasHop(), "hops");
        // A tiny row: the offsets must converge rather than step onto the neighbouring row.
        int tiny = 10;
        boolean tinySafe = true;
        for (int i = 0; i < 5; i++) {
            if (Math.abs(LinkArt.INSTANCE.centreOffset(i, tiny)) > tiny / 2) tinySafe = false;
        }
        check("on a row too small to hold five they converge rather than leaving it", tinySafe,
                "lineHeight " + tiny + ", worst offset " + LinkArt.INSTANCE.centreOffset(4, tiny));

        header("THE STEM — 'they look a bit detached from highlighted tokens'");
        // A fanned token end and its unfanned twin, on one row, diving. The band measured is the space
        // strictly ABOVE the row: at fan 0 nothing of the drawing is up there at all, which is what makes
        // this a measurement of the stem rather than of the link.
        int stemRowMid = 60;
        int stemTop = stemRowMid - ROW_H / 2;
        for (int fan = 1; fan <= 4; fan++) {
            LinkShape st = token(200, 210, stemRowMid, 400, true, false, LinkJoin.PORTAL, fan);
            check("a token end fanned " + fan + " out of its row grows a stem back to it", st.getHasStem(),
                    "rail " + st.getRailY() + ", stem lands " + st.getStemY() + ", row top " + stemTop);
            check("  and it is exactly the fan's own offset long",
                    Math.abs(st.getRailY() - st.getStemY()) == fan * LinkArt.INSTANCE.railStep(ROW_H),
                    Math.abs(st.getRailY() - st.getStemY()) + "px for a fan of " + fan);
            check("  landing where a link with the row to itself would have left",
                    st.getStemY() == stemTop + LinkArt.INSTANCE.railInset(),
                    "stem " + st.getStemY() + " = fan-0 rail "
                            + token(200, 210, stemRowMid, 400, true, false, LinkJoin.PORTAL, 0).getRailY());
        }
        LinkShape stem3 = token(200, 210, stemRowMid, 400, true, false, LinkJoin.PORTAL, 3);
        float[] stemPts = stem3.points(stem3.getLegEndY());
        check("the stem is part of the polyline, so the mouse finds what the editor drew",
                stemPts[0] == 200f && stemPts[1] == stem3.getStemY(),
                "starts at (" + stemPts[0] + ", " + stemPts[1] + ")");
        check("  and a hover on the token's corner really does hit the link",
                LinkArt.INSTANCE.hit(stem3, new java.awt.Point(200, stem3.getStemY() - 1),
                        stem3.getLegEndY()),
                "hit at the corner");
        // Ink, in the 3px column on the token's own edge, in the space above the row.
        int stemInk = columnInk(stem3, 200, stem3.getRailY(), stemTop - 1);
        int flatInk = columnInk(token(200, 210, stemRowMid, 400, true, false, LinkJoin.PORTAL, 0),
                200, stem3.getRailY(), stemTop - 1);
        int endInk = columnInk(lineEnd(200, 210, stemRowMid, 400, true, false, LinkJoin.PORTAL, 3),
                200, stem3.getRailY(), stemTop - 1);
        check("drawn: the token's edge carries ink from the rail down to the row", stemInk >= 4,
                stemInk + " of " + (stemTop - stem3.getRailY()) + " rows of the gap");
        check("(a token end ON its row draws nothing up there — the control)", flatInk == 0,
                flatInk + " px");
        check("(nor does a LINE END fanned just as far, having no token to hang off — the control)",
                endInk == 0, endInk + " px");
        LinkShape rise = token(200, 210, stemRowMid, 400, false, false, LinkJoin.PORTAL, 3);
        check("a rising end hangs its stem off the tint's BOTTOM corner",
                rise.getHasStem() && rise.getStemY() == rise.getRowBottom() - LinkArt.INSTANCE.railInset()
                        && rise.getRailY() > rise.getRowBottom(),
                "rail " + rise.getRailY() + ", stem " + rise.getStemY() + ", row bottom "
                        + rise.getRowBottom());
        check("  and it is drawn below the row, where the rail went",
                columnInk(rise, 200, rise.getRowBottom() + 1, rise.getRailY()) >= 4,
                columnInk(rise, 200, rise.getRowBottom() + 1, rise.getRailY()) + " rows");
        check("no fanned end is left detached — every token end off its row has one",
                allFannedTokensStemmed(), "all 4 fans x 2 directions x 2 joins");

        header("THE SCREEN BASIS — 'all links are routed to the far right side'");
        // The numbers are the live ones, off the round-10 trace: a five-link step in a node whose editor is
        // on a monitor SB_LEFT of the primary, so every locationOnScreen the group exchanges is negative.
        // Round 8's exchange used 0 for "this lane asks for nothing", the merge took the max, and no
        // negative demand ever won it — so every lane fell back to the minimum-pitch fan and the clamp
        // pinned that fan against the viewport's right edge. In one node. On one monitor. Same build.
        java.util.List<LinkJoin> sbFive = lanes(LinkJoin.LINE, 5);
        final int SB_ORIGIN = -2339, SB_ROOM = -729, SB_LEFT = -2339;   // 1610 px of viewport, 2339 px left of 0
        int[] sbLocal = {300, 620, 640, 660, 680};               // each lane's own code, in content x
        int[] sbScreen = new int[sbLocal.length];
        for (int i = 0; i < sbLocal.length; i++) sbScreen[i] = sbLocal[i] + SB_ORIGIN;

        int[] sbAtZero = LinkArt.INSTANCE.alignedLaneAxes(0, sbFive, sbLocal.clone(), 1610, 0, ROW_H);
        int[] sbAtLeft = LinkArt.INSTANCE.alignedLaneAxes(SB_ORIGIN, sbFive, sbScreen.clone(), SB_ROOM, SB_LEFT, ROW_H);
        check("the same step routes identically on a monitor left of the primary",
                java.util.Arrays.equals(sbAtZero, sbAtLeft),
                "primary " + java.util.Arrays.toString(sbAtZero) + " vs left " + java.util.Arrays.toString(sbAtLeft));
        check("  and every lane still clears its own code rather than bunching",
                sbAtLeft != null && sbAtLeft[0] < sbAtLeft[1] && sbAtLeft[1] - sbAtLeft[0] > 300,
                sbAtLeft == null ? "null" : "lane 0 at " + sbAtLeft[0] + ", lane 1 at " + sbAtLeft[1]);
        check("  so the outermost lane is not flush against the viewport's edge",
                sbAtLeft != null && sbAtLeft[4] < SB_ROOM - SB_ORIGIN - 12,
                sbAtLeft == null ? "null" : "lane 4 at " + sbAtLeft[4] + " of " + (SB_ROOM - SB_ORIGIN));

        // The bug, kept as the control: 0 is a coordinate, not a sentinel. Zeros on this basis still mean
        // "clear x = 0", which is 2339 px right of the code — and this is what the reader saw.
        int[] sbZeros = LinkArt.INSTANCE.alignedLaneAxes(SB_ORIGIN, sbFive, new int[5], SB_ROOM, SB_LEFT, ROW_H);
        check("(0 as a demand still means x = 0, which is why it could never be the sentinel — the control)",
                sbZeros != null && sbZeros[0] == 1560 && sbZeros[4] == 1608,
                java.util.Arrays.toString(sbZeros) + ", and the live trace said [1560, 1572, 1584, 1596, 1608]");

        // NO_DEMAND, the value that is not an x.
        int[] sbAbsent = new int[5];
        java.util.Arrays.fill(sbAbsent, LinkArt.NO_DEMAND);
        int[] sbNone = LinkArt.INSTANCE.alignedLaneAxes(SB_ORIGIN, sbFive, sbAbsent.clone(), SB_ROOM, SB_LEFT, ROW_H);
        check("a step that asks for nothing packs at the viewport's left edge, not off its right",
                sbNone != null && sbNone[0] == 2,
                sbNone == null ? "null" : java.util.Arrays.toString(sbNone));
        int[] sbMixed = {LinkArt.NO_DEMAND, SB_ORIGIN + 620, LinkArt.NO_DEMAND, SB_ORIGIN + 660, SB_ORIGIN + 680};
        int[] sbSome = LinkArt.INSTANCE.alignedLaneAxes(SB_ORIGIN, sbFive, sbMixed.clone(), SB_ROOM, SB_LEFT, ROW_H);
        check("  and one absent lane sets no floor for the others",
                sbSome != null && sbSome[1] == sbAtLeft[1] && sbSome[3] == sbAtLeft[3] && sbSome[4] == sbAtLeft[4],
                java.util.Arrays.toString(sbSome));
        check("  the absent lanes sorting innermost, which is where a lane with no code belongs",
                sbSome != null && sbSome[0] < sbSome[1] && sbSome[2] < sbSome[3],
                sbSome == null ? "null" : "absent at " + sbSome[0] + " and " + sbSome[2]);

        // The packing itself, unclamped: a negative demand is honoured rather than floored at zero.
        int[] sbRaw = LinkArt.INSTANCE.laneAxes(sbFive, sbScreen.clone(), ROW_H, SB_LEFT);
        boolean sbClears = true;
        for (int i = 0; i < 5; i++) if (sbRaw[i] < sbScreen[i]) sbClears = false;
        check("laneAxes clamps no demand to 0 any more, so a negative basis packs like any other", sbClears,
                java.util.Arrays.toString(sbRaw) + " against " + java.util.Arrays.toString(sbScreen));
        check("  and with base 0 it is byte-for-byte round 8's answer for a positive basis",
                java.util.Arrays.equals(LinkArt.INSTANCE.laneAxes(sbFive, sbLocal.clone(), ROW_H),
                        LinkArt.INSTANCE.laneAxes(sbFive, sbLocal.clone(), ROW_H, 0)),
                "the width measurement is unchanged");

        header("THE ROW GAP — 'routed further to the right … though it's sufficient free space on the left'");
        // tree-links.json step 7, as the numbers the editor would have produced: rows 38 and 39 adjacent,
        // and the nested child node for the portal on line 39 docked under the LOWER of them.
        int r38 = 100, r39 = r38 + ROW_H, childY = r39 + ROW_H, childH = 300;
        check("a node docked under the lower row is not between the two rows",
                !LinkArt.INSTANCE.crossesRowGap(r38 + ROW_H, r39, childY, childH),
                "gap [" + (r38 + ROW_H) + ", " + r39 + "), node at " + childY);
        check("(round 8's band was the two rows' full extent, and counted it — the control)",
                round8CrossedIt(r38, r39 + ROW_H, childY, childH), "counted, at " + childY);
        check("  so two adjacent rows with nothing between them have an empty gap",
                !LinkArt.INSTANCE.crossesRowGap(r38 + ROW_H, r39, 0, 10_000),
                "nothing can be between them");
        // The control that fires: the same node, docked between the two rows instead of under them.
        int between = r38 + ROW_H;
        check("a node really between the rows IS crossed, and still routes the lane clear of it",
                LinkArt.INSTANCE.crossesRowGap(r38 + ROW_H, between + childH, between, childH),
                "gap [" + (r38 + ROW_H) + ", " + (between + childH) + "), node at " + between);
        check("an inlay above the upper row is not on the way either",
                !LinkArt.INSTANCE.crossesRowGap(r38 + ROW_H, r39 + 200, 40, 60),
                "inlay [40, 100) vs gap [" + (r38 + ROW_H) + ", " + (r39 + 200) + ")");
        check("one that ends exactly on the gap's top edge is not crossed",
                !LinkArt.INSTANCE.crossesRowGap(120, 400, 20, 100), "inlay [20, 120), gap [120, 400)");
        check("one that starts exactly on its bottom edge is not crossed either",
                !LinkArt.INSTANCE.crossesRowGap(120, 400, 400, 100), "inlay [400, 500), gap [120, 400)");
        check("but one overlapping it by a single pixel is",
                LinkArt.INSTANCE.crossesRowGap(120, 400, 399, 100), "inlay [399, 499), gap [120, 400)");

        header("STRIKETHROUGH, counted — pixels of link ink on top of a glyph");
        int straight = strikeCount(LinkRoute.STRAIGHT);
        int shipped = strikeCount(SHIPPED);
        System.out.printf("       %-12s %5d px on glyphs%n", "STRAIGHT", straight);
        System.out.printf("       %-12s %5d px on glyphs   <- shipped%n", SHIPPED.name(), shipped);
        check("STRAIGHT does cross the code, which is why it is only the control", straight > 0, straight + " px");
        check("the shipped route all but eliminates it (>90% less)", shipped < straight / 10,
                shipped + " px vs " + straight + " px");
        // **Round 6's open question, and round 7's answer as a control.** These are the pixels a fan puts on
        // the neighbouring row's glyphs *when the row has not bought itself any space* — which is exactly what
        // round 6 shipped, and what `railSpace` now prevents. The bench draws without the spacer inlay,
        // because a spacer is an editor's block inlay and there is no editor here; so this is the size of the
        // problem the reservation exists to remove, measured.
        for (int n = 1; n <= 5; n++) {
            System.out.printf("       fan of %d: %4d px of rail ink on a neighbouring row's glyphs%n",
                    n, fanStrike(n));
        }
        check("one link alone crosses nothing — the fan is what costs, not the routing",
                fanStrike(1) <= 4, fanStrike(1) + " px");
        check("and the cost grows with the fan, which is why the row now buys the space",
                fanStrike(5) > fanStrike(1), fanStrike(1) + " px -> " + fanStrike(5) + " px");
        check("  and railSpace buys enough to cover all of it",
                LinkArt.INSTANCE.railSpace(5, ROW_H) >= reach + 2,
                LinkArt.INSTANCE.railSpace(5, ROW_H) + "px bought for " + reach + "px of reach");

        header("pixels  ->  " + out);
        for (LinkRoute route : LinkRoute.values()) {
            for (int w : new int[]{NARROW_NODE, WIDE_NODE}) {
                wrote(String.format("link-%s-%d.png", route.name().toLowerCase(), w),
                        p -> draw(p, GOLD, w, route));
            }
        }
        wrote("link-grey-550.png", p -> draw(p, GREY, NARROW_NODE, SHIPPED));
        wrote("link-aligned.png", p -> alignment(p, GOLD));
        wrote("link-zoom.png", p -> zoom(p, GOLD));
        wrote("link-fontscale.png", p -> fontScale(p, GOLD));
        wrote("link-forms.png", p -> forms(p, GOLD));
        wrote("link-fan.png", p -> fan(p, GOLD));
        wrote("link-lanes.png", p -> lanePicture(p, GOLD));
        wrote("link-label.png", p -> labelReach(p, GOLD));
        wrote("link-dim.png", p -> dimming(p, GOLD));

        // ── backforth: the one thing `kind` draws ─────────────────────────────
        header("BACKFORTH — 'they refer to each other', drawn as arriving at both ends");
        LinkShape outward = token(200, 260, 60, 420, true, false, LinkJoin.PORTAL, 0);
        LinkShape inward = token(200, 260, 60, 420, true, true, LinkJoin.PORTAL, 0);
        LinkShape bothA = token(200, 260, 60, 420, true, false, LinkJoin.PORTAL, 0, true);
        LinkShape bothB = token(200, 260, 60, 420, true, true, LinkJoin.PORTAL, 0, true);
        // A one-way link arrives at exactly one of its ends; that is what one arrowhead means.
        check("a flow link arrives at its incoming end only",
                inward.getArrives() && !outward.getArrives(),
                "in=" + inward.getArrives() + " out=" + outward.getArrives());
        // Both halves of a mutual link carry the same flag, so neither can draw a tail while the other draws
        // a head — the failure mode a per-end derivation would have.
        check("a backforth link arrives at BOTH ends, whichever half is asked",
                bothA.getArrives() && bothB.getArrives(),
                "outgoing half=" + bothA.getArrives() + " incoming half=" + bothB.getArrives());
        // The head is real ink at the anchor, so the line has to start past it — at both ends now.
        check("the line starts past the arrowhead on both ends of a backforth link",
                bothA.getFromX() > bothA.getInkX() && bothB.getFromX() > bothB.getInkX()
                        && outward.getFromX() == outward.getInkX(),
                "mutual +" + (bothA.getFromX() - bothA.getInkX()) + "px, flow outgoing +"
                        + (outward.getFromX() - outward.getInkX()) + "px");
        // Everything else is round 6's, and this must not have spent any of it.
        check("backforth changes nothing but the direction: same rail, same axis, same portal",
                bothA.getRailY() == outward.getRailY() && bothA.getAxisX() == outward.getAxisX()
                        && bothA.getOuter().equals(outward.getOuter()),
                "railY=" + bothA.getRailY() + " axisX=" + bothA.getAxisX());

        System.out.println();
        System.out.println(pass + " pass / " + fail + " fail"
                + (skipped > 0 ? "  (" + skipped + " end(s) had no room and were skipped)" : ""));
        if (fail > 0) System.exit(1);
    }

    // ── factories ────────────────────────────────────────────────────────────

    /** A token-anchored end through the real factory. {@code fan} doubles as the lane, for the colour. */
    private static LinkShape token(int startX, int textEndX, int rowMid, int axisX,
                                   boolean targetBelow, boolean incoming, LinkJoin join, int fan) {
        return token(startX, textEndX, rowMid, axisX, targetBelow, incoming, join, fan, false);
    }

    /** The same, with {@code mutual} — `Relation.KIND_BACKFORTH`, which arrives at both ends. */
    private static LinkShape token(int startX, int textEndX, int rowMid, int axisX,
                                   boolean targetBelow, boolean incoming, LinkJoin join, int fan,
                                   boolean mutual) {
        return LinkArt.INSTANCE.shape(LinkAnchor.TOKEN, join, fan, fan, startX, textEndX, rowMid, axisX,
                ROW_H, incoming, targetBelow, SHIPPED, mutual);
    }

    /** A line-end-anchored end through the real factory. */
    private static LinkShape lineEnd(int startX, int textEndX, int rowMid, int axisX,
                                     boolean targetBelow, boolean incoming, LinkJoin join, int fan) {
        return LinkArt.INSTANCE.shape(LinkAnchor.LINE_END, join, fan, fan, startX, textEndX, rowMid, axisX,
                ROW_H, incoming, targetBelow, SHIPPED, false);
    }

    /** A step's lane layout, as the tree hands it to every half. */
    private static java.util.List<LinkJoin> lanes(LinkJoin... js) {
        return java.util.List.of(js);
    }

    /** {@code n} lanes of one join — the two uniform layouts, which is what most of the bench draws. */
    private static java.util.List<LinkJoin> lanes(LinkJoin join, int n) {
        LinkJoin[] js = new LinkJoin[n];
        java.util.Arrays.fill(js, join);
        return java.util.List.of(js);
    }

    /** The axis of one lane of a layout, counting from the step's base column. */
    private static int laneAxis(int base, int lane, java.util.List<LinkJoin> layout) {
        return base + LinkArt.INSTANCE.laneAxes(layout, ROW_H)[lane];
    }

    /**
     * What {@code LinkAlignGroup.axesFor} does, in the one case this bench can pose without editors: every
     * member wants the same clearance, so the lane-by-lane maximum is that one number in every lane. The
     * per-lane case is exercised directly against {@link LinkArt#laneAxes} in PER-LANE ROUTING.
     */
    private static int[] groupAxes(int origin, java.util.List<LinkJoin> joins, int[] demandsScreen,
                                   int[] roomsScreen, int leftScreen) {
        int[] d = new int[joins.size()];
        int want = 0;
        for (int x : demandsScreen) want = Math.max(want, x);
        java.util.Arrays.fill(d, want);
        int room = Integer.MAX_VALUE;
        for (int r : roomsScreen) room = Math.min(room, r);
        return LinkArt.INSTANCE.alignedLaneAxes(origin, joins, d, room, leftScreen, ROW_H);
    }

    /**
     * Rows of ink in the 3px column centred on {@code x}, between {@code y0} and {@code y1} inclusive.
     *
     * Painted through {@link LinkArt#paint} — the shipped entry point, not a re-drawing of the geometry — so
     * what is counted is what the editor would put on screen.
     */
    private static int columnInk(LinkShape s, int x, int y0, int y1) {
        BufferedImage img = new BufferedImage(600, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(PROBE_BG);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        LinkArt.INSTANCE.paint(g, s, GOLD, PROBE_BG, false);
        g.dispose();
        int n = 0;
        for (int y = Math.max(0, y0); y <= Math.min(img.getHeight() - 1, y1); y++) {
            for (int dx = -1; dx <= 1; dx++) {
                int px = x + dx;
                if (px < 0 || px >= img.getWidth()) continue;
                if (img.getRGB(px, y) != PROBE_BG.getRGB()) { n++; break; }
            }
        }
        return n;
    }

    /** Every token end that has left its row, in both directions and both joins, has a stem. */
    private static boolean allFannedTokensStemmed() {
        for (int fan = 1; fan <= 4; fan++) {
            for (boolean below : new boolean[]{true, false}) {
                for (LinkJoin join : LinkJoin.values()) {
                    if (!token(200, 210, 60, 400, below, false, join, fan).getHasStem()) return false;
                }
            }
        }
        return true;
    }

    /**
     * Round 8's band, kept verbatim as the control: the two rows' full extent, with an inlay merely touching
     * it counting as an obstruction. Only this bench calls it.
     */
    private static boolean round8CrossedIt(int topRowY, int bottomRowEndY, int inlayY, int inlayHeight) {
        return !(inlayY + inlayHeight < topRowY || inlayY > bottomRowEndY);
    }

    /** The base column a set of axes is equivalent to, for the picture code that still draws from one. */
    private static int baseOf(int[] axes, java.util.List<LinkJoin> layout) {
        return axes == null ? 0 : axes[0] - LinkArt.INSTANCE.laneAxes(layout, ROW_H)[0];
    }

    /** The five-portal layout, which is the widest a step can ask for and what most drawings here use. */
    private static final java.util.function.Supplier<java.util.List<LinkJoin>> P5 =
            () -> lanes(LinkJoin.PORTAL, 5);

    private static Color[] palette(Color accent) {
        Color[] out = new Color[5];
        for (int i = 0; i < 5; i++) out[i] = LinkArt.INSTANCE.color(accent, i);
        return out;
    }

    // ── measurements ─────────────────────────────────────────────────────────

    /** One end painted over {@link #PROBE_BG}, so "unchanged" is provably "nothing was drawn". */
    private static BufferedImage onProbeBackground(Color accent, boolean targetBelow, boolean hovered) {
        BufferedImage img = new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(PROBE_BG);
        g.fillRect(0, 0, 100, 60);
        LinkShape s = token(4, 4, 30, 40, targetBelow, false, LinkJoin.PORTAL, 0);
        LinkArt.INSTANCE.paint(g, s, accent, BG, hovered);
        g.dispose();
        return img;
    }

    /**
     * The colour the rail is actually drawn in, read off a rendered pixel rather than recomputed.
     *
     * The whole dimming question is *what colour does a resting link come out*, and reading it off the
     * drawing is the only answer that cannot drift from the constant that produces it.
     */
    private static Color railColor(Color accent, Color themeBg, boolean hovered) {
        BufferedImage img = new BufferedImage(200, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(themeBg);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        LinkShape s = token(20, 20, 30, 120, true, false, LinkJoin.PORTAL, 0);
        LinkArt.INSTANCE.paint(g, s, accent, themeBg, hovered);
        g.dispose();
        // A column of plain rail, clear of the head and of the turn.
        return new Color(img.getRGB(40, s.getRailY()));
    }

    /** How many pixels tall the plain rail's ink is — the stroke weight, measured. */
    private static int railInkThickness(Color accent, boolean hovered) {
        BufferedImage img = new BufferedImage(200, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        LinkShape s = token(20, 20, 30, 120, true, false, LinkJoin.PORTAL, 0);
        LinkArt.INSTANCE.paint(g, s, accent, BG, hovered);
        g.dispose();
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) if (inked(img.getRGB(40, y), BG)) n++;
        return n;
    }

    /**
     * Pixels of ink <b>along the row's own middle</b>, between the anchor and the lane.
     *
     * Round 7's item 4 as a measurement: a line end runs the whole span there and a token end places nothing
     * there at all. Round 6 would have answered a handful of pixels for the line end — the vertical of its
     * turn at the text end — and this now answers hundreds, which is the difference between the two rounds.
     */
    private static int inkAlongRowMid(Color accent, LinkAnchor anchor, LinkJoin join) {
        BufferedImage img = new BufferedImage(400, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        LinkShape s = anchor == LinkAnchor.LINE_END
                ? lineEnd(20, 20, 30, 320, true, false, join, 0)
                : token(20, 20, 30, 320, true, false, join, 0);
        // A LINE end's leg belongs to the pair, so give it a partner two rows down to run to.
        LinkArt.INSTANCE.paintHalf(g, s, accent, BG, false,
                join == LinkJoin.LINE ? s.getRailY() + ROW_H * 2 : s.getLegEndY());
        g.dispose();
        int n = 0;
        // Stopped one uRun short of the axis: past that lies the hop (for a line end) and the portal itself,
        // whose annulus straddles the row's middle for a diving link. Counting either would answer a question
        // about the portal rather than about where the run is routed.
        for (int x = s.getInkX(); x <= s.getCentreX() - LinkArt.INSTANCE.uRun(); x++) {
            if (inked(img.getRGB(x, s.getRowMid()), BG)) n++;
        }
        return n;
    }

    /** Top and bottom of the label's drawn glyphs, so "centred on the row" is measured rather than claimed. */
    /** Glyph ink the label actually put on the canvas, ignoring its backdrop. */
    private static int labelInkWidth(LinkShape s, String text, Font font, FontMetrics fm,
                                     int roomRight, int fanRight, int fanSpan) {
        BufferedImage img = new BufferedImage(1000, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(PROBE_BG);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        // A backdrop of the SAME colour as the canvas, so only glyphs count as ink.
        LinkArt.INSTANCE.paintLabel(g, s, text, GOLD, font, fm, roomRight, fanRight, fanSpan, PROBE_BG);
        g.dispose();
        int n = 0;
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                if (img.getRGB(x, y) != PROBE_BG.getRGB()) { n++; break; }
            }
        }
        return n;
    }

    /**
     * The x of the leftmost pixel the label puts down, **backdrop included** — the backdrop is what covers
     * a lane, so it is what "beside its own end" has to be measured on.
     */
    private static int labelInkLeft(LinkShape s, String text, Font font, FontMetrics fm,
                                    int roomRight, int fanRight, int fanSpan) {
        BufferedImage img = new BufferedImage(1400, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(PROBE_BG);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        // A backdrop the canvas colour is NOT, so the box counts as ink here.
        LinkArt.INSTANCE.paintLabel(g, s, text, GOLD, font, fm, roomRight, fanRight, fanSpan, Color.BLACK);
        g.dispose();
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                if (img.getRGB(x, y) != PROBE_BG.getRGB()) return x;
            }
        }
        return -1;
    }

    /** True when the label paints something behind its glyphs. */
    private static boolean labelBackdrop(LinkShape s, String text, Font font, FontMetrics fm) {
        BufferedImage img = new BufferedImage(1000, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(PROBE_BG);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        LinkArt.INSTANCE.paintLabel(g, s, text, GOLD, font, fm, 900, 0, 0, Color.BLACK);
        g.dispose();
        int backdrop = 0;
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                int rgb = img.getRGB(x, y);
                if (rgb != PROBE_BG.getRGB() && new Color(rgb).getRed() < PROBE_BG.getRed()) backdrop++;
            }
        }
        return backdrop > 200;
    }

    private static int[] labelInkBand(LinkShape s, String text, Font font, FontMetrics fm) {
        BufferedImage img = new BufferedImage(700, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(PROBE_BG);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        LinkArt.INSTANCE.paintLabel(g, s, text, GOLD, font, fm, 690, 0, 0, PROBE_BG);
        g.dispose();
        int top = -1, bottom = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = s.getOuter().x + s.getOuter().width; x < img.getWidth(); x++) {
                if (img.getRGB(x, y) != PROBE_BG.getRGB()) {
                    if (top < 0) top = y;
                    bottom = y;
                    break;
                }
            }
        }
        return new int[]{top, bottom};
    }

    /**
     * <b>Worst per-column ink mismatch when the drawn portal is folded about its own centre</b> — round 5's
     * complaint, turned into a number and kept.
     *
     * Only the rows the portal itself occupies are counted, which is what makes the measurement clean: for a
     * target below the rail runs along the row's <i>top</i> and the portal sits at its bottom, so the one
     * deliberately one-sided piece of ink — the connector arriving from the left — is nowhere near the band.
     *
     * <p>{@code even} rebuilds the same end with a 22 px outer against the 13 px hole — the exact parity
     * mismatch that produced the complaint — so the instrument is shown failing on the defect it catches.
     */
    private static int mirrorMismatch(Color accent, boolean even) {
        LinkShape s = token(4, 4, 30, 40, true, false, LinkJoin.PORTAL, 0);
        if (even) {
            Rectangle o = s.getOuter();
            s = new LinkShape(s.getAnchor(), s.getJoin(), s.getLane(), s.getFan(), s.getStartX(),
                    s.getTextEndX(), s.getRowMid(), s.getLineHeight(), s.getAxisX(),
                    new Rectangle(o.x, o.y, o.width - 1, o.height), s.getInner(),
                    s.getRailY(), s.getLegEndY(), s.getIncoming(), s.getTargetBelow(), s.getMutual(),
                    // Carried rather than defaulted: this is a COPY of `s` with one rectangle changed, and a
                    // copy that silently re-defaults a field is how a bench ends up measuring something the
                    // product never draws. Kotlin would have defaulted it for us, which is exactly the help
                    // not wanted here.
                    s.getMetrics());
        }
        Rectangle o = s.getOuter();
        BufferedImage img = new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(PROBE_BG);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        LinkArt.INSTANCE.paint(g, s, accent, BG, false);
        g.dispose();

        int[] perColumn = new int[o.width];
        for (int j = 0; j < o.width; j++) {
            for (int y = o.y; y < o.y + o.height; y++) {
                if (img.getRGB(o.x + j, y) != PROBE_BG.getRGB()) perColumn[j]++;
            }
        }
        int worst = 0;
        for (int j = 0; j < o.width / 2; j++) {
            worst = Math.max(worst, Math.abs(perColumn[j] - perColumn[o.width - 1 - j]));
        }
        return worst;
    }

    /**
     * Leftmost inked column on the rail, against the {@code inkX} the shape reports.
     *
     * Round 5's *"there is several pixels of space between link line ending and the highlight"* was
     * {@code SpotlightRenderer} handing the portal the in-file arrow's 3 px stub. This proves the drawing adds
     * none of its own for a token anchor — and adds exactly three for a line end, which is where that stub
     * belongs and now lives.
     */
    private static int inkStartsAt(Color accent, boolean incoming, LinkAnchor anchor) {
        LinkShape s = anchor == LinkAnchor.LINE_END
                ? lineEnd(30, 30, 30, 120, true, incoming, LinkJoin.PORTAL, 0)
                : token(30, 30, 30, 120, true, incoming, LinkJoin.PORTAL, 0);
        BufferedImage img = new BufferedImage(200, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(PROBE_BG);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        LinkArt.INSTANCE.paint(g, s, accent, BG, false);
        g.dispose();
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = s.getRailY() - 6; y <= s.getRowMid() + 2; y++) {
                if (img.getRGB(x, y) != PROBE_BG.getRGB()) return x;
            }
        }
        return -1;
    }

    /**
     * How many pixels tall the rail's ink is, in the column {@code dx} px from the indicator's centre.
     *
     * The rail is a 1 px line everywhere except where the direction head sits, so this measures the head —
     * and comparing the column ahead of it against the one behind tells you which way it points, without
     * anything having to expose the triangle.
     */
    private static int railThickness(Color accent, boolean incoming, int dx, LinkAnchor anchor) {
        BufferedImage img = new BufferedImage(140, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        LinkShape s = anchor == LinkAnchor.LINE_END
                ? lineEnd(4, 4, 30, 90, true, incoming, LinkJoin.PORTAL, 0)
                : token(4, 4, 30, 90, true, incoming, LinkJoin.PORTAL, 0);
        LinkArt.INSTANCE.paint(g, s, accent, BG, false);
        g.dispose();
        int x = Math.round(s.getIndicatorX()) + dx;
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            if (Math.abs(y - s.getRailY()) <= 6 && inked(img.getRGB(x, y), BG)) n++;
        }
        return n;
    }

    /**
     * How many pixels of this route's decorations land on top of a glyph.
     *
     * Two renders of the same node — the code alone, the links alone — and a count of the pixels that have
     * ink in both. The token tint is deliberately <b>not</b> in the text pass: the tint is meant to be on the
     * token, and counting it would drown the thing being measured.
     *
     * <p><b>Both nodes, and a row of code above and below them</b> — round 5's correction to the instrument
     * rather than to the drawing. It used to render the parent alone from y=0, which measured only the
     * diving ends, and measured the topmost one against an empty margin.
     */
    private static int strikeCount(LinkRoute route) {
        Row[] parent = parentRows();
        Row[] child = childRows();
        Row[] filler = {new Row("        problems.Add(Problem.For(payload, Qty, jgpqy JGPQY));", null, 0, 0, true)};
        int rows = parent.length + child.length + 2;
        BufferedImage text = new BufferedImage(WIDE_NODE, rows * ROW_H, BufferedImage.TYPE_INT_RGB);
        BufferedImage art = new BufferedImage(WIDE_NODE, rows * ROW_H, BufferedImage.TYPE_INT_RGB);
        for (BufferedImage img : new BufferedImage[]{text, art}) {
            Graphics2D g = img.createGraphics();
            g.setColor(BG);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Mode mode = img == text ? Mode.TEXT : Mode.LINK;
            node(g, 0, 0, WIDE_NODE, filler, GOLD, route, null, true, mode, -1);
            node(g, 0, ROW_H, WIDE_NODE, parent, GOLD, route, null, true, mode, -1);
            node(g, 0, ROW_H * (1 + parent.length), WIDE_NODE, child, GOLD, route, null, false, mode, -1);
            node(g, 0, ROW_H * (rows - 1), WIDE_NODE, filler, GOLD, route, null, false, mode, -1);
            g.dispose();
        }
        return overlap(text, art);
    }

    /**
     * <b>The fan's cost, counted.</b> How many pixels of a fan of [links] rails land on the glyphs of the
     * rows above and below.
     *
     * Reported rather than asserted at zero, because it is not zero and pretending otherwise would be the
     * exact failure this bench exists to prevent. Slot 0 sits inside its own row's leading and touches
     * nothing; slot 4 sits {@code 4 · railStep} px beyond the row's bound, which at the default row height is
     * inside the neighbouring row's ascenders. The reader's own rule allows it — *"lines can pass other lines
     * or portals, different color will handle this"* — and `link-fan.png` is where that gets judged.
     */
    private static int fanStrike(int links) {
        String filler = "        problems.Add(Problem.For(payload, Qty, jgpqy JGPQY));";
        int rows = 5;
        BufferedImage text = new BufferedImage(900, rows * ROW_H, BufferedImage.TYPE_INT_RGB);
        BufferedImage art = new BufferedImage(900, rows * ROW_H, BufferedImage.TYPE_INT_RGB);
        for (BufferedImage img : new BufferedImage[]{text, art}) {
            Graphics2D g = img.createGraphics();
            g.setColor(BG);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font code = new Font(Font.MONOSPACED, Font.PLAIN, 13);
            FontMetrics fm = g.getFontMetrics(code);
            if (img == text) {
                g.setFont(code);
                g.setColor(FG);
                for (int r = 0; r < rows; r++) {
                    g.drawString(filler, 0, r * ROW_H + (ROW_H - fm.getHeight()) / 2 + fm.getAscent());
                }
            } else {
                int rowMid = 2 * ROW_H + ROW_H / 2;
                int base = fm.stringWidth(filler) + LinkArt.INSTANCE.channelGap();
                java.util.List<LinkJoin> layout = lanes(LinkJoin.PORTAL, Math.max(1, links));
                for (int slot = 0; slot < links; slot++) {
                    LinkShape s = token(120 + slot * 20, fm.stringWidth(filler), rowMid,
                            laneAxis(base, slot, layout), true, false, LinkJoin.PORTAL, slot);
                    if (s == null) continue;
                    Graphics2D g2 = (Graphics2D) g.create();
                    LinkArt.INSTANCE.paint(g2, s, LinkArt.INSTANCE.color(GOLD, slot), BG, false);
                    g2.dispose();
                }
            }
            g.dispose();
        }
        return overlap(text, art);
    }

    private static int overlap(BufferedImage text, BufferedImage art) {
        int hits = 0;
        for (int y = 0; y < text.getHeight(); y++) {
            for (int x = 0; x < text.getWidth(); x++) {
                if (inked(text.getRGB(x, y), BG) && inked(art.getRGB(x, y), BG)) hits++;
            }
        }
        return hits;
    }

    // ── mock nodes ───────────────────────────────────────────────────────────

    /** Parent node: three outgoing links, one row with none as the control for "a link is visible". */
    private static Row[] parentRows() {
        return new Row[]{
                new Row("        var verdict = _validation.Handle(order);", "_validation", 1, 0, true),
                new Row("        var priced = _pricing.Handle(order, verdict);", "_pricing", 2, 1, true),
                new Row("        _audit.Record(order, verdict, priced);", null, 0, 0, true),
                new Row("        return new DispatchResult(order.Reference, priced);", null, 0, 0, true),
                // The 90-character fixture line that killed an earlier placement, carrying link 3.
                new Row("        problems.AddRange(BlockedLines(order).Select(l => Problem.For(l, order)));",
                        "BlockedLines", 3, 2, true),
        };
    }

    /** Child node: the matching incoming ends, in a different order, so only colour and hover pair them. */
    private static Row[] childRows() {
        return new Row[]{
                new Row("    public Money Handle(Order order, Verdict verdict)", "Handle", 2, 1, false),
                new Row("    {", null, 0, 0, false),
                new Row("        if (!verdict.IsAcceptable) return Money.Zero;", "verdict", 1, 0, false),
                new Row("        var settled = MidInvoker.InvokeOn(subtotal, Scale, _options);", "MidInvoker", 3, 2, false),
        };
    }

    /**
     * Two stacked mock nodes with three links running between them, drawn twice: resting, then with link 2
     * hovered at <b>both</b> ends — which is the cross-editor brightening, and the whole reason a link is
     * expected to read as one thing.
     */
    private static void draw(String path, Color accent, int nodeWidth, LinkRoute route) throws Exception {
        Row[] parent = parentRows();
        Row[] child = childRows();
        int gap = 14;
        int h = (parent.length + child.length) * ROW_H + gap + 24;
        BufferedImage img = new BufferedImage(nodeWidth * 2 + 16, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x13, 0x14, 0x16));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        for (int panel = 0; panel < 2; panel++) {
            int ox = panel * (nodeWidth + 16);
            Integer hovered = panel == 1 ? 2 : null;
            g.setColor(BG);
            g.fillRect(ox, 0, nodeWidth, h);
            g.setColor(new Color(0x9A, 0x76, 0x28));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            g.drawString(panel == 0 ? "rest" : "hover on link 2 (both ends)", ox + 6, 12);
            node(g, ox, 18, nodeWidth, parent, accent, route, hovered, true, Mode.BOTH, -1);
            node(g, ox, 18 + parent.length * ROW_H + gap, nodeWidth, child, accent, route, hovered, false,
                    Mode.BOTH, -1);
        }
        g.dispose();
        write(path, img);
    }

    /**
     * The alignment panel: the same two nodes at the indents the nest gives them, each picking its own
     * channel on the left and sharing one column on the right.
     */
    private static void alignment(String path, Color accent) throws Exception {
        Row[] parent = parentRows();
        Row[] child = childRows();
        int nodeWidth = 820;
        int indent = 26;
        int gap = 14;
        int h = (parent.length + child.length) * ROW_H + gap + 30;
        BufferedImage img = new BufferedImage(nodeWidth * 2 + 24, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x13, 0x14, 0x16));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        FontMetrics fm = g.getFontMetrics(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        int childY = 24 + parent.length * ROW_H + gap;
        for (int panel = 0; panel < 2; panel++) {
            int ox = panel * (nodeWidth + 24);
            boolean aligned = panel == 1;
            g.setColor(BG);
            g.fillRect(ox, 0, nodeWidth, h);
            g.setColor(new Color(0x9A, 0x76, 0x28));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            g.drawString(aligned ? "aligned: one shared column" : "each node its own channel", ox + 6, 14);

            int parentOrigin = ox;
            int childOrigin = ox + indent;
            int[] demands = {parentOrigin + demand(fm, parent), childOrigin + demand(fm, child)};
            int[] roomsPx = {parentOrigin + nodeWidth - 40, childOrigin + nodeWidth - indent - 40};
            java.util.List<LinkJoin> three = lanes(LinkJoin.PORTAL, 3);
            int pc = aligned ? baseOf(groupAxes(parentOrigin, three, demands, roomsPx, 0), three)
                    : demand(fm, parent);
            int cc = aligned ? baseOf(groupAxes(childOrigin, three, demands, roomsPx, 0), three)
                    : demand(fm, child);

            node(g, parentOrigin, 24, nodeWidth, parent, accent, SHIPPED, null, true, Mode.BOTH, pc);
            node(g, childOrigin, childY, nodeWidth - indent, child, accent, SHIPPED, null, false, Mode.BOTH, cc);

            g.setColor(new Color(0x44, 0x46, 0x4A));
            int guide = parentOrigin + laneAxis(pc, 0, lanes(LinkJoin.PORTAL, 3));
            g.drawLine(guide, 18, guide, h - 4);
        }
        g.dispose();
        write(path, img);
    }

    /**
     * The portal at 5×, nearest-neighbour, in the eight states it has.
     *
     * A 23×11 portal is the right size on a row and far too small to argue about in a screenshot. Both
     * directions × outgoing/incoming × resting/hovered, each drawn at 1× through the real
     * {@link LinkArt#paint} and then magnified, with real glyphs after the token so the claim that the
     * connector clears them is visible rather than asserted.
     */
    private static void zoom(String path, Color accent) throws Exception {
        int cellW = 200, cellH = 34, scale = 5, rows = 8;
        boolean[] belows = {true, true, true, true, false, false, false, false};
        boolean[] incomings = {false, false, true, true, false, false, true, true};
        boolean[] hovers = {false, true, false, true, false, true, false, true};

        BufferedImage strip = new BufferedImage(cellW, cellH * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = strip.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, strip.getWidth(), strip.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font code = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        FontMetrics fm = g.getFontMetrics(code);
        String tail = ".Handle(x);";
        for (int i = 0; i < rows; i++) {
            int rowMid = i * cellH + cellH / 2;
            g.setColor(LinkArt.INSTANCE.tint(accent, BG));
            g.fillRect(6, rowMid - ROW_H / 2 + 2, 34, ROW_H - 4);
            g.setFont(code);
            g.setColor(FG);
            g.drawString(tail, 41, rowMid + fm.getAscent() / 2 - 2);
            int textEnd = 41 + fm.stringWidth(tail) + 3;
            int channel = Math.max(110, textEnd + LinkArt.INSTANCE.channelGap());
            LinkShape s = token(42, textEnd, rowMid, channel, belows[i], incomings[i], LinkJoin.PORTAL, 0);
            if (s == null) {
                skipped++;
                continue;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            LinkArt.INSTANCE.paint(g2, s, accent, BG, hovers[i]);
            g2.dispose();
        }
        g.dispose();

        BufferedImage big = magnify(strip, scale);
        Graphics2D bg = big.createGraphics();
        bg.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        for (int i = 0; i < rows; i++) {
            bg.setColor(new Color(0x9A, 0x76, 0x28));
            bg.drawString((belows[i] ? "target BELOW (dives)" : "target ABOVE (climbs)")
                            + " · " + (incomings[i] ? "incoming" : "outgoing")
                            + " · " + (hovers[i] ? "HOVER" : "rest"),
                    8, i * cellH * scale + 16);
        }
        bg.dispose();
        write(path, big);
    }

    /**
     * <b>The taxonomy, at 4×</b>: token × line-end against line × portal, each over real glyphs.
     *
     * This is the round's headline claim as a picture — that the four forms are one drawing from the rail
     * onward, and that a line-end anchor's `∩` turn reads as a turn onto that rail rather than as a kink.
     */
    private static void forms(String path, Color accent) throws Exception {
        int scale = 4, cellW = 250, bandH = ROW_H * 5, pad = 6;
        LinkAnchor[] anchors = {LinkAnchor.TOKEN, LinkAnchor.TOKEN, LinkAnchor.LINE_END, LinkAnchor.LINE_END};
        LinkJoin[] joins = {LinkJoin.PORTAL, LinkJoin.LINE, LinkJoin.PORTAL, LinkJoin.LINE};
        String code = "var settled = Invoke(x);";
        String tokenText = "settled";

        BufferedImage strip = new BufferedImage(cellW, bandH * anchors.length, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = strip.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, strip.getWidth(), strip.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        FontMetrics fm = g.getFontMetrics(font);
        for (int i = 0; i < anchors.length; i++) {
            int top = i * bandH;
            g.setFont(font);
            // Four rows of code, so a joined pair has somewhere to run to.
            for (int r = 0; r < 4; r++) {
                g.setColor(FG);
                g.drawString(code, pad, top + r * ROW_H + (ROW_H - fm.getHeight()) / 2 + fm.getAscent() + 8);
            }
            int textEnd = pad + fm.stringWidth(code);
            int tokenRight = pad + fm.stringWidth(code.substring(0, code.indexOf(tokenText) + tokenText.length()));
            int startX = anchors[i] == LinkAnchor.TOKEN ? tokenRight : textEnd;
            int channel = textEnd + LinkArt.INSTANCE.channelGap();
            int topMid = top + ROW_H / 2 + 8;
            int botMid = top + ROW_H * 3 + ROW_H / 2 + 8;
            if (anchors[i] == LinkAnchor.TOKEN) {
                g.setColor(LinkArt.INSTANCE.tint(accent, BG));
                int x0 = pad + fm.stringWidth(code.substring(0, code.indexOf(tokenText)));
                g.fillRect(x0, topMid - ROW_H / 2 + 2, tokenRight - x0, ROW_H - 4);
                if (joins[i] == LinkJoin.LINE) g.fillRect(x0, botMid - ROW_H / 2 + 2, tokenRight - x0, ROW_H - 4);
            }
            LinkShape a = LinkArt.INSTANCE.shape(anchors[i], joins[i], 0, 0, startX, textEnd, topMid,
                    laneAxis(channel, 0, lanes(joins[i])), ROW_H, false, true, SHIPPED);
            Graphics2D g2 = (Graphics2D) g.create();
            if (joins[i] == LinkJoin.LINE) {
                LinkShape b = LinkArt.INSTANCE.shape(anchors[i], joins[i], 0, 0, startX, textEnd, botMid,
                        laneAxis(channel, 0, lanes(joins[i])), ROW_H, true, false, SHIPPED);
                if (a != null && b != null) LinkArt.INSTANCE.paintPair(g2, a, b, accent, BG, false);
            } else if (a != null) {
                LinkArt.INSTANCE.paint(g2, a, accent, BG, false);
            }
            g2.dispose();
        }
        g.dispose();

        BufferedImage big = magnify(strip, scale);
        Graphics2D bg = big.createGraphics();
        bg.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        bg.setColor(new Color(0x9A, 0x76, 0x28));
        for (int i = 0; i < anchors.length; i++) {
            bg.drawString(anchors[i] + " x " + joins[i], 8, i * bandH * scale + 18);
        }
        bg.dispose();
        write(path, big);
    }

    /**
     * <b>Five links off one row, at 3×</b> — the round's open question as a picture.
     *
     * Lane <i>k</i>'s vertical is {@link LinkArt#laneAxes} px right of the base column and its rail is its
     * <i>fan</i> — its rank among the ends leaving this row <b>on this side</b> — times {@code railStep}
     * further out. The five are provably separate; what only the eye can say is whether the outermost of
     * them is a link passing behind code or a mess. Drawn resting on the left and with lane 2 hovered on the
     * right, exactly as the editor would.
     *
     * <p>Note the fan indices: three of these dive and two rise, so the rails are 0,1,2 on one side and 0,1
     * on the other rather than 0..4 on one. Round 7 counts the fan per row and per side, which is the whole
     * reason {@link LinkArt#railSpace} can buy a row 7 px instead of 13.
     */
    private static void fan(String path, Color accent) throws Exception {
        int scale = 3, rows = 7, panelW = 430;
        String[] code = {
                "        var v = Validate(order);",
                "        // the row above the fan",
                "        Dispatch(a, b, c, d, e);",
                "        // the row below the fan",
                "        var p = Price(order, v);",
                "        Audit(order, v, p);",
                "        return Result(p);",
        };
        String[] tokens = {null, null, "Dispatch", null, null, null, null};
        BufferedImage strip = new BufferedImage(panelW * 2, rows * ROW_H + 12, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = strip.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, strip.getWidth(), strip.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        FontMetrics fm = g.getFontMetrics(font);
        int widest = 0;
        for (String c : code) widest = Math.max(widest, fm.stringWidth(c));
        int base = widest + LinkArt.INSTANCE.channelGap();

        for (int panel = 0; panel < 2; panel++) {
            int ox = panel * panelW;
            g.setFont(font);
            for (int r = 0; r < rows; r++) {
                g.setColor(FG);
                g.drawString(code[r], ox + 4, r * ROW_H + (ROW_H - fm.getHeight()) / 2 + fm.getAscent() + 10);
            }
            int fanRow = 2;
            int rowMid = fanRow * ROW_H + ROW_H / 2 + 10;
            int tokenRight = ox + 4 + fm.stringWidth(code[fanRow].substring(0,
                    code[fanRow].indexOf(tokens[fanRow]) + tokens[fanRow].length()));
            java.util.List<LinkJoin> layout = lanes(LinkJoin.PORTAL, 5);
            int dive = 0, rise = 0;
            for (int slot = 0; slot < 5; slot++) {
                Color c = LinkArt.INSTANCE.color(accent, slot);
                // Alternating direction, so the fan is seen spreading BOTH ways off one row — and so the
                // per-side fan index is visibly not the lane.
                boolean below = slot % 2 == 0;
                int fan = below ? dive++ : rise++;
                LinkShape s = LinkArt.INSTANCE.shape(LinkAnchor.TOKEN, LinkJoin.PORTAL, slot, fan,
                        tokenRight, ox + 4 + widest, rowMid, ox + laneAxis(base, slot, layout), ROW_H,
                        false, below, SHIPPED);
                if (s == null) {
                    skipped++;
                    continue;
                }
                Graphics2D g2 = (Graphics2D) g.create();
                LinkArt.INSTANCE.paint(g2, s, c, BG, panel == 1 && slot == 2);
                g2.dispose();
            }
        }
        g.dispose();

        BufferedImage big = magnify(strip, scale);
        Graphics2D bg = big.createGraphics();
        bg.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        bg.setColor(new Color(0x9A, 0x76, 0x28));
        bg.drawString("five links off one row — rest", 10, 18);
        bg.drawString("hover on slot 2", panelW * scale + 10, 18);
        bg.dispose();
        write(path, big);
    }

    /**
     * <b>A spread fan, one short link hovered, and both label positions</b> — round 11's question as a
     * picture, at 2×.
     *
     * Two same-editor links on one node: the first spans two <i>short</i> rows, the second two <i>long</i>
     * ones, so per-lane demands put their columns ~200 px apart — the reader's <i>"link 1 spans 500px from
     * left, link 2 spans 800px from left"</i>. The first link is hovered in both panels, and the only
     * difference between them is the {@code fanSpan} argument to {@link LinkArt#paintLabel}: the left panel
     * is told the fan is cheap to clear (round 8's position, past every lane) and the right one is told the
     * truth (round 11's, beside its own end). Same call, same geometry, one number.
     *
     * <p>What the eye has to say is whether the right panel's words read as belonging to the link they sit
     * beside — including where they cross the outer lane — and the left panel's do not.
     */
    private static void labelReach(String path, Color accent) throws Exception {
        int scale = 2, rows = 6, panelW = 620;
        String[] code = {
                "    void Settle(Order order)",
                "    {",
                "        var v = Validate(order);",
                "        var p = Price(v);",
                "        var q = Combine(p, v, order.Reference, options, telemetry);",
                "        Audit(q, order.Reference, options.Enabled, telemetry.Trail);",
        };
        // The note is a real one, off `link-fan.json` — long enough that where it starts decides how much
        // of it the reader ever sees.
        String note = "the shortest reach of the five - two 49-column rows, so this lane routes furthest left";
        BufferedImage strip = new BufferedImage(panelW * 2, rows * ROW_H + 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = strip.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, strip.getWidth(), strip.getHeight());
        Font code11 = new Font(Font.MONOSPACED, Font.PLAIN, 11);
        Font italic = new Font(Font.SANS_SERIF, Font.ITALIC, 9);
        FontMetrics fm = g.getFontMetrics(code11);
        int widest = 0;
        for (String c : code) widest = Math.max(widest, fm.stringWidth(c));
        java.util.List<LinkJoin> layout = lanes(LinkJoin.LINE, LinkJoin.LINE);
        int[] from = {2, 4}, to = {3, 5};
        String[] token = {"Validate", "Combine"};

        // Per-lane demands, the same rule the editor uses: clear of every row the leg spans, plus the gap.
        int[] demands = new int[2];
        for (int lane = 0; lane < 2; lane++) {
            int end = 0;
            for (int r = from[lane]; r <= to[lane]; r++) end = Math.max(end, 4 + fm.stringWidth(code[r]));
            demands[lane] = end + LinkArt.INSTANCE.channelGap();
        }
        int[] axes = LinkArt.INSTANCE.alignedLaneAxes(0, layout, demands.clone(), panelW - 4, 0, ROW_H);
        if (axes == null) {
            skipped++;
            g.dispose();
            return;
        }
        int fanRight = LinkArt.INSTANCE.laneRightEdge(layout, axes, ROW_H);

        for (int panel = 0; panel < 2; panel++) {
            int ox = panel * panelW;
            g.setFont(code11);
            for (int r = 0; r < rows; r++) {
                g.setColor(FG);
                g.drawString(code[r], ox + 4, r * ROW_H + (ROW_H - fm.getHeight()) / 2 + fm.getAscent() + 10);
            }
            LinkShape hovered = null;
            for (int lane = 0; lane < 2; lane++) {
                Color c = LinkArt.INSTANCE.color(accent, lane);
                int rowMid = from[lane] * ROW_H + ROW_H / 2 + 10;
                int tokenRight = ox + 4 + fm.stringWidth(code[from[lane]].substring(0,
                        code[from[lane]].indexOf(token[lane]) + token[lane].length()));
                LinkShape a = LinkArt.INSTANCE.shape(LinkAnchor.TOKEN, LinkJoin.LINE, lane, 0, tokenRight,
                        ox + 4 + widest, rowMid, ox + axes[lane], ROW_H, false, true, SHIPPED);
                LinkShape b = LinkArt.INSTANCE.shape(LinkAnchor.LINE_END, LinkJoin.LINE, lane, 0,
                        ox + 4 + fm.stringWidth(code[to[lane]]), ox + 4 + widest,
                        to[lane] * ROW_H + ROW_H / 2 + 10, ox + axes[lane], ROW_H, true, false, SHIPPED);
                if (a == null || b == null) {
                    skipped++;
                    continue;
                }
                Graphics2D g2 = (Graphics2D) g.create();
                LinkArt.INSTANCE.paintPair(g2, a, b, c, BG, lane == 0);
                g2.dispose();
                if (lane == 0) hovered = a;
            }
            if (hovered != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                // The ONE difference between the panels: what the label is told the fan is worth.
                int span = panel == 0 ? 9999 : LinkArt.INSTANCE.laneSpan(layout, ROW_H);
                LinkArt.INSTANCE.paintLabel(g2, hovered, note, LinkArt.INSTANCE.color(accent, 0), italic,
                        g.getFontMetrics(italic), ox + panelW - 4, ox + fanRight, span, BG);
                g2.dispose();
            }
        }
        g.dispose();
        BufferedImage big = magnify(strip, scale);
        Graphics2D bg = big.createGraphics();
        bg.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        bg.setColor(new Color(0x9A, 0x76, 0x28));
        bg.drawString("round 8 — past the whole fan, beside the OTHER link", 10, 18);
        bg.drawString("round 11 — beside its own end", panelW * scale + 10, 18);
        bg.dispose();
        write(path, big);
    }

    /**
     * <b>Three same-editor links and two portals off one row, at 3×</b> — round 7's new fixture steps as a
     * picture, and the one the routing has to be judged on.
     *
     * The same-editor lanes are innermost and 12 px apart; the portal lanes are outermost and a whole portal
     * apart. Nothing is drawn on anything, nothing passes through a portal that is not its own, and the whole
     * fan is 92 px wide where round 6 would have asked for 147. Resting on the left, lane 3 — the first
     * portal — hovered on the right.
     */
    private static void lanePicture(String path, Color accent) throws Exception {
        int scale = 3, rows = 8, panelW = 560;
        String[] code = {
                "    static Options EnsureRequired(Options options)",
                "    {",
                "        var missing = Required.Where(Missing).ToArray();",
                "        if (missing.Length == 0)",
                "        {",
                "            return options;",
                "        }",
                "        return options with { Enabled = merged };",
        };
        BufferedImage strip = new BufferedImage(panelW * 2, rows * ROW_H + 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = strip.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, strip.getWidth(), strip.getHeight());
        Font code13 = new Font(Font.MONOSPACED, Font.PLAIN, 11);
        FontMetrics fm = g.getFontMetrics(code13);
        int widest = 0;
        for (String c : code) widest = Math.max(widest, fm.stringWidth(c));
        java.util.List<LinkJoin> layout = lanes(LinkJoin.LINE, LinkJoin.LINE, LinkJoin.LINE,
                LinkJoin.PORTAL, LinkJoin.PORTAL);

        for (int panel = 0; panel < 2; panel++) {
            int ox = panel * panelW;
            g.setFont(code13);
            for (int r = 0; r < rows; r++) {
                g.setColor(FG);
                g.drawString(code[r], ox + 4, r * ROW_H + (ROW_H - fm.getHeight()) / 2 + fm.getAscent() + 10);
            }
            int fromRow = 2;
            int rowMid = fromRow * ROW_H + ROW_H / 2 + 10;
            int tokenRight = ox + 4 + fm.stringWidth(code[fromRow].substring(0,
                    code[fromRow].indexOf("missing") + "missing".length()));
            int base = ox + 4 + widest + LinkArt.INSTANCE.channelGap();
            int[] partner = {5, 6, 7, -1, -1};
            for (int lane = 0; lane < 5; lane++) {
                Color c = LinkArt.INSTANCE.color(accent, lane);
                LinkJoin join = layout.get(lane);
                int axis = laneAxis(base, lane, layout);
                boolean hot = panel == 1 && lane == 3;
                LinkShape a = LinkArt.INSTANCE.shape(LinkAnchor.TOKEN, join, lane, lane, tokenRight,
                        ox + 4 + widest, rowMid, axis, ROW_H, false, true, SHIPPED);
                if (a == null) {
                    skipped++;
                    continue;
                }
                Graphics2D g2 = (Graphics2D) g.create();
                if (join == LinkJoin.LINE) {
                    int pm = partner[lane] * ROW_H + ROW_H / 2 + 10;
                    LinkShape b = LinkArt.INSTANCE.shape(LinkAnchor.LINE_END, join, lane, 0,
                            ox + 4 + fm.stringWidth(code[partner[lane]]), ox + 4 + widest, pm, axis, ROW_H,
                            true, false, SHIPPED);
                    if (b != null) LinkArt.INSTANCE.paintPair(g2, a, b, c, BG, hot);
                } else {
                    LinkArt.INSTANCE.paint(g2, a, c, BG, hot);
                }
                g2.dispose();
            }
            if (panel == 1) {
                // The hovered link's label, where round 7 puts it: right of its lane, on the row's middle.
                Font italic = new Font(Font.SANS_SERIF, Font.ITALIC, 9);
                LinkShape hovered = LinkArt.INSTANCE.shape(LinkAnchor.TOKEN, LinkJoin.PORTAL, 3, 3,
                        tokenRight, ox + 4 + widest, rowMid, laneAxis(base, 3, layout), ROW_H,
                        false, true, SHIPPED);
                if (hovered != null) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    // This strip's fan is drawn at minimum pitch, so it is the BUNCHED case and the
                    // label steps past it — the same choice the editor makes. See LinkArt.paintLabel.
                    LinkArt.INSTANCE.paintLabel(g2, hovered, "the order this is priced for",
                            LinkArt.INSTANCE.color(accent, 3), italic, g.getFontMetrics(italic),
                            ox + panelW - 4,
                            base + LinkArt.INSTANCE.laneRightEdge(
                                    layout, LinkArt.INSTANCE.laneAxes(layout, ROW_H), ROW_H),
                            LinkArt.INSTANCE.laneSpan(layout, ROW_H),
                            BG);
                    g2.dispose();
                }
            }
        }
        g.dispose();
        BufferedImage big = magnify(strip, scale);
        Graphics2D bg = big.createGraphics();
        bg.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        bg.setColor(new Color(0x9A, 0x76, 0x28));
        bg.drawString("3 same-editor lanes (inner, 12px apart) + 2 portal lanes (outer)", 10, 18);
        bg.drawString("lane 3 hovered — link, portal and label together", panelW * scale + 10, 18);
        bg.dispose();
        write(path, big);
    }

    /**
     * <b>Rest against hover, all five slots, dark theme against light</b> — *"too dim default link/portal
     * tint"*, and its fix, side by side.
     *
     * The reader's report was theme-shaped: a purple link was invisible on dark and a yellow one on light.
     * So the panel is too — the same five slots over both backgrounds, resting on the left of each pair and
     * hovered on the right.
     */
    private static void dimming(String path, Color accent) throws Exception {
        int scale = 3, cellW = 190, cellH = ROW_H + 6;
        BufferedImage strip = new BufferedImage(cellW * 2, cellH * 5 * 2 + 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = strip.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, strip.getWidth(), strip.getHeight());
        for (int theme = 0; theme < 2; theme++) {
            Color themeBg = theme == 0 ? BG : LIGHT_BG;
            Color themeFg = theme == 0 ? FG : LIGHT_FG;
            int oy = theme * cellH * 5 + 2;
            g.setColor(themeBg);
            g.fillRect(0, oy, strip.getWidth(), cellH * 5);
            for (int slot = 0; slot < 5; slot++) {
                int rowMid = oy + slot * cellH + cellH / 2;
                for (int panel = 0; panel < 2; panel++) {
                    int ox = panel * cellW;
                    g.setColor(themeFg);
                    g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                    g.drawString("slot " + slot, ox + 4, rowMid + 4);
                    LinkShape s = token(ox + 46, ox + 46, rowMid, ox + 100, true, false, LinkJoin.PORTAL, 0);
                    if (s == null) {
                        skipped++;
                        continue;
                    }
                    Graphics2D g2 = (Graphics2D) g.create();
                    LinkArt.INSTANCE.paint(g2, s, LinkArt.INSTANCE.color(accent, slot), themeBg, panel == 1);
                    g2.dispose();
                }
            }
        }
        g.dispose();
        BufferedImage big = magnify(strip, scale);
        Graphics2D bg = big.createGraphics();
        bg.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        bg.setColor(new Color(0x9A, 0x76, 0x28));
        bg.drawString("dark: rest | hover", 8, 14);
        bg.setColor(new Color(0x6A, 0x50, 0x18));
        bg.drawString("light: rest | hover", 8, (cellH * 5 + 2) * scale + 14);
        bg.dispose();
        write(path, big);
    }

    /**
     * The same end at four editor font sizes, 3× — round 5's *"can we consider portal shape scaling along
     * with main editor font scaling"*, drawn rather than argued.
     */
    private static void fontScale(String path, Color accent) throws Exception {
        int[] rowHs = {14, 20, 28, 36};
        int scale = 3, cellW = 290, pad = 6;
        String code = "verdict.Ok;";
        String tokenText = "verdict";
        int topPad = 8;
        int stripH = topPad;
        for (int r : rowHs) stripH += r * 2;

        BufferedImage strip = new BufferedImage(cellW, stripH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = strip.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, cellW, stripH);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int[] bandTop = new int[rowHs.length];
        int y = topPad;
        for (int i = 0; i < rowHs.length; i++) {
            int rowH = rowHs[i];
            bandTop[i] = y;
            Font font = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(6, Math.round(rowH * 0.65f)));
            FontMetrics fm = g.getFontMetrics(font);
            int rowMid = y + rowH + rowH / 2;
            int tokenRight = pad + fm.stringWidth(tokenText);
            g.setColor(LinkArt.INSTANCE.tint(accent, BG));
            g.fillRect(pad, rowMid - rowH / 2, tokenRight - pad, rowH);
            g.setFont(font);
            g.setColor(FG);
            g.drawString(code, pad, rowMid + fm.getAscent() / 2 - 1);
            g.drawString(code, pad, rowMid - rowH + fm.getAscent() / 2 - 1);
            int textEnd = pad + fm.stringWidth(code);
            LinkShape s = LinkArt.INSTANCE.shape(LinkAnchor.TOKEN, LinkJoin.PORTAL, 0, 0, tokenRight, textEnd,
                    rowMid, textEnd + LinkArt.INSTANCE.channelGap()
                            + LinkArt.INSTANCE.laneHalfExtent(LinkJoin.PORTAL, rowH),
                    rowH, false, true, SHIPPED);
            if (s != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                LinkArt.INSTANCE.paint(g2, s, accent, BG, false);
                g2.dispose();
            } else {
                skipped++;
            }
            y += rowH * 2;
        }
        g.dispose();

        BufferedImage big = magnify(strip, scale);
        Graphics2D bg = big.createGraphics();
        bg.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        bg.setColor(new Color(0x9A, 0x76, 0x28));
        for (int i = 0; i < rowHs.length; i++) {
            bg.drawString("row " + rowHs[i] + "px  ->  portal "
                    + LinkArt.INSTANCE.outerWidth(rowHs[i]) + "px wide", 8, bandTop[i] * scale + 16);
        }
        bg.dispose();
        write(path, big);
    }

    /** What a node's rows demand of the channel: clear of EVERY link row's code, plus the gap. */
    private static int demand(FontMetrics fm, Row[] rows) {
        int channel = 0;
        for (Row r : rows) {
            if (r.token() == null) continue;
            channel = Math.max(channel, fm.stringWidth(r.code()));
        }
        return channel + LinkArt.INSTANCE.channelGap();
    }

    /**
     * One mock node: the code, the token tints, and each row's link end.
     *
     * @param targetBelow which way this node's links point — down for the parent, up for the child
     * @param channelOverride the base channel to use, or -1 to compute this node's own
     */
    private static void node(Graphics2D g, int ox, int oy, int nodeWidth, Row[] rows, Color accent,
                             LinkRoute route, Integer hovered, boolean targetBelow, Mode mode,
                             int channelOverride) {
        Font code = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        FontMetrics fm = g.getFontMetrics(code);

        // The shared base column, exactly as SpotlightRenderer.linkChannelBaseX computes it: clear of EVERY
        // link row's code, not each row's own, so the channels form columns.
        java.util.List<LinkJoin> layout = lanes(LinkJoin.PORTAL, LinkArt.INSTANCE.slots());
        int channel = channelOverride >= 0 ? channelOverride : demand(fm, rows);
        int room = nodeWidth - 40;
        int need = LinkArt.INSTANCE.minRoom(layout, ROW_H);
        if (room - channel < need) channel = room - need;

        for (int i = 0; i < rows.length; i++) {
            Row r = rows[i];
            int y = oy + i * ROW_H;
            int rowMid = y + ROW_H / 2;
            int tokenStart = r.token() == null ? -1 : r.code().indexOf(r.token());

            if (mode != Mode.LINK) {
                if (tokenStart >= 0 && mode == Mode.BOTH) {
                    int x0 = fm.stringWidth(r.code().substring(0, tokenStart));
                    int x1 = fm.stringWidth(r.code().substring(0, tokenStart + r.token().length()));
                    g.setColor(LinkArt.INSTANCE.tint(LinkArt.INSTANCE.color(accent, r.slot()), BG));
                    g.fillRect(ox + x0, y + 2, x1 - x0, ROW_H - 4);
                }
                g.setFont(code);
                g.setColor(FG);
                g.drawString(r.code(), ox, y + (ROW_H - fm.getHeight()) / 2 + fm.getAscent());
            }

            if (mode == Mode.TEXT || tokenStart < 0) continue;
            int tokenRight = ox + fm.stringWidth(r.code().substring(0, tokenStart + r.token().length())) + 3;
            int textEnd = ox + fm.stringWidth(r.code()) + 3;
            // Through the SAME factory the renderer uses, so an end this bench draws is an end the editor
            // would draw — and one it skips is one the editor skips. The slot fans it, exactly as there.
            LinkShape shape = LinkArt.INSTANCE.shape(LinkAnchor.TOKEN, LinkJoin.PORTAL, r.slot(), r.slot(),
                    tokenRight, textEnd,
                    rowMid, ox + laneAxis(channel, r.slot(), layout), ROW_H,
                    !r.outgoing(), targetBelow, route);
            if (shape == null) {
                if (mode == Mode.BOTH) skipped++;
                continue;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            LinkArt.INSTANCE.paint(g2, shape, LinkArt.INSTANCE.color(accent, r.slot()), BG,
                    hovered != null && hovered == r.pairId());
            g2.dispose();
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private static BufferedImage magnify(BufferedImage src, int scale) {
        BufferedImage big = new BufferedImage(src.getWidth() * scale, src.getHeight() * scale,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = big.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, big.getWidth(), big.getHeight(), null);
        g.dispose();
        return big;
    }

    private static void write(String path, BufferedImage img) throws Exception {
        new File(path).getParentFile().mkdirs();
        ImageIO.write(img, "png", new File(path));
    }

    private static boolean inked(int rgb, Color against) {
        int dr = ((rgb >> 16) & 0xFF) - against.getRed();
        int dg = ((rgb >> 8) & 0xFF) - against.getGreen();
        int db = (rgb & 0xFF) - against.getBlue();
        return Math.sqrt(dr * dr + dg * dg + db * db) > INK;
    }

    private static boolean disjoint(int[] a, int[] b) {
        for (int x : a) for (int y : b) if (x == y) return false;
        return true;
    }

    private static double minPairwise(Color[] cs) {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < cs.length; i++) {
            for (int j = i + 1; j < cs.length; j++) min = Math.min(min, dist(cs[i], cs[j]));
        }
        return min;
    }

    private static double minConsecutive(Color[] cs) {
        double min = Double.MAX_VALUE;
        for (int i = 1; i < cs.length; i++) min = Math.min(min, dist(cs[i - 1], cs[i]));
        return min;
    }

    private static Color blend(Color a, Color b, double t) {
        return new Color(
                (int) Math.round(a.getRed() * t + b.getRed() * (1 - t)),
                (int) Math.round(a.getGreen() * t + b.getGreen() * (1 - t)),
                (int) Math.round(a.getBlue() * t + b.getBlue() * (1 - t)));
    }

    private static double dist(Color a, Color b) {
        int dr = a.getRed() - b.getRed(), dg = a.getGreen() - b.getGreen(), db = a.getBlue() - b.getBlue();
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** A panel to draw, named by its file — so {@link #out} is applied in one place rather than eleven. */
    private interface Panel {
        void draw(String path) throws Exception;
    }

    private static void wrote(String name, Panel panel) throws Exception {
        String path = out + "/" + name;
        panel.draw(path);
        System.out.println("  wrote " + path);
    }

    private static void header(String s) {
        System.out.println();
        System.out.println("== " + s + " ==");
    }

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what + "   [" + detail + "]");
        if (ok) pass++;
        else fail++;
    }
}
