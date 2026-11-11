import com.audioexplainer.*;
import com.audioexplainer.tree.*;
import java.util.*;

/**
 * Plan probe — runs the REAL PlanLoader and PlanOutline over hand-built plans and asserts what each
 * degrade does. It imports the production classes rather than re-deriving their arithmetic, which is the
 * one property that stops a probe drifting from the code it measures.
 *
 * Every check that asserts an *absence* (no cycle issue, no dropped step) is paired with a case where the
 * thing is present, so the instrument can be seen to work.
 */
public final class PlanProbe {

    static int pass = 0, fail = 0;

    /** A root frame in scene [scene], for the outline's scene-level checks. */
    static Frame sceneFrame(int id, String file, int scene) {
        return new Frame(id, file, "F" + id, "", scene, null, 0, null, 1,
                Collections.singletonList(ctx(10, 40)), Collections.emptyList());
    }

    /** The two escapes beat-normalize.tsv uses. Kept to exactly what ClockProbe.js also decodes. */
    static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t");
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (ok) pass++; else fail++;
    }

    static LineRange ctx(int a, int b) { return new LineRange(a, b, LineRange.ROLE_CONTEXT); }
    static LineRange focus(int a, int b) { return new LineRange(a, b, LineRange.ROLE_FOCUS); }

    static Frame frame(int id, String file, Integer parent, int callLine, String token, LineRange... keep) {
        return sceneFrame(0, id, file, parent, callLine, token, keep);
    }

    /** The same, in a named scene — everything the scene decision added is exercised through this. */
    static Frame sceneFrame(int scene, int id, String file, Integer parent, int callLine, String token,
                            LineRange... keep) {
        return new Frame(id, file, "F" + id, "", scene, parent, callLine, token, 1,
                Arrays.asList(keep), Collections.emptyList());
    }

    static Step step(int frameId, LineRange... ranges) {
        return new Step(frameId, Arrays.asList(ranges), Collections.emptyList(),
                "tts", null, null, null, null, 1, Collections.emptyList());
    }

    static Step multiStep(int a, LineRange ra, int b, LineRange rb) {
        return new Step(null, Collections.emptyList(),
                Arrays.asList(new StepFrame(a, Collections.singletonList(ra)),
                              new StepFrame(b, Collections.singletonList(rb))),
                "tts", null, null, null, null, 1, Collections.emptyList());
    }

    /** A step lighting lines 10-20, narrating [tts], drawing [beats]. No legacy token. */
    static Step beatStep(int frameId, String tts, Beat... beats) {
        return new Step(frameId, Collections.singletonList(ctx(10, 20)), Collections.emptyList(),
                tts, null, null, null, null, 1, Arrays.asList(beats));
    }

    /** The pre-beats shape: a bare token and no beats, which the loader has to read as one beat. */
    static Step legacyStep(int frameId, String token, int occurrence) {
        return new Step(frameId, Collections.singletonList(ctx(10, 20)), Collections.emptyList(),
                "tts", null, null, null, token, occurrence, Collections.emptyList());
    }

    /** The beats of the plan's only step, after resolution. */
    static java.util.List<Beat> beatsOf(ResolvedPlan r) {
        return r.getSteps().get(0).getStep().getBeats();
    }

    /** A one-frame plan whose only step is [step]. */
    static ResolvedPlan onePlan(Step step) {
        return PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(frame(0, "A.cs", null, 0, null, ctx(10, 40))),
                Collections.singletonList(step)));
    }

    static Plan plan(List<Frame> frames, List<Step> steps) {
        return new Plan("t", "en", frames, steps);
    }

    // ── 11b. The saved list accepts exactly what the saver could have written ──
    //
    // An agent told only "a temp file" may leave its POST payload in `.walkthroughs/`; decoded leniently it is
    // a Plan with a blank title — a blank row that errors on open. The payload that matters most is the
    // `set_plan` one: it HAS a title and frames, so only its `type` gives it away.

    static void writeJson(java.nio.file.Path dir, String name, String json) throws java.io.IOException {
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.write(dir.resolve(name + ".json"), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static final String FRAMES_JSON =
            "\"frames\":[{\"id\":0,\"file\":\"src/A.cs\",\"keep\":[{\"start\":1,\"end\":9}]}],"
                    + "\"steps\":[{\"frame\":0,\"ranges\":[{\"start\":2,\"end\":3}]}]";

    static void savingStorage() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("ae-saving");
        java.nio.file.Path dir = root.resolve(".walkthroughs");
        WalkthroughStorage store = new WalkthroughStorage(root);

        writeJson(dir, "ref-plan-1", "{\"type\":\"set_plan\",\"title\":\"Ref plan\"," + FRAMES_JSON + "}");
        writeJson(dir, "ref-plan-2", "{\"type\":\"goto\",\"step\":1}");
        writeJson(dir, "blank-title", "{\"title\":\"\"," + FRAMES_JSON + "}");
        writeJson(dir, "no-frames", "{\"title\":\"No frames\",\"frames\":[],\"steps\":[]}");
        writeJson(dir, "not-an-object", "[1, 2, 3]");
        Plan real = new Plan("Real save", "en",
                Collections.singletonList(new Frame(0, root.resolve("src").resolve("A.cs").toString(), "A", "", 0,
                        null, 0, null, 1, Collections.singletonList(focus(1, 9)), Collections.<Relation>emptyList())),
                Collections.singletonList(step(0, focus(2, 3))));
        store.save(real, "memory0");

        List<WalkthroughEntry> listed = store.list();
        check("saving: of six files, only the real save is listed",
                listed.size() == 1 && "memory0".equals(listed.get(0).getName())
                        && "Real save".equals(listed.get(0).getTitle()));
        check("saving: load refuses the set_plan payload, though it has a title and frames",
                store.load("ref-plan-1") == null);
        check("saving: load refuses another wire message (a goto)", store.load("ref-plan-2") == null);
        check("saving: load refuses a blank title", store.load("blank-title") == null);
        check("saving: load refuses no frames, and a file that is not an object",
                store.load("no-frames") == null && store.load("not-an-object") == null);
        check("  control: load still opens the real save", store.load("memory0") != null);
        long onDisk;
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(dir)) { onDisk = s.count(); }
        check("saving: hidden, not deleted — all six files are still on disk", onDisk == 6);
        check("saving: a hidden file still counts as one a save would replace", store.exists("ref-plan-1"));
        check("  control: a name nobody wrote does not", !store.exists("never-written"));

        // The control that makes the first check mean something: the SAME set_plan payload with its `type`
        // removed is listed. So it is the `type` key that hid it, not an instrument that lists nothing.
        java.nio.file.Path root2 = java.nio.file.Files.createTempDirectory("ae-saving-ctl");
        writeJson(root2.resolve(".walkthroughs"), "ref-plan-1", "{\"title\":\"Ref plan\"," + FRAMES_JSON + "}");
        List<WalkthroughEntry> listed2 = new WalkthroughStorage(root2).list();
        check("  control: the set_plan payload without its `type` IS listed",
                listed2.size() == 1 && "Ref plan".equals(listed2.get(0).getTitle()));
    }

    // ── 11c. Save gets an identity ──
    //
    // `savedAs` + `unsaved` live on the REAL Walkthrough, which is pure, so every transition the Save button
    // and the unsaved prompt read is reachable here with no IDE.

    static final class SaveCounting implements WalkthroughListener {
        int saveStates = 0, plans = 0;
        @Override public void onSaveState(WalkthroughSnapshot s) { saveStates++; }
        @Override public void onPlan(WalkthroughSnapshot s) { plans++; }
    }

    static Plan savingPlan(String title, int steps) {
        List<Step> ss = new ArrayList<>();
        for (int i = 0; i < steps; i++) ss.add(step(0, focus(20 + i, 21 + i)));
        return new Plan(title, "en",
                Collections.singletonList(new Frame(0, "/src/A.cs", "A.M", "", 0, null, 0, null, 1,
                        Arrays.asList(ctx(10, 10), focus(20, 40)), Collections.<Relation>emptyList())),
                ss);
    }

    static void savingIdentity() {
        Walkthrough w = new Walkthrough();
        SaveCounting events = new SaveCounting();
        w.addListener(events);

        check("identity: before any plan, nothing is unsaved",
                !w.getState().getUnsaved() && w.getState().getSavedAs() == null && !w.getState().getWorthAsking());

        w.setPlan(savingPlan("Generated", 3));
        WalkthroughSnapshot gen = w.getState();
        check("identity: a generated plan lands unsaved, with no name", gen.getUnsaved() && gen.getSavedAs() == null);
        check("  and is worth asking about", gen.getWorthAsking());
        check("  and setPlan does not fire onSaveState (the snapshot in onPlan carries it)",
                events.saveStates == 0 && events.plans == 1);

        check("identity: markSaved on a DIFFERENT plan object is refused",
                !w.markSaved("x", savingPlan("Generated", 3)) && w.getState().getUnsaved());
        check("identity: markSaved on the plan showing names it and cleans it",
                w.markSaved("generated", gen.getPlan())
                        && "generated".equals(w.getState().getSavedAs()) && !w.getState().getUnsaved());
        check("  and fires onSaveState once", events.saveStates == 1);
        check("  and a clean plan is not worth asking about", !w.getState().getWorthAsking());

        // Position is not saved, so nothing that only moves it can make the walkthrough stale.
        w.play(); w.nextStep(); w.gotoStep(2); w.prevStep(); w.pause(); w.restart(); w.pause();
        check("identity: playback and stepping do not dirty it", !w.getState().getUnsaved());

        // The one way a saved walkthrough stops being clean on this base: a new plan replaces it.
        w.setPlan(savingPlan("Regenerated", 3));
        check("identity: setPlan over a save clears savedAs, and the new plan is unsaved",
                w.getState().getSavedAs() == null && w.getState().getUnsaved());
        check("  control: the save of the earlier snapshot cannot clean the plan that replaced it",
                !w.markSaved("generated", gen.getPlan()) && w.getState().getUnsaved());

        w.discardUnsaved();
        check("identity: Don't save clears the mark", !w.getState().getUnsaved());
        int before = events.saveStates;
        w.discardUnsaved();
        check("  and a second Don't save is a no-op (no event)", events.saveStates == before);

        w.setPlan(savingPlan("Next", 2));
        check("identity: setPlan clears savedAs, and the new plan is unsaved",
                w.getState().getSavedAs() == null && w.getState().getUnsaved());

        w.setPlan(savingPlan("Loaded", 2), "memory0");
        check("identity: a plan loaded from a file IS that save, and lands clean",
                "memory0".equals(w.getState().getSavedAs()) && !w.getState().getUnsaved());
        check("identity: Undo restores the replaced plan's identity with it",
                w.restorePrevious() && w.getState().getSavedAs() == null && w.getState().getUnsaved()
                        && "Next".equals(w.getState().getTitle()));

        w.stop();
        check("identity: stop clears the name and the mark",
                w.getState().getSavedAs() == null && !w.getState().getUnsaved());

        w.setPlan(new Plan("", "en", savingPlan("x", 1).getFrames(), savingPlan("x", 1).getSteps()));
        check("identity: an unsaved plan with no title is NOT worth asking about (Save would refuse it)",
                w.getState().getUnsaved() && !w.getState().getWorthAsking());

        // **The early-loaded plan and its own POST** (`ClaudeExplainer.EarlyPlan`): the same plan again changes
        // nothing — not the position, not the undo slot, no listener call — and a different one still loads.
        Walkthrough e = new Walkthrough();
        PlanCounting plans = new PlanCounting();
        e.setPlan(savingPlan("Early", 3));
        e.addListener(plans);
        e.navigateToStep(2);
        plans.n = 0; // navigating fires onPlan too
        ResolvedPlan again = e.setPlanUnlessShowing(savingPlan("Early", 3));
        check("early: the same plan POSTed again keeps the reader on step 3, fires nothing, offers no undo",
                Integer.valueOf(2).equals(e.currentStepIndex()) && plans.n == 0 && !e.hasPrevious()
                        && again == e.getResolvedPlan());
        e.setPlanUnlessShowing(savingPlan("Fixed", 3));
        check("  control: a different plan through the same door loads, back on step 1, with an undo",
                Integer.valueOf(0).equals(e.currentStepIndex()) && plans.n == 1 && e.hasPrevious()
                        && "Fixed".equals(e.getState().getTitle()));
    }

    static final class PlanCounting implements WalkthroughListener {
        int n;
        @Override public void onPlan(WalkthroughSnapshot state) { n++; }
    }

    /** A root frame carrying relations — the only thing section 14 varies. */
    static Frame relFrame(int id, String file, LineRange keep, Relation... rels) {
        return new Frame(id, file, "F" + id, "", 0, null, 0, null, 1,
                Collections.singletonList(keep), Arrays.asList(rels));
    }

    /** An endpoint in this frame's own file. */
    static Endpoint at(int line) { return new Endpoint(null, line, "tok", 1, null); }

    /** An endpoint naming its file explicitly — the form that makes the owner lookup a real choice. */
    static Endpoint atFile(String file, int line) { return new Endpoint(file, line, "tok", 1, null); }

    static Relation rel(String kind, Endpoint from, Endpoint to) {
        return new Relation(kind, from, to, "note");
    }

    // ── 15. The source check: the plan against the files it names ──
    //
    // The loader never opens a file, so the server checks these; here the "files" are a map, which is the
    // whole reason SourceLines is an interface.

    static long count(List<PlanIssue> issues, String code) {
        return issues.stream().filter(i -> i.getCode().equals(code)).count();
    }

    static Frame callFrame(int id, String file, int parent, int callLine, String token, int occurrence,
                           LineRange keep) {
        return new Frame(id, file, "F" + id, "", 0, parent, callLine, token, occurrence,
                Collections.singletonList(keep), Collections.emptyList());
    }

    static void sourceCheck() {
        // A.cs: 30 lines. Line 12 holds the lookup, line 15 calls Apply twice.
        List<String> a = new ArrayList<>();
        for (int n = 1; n <= 30; n++) a.add("    var x" + n + " = Compute(" + n + ");");
        a.set(11, "    if (cache.TryGetValue(key, out var hit)) return hit;");
        a.set(14, "        Apply(Apply(x));");
        List<String> b = new ArrayList<>();
        for (int n = 1; n <= 20; n++) b.add("    int b" + n + " = " + n + ";");
        Map<String, List<String>> disk = new HashMap<>();
        disk.put("C:/proj/A.cs", a);
        disk.put("C:/proj/src/B.cs", b);
        SourceLines source = disk::get;
        String tts = "It looks up the cache first.";

        // Clean: every anchor is on its line. Second Apply is occurrence 2; "B.cs" names frame 1's file.
        Frame a0 = new Frame(0, "C:/proj/A.cs", "A", "", 0, null, 0, null, 1,
                Collections.singletonList(focus(1, 30)), Arrays.asList(
                        new Relation("flow", new Endpoint(null, 12, "cache", 1, null),
                                new Endpoint(null, 14, "x14", 1, null), "n"),
                        new Relation("flow", new Endpoint(null, 12, "hit", 2, null),
                                new Endpoint("B.cs", 5, "b5", 1, null), "n")));
        ResolvedPlan clean = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(a0, callFrame(1, "C:/proj/src/B.cs", 0, 15, "Apply", 2, ctx(1, 20))),
                Collections.singletonList(beatStep(0, tts, new Beat(12, "TryGetValue", 1, "looks up")))));
        List<PlanIssue> none = PlanSourceCheck.INSTANCE.check(clean, source);
        check("source: a clean plan raises nothing (" + none + ")", none.isEmpty());

        // Faulty: one of each, against the same files.
        Frame f0 = new Frame(0, "C:/proj/A.cs", "A", "", 0, null, 0, null, 1,
                Arrays.asList(focus(1, 20), ctx(25, 34)), Arrays.asList(
                        new Relation("flow", new Endpoint(null, 13, "cache", 1, null),
                                new Endpoint(null, 99, null, 1, null), "n"),
                        new Relation("flow", new Endpoint(null, 12, "cache", 1, null),
                                new Endpoint("Missing.cs", 3, "m", 1, null), "n")));
        ResolvedPlan bad = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(f0,
                        callFrame(1, "C:/proj/src/B.cs", 0, 15, "Apply", 3, ctx(1, 20)),
                        callFrame(2, "C:/proj/src/B.cs", 0, 16, "Apply", 1, ctx(1, 20)),
                        frame(3, "C:/proj/Gone.cs", null, 0, null, ctx(1, 5))),
                Collections.singletonList(beatStep(0, tts,
                        new Beat(12, "TryGetValues", 1, "looks up"),
                        new Beat(13, "TryGetValue", 1, "the cache")))));
        List<PlanIssue> got = PlanSourceCheck.INSTANCE.check(bad, source);
        for (PlanIssue i : got) System.out.println("      " + i);
        check("source: missing file is an error", count(got, "frame.file-unresolved") == 1
                && got.stream().anyMatch(i -> i.getCode().equals("frame.file-unresolved")
                        && i.getLevel() == IssueLevel.ERROR));
        check("source: keep 25-34 of a 30-line file", count(got, "frame.keep-past-eof") == 1);
        check("source: call token on another line, and a 3rd Apply of two — both reported",
                count(got, "frame.call-token-not-on-line") == 2);
        check("source: an occurrence overshoot says how many there are",
                got.stream().anyMatch(i -> i.getMessage().contains("as occurrence 3 — it occurs there only 2 time(s)")));
        check("source: both beats miss — a misspelt token, and a real token on the wrong line",
                count(got, "step.beat-token-not-on-line") == 2);
        check("source: relation token on line 13 is reported, quoting what line 13 reads",
                count(got, "relation.token-not-on-line") == 1
                        && got.stream().anyMatch(i -> i.getMessage().contains("x13 = Compute(13)")));
        check("source: tokenless end on line 99", count(got, "relation.line-past-eof") == 1);
        check("source: cross-file end naming a file that does not exist",
                count(got, "relation.file-unresolved") == 1);
        check("source: nothing but these seven kinds", got.size() == 9);

        // The token rule itself, both sides of each edge.
        check("tokenOnLine: overlapping matches count (aaa holds aa twice)",
                ProtocolKt.tokenOnLine("aaa", "aa", 2) && !ProtocolKt.tokenOnLine("aaa", "aa", 3));
        check("tokenOnLine: a substring inside an identifier counts, as the renderer's fallback does",
                ProtocolKt.tokenOnLine("rateTable", "rate", 1));
        check("tokenOnLine: empty token never matches", !ProtocolKt.tokenOnLine("abc", "", 1));
        check("tokenOnLine: occurrence below 1 reads as 1", ProtocolKt.tokenOnLine("abc", "b", 0));
    }

    /** True when NO issue of this code was raised — the absence half of every pair below. */
    static boolean lacks(ResolvedPlan r, String code) { return !has(r, code); }

    /**
     * Where a path's outline BEGINS, which is the only thing that separates a square top corner from a
     * rounded one — the two have identical bounds, so bounds cannot answer it.
     */
    static boolean pathStartsAt(java.awt.geom.GeneralPath p, double x, double y) {
        java.awt.geom.PathIterator it = p.getPathIterator(null);
        if (it.isDone()) return false;
        double[] c = new double[6];
        if (it.currentSegment(c) != java.awt.geom.PathIterator.SEG_MOVETO) return false;
        return Math.abs(c[0] - x) < 0.51 && Math.abs(c[1] - y) < 0.51;
    }

    static boolean has(ResolvedPlan r, String code) {
        for (PlanIssue i : r.getIssues()) if (i.getCode().equals(code)) return true;
        return false;
    }

    /** LazyDotIde's Services/BuildService.cs, whole - for StepCondense.chain. */
    private static final String POOL = """
using System.Diagnostics;
using System.Text.RegularExpressions;

namespace DotNetIDE;

public record BuildDiagnostic(
    string FilePath,
    int Line,
    int Column,
    string Code,
    string Severity,
    string Message);

public class BuildResult
{
    public bool Success { get; init; }
    public List<BuildDiagnostic> Diagnostics { get; init; } = new();
}

public class BuildService
{
    private static readonly Regex DiagnosticPattern = new(
        @"^(.+?)\((\d+),(\d+)\):\s+(error|warning)\s+([A-Z0-9]+):\s+(.+)$",
        RegexOptions.Compiled);

    private Process? _currentProcess;
    private readonly object _lock = new();

    public async Task<BuildResult> BuildAsync(string target, Action<string> onLine, CancellationToken ct)
        => await RunDotnetAsync("build", target, ["--nologo"], onLine, ct);

    public async Task<BuildResult> TestAsync(string target, Action<string> onLine, CancellationToken ct)
        => await RunDotnetAsync("test", target, ["--nologo", "--logger", "console;verbosity=normal"], onLine, ct);

    public async Task RunAsync(string executable, string[] args, Action<string> onLine, CancellationToken ct, string? workingDirectory = null)
    {
        var psi = new ProcessStartInfo(executable, args)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        if (!string.IsNullOrEmpty(workingDirectory))
            psi.WorkingDirectory = workingDirectory;

        using var process = new Process { StartInfo = psi, EnableRaisingEvents = true };
        lock (_lock) { _currentProcess = process; }

        try
        {
            process.Start();
            var stdoutTask = ReadStreamAsync(process.StandardOutput, onLine, ct);
            var stderrTask = ReadStreamAsync(process.StandardError, onLine, ct);
            await Task.WhenAll(stdoutTask, stderrTask);
            await process.WaitForExitAsync(ct);
        }
        catch (OperationCanceledException)
        {
            try { process.Kill(entireProcessTree: true); } catch { } // Best effort — process may have already exited
        }
        finally
        {
            lock (_lock) { _currentProcess = null; }
        }
    }

    public void Cancel()
    {
        lock (_lock)
        {
            try { _currentProcess?.Kill(entireProcessTree: true); }
            catch { } // Best effort — process may have already exited
        }
    }

    private async Task<BuildResult> RunDotnetAsync(
        string command,
        string? target,
        string[] extraArgs,
        Action<string> onLine,
        CancellationToken ct)
    {
        var allArgs = new List<string> { command };
        if (target != null) allArgs.Add(target);
        allArgs.AddRange(extraArgs);

        var psi = new ProcessStartInfo("dotnet", allArgs)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };

        var diagnostics = new List<BuildDiagnostic>();
        bool success = false;

        using var process = new Process { StartInfo = psi, EnableRaisingEvents = true };
        lock (_lock) { _currentProcess = process; }

        try
        {
            process.Start();

            var stdoutTask = ReadStreamAsync(process.StandardOutput, line =>
            {
                onLine(line);
                var match = DiagnosticPattern.Match(line);
                if (match.Success)
                {
                    diagnostics.Add(new BuildDiagnostic(
                        FilePath: match.Groups[1].Value.Trim(),
                        Line: int.TryParse(match.Groups[2].Value, out var l) ? l : 0,
                        Column: int.TryParse(match.Groups[3].Value, out var c) ? c : 0,
                        Severity: match.Groups[4].Value,
                        Code: match.Groups[5].Value,
                        Message: match.Groups[6].Value.Trim()));
                }
            }, ct);

            var stderrTask = ReadStreamAsync(process.StandardError, onLine, ct);

            await Task.WhenAll(stdoutTask, stderrTask);
            await process.WaitForExitAsync(ct);

            success = process.ExitCode == 0;
        }
        catch (OperationCanceledException)
        {
            try { process.Kill(entireProcessTree: true); } catch { } // Best effort — process may have already exited
            success = false;
        }
        finally
        {
            lock (_lock) { _currentProcess = null; }
        }

        return new BuildResult { Success = success, Diagnostics = diagnostics };
    }

    private static async Task ReadStreamAsync(StreamReader reader, Action<string> onLine, CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync(ct);
                if (line == null) break;
                onLine(line);
            }
        }
        catch (OperationCanceledException) { }
    }
}
""";

    public static void main(String[] args) throws Exception {
        // ── 1. The happy chain: 3 frames, depth 0/1/2, nothing wrong ──
        ResolvedPlan good = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(
                        frame(0, "A.cs", null, 0, null, ctx(18, 18), focus(68, 92)),
                        frame(1, "B.cs", 0, 85, "InvokeOn", ctx(17, 17), focus(36, 60)),
                        frame(2, "C.cs", 1, 40, "Apply", ctx(15, 15), focus(68, 80))),
                Arrays.asList(step(0, ctx(68, 70)), step(1, ctx(36, 38)), step(2, ctx(68, 70)))));
        check("clean chain: 3 frames resolved", good.getFrames().size() == 3);
        check("clean chain: depths 0,1,2", good.frame(0).getDepth() == 0
                && good.frame(1).getDepth() == 1 && good.frame(2).getDepth() == 2);
        check("clean chain: all nest", good.frame(1).nests() && good.frame(2).nests());
        check("clean chain: focus is the stated one", good.frame(0).getFocus().getStart() == 68);
        check("clean chain: no cycle issue (control below proves the check fires)", !has(good, "frame.cycle"));
        check("clean chain: no depth issue", !has(good, "frame.depth-exceeded"));
        check("clean chain: no dropped step", !has(good, "step.dropped"));

        // ── 2. Cycle: 0 -> 1 -> 0. Broken at the frame that closes it, both rendered ──
        ResolvedPlan cyc = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(
                        frame(0, "A.cs", 1, 10, null, ctx(10, 20)),
                        frame(1, "B.cs", 0, 12, null, ctx(10, 20))),
                Collections.singletonList(step(0, ctx(10, 11)))));
        check("cycle: reported", has(cyc, "frame.cycle"));
        check("cycle: both frames still rendered", cyc.getFrames().size() == 2);
        check("cycle: at least one is a root", !cyc.roots().isEmpty());
        boolean acyclic = true;
        for (ResolvedFrame f : cyc.getFrames()) {
            Set<Integer> seen = new HashSet<>();
            Integer p = f.getParentId();
            while (p != null && seen.add(p)) p = cyc.frame(p) == null ? null : cyc.frame(p).getParentId();
            if (p != null) acyclic = false;
        }
        check("cycle: resolved links are acyclic", acyclic);

        // ── 3. Depth 3 degrades to top level ──
        ResolvedPlan deep = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(
                        frame(0, "A.cs", null, 0, null, ctx(1, 50)),
                        frame(1, "B.cs", 0, 10, null, ctx(1, 50)),
                        frame(2, "C.cs", 1, 10, null, ctx(1, 50)),
                        frame(3, "D.cs", 2, 10, null, ctx(1, 50))),
                Collections.singletonList(step(0, ctx(1, 2)))));
        check("depth: over-deep frame reported", has(deep, "frame.depth-exceeded"));
        check("depth: it became a root", deep.frame(3).getParentId() == null && deep.frame(3).getDepth() == 0);
        check("depth: the legal ones kept their nesting", deep.frame(2).getDepth() == 2);

        // ── 4. A call line the parent does not keep ──
        ResolvedPlan unkept = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(
                        frame(0, "A.cs", null, 0, null, ctx(10, 20)),
                        frame(1, "B.cs", 0, 85, "x", ctx(10, 20))),
                Collections.singletonList(step(0, ctx(10, 11)))));
        check("call line: not-kept reported", has(unkept, "frame.call-line-not-kept"));
        check("call line: child degraded to top level", unkept.frame(1).getParentId() == null);

        // ── 5. Focus inference, and its control (a stated focus must NOT be inferred) ──
        ResolvedPlan inferred = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(frame(0, "A.cs", null, 0, null, ctx(18, 18), ctx(68, 92))),
                Collections.singletonList(step(0, ctx(68, 70)))));
        check("focus: inferred when unstated", has(inferred, "frame.focus-inferred"));
        check("focus: inference picks the second range", inferred.frame(0).getFocus().getStart() == 68);
        check("focus: NOT inferred when stated (control)", !has(good, "frame.focus-inferred"));

        // ── 6. A step lighting a line its frame elides ──
        // Just past a kept range: the loader widens it instead of a [warn] re-send.
        ResolvedPlan stray = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(frame(0, "A.cs", null, 0, null, ctx(1, 3), focus(68, 87))),
                Collections.singletonList(step(0, ctx(86, 90)))));
        check("step range: a few stray lines widen keep", has(stray, "step.keep-widened")
                && !has(stray, "step.range-outside-keep"));
        check("step range: the nearest range grows, role kept",
                stray.frame(0).getKeep().size() == 2 && stray.frame(0).getKeep().get(1).getEnd() == 90
                        && stray.frame(0).getKeep().get(1).isFocus() && stray.frame(0).getKeep().get(0).getEnd() == 3);
        check("step range: the focus is the widened range", stray.frame(0).getFocus().getEnd() == 90);
        check("step range: widening is [info]", stray.issues(IssueLevel.WARN).isEmpty());
        // The gap counts: keep 68-87, step 92-93 adds 88-93 = 6 lines, the cap — widened.
        ResolvedPlan gap = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(frame(0, "A.cs", null, 0, null, ctx(68, 87))),
                Collections.singletonList(step(0, ctx(92, 93)))));
        check("step range: a gap up to the cap is filled", has(gap, "step.keep-widened")
                && gap.frame(0).getKeep().get(0).getEnd() == 93);
        // Control: one line further is a new excerpt the author never chose — still the [warn].
        ResolvedPlan outside = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(frame(0, "A.cs", null, 0, null, ctx(68, 87))),
                Collections.singletonList(step(0, ctx(93, 94)))));
        check("step range: far outside keep still reported (control)", has(outside, "step.range-outside-keep")
                && !has(outside, "step.keep-widened") && outside.frame(0).getKeep().get(0).getEnd() == 87);
        check("step range: the step still renders", outside.getSteps().size() == 1);

        // ── 7. An orphaned step, and a duplicate frame id ──
        ResolvedPlan orphan = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(frame(0, "A.cs", null, 0, null, ctx(1, 10))),
                Arrays.asList(step(0, ctx(1, 2)), step(9, ctx(1, 2)))));
        check("orphan step: unknown frame reported", has(orphan, "step.unknown-frame"));
        check("orphan step: dropped", has(orphan, "step.dropped") && orphan.getSteps().size() == 1);
        ResolvedPlan dup = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(frame(0, "A.cs", null, 0, null, ctx(1, 10)),
                              frame(0, "B.cs", null, 0, null, ctx(1, 10))),
                Collections.singletonList(step(0, ctx(1, 2)))));
        check("duplicate id: reported and first wins",
                has(dup, "frame.duplicate-id") && dup.getFrames().size() == 1
                        && dup.frame(0).getFile().equals("A.cs"));

        // ── 8. The outline: scenes, runs, revisits ──
        // Steps: f0, f0, f1, f0  → three groups (the revisit must NOT merge back into group 0).
        ResolvedPlan revisit = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(frame(0, "A.cs", null, 0, null, ctx(10, 40)),
                              frame(1, "B.cs", 0, 20, "call", ctx(10, 40))),
                Arrays.asList(step(0, ctx(10, 11)), step(0, ctx(12, 13)),
                              step(1, ctx(10, 11)), step(0, ctx(14, 15)))));
        Outline out = PlanOutline.INSTANCE.of(revisit);
        check("outline: one scene, so the scene level is off",
                out.getScenes().size() == 1 && !out.getScened());
        List<OutlineGroup> groups = out.getScenes().get(0).getGroups();
        check("outline: 3 groups from f0,f0,f1,f0", groups.size() == 3);
        check("outline: group 0 holds two steps", groups.get(0).getSteps().size() == 2);
        check("outline: the revisit is its own group",
                groups.get(2).getSteps().size() == 1 && groups.get(2).getFrame() == 0);
        // Every plan step appears exactly once, in plan order — the property the projection's bijection
        // check was really asserting, stated directly now that a row IS a step index.
        List<Integer> seen = new ArrayList<>();
        for (OutlineScene sc : out.getScenes())
            for (OutlineGroup g : sc.getGroups())
                for (OutlineStep st : g.getSteps()) seen.add(st.getIndex());
        boolean order = seen.size() == revisit.getSteps().size();
        for (int k = 0; order && k < seen.size(); k++) order = seen.get(k) == k;
        check("outline: every step appears exactly once, in plan order", order);
        // **No line numbers, no file names on a row** — asserted on the type rather
        // than on the rendering, because the sidebar can only draw what the row carries. A field count would
        // be wrong here (serialization adds its own), so this names the four accessors that must not exist.
        boolean bare = true, hasIndex = false, hasLabel = false;
        for (java.lang.reflect.Method m : OutlineStep.class.getDeclaredMethods()) {
            String n = m.getName();
            if (n.equals("getStart") || n.equals("getEnd") || n.equals("getFile") || n.equals("getRanges")) {
                bare = false;
            }
            if (n.equals("getIndex")) hasIndex = true;
            if (n.equals("getLabel")) hasLabel = true;
        }
        check("outline: a step row carries no line range and no file name", bare);
        check("outline: a step row does carry its index and label (control)", hasIndex && hasLabel);

        // Two scenes → the scene level turns on, and a revisited scene is TWO rows (two mounts).
        ResolvedPlan scened = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(sceneFrame(0, "A.cs", 0), sceneFrame(1, "B.cs", 1)),
                Arrays.asList(step(0, ctx(10, 11)), step(1, ctx(10, 11)), step(0, ctx(12, 13)))));
        Outline so = PlanOutline.INSTANCE.of(scened);
        check("outline: more than one scene turns the level on", so.getScened());
        check("outline: a revisited scene is two rows, one per mount", so.getScenes().size() == 3);
        check("outline: a revisit reuses its scene number",
                so.getScenes().get(0).getLabel().equals(so.getScenes().get(2).getLabel())
                        && !so.getScenes().get(0).getLabel().equals(so.getScenes().get(1).getLabel()));
        check("outline: the row count matches the plan's step count",
                so.getSteps() == 3);

        // The step view: the frame's identity, the step's narration, and the location link's line span.
        ResolvedPlan disjoint = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(frame(0, "A.cs", null, 0, null, ctx(10, 40))),
                Collections.singletonList(new Step(0, Arrays.asList(ctx(10, 11), ctx(20, 21)),
                        Collections.emptyList(), "tts", null, null, null, null, 1,
                        Collections.emptyList()))));
        StepView sv = PlanOutline.INSTANCE.stepView(disjoint, 0);
        check("stepView: names the primary frame", sv != null && sv.getTitle().length() > 0);
        check("stepView: start/end are the enclosing span of the step's ranges",
                sv.getStart() == 10 && sv.getEnd() == 21);
        check("stepView: carries the narration", sv.getTtsText().equals("tts"));
        check("stepView: out of range is null", PlanOutline.INSTANCE.stepView(disjoint, 7) == null);
        check("outline: disjoint ranges are the step's, untouched by the outline",
                disjoint.getSteps().get(0).rangesIn(0).size() == 2);

        // A multi-frame step is ONE row, under its primary frame — the other frame is the tree's business.
        ResolvedPlan multi = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(frame(0, "A.cs", null, 0, null, ctx(10, 40)),
                              frame(1, "B.cs", 0, 20, "call", ctx(10, 40))),
                Collections.singletonList(multiStep(1, ctx(12, 13), 0, ctx(20, 21)))));
        Outline mo = PlanOutline.INSTANCE.of(multi);
        check("multi-frame: one row, under the primary frame (frames[0])",
                mo.getScenes().size() == 1 && mo.getScenes().get(0).getGroups().size() == 1
                        && mo.getScenes().get(0).getGroups().get(0).getFrame() == 1);
        check("multi-frame: the step still names both frames",
                multi.getSteps().get(0).getFrames().size() == 2);
        check("multi-frame: ranges in the non-primary frame are reachable",
                multi.getSteps().get(0).rangesIn(0).size() == 1);

        // ── 9. Soft caps ──
        ResolvedPlan caps = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(
                        frame(0, "A.cs", null, 0, null, ctx(1, 60)),
                        frame(1, "B.cs", 0, 10, null, ctx(1, 30)),
                        frame(2, "C.cs", 0, 11, null, ctx(1, 30)),
                        frame(3, "D.cs", 0, 12, null, ctx(1, 30)),
                        frame(4, "E.cs", 0, 13, null, ctx(1, 30)),
                        frame(5, "F.cs", 0, 14, null, ctx(1, 30)),
                        frame(6, "G.cs", 0, 15, null, ctx(1, 30)),
                        frame(7, "H.cs", 0, 16, null, ctx(1, 30))),
                Collections.singletonList(step(0, ctx(1, 2)))));
        // Seven, one over the cap. The number in the assertion is deliberately not written out: it reads the
        // constant, so changing the cap cannot leave a probe passing for the wrong reason.
        check("caps: more than MAX_SIBLINGS(" + PlanLoader.MAX_SIBLINGS + ") siblings reported",
                has(caps, "plan.siblings-exceeded"));
        check("caps: line budget reported per scene", has(caps, "scene.line-budget-exceeded"));
        // The control: a genuinely small plan — 14 kept lines. (The `chain` above keeps 66 and trips the
        // budget, so it cannot be the control.)
        ResolvedPlan small = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(frame(0, "A.cs", null, 0, null, ctx(18, 18), focus(68, 80))),
                Collections.singletonList(step(0, ctx(68, 70)))));
        check("caps: budget NOT reported on a small plan (control)",
                !has(small, "scene.line-budget-exceeded"));
        // **And the 66-line chain is over the cap**: measured viewports in a tool window beside the sidebar are
        // 25-55 lines, so a 66-line scene scrolls. `small` above is what keeps this from being a cap that cries
        // wolf.
        check("caps: the 66-line chain is over the measured-viewport cap",
                has(good, "scene.line-budget-exceeded"));
        check("caps: unnarrated frame reported", has(caps, "frame.no-step"));

        // ── Scenes: the mounted-set model, and the two degrades it adds ──
        //
        // Every "absence" below has its own present case, per the standing rule: the cross-scene parent is
        // asserted beside a same-scene parent that nests, and the per-scene budget beside a plan that trips it.

        // Three scenes, 2 + 2 + 1 frames, steps visiting 0, 1, 2 and then 0 again.
        ResolvedPlan scenes = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(
                        sceneFrame(0, 0, "A.cs", null, 0, null, ctx(18, 18), focus(68, 80)),
                        sceneFrame(0, 1, "B.cs", 0, 70, "Call", ctx(17, 17), focus(36, 44)),
                        sceneFrame(1, 2, "C.cs", null, 0, null, ctx(11, 11), focus(29, 41)),
                        sceneFrame(1, 3, "D.cs", 2, 39, "InvokeOn", ctx(17, 17), focus(36, 43)),
                        sceneFrame(2, 4, "E.cs", null, 0, null, ctx(15, 15), focus(68, 85))),
                Arrays.asList(
                        step(0, ctx(68, 70)), step(1, ctx(36, 38)),
                        step(2, ctx(29, 31)), step(3, ctx(36, 38)),
                        step(4, ctx(68, 70)),
                        step(0, ctx(71, 73)))));
        check("scenes: 3 scenes found", scenes.getScenes().size() == 3);
        check("scenes: grouped in step order (0,1,2)",
                scenes.getScenes().get(0).getId() == 0
                        && scenes.getScenes().get(1).getId() == 1
                        && scenes.getScenes().get(2).getId() == 2);
        check("scenes: frames land in their own scene",
                scenes.scene(0).getFrames().size() == 2
                        && scenes.scene(1).getFrames().size() == 2
                        && scenes.scene(2).getFrames().size() == 1);
        check("scenes: a revisited scene collects both runs of steps",
                scenes.scene(0).getStepIndices().size() == 3);
        check("scenes: step -> scene mapping",
                scenes.sceneOfStep(0) == 0 && scenes.sceneOfStep(2) == 1
                        && scenes.sceneOfStep(4) == 2 && scenes.sceneOfStep(5) == 0);
        // 0,0,1,1,2,0 — three crossings, and the last one is the revisit paying again.
        check("scenes: 3 rebuilds end to end (the revisit is not free)",
                scenes.sceneTransitions() == 3);
        check("scenes: a same-scene parent still nests (control)",
                scenes.frame(1).nests() && scenes.frame(3).nests());
        check("scenes: no cross-scene parent issue here (control)",
                !has(scenes, "frame.parent-other-scene"));
        check("scenes: no cross-scene step issue here (control)",
                !has(scenes, "step.frames-cross-scene"));

        // A parent in another scene: no node to nest into, so top level and named.
        ResolvedPlan farParent = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(
                        sceneFrame(0, 0, "A.cs", null, 0, null, ctx(18, 18), focus(68, 80)),
                        sceneFrame(1, 1, "B.cs", 0, 70, "Call", ctx(17, 17), focus(36, 44))),
                Arrays.asList(step(0, ctx(68, 70)), step(1, ctx(36, 38)))));
        check("scenes: cross-scene parent reported", has(farParent, "frame.parent-other-scene"));
        check("scenes: cross-scene child is re-rooted, not dropped",
                farParent.getFrames().size() == 2 && farParent.frame(1).getParentId() == null);
        check("scenes: re-rooted child does not claim to nest", !farParent.frame(1).nests());

        // A multi-frame step reaching into another scene: the stray reference is dropped, the step survives.
        ResolvedPlan farStep = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(
                        sceneFrame(0, 0, "A.cs", null, 0, null, ctx(18, 18), focus(68, 80)),
                        sceneFrame(1, 1, "B.cs", null, 0, null, ctx(17, 17), focus(36, 44))),
                Arrays.asList(multiStep(0, ctx(68, 70), 1, ctx(36, 38)))));
        check("scenes: cross-scene multi-frame step reported", has(farStep, "step.frames-cross-scene"));
        check("scenes: the step survives with only its primary scene's frames",
                farStep.getSteps().size() == 1 && farStep.getSteps().get(0).getFrames().size() == 1
                        && farStep.getSteps().get(0).getPrimaryFrameId() == 0);

        // **The control the scene model exists for**: two scenes that each fit, and together do not. A
        // whole-plan budget would flag this and the per-scene one must not. Paired with `caps: line
        // budget reported per scene` above, which does fire.
        //
        // The sizes are **derived from the cap**, not written out, so changing the cap cannot make the control
        // fail for the wrong subject — it is not about how big a scene may be, it is about the budget being
        // per-scene.
        int perScene = (PlanLoader.LINE_BUDGET * 3) / 4;
        int lastLine = perScene + 8; // ctx(1,1) is the other kept line, and focus starts at 10
        ResolvedPlan longWalk = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(
                        sceneFrame(0, 0, "A.cs", null, 0, null, ctx(1, 1), focus(10, lastLine)),
                        sceneFrame(1, 1, "B.cs", null, 0, null, ctx(1, 1), focus(10, lastLine))),
                Arrays.asList(step(0, ctx(10, 12)), step(1, ctx(10, 12)))));
        check("scenes: " + (2 * perScene) + " kept lines over 2 scenes is NOT over budget",
                !has(longWalk, "scene.line-budget-exceeded"));
        check("scenes: each scene fits and the pair does not (so the control is not vacuous)",
                longWalk.scene(0).keptLines() == perScene && longWalk.scene(1).keptLines() == perScene
                        && 2 * perScene > PlanLoader.LINE_BUDGET);

        // A plan with no `scene` anywhere is one scene and one mount.
        check("scenes: a plan that never mentions scenes is exactly one scene",
                good.getScenes().size() == 1 && good.sceneTransitions() == 0);

        // ── 11. A saved walkthrough round-trips the schema, and `scene` is what has to survive ──
        //
        // WalkthroughStorage rewrites paths through `Frame.copy(file = …)`, so every field it does not name is
        // carried *by construction*. That is an argument, not a measurement, and the failure it would hide is
        // the worst one the model has: a plan that lost its scenes comes back as ONE tree of every file it
        // ever mentioned — the exact picture scenes exist to prevent, arriving through the storage layer
        // instead of through the schema. So it is asserted, on a plan with three scenes and every field that
        // travels beside them.
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("ae-probe");
        String fileA = root.resolve("src").resolve("A.cs").toString();
        String fileB = root.resolve("src").resolve("B.cs").toString();
        String fileC = root.resolve("src").resolve("C.cs").toString();

        // A cross-file relation: its endpoint carries its OWN file, which is as root-dependent as the frame's
        // and takes part in the same rewrite.
        Relation crossFile = new Relation(
                Relation.KIND_FLOW,
                new Endpoint(null, 70, "verdict", 1, null),
                new Endpoint(fileC, 12, "Apply", 2, null),
                "hands the verdict on");
        Frame saveRoot = new Frame(0, fileA, "A.Run", "the entry point", 0, null, 0, null, 1,
                Arrays.asList(ctx(18, 18), focus(68, 92)), Collections.singletonList(crossFile));
        Frame saveChild = new Frame(1, fileB, "B.InvokeOn", "", 0, 0, 85, "InvokeOn", 2,
                Arrays.asList(ctx(17, 17), focus(36, 60)), Collections.<Relation>emptyList());
        Frame saveFar = new Frame(2, fileC, "C.Apply", "", 2, null, 0, null, 1,
                Collections.singletonList(focus(10, 30)), Collections.<Relation>emptyList());
        Plan saved = new Plan("Round trip", "de",
                Arrays.asList(saveRoot, saveChild, saveFar),
                Arrays.asList(step(0, ctx(68, 70)), step(1, ctx(36, 38)), step(2, ctx(10, 12))));

        WalkthroughStorage store = new WalkthroughStorage(root);
        String savedAt = store.save(saved, "round-trip");
        String onDisk = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(savedAt)),
                java.nio.charset.StandardCharsets.UTF_8);
        Plan back = store.load("round-trip");

        check("storage: the file loads at all", back != null);
        // **The control for everything below**: if save/load were an identity the scene assertions would pass
        // on a rewrite that never ran. The file on disk must hold RELATIVE paths and none of the absolute root.
        check("storage: paths on disk are relative (so the rewrite really ran)",
                onDisk.contains("\"src/A.cs\"") && !onDisk.contains(root.toString().replace("\\", "\\\\")));
        check("storage: scenes survive the round trip",
                back.getFrames().get(0).getScene() == 0
                        && back.getFrames().get(1).getScene() == 0
                        && back.getFrames().get(2).getScene() == 2);
        check("storage: and the scene is genuinely on disk (not re-defaulted on load)",
                onDisk.contains("\"scene\": 2"));
        check("storage: the nesting link survives — parent, call line, token, occurrence",
                back.getFrames().get(1).getParent().intValue() == 0
                        && back.getFrames().get(1).getCallLine() == 85
                        && "InvokeOn".equals(back.getFrames().get(1).getCallToken())
                        && back.getFrames().get(1).getCallOccurrence() == 2);
        check("storage: keep ranges survive with their roles",
                back.getFrames().get(0).getKeep().size() == 2
                        && back.getFrames().get(0).getKeep().get(1).isFocus());
        check("storage: title, explanation and language survive",
                "A.Run".equals(back.getFrames().get(0).getTitle())
                        && "the entry point".equals(back.getFrames().get(0).getExplanation())
                        && "de".equals(back.getLanguage()));
        check("storage: steps survive whole",
                back.getSteps().size() == 3 && back.getSteps().get(1).getFrame().intValue() == 1);
        check("storage: frame paths come back absolute",
                back.getFrames().get(0).getFile().equals(fileA));
        check("storage: a relation's own endpoint file is rewritten and restored too",
                back.getFrames().get(0).getRelations().get(0).getTo().getFile().equals(fileC)
                        && back.getFrames().get(0).getRelations().get(0).getFrom().getFile() == null);
        check("storage: the relation's note and occurrence survive",
                "hands the verdict on".equals(back.getFrames().get(0).getRelations().get(0).getNote())
                        && back.getFrames().get(0).getRelations().get(0).getTo().getOccurrence() == 2);

        // **The assertion the bullet is actually about**: resolve both and compare the scene structure. A
        // schema field surviving is the mechanism; the tree still being three scenes is the consequence.
        ResolvedPlan beforeSave = PlanLoader.INSTANCE.resolve(saved);
        ResolvedPlan afterLoad = PlanLoader.INSTANCE.resolve(back);
        check("storage: the loaded plan resolves to the same scene count",
                afterLoad.getScenes().size() == beforeSave.getScenes().size()
                        && afterLoad.getScenes().size() == 2);
        check("storage: and to the same number of rebuilds",
                afterLoad.sceneTransitions() == beforeSave.sceneTransitions());
        check("storage: no new issue is introduced by the round trip",
                afterLoad.getIssues().size() == beforeSave.getIssues().size());

        // A file written while `view`/`segments` still existed must load, not fail: `ignoreUnknownKeys`.
        java.nio.file.Files.write(
                root.resolve(".walkthroughs").resolve("legacy.json"),
                ("{\"title\":\"Old\",\"view\":\"editor\",\"segments\":[{\"id\":1}],"
                        + "\"frames\":[{\"id\":0,\"file\":\"src/A.cs\",\"scene\":3,"
                        + "\"keep\":[{\"start\":1,\"end\":9}]}],"
                        + "\"steps\":[{\"frame\":0,\"ranges\":[{\"start\":2,\"end\":3}]}]}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Plan legacy = store.load("legacy");
        check("storage: a file with the dropped `view`/`segments` keys still loads",
                legacy != null && legacy.getFrames().size() == 1);
        check("storage: and its scene is read, not discarded with the unknown keys",
                legacy.getFrames().get(0).getScene() == 3);

        savingStorage();
        savingIdentity();
        sourceCheck();

        // ── 12. The tree's settings defaults agree with the style classes' built-ins ──
        //
        // Two transcriptions of one set of decisions: `NestStyle()` / `CodeNodeStyle()`, which every internal
        // caller and every bench gets, and `AudioExplainerSettings.DEF_TREE_*`, which a fresh install gets and
        // which Reset-to-defaults restores. Neither can be derived from the other — the settings hold strings
        // and cannot import an enum — so the only thing that keeps them together is this check.
        AudioExplainerSettings.State def = new AudioExplainerSettings.State();
        NestStyle fromSettings = TreeStyle.INSTANCE.nest(def);
        CodeNodeStyle nodeFromSettings = TreeStyle.INSTANCE.node(def);
        check("settings: the shipped nest defaults are the style class's own",
                fromSettings.equals(TreeStyle.INSTANCE.builtinNest()));
        check("settings: the shipped node defaults are the style class's own",
                nodeFromSettings.equals(TreeStyle.INSTANCE.builtinNode()));
        // Spelled out per value rather than as one line, so a changed default names itself.
        check("settings: the six tree defaults (PROJECT_RELATIVE / TINT / OPENABLE / 600 / grow off / hosts grow)",
                nodeFromSettings.getPath() == NodePath.PROJECT_RELATIVE
                        && nodeFromSettings.getDeemphasis() == NodeDeemphasis.TINT
                        && nodeFromSettings.getElision() == NodeElision.OPENABLE
                        && nodeFromSettings.getMaxHeight() == 600
                        && !nodeFromSettings.getGrow()
                        && fromSettings.getGrowHosts());
        check("settings: the two the reader changed before the round are BRACE / BOTTOM_BORDER",
                fromSettings.getLeader() == NestLeader.BRACE
                        && fromSettings.getChevron() == ChevronStyle.BOTTOM_BORDER);
        // The step fold ships as ALWAYS: OVER_BUDGET leaves a small walkthrough fully expanded.
        check("settings: the step fold ships as ALWAYS",
                fromSettings.getStepFold() == StepFold.ALWAYS);
        check("settings: a step's own folding ships as LINKS, in the settings and the style alike",
                fromSettings.getCondense() == StepCondense.LINKS
                        && TreeStyle.INSTANCE.builtinNest().getCondense() == StepCondense.LINKS);
        AudioExplainerSettings.State noCondense = new AudioExplainerSettings.State();
        noCondense.treeCondense = "off";
        check("  control: switching it off reaches the style",
                TreeStyle.INSTANCE.nest(noCondense).getCondense() == StepCondense.OFF);

        // ── StepCondense.window, on two real cases ──
        // xhigh-deep-1, steps 6-7: the child plays under frame 0, which keeps 6-10, 21-31, 33-65 and hangs it
        // from line 62. Condensed it is its three headers and the call line — 4 rows instead of 49.
        List<kotlin.ranges.IntRange> ancestor = StepCondense.Companion.window(
                Arrays.asList(new kotlin.ranges.IntRange(6, 10), new kotlin.ranges.IntRange(21, 31), new kotlin.ranges.IntRange(33, 65)),
                new java.util.HashSet<>(Arrays.asList(6, 21, 33, 62)), l -> true, 0);
        check("condense: an ancestor keeps its headers and the call line — " + ancestor,
                ancestor.toString().equals("[6..6, 21..21, 33..33, 62..62]"));
        // high-standard-real-1 frame 0: a link from line 62 back up to 8, with 51 kept lines between. Folding the
        // stretch between, except the headers, the step (61-62) and a line either side, leaves 4 rows of it.
        List<kotlin.ranges.IntRange> keep0 = Arrays.asList(
                new kotlin.ranges.IntRange(6, 16), new kotlin.ranges.IntRange(18, 31), new kotlin.ranges.IntRange(33, 65));
        List<kotlin.ranges.IntRange> linked = StepCondense.Companion.window(keep0,
                new java.util.HashSet<>(Arrays.asList(6, 18, 33, 61, 62, 8)), l -> l > 8 && l < 62, 1);
        check("condense: a long link's stretch folds to what the step touches — " + linked,
                linked.toString().equals("[6..9, 18..19, 33..34, 60..65]"));
        check("  control: with no long link the zone is empty and the frame is its whole keep",
                StepCondense.Companion.window(keep0, new java.util.HashSet<>(Arrays.asList(6, 18, 33)), l -> false, 1)
                        .toString().equals("[6..16, 18..31, 33..65]"));
        // A run shorter than MIN_FOLD with code on both sides stays: a placeholder would cost a row to save four.
        List<kotlin.ranges.IntRange> shortRun = StepCondense.Companion.window(
                Collections.singletonList(new kotlin.ranges.IntRange(10, 30)),
                new java.util.HashSet<>(Arrays.asList(10, 15, 30)), l -> true, 0);
        check("condense: a short run between kept lines stays, a long one folds — " + shortRun,
                shortRun.toString().equals("[10..15, 30..30]"));

        // ── StepCondense.chain: the headers of the blocks a line sits in ──
        // xhigh-deep-1's pool, lines 1-102 as they are in LazyDotIde. The "Exhaustion signal" step (80-82) sits in
        // the `if` at 70-71, in Get (67-68), in the class (21-22).
        String[] pool = POOL.split("\n", -1);
        kotlin.jvm.functions.Function1<Integer, String> poolText = l -> l >= 1 && l <= pool.length ? pool[l - 1] : null;
        check("chain: a step inside an `if` keeps the `if`, its brace, the method and the class — "
                        + StepCondense.Companion.chain(80, poolText),
                StepCondense.Companion.chain(80, poolText).toString().equals("[71, 70, 68, 67, 22, 21]"));
        // STEP on that step, frame 2 (keep 21, 23-31, 67-102): before the chain, 69-78 folded as "10 lines" and
        // took the `if` with it; with it, the `if` and its brace stay and only its first branch (72-78) folds.
        List<kotlin.ranges.IntRange> keep2 = Arrays.asList(new kotlin.ranges.IntRange(21, 21),
                new kotlin.ranges.IntRange(23, 31), new kotlin.ranges.IntRange(67, 102));
        java.util.Set<Integer> placed2 = new java.util.HashSet<>(Arrays.asList(21, 23, 67, 80));
        java.util.Set<Integer> chains2 = new java.util.HashSet<>();
        for (int l : placed2) chains2.addAll(StepCondense.Companion.chain(l, poolText));
        java.util.Set<Integer> anchors2 = new java.util.HashSet<>(Arrays.asList(21, 23, 67, 80, 81, 82));
        List<kotlin.ranges.IntRange> stepped = StepCondense.Companion.window(keep2, anchors2, l -> true, 1, chains2);
        check("condense: STEP keeps the `if` a step sits in — " + stepped,
                stepped.toString().equals("[21..21, 23..24, 67..71, 79..83]"));
        check("  control: without the chain, 69-78 folds and the `if` with it",
                StepCondense.Companion.window(keep2, anchors2, l -> true, 1, java.util.Collections.emptySet())
                        .toString().equals("[21..21, 23..24, 67..68, 79..83]"));
        // The ancestor case again: above the call line 62, CreateItem's signature and brace now stay.
        java.util.Set<Integer> chains0 = new java.util.HashSet<>();
        for (int l : Arrays.asList(6, 21, 33, 62)) chains0.addAll(StepCondense.Companion.chain(l, poolText));
        List<kotlin.ranges.IntRange> ancestorChained = StepCondense.Companion.window(
                Arrays.asList(new kotlin.ranges.IntRange(6, 10), new kotlin.ranges.IntRange(21, 31), new kotlin.ranges.IntRange(33, 65)),
                new java.util.HashSet<>(Arrays.asList(6, 21, 33, 62)), l -> true, 0, chains0);
        check("condense: an ancestor shows which method the call line is in — " + ancestorChained,
                ancestorChained.toString().equals("[6..6, 21..22, 33..33, 59..62]"));
        // K&R, a multi-line condition, `} else {`, a comment and a lone closer at a lesser indent.
        String[] kr = {
                "class A {",
                "    void m() {",
                "        if (a &&",
                "            b) {",
                "            x();",
                "        } else {",
                "            y();",
                "        }",
                "    }",
        };
        kotlin.jvm.functions.Function1<Integer, String> krText = l -> l >= 1 && l <= kr.length ? kr[l - 1] : null;
        check("chain: `} else {` opens the block it is in, then the method and the class — "
                        + StepCondense.Companion.chain(7, krText),
                StepCondense.Companion.chain(7, krText).toString().equals("[6, 2, 1]"));
        String[] py = {"def f():", "# stray", "    if x:", "        pass", "    });", "        y = 1"};
        kotlin.jvm.functions.Function1<Integer, String> pyText = l -> l >= 1 && l <= py.length ? py[l - 1] : null;
        check("chain: comments and lone closers are no headers — " + StepCondense.Companion.chain(6, pyText),
                StepCondense.Companion.chain(6, pyText).toString().equals("[3, 1]"));
        check("  control: a blank line has no chain", StepCondense.Companion.chain(2, l -> "").isEmpty());

        // ── StepCondense.member: the method a step is in, for MEMBER ──
        check("block: Get runs from its signature through its closing brace — " + StepCondense.Companion.block(67, poolText),
                StepCondense.Companion.block(67, poolText).toString().equals("67..86"));
        check("block: the `if` in it, brace to brace — " + StepCondense.Companion.block(70, poolText),
                StepCondense.Companion.block(70, poolText).toString().equals("70..83"));
        check("  control: an attribute line is a block of itself", StepCondense.Companion.block(58, poolText).toString().equals("58..58"));
        java.util.List<Integer> kept2 = new java.util.ArrayList<>();
        for (kotlin.ranges.IntRange r : keep2) for (int l = r.getFirst(); l <= r.getLast(); l++) kept2.add(l);
        check("member: a statement deep in Get is in Get, not in the class — " + StepCondense.Companion.member(80, kept2, poolText),
                String.valueOf(StepCondense.Companion.member(80, kept2, poolText)).equals("67..86"));
        java.util.Set<Integer> outsideGet = new java.util.HashSet<>();
        for (int l : kept2) if (l < 67 || l > 86) outsideGet.add(l);
        List<kotlin.ranges.IntRange> member = StepCondense.Companion.window(keep2, anchors2, outsideGet::contains, 1, chains2);
        check("condense: MEMBER keeps Get whole and folds Return — " + member,
                member.toString().equals("[21..21, 23..24, 67..86]"));
        // A frame that is one method plus its class header: the method holds every kept line but the header, so
        // it is the member, and there is nothing else to fold.
        check("  control: a frame of one method has it as the member",
                String.valueOf(StepCondense.Companion.member(80, Arrays.asList(21, 67, 70, 80, 86), poolText)).equals("67..86"));
        check("  control: a frame that is only the step line has no member",
                StepCondense.Companion.member(80, Arrays.asList(80), poolText) == null);
        // hig-standard-1 frame 0 keeps the two interfaces above the class (6-16) as well as the class: no block
        // holds every kept line, so "below the block holding everything" took the class as the member. The type
        // header stops the walk instead.
        java.util.List<Integer> kept0 = new java.util.ArrayList<>();
        for (int[] r : new int[][]{{6, 16}, {18, 31}, {33, 65}}) for (int l = r[0]; l <= r[1]; l++) kept0.add(l);
        check("member: with types above the class, a constructor line is in the constructor — "
                        + StepCondense.Companion.member(38, kept0, poolText),
                String.valueOf(StepCondense.Companion.member(38, kept0, poolText)).equals("36..44"));
        check("member: a field is a member of itself — " + StepCondense.Companion.member(24, kept0, poolText),
                String.valueOf(StepCondense.Companion.member(24, kept0, poolText)).equals("24..24"));
        check("  control: the class header has no member", StepCondense.Companion.member(21, kept0, poolText) == null);
        String[] types = {"public sealed class Pool<T> : IPool where T : class, new()", "class Foo:",
                "@Serializable data class Foo(val a: Int)", "object Registry {", "pub struct Point {",
                "export default interface Shape {", "namespace DotNetIDE;"};
        String[] notTypes = {"    private object _lock = new();", "    var record = load();",
                "    public void M<T>() where T : class", "    return new Foo();", "    // a class of its own"};
        boolean typesOk = true;
        for (String t : types) typesOk &= StepCondense.Companion.isTypeHeader(t);
        for (String t : notTypes) typesOk &= !StepCondense.Companion.isTypeHeader(t);
        check("type headers: declarations are, fields, generic constraints and statements are not", typesOk);

        // Every DEF_TREE_* must NAME a real constant. It degrades silently otherwise — the tree would draw
        // correctly and only Reset-to-defaults would misbehave, by selecting an item the combo does not hold.
        boolean namesReal = true;
        try {
            NestLeader.valueOf(AudioExplainerSettings.DEF_TREE_LEADER);
            ChevronStyle.valueOf(AudioExplainerSettings.DEF_TREE_CHEVRON);
            NodePath.valueOf(AudioExplainerSettings.DEF_TREE_PATH);
            NodeDeemphasis.valueOf(AudioExplainerSettings.DEF_TREE_DEEMPHASIS);
            NodeElision.valueOf(AudioExplainerSettings.DEF_TREE_ELISION);
            StepFold.valueOf(AudioExplainerSettings.DEF_TREE_STEP_FOLD);
            StepCondense.valueOf(AudioExplainerSettings.DEF_TREE_CONDENSE);
        } catch (IllegalArgumentException e) {
            namesReal = false;
        }
        check("settings: every tree default names a real enum constant", namesReal);

        // The control: a value no enum knows must fall back rather than throw — and must be distinguishable
        // from a value that was genuinely picked, or the fallback would be hiding the difference.
        AudioExplainerSettings.State odd = new AudioExplainerSettings.State();
        odd.treeLeader = "sideways";
        odd.treeElision = "";
        check("settings: an unknown persisted value degrades to the default",
                TreeStyle.INSTANCE.nest(odd).getLeader() == TreeStyle.INSTANCE.builtinNest().getLeader()
                        && TreeStyle.INSTANCE.node(odd).getElision()
                                == TreeStyle.INSTANCE.builtinNode().getElision());
        AudioExplainerSettings.State picked = new AudioExplainerSettings.State();
        picked.treeLeader = "corners";
        picked.treeMaxHeight = 5;
        // Deliberately the *old* default: a pick that differs from the current one, so a fallback quietly
        // replacing every read would be caught. `CORNERS` also keeps this control honest if the shipped
        // default moves again — it can never coincide with what the fallback would hand back.
        check("settings: a real value is read (lower case too), so the fallback is not swallowing picks",
                TreeStyle.INSTANCE.nest(picked).getLeader() == NestLeader.CORNERS
                        && TreeStyle.INSTANCE.builtinNest().getLeader() != NestLeader.CORNERS);
        check("settings: an out-of-range height is clamped rather than passed through",
                TreeStyle.INSTANCE.node(picked).getMaxHeight() == TreeStyle.MIN_MAX_HEIGHT);

        // ── 12b. The frame's top EDGE and its top CORNERS are two questions ──
        //
        // A frame with no top edge can still have rounded top corners (BRACE), and a gap there is one a relation
        // link can be seen crossing. The pairing is asserted
        // per mode because the failure it guards is a mode *switch* leaving one of the two behind.
        check("leader: only ARROW draws the frame's top edge",
                LeaderArt.INSTANCE.drawsFrameTop(NestLeader.ARROW)
                        && !LeaderArt.INSTANCE.drawsFrameTop(NestLeader.BRACE)
                        && !LeaderArt.INSTANCE.drawsFrameTop(NestLeader.CORNERS));
        // CORNERS is the control, and it is the reason this is not just `!drawsFrameTop`: its diagonals LAND
        // on the frame's top corners, so a radius there would open a gap exactly where one arrives.
        check("leader: only BRACE rounds the frame's top corners without drawing its top edge",
                LeaderArt.INSTANCE.roundsFrameTop(NestLeader.BRACE)
                        && !LeaderArt.INSTANCE.roundsFrameTop(NestLeader.CORNERS)
                        && !LeaderArt.INSTANCE.roundsFrameTop(NestLeader.ARROW));
        // The brace's horizontal run must END on the strip's last row — the row adjacent to the header — and
        // must stop one frame-radius short of either wall, which is where the frame's own corner starts. Read
        // off the path itself rather than off the source, because the failure is a number in the path
        // disagreeing with the intended picture.
        java.awt.geom.Rectangle2D braceBounds =
                LeaderArt.INSTANCE.leaderPath(NestLeader.BRACE, 60, 120, 400, 13).getBounds2D();
        check("leader: the brace's run reaches the strip's last row (13), not a radius above it",
                Math.abs(braceBounds.getMaxY() - 13.0) < 0.51);
        check("leader: the brace stops one frame-radius (ARC) short of either wall",
                Math.abs(braceBounds.getMinX() - LeaderArt.ARC) < 0.51
                        && Math.abs(braceBounds.getMaxX() - (399 - LeaderArt.ARC)) < 0.51);
        // The frame picks the corner back up on its own row 0. Read as the path's FIRST point rather than as
        // its bounds: a square top and a rounded one have identical bounds, and the difference between them
        // is exactly where the outline starts — at the wall, or one radius along the top row.
        check("leader: a rounded top starts one radius along row 0, a square one starts at the wall",
                pathStartsAt(LeaderArt.INSTANCE.framePath(0, 0, 400, 200, false, true), LeaderArt.ARC, 0)
                        && pathStartsAt(
                                LeaderArt.INSTANCE.framePath(0, 0, 400, 200, false, false), 0, 0));
        // And with a top edge nothing changed: `drawTop` still starts its walls a radius down, which is the
        // ARROW case this split had to leave alone.
        check("leader: ARROW's frame top is untouched by the corner split",
                pathStartsAt(LeaderArt.INSTANCE.framePath(0, 0, 400, 200, true, false), 0, LeaderArt.ARC));

        // ── 13. The collapse hint BOTTOM_BORDER's expanded state was missing ──
        //
        // Geometry only, which is the whole point of putting it here: the mark itself has to be looked at, but
        // *where* it lands is arithmetic, and arithmetic that is wrong by a pixel is checkable here. `x1`/`x2`
        // are the token box's edges in the strip's own coordinates.
        java.awt.Rectangle hint = LeaderArt.INSTANCE.collapseHintRect(40, 120, 13);
        check("hint: centred under the invoking token, not under the box's left edge",
                hint != null && hint.x + hint.width / 2 == 80);
        check("hint: inside the strip band, so it cannot be clipped by the frame below",
                hint != null && hint.y >= 0 && hint.y + hint.height <= 13);
        // BRACE descends at x1 and x2 and runs horizontally *outward* from there — the span between the box's
        // corners is empty by construction, which is the room this is drawn in. A hint wider than that would be
        // the one thing in the strip touching the leader.
        check("hint: strictly inside the box's own corners, where every leader shape leaves the strip empty",
                hint != null && hint.x > 40 && hint.x + hint.width < 120);
        check("hint: declines on a token box too narrow to centre a mark in",
                LeaderArt.INSTANCE.collapseHintRect(40, 45, 13) == null);
        // The strip is 14px for CORNERS/BRACE and 22 for ARROW, so both have room; the guard is for a style
        // that does not, and it must decline rather than draw a mark taller than the band it sits in.
        check("hint: declines on a strip too short to clear the leader",
                LeaderArt.INSTANCE.collapseHintRect(40, 120, 4) == null);
        check("hint: both shipped strip heights have room for it",
                LeaderArt.INSTANCE.collapseHintRect(40, 120, LeaderArt.INSTANCE.defaultStripHeight(NestLeader.BRACE) - 1) != null
                        && LeaderArt.INSTANCE.collapseHintRect(40, 120, LeaderArt.INSTANCE.defaultStripHeight(NestLeader.ARROW) - 1) != null);

        // ── 14. `relation.*` — the loader's first word about relations ──
        //
        // Structural only, because that is all this class is: it never opens a file, so a line past the end
        // of the file and a token that is not on its line are still nobody's report. What is here is every
        // way a relation can fail to be DRAWN, which is the half an author cannot see went wrong.
        //
        // Every check is paired with a control where the same shape is correct, because a validator that
        // fires on everything is indistinguishable from one that fires on nothing.
        ResolvedPlan relOk = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(
                        relFrame(0, "A.cs", ctx(10, 40), rel(Relation.KIND_FLOW, at(12), at(30)))),
                Collections.singletonList(step(0, ctx(10, 20)))));
        check("relation: a correct one raises nothing at all",
                lacks(relOk, "relation.endpoint-not-kept") && lacks(relOk, "relation.no-step")
                        && lacks(relOk, "relation.unknown-kind")
                        && lacks(relOk, "relation.endpoint-unnamed")
                        && lacks(relOk, "relation.step-exceeded"));

        // An end on a line the frame elided lands on a `⋯ N lines ⋯` placeholder — which reads as the
        // renderer having dropped the link, the same confusion `frame.call-line-not-kept` exists for.
        ResolvedPlan relUnkept = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(
                        relFrame(0, "A.cs", ctx(10, 40), rel(Relation.KIND_FLOW, at(12), at(90)))),
                Collections.singletonList(step(0, ctx(10, 20)))));
        check("relation: an endpoint on a line the frame does not keep is reported",
                has(relUnkept, "relation.endpoint-not-kept"));
        // **Dropped by the plugin, at info**: as a warn, an agent re-sent the whole plan to fix
        // one arrow. The renderer must not get the relation, and the report must not read as "fix and re-send".
        check("relation: and it is dropped from the frame, reported at info rather than warn",
                relUnkept.frame(0).getFrame().getRelations().isEmpty()
                        && relUnkept.getIssues().stream().filter(i -> i.getCode().equals("relation.endpoint-not-kept"))
                                .allMatch(i -> i.getLevel() == IssueLevel.INFO));
        check("relation: a kept one survives on the resolved frame", relOk.frame(0).getFrame().getRelations().size() == 1);
        // One relation, both ends elided: one issue, because the first end already drops it.
        ResolvedPlan relBothUnkept = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(
                        relFrame(0, "A.cs", ctx(10, 40), rel(Relation.KIND_FLOW, at(80), at(90)))),
                Collections.singletonList(step(0, ctx(10, 20)))));
        check("relation: both ends elided is ONE issue, and no relation.no-step on top of it",
                relBothUnkept.getIssues().stream().filter(i -> i.getCode().startsWith("relation.")).count() == 1);

        // **Two frames over ONE file** — a `.cs` holding two classes, which is the ordinary shape and the one
        // a path-only owner lookup got wrong. Frame 1's window is 20..40, so an endpoint naming that file at
        // line 30 belongs to frame 1; read against frame 0's 1..12 it looks elided, and the tree made the
        // matching mistake in `frameOwning` — it planted a link's far half in frame 0's node, on a row that
        // node does not display, which is a portal with no other end. One predicate now answers both.
        ResolvedPlan relSplit = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(
                        relFrame(0, "A.cs", ctx(1, 12), rel(Relation.KIND_FLOW, at(5), atFile("A.cs", 30))),
                        relFrame(1, "A.cs", ctx(20, 40))),
                Collections.singletonList(step(0, ctx(1, 8)))));
        check("relation: with two frames on one file, an endpoint is read against the frame that SHOWS it",
                lacks(relSplit, "relation.endpoint-not-kept"));
        // The control, and it is what stops the fix from being "never report anything": a line NO window on
        // that file holds is still on a placeholder, and still reported.
        ResolvedPlan relSplitGone = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(
                        relFrame(0, "A.cs", ctx(1, 12), rel(Relation.KIND_FLOW, at(5), atFile("A.cs", 60))),
                        relFrame(1, "A.cs", ctx(20, 40))),
                Collections.singletonList(step(0, ctx(1, 8)))));
        check("relation: and a line no window on that file holds is still reported",
                has(relSplitGone, "relation.endpoint-not-kept"));

        // **Only the endpoint's own scene counts**, because the tree only searches the mounted nodes. Frame 1
        // keeps line 30 but sits in scene 1, so in scene 0 the endpoint lands on frame 0's fold — a portal into
        // a `⋯ N lines ⋯` placeholder, which a whole-plan lookup would call fine.
        ResolvedPlan relOtherScene = PlanLoader.INSTANCE.resolve(plan(
                Arrays.asList(
                        relFrame(0, "A.cs", ctx(1, 12), rel(Relation.KIND_FLOW, at(5), atFile("A.cs", 30))),
                        new Frame(1, "A.cs", "F1", "", 1, null, 0, null, 1,
                                Collections.singletonList(ctx(20, 40)), Collections.emptyList())),
                Arrays.asList(step(0, ctx(1, 8)), step(1, ctx(20, 30)))));
        check("relation: an endpoint kept only by a frame in ANOTHER scene is reported, and the message says so",
                has(relOtherScene, "relation.endpoint-not-kept")
                        && relOtherScene.getIssues().stream().anyMatch(i -> i.getMessage().contains("in scene 1")));

        // The predicate itself, at both edges: `shows` decides which window owns a line, so an off-by-one
        // here moves a link's far half to the wrong node rather than merely mis-wording an issue.
        ResolvedFrame splitSecond = relSplit.frame(1);
        check("frame: shows() is inclusive at both ends of a kept range, and false one line outside either",
                splitSecond != null && splitSecond.shows(20) && splitSecond.shows(40)
                        && !splitSecond.shows(19) && !splitSecond.shows(41));

        // A relation shows only while a step whose FIRST range holds one of its ends is active. Both ends
        // kept and neither in any step's band is a relation that exists and is never seen.
        ResolvedPlan relNoStep = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(
                        relFrame(0, "A.cs", ctx(10, 40), rel(Relation.KIND_FLOW, at(30), at(35)))),
                Collections.singletonList(step(0, ctx(10, 20)))));
        check("relation: one no step will ever draw is reported",
                has(relNoStep, "relation.no-step"));
        check("relation: and its endpoints are not ALSO reported as unkept — one defect, one issue",
                lacks(relNoStep, "relation.endpoint-not-kept"));

        ResolvedPlan relKind = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(
                        relFrame(0, "A.cs", ctx(10, 40), rel("sideways", at(12), at(30)))),
                Collections.singletonList(step(0, ctx(10, 20)))));
        check("relation: an unrecognised kind is reported (it costs the backforth drawing)",
                has(relKind, "relation.unknown-kind"));
        // Case and padding are the decoder's tolerance, not a typo — the renderer matches the same way.
        ResolvedPlan relKindOk = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(
                        relFrame(0, "A.cs", ctx(10, 40), rel("  BackForth ", at(12), at(30)))),
                Collections.singletonList(step(0, ctx(10, 20)))));
        check("relation: a real kind in any case is not reported",
                lacks(relKindOk, "relation.unknown-kind"));

        ResolvedPlan relBlind = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(
                        relFrame(0, "A.cs", ctx(10, 40),
                                rel(Relation.KIND_FLOW, at(12), new Endpoint(null, 0, null, 1, null)))),
                Collections.singletonList(step(0, ctx(10, 20)))));
        check("relation: an endpoint with no line and no symbol is reported",
                has(relBlind, "relation.endpoint-unnamed"));
        // The documented use of a lineless endpoint: an off-screen target you can name but cannot locate.
        ResolvedPlan relNamed = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(
                        relFrame(0, "A.cs", ctx(10, 40),
                                rel(Relation.KIND_FLOW, at(12),
                                        new Endpoint(null, 0, null, 1, "RateTable.Lookup")))),
                Collections.singletonList(step(0, ctx(10, 20)))));
        check("relation: one that names a symbol instead of a line is legitimate",
                lacks(relNamed, "relation.endpoint-unnamed"));

        // Six on one step against five lanes: the sixth is not drawn at all, which is silent today.
        Relation[] six = new Relation[6];
        for (int i = 0; i < six.length; i++) six[i] = rel(Relation.KIND_FLOW, at(12 + i), at(30));
        ResolvedPlan relMany = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(relFrame(0, "A.cs", ctx(10, 40), six)),
                Collections.singletonList(step(0, ctx(10, 20)))));
        check("relation: more on one step than there are lanes is reported",
                has(relMany, "relation.step-exceeded"));
        // Exactly the cap is what the drawing survives, so it must not be what the report fires on.
        Relation[] five = new Relation[5];
        for (int i = 0; i < five.length; i++) five[i] = rel(Relation.KIND_FLOW, at(12 + i), at(30));
        ResolvedPlan relCap = PlanLoader.INSTANCE.resolve(plan(
                Collections.singletonList(relFrame(0, "A.cs", ctx(10, 40), five)),
                Collections.singletonList(step(0, ctx(10, 20)))));
        check("relation: exactly the cap is not reported — the boundary is off by nobody",
                lacks(relCap, "relation.step-exceeded"));


        // ── 16. BEATS — a pulse timed to a phrase in the narration (B1b) ──────────────────
        //
        // The load-time job is narrow and worth stating: PlanLoader cannot know where the SERVER will cut
        // sentences, so it does not try. All it answers is "is this phrase in the narration at all", which
        // is the difference between a beat that lands somewhere and one that can never fire.
        System.out.println();
        System.out.println("-- beats --");

        String tts = "The dispatcher looks up the handler. If it's missing it throws.";

        // A beat is {line, token, occurrence?, say?} — the same anchor an Endpoint and a callLine use.
        ResolvedPlan beatsOk = onePlan(beatStep(0, tts,
                new Beat(12, "TryGetValue", 1, "looks up"),
                new Beat(15, "throw", 1, "if it's missing")));
        check("beats: phrases the narration does say are kept, and nothing is reported",
                lacks(beatsOk, "step.beat-unspoken") && beatsOf(beatsOk).size() == 2);

        ResolvedPlan beatsBad = onePlan(beatStep(0, tts,
                new Beat(12, "TryGetValue", 1, "looks up"),
                new Beat(15, "throw", 1, "consults the registry")));
        check("beats: a phrase the narration never says is reported",
                has(beatsBad, "step.beat-unspoken"));
        // Validate-and-DROP, not validate-and-reject: the step still renders, minus the beat that could
        // not fire. A step lost to one bad phrase would be a worse trade than a step with one less pulse.
        check("beats: ...and dropped, leaving the step and its good beat alone",
                beatsBad.getSteps().size() == 1 && beatsOf(beatsBad).size() == 1
                        && beatsOf(beatsBad).get(0).getSay().equals("looks up"));

        // Matching is normalised, so an author quoting from a wrapped editor still matches.
        check("beats: matching ignores case and collapses whitespace",
                lacks(onePlan(beatStep(0, tts, new Beat(12, "TryGetValue", 1, "Looks   Up"))),
                        "step.beat-unspoken"));
        // The control: normalisation must not be so loose that anything matches.
        check("beats: CONTROL — collapsing whitespace does not mean deleting it",
                has(onePlan(beatStep(0, tts, new Beat(12, "TryGetValue", 1, "looksup"))),
                        "step.beat-unspoken"));

        // A beat with nothing to pulse is not a beat.
        ResolvedPlan noTok = onePlan(beatStep(0, tts,
                new Beat(12, "  ", 1, "looks up"),
                new Beat(15, "throw", 1, "if it's missing")));
        check("beats: a beat naming no token is reported and dropped",
                has(noTok, "step.beat-no-token") && beatsOf(noTok).size() == 1);

        // ── say is what makes a beat TIMED; without it the beat is the step's start pulse ──
        ResolvedPlan atStart = onePlan(beatStep(0, tts, new Beat(12, "TryGetValue", 1, "")));
        check("beats: a beat with no `say` is legal — that is the one-pulse-at-audio-start shape",
                lacks(atStart, "step.beat-unspoken") && beatsOf(atStart).size() == 1
                        && beatsOf(atStart).get(0).getAtStart());
        check("beats: CONTROL — a beat WITH a say is not an at-start beat",
                !beatsOf(beatsOk).get(0).getAtStart());

        // Two of them would fire on the same instant and cancel each other (startTokenFlash self-cancels).
        ResolvedPlan twoStart = onePlan(beatStep(0, tts,
                new Beat(12, "TryGetValue", 1, ""),
                new Beat(15, "throw", 1, "")));
        check("beats: two beats with no `say` are reported, and only the first survives",
                has(twoStart, "step.beat-many-at-start") && beatsOf(twoStart).size() == 1
                        && beatsOf(twoStart).get(0).getToken().equals("TryGetValue"));

        // ── the check a token alone could never get: is the line one the step actually lights? ──
        // This is the MaxLines/DispatchResult family, and it is pure arithmetic — no file, no VFS — so it
        // reaches the agent in the set_plan reply instead of dying in a FINE log line.
        ResolvedPlan beatOutside = onePlan(beatStep(0, tts, new Beat(31, "TryGetValue", 1, "looks up")));
        check("beats: a pulse on a line the step does not light is reported",
                has(beatOutside, "step.beat-line-outside-ranges"));
        // Reported, KEPT: the author named that line, and honouring it beats guessing. The pulse works.
        check("beats: ...and kept, because a named line is an instruction, not a typo to discard",
                beatsOf(beatOutside).size() == 1 && beatsOf(beatOutside).get(0).getLine() == 31);
        check("beats: CONTROL — a line inside the step's own lines is silent",
                lacks(onePlan(beatStep(0, tts, new Beat(20, "TryGetValue", 1, "looks up"))),
                        "step.beat-line-outside-ranges"));
        check("beats: CONTROL — a legacy beat has no line, so the check cannot fire on it",
                lacks(onePlan(legacyStep(0, "TryGetValue", 1)), "step.beat-line-outside-ranges"));

        // ── the legacy shape: a bare token becomes exactly one beat ──
        ResolvedPlan preBeats = onePlan(legacyStep(0, "rate", 3));
        check("beats: a pre-beats step's `token` is read as one beat",
                beatsOf(preBeats).size() == 1 && beatsOf(preBeats).get(0).getToken().equals("rate"));
        check("beats: ...carrying its occurrence, and firing at audio start",
                beatsOf(preBeats).get(0).getOccurrence() == 3 && beatsOf(preBeats).get(0).getAtStart());
        // line 0 is the flag the renderer reads as "search the step's whole span, not one line" — which is
        // what stops a legacy token in a step's SECOND range resolving to nothing.
        check("beats: ...with no line, which is what selects the whole-span fallback",
                beatsOf(preBeats).get(0).getLine() == 0);
        check("beats: a blank legacy token synthesises nothing rather than an empty beat",
                beatsOf(onePlan(legacyStep(0, "   ", 1))).isEmpty());
        check("beats: CONTROL — a step with neither token nor beats draws none, silently",
                beatsOf(onePlan(step(0, ctx(10, 20)))).isEmpty()
                        && lacks(onePlan(step(0, ctx(10, 20))), "step.beat-no-token"));
        // Explicit beats win: a plan carrying both is not read twice.
        Step both = new Step(0, Collections.singletonList(ctx(10, 20)), Collections.emptyList(),
                tts, null, null, null, "legacyTok", 1,
                Collections.singletonList(new Beat(12, "TryGetValue", 1, "looks up")));
        check("beats: when a step has both, `beats` wins and the legacy token is ignored",
                beatsOf(onePlan(both)).size() == 1
                        && beatsOf(onePlan(both)).get(0).getToken().equals("TryGetValue"));

        check("beats: a blank phrase is not a wildcard",
                !new Beat(1, "t", 1, "").spokenIn(tts) && !new Beat(1, "t", 1, "   ").spokenIn(tts));

        // The cross-language table. sidebar.js's beatKey() is asserted against the same file by
        // ClockProbe.js — a divergence fails one of the two, which is the whole point of sharing it.
        int normCases = 0;
        for (String line : java.nio.file.Files.readAllLines(
                java.nio.file.Paths.get("probes", "beat-normalize.tsv"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            int tab = line.indexOf('\t');
            if (tab < 0) { check("beats: malformed row in beat-normalize.tsv: " + line, false); continue; }
            String in = unescape(line.substring(0, tab));
            String want = unescape(line.substring(tab + 1));
            String got = Beat.Companion.normalize(in);
            if (!got.equals(want)) {
                check("beats: normalize(\"" + in + "\") = \"" + got + "\", table says \"" + want + "\"", false);
            }
            normCases++;
        }
        check("beats: Beat.normalize matches the shared table (" + normCases + " rows)", normCases >= 6);
        check("beats: whitespace-only normalises to empty, which is what makes a blank phrase inert",
                Beat.Companion.normalize(" \t\n ").isEmpty());

        System.out.println();
        System.out.println(pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
