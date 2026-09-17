import com.audioexplainer.AppearancePreview;
import com.audioexplainer.AudioExplainerSettings;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * **The settings page's Appearance preview, painted headlessly.**
 *
 * Same trade as {@code LinkBench} and {@code TreeBench}: import the production drawing, hand it a
 * {@code BufferedImage} instead of an editor, and assert the promise the feature actually makes — that
 * <b>every Appearance control visibly changes the frame</b>. A spinner wired to nothing, a colour rule the
 * preview forgot to read, or a setting whose effect the miniature has no room to show all fail here in
 * seconds.
 *
 * <p>The one thing this cannot borrow is an {@code EditorColorsScheme}: it is a platform interface with no
 * headless implementation. So the bench supplies one as a {@link Proxy} answering the four things
 * {@code SpotlightRenderer.palette} and {@code calloutFont} actually ask it — the two default colours, a
 * font per {@link EditorFontType}, and the comment attributes. Every blend, clamp and mix downstream of
 * that is still production's; only the scheme is posed.
 *
 * <p>Run (after {@code ./gradlew compileKotlin}, with the argfile from {@code probes/README.md}):
 * {@code java -Dide.ui.scale=1.0 -Dsun.java2d.uiScale.enabled=false "@build/probe/cp.txt" AppearanceBench build/probe/pngs}
 */
public final class AppearanceBench {

    static int pass = 0;
    static int fail = 0;

    static void check(String what, boolean ok, String note) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what + (note.isEmpty() ? "" : "   [" + note + "]"));
        if (ok) pass++; else fail++;
    }

    static final Color BG = new Color(0x2B2B2B);
    static final Color FG = new Color(0xBBBBBB);
    static final Color COMMENT = new Color(0x7A7E85);

    /** A scheme that answers only what the production colour rules ask of one. */
    static EditorColorsScheme scheme() {
        Font plain = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        Font italic = new Font(Font.MONOSPACED, Font.ITALIC, 13);
        TextAttributes comment = new TextAttributes();
        comment.setForegroundColor(COMMENT);
        return (EditorColorsScheme) Proxy.newProxyInstance(
                EditorColorsScheme.class.getClassLoader(),
                new Class<?>[]{EditorColorsScheme.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getDefaultForeground": return FG;
                        case "getDefaultBackground": return BG;
                        case "getFont": return args[0] == EditorFontType.ITALIC ? italic : plain;
                        case "getEditorFontSize": return 13;
                        case "getAttributes": return comment;
                        case "getName": return "bench";
                        case "toString": return "BenchScheme";
                        case "hashCode": return 1;
                        case "equals": return proxy == args[0];
                        default: return defaultFor(method.getReturnType());
                    }
                });
    }

    static Object defaultFor(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        return 0;
    }

    static BufferedImage render(AudioExplainerSettings.State st) {
        AppearancePreview.Ink ink = AppearancePreview.Ink.Companion.of(scheme(), st);

        BufferedImage probe = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = probe.createGraphics();
        FontMetrics codeFm = pg.getFontMetrics(ink.getCode());
        FontMetrics proseFm = pg.getFontMetrics(ink.getProse());
        int h = AppearancePreview.Companion.preferredHeight(codeFm, proseFm);
        pg.dispose();

        int w = 560;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            AppearancePreview.Companion.paint(g, w, h, ink);
        } finally {
            g.dispose();
        }
        return img;
    }

    static int fingerprint(BufferedImage img) {
        return Arrays.hashCode(img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth()));
    }

    static double inkCoverage(BufferedImage img) {
        int bg = BG.getRGB();
        int marked = 0;
        int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        for (int p : px) if (p != bg) marked++;
        return (double) marked / px.length;
    }

    static void write(File dir, String name, BufferedImage img) {
        try {
            ImageIO.write(img, "png", new File(dir, name + ".png"));
        } catch (Exception e) {
            System.out.println("      (could not write " + name + ".png: " + e + ")");
        }
    }

    /** Paint each variant of one control and assert they are all different pictures. */
    static void dimension(File out, String label, String[] names, List<Consumer<AudioExplainerSettings.State>> variants) {
        Map<String, BufferedImage> shots = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            AudioExplainerSettings.State st = new AudioExplainerSettings.State();
            variants.get(i).accept(st);
            BufferedImage img = render(st);
            shots.put(names[i], img);
            write(out, label + "-" + names[i], img);
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
        check(label + ": every value changes the picture", same.isEmpty(),
                same.isEmpty() ? keys.size() + " distinct" : "identical: " + same);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).createGraphics().dispose();

        File out = args.length > 0 ? new File(args[0]) : new File("build/probe");
        out.mkdirs();

        System.out.println("AppearanceBench — the Appearance settings preview, painted without an IDE");
        System.out.println("PNGs -> " + out.getAbsolutePath());
        System.out.println();

        BufferedImage shipped = render(new AudioExplainerSettings.State());
        check("the shipped defaults paint a frame", inkCoverage(shipped) > 0.02,
                String.format("%.1f%% marked, %dx%d", inkCoverage(shipped) * 100,
                        shipped.getWidth(), shipped.getHeight()));
        write(out, "defaults", shipped);
        System.out.println();

        dimension(out, "frame",
                new String[]{"bar", "outline", "solid", "none", "comment"},
                List.of(st -> st.calloutFrame = "bar", st -> st.calloutFrame = "outline",
                        st -> st.calloutFrame = "solid", st -> st.calloutFrame = "none",
                        st -> st.calloutFrame = "comment"));

        dimension(out, "opacity", new String[]{"100", "50", "0"},
                List.of(st -> st.calloutOpacityPercent = 100, st -> st.calloutOpacityPercent = 50,
                        st -> st.calloutOpacityPercent = 0));

        dimension(out, "callout-tint", new String[]{"0", "18", "90"},
                List.of(st -> st.calloutTintPercent = 0, st -> st.calloutTintPercent = 18,
                        st -> st.calloutTintPercent = 90));

        dimension(out, "radius", new String[]{"0", "6", "16"},
                List.of(st -> st.calloutCornerRadius = 0, st -> st.calloutCornerRadius = 6,
                        st -> st.calloutCornerRadius = 16));

        dimension(out, "bar-width", new String[]{"1", "3", "10"},
                List.of(st -> st.calloutBarWidth = 1, st -> st.calloutBarWidth = 3,
                        st -> st.calloutBarWidth = 10));

        dimension(out, "leader", new String[]{"on", "off"},
                List.of(st -> st.calloutLeader = true, st -> st.calloutLeader = false));

        dimension(out, "dock", new String[]{"above", "below"},
                List.of(st -> st.calloutBelow = false, st -> st.calloutBelow = true));

        dimension(out, "text-scale", new String[]{"60", "100", "160"},
                List.of(st -> st.calloutTextScalePercent = 60, st -> st.calloutTextScalePercent = 100,
                        st -> st.calloutTextScalePercent = 160));

        dimension(out, "accent", new String[]{"gold", "cyan", "magenta"},
                List.of(st -> st.accentColor = "FFBE3C", st -> st.accentColor = "3CD6FF",
                        st -> st.accentColor = "FF3CC8"));

        dimension(out, "glow", new String[]{"0", "10", "80"},
                List.of(st -> st.activeGlowPercent = 0, st -> st.activeGlowPercent = 10,
                        st -> st.activeGlowPercent = 80));

        dimension(out, "outside-dim", new String[]{"5", "22", "95"},
                List.of(st -> st.outsideDimPercent = 5, st -> st.outsideDimPercent = 22,
                        st -> st.outsideDimPercent = 95));

        dimension(out, "within-dim", new String[]{"20", "62", "100"},
                List.of(st -> st.withinDimPercent = 20, st -> st.withinDimPercent = 62,
                        st -> st.withinDimPercent = 100));

        dimension(out, "nonactive-tint", new String[]{"off", "on-14", "on-90"},
                List.of(st -> st.nonActiveTint = false,
                        st -> { st.nonActiveTint = true; st.nonActiveTintPercent = 14; },
                        st -> { st.nonActiveTint = true; st.nonActiveTintPercent = 90; }));

        dimension(out, "gutter", new String[]{"off", "w1", "w8"},
                List.of(st -> st.gapGutter = false,
                        st -> { st.gapGutter = true; st.gapGutterWidth = 1; },
                        st -> { st.gapGutter = true; st.gapGutterWidth = 8; }));

        dimension(out, "overview", new String[]{"on", "off"},
                List.of(st -> st.overviewRulerMark = true, st -> st.overviewRulerMark = false));

        dimension(out, "arrow-thickness", new String[]{"8", "10", "40"},
                List.of(st -> st.arrowThicknessTenths = 8, st -> st.arrowThicknessTenths = 10,
                        st -> st.arrowThicknessTenths = 40));

        dimension(out, "arrow-head", new String[]{"4", "7", "16"},
                List.of(st -> st.arrowHeadSize = 4, st -> st.arrowHeadSize = 7,
                        st -> st.arrowHeadSize = 16));

        dimension(out, "arrow-elbow", new String[]{"round", "square"},
                List.of(st -> st.arrowSquareElbow = false, st -> st.arrowSquareElbow = true));

        dimension(out, "arrow-labels", new String[]{"on", "off"},
                List.of(st -> st.arrowLabels = true, st -> st.arrowLabels = false));

        System.out.println();
        // The control: a setting this frame does NOT stage must leave it alone, or "every value changes the
        // picture" above could be passing on some unrelated wobble.
        AudioExplainerSettings.State other = new AudioExplainerSettings.State();
        other.ttsSpeedPercent = 250;
        check("control: a setting outside Appearance leaves the frame untouched",
                fingerprint(render(other)) == fingerprint(render(new AudioExplainerSettings.State())), "");

        System.out.println();
        System.out.println(pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
