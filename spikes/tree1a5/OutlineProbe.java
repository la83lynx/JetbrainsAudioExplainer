import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Phase 1a⁵ pre-flight probe — **the one-outline rule, measured in pixels before any Kotlin changes.**
 *
 * `TreeOfCode.md` constraint 16 says the token box, the leader and the child's frame are ONE outline, not three
 * drawings that meet: in `CORNERS`/`BRACE` the box gives up its bottom edge and the frame gives up its top edge,
 * and the leader *is* that stretch of the outline. §1a⁵ 1 specifies the three shapes to the pixel. All of it is
 * pure Java2D, so all of it is answerable here — in two seconds rather than in a 4–7 minute sandbox launch.
 *
 * The questions, in the order the plan raises them:
 *
 *  1. **Is the outline actually continuous?** Three drawings own three stretches of it, and the junctions are the
 *     part that can only be wrong by one pixel: the box's side walls end where the leader begins, and the leader
 *     ends where the frame's side walls begin. §1a⁵ 1 calls the second one out by name — a stroke drawn to
 *     `y == height` is clipped away, so the leader ends at `height − 1` while the frame's walls start at their own
 *     row 0, "a 1 px stagger unless something absorbs it". This measures whether it needs absorbing.
 *  2. **Are the suppressed edges actually absent, and present again in `ARROW`?** The same measurement inverted,
 *     which is why `ARROW` is both a mode and the control for the other two.
 *  3. **Is `BRACE` two L-shaped halves** — one right angle each, at the outer end — with no central stem and no
 *     spine across the middle? The built version had both, which is what the round sent back. The old path is
 *     inlined here as a control that must fail the emptiness test.
 *  4. **Does `ARROW` stop short of the frame**, ending in an arrowhead that points at the top border without
 *     touching it, with its turn-down x strictly between the box's right edge and the frame's?
 *
 * **The single definition this probe exists to pin down** is `BOX_BOTTOM`: the row where the box's bottom edge
 * would be *is* the strip's row 0. Constraint 16's recorded lesson is that sharing the path was not enough — the
 * **origin** has to be shared — and its corollary, that sharing one number was not enough when a second (the
 * corner radius) was not shared. So there is one row constant and one [ARC] here, and the Kotlin transcription
 * takes both.
 *
 * Every check about geometry rather than arithmetic carries a **control that must fail**: a staggered outline, the
 * old brace, an arrow drawn into the frame. `spikes/b3a/`'s first version had none and its PASS was worthless.
 *
 *   javac -d out OutlineProbe.java && java -cp out OutlineProbe
 *
 * Writes PNGs next to itself — the three modes, a strip-height sweep and an arrow-turn sweep — because
 * `stripHeight` and `ARROW`'s turn-down x are numbers to judge by eye at a stated width (constraint 9), and the
 * width is stated: {@value #W} px.
 */
public final class OutlineProbe {

    // ── the canvas, at a stated width (constraint 9) ──

    static final int W = 640;
    static final int LINE_H = 19;
    static final int GUTTER = 34;

    /** The frame is `fullWidth`, so it spans the host editor's content from x=0; [NestStyle] insets it on the right only. */
    static final int FRAME_L = GUTTER;
    static final int FRAME_R_INSET = 14;

    static final int STRIP_H = 22;
    static final int CAPTION_H = 17;
    static final int CHILD_ROWS = 4;
    static final int GAP_BELOW = 6;
    static final int ROWS_ABOVE = 3;
    static final int ROWS_AFTER = 2;

    /** The call line's top row. */
    static final int CALL_ROW_Y = ROWS_ABOVE * LINE_H;

    /**
     * **The one definition.** The row the box's bottom edge sits on, which *is* the strip's row 0 and *is* the
     * frame's top row. Every one of the three drawings measures from this and none of them derives it again.
     *
     * It is `CALL_ROW_Y + LINE_H` — one past the call row's last pixel — rather than `+ LINE_H − 1`, and that is
     * the whole of `ARROW`'s collinearity: its horizontal run leaves the box's bottom edge and continues at the
     * same y, so a bottom edge one row higher than the leader's origin would read as a kink in a straight line.
     */
    static final int BOX_BOTTOM = CALL_ROW_Y + LINE_H;

    static final int FRAME_T = BOX_BOTTOM;
    static final int CHILD_T = FRAME_T + STRIP_H;
    static final int CHILD_H = CAPTION_H + CHILD_ROWS * LINE_H + 2;
    static final int H = CHILD_T + CHILD_H + GAP_BELOW + ROWS_AFTER * LINE_H;

    static final int TOKEN_PAD = 2;

    /** One radius for the frame's rounded corners **and** the brace's outer turn — the second number, shared. */
    static final int ARC = 5;

    static final float STROKE_W = 1.4f;
    static final int HEAD_H = 6;
    static final int HEAD_W = 7;

    /** How far short of the frame's top border the arrowhead's tip stops. "Points at, without touching." */
    static final int TIP_GAP = 3;

    static final float ARROW_TURN = 0.5f;

    // ── ink: one saturated blue for the whole outline, greys for everything else ──

    /**
     * The outline's colour, and it is one colour for all three drawings.
     *
     * Constraint 18 already required the box and the leader to share it ("they are the same object to the reader's
     * eye"); constraint 16 makes it stronger by making them literally one path, so the *frame's* border joins the
     * same derivation. A saturated blue against greys is also what makes [#isOutlineInk] able to tell the outline
     * from a caption band or a code glyph — which is the measurement, not the palette.
     */
    static final Color OUTLINE = new Color(0x1E, 0x6F, 0xC8);

    static final Color BG = Color.WHITE;
    static final Color CODE_FG = new Color(0x2B, 0x2B, 0x2B);
    static final Color GUTTER_FG = new Color(0x99, 0x99, 0x99);
    static final Color CAPTION_BG = new Color(0xE8, 0xE8, 0xE8);
    static final Color CAPTION_FG = new Color(0x77, 0x77, 0x77);
    static final Color CHILD_BG = new Color(0xFC, 0xFC, 0xFC);

    static int passes = 0;
    static int failures = 0;

    enum Leader { CORNERS, BRACE, ARROW }

    /** What is being rendered: the specified thing, or one of the deliberate defects that must be caught. */
    enum Variant {
        /** The specification. */
        SPEC,
        /** Control: the leader begins 3 rows below the box and ends 3 rows above the frame. Must show gaps. */
        STAGGERED,
        /** Control: 1a‴'s brace — a central stem plus a spine across the width. Must fail the emptiness test. */
        OLD_BRACE,
        /** Control: the arrowhead drawn down to the frame's top row. Must fail "does not touch". */
        ARROW_TOUCHING,
        /** Control: nothing suppressed — a 4-sided box above a 4-sided frame, with a leader between them. */
        FOUR_SIDED,
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".");
        System.out.println("Phase 1a⁵ pre-flight probe — the one-outline rule (constraint 16, §1a⁵ 1)");
        System.out.println("canvas " + W + "x" + H + "px  lineHeight=" + LINE_H + "  stripHeight=" + STRIP_H
                + "  arc=" + ARC + "  stroke=" + STROKE_W + "px");
        System.out.println("BOX_BOTTOM=" + BOX_BOTTOM + " (= strip row 0 = frame top)  frame=" + FRAME_L + ".."
                + (W - FRAME_R_INSET) + "  child top=" + CHILD_T);
        System.out.println();

        // ── the definition itself ──
        checkOneRowDefinition();

        // ── question 1: continuity across the three owners ──
        for (Leader l : Leader.values()) checkOutlineIsContinuous(l);
        checkStaggeredOutlineShowsGaps();

        // ── question 2: which edges each mode draws ──
        for (Leader l : Leader.values()) checkSuppressedEdges(l);
        checkFourSidedControlKeepsBothEdges();

        // ── question 3: the brace's shape ──
        checkBraceIsTwoLShapedHalves();
        checkOldBraceStillHasItsSpine();
        checkBraceClearsTheCaptionRow();

        // ── question 4: the arrow's end and its one free parameter ──
        checkArrowStopsShortOfTheFrame();
        checkArrowTouchingControlTouches();
        checkArrowTurnSitsBetweenTheEdges();
        checkArrowRunIsCollinearWithTheBoxBottom();

        // ── the known control, re-asserted because the leader's `bottom` depends on it ──
        checkStrokeAtHeightIsClipped();

        // ── for the eye ──
        for (Leader l : Leader.values()) {
            write(dir, "outline-" + l.name().toLowerCase() + ".png", render(l, Variant.SPEC, STRIP_H, ARROW_TURN));
        }
        for (int h : new int[]{14, 22, 30}) {
            write(dir, "strip-" + h + "-corners.png", render(Leader.CORNERS, Variant.SPEC, h, ARROW_TURN));
            write(dir, "strip-" + h + "-brace.png", render(Leader.BRACE, Variant.SPEC, h, ARROW_TURN));
        }
        for (float t : new float[]{0.30f, 0.50f, 0.70f}) {
            write(dir, "arrow-turn-" + Math.round(t * 100) + ".png", render(Leader.ARROW, Variant.SPEC, STRIP_H, t));
        }
        write(dir, "control-staggered.png", render(Leader.CORNERS, Variant.STAGGERED, STRIP_H, ARROW_TURN));
        write(dir, "control-old-brace.png", render(Leader.BRACE, Variant.OLD_BRACE, STRIP_H, ARROW_TURN));
        write(dir, "control-four-sided.png", render(Leader.CORNERS, Variant.FOUR_SIDED, STRIP_H, ARROW_TURN));

        System.out.println();
        System.out.println(passes + " pass, " + failures + " fail");
        if (failures > 0) {
            System.out.println("Read the first run as a report on the probe: 1a⁗'s opened 18/5 and all five "
                    + "failures were its own.");
        }
    }

    // ────────────────────────────── checks ──────────────────────────────

    /**
     * The box's bottom-edge row, the strip's row 0 and the frame's top row are one number.
     *
     * Arithmetic rather than pixels, and it is here because the 1a‴ defect was exactly this shape: the split
     * fallback anchored its leader to a line's *centre* while the nested surface anchored to the box's *bottom*,
     * and two notions of "where the call is" is what one shared vocabulary is for.
     */
    static void checkOneRowDefinition() {
        if (BOX_BOTTOM == FRAME_T && FRAME_T == CALL_ROW_Y + LINE_H) {
            pass("the box's bottom edge, the strip's row 0 and the frame's top row are one constant (" + BOX_BOTTOM + ")");
        } else {
            fail("one row definition", "BOX_BOTTOM=" + BOX_BOTTOM + " FRAME_T=" + FRAME_T
                    + " — two derivations of the origin, which is the 1a‴ mistake");
        }
    }

    /**
     * **Is it one outline?** Asked as connectivity, which is what the words mean.
     *
     * The probe's first run asked it the wrong way — an ink-free-row walk down a fixed column at the box's wall —
     * and failed all three modes, because a diagonal legitimately leaves that column at 6 px per row and a
     * horizontal run leaves it entirely. That is the loop's step 3 in miniate: the first run is a report on the
     * probe, and a column scan cannot follow a path that moves.
     *
     * So: label the outline's ink into 8-connected components and ask which drawings share one. The specification
     * makes this the discriminating measurement for all three modes at once, in *opposite* directions —
     *
     *  - `CORNERS`/`BRACE`: the box, the leader and the frame are **one** component. That is constraint 16.
     *  - `ARROW`: the box and the leader are one component and the frame is a **separate** one. "A reference, not
     *    a join" is not a stylistic note — it is a different connectivity, and a probe that only tested
     *    continuity would call the correct arrow broken.
     */
    static void checkOutlineIsContinuous(Leader leader) {
        BufferedImage img = render(leader, Variant.SPEC, STRIP_H, ARROW_TURN);
        int[] labels = label(img);
        int box = labelNear(img, labels, boxMidTop(), CALL_ROW_Y, 3);
        int frame = labelNear(img, labels, (FRAME_L + W - FRAME_R_INSET) / 2, CHILD_T + CHILD_H - 1, 3);
        int lead = labelNear(img, labels, leaderProbeX(leader), leaderProbeY(leader), 4);
        int parts = componentCount(labels);

        if (box == 0 || frame == 0 || lead == 0) {
            fail(leader + " outline seeds", "a seed found no ink within its radius (box=" + box + " leader="
                    + lead + " frame=" + frame + ") — the check is aiming at empty canvas, not measuring a shape");
            return;
        }
        if (leader == Leader.ARROW) {
            if (box == lead && frame != box) {
                pass("ARROW: the box and the leader are one component and the frame is a separate one ("
                        + parts + " components) — a reference, not a join");
            } else {
                fail("ARROW connectivity", "box=" + box + " leader=" + lead + " frame=" + frame + " in " + parts
                        + " components; ARROW must leave both outlines closed and end short of the frame");
            }
            return;
        }
        if (box == lead && lead == frame) {
            pass(leader + ": the box, the leader and the frame are ONE 8-connected component (" + parts
                    + " total) — constraint 16, measured");
        } else {
            fail(leader + " connectivity", "box=" + box + " leader=" + lead + " frame=" + frame + " in " + parts
                    + " components — the three drawings meet somewhere with a gap, which is §1a⁵ 1's seam and "
                    + "means the strip needs an overlap after all");
        }
    }

    /** Control: a leader deliberately 3 px short at both ends must break the outline into separate components. */
    static void checkStaggeredOutlineShowsGaps() {
        BufferedImage img = render(Leader.CORNERS, Variant.STAGGERED, STRIP_H, ARROW_TURN);
        int[] labels = label(img);
        int box = labelNear(img, labels, boxMidTop(), CALL_ROW_Y, 3);
        int frame = labelNear(img, labels, (FRAME_L + W - FRAME_R_INSET) / 2, CHILD_T + CHILD_H - 1, 3);
        int lead = labelNear(img, labels, leaderProbeX(Leader.CORNERS), leaderProbeY(Leader.CORNERS), 4);
        if (box != 0 && lead != 0 && frame != 0 && box != lead && lead != frame) {
            pass("control: a leader staggered 3px at both ends leaves three separate components (box=" + box
                    + " leader=" + lead + " frame=" + frame + ") — the connectivity test discriminates");
        } else {
            fail("continuity control", "the staggered control was not caught (box=" + box + " leader=" + lead
                    + " frame=" + frame + ") — the connectivity PASSes above cannot tell a met junction from a "
                    + "missed one, so they mean nothing");
        }
    }

    /** A point the leader's own ink passes through, per mode — and only its own. */
    static int leaderProbeX(Leader leader) {
        switch (leader) {
            case CORNERS:
                return FRAME_L + (boxLeft() - FRAME_L) / 2;
            case BRACE:
                return FRAME_L + (boxLeft() - FRAME_L) / 2;
            default:
                return turnX(ARROW_TURN);
        }
    }

    static int leaderProbeY(Leader leader) {
        switch (leader) {
            case CORNERS:
                return FRAME_T + STRIP_H / 2;
            case BRACE:
                return BOX_BOTTOM;
            default:
                return CHILD_T - TIP_GAP - 2;
        }
    }

    static int boxMidTop() {
        return (boxLeft() + boxRight()) / 2;
    }

    /**
     * **Which edges each mode draws** — constraint 16 as two pixel counts.
     *
     * The middle of the box's bottom row and the middle of the frame's top row: empty in `CORNERS`/`BRACE`,
     * inked in `ARROW`. Sampled away from the corners, where the leader's own tips and turns legitimately are.
     */
    static void checkSuppressedEdges(Leader leader) {
        BufferedImage img = render(leader, Variant.SPEC, STRIP_H, ARROW_TURN);
        boolean expect = leader == Leader.ARROW;

        double boxRun = inkFraction(img, boxLeft() + ARC + 4, boxRight() - ARC - 4, BOX_BOTTOM, 1);
        double frameRun = inkFraction(img, FRAME_L + ARC + 6, W - FRAME_R_INSET - ARC - 7, CHILD_T, 1);

        checkEdge(leader + " box bottom edge", boxRun, expect);
        checkEdge(leader + " frame top edge", frameRun, expect);
    }

    static void checkEdge(String what, double fraction, boolean expected) {
        String seen = String.format("%.0f%% inked", fraction * 100);
        if (expected && fraction > 0.90) {
            pass(what + ": present (" + seen + ") — ARROW leaves both outlines closed");
        } else if (!expected && fraction < 0.02) {
            pass(what + ": absent (" + seen + ") — the leader owns this stretch of the outline");
        } else {
            fail(what, "expected " + (expected ? "an edge" : "no edge") + " and measured " + seen
                    + " — a leader mode that does not change which edges are drawn is three drawings that meet, "
                    + "not one outline");
        }
    }

    /** Control: with nothing suppressed, both runs are inked — so the absence measured above is the suppression. */
    static void checkFourSidedControlKeepsBothEdges() {
        BufferedImage img = render(Leader.CORNERS, Variant.FOUR_SIDED, STRIP_H, ARROW_TURN);
        double boxRun = inkFraction(img, boxLeft() + ARC + 4, boxRight() - ARC - 4, BOX_BOTTOM, 1);
        double frameRun = inkFraction(img, FRAME_L + ARC + 6, W - FRAME_R_INSET - ARC - 7, CHILD_T, 1);
        if (boxRun > 0.90 && frameRun > 0.90) {
            pass("control: unsuppressed, both the box's bottom and the frame's top are inked — so the two "
                    + "absences above are the suppression and not the sampling");
        } else {
            fail("suppression control", String.format(
                    "the 4-sided control measured box=%.0f%% frame=%.0f%%; the rows sampled are not where these "
                            + "edges are drawn, so every 'absent' PASS above is measuring empty space",
                    boxRun * 100, frameRun * 100));
        }
    }

    /**
     * **`BRACE` is two L-shaped halves and nothing else.**
     *
     * The shape the round sent back had a central stem out of the token and a spine across the frame's width, so
     * the discriminating measurement is the *interior*: below the horizontal run, above the frame, strictly
     * between the two side walls, there must be no ink at all. A brace that draws anything in there is a T.
     */
    static void checkBraceIsTwoLShapedHalves() {
        BufferedImage img = render(Leader.BRACE, Variant.SPEC, STRIP_H, ARROW_TURN);

        // The horizontal runs, at the box's bottom-edge level, reaching outward to each side wall.
        double left = inkFraction(img, FRAME_L + ARC + 2, boxLeft() - 2, BOX_BOTTOM, 1);
        double right = inkFraction(img, boxRight() + 2, W - FRAME_R_INSET - ARC - 3, BOX_BOTTOM, 1);
        // The interior: everything the halves are not.
        int interior = countInk(img, FRAME_L + ARC + 3, W - FRAME_R_INSET - ARC - 4, BOX_BOTTOM + 3, CHILD_T - 2);

        if (left > 0.90 && right > 0.90 && interior == 0) {
            pass(String.format("BRACE is two L-shaped halves: horizontal runs %.0f%%/%.0f%% inked at the box's "
                    + "bottom level, and 0 ink in the strip's interior — no stem, no spine", left * 100, right * 100));
        } else {
            fail("brace shape", String.format(
                    "left run %.0f%%, right run %.0f%%, interior ink %dpx — the specified shape is one right angle "
                            + "per half at the OUTER end, with nothing between them",
                    left * 100, right * 100, interior));
        }
    }

    /** Control: 1a‴'s brace, inlined. Its stem and spine must be found by the same interior test. */
    static void checkOldBraceStillHasItsSpine() {
        BufferedImage img = render(Leader.BRACE, Variant.OLD_BRACE, STRIP_H, ARROW_TURN);
        int interior = countInk(img, FRAME_L + ARC + 3, W - FRAME_R_INSET - ARC - 4, BOX_BOTTOM + 3, CHILD_T - 2);
        if (interior > 20) {
            pass("control: the built brace's stem and spine are found in the interior (" + interior
                    + "px) — the emptiness test discriminates");
        } else {
            fail("brace control", "the old brace measured only " + interior + "px of interior ink, so the "
                    + "emptiness test above cannot tell the specified shape from the one that was sent back");
        }
    }

    /**
     * `BRACE`'s stated advantage over `CORNERS`: *"unlike diagonals braces do not overlap with header text"*.
     *
     * Worth measuring rather than repeating, because the answer moved: with the strip no longer overlapping the
     * child (constraint 16 dissolved the offset), **neither** shape reaches the caption row, so the advantage is
     * now about the strip's own band and not about the caption. The number is what says so.
     */
    static void checkBraceClearsTheCaptionRow() {
        int y0 = CHILD_T + 1;
        int y1 = CHILD_T + CAPTION_H;
        int brace = countInk(render(Leader.BRACE, Variant.SPEC, STRIP_H, ARROW_TURN),
                FRAME_L + 2, W - FRAME_R_INSET - 3, y0, y1);
        int corners = countInk(render(Leader.CORNERS, Variant.SPEC, STRIP_H, ARROW_TURN),
                FRAME_L + 2, W - FRAME_R_INSET - 3, y0, y1);
        if (brace == 0) {
            pass("BRACE draws no ink in the caption row (rows " + y0 + ".." + y1 + "); CORNERS draws " + corners
                    + "px there — with zero overlap both clear it, so the advantage is the strip's band, not the caption");
        } else {
            fail("brace clears the caption", "BRACE put " + brace + "px of ink in the child's caption row, which "
                    + "is the one thing it is kept for over CORNERS");
        }
    }

    /** The arrowhead points at the frame's top border **without touching it** — measured as the gap. */
    static void checkArrowStopsShortOfTheFrame() {
        BufferedImage img = render(Leader.ARROW, Variant.SPEC, STRIP_H, ARROW_TURN);
        int turn = turnX(ARROW_TURN);
        int lowest = lowestInkRow(img, turn - HEAD_W, turn + HEAD_W, BOX_BOTTOM, CHILD_T - 1);
        int gap = CHILD_T - lowest;
        if (lowest > 0 && gap >= 2) {
            pass("ARROW's head ends at row " + lowest + ", " + gap + "px short of the frame's top row (" + CHILD_T
                    + ") — it points at the border without touching it");
        } else {
            fail("arrow gap", "the arrowhead's lowest ink is at row " + lowest + " against the frame's top row "
                    + CHILD_T + " (gap " + gap + "px) — 'points at, without touching' is the difference between "
                    + "a reference and a join");
        }
    }

    /** Control: an arrow drawn to the strip's last row must be caught as touching. */
    static void checkArrowTouchingControlTouches() {
        BufferedImage img = render(Leader.ARROW, Variant.ARROW_TOUCHING, STRIP_H, ARROW_TURN);
        int turn = turnX(ARROW_TURN);
        int lowest = lowestInkRow(img, turn - HEAD_W, turn + HEAD_W, BOX_BOTTOM, CHILD_T - 1);
        if (CHILD_T - lowest < 2) {
            pass("control: an arrow drawn to the strip's last row is caught touching (lowest ink row " + lowest + ")");
        } else {
            fail("arrow gap control", "the touching control measured a " + (CHILD_T - lowest) + "px gap, so the "
                    + "gap check above would pass whatever the arrow did");
        }
    }

    /**
     * `ARROW`'s turn-down x is the only free parameter in any of the three shapes, and the constraint on it is
     * ordinal rather than aesthetic: strictly between the box's right edge and the frame's.
     */
    static void checkArrowTurnSitsBetweenTheEdges() {
        int frameR = W - FRAME_R_INSET - 1;
        StringBuilder seen = new StringBuilder();
        boolean ok = true;
        for (float t : new float[]{0.30f, 0.50f, 0.70f}) {
            int x = turnX(t);
            seen.append(String.format(" %.0f%%→x=%d", t * 100, x));
            if (x <= boxRight() || x >= frameR) ok = false;
        }
        if (ok) {
            pass("ARROW's turn-down x stays between the box's right edge (" + boxRight() + ") and the frame's ("
                    + frameR + ") at every fraction:" + seen + " — default " + Math.round(ARROW_TURN * 100) + "%");
        } else {
            fail("arrow turn", "a turn x landed on or past an edge:" + seen + " — running to the frame's far "
                    + "corner is what the round called 'unnatural', and past it the shape leaves the frame");
        }
    }

    /**
     * The run leaves the box's bottom edge **collinearly** — the same row, so it reads as the edge extended
     * rather than as a new line leaving a corner. This is what [#BOX_BOTTOM] being one constant buys, and it is
     * the only place in the three shapes where a 1 px stagger would be visible as a kink instead of as adjacency.
     */
    static void checkArrowRunIsCollinearWithTheBoxBottom() {
        BufferedImage img = render(Leader.ARROW, Variant.SPEC, STRIP_H, ARROW_TURN);
        int turn = turnX(ARROW_TURN);
        double run = inkFraction(img, boxRight() + 2, turn - ARC - 2, BOX_BOTTOM, 1);
        double above = inkFraction(img, boxRight() + 2, turn - ARC - 2, BOX_BOTTOM - 2, 0);
        double below = inkFraction(img, boxRight() + 2, turn - ARC - 2, BOX_BOTTOM + 2, 0);
        if (run > 0.90 && above < 0.05 && below < 0.05) {
            pass(String.format("ARROW's horizontal run is on the box's bottom row exactly (%.0f%% inked there, "
                    + "%.0f%%/%.0f%% two rows above/below) — collinear, not a kink", run * 100, above * 100, below * 100));
        } else {
            fail("arrow collinearity", String.format(
                    "run=%.0f%% on row %d, %.0f%% two rows above, %.0f%% two rows below — the extension has left "
                            + "the box's bottom-edge level, which is the one seam a horizontal line shows",
                    run * 100, BOX_BOTTOM, above * 100, below * 100));
        }
    }

    /**
     * The known control, re-asserted because every leader's `bottom` is `height − 1` *because* of it: a stroke
     * drawn at `y == height` is clipped out of existence.
     */
    static void checkStrokeAtHeightIsClipped() {
        int h = 12;
        BufferedImage at = strokeAt(h, h);
        BufferedImage inside = strokeAt(h, h - 1);
        int atInk = countInk(at, 0, 39, 0, h - 1);
        int insideInk = countInk(inside, 0, 39, 0, h - 1);
        if (atInk == 0 && insideInk > 0) {
            pass("a stroke at y == height is clipped away (" + atInk + "px) while y == height − 1 draws ("
                    + insideInk + "px) — which is why every leader ends one row inside its own band");
        } else {
            fail("clipping", "y==height drew " + atInk + "px and y==height−1 drew " + insideInk
                    + "px; if the first is non-zero the leader's bottom is off by one everywhere");
        }
    }

    // ────────────────────────────── the geometry under test ──────────────────────────────

    /**
     * **One definition of each leader shape**, in the strip's own space, to be transcribed into `LeaderArt`.
     *
     * `x1`/`x2` are the token box's bottom corners on **row 0** — which is [#BOX_BOTTOM], the row the box's
     * suppressed bottom edge would occupy. `w` is the strip's width; `b` is its last drawable row.
     */
    static GeneralPath leaderPath(Leader leader, int x1, int x2, int w, int b, float turnFraction, Variant v) {
        GeneralPath p = new GeneralPath();
        float right = w - 1;
        int top = v == Variant.STAGGERED ? 3 : 0;
        float bottom = v == Variant.STAGGERED ? b - 3 : b;
        float arc = Math.min(ARC, b / 3f);

        switch (leader) {
            case CORNERS:
                p.moveTo(x1, top);
                p.lineTo(0f, bottom);
                p.moveTo(x2, top);
                p.lineTo(right, bottom);
                break;

            case BRACE:
                if (v == Variant.OLD_BRACE) {
                    // 1a‴, inlined verbatim as the control: a central stem plus a spine across the width.
                    float spine = b * 0.55f;
                    float mid = (x1 + x2) / 2f;
                    p.moveTo(mid, 0f);
                    p.lineTo(mid, spine - arc);
                    p.moveTo(mid - arc, spine);
                    p.lineTo(arc, spine);
                    p.quadTo(0f, spine, 0f, spine + arc);
                    p.lineTo(0f, bottom);
                    p.moveTo(mid + arc, spine);
                    p.lineTo(right - arc, spine);
                    p.quadTo(right, spine, right, spine + arc);
                    p.lineTo(right, bottom);
                    p.moveTo(mid - arc, spine);
                    p.quadTo(mid, spine, mid, spine - arc);
                    p.moveTo(mid + arc, spine);
                    p.quadTo(mid, spine, mid, spine - arc);
                    break;
                }
                // Two L-shaped halves. Outward at the box's bottom-edge level to the side wall, one turn, then
                // down into the frame's top corner. The turn takes the frame's own corner radius, because the
                // second number is shared too (constraint 16).
                p.moveTo(x1, top);
                p.lineTo(arc, top);
                p.quadTo(0f, top, 0f, top + arc);
                p.lineTo(0f, bottom);
                p.moveTo(x2, top);
                p.lineTo(right - arc, top);
                p.quadTo(right, top, right, top + arc);
                p.lineTo(right, bottom);
                break;

            case ARROW:
                float turn = turnXIn(x2, w, turnFraction);
                float tip = v == Variant.ARROW_TOUCHING ? b : b - TIP_GAP;
                float shaftEnd = Math.max(tip - HEAD_H, top + arc);
                // Collinear out of the box's bottom-right corner, one turn, then down to the head.
                p.moveTo(x2, top);
                p.lineTo(turn - arc, top);
                p.quadTo(turn, top, turn, top + arc);
                p.lineTo(turn, shaftEnd);
                // The head: an open chevron rather than a filled triangle, so it is the same 1.4px stroke as the
                // rest of the outline and does not read as a second object.
                p.moveTo(turn - HEAD_W / 2f, tip - HEAD_H);
                p.lineTo(turn, tip);
                p.lineTo(turn + HEAD_W / 2f, tip - HEAD_H);
                break;
        }
        return p;
    }

    /** The box round the invoking token: three sides in `CORNERS`/`BRACE`, four in `ARROW`. */
    static void paintTokenBox(Graphics2D g, int x, int rowY, int w, int lineHeight, Leader leader, Variant v) {
        boolean bottom = leader == Leader.ARROW || v == Variant.FOUR_SIDED;
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(1.2f));
        int h = lineHeight;
        if (bottom) {
            g.drawRoundRect(x, rowY, w, h, ARC, ARC);
            return;
        }
        // Left, top, right — and the walls run down to the bottom-edge row the leader starts on, so the two are
        // adjacent by construction rather than by agreement.
        GeneralPath p = new GeneralPath();
        p.moveTo(x, rowY + h);
        p.lineTo(x, rowY + ARC);
        p.quadTo(x, rowY, x + ARC, rowY);
        p.lineTo(x + w - ARC, rowY);
        p.quadTo(x + w, rowY, x + w, rowY + ARC);
        p.lineTo(x + w, rowY + h);
        g.draw(p);
    }

    /**
     * The child's frame: four sides in `ARROW`, three in `CORNERS`/`BRACE`.
     *
     * **The top corners are square when there is no top edge**, and that is what dissolves 1a⁗'s rounding defect
     * rather than working around it: with no top run there is no top corner to round — the "corner" is just where
     * a side wall begins, and the leader arrives into it.
     */
    static void paintFrameBorder(Graphics2D g, int x, int y, int w, int h, boolean drawTop) {
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(1f));
        int right = x + w - 1;
        int bottom = y + h - 1;
        GeneralPath p = new GeneralPath();
        if (drawTop) {
            p.moveTo(x, y + ARC);
            p.quadTo(x, y, x + ARC, y);
            p.lineTo(right - ARC, y);
            p.quadTo(right, y, right, y + ARC);
        } else {
            p.moveTo(x, y);
            p.moveTo(right, y);
        }
        p.moveTo(x, y + (drawTop ? ARC : 0));
        p.lineTo(x, bottom - ARC);
        p.quadTo(x, bottom, x + ARC, bottom);
        p.lineTo(right - ARC, bottom);
        p.quadTo(right, bottom, right, bottom - ARC);
        p.lineTo(right, y + (drawTop ? ARC : 0));
        g.draw(p);
    }

    static int turnX(float fraction) {
        return FRAME_L + (int) turnXIn(boxRight() - FRAME_L, W - FRAME_R_INSET - FRAME_L, fraction);
    }

    /** The turn-down x, in the strip's space: a fraction of the span from the box's right edge to the frame's. */
    static float turnXIn(float x2, int w, float fraction) {
        float right = w - 1;
        float span = right - x2;
        float turn = x2 + span * fraction;
        // Room for the arc on the way in and for half a head on either side on the way out.
        return Math.max(x2 + ARC + 2, Math.min(right - HEAD_W / 2f - 1, turn));
    }

    // ────────────────────────────── the picture ──────────────────────────────

    static BufferedImage render(Leader leader, Variant v, int stripH, float turnFraction) {
        int childT = FRAME_T + stripH;
        int h = childT + CHILD_H + GAP_BELOW + ROWS_AFTER * LINE_H;
        BufferedImage img = new BufferedImage(W, Math.max(h, H), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(BG);
            g.fillRect(0, 0, W, img.getHeight());

            Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
            g.setFont(mono);
            FontMetrics fm = g.getFontMetrics();

            // The host's own code, above and below the call site.
            String[] above = {"public static Money MainLoop()", "{", "    var order = Intake();"};
            for (int i = 0; i < above.length; i++) row(g, fm, i * LINE_H, 66 + i, above[i]);

            String prefix = "    var priced = ";
            String token = "_pricing.Handle(order, verdict)";
            row(g, fm, CALL_ROW_Y, 69, prefix + token + ";");

            int childH = CHILD_H;
            int afterY = childT + childH + GAP_BELOW;
            row(g, fm, afterY, 70, "    Audit(priced);");
            row(g, fm, afterY + LINE_H, 71, "    return priced;");

            // The child: an opaque body, its border, its caption band, its code. Painted before the leader,
            // because the strip is at z-order 0 and paints last (constraint 7's mechanism, one level down).
            int frameR = W - FRAME_R_INSET;
            int childW = frameR - FRAME_L;
            g.setColor(CHILD_BG);
            g.fillRect(FRAME_L + 1, childT + 1, childW - 2, childH - 2);
            g.setColor(CAPTION_BG);
            g.fillRect(FRAME_L + 1, childT + 1, childW - 2, CAPTION_H);
            g.setColor(CAPTION_FG);
            g.drawString("src/Fixture.Processing/Handlers/PricingHandler.cs  ·  Handle", FRAME_L + 8,
                    childT + 1 + fm.getAscent());
            g.setColor(CODE_FG);
            String[] childCode = {"public sealed class PricingHandler", "    ⋯  12 lines  ⋯",
                    "    public Money Handle(Order order, Verdict verdict)", "    {"};
            for (int i = 0; i < childCode.length; i++) {
                g.drawString(childCode[i], FRAME_L + 34, childT + CAPTION_H + i * LINE_H + fm.getAscent());
            }
            paintFrameBorder(g, FRAME_L, childT, childW, childH,
                    leader == Leader.ARROW || v == Variant.FOUR_SIDED);

            // The token box, in the host's row.
            int tokenX = GUTTER + fm.stringWidth(prefix);
            int tokenW = fm.stringWidth(token);
            paintTokenBox(g, tokenX - TOKEN_PAD, CALL_ROW_Y, tokenW + 2 * TOKEN_PAD, LINE_H, leader, v);

            // The leader, in the strip's space.
            Graphics2D s = (Graphics2D) g.create(FRAME_L, FRAME_T, childW, stripH);
            try {
                s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                s.setColor(OUTLINE);
                s.setStroke(new BasicStroke(STROKE_W, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int x1 = tokenX - TOKEN_PAD - FRAME_L;
                int x2 = tokenX + tokenW + TOKEN_PAD - FRAME_L;
                s.draw(leaderPath(leader, x1, x2, childW, stripH - 1, turnFraction, v));
            } finally {
                s.dispose();
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    static void row(Graphics2D g, FontMetrics fm, int y, int lineNo, String text) {
        g.setColor(GUTTER_FG);
        String n = String.valueOf(lineNo);
        g.drawString(n, GUTTER - 8 - fm.stringWidth(n), y + fm.getAscent());
        g.setColor(CODE_FG);
        g.drawString(text, GUTTER, y + fm.getAscent());
    }

    static int boxLeft() {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            FontMetrics fm = g.getFontMetrics(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            return GUTTER + fm.stringWidth("    var priced = ") - TOKEN_PAD;
        } finally {
            g.dispose();
        }
    }

    static int boxRight() {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            FontMetrics fm = g.getFontMetrics(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            return GUTTER + fm.stringWidth("    var priced = ")
                    + fm.stringWidth("_pricing.Handle(order, verdict)") + TOKEN_PAD;
        } finally {
            g.dispose();
        }
    }

    /** A 40x`h` image with one horizontal stroke at `y` — the clipping control's whole apparatus. */
    static BufferedImage strokeAt(int h, int y) {
        BufferedImage img = new BufferedImage(40, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(BG);
            g.fillRect(0, 0, 40, h);
            g.setColor(OUTLINE);
            g.setStroke(new BasicStroke(STROKE_W));
            g.drawLine(2, y, 37, y);
        } finally {
            g.dispose();
        }
        return img;
    }

    // ────────────────────────────── pixels ──────────────────────────────

    /**
     * True for the outline's own ink, and **only** for it.
     *
     * A saturated blue against greys, tested on the blue-minus-red difference: a caption band, a code glyph and a
     * child's background are all neutral, so none of them can be mistaken for the stroke. Antialiased edge pixels
     * are blends, hence a threshold rather than an equality — an equality test would report a diagonal as
     * two-thirds missing.
     */
    static boolean isOutlineInk(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int b = rgb & 0xFF;
        return b - r > 24;
    }

    /**
     * 8-connected components of the outline's ink, one label per pixel and 0 for everything else.
     *
     * **8-connected, not 4:** a 1.4 px diagonal is a staircase of pixels that touch only at their corners, so
     * 4-connectivity would report every diagonal as a few hundred separate components and the whole test would
     * invert. Iterative flood fill rather than recursion — a 640×237 canvas of diagonal ink overflows the stack.
     */
    static int[] label(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] labels = new int[w * h];
        int[] stack = new int[w * h];
        int next = 0;
        for (int y0 = 0; y0 < h; y0++) {
            for (int x0 = 0; x0 < w; x0++) {
                int seed = y0 * w + x0;
                if (labels[seed] != 0 || !isOutlineInk(img.getRGB(x0, y0))) continue;
                int id = ++next;
                int top = 0;
                stack[top++] = seed;
                labels[seed] = id;
                while (top > 0) {
                    int p = stack[--top];
                    int px = p % w;
                    int py = p / w;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int x = px + dx;
                            int y = py + dy;
                            if (x < 0 || y < 0 || x >= w || y >= h) continue;
                            int q = y * w + x;
                            if (labels[q] != 0 || !isOutlineInk(img.getRGB(x, y))) continue;
                            labels[q] = id;
                            stack[top++] = q;
                        }
                    }
                }
            }
        }
        return labels;
    }

    /** The label of the ink pixel nearest `(x, y)` within `r`, or 0 if the seed is aimed at empty canvas. */
    static int labelNear(BufferedImage img, int[] labels, int x, int y, int r) {
        int w = img.getWidth();
        for (int radius = 0; radius <= r; radius++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int xx = x + dx;
                    int yy = y + dy;
                    if (xx < 0 || yy < 0 || xx >= w || yy >= img.getHeight()) continue;
                    int l = labels[yy * w + xx];
                    if (l != 0) return l;
                }
            }
        }
        return 0;
    }

    static int componentCount(int[] labels) {
        int max = 0;
        for (int l : labels) if (l > max) max = l;
        return max;
    }

    /**
     * The fraction of columns in `[x0, x1]` carrying outline ink within `slack` rows of `y`.
     *
     * A fraction rather than a count because it is the same question at every width, and a slack of 1 because a
     * 1.4 px stroke straddles two rows wherever it is not pixel-aligned — a strict single-row count reports a
     * present edge as half absent.
     */
    static double inkFraction(BufferedImage img, int x0, int x1, int y, int slack) {
        if (x1 < x0) return 0;
        int hit = 0;
        int total = 0;
        for (int x = Math.max(x0, 0); x <= Math.min(x1, img.getWidth() - 1); x++) {
            total++;
            for (int dy = -slack; dy <= slack; dy++) {
                int yy = y + dy;
                if (yy >= 0 && yy < img.getHeight() && isOutlineInk(img.getRGB(x, yy))) {
                    hit++;
                    break;
                }
            }
        }
        return total == 0 ? 0 : (double) hit / total;
    }

    static int countInk(BufferedImage img, int x0, int x1, int y0, int y1) {
        int n = 0;
        for (int y = Math.max(y0, 0); y <= Math.min(y1, img.getHeight() - 1); y++) {
            for (int x = Math.max(x0, 0); x <= Math.min(x1, img.getWidth() - 1); x++) {
                if (isOutlineInk(img.getRGB(x, y))) n++;
            }
        }
        return n;
    }

    static int lowestInkRow(BufferedImage img, int x0, int x1, int y0, int y1) {
        for (int y = Math.min(y1, img.getHeight() - 1); y >= Math.max(y0, 0); y--) {
            for (int x = Math.max(x0, 0); x <= Math.min(x1, img.getWidth() - 1); x++) {
                if (isOutlineInk(img.getRGB(x, y))) return y;
            }
        }
        return -1;
    }

    static void write(File dir, String name, BufferedImage img) throws Exception {
        File f = new File(dir, name);
        ImageIO.write(img, "png", f);
        System.out.println("   wrote " + f.getName() + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }

    static void pass(String what) {
        passes++;
        System.out.println("PASS  " + what);
    }

    static void fail(String what, String why) {
        failures++;
        System.out.println("FAIL  " + what + ": " + why);
    }

    private OutlineProbe() {
    }
}
