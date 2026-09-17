import com.audioexplainer.AudioExplainerSettings;
import com.audioexplainer.tree.CodeNodeStyle;
import com.audioexplainer.tree.NestStyle;
import com.audioexplainer.tree.TreePreview;
import com.audioexplainer.tree.TreeStyle;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **The settings page's Code-tree preview, painted headlessly.**
 *
 * `TreePreview` exists so the reader can see what a dropdown does instead of reading a paragraph about it.
 * That promise has a testable form — <b>every value of every Code-tree dropdown must change the picture</b> —
 * and this asserts it by painting each value and comparing rasters. A combo wired to nothing, a mode that
 * silently paints like its neighbour, or a style field the preview forgot to read all fail here, in seconds,
 * rather than in a four-minute `runIde` launch where they look like "I can't tell the difference".
 *
 * It follows `LinkBench`'s trade exactly: import the production drawing, give it a `BufferedImage` instead of
 * an editor, and write PNGs beside the assertions so a human can look. `TreePreview.paint` takes an
 * {@link TreePreview.Ink} rather than an `EditorColorsScheme` for precisely this reason — there is no
 * Application here to hand one over.
 *
 * <p>Run (after {@code ./gradlew compileKotlin}, with the argfile from {@code probes/README.md}):
 * {@code java -Dide.ui.scale=1.0 -Dsun.java2d.uiScale.enabled=false "@build/probe/cp.txt" TreeBench build/probe/pngs}
 */
public final class TreeBench {

    static int pass = 0;
    static int fail = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (ok) pass++; else fail++;
    }

    static void check(String what, boolean ok, String note) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what + "   [" + note + "]");
        if (ok) pass++; else fail++;
    }

    /** A dark-scheme stand-in. The colours only have to be a *scheme*, not the reader's scheme. */
    static final Color BG = new Color(0x2B2B2B);
    static final Color FG = new Color(0xBBBBBB);
    static final Color FOLD_FG = new Color(0x9A9A8C);
    static final Color FOLD_BG = new Color(0x3A3A30);
    /** `AudioExplainerSettings.DEF_ACCENT_COLOR`, so the bench shows the shipped picture. */
    static final Color ACCENT = new Color(0xFFBE3C);

    static TreePreview.Ink ink() {
        return new TreePreview.Ink(
                new Font(Font.MONOSPACED, Font.PLAIN, 13),
                new Font(Font.MONOSPACED, Font.BOLD, 12),
                new Font(Font.MONOSPACED, Font.ITALIC, 13),
                BG, FG, FOLD_FG, FOLD_BG, ACCENT);
    }

    /** A settings state with every Code-tree field at its shipped default. */
    static AudioExplainerSettings.State defaults() {
        return new AudioExplainerSettings.State();
    }

    static BufferedImage render(AudioExplainerSettings.State st) {
        TreePreview.Ink ink = ink();
        NestStyle nest = TreeStyle.INSTANCE.nest(st);
        CodeNodeStyle node = TreeStyle.INSTANCE.node(st);

        // Measure with a throwaway surface, exactly as the component measures with its own.
        BufferedImage probe = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = probe.createGraphics();
        FontMetrics codeFm = pg.getFontMetrics(ink.getCode());
        FontMetrics titleFm = pg.getFontMetrics(ink.getTitle());
        int w = TreePreview.Companion.preferredWidth(codeFm);
        int h = TreePreview.Companion.preferredHeight(codeFm, titleFm, nest);
        pg.dispose();

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            TreePreview.Companion.paint(g, w, h, ink, nest, node);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** A raster fingerprint — two pictures are "the same" when every pixel is. */
    static int fingerprint(BufferedImage img) {
        int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        return Arrays.hashCode(px) * 31 + img.getHeight();
    }

    /** How much of the canvas is not background — a picture that painted nothing is not a picture. */
    static double inkCoverage(BufferedImage img) {
        int bg = BG.getRGB();
        int marked = 0;
        int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        for (int p : px) if (p != bg) marked++;
        return (double) marked / px.length;
    }

    interface Setter { void set(AudioExplainerSettings.State st, String value); }

    /**
     * The whole contract for one dropdown: paint every value, assert each renders something, and assert no
     * two of them render the *same* something.
     */
    static void dimension(File out, String label, String[] values, Setter setter) {
        Map<String, BufferedImage> shots = new LinkedHashMap<>();
        for (String v : values) {
            AudioExplainerSettings.State st = defaults();
            setter.set(st, v);
            shots.put(v, render(st));
        }

        for (Map.Entry<String, BufferedImage> e : shots.entrySet()) {
            double cover = inkCoverage(e.getValue());
            check(label + " = " + e.getKey() + ": the frame paints",
                    cover > 0.01, String.format("%.1f%% of the canvas marked", cover * 100));
            write(out, label + "-" + e.getKey(), e.getValue());
        }

        List<String> same = new ArrayList<>();
        List<String> keys = new ArrayList<>(shots.keySet());
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                if (fingerprint(shots.get(keys.get(i))) == fingerprint(shots.get(keys.get(j)))) {
                    same.add(keys.get(i) + "==" + keys.get(j));
                }
            }
        }
        check("**" + label + ": every value changes the picture**", same.isEmpty(),
                same.isEmpty() ? keys.size() + " values, " + keys.size() + " distinct pictures"
                        : "identical: " + same);
    }

    static void write(File dir, String name, BufferedImage img) {
        if (dir == null) return;
        try {
            ImageIO.write(img, "png", new File(dir, name + ".png"));
        } catch (Exception e) {
            System.out.println("      (could not write " + name + ".png: " + e + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        // Mandatory before anything touches JBUI/JBColor: JBUIScale.computeSystemScaleFactor asserts
        // "Must be precomputed" when the graphics environment has never been initialised. Same line, same
        // reason, as LinkBench's.
        new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).createGraphics().dispose();

        File out = args.length > 0 ? new File(args[0]) : new File("build/probe");
        out.mkdirs();

        System.out.println("TreeBench — the Code-tree settings preview, painted without an IDE");
        System.out.println("PNGs -> " + out.getAbsolutePath());
        System.out.println();

        // The shipped picture, first, as the thing a human actually looks at.
        BufferedImage shipped = render(defaults());
        check("the shipped defaults paint a frame", inkCoverage(shipped) > 0.02,
                String.format("%.1f%% marked, %dx%d", inkCoverage(shipped) * 100,
                        shipped.getWidth(), shipped.getHeight()));
        write(out, "defaults", shipped);
        System.out.println();

        // **Every dropdown the preview claims to answer.** The values are the enum names, which is what the
        // settings combos hold and what TreeStyle matches on — so a rename that breaks the combo breaks this.
        dimension(out, "leader", new String[]{"CORNERS", "BRACE", "ARROW"},
                (st, v) -> st.treeLeader = v);
        System.out.println();
        dimension(out, "chevron", new String[]{"NONE", "HORIZONTAL", "VERTICAL", "BOTTOM_BORDER"},
                (st, v) -> st.treeChevron = v);
        System.out.println();
        dimension(out, "path", new String[]{"FULL", "PROJECT_RELATIVE", "BASENAME"},
                (st, v) -> st.treePath = v);
        System.out.println();
        dimension(out, "deemphasis", new String[]{"NONE", "TINT", "BORDER", "TINT_AND_BORDER"},
                (st, v) -> st.treeDeemphasis = v);
        System.out.println();
        dimension(out, "elision", new String[]{"OPENABLE", "SEALED"},
                (st, v) -> st.treeElision = v);
        System.out.println();

        // **The controls that must NOT move the picture**, so "every value changes it" above cannot be
        // passing on noise. `stepFold` is a behaviour over time and `maxHeight`/`grow` are sizing rules the
        // miniature does not stage — the settings text says so, and this is what makes that honest.
        AudioExplainerSettings.State a = defaults();
        a.treeStepFold = "NEVER";
        AudioExplainerSettings.State b = defaults();
        b.treeStepFold = "ALWAYS";
        check("control: step-fold is a behaviour, not a shape — the frame is identical",
                fingerprint(render(a)) == fingerprint(render(b)));

        AudioExplainerSettings.State tall = defaults();
        tall.treeMaxHeight = 4000;
        check("control: the height ceiling does not stage in the miniature either",
                fingerprint(render(tall)) == fingerprint(render(defaults())));

        System.out.println();
        System.out.println(pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
