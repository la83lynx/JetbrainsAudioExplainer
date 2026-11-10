import com.audioexplainer.ExplainProgress;
import com.audioexplainer.RunLog;
import com.audioexplainer.RunShape;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * **What a user sees of a Claude run, frozen**: each recorded `stream-json` run in
 * `probes/fixtures/stream/` replayed through the plugin's own reading of the stream, on the run loop's 200 ms
 * beat and the recording's own clock, and everything that reading produces written down — the bar and its
 * activity line at every tick that changed them, the warning, the run log, the `idea.log` timeline, the plans
 * offered early, the stops, and the result.
 *
 * `record` writes `<fixture>.golden.txt`; without it the probe compares and fails on the first line that moved.
 * The golden was recorded from the code before the `RunEvent` refactor, which is the point: the refactor must
 * not change a byte of it.
 *
 * A fixture is `t<TAB>line` per stdout line, after one `#` header: `tool=0|1 provider=<name>|- cancel=0|1`.
 */
public final class RunEventGolden {

    static final List<String> INFO = new ArrayList<>();

    /** Captures the INFO lines the run code logs; the timeline is one of the things a round reads. */
    static final class Capture extends Logger {
        final String category;
        Capture(String category) { this.category = category; }
        @Override public boolean isDebugEnabled() { return false; }
        @Override public void debug(String message, Throwable t) { }
        @Override public void info(String message, Throwable t) { synchronized (INFO) { INFO.add(message); } }
        @Override public void warn(String message, Throwable t) { synchronized (INFO) { INFO.add("WARN " + message); } }
        @Override public void error(String message, Throwable t, String... details) { synchronized (INFO) { INFO.add("ERROR " + message); } }
    }

    public static void main(String[] args) throws Exception {
        Logger.setFactory(Capture::new);
        boolean record = args.length > 0 && args[0].equals("record");
        Path dir = Paths.get("probes/fixtures/stream");
        List<Path> fixtures = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.tsv")) { ds.forEach(fixtures::add); }
        fixtures.sort(null);
        int pass = 0, fail = 0;
        for (Path f : fixtures) {
            String name = f.getFileName().toString().replace(".tsv", "");
            String got = replay(f, name);
            Path golden = dir.resolve(name + ".golden.txt");
            if (record) {
                Files.writeString(golden, got, StandardCharsets.UTF_8);
                System.out.println("RECORDED " + golden + " (" + got.lines().count() + " lines)");
                continue;
            }
            String want = Files.exists(golden) ? Files.readString(golden, StandardCharsets.UTF_8) : "";
            if (want.equals(got)) {
                System.out.println("PASS  " + name + " — byte-identical (" + got.lines().count() + " lines)");
                pass++;
            } else {
                String[] w = want.split("\n", -1), g = got.split("\n", -1);
                int i = 0;
                while (i < Math.min(w.length, g.length) && w[i].equals(g[i])) i++;
                System.out.println("FAIL  " + name + " — line " + (i + 1) + ":\n      want: " + (i < w.length ? w[i] : "<end>")
                        + "\n      got:  " + (i < g.length ? g[i] : "<end>"));
                fail++;
            }
        }
        if (!record) {
            System.out.println();
            System.out.println(pass + " pass / " + fail + " fail");
            if (fail > 0 || pass == 0) System.exit(1);
        }
    }

    static String replay(Path fixture, String name) throws Exception {
        List<String> raw = Files.readAllLines(fixture, StandardCharsets.UTF_8);
        String header = raw.get(0);
        boolean needsTool = header.contains("tool=1");
        String provider = header.replaceAll(".*provider=(\\S+).*", "$1");
        if (provider.equals("-")) provider = null;
        boolean cancel = header.contains("cancel=1");
        List<Double> ts = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (String l : raw.subList(1, raw.size())) {
            int tab = l.indexOf('\t');
            ts.add(Double.parseDouble(l.substring(0, tab)));
            lines.add(l.substring(tab + 1));
        }
        synchronized (INFO) { INFO.clear(); }

        double[] clock = {0};
        StringBuilder out = new StringBuilder();
        out.append("# ").append(name).append(": ").append(header.substring(1).trim()).append('\n');
        GoldenDriver d = GoldenDriver.create(new RunShape(30, 8000, 320, 1), name, () -> clock[0], needsTool, provider);

        // The production run loop, on a fake process: each 200 ms wait takes the lines that arrived by then and
        // moves the clock to the tick; the stream's end is the process exiting — or, for a cancelled run, the user
        // pressing Stop.
        int[] next = {0};
        int[] tick = {0};
        double last = ts.isEmpty() ? 0 : ts.get(ts.size() - 1);
        // An indicator with its own cancel flag, everything else a no-op: the platform's ask a ProgressManager when
        // cancelled, and there is no application here.
        boolean[] stopped = {false};
        com.intellij.openapi.progress.ProgressIndicator indicator = (com.intellij.openapi.progress.ProgressIndicator)
                java.lang.reflect.Proxy.newProxyInstance(RunEventGolden.class.getClassLoader(),
                        new Class<?>[] { com.intellij.openapi.progress.ProgressIndicator.class }, (proxy, m, a) -> {
                            switch (m.getName()) {
                                case "cancel": stopped[0] = true; return null;
                                case "isCanceled": return stopped[0];
                                case "getText": case "getText2": return "";
                                case "getFraction": return 0.0;
                                case "hashCode": return System.identityHashCode(proxy);
                                case "equals": return proxy == a[0];
                                case "toString": return "probe indicator";
                                default: return m.getReturnType() == boolean.class ? false : null;
                            }
                        });
        String stop = d.run(indicator,
                (activity, fraction, warning) -> {
                    out.append(String.format(Locale.ROOT, "%7.1fs  %s\n", clock[0],
                            (int) (fraction * 100) + "%  " + activity + (warning != null ? "  ⚠ " + warning : "")));
                    return kotlin.Unit.INSTANCE;
                },
                ms -> {
                    double t = ++tick[0] * 0.2;
                    if (t > last + 0.2) {
                        if (!cancel) return true;
                        indicator.cancel();
                        return false;
                    }
                    while (next[0] < lines.size() && ts.get(next[0]) <= t) {
                        clock[0] = ts.get(next[0]);
                        d.line(lines.get(next[0]++));
                    }
                    clock[0] = t;
                    return false;
                });
        out.append("== stop: ").append(stop == null ? "-" : stop).append('\n');
        out.append("== result\n").append(d.result()).append('\n');
        out.append("== progress report\n").append(d.report()).append('\n');
        out.append("== early plans\n");
        for (String p : d.early()) out.append(p.length()).append(" chars, sha1 ").append(sha1(p)).append('\n');
        out.append("== run log (warning: ").append(d.warning()).append(", rereads ").append(d.rereads()).append(")\n");
        for (String l : d.runLog()) out.append(l).append('\n');
        out.append("== idea.log\n");
        synchronized (INFO) {
            // A time the timeline takes from the wall clock is masked; everything else is the recording's.
            for (String l : INFO) out.append(l.replaceAll("(\\+|at )\\d+\\.\\ds", "$1#s")).append('\n');
        }
        return out.toString();
    }

    static String sha1(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < 6; k++) b.append(String.format("%02x", h[k]));
        return b.toString();
    }
}

/**
 * The run loop's reading of the stream, as `ClaudeHarness` does it: each line through the production
 * `ClaudeStream` into the production `RunReader`, and the stops in `RunLoop`'s order. The one part of this probe
 * that changed with the `RunEvent` refactor — before it, this reached `ClaudeExplainer`'s private parser.
 */
final class GoldenDriver {
    final ExplainProgress progress;
    final RunLog runLog;
    final String provider;
    final com.audioexplainer.ClaudeStream stream = new com.audioexplainer.ClaudeStream();
    final com.audioexplainer.RunReader reader;
    final List<String> early = new ArrayList<>();
    final Object lock = new Object();

    private GoldenDriver(RunShape shape, String title, kotlin.jvm.functions.Function0<Double> now, boolean needsTool, String provider) {
        progress = new ExplainProgress(shape, now);
        runLog = new RunLog(title, now);
        this.provider = provider;
        Logger info = Logger.getInstance("com.audioexplainer.ClaudeExplainer");
        reader = new com.audioexplainer.RunReader(progress, runLog, provider, needsTool,
                c -> { early.add(c); return kotlin.Unit.INSTANCE; },
                m -> { info.info(m); return kotlin.Unit.INSTANCE; }, now, null);
    }

    static GoldenDriver create(RunShape shape, String title, java.util.function.DoubleSupplier now, boolean needsTool, String provider) {
        return new GoldenDriver(shape, title, now::getAsDouble, needsTool, provider);
    }

    void line(String line) {
        reader.onLine(stream.parse(line));
    }

    /** The production `RunLoop` over [waitFor]'s fake process; what it stopped for, or null when the stream ran out. */
    String run(com.intellij.openapi.progress.ProgressIndicator indicator,
               kotlin.jvm.functions.Function3<String, Double, String, kotlin.Unit> onActivity,
               kotlin.jvm.functions.Function1<Long, Boolean> waitFor) {
        com.audioexplainer.RunResult r = new com.audioexplainer.RunLoop(indicator, progress, runLog, reader, lock, onActivity, provider)
                .run(() -> kotlin.Unit.INSTANCE, waitFor, () -> kotlin.Unit.INSTANCE, () -> 0, () -> "");
        if (r.getCancelled()) return "cancelled after " + reader.getTools() + " tool call(s)";
        if (reader.getAuthStop() != null) return "auth rejected, HTTP " + reader.getAuthStop().getStatus();
        if (r.getExitCode() == -1 && r.getToolDown() != null) return "tool down: " + r.getToolDown();
        return null;
    }

    String result() {
        String t = reader.getFinalText();
        return "isError=" + reader.isError() + " toolDown=" + reader.getToolStatus() + " finalText="
                + (t == null ? "null" : t.length() + " chars: " + t.replace('\n', ' ').substring(0, Math.min(160, t.length())));
    }

    String report() {
        RunShape o = progress.observed();
        return progress.report() + "\nobserved: " + (o == null ? "null" : o.encode());
    }

    List<String> early() { return early; }
    String warning() { return runLog.getWarning(); }
    int rereads() { return runLog.getRereads(); }
    List<String> runLog() { return runLog.snapshot(); }
}
