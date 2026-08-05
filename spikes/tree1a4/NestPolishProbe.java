import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.AbstractBorder;
import javax.swing.border.TitledBorder;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Phase 1a⁗ pre-flight probe — the three questions `TreeOfCode.md` §1a⁗ says to answer **before** building,
 * because each is pure Swing/Java2D and none of them needs the IDE.
 *
 * The loop's step 3, and the reason it is a step: 1a″'s equivalent probe found the `contains()` trap that would
 * otherwise have shipped as "the editors are broken", and 1a‴'s dismissed a leader shape (FLARE) before the
 * sandbox ever opened. The questions here:
 *
 *  1. **Item 3 / constraint 16 — where is a chrome's border line actually *drawn*?** With `TITLED_BORDER` the
 *     visible line sits at the caption's mid-height, some pixels *below* the component's top edge, so a leader
 *     that ends at the component's corner visibly stops short. The number was estimated at "~8 px" by eye; this
 *     measures it per chrome, and then measures whether a leader whose tips are extended by exactly that offset
 *     lands **on** the drawn line. That extension paints over the child's top rows, which is the part §1a⁗ says
 *     to probe rather than assume.
 *  2. **Item 7 / constraint 18 — how much padding can a token box take before it collides with its
 *     neighbours?** `EffectType.BOXED` draws on the glyph bounds, so it touches the first and last character and
 *     reads as clipping the text. An inflated rectangle fixes that and risks overlapping the *neighbouring*
 *     glyphs on the same line. Both are pixel questions over monospace text.
 *  3. **Item 9 / verdict 4 — is a collapse control in a node's header band hit-testable** when the node is
 *     inside a frame inside (what stands in for) an editor's content component? An affordance the platform
 *     positions but does not hit-test reads as broken rather than as absent, which is 1a″'s `contains()` lesson
 *     one layer down.
 *
 * **Not re-measured here, deliberately:** whether a sibling above a child survives that child repainting
 * itself. B3a settled it decisively (1444 → 1444 px for a higher-z sibling vs 1444 → **3** px for
 * container-side `paintChildren`) and constraint 7 records it. What this probe adds for the overlap design is
 * the part B3a did *not* cover: that the tips land where they are meant to, that **z-order** is what puts them
 * over the child, and that the strip can overlap the child without swallowing its mouse events.
 *
 * Every check about Swing rather than about arithmetic carries a **control that must fail** — `spikes/b3a/`'s
 * first version had none and its PASS was worthless.
 *
 *   javac -d out NestPolishProbe.java && java -cp out NestPolishProbe
 *
 * Writes PNGs next to itself so the *look* is judged before it costs a sandbox round.
 */
public final class NestPolishProbe {

    /** The four chromes a nested child can wear. `NONE` is new in 1a⁗, asked for by name for the same-file case. */
    enum Chrome { TITLED_BORDER, HEADER_STRIP, PLAIN, NONE }

    /** The three leader shapes 1a⁗ ships. `FLARE` is dismissed and absent. */
    enum Leader { CORNERS, BRACE, ARROW }

    static final int STRIP_H = 22;
    static final int LINE_H = 19;
    static final int CAPTION_H = 15;
    static final int CAPTION_LINE_Y = CAPTION_H / 2;

    static final Color ACCENT = new Color(0x2F, 0x7F, 0xD0);

    /**
     * The leader and the token box, one colour — constraint 18's "they are the same object to the reader's eye".
     *
     * **Deliberately not production's colour.** Production blends the accent 0.80 toward the border for both the
     * leader and the chrome, which makes them near-identical — and a check cannot measure a line it cannot tell
     * from its neighbour. The first run of this probe passed a z-order control it should have failed, because it
     * was matching the *chrome's* border line and calling it the leader. So the mock separates them by hue and
     * the matchers ([isLeaderInk] / [isChromeInk]) test the hue rather than a tolerance ball: 1a″'s "a colour
     * count measures the palette, not the drawing", arriving as a false PASS instead of a false count.
     */
    static final Color LEADER = new Color(0x1E, 0x6F, 0xC8);

    /** The chrome's border line: warm grey, so no assertion can confuse it with the leader. */
    static final Color CHROME_LINE = new Color(0x9A, 0x8F, 0x86);

    static final Color CODE_FG = new Color(0x2B, 0x2B, 0x2B);
    static final Color GUTTER_FG = new Color(0x99, 0x99, 0x99);
    static final Color BG = Color.WHITE;

    static int passes = 0;
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File here = new File(NestPolishProbe.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).getParentFile();

        System.out.println("=".repeat(100));
        System.out.println("Phase 1a⁗ pre-flight — chrome × leader, token-box padding, collapse affordance");
        System.out.println("=".repeat(100));

        // ── item 3: where each chrome draws its line, and whether a leader can land on it ──
        System.out.println("\n[1] Item 3 / constraint 16 — the chrome's *drawn* edge vs its bounds edge");
        int titledOffset = 0;
        for (Chrome c : Chrome.values()) {
            int off = measureDrawnTopOffset(c);
            if (c == Chrome.TITLED_BORDER) titledOffset = off;
        }
        checkTitledBorderIsTheOnlyOffender(titledOffset);

        System.out.println("\n[2] Does a leader whose tips are extended by that offset land ON the drawn line?");
        for (Chrome c : Chrome.values()) {
            for (Leader l : Leader.values()) checkLeaderLandsOnDrawnLine(c, l);
        }
        checkUnextendedLeaderMissesTitledBorder();
        checkZOrderIsWhatPutsTheTipsOverTheChild();
        checkOverlapDoesNotSwallowTheChildsMouse();

        // ── item 7: token box padding ──
        System.out.println("\n[3] Item 7 / constraint 18 — token-box padding vs the neighbouring glyphs");
        checkTokenBoxPadding();

        // ── item 9: the collapse affordance ──
        System.out.println("\n[4] Item 9 / verdict 4 — one collapse affordance, and can it be clicked?");
        checkAffordanceIsHitTestable();
        checkStubRowReadsAsAFrame();

        // ── the look, for the eye ──
        System.out.println("\n[5] Captures");
        for (Leader l : Leader.values()) {
            writeMock(here, "polish-titled-" + l.name().toLowerCase() + ".png", Chrome.TITLED_BORDER, l, false);
        }
        writeMock(here, "polish-none-corners.png", Chrome.NONE, Leader.CORNERS, false);
        writeMock(here, "polish-strip-brace.png", Chrome.HEADER_STRIP, Leader.BRACE, false);
        writeMock(here, "polish-collapsed-stub.png", Chrome.TITLED_BORDER, Leader.CORNERS, true);
        writeBoxComparison(here, "polish-tokenbox-pad.png");

        System.out.println();
        System.out.println("-".repeat(100));
        System.out.printf("%d pass, %d fail%n", passes, failures);
        if (failures > 0) System.exit(1);
    }

    // ══════════════════════════ item 3 — chrome × leader ══════════════════════════

    /**
     * The offset from a node component's top edge to the topmost row its chrome actually *draws* in.
     *
     * Measured off pixels rather than read off `getBorderInsets`, because those are two different numbers and
     * mixing them up is the whole of constraint 16: a titled border's inset is the caption's full height, and
     * its line is drawn at the caption's middle.
     */
    static int measureDrawnTopOffset(Chrome chrome) {
        int offset = measureQuietly(chrome);
        if (chrome == Chrome.NONE) {
            System.out.printf("  INFO  %-14s draws no border at all (nothing found in the top 40 rows: %s)%n",
                    chrome, offset < 0 ? "confirmed" : "found ink at y=" + offset);
        } else {
            System.out.printf("  INFO  %-14s bounds edge y=0, drawn line y=%d  →  a leader ending at the "
                            + "component's corner stops %dpx short%n",
                    chrome, offset, Math.max(offset, 0));
        }
        return Math.max(offset, 0);
    }

    /**
     * The same measurement without the commentary.
     *
     * Samples only x=2..5 — left of any caption text and right of the rounded corner's arc. The first version
     * scanned x=6..24 and found the *caption text* at y=4 instead of the line at y=7, which is the ledger's
     * "an assertion that recomputes what it is checking" in its other form: an assertion looking in a place where
     * more than one thing is drawn.
     */
    static int measureQuietly(Chrome chrome) {
        NodeMock node = new NodeMock(chrome);
        node.setSize(520, 140);
        node.doLayout();
        BufferedImage img = paint(node, 520, 140);
        for (int y = 0; y < 40; y++) {
            for (int x = 2; x <= 5; x++) if (isChromeInk(img.getRGB(x, y))) return y;
        }
        return -1;
    }

    /**
     * The claim §1a⁗ is built on: only `TITLED_BORDER` has a drawn edge away from its bounds edge, so the other
     * three make the leader exact for free.
     *
     * This is the check that decides whether "pick a different chrome" is a real fix or a hope.
     */
    static void checkTitledBorderIsTheOnlyOffender(int titledOffset) {
        int strip = measureQuietly(Chrome.HEADER_STRIP);
        int plain = measureQuietly(Chrome.PLAIN);
        int none = measureQuietly(Chrome.NONE);
        report(titledOffset > 2 && strip == 0 && plain == 0 && none < 0,
                "TITLED_BORDER is the only chrome whose drawn line is not its bounds edge",
                String.format("titled=%dpx header_strip=%dpx plain=%dpx none=%s — so HEADER_STRIP/PLAIN/NONE make "
                                + "the leader exact with no extension at all",
                        titledOffset, strip, plain, none < 0 ? "no border" : none + "px"));
    }

    /**
     * The leader's tips must land in the drawn line's own row, at both ends of the frame.
     *
     * The design under test: the strip's height is `STRIP_H + chromeTopOffset`, it overlaps the child's top rows
     * by that offset, and it is z-ordered above the child. `ARROW` is one-ended by construction (out of the box's
     * bottom-right, right, then down), so only its right tip is asserted.
     */
    static void checkLeaderLandsOnDrawnLine(Chrome chrome, Leader leader) {
        FrameMock m = new FrameMock(chrome, leader, false, /* extendTips = */ true);
        m.layOut(900, 460);
        BufferedImage img = paint(m.content, 900, 460);

        Rectangle strip = SwingUtilities.convertRectangle(m.frame, m.strip.getBounds(), m.content);
        // The row the child's chrome draws its line in, in content coordinates.
        int lineY = strip.y + STRIP_H + m.chromeOffset();
        int leftX = strip.x;
        int rightX = strip.x + strip.width - 1;

        boolean right = anyLeaderInk(img, rightX - 3, lineY - 2, 5, 5);
        boolean left = leader == Leader.ARROW || anyLeaderInk(img, leftX - 1, lineY - 2, 5, 5);
        boolean atBox = anyLeaderInk(img, m.content.tokenBox().x - 3, strip.y - 3,
                m.content.tokenBox().width + 6, 6);

        report(left && right && atBox,
                String.format("%-14s + %-7s: the leader reaches the drawn line at both ends", chrome, leader),
                String.format("drawn line at y=%d (strip y=%d + %d + chromeOffset %d) — left=%b right=%b box=%b",
                        lineY, strip.y, STRIP_H, m.chromeOffset(), left, right, atBox));
    }

    /**
     * The control for the whole extension idea: with the tips **not** extended, the leader stops short of a
     * titled border's drawn line by exactly the offset.
     *
     * Without this, check [2] would pass on a hierarchy where the offset happened to be zero and would keep
     * passing after the extension was deleted — which is precisely the defect the reader spotted by eye in 1a‴.
     */
    static void checkUnextendedLeaderMissesTitledBorder() {
        FrameMock m = new FrameMock(Chrome.TITLED_BORDER, Leader.CORNERS, false, /* extendTips = */ false);
        m.layOut(900, 460);
        BufferedImage img = paint(m.content, 900, 460);
        Rectangle strip = SwingUtilities.convertRectangle(m.frame, m.strip.getBounds(), m.content);
        // The chrome's *real* offset, not the frame's — with the tips unextended `chromeOffset()` is 0, and using
        // it here made the expected line and the bounds edge the same row, so the control passed on both. That is
        // the ledger's "an assertion that recomputes what it is checking", in a control rather than in a check.
        int offset = drawnOffsetFor(Chrome.TITLED_BORDER);
        int lineY = strip.y + STRIP_H + offset;
        boolean atLine = anyLeaderInk(img, strip.x + strip.width - 3, lineY - 1, 5, 3);
        boolean atBoundsEdge = anyLeaderInk(img, strip.x + strip.width - 3, strip.y + STRIP_H - 4, 5, 4);

        report(!atLine && atBoundsEdge,
                "CONTROL: an unextended leader stops at the component's edge, short of the drawn line",
                String.format("at the drawn line (y=%d): %b — at the bounds edge (y=%d): %b — a %dpx gap, which "
                                + "is exactly what 1a‴ saw by eye", lineY, atLine, strip.y + STRIP_H - 1,
                        atBoundsEdge, offset));
    }

    /**
     * **Z-order is the mechanism**, not paint order inside one component.
     *
     * The extended tips are drawn by a sibling whose bounds overlap the child's top rows. Swing paints children
     * from the highest index down, so index 0 paints last and wins; an opaque child above the strip erases the
     * tips completely. Control: the same hierarchy with the strip sent to the back — the tips must vanish.
     */
    static void checkZOrderIsWhatPutsTheTipsOverTheChild() {
        FrameMock above = new FrameMock(Chrome.TITLED_BORDER, Leader.CORNERS, false, true);
        above.layOut(900, 460);
        Rectangle stripAbove = SwingUtilities.convertRectangle(above.frame, above.strip.getBounds(), above.content);
        int lineYAbove = stripAbove.y + STRIP_H + above.chromeOffset();
        boolean visibleAbove = anyLeaderInk(paint(above.content, 900, 460),
                stripAbove.x + stripAbove.width - 3, lineYAbove - 2, 5, 5);

        FrameMock below = new FrameMock(Chrome.TITLED_BORDER, Leader.CORNERS, false, true);
        below.frame.sendStripToBack();
        below.layOut(900, 460);
        Rectangle stripBelow = SwingUtilities.convertRectangle(below.frame, below.strip.getBounds(), below.content);
        int lineYBelow = stripBelow.y + STRIP_H + below.chromeOffset();
        // Rows inside the child's own area, where only a sibling above the child can leave ink. The colour matters
        // here more than anywhere: the child's chrome draws its own line in this row, and the first version of
        // this control matched *that* and reported the tips as surviving in both orders.
        boolean visibleBelow = anyLeaderInk(paint(below.content, 900, 460),
                stripBelow.x + stripBelow.width - 3, lineYBelow - 2, 5, 5);

        report(visibleAbove && !visibleBelow,
                "the extended tips survive only because the strip is z-above the child",
                String.format("strip at index 0 (topmost): tips visible=%b — strip sent to back: tips visible=%b "
                        + "(the child's opaque background erases them)", visibleAbove, visibleBelow));
    }

    /**
     * An overlapping strip must not take the child's mouse events in the rows it covers.
     *
     * This is 1a″'s `contains()` trap arriving one layer down: the strip is above the child, so with the default
     * `contains` every click and wheel notch in the child's top rows goes to the strip — which reads as the
     * nested editor being dead at the top rather than as a strip being in the way. The fix is a `contains` that
     * claims only the strip's own band; the control is the un-overridden version, which must swallow the point.
     */
    static void checkOverlapDoesNotSwallowTheChildsMouse() {
        FrameMock polite = new FrameMock(Chrome.TITLED_BORDER, Leader.CORNERS, false, true);
        polite.layOut(900, 460);
        int overlapY = STRIP_H + polite.chromeOffset() / 2;      // inside the overlap band
        Point inOverlap = SwingUtilities.convertPoint(polite.frame, new Point(polite.frame.getWidth() / 2, overlapY),
                polite.content);
        Component deepPolite = SwingUtilities.getDeepestComponentAt(polite.content, inOverlap.x, inOverlap.y);
        boolean reachesChild = deepPolite != null && SwingUtilities.isDescendingFrom(deepPolite, polite.frame.child);

        Point inStrip = SwingUtilities.convertPoint(polite.frame, new Point(polite.frame.getWidth() / 2, STRIP_H / 2),
                polite.content);
        Component deepStrip = SwingUtilities.getDeepestComponentAt(polite.content, inStrip.x, inStrip.y);
        boolean stripTakesItsOwn = deepStrip == polite.strip;

        FrameMock greedy = new FrameMock(Chrome.TITLED_BORDER, Leader.CORNERS, false, true);
        greedy.frame.makeStripGreedy();
        greedy.layOut(900, 460);
        Point greedyPoint = SwingUtilities.convertPoint(greedy.frame,
                new Point(greedy.frame.getWidth() / 2, overlapY), greedy.content);
        Component deepGreedy = SwingUtilities.getDeepestComponentAt(greedy.content, greedyPoint.x, greedyPoint.y);
        boolean swallowed = deepGreedy == greedy.strip;

        report(reachesChild && stripTakesItsOwn,
                "a point in the overlap band reaches the child, and the strip still takes clicks in its own band",
                String.format("overlap y=%d → %s (reaches child=%b); strip y=%d → %s (strip=%b)",
                        overlapY, name(deepPolite), reachesChild, STRIP_H / 2, name(deepStrip), stripTakesItsOwn));
        report(swallowed,
                "CONTROL: without the narrowed contains(), the strip swallows the child's top rows",
                String.format("overlap y=%d → %s — which reads as the nested editor being dead at the top",
                        overlapY, name(deepGreedy)));
    }

    // ══════════════════════════ item 7 — token-box padding ══════════════════════════

    /**
     * How much padding a token box can take, as a window rather than a preference.
     *
     * **Two different masks, and the first version of this check confused them.** Constraint 18 names one defect
     * and one risk, and they are measured against different ink:
     *
     * - the **defect** is that `EffectType.BOXED` draws on the glyph bounds, so the stroke sits against the
     *   token's *own* first and last character — measured against **token ink**, as a clearance in pixels;
     * - the **risk** of inflating the rectangle is colliding with the characters *around* the token — measured
     *   against **neighbour ink**, as a collision count.
     *
     * Measuring the defect against the neighbours (which is what the first run did) reports 0 collisions at pad 0
     * and calls the defect absent. So the answer is a window: the smallest pad that gives the token room, and the
     * largest that still clears the neighbours. The fixture line ends `…verdict);` with **no space** before the
     * `;`, which is deliberate — it is the tight case, and it is what bounds the window from above.
     */
    static void checkTokenBoxPadding() {
        CodeLineMock line = new CodeLineMock();
        BufferedImage neighbours = line.paintNeighbourInk();
        BufferedImage token = line.paintTokenInk();
        int firstRoomy = -1;
        int[] graze = new int[6];
        for (int pad = 0; pad <= 5; pad++) {
            BufferedImage box = line.paintBoxOnly(pad);
            graze[pad] = countIntersection(neighbours, box);
            int tokenHits = countIntersection(token, box);
            int clearance = line.clearanceToTokenInk(pad);
            Rectangle r = line.tokenBox(pad);
            boolean insideRow = r.y >= line.rowTop() - 1 && r.y + r.height <= line.rowTop() + LINE_H + 1;
            System.out.printf("  INFO  pad=%d  box=%dx%d at x=%d  token ink: %d hits, %dpx clearance  |  "
                            + "neighbour ink: %d hits  |  inside its own row=%b%n",
                    pad, r.width, r.height, r.x, tokenHits, clearance, neighbourHits(graze[pad]), insideRow);
            if (tokenHits == 0 && clearance >= 2 && firstRoomy < 0) firstRoomy = pad;
        }

        int zeroClearance = line.clearanceToTokenInk(0);
        report(zeroClearance <= 1,
                "CONTROL: pad=0 (what EffectType.BOXED does) leaves the stroke against the token's own glyphs",
                String.format("%dpx clearance to the first/last character — which is what \"reads as clipping the "
                        + "text\" measures as", zeroClearance));

        // The trade-off is **not monotonic**, and saying "pads 2..5 are safe" would have hidden that: as the
        // padding grows the right-hand stroke walks *across* the `;` that follows the token with no space, grazing
        // it at 2–4px and clearing it entirely at 5. So the honest statement is a cheap choice with a bounded
        // cost, not a clean window — and the cost is a stroke crossing a glyph's edge, not a box over a character.
        report(firstRoomy == 2 && graze[2] <= GRAZE_BUDGET,
                "2px is the cheapest padding that gives the token air, and its cost is bounded",
                String.format("clearance 0px → 2px; %d pixel(s) of the stroke fall on the immediately-following "
                                + "';'. The cost is non-monotonic (pad 3: %d, pad 4: %d, pad 5: %d — the stroke "
                                + "walks across the glyph and out the other side), so a larger pad buys nothing "
                                + "but width", graze[2], graze[3], graze[4], graze[5]));

        int chosen = firstRoomy < 0 ? 2 : firstRoomy;
        int vertical = countIntersection(line.paintAdjacentRowInk(), line.paintBoxOnly(chosen));
        report(vertical == 0,
                "the padded box does not touch the lines above or below it",
                String.format("%d colliding pixels against the adjacent rows at pad=%d — the vertical inflate is "
                        + "clamped to the row, so it cannot reach them", vertical, chosen));
    }

    // ══════════════════════════ item 9 — the collapse affordance ══════════════════════════

    /**
     * A control in the node's **header band** — the position §1a⁗ prefers — three components deep inside what
     * stands in for an editor's content component.
     *
     * The failure mode is not "it does not work", it is "it is invisible to hit-testing while looking perfect",
     * which is why the control is the same button placed where the chrome has no header band to put it in: at
     * y=0 of a `NONE` chrome, where the editor's own component covers it.
     */
    static void checkAffordanceIsHitTestable() {
        FrameMock m = new FrameMock(Chrome.TITLED_BORDER, Leader.CORNERS, false, true);
        m.layOut(900, 460);
        JComponent toggle = m.frame.child.toggle;
        Rectangle inContent = SwingUtilities.convertRectangle(toggle.getParent(), toggle.getBounds(), m.content);
        Component deepest = SwingUtilities.getDeepestComponentAt(m.content,
                inContent.x + inContent.width / 2, inContent.y + inContent.height / 2);
        report(deepest == toggle,
                "a collapse control in the node's header band is the deepest component at its own centre",
                String.format("toggle %s in content space → %s", inContent, name(deepest)));

        // The control: the same control on a chrome with no header band, where the editor component covers it.
        FrameMock headerless = new FrameMock(Chrome.NONE, Leader.CORNERS, false, true);
        headerless.frame.child.placeToggleUnderTheEditor();
        headerless.layOut(900, 460);
        JComponent buried = headerless.frame.child.toggle;
        Rectangle burInContent = SwingUtilities.convertRectangle(buried.getParent(), buried.getBounds(),
                headerless.content);
        Component deepBuried = SwingUtilities.getDeepestComponentAt(headerless.content,
                burInContent.x + burInContent.width / 2, burInContent.y + burInContent.height / 2);
        report(deepBuried != buried,
                "CONTROL: the same control behind the editor component is unreachable while still being drawn",
                String.format("→ %s instead of the toggle — so a chrome with no header band needs the control "
                        + "lifted above the editor, not merely positioned over it", name(deepBuried)));
    }

    /**
     * Verdict 4's other half: a collapsed nesting is a one-row stub, and it still has to read as a frame.
     *
     * Cheap, and it is the check that would have caught the 1a‴ complaint: the stub was a bare label.
     */
    static void checkStubRowReadsAsAFrame() {
        FrameMock m = new FrameMock(Chrome.TITLED_BORDER, Leader.CORNERS, /* collapsed = */ true, true);
        m.layOut(900, 460);
        JComponent stub = m.frame.stub;
        BufferedImage img = paint(m.frame, m.frame.getWidth(), m.frame.getPreferredSize().height);
        Rectangle b = stub.getBounds();
        int midX = b.x + b.width / 2;
        int midY = b.y + b.height / 2;
        boolean top = anyLeaderInk(img, midX - 6, b.y - 1, 13, 3);
        boolean bottom = anyLeaderInk(img, midX - 6, b.y + b.height - 2, 13, 3);
        boolean left = anyLeaderInk(img, b.x - 1, midY - 4, 3, 9);
        boolean right = anyLeaderInk(img, b.x + b.width - 2, midY - 4, 3, 9);
        report(top && bottom && left && right,
                "the collapsed stub row is bordered on all four sides, so it still reads as a frame",
                String.format("top=%b bottom=%b left=%b right=%b (stub %s, dashed)", top, bottom, left, right, b));
    }

    // ══════════════════════════ the mock hierarchy ══════════════════════════

    /**
     * The same three-deep hierarchy production has — `content` stands for the host editor's content component,
     * `frame` for the component handed to `EditorEmbeddedComponentManager`, and inside it the leader strip and
     * the nested node.
     *
     * The one structural change from 1a‴'s probe: the frame lays its two children out **by hand** rather than
     * with a `BorderLayout`, because the strip has to be able to overlap the child. A `BorderLayout` cannot
     * express that, and z-order is what decides who wins in the overlap.
     */
    static final class FrameMock {
        final ContentMock content;
        final NestFrame frame;
        final JComponent strip;

        FrameMock(Chrome chrome, Leader leader, boolean collapsed, boolean extendTips) {
            frame = new NestFrame(chrome, leader, extendTips);
            frame.setCollapsed(collapsed);
            strip = frame.strip;
            content = new ContentMock(frame);
        }

        int chromeOffset() { return frame.chromeOffset(); }

        void layOut(int w, int h) {
            content.setSize(w, h);
            content.doLayout();
            content.validate();
            frame.doLayout();
            frame.child.doLayout();
        }
    }

    /** The fake host editor: a gutter, code, the boxed invoking token, and the nested frame under the call line. */
    static final class ContentMock extends JPanel {
        static final String[] BEFORE = {
                "public static Money MainLoop()",
                "{",
                "    var subtotal = Money.Of(1200m);",
                "    int someParam = SomeSysCall();",
        };
        static final String CALL_PREFIX = "    var priced = ";
        static final String TOKEN = "_pricing.Handle(order, verdict)";
        static final String CALL_SUFFIX = ";";
        static final String[] AFTER = {"    Audit(priced);", "    return priced;", "}"};

        final NestFrame frame;
        final Font code = new Font(Font.MONOSPACED, Font.PLAIN, 13);

        ContentMock(NestFrame frame) {
            this.frame = frame;
            setLayout(null);
            setOpaque(true);
            setBackground(BG);
            add(frame);
        }

        FontMetrics fm() {
            BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scratch.createGraphics();
            try {
                return g.getFontMetrics(code);
            } finally {
                g.dispose();
            }
        }

        int gutterW() { return fm().stringWidth("888") + 14; }

        int callRow() { return BEFORE.length; }

        int callLineBottom() { return (callRow() + 1) * LINE_H; }

        int tokenX1() { return gutterW() + fm().stringWidth(CALL_PREFIX); }

        int tokenX2() { return tokenX1() + fm().stringWidth(TOKEN); }

        /** One definition of the box, used by the painter and by every check (the 1a‴ lesson). */
        Rectangle tokenBox() {
            int pad = 3;
            return new Rectangle(tokenX1() - pad, callRow() * LINE_H + 2,
                    tokenX2() - tokenX1() + 2 * pad, LINE_H - 4);
        }

        @Override
        public void doLayout() {
            int w = Math.max(getWidth() - 16, 160);
            frame.setBounds(0, callLineBottom(), w, frame.getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setFont(code);
                FontMetrics fm = g2.getFontMetrics();
                int gw = gutterW();
                int row = 0;
                for (String s : BEFORE) { drawRow(g2, fm, gw, row, 68 + row, s); row++; }
                drawRow(g2, fm, gw, row, 68 + row, CALL_PREFIX + TOKEN + CALL_SUFFIX);

                // The token box, in the leader's colour — constraint 18's "they are the same object to the eye".
                g2.setColor(LEADER);
                g2.setStroke(new BasicStroke(1.2f));
                Rectangle b = tokenBox();
                g2.drawRoundRect(b.x, b.y, b.width, b.height, 5, 5);

                int afterY = frame.getY() + frame.getPreferredSize().height + 4;
                int afterRow = afterY / LINE_H;
                for (int i = 0; i < AFTER.length; i++) drawRow(g2, fm, gw, afterRow + i, 74 + i, AFTER[i]);
            } finally {
                g2.dispose();
            }
        }

        private void drawRow(Graphics2D g2, FontMetrics fm, int gutter, int row, int lineNo, String text) {
            int baseline = row * LINE_H + fm.getAscent() + 1;
            g2.setColor(GUTTER_FG);
            String n = String.valueOf(lineNo);
            g2.drawString(n, gutter - 10 - fm.stringWidth(n), baseline);
            g2.setColor(CODE_FG);
            g2.drawString(text, gutter, baseline);
        }
    }

    /**
     * The embedded component: leader strip and nested node, laid out by hand so the strip can overlap.
     *
     * The overlap is the whole of the `TITLED_BORDER` fix — the strip's bounds extend `chromeOffset` px into the
     * child's rows so the diagonals can reach the *drawn* line rather than the bounds edge.
     */
    static class NestFrame extends JPanel {
        final LeaderStrip strip;
        final NodeMock child;
        final JComponent stub;
        private final Chrome chrome;
        private final boolean extendTips;
        private boolean collapsed;

        NestFrame(Chrome chrome, Leader leader, boolean extendTips) {
            this.chrome = chrome;
            this.extendTips = extendTips;
            setLayout(null);
            setOpaque(false);
            child = new NodeMock(chrome);
            stub = new StubRow();
            strip = new LeaderStrip(this, leader);
            add(child);
            add(strip);
            // Index 0 paints last, so the strip wins in the overlap band. This line IS the mechanism.
            setComponentZOrder(strip, 0);
        }

        int chromeOffset() { return extendTips ? drawnOffsetFor(chrome) : 0; }

        int stripHeight() { return STRIP_H + chromeOffset(); }

        void setCollapsed(boolean c) {
            collapsed = c;
            child.setVisible(!c);
            stub.setVisible(c);
            if (c && stub.getParent() == null) add(stub);
            revalidate();
        }

        boolean isCollapsed() { return collapsed; }

        void sendStripToBack() { setComponentZOrder(strip, getComponentCount() - 1); }

        void makeStripGreedy() { strip.greedy = true; }

        @Override
        public Dimension getPreferredSize() {
            JComponent body = collapsed ? stub : child;
            return new Dimension(Math.max(getWidth(), 200), STRIP_H + body.getPreferredSize().height);
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            JComponent body = collapsed ? stub : child;
            body.setBounds(0, STRIP_H, w, body.getPreferredSize().height);
            // Taller than STRIP_H by the chrome's offset, and above the child: the tips reach the drawn line.
            strip.setBounds(0, 0, w, stripHeight());
            if (!collapsed) stub.setBounds(0, STRIP_H, w, 0);
        }
    }

    /** The collapsed stub. A bordered row, not a bare label — verdict 4. */
    static final class StubRow extends JPanel {
        private final JLabel label = new JLabel("  SubInvoker.cs  ·  SubInvoker.Apply");

        StubRow() {
            setLayout(null);
            setOpaque(false);
            label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            label.setForeground(new Color(0x77, 0x77, 0x77));
            add(label);
        }

        @Override
        public Dimension getPreferredSize() { return new Dimension(200, 20); }

        @Override
        public void doLayout() { label.setBounds(20, 0, Math.max(getWidth() - 24, 40), getHeight()); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(LEADER);
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f,
                        new float[]{3f, 3f}, 0f));
                g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * A nested node: chrome, a fake editor component, and the collapse control in the header band.
     *
     * `NONE` is 1a⁗'s new chrome — no border, no caption, which is what verdict 16 asked for by name for the
     * same-file case, and which also makes the leader exact with no extension.
     */
    static final class NodeMock extends JPanel {
        final JComponent toggle = new ToggleControl();
        private final JComponent editorComponent = new FakeEditor();
        private final Chrome chrome;

        NodeMock(Chrome chrome) {
            this.chrome = chrome;
            setLayout(null);
            setOpaque(true);
            setBackground(BG);
            add(editorComponent);
            add(toggle);
            // The control lives in the header band and must be above the editor component, not merely over it.
            setComponentZOrder(toggle, 0);
            applyChrome();
        }

        Color borderColor() { return CHROME_LINE; }

        private void applyChrome() {
            switch (chrome) {
                case TITLED_BORDER -> setBorder(new CaptionBorder(borderColor()));
                case HEADER_STRIP, PLAIN -> setBorder(BorderFactory.createLineBorder(borderColor(), 1, true));
                case NONE -> setBorder(null);
            }
        }

        /** Where the chrome's own header band ends — the top inset the toggle is placed inside. */
        int headerBand() {
            Insets ins = getInsets();
            return switch (chrome) {
                case TITLED_BORDER -> Math.max(ins.top, CAPTION_H);
                case HEADER_STRIP -> CAPTION_H;
                case PLAIN, NONE -> 0;
            };
        }

        void placeToggleUnderTheEditor() { setComponentZOrder(toggle, getComponentCount() - 1); }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(Math.max(getWidth(), 200), headerBand() + 6 * LINE_H + 8);
        }

        @Override
        public void doLayout() {
            Insets ins = getInsets();
            int band = headerBand();
            int tw = 14, th = 12;
            // Trailing edge of the header band: the caption text is leading, so the control does not collide.
            toggle.setBounds(Math.max(getWidth() - ins.right - tw - 4, 0),
                    Math.max((band - th) / 2, 1), tw, th);
            // Inside the insets, never over them — production uses a BorderLayout, which guarantees this for free.
            // The first version placed the editor at y=band with band=0 for PLAIN, so the opaque editor covered the
            // border's own top line and the offset measurement found gutter text instead of a border.
            int top = Math.max(band, ins.top);
            editorComponent.setBounds(ins.left, top,
                    Math.max(getWidth() - ins.left - ins.right, 10),
                    Math.max(getHeight() - top - ins.bottom, 10));
        }
    }

    /** The small triangle: **one** affordance, in the node's header band. The strip no longer draws one. */
    static final class ToggleControl extends JComponent {
        ToggleControl() { setOpaque(false); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(LEADER);
                GeneralPath t = new GeneralPath();
                float w = getWidth(), h = getHeight();
                t.moveTo(w * 0.2f, h * 0.35f);
                t.lineTo(w * 0.8f, h * 0.35f);
                t.lineTo(w * 0.5f, h * 0.75f);
                t.closePath();
                g2.fill(t);
            } finally {
                g2.dispose();
            }
        }
    }

    static final class FakeEditor extends JPanel {
        FakeEditor() {
            setOpaque(true);
            setBackground(BG);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
                FontMetrics fm = g2.getFontMetrics();
                String[] rows = {
                        "public sealed class SubInvoker", "    ⋯  18 lines  ⋯",
                        "    public Money Apply(Order order)", "    {",
                        "        return order.Lines.Sum(l => l.Total);", "    }",
                };
                int[] nos = {15, 0, 68, 69, 70, 71};
                for (int i = 0; i < rows.length; i++) {
                    int baseline = 2 + i * LINE_H + fm.getAscent();
                    if (nos[i] > 0) {
                        g2.setColor(GUTTER_FG);
                        g2.drawString(String.valueOf(nos[i]), 6, baseline);
                    }
                    g2.setColor(CODE_FG);
                    g2.drawString(rows[i], 40, baseline);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * A caption border whose drawn line is at a **known** y.
     *
     * Swing's `TitledBorder` puts its line at the caption's mid-height and does not say where that is, which is
     * how constraint 16 became an eye finding rather than a number. Production replaces it with this for the same
     * reason the 1a‴ probe replaced a re-derived rectangle with one definition: a leader that has to land on a
     * line needs that line's position to be a value, not an inference.
     */
    static final class CaptionBorder extends AbstractBorder {
        private final Color line;

        CaptionBorder(Color line) { this.line = line; }

        /** The row the top line is actually drawn in, relative to the component's top edge. */
        static int drawnTopOffset() { return CAPTION_LINE_Y; }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(CAPTION_H, 1, 1, 1);
            return insets;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
                FontMetrics fm = g2.getFontMetrics();
                String caption = "Fixture.Core/SubInvoker.cs  ·  SubInvoker.Apply";
                int textX = x + 10;
                int textW = fm.stringWidth(caption);
                int lineY = y + drawnTopOffset();
                g2.setColor(line);
                g2.drawLine(x + 2, lineY, textX - 4, lineY);
                g2.drawLine(textX + textW + 4, lineY, x + w - 3, lineY);
                g2.drawLine(x, lineY + 2, x, y + h - 1);
                g2.drawLine(x + w - 1, lineY + 2, x + w - 1, y + h - 1);
                g2.drawLine(x, y + h - 1, x + w - 1, y + h - 1);
                g2.setColor(blend(line, CODE_FG, 0.4));
                g2.drawString(caption, textX, y + CAPTION_H - 3);
            } finally {
                g2.dispose();
            }
        }
    }

    static int drawnOffsetFor(Chrome chrome) {
        return chrome == Chrome.TITLED_BORDER ? CaptionBorder.drawnTopOffset() : 0;
    }

    /**
     * The leader. Three shapes; `FLARE` is gone (dismissed in 1a‴ before the sandbox opened).
     *
     * `greedy` exists only as the control for the mouse check — it is the un-narrowed `contains()`, kept so the
     * PASS above means something.
     */
    static final class LeaderStrip extends JComponent {
        private final NestFrame frame;
        private final Leader leader;
        boolean greedy = false;

        LeaderStrip(NestFrame frame, Leader leader) {
            this.frame = frame;
            this.leader = leader;
            setOpaque(false);
        }

        /**
         * Only the strip's own band, not the rows it overlaps into.
         *
         * 1a″'s `contains()` lesson one layer down: this component is above the child, so the default `contains`
         * would take every event in the child's top rows.
         */
        @Override
        public boolean contains(int x, int y) {
            if (greedy) return super.contains(x, y);
            return x >= 0 && x < getWidth() && y >= 0 && y < STRIP_H;
        }

        @Override
        public Dimension getPreferredSize() { return new Dimension(40, frame.stripHeight()); }

        @Override
        protected void paintComponent(Graphics g) {
            ContentMock content = (ContentMock) SwingUtilities.getAncestorOfClass(ContentMock.class, this);
            if (content == null) return;
            Rectangle box = content.tokenBox();
            Point l = SwingUtilities.convertPoint(content, new Point(box.x, box.y + box.height), this);
            Point r = SwingUtilities.convertPoint(content, new Point(box.x + box.width, box.y + box.height), this);
            int w = getWidth();
            // The tips land here: the strip's own band plus the chrome's drawn-line offset, minus the clipped row.
            int bottom = STRIP_H + frame.chromeOffset() - 1;
            if (w <= 2 || bottom <= 0) return;

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(LEADER);
                g2.setStroke(frame.isCollapsed()
                        ? new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f,
                                new float[]{3f, 3f}, 0f)
                        : new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(path(leader, l.x, r.x, w, bottom));
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * The three shapes, as one function so the probe and production cannot disagree about them.
     *
     * - `CORNERS` — straight from the box's bottom corners to the frame's top corners. 1a‴'s default.
     * - `BRACE` — `{` opening downwards: a stem out of the box, a spine across the frame, tips turning down at
     *   both ends. The shape that reads as "this expands to the whole of that", and the one item 3 wants when the
     *   chrome's drawn edge is not its bounds edge.
     * - `ARROW` — out of the box's bottom-**right** corner, right, then down. One-ended, quiet, and the shape the
     *   split fallback should use (item 12).
     */
    static GeneralPath path(Leader leader, int x1, int x2, int w, int bottom) {
        GeneralPath p = new GeneralPath();
        float right = w - 1f;
        switch (leader) {
            case CORNERS -> {
                p.moveTo(x1, 0);
                p.lineTo(0, bottom);
                p.moveTo(x2, 0);
                p.lineTo(right, bottom);
            }
            case BRACE -> {
                float spine = bottom * 0.55f;
                float arc = Math.min(6f, bottom / 3f);
                float mid = (x1 + x2) / 2f;
                p.moveTo(mid, 0);
                p.lineTo(mid, spine - arc);
                // left half of the spine, then the tip turning down to the frame's corner
                p.moveTo(mid - arc, spine);
                p.lineTo(arc, spine);
                p.quadTo(0, spine, 0, spine + arc);
                p.lineTo(0, bottom);
                // right half
                p.moveTo(mid + arc, spine);
                p.lineTo(right - arc, spine);
                p.quadTo(right, spine, right, spine + arc);
                p.lineTo(right, bottom);
                // the stem's shoulders
                p.moveTo(mid - arc, spine);
                p.quadTo(mid, spine, mid, spine - arc);
                p.moveTo(mid + arc, spine);
                p.quadTo(mid, spine, mid, spine - arc);
            }
            case ARROW -> {
                float arc = Math.min(5f, bottom / 3f);
                p.moveTo(x2, 0);
                p.lineTo(right - arc, 0);
                p.quadTo(right, 0, right, arc);
                p.lineTo(right, bottom);
            }
        }
        return p;
    }

    // ══════════════════════════ the token-box mock ══════════════════════════

    /**
     * One line of monospace code with a token in the middle, and three ways to paint it: everything, the
     * neighbours only, and the box only.
     *
     * The separation is what makes "does the box touch the text" a count instead of an opinion — an intersection
     * of two ink masks, not a look at a screenshot.
     */
    static final class CodeLineMock {
        static final String PREFIX = "    var priced = ";
        static final String TOKEN = "_pricing.Handle(order, verdict)";
        static final String SUFFIX = ";";
        static final String ABOVE = "    int someParam = SomeSysCall();";
        static final String BELOW = "    Audit(priced, someParam, subtotal);";

        final Font code = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        final int w = 720;
        final int h = 3 * LINE_H + 8;

        int rowTop() { return LINE_H; }

        FontMetrics fm(Graphics2D g) { return g.getFontMetrics(code); }

        int glyphBoxHeight() {
            BufferedImage s = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = s.createGraphics();
            try {
                return fm(g).getAscent() + fm(g).getDescent();
            } finally {
                g.dispose();
            }
        }

        /**
         * The rectangle a padded box occupies. `pad` 0 is `EffectType.BOXED`: exactly the glyph bounds.
         *
         * The vertical inflate is deliberately half the horizontal one and clamped to the row — a box as tall as
         * it is wide-padded would reach the line above, which is the other half of the collision question.
         */
        Rectangle tokenBox(int pad) {
            BufferedImage s = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = s.createGraphics();
            try {
                FontMetrics fm = fm(g);
                int x = 8 + fm.stringWidth(PREFIX);
                int tw = fm.stringWidth(TOKEN);
                int top = rowTop() + (LINE_H - glyphBoxHeight()) / 2;
                int vPad = Math.min(pad / 2 + (pad > 0 ? 1 : 0), Math.max((LINE_H - glyphBoxHeight()) / 2, 0));
                return new Rectangle(x - pad, top - vPad, tw + 2 * pad, glyphBoxHeight() + 2 * vPad);
            } finally {
                g.dispose();
            }
        }

        /** Every glyph on the three rows **except** the token's — the ink an inflated box must not touch. */
        BufferedImage paintNeighbourInk() {
            return paintText(true, true);
        }

        /** Only the rows above and below, for the vertical half of the question. */
        BufferedImage paintAdjacentRowInk() {
            return paintText(true, false);
        }

        /** The token's own glyphs, and nothing else — the ink the box must stand *off* rather than avoid. */
        BufferedImage paintTokenInk() {
            BufferedImage img = blank();
            Graphics2D g = img.createGraphics();
            try {
                g.setColor(BG);
                g.fillRect(0, 0, w, h);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(code);
                FontMetrics fm = g.getFontMetrics();
                g.setColor(CODE_FG);
                g.drawString(TOKEN, 8 + fm.stringWidth(PREFIX), rowTop() + fm.getAscent());
            } finally {
                g.dispose();
            }
            return img;
        }

        /**
         * The horizontal gap, in pixels, between the box's vertical strokes and the nearest token ink.
         *
         * The number constraint 18 is actually about: `EffectType.BOXED` puts the stroke inside the first glyph's
         * advance box, a pixel from its ink, and *that* is what reads as clipping — not a literal overlap, which
         * is why a collision count reported the defect as absent.
         */
        int clearanceToTokenInk(int pad) {
            BufferedImage ink = paintTokenInk();
            Rectangle box = tokenBox(pad);
            int left = Integer.MAX_VALUE, right = Integer.MAX_VALUE;
            for (int y = box.y; y < box.y + box.height; y++) {
                if (y < 0 || y >= h) continue;
                for (int x = Math.max(box.x, 0); x < Math.min(box.x + box.width, w); x++) {
                    if (isInk(ink.getRGB(x, y))) {
                        left = Math.min(left, x - box.x);
                        break;
                    }
                }
                for (int x = Math.min(box.x + box.width, w) - 1; x >= Math.max(box.x, 0); x--) {
                    if (isInk(ink.getRGB(x, y))) {
                        right = Math.min(right, (box.x + box.width) - x);
                        break;
                    }
                }
            }
            if (left == Integer.MAX_VALUE || right == Integer.MAX_VALUE) return -1;
            return Math.min(left, right);
        }

        private BufferedImage paintText(boolean blankToken, boolean includeOwnRow) {
            BufferedImage img = blank();
            Graphics2D g = img.createGraphics();
            try {
                g.setColor(BG);
                g.fillRect(0, 0, w, h);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(code);
                FontMetrics fm = g.getFontMetrics();
                g.setColor(CODE_FG);
                g.drawString(ABOVE, 8, fm.getAscent() + 1);
                g.drawString(BELOW, 8, 2 * LINE_H + fm.getAscent() + 1);
                if (includeOwnRow) {
                    int base = rowTop() + fm.getAscent();
                    g.drawString(PREFIX, 8, base);
                    int tokenX = 8 + fm.stringWidth(PREFIX);
                    if (!blankToken) g.drawString(TOKEN, tokenX, base);
                    g.drawString(SUFFIX, tokenX + fm.stringWidth(TOKEN), base);
                }
            } finally {
                g.dispose();
            }
            return img;
        }

        /** The box alone, so an intersection with an ink mask is exactly "the stroke is on a glyph". */
        BufferedImage paintBoxOnly(int pad) {
            BufferedImage img = blank();
            Graphics2D g = img.createGraphics();
            try {
                g.setColor(BG);
                g.fillRect(0, 0, w, h);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(LEADER);
                g.setStroke(new BasicStroke(1.2f));
                Rectangle r = tokenBox(pad);
                g.drawRoundRect(r.x, r.y, r.width, r.height, 5, 5);
            } finally {
                g.dispose();
            }
            return img;
        }

        /** The whole thing, for the eye. */
        BufferedImage paintAll(int pad) {
            BufferedImage img = paintText(false, true);
            Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(LEADER);
                g.setStroke(new BasicStroke(1.2f));
                Rectangle r = tokenBox(pad);
                g.drawRoundRect(r.x, r.y, r.width, r.height, 5, 5);
            } finally {
                g.dispose();
            }
            return img;
        }

        private BufferedImage blank() { return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); }
    }

    /**
     * Pixels where both images have ink.
     *
     * The tolerance is not decoration: a count over rendered text has a floor and it is not zero (1a″'s
     * subpixel-fringe lesson), so "ink" is anything meaningfully darker than the background rather than any
     * non-white pixel at all.
     */
    static int countIntersection(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < Math.min(a.getHeight(), b.getHeight()); y++) {
            for (int x = 0; x < Math.min(a.getWidth(), b.getWidth()); x++) {
                if (isInk(a.getRGB(x, y)) && isInk(b.getRGB(x, y))) n++;
            }
        }
        return n;
    }

    /** Meaningfully darker than white — above the antialiasing floor a fringe count would otherwise pick up. */
    static boolean isInk(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (255 - r) + (255 - g) + (255 - b) > 150;
    }

    // ══════════════════════════ plumbing ══════════════════════════

    static void writeMock(File dir, String name, Chrome chrome, Leader leader, boolean collapsed) throws Exception {
        FrameMock m = new FrameMock(chrome, leader, collapsed, true);
        m.layOut(920, 480);
        BufferedImage img = paint(m.content, 920, 480);
        ImageIO.write(img, "png", new File(dir, name));
        System.out.printf("  wrote %-34s %dx%d  chrome=%-14s leader=%-7s collapsed=%b offset=%dpx%n",
                name, img.getWidth(), img.getHeight(), chrome, leader, collapsed, m.chromeOffset());
    }

    static void writeBoxComparison(File dir, String name) throws Exception {
        CodeLineMock line = new CodeLineMock();
        BufferedImage a = line.paintAll(0);
        BufferedImage b = line.paintAll(3);
        BufferedImage both = new BufferedImage(a.getWidth(), a.getHeight() * 2 + 6, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = both.createGraphics();
        try {
            g.setColor(BG);
            g.fillRect(0, 0, both.getWidth(), both.getHeight());
            g.drawImage(a, 0, 0, null);
            g.drawImage(b, 0, a.getHeight() + 6, null);
        } finally {
            g.dispose();
        }
        ImageIO.write(both, "png", new File(dir, name));
        System.out.printf("  wrote %-34s %dx%d  (top: pad=0, what BOXED does; bottom: pad=3)%n",
                name, both.getWidth(), both.getHeight());
    }

    static BufferedImage paint(JComponent c, int w, int h) {
        BufferedImage img = new BufferedImage(Math.max(w, 1), Math.max(h, 1), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(BG);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            c.paint(g);
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * **Hue, not a tolerance ball.** A tolerance wide enough to admit an antialiased half-covered stroke is also
     * wide enough to admit the neighbouring grey — which is how the first run matched gutter text and a chrome
     * border and called them the leader. So the leader is the only blue thing in the mock and the chrome the only
     * warm one, and each matcher asks that question directly.
     */
    static boolean isLeaderInk(int rgb) {
        int r = (rgb >> 16) & 0xFF, b = rgb & 0xFF;
        return isInk(rgb) && b - r >= 40;
    }

    static boolean isChromeInk(int rgb) {
        int r = (rgb >> 16) & 0xFF, b = rgb & 0xFF;
        return isInk(rgb) && r - b >= 8;
    }

    static boolean anyLeaderInk(BufferedImage img, int x0, int y0, int w, int h) {
        for (int y = Math.max(y0, 0); y < Math.min(y0 + h, img.getHeight()); y++) {
            for (int x = Math.max(x0, 0); x < Math.min(x0 + w, img.getWidth()); x++) {
                if (isLeaderInk(img.getRGB(x, y))) return true;
            }
        }
        return false;
    }

    static Color blend(Color from, Color to, double t) {
        return new Color(
                (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
                (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
                (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t));
    }

    static String name(Component c) { return c == null ? "<null>" : c.getClass().getSimpleName(); }

    /** Identity, named: the count printed for a pad is the neighbour-ink intersection, not a derived quantity. */
    static int neighbourHits(int intersection) { return intersection; }

    /**
     * How many pixels of the box's stroke may fall on a neighbouring glyph before it stops being a grazed edge.
     *
     * A monospace glyph at 13pt is ~7×13 px of cell and a few dozen pixels of ink, so single digits is a stroke
     * crossing an outline. Stated as a budget rather than as zero because the fixture line has no space before its
     * `;` — the tight case, on purpose.
     */
    static final int GRAZE_BUDGET = 8;

    static void report(boolean ok, String what, String detail) {
        if (ok) passes++; else failures++;
        System.out.printf("  %s  %s%n        %s%n", ok ? "PASS" : "FAIL", what, detail);
    }

    private NestPolishProbe() {}
}
