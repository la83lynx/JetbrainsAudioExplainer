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
 *  4. **Does `ARROW` reach the frame**, ending in an arrowhead that lands on the top border, with its turn-down x
 *     strictly between the box's right edge and the frame's? (It stopped {@link #TIP_GAP} short until the G1c'
 *     round read the gap as a misdrawn arrow. What keeps it a *reference* is question 2, not a gap.)
 *  5. **Does the leader start at the box's corner** rather than at the token's glyph, 2 px inside it? The round
 *     found that it did not — and this probe had it right while the Kotlin did not, so an untested agreement
 *     between two surfaces is a coincidence and it is a check now.
 *
 * **The single definition this probe exists to pin down** is `BOX_BOTTOM`: the row where the box's bottom edge
 * would be *is* the strip's row 0. Constraint 16's recorded lesson is that sharing the path was not enough — the
 * **origin** has to be shared — and its corollary, that sharing one number was not enough when a second (the
 * corner radius) was not shared. So there is one row constant and one [ARC] here, and the Kotlin transcription
 * takes both.
 *
 * Every check about geometry rather than arithmetic carries a **control that must fail**: a staggered outline, the
 * old brace, a four-sided everything, an arrow stopping short, a leader started at the glyph. `spikes/b3a/`'s first
 * version had none and its PASS was worthless.
 *
 * And one measurement is deliberately **not** a check — see {@link #reportArrowShaftByStripHeight}, where two
 * attempts at making it one failed as probe defects and the lesson was that the claim is not geometric.
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

    /**
     * How far short of the frame's top border the arrowhead's tip stops — **0 since the G1c′ round.**
     *
     * It was 3 ("points at, without touching") and the round read the result as a defect rather than as a
     * distinction: *"arrow does not reach header top per 2px."* An arrowhead floating short of the line it points at
     * reads as a misdrawn arrow. What still makes `ARROW` a reference and not a join is that **both outlines stay
     * closed** — see [#checkSuppressedEdges] — so the constant is kept, at 0, rather than deleted: it is the number
     * the judgement was about.
     */
    static final int TIP_GAP = 0;

    /** The gap the {@link Variant#ARROW_SHORT} control re-introduces, so "it reaches" has something to fail against. */
    static final int SHORT_GAP = 3;

    static final float ARROW_TURN = 0.5f;

    /**
     * **The strip's height by mode**, which is what the G1c′ round turned this from one number into: *"corners and
     * brace — 14px enough, arrow needs 22px."* [#checkArrowNeedsTheTallerStrip] and
     * [#checkBraceKeepsAWallAtItsDefault] are why those two numbers rather than one.
     */
    static int defaultStripHeight(Leader leader) {
        return leader == Leader.ARROW ? 22 : 14;
    }

    /** The corner radius actually used in a band `b` rows tall — clamped, or a full turn would eat the whole run. */
    static float arcFor(int b) {
        return Math.max(0f, Math.min(ARC, b / 3f));
    }

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
        /** Control: the arrowhead drawn {@link #SHORT_GAP} rows short. Must fail "reaches the frame". */
        ARROW_SHORT,
        /**
         * Control: the leader started at the token's **glyph** x instead of the box's edge — the G1c′ round's items 1
         * and 6, which is what an unshared origin looks like. Must fail the origin check by exactly {@link #TOKEN_PAD}.
         */
        GLYPH_ORIGIN,
        /** Control: nothing suppressed — a 4-sided box above a 4-sided frame, with a leader between them. */
        FOUR_SIDED,
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".");
        System.out.println("Phase 1a⁵ pre-flight probe — the one-outline rule (constraint 16, §1a⁵ 1)");
        System.out.println("canvas " + W + "x" + H + "px  lineHeight=" + LINE_H + "  arc=" + ARC + "  stroke="
                + STROKE_W + "px");
        System.out.println("stripHeight: " + STRIP_H + " for the checks, and by mode for what ships — corners/brace "
                + defaultStripHeight(Leader.CORNERS) + ", arrow " + defaultStripHeight(Leader.ARROW));
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
        checkBraceKeepsAWallAtItsDefault();

        // ── question 4: the arrow's end and its one free parameter ──
        checkArrowReachesTheFrame();
        checkArrowShortControlIsCaught();
        checkArrowTurnSitsBetweenTheEdges();
        checkArrowRunIsCollinearWithTheBoxBottom();

        // ── question 5: where the leader starts (the G1c-prime round's items 1 and 6) ──
        checkLeaderLeavesTheBoxCorner();

        // ── the known control, re-asserted because the leader's `bottom` depends on it ──
        checkStrokeAtHeightIsClipped();

        // ── for the eye ──
        reportArrowShaftByStripHeight();
        for (Leader l : Leader.values()) {
            // At the mode's own default rather than at one number for all three: that split is the round's answer,
            // and a PNG of the shape at a height it does not ship with is a picture of nothing in particular.
            write(dir, "outline-" + l.name().toLowerCase() + ".png",
                    render(l, Variant.SPEC, defaultStripHeight(l), ARROW_TURN));
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
        write(dir, "control-arrow-short.png", render(Leader.ARROW, Variant.ARROW_SHORT, STRIP_H, ARROW_TURN));
        write(dir, "control-glyph-origin.png", render(Leader.BRACE, Variant.GLYPH_ORIGIN, STRIP_H, ARROW_TURN));

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
     *  - `ARROW`: **also one, since the G1c' round** - its head used to stop {@link #TIP_GAP} short and the frame was
     *    therefore a separate component, and the round read the gap as a misdrawn arrow rather than as a
     *    distinction. So connectivity no longer discriminates ARROW from the joining modes; what does is
     *    {@link #checkSuppressedEdges}, where ARROW alone keeps both outlines closed. Recorded because the earlier
     *    reading was the opposite and the PASS text asserted it.
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
        if (box == lead && lead == frame) {
            pass(leader + ": the box, the leader and the frame are ONE 8-connected component (" + parts
                    + " total) - constraint 16, measured"
                    + (leader == Leader.ARROW ? " (and one since the head reaches the frame)" : ""));
        } else {
            fail(leader + " connectivity", "box=" + box + " leader=" + lead + " frame=" + frame + " in " + parts
                    + " components - the three drawings meet somewhere with a gap, which is the seam of 1a5 1 and "
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

    /** Where the leader's own ink is, per mode - on the diagonal, the run or the shaft, never near another owner. */
    static int leaderSeedY(Leader leader, int stripH) {
        switch (leader) {
            case CORNERS:
                return FRAME_T + stripH / 2;
            case BRACE:
                return BOX_BOTTOM + (int) arcFor(stripH - 1);
            default:
                // On the shaft, above the head: the head's tip now touches the frame, and a seed with a radius near
                // it would find the frame's own border rather than the leader's.
                return FRAME_T + stripH - 8;
        }
    }

    static int leaderProbeY(Leader leader) {
        return leaderSeedY(leader, STRIP_H);
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
        int runY = BOX_BOTTOM + (int) arcFor(STRIP_H - 1);

        // The horizontal runs, one radius below the box's bottom-edge level - the descent is what put them there -
        // reaching outward from the descent's end to each side wall's own turn.
        double left = inkFraction(img, FRAME_L + ARC + 2, boxLeft() - ARC - 2, runY, 1);
        double right = inkFraction(img, boxRight() + ARC + 2, W - FRAME_R_INSET - ARC - 3, runY, 1);
        // Under the box: everything the two halves are not. The specified shape leaves both corners outward, so
        // nothing at all is drawn between them - a stem or a spine is ink here.
        int under = braceInkUnderTheBox(img);

        if (left > 0.90 && right > 0.90 && under == 0) {
            pass(String.format("BRACE is two stepped halves: horizontal runs %.0f%%/%.0f%% inked on row %d (one "
                    + "radius below the box, which is the descent), and 0px of ink under the box - no stem, no spine",
                    left * 100, right * 100, runY));
        } else {
            fail("brace shape", String.format(
                    "left run %.0f%%, right run %.0f%%, ink under the box %dpx - the specified shape descends out of "
                            + "each bottom corner, runs outward, and turns down at the OUTER end",
                    left * 100, right * 100, under));
        }
    }

    /**
     * Ink strictly between the box's two bottom corners, below its (suppressed) bottom edge and above the frame.
     *
     * The discriminating window for the brace, and it moved with the shape: the specified run used to sit *on* the
     * box's bottom row, so "the strip's interior" could be everything below it. The run is one radius lower now, so
     * the window has to be horizontal instead - under the box, where neither half ever goes.
     */
    static int braceInkUnderTheBox(BufferedImage img) {
        return countInk(img, boxLeft() + 3, boxRight() - 3, BOX_BOTTOM + 2, CHILD_T - 2);
    }

    /** Control: 1a‴'s brace, inlined. Its stem and spine must be found by the same emptiness test. */
    static void checkOldBraceStillHasItsSpine() {
        int under = braceInkUnderTheBox(render(Leader.BRACE, Variant.OLD_BRACE, STRIP_H, ARROW_TURN));
        if (under > 20) {
            pass("control: the built brace's stem and spine are found under the box (" + under
                    + "px) - the emptiness test discriminates");
        } else {
            fail("brace control", "the old brace measured only " + under + "px of ink under the box, so the "
                    + "emptiness test above cannot tell the specified shape from the one that was sent back");
        }
    }

    /**
     * **`BRACE` still has a side wall at its own default height** - the G1c' round set that to 14 px, and the
     * descent spends two radii of the band before the wall starts.
     *
     * The number that could have made the round's answer wrong: at 14 px the two turns consume ~9 rows, and a wall
     * of one or two pixels would read as the run simply ending rather than as an outline arriving into the frame.
     * Measured rather than reasoned, in a 3 px column on the frame's left wall.
     */
    static void checkBraceKeepsAWallAtItsDefault() {
        int stripH = defaultStripHeight(Leader.BRACE);
        BufferedImage img = render(Leader.BRACE, Variant.SPEC, stripH, ARROW_TURN);
        int wallTop = BOX_BOTTOM + (int) (2 * arcFor(stripH - 1));
        int ink = countInk(img, FRAME_L, FRAME_L + 2, wallTop, FRAME_T + stripH - 1);
        if (ink >= 4) {
            pass("BRACE at its default " + stripH + "px still draws a side wall below the turn (" + ink
                    + "px of ink in a 3px column from row " + wallTop + ") - the descent leaves it room");
        } else {
            fail("brace wall at default", "only " + ink + "px of wall below the turn at stripHeight=" + stripH
                    + " - the two rounded turns have eaten the band, so the run ends instead of arriving");
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

    /**
     * **The arrowhead reaches the frame's top border** - the G1c' round's item 7, which inverted this check.
     *
     * It used to assert a gap of at least 2 px ("points at, without touching") and the round measured the result as
     * "arrow does not reach header top per 2px". So the assertion is the other way round now, and the control with
     * it: the tip's ink must land within one row of the frame's top, which is as close as it can get given that a
     * stroke at `y == height` is clipped ({@link #checkStrokeAtHeightIsClipped}).
     */
    static void checkArrowReachesTheFrame() {
        BufferedImage img = render(Leader.ARROW, Variant.SPEC, STRIP_H, ARROW_TURN);
        int turn = turnX(ARROW_TURN);
        int lowest = lowestInkRow(img, turn - HEAD_W, turn + HEAD_W, BOX_BOTTOM, CHILD_T - 1);
        int gap = CHILD_T - lowest;
        if (lowest > 0 && gap <= 1) {
            pass("ARROW's head ends at row " + lowest + ", " + gap + "px from the frame's top row (" + CHILD_T
                    + ") - it lands on the border, and the closed box and closed frame are what keep it a reference");
        } else {
            fail("arrow reach", "the arrowhead's lowest ink is at row " + lowest + " against the frame's top row "
                    + CHILD_T + " (gap " + gap + "px) - an arrow floating short of the line it points at reads as "
                    + "misdrawn, which is what the round sent back");
        }
    }

    /** Control: the old shape, {@link #SHORT_GAP} rows short, must be caught as not reaching. */
    static void checkArrowShortControlIsCaught() {
        BufferedImage img = render(Leader.ARROW, Variant.ARROW_SHORT, STRIP_H, ARROW_TURN);
        int turn = turnX(ARROW_TURN);
        int lowest = lowestInkRow(img, turn - HEAD_W, turn + HEAD_W, BOX_BOTTOM, CHILD_T - 1);
        if (CHILD_T - lowest >= 2) {
            pass("control: an arrow drawn " + SHORT_GAP + "px short is caught not reaching (lowest ink row "
                    + lowest + ", gap " + (CHILD_T - lowest) + "px)");
        } else {
            fail("arrow reach control", "the short control measured a " + (CHILD_T - lowest) + "px gap, so the "
                    + "reach check above would pass whatever the arrow did");
        }
    }

    /**
     * **How `ARROW`'s descent divides up at each strip height** - reported, deliberately **not** asserted.
     *
     * The round chose 22 px for `ARROW` and 14 for the other two on real code, and that is the authority; this is the
     * number behind it, so the choice is re-judgeable rather than remembered. It is an `INFO` and not a check because
     * two attempts at making it one both failed *as probe defects*, and the second failure is the informative one:
     *
     *  - the first counted ink inside a window whose bounds *were* `arc` and `HEAD_H`, so it measured the size of its
     *    own window - fully inked at every height - and could only restate arithmetic;
     *  - the second classified rows by where the ink sits and still could not draw a clean line, because the chevron's
     *    arms converge onto the axis and its last rows are indistinguishable from shaft by any local test.
     *
     * The lesson is worth more than the check: **"is there enough shaft" is not a geometric claim.** A probe can say
     * where ink is; how much of it reads as an arrow is the eye's question, and the round answered it. Forcing a
     * threshold here would have meant fitting one to the numbers already observed, which is a PASS that measures
     * nothing - `spikes/b3a/`'s original mistake in a new costume.
     */
    static void reportArrowShaftByStripHeight() {
        int turn = turnX(ARROW_TURN);
        StringBuilder sb = new StringBuilder();
        for (int h : new int[]{14, 22, 30}) {
            int[] parts = classifyDescent(render(Leader.ARROW, Variant.SPEC, h, ARROW_TURN), turn, h);
            sb.append(String.format("  %dpx: arc %d + shaft %d + head %d rows", h, parts[0], parts[1], parts[2]));
        }
        info("ARROW's descent, by strip height (the round chose " + defaultStripHeight(Leader.ARROW) + "):" + sb);
    }

    /**
     * `{arc, shaft, head}` rows of the arrow's descent, by where each row's ink sits relative to the turn's axis.
     *
     * The arc leaves ink on the **left** of the axis only (it comes in from the horizontal run); the chevron leaves
     * it on **both** sides; a straight shaft row has it on neither. Approximate at the head's last rows, where the
     * Approximate at **both** ends, and the numbers say so: the arc's curve hugs the axis to within a pixel after its
     * first row, so most of it is counted as shaft, and the chevron's arms converge onto the axis before its last row.
     * Which is the second half of why this is reported rather than asserted — the classification is directional
     * evidence, not a boundary. See {@link #reportArrowShaftByStripHeight}.
     */
    static int[] classifyDescent(BufferedImage img, int turn, int stripH) {
        int arc = 0;
        int shaft = 0;
        int head = 0;
        for (int y = BOX_BOTTOM + 1; y < FRAME_T + stripH; y++) {
            boolean left = countInk(img, turn - 6, turn - 2, y, y) > 0;
            boolean right = countInk(img, turn + 2, turn + 6, y, y) > 0;
            boolean axis = countInk(img, turn - 1, turn + 1, y, y) > 0;
            if (left && right) head++;
            else if (left) arc++;
            else if (axis) shaft++;
        }
        return new int[]{arc, shaft, head};
    }

    /**
     * **The leader leaves the box's *corner*, not the token's glyph** - the G1c' round's items 1 and 6, and the
     * second half of the shared-origin rule.
     *
     * The round's words: "diagonals top do not meet invoker box ends - off by 2px because of invoker box padding".
     * The box is inflated by {@link #TOKEN_PAD} on each side, so a leader started from the glyph's x leaves the
     * outline 2 px inside its own wall. This probe always padded its endpoints and the Kotlin transcription did not,
     * which is why the check exists now rather than then: an untested agreement is a coincidence.
     *
     * Measured on `BRACE`, because its descent is *vertical* for the first radius, whereas a diagonal moves ~15 px
     * per row at this width - there is no row on which a diagonal's x means anything to 2 px.
     */
    static void checkLeaderLeavesTheBoxCorner() {
        int spec = originOffset(Variant.SPEC);
        int glyph = originOffset(Variant.GLYPH_ORIGIN);
        if (spec == NOT_MEASURED || glyph == NOT_MEASURED) {
            fail("origin seeds", "spec=" + spec + " control=" + glyph + " - the check is aiming at empty canvas");
            return;
        }
        // Within the two stroke widths, and not zero: the box's border is 1.2px and the leader's 1.4px, so the
        // leftmost inked column of one is allowed to sit a pixel off the other's on the same geometry. That
        // systematic bias is the probe's own first-run defect here - it made a 2px control read as 1px and fail.
        if (Math.abs(spec) <= 1) {
            pass("the leader's origin is the box's wall to within the two stroke widths (off by " + spec + "px) "
                    + "- one derivation of the origin, not two");
        } else {
            fail("leader origin", "the descent starts " + spec + "px from the box's wall - two derivations of where "
                    + "the call is, which is the 1a-triple-prime mistake on the other axis");
        }
        // The control's whole value is that the bias cancels: same two strokes, same rows, one changed input.
        if (glyph - spec == TOKEN_PAD) {
            pass("control: starting the leader at the token's glyph instead of the box's edge moves the origin by "
                    + (glyph - spec) + "px = TOKEN_PAD - which is what the round saw, and it is detected exactly");
        } else {
            fail("leader origin control", "the glyph-origin control moved the origin by " + (glyph - spec)
                    + "px, not TOKEN_PAD=" + TOKEN_PAD + "; the check above cannot tell a shared origin from a "
                    + "coincidence, so its PASS means nothing");
        }
    }

    static final int NOT_MEASURED = Integer.MIN_VALUE;

    /** The descent's x minus the box wall's x, in one render. See {@link #checkLeaderLeavesTheBoxCorner}. */
    static int originOffset(Variant v) {
        BufferedImage img = render(Leader.BRACE, v, STRIP_H, ARROW_TURN);
        // The box's own left wall, sampled in the middle of the token's row where nothing else is inked.
        int wall = leftmostInkX(img, FRAME_L, boxMidTop(), CALL_ROW_Y + LINE_H / 2);
        // The descent, one row into the strip: still vertical there, and the frame's own wall has not begun.
        int origin = leftmostInkX(img, FRAME_L, boxMidTop(), BOX_BOTTOM + 1);
        return (wall < 0 || origin < 0) ? NOT_MEASURED : origin - wall;
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
                // Two stepped halves, each **leaving the box's bottom corner going down**: a rounded turn out of
                // the box's own side wall, an outward run at that level to the frame's side wall, then a second
                // rounded turn down into it. Two turns per half and nothing between them.
                //
                // The descent is the G1c' round's correction - the run used to leave at row 0 exactly, which is a
                // T-junction at the end of the box's wall however well the ends meet: "it stems immediately
                // sideways from the invocation token box, I expected smooth roundings span down from sides of box".
                float runY = top + arc;
                if (x1 - arc > arc) {
                    p.moveTo(x1, top);
                    p.quadTo(x1, runY, x1 - arc, runY);
                    p.lineTo(arc, runY);
                    p.quadTo(0f, runY, 0f, runY + arc);
                } else {
                    p.moveTo(0f, top);
                }
                p.lineTo(0f, bottom);
                if (x2 + arc < right - arc) {
                    p.moveTo(x2, top);
                    p.quadTo(x2, runY, x2 + arc, runY);
                    p.lineTo(right - arc, runY);
                    p.quadTo(right, runY, right, runY + arc);
                } else {
                    p.moveTo(right, top);
                }
                p.lineTo(right, bottom);
                break;

            case ARROW:
                float turn = turnXIn(x2, w, turnFraction);
                float tip = v == Variant.ARROW_SHORT ? b - SHORT_GAP : b - TIP_GAP;
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
                // The **box's** corners, which is the whole of the origin rule; the control uses the token's own
                // glyph bounds instead, which is what the Kotlin was doing when the round caught it.
                int pad = v == Variant.GLYPH_ORIGIN ? 0 : TOKEN_PAD;
                int x1 = tokenX - pad - FRAME_L;
                int x2 = tokenX + tokenW + pad - FRAME_L;
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

    /** The x of the leftmost outline-ink pixel on row `y` within `[x0, x1]`, or -1. */
    static int leftmostInkX(BufferedImage img, int x0, int x1, int y) {
        if (y < 0 || y >= img.getHeight()) return -1;
        for (int x = Math.max(x0, 0); x <= Math.min(x1, img.getWidth() - 1); x++) {
            if (isOutlineInk(img.getRGB(x, y))) return x;
        }
        return -1;
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

    /** A measurement with no claim attached. See {@link #reportArrowShaftByStripHeight} for when that is right. */
    static void info(String what) {
        System.out.println("INFO  " + what);
    }

    static void fail(String what, String why) {
        failures++;
        System.out.println("FAIL  " + what + ": " + why);
    }

    private OutlineProbe() {
    }
}
