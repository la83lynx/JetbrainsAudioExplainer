import com.audioexplainer.AudioExplainerSettings;
import com.audioexplainer.CheckOs;
import com.audioexplainer.DiagnosticCheck;
import com.audioexplainer.DiagnosticContext;
import com.audioexplainer.DiagnosticResult;
import com.audioexplainer.ExplainerDiagnostics;
import com.audioexplainer.ExplainProgress;
import com.audioexplainer.RunShape;
import com.audioexplainer.ExplainPrompt;
import com.audioexplainer.ExplainRequest;
import com.audioexplainer.ExplainScope;
import com.audioexplainer.Language;
import com.audioexplainer.Languages;
import com.audioexplainer.McpEndpoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two generation knobs and the per-run free text (`Effort.md`), asserted on the exact strings the
 * plugin hands `claude`.
 *
 * It calls the REAL {@link ExplainPrompt}, which is why that object exists separately from
 * `ClaudeExplainer`: everything in it is a pure function of a request, a skill dir, a language and the
 * three knobs, so all 3 x 6 x 4 combinations build in milliseconds with no IDE, no VFS and no network.
 * The parts that need an application — the settings lookup and the SAPI warm-up — stay in
 * `ClaudeExplainer.explain` and are NOT reachable here; what is asserted is the string building.
 *
 * <h2>The golden file</h2>
 *
 * {@code probes/prompt-standard.txt} is the prompt as it was before depth existed, frozen. It was
 * written by {@code java EffortProbe --freeze} against the commit that moved `buildPrompt` out of
 * `ClaudeExplainer` unchanged (a pure motion — the literals were not touched), and the assertion below
 * compares every Standard-depth prompt against it byte for byte.
 *
 * That is the assertion that matters. The prompt is the part an upgrade could silently change for
 * everyone, so it has to be provably identical for a user who never opens the new controls. The argv
 * deliberately is NOT identical: it gains `--effort medium`, which is the fix.
 *
 * Every "X is absent" assertion here sits beside a case where X is present — Brief's missing asks are
 * measured against Standard's and Deep's, so a green tick cannot come from an instrument that is blind.
 */
public final class EffortProbe {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (ok) pass++; else fail++;
    }

    static final Path GOLDEN = Paths.get("probes", "prompt-standard.txt");

    /** A fixed, OS-independent skill dir: `buildPrompt` normalises separators, so the golden is stable. */
    static final Path SKILL = Paths.get("/home/u/.claude/skills/audio-explainer");

    /**
     * A fixed plan dir, for the same reason (`Saving.md` B): the real one is a fresh temp directory per run,
     * and a golden holding it would differ on every machine and every run.
     */
    static final Path PLAN_DIR = Paths.get("/tmp/audio-explainer/plans/run-0");

    // ── the case matrix the golden freezes ────────────────────────────────────
    // Scope x language x skill-installed, because each of the three moves a different part of the prompt:
    // scope picks the intro/step-1/context block, a non-English language adds the LANGUAGE paragraph, and a
    // missing skill dir swaps the whole schema section for the inline fallback mirror.

    static final class Case {
        final String name;
        final ExplainRequest req;
        final Path skill;
        final Language lang;

        Case(String name, ExplainRequest req, Path skill, String langCode) {
            this.name = name;
            this.req = req;
            this.skill = skill;
            this.lang = Languages.INSTANCE.byCode(langCode);
        }
    }

    static ExplainRequest request(ExplainScope scope, boolean selection, String effort, String depth, String extra) {
        return new ExplainRequest(
                "C:/work/proj",
                "C:/work/proj/src/Services/BuildService.cs",
                "src/Services/BuildService.cs",
                142,
                selection ? "var x = service.Build();" : null,
                selection ? Integer.valueOf(140) : null,
                selection ? Integer.valueOf(144) : null,
                scope,
                scope == ExplainScope.DIRECTORY ? Arrays.asList("src/UI", "src/Services/Book.cs") : List.of(),
                effort, depth, extra);
    }

    /** Every case the golden holds, at Standard depth with no extra instructions. */
    static List<Case> goldenCases() {
        String std = AudioExplainerSettings.DEPTH_STANDARD;
        List<Case> cs = new ArrayList<>();
        for (ExplainScope scope : ExplainScope.values()) {
            String s = scope.name().toLowerCase();
            for (String lang : new String[] { "en", "de" }) {
                cs.add(new Case(s + "-" + lang + "-skill", request(scope, false, null, std, null), SKILL, lang));
                cs.add(new Case(s + "-" + lang + "-noskill", request(scope, false, null, std, null), null, lang));
            }
        }
        cs.add(new Case("cursor-selection-en-skill", request(ExplainScope.CURSOR, true, null, std, null), SKILL, "en"));
        cs.add(new Case("cursor-selection-en-noskill", request(ExplainScope.CURSOR, true, null, std, null), null, "en"));
        return cs;
    }

    static String prompt(Case c) {
        return ExplainPrompt.INSTANCE.buildPrompt(c.req, c.skill, c.lang, c.req.getDepth(), c.req.getExtraInstructions(), PLAN_DIR);
    }

    static String prompt(ExplainScope scope, Path skill, String depth, String extra) {
        ExplainRequest r = request(scope, false, null, depth, extra);
        return ExplainPrompt.INSTANCE.buildPrompt(r, skill, Languages.INSTANCE.byCode("en"), depth, extra, PLAN_DIR);
    }

    // ── the golden file ───────────────────────────────────────────────────────

    static final String BEGIN = "=== case: ";
    static final String END = "=== end ===";

    static void freeze() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# probes/prompt-standard.txt — the Standard-depth prompt, frozen.\n");
        sb.append("# Written by `java EffortProbe --freeze`; asserted byte for byte by `java EffortProbe`.\n");
        sb.append("# Regenerate this ONLY when the prompt is meant to change for every existing user.\n");
        for (Case c : goldenCases()) {
            sb.append(BEGIN).append(c.name).append(" ===\n");
            sb.append(prompt(c));
            sb.append(END).append('\n');
        }
        Files.createDirectories(GOLDEN.getParent());
        Files.write(GOLDEN, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("froze " + goldenCases().size() + " prompts into " + GOLDEN);
    }

    static Map<String, String> readGolden() throws IOException {
        String text = new String(Files.readAllBytes(GOLDEN), StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        int i = text.indexOf(BEGIN);
        while (i >= 0) {
            int nameEnd = text.indexOf(" ===\n", i + BEGIN.length());
            String name = text.substring(i + BEGIN.length(), nameEnd);
            int bodyStart = nameEnd + " ===\n".length();
            int bodyEnd = text.indexOf(END + "\n", bodyStart);
            out.put(name, text.substring(bodyStart, bodyEnd));
            i = text.indexOf(BEGIN, bodyEnd);
        }
        return out;
    }

    // ── the asks depth switches off ───────────────────────────────────────────
    // Distinctive substrings of the *request* for each optional feature, not of the word itself: Brief's
    // DEPTH line names `relations` in order to forbid it, so asserting on the bare word would fail on a
    // prompt that is correct. These are the clauses that ask for one.

    static final String[] ASKS = {
            "add an optional frame-level `relations` entry",
            "give each nested frame `parent`",
            "group the frames into `scene`s",
            "add an optional one-sentence `note`",
            "add an optional `beats` entry",
            "ONE-LINE `example`",
    };

    /** The fallback mirror's own spellings of the same asks (no skill dir on disk). */
    static final String[] FALLBACK_ASKS = {
            "Frame `relations` is optional",
            "Step `note` is optional",
            "Step `beats` is optional",
            "Step `example` is optional",
            "\"scene\":0",
    };

    static boolean none(String prompt, String[] asks) {
        for (String a : asks) if (prompt.contains(a)) return false;
        return true;
    }

    static boolean all(String prompt, String[] asks) {
        for (String a : asks) if (!prompt.contains(a)) return false;
        return true;
    }

    static int countOf(String haystack, String needle) {
        int n = 0, i = haystack.indexOf(needle);
        while (i >= 0) { n++; i = haystack.indexOf(needle, i + needle.length()); }
        return n;
    }

    static kotlinx.serialization.json.JsonObject ev(String json) {
        return kotlinx.serialization.json.JsonElementKt.getJsonObject(
                kotlinx.serialization.json.Json.Default.parseToJsonElement(json));
    }

    /** One JSON string literal's worth of escaping. */
    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String delta(String part) {
        return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"" + esc(part) + "\"}}}";
    }

    static String toolUse(String name, String inputJson) {
        return "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"" + name
                + "\",\"input\":" + inputJson + "}]}}";
    }

    static String blockStart(int index, String name) {
        return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"index\":" + index
                + ",\"content_block\":{\"type\":\"tool_use\",\"name\":\"" + name + "\",\"input\":{}}}}";
    }

    static final String TOOL_RESULT = "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\"}]}}";
    static final String MESSAGE_START = "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\"}}";

    static String thinkingTokens(int n) {
        return "{\"type\":\"system\",\"subtype\":\"thinking_tokens\",\"estimated_tokens_delta\":" + n + "}";
    }

    static final String THINKING_START = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\","
            + "\"index\":0,\"content_block\":{\"type\":\"thinking\"}}}";

    static String pct(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", v * 100);
    }

    /**
     * **The run's progress bar** (`ExplainProgress`), fed a synthetic run on a hand-stepped clock that goes
     * exactly as its `RunShape` expects — so an even bar is the right answer at every sample. The plan `Write`
     * arrives in 3-character deltas, so **no delta holds a whole key** — asserted, as the control, because that is
     * what bench3 recorded (1 key of 13 whole) and what a per-delta count gets wrong.
     */
    static void progress() {
        List<String> argv = ExplainPrompt.INSTANCE.buildArgs("P", "medium", "", PLAN_DIR);
        check("progress: the argv asks for partial messages, once, before the variadic --add-dir",
                argv.indexOf("--include-partial-messages") >= 0
                        && argv.indexOf("--include-partial-messages") == argv.lastIndexOf("--include-partial-messages")
                        && argv.indexOf("--include-partial-messages") < argv.indexOf("--add-dir"));

        // ── the expectation: seeded per effort and depth, learned from runs ──
        RunShape xhighDeep = RunShape.Companion.seed("xhigh", "deep");
        RunShape mediumStd = RunShape.Companion.seed("medium", "standard");
        RunShape mediumBrief = RunShape.Companion.seed("medium", "brief");
        check("progress: the seeds order by effort and depth — xhigh/deep thinks longest, medium/brief least",
                xhighDeep.getPreSeconds() > 4 * mediumStd.getPreSeconds()
                        && mediumStd.getPreSeconds() > mediumBrief.getPreSeconds()
                        && xhighDeep.getWriteChars() > mediumStd.getWriteChars()
                        && mediumStd.getWriteChars() > mediumBrief.getWriteChars());
        RunShape learnt = mediumStd.learn(new RunShape(52, 6000, 300, 1));
        check("progress: a seed learns 3/4 of the way to its first run",
                Math.abs(learnt.getPreSeconds() - (32 + 0.75 * 20)) < 1e-9 && learnt.getRuns() == 1);
        RunShape again = learnt.learn(new RunShape(27, 6000, 300, 1));
        check("  and 40 % toward each later one",
                Math.abs(again.getPreSeconds() - (learnt.getPreSeconds() + 0.4 * (27 - learnt.getPreSeconds()))) < 1e-9
                        && again.getRuns() == 2);
        RunShape stored = RunShape.Companion.parse(learnt.encode());
        check("progress: a shape survives its stored form",
                stored != null && Math.abs(stored.getPreSeconds() - learnt.getPreSeconds()) < 0.06
                        && stored.getRuns() == learnt.getRuns());
        check("  control: a damaged stored form is no shape, so the seed is used",
                RunShape.Companion.parse("pre=abc;chars=1") == null
                        && RunShape.Companion.parse("pre=-1;chars=5;rate=5") == null);

        // The third step's prose says "keep" and "ttsText" — unescaped quotes there would count; escaped ones don't.
        String plan = "{\"frames\":[{\"keep\":[]},{\"keep\":[]}],\"steps\":[{\"ttsText\":\"a\"},{\"ttsText\":\"b\"},"
                + "{\"ttsText\":\"say \\\"keep\\\" and ttsText\"}]}";
        String input = "{\"file_path\": \"p.json\", \"content\": \"" + esc(plan) + "\"}";

        // 30 s to the Write, then the input over 10 s, then the tail: an even bar is t / (40 + TAIL).
        double total = 40 + ExplainProgress.TAIL;
        double[] clock = {0};
        ExplainProgress p = new ExplainProgress(new RunShape(30, input.length(), input.length() / 10.0, 1), () -> clock[0]);
        List<Double> seen = new ArrayList<>();
        java.util.function.Consumer<String> feed = j -> { p.onEvent(ev(j)); seen.add(p.fraction()); };
        java.util.function.DoubleUnaryOperator at = t -> { clock[0] = t; double f = p.fraction(); seen.add(f); return f; };

        feed.accept("{\"type\":\"system\",\"subtype\":\"init\"}");
        check("progress: it starts by saying Claude is starting", "Starting Claude…".equals(p.activity()));
        double worst = 0;
        for (double t = 1; t <= 4; t++) worst = Math.max(worst, Math.abs(at.applyAsDouble(t) - t / total));
        feed.accept(MESSAGE_START);
        feed.accept(THINKING_START);
        check("progress: a thinking block says so", "Thinking it through…".equals(p.activity()));
        feed.accept(toolUse("Read", "{\"file_path\":\"" + esc("C:\\Users\\me\\.claude\\skills\\audio-explainer\\docs\\plan-schema.md") + "\"}"));
        check("progress: reading the skill's own docs is reading the instructions, backslashes or not",
                "Reading the explainer's instructions…".equals(p.activity()));
        feed.accept(TOOL_RESULT);
        feed.accept(toolUse("Read", "{\"file_path\":\"C:/src/Tracker.cs\"}"));
        check("progress: a Read of the code names its file", "Reading Tracker.cs…".equals(p.activity()));
        feed.accept(toolUse("Grep", "{\"pattern\":\"Tracker\"}"));
        check("progress: a search says so", "Searching the code…".equals(p.activity()));
        feed.accept(TOOL_RESULT);
        check("progress: a tool's result hands back to thinking", "Thinking it through…".equals(p.activity()));
        feed.accept(thinkingTokens(150));
        check("progress: a thinking heartbeat puts its running count in the label — the one live sign of a long think",
                "Thinking it through — 150 tokens…".equals(p.activity()));
        feed.accept(thinkingTokens(1050));
        check("  in thousands past a thousand", "Thinking it through — 1.2k tokens…".equals(p.activity()));
        for (double t = 5; t < 30; t++) worst = Math.max(worst, Math.abs(at.applyAsDouble(t) - t / total));
        clock[0] = 30;
        double beforeWrite = p.fraction();
        feed.accept(MESSAGE_START);
        feed.accept(blockStart(1, "Write"));
        double afterWrite = p.fraction();
        check("progress: the Write starting is not a jump — " + pct(beforeWrite) + " before, " + pct(afterWrite) + " after",
                Math.abs(afterWrite - beforeWrite) < 1e-9);
        check("  and stands where an even bar would, " + pct(30 / total), Math.abs(afterWrite - 30 / total) < 0.01);
        int whole = 0;
        boolean sawFrame2 = false;
        int parts = (input.length() + 2) / 3;
        for (int i = 0, k = 0; i < input.length(); i += 3, k++) {
            String part = input.substring(i, Math.min(input.length(), i + 3));
            if (part.contains("\\\"keep\\\"") || part.contains("\\\"ttsText\\\"")) whole++;
            clock[0] = 30 + 10.0 * (k + 1) / parts;
            feed.accept(delta(part));
            if (clock[0] > 33) worst = Math.max(worst, Math.abs(seen.get(seen.size() - 1) - clock[0] / total));
            sawFrame2 |= p.activity().endsWith("frame 2…");
        }
        check("  control: no delta held a whole key", whole == 0);
        check("progress: frames counted as they stream, then steps — 2 frames, 3 steps, the prose ignored",
                sawFrame2 && "Writing the walkthrough — step 3…".equals(p.activity()));
        feed.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_stop\",\"index\":1}}");
        check("progress: the Write's end is checking, near the top — " + pct(p.fraction()),
                p.getPhase() == ExplainProgress.Phase.CHECKING && "Checking the walkthrough…".equals(p.activity())
                        && Math.abs(p.fraction() - 40 / total) < 0.02);
        check("progress: a run that goes as expected fills evenly — worst " + pct(worst) + " off an even bar",
                worst < 0.03);
        feed.accept(toolUse("PowerShell", "{\"command\":\"powershell -File explainer.ps1 plan p.json\"}"));
        check("progress: the POST is sending", "Sending the walkthrough…".equals(p.activity()));
        feed.accept(TOOL_RESULT);
        check("  and its reply is finishing", "Finishing…".equals(p.activity()));
        double beforeFix = p.fraction();
        feed.accept(MESSAGE_START);
        feed.accept(blockStart(0, "Write"));
        check("progress: a second Write (a fix) relabels and does not refill",
                "Fixing the walkthrough…".equals(p.activity()) && p.getPhase() == ExplainProgress.Phase.CHECKING
                        && p.fraction() >= beforeFix);
        clock[0] = 600;
        check("  and time alone never finishes the bar", p.fraction() < 1.0);
        RunShape observed = p.observed();
        check("progress: the run's own shape is what it did — Write at 30 s, " + input.length() + " chars in 10 s",
                observed != null && Math.abs(observed.getPreSeconds() - 30) < 1e-9
                        && observed.getWriteChars() == input.length()
                        && Math.abs(observed.getWriteRate() - input.length() / 10.0) < 1e-6);
        feed.accept("{\"type\":\"result\",\"subtype\":\"success\"}");
        check("progress: the result line is 100 %", p.fraction() == 1.0);
        check("progress: the report says when the Write came against the expectation",
                p.report().startsWith("plan Write at 30.0s (expected 30.0s)"));
        boolean monotone = true;
        for (int i = 1; i < seen.size(); i++) monotone &= seen.get(i) >= seen.get(i - 1);
        check("progress: never decreased over " + seen.size() + " samples", monotone);

        // ── a think that runs long: slows, never pins ──
        double[] c2 = {0};
        ExplainProgress slow = new ExplainProgress(new RunShape(30, 3000, 300, 1), () -> c2[0]);
        double[] f = new double[5];
        double[] ts = {30, 60, 120, 240, 480};
        for (int i = 0; i < ts.length; i++) { c2[0] = ts[i]; f[i] = slow.fraction(); }
        boolean creeping = true;
        for (int i = 1; i < f.length; i++) creeping &= f[i] > f[i - 1] + 0.003;
        check("progress: a think 16x past its expectation still moves the bar at every sample ("
                + pct(f[0]) + ", " + pct(f[1]) + ", " + pct(f[2]) + ", " + pct(f[3]) + ", " + pct(f[4]) + ")",
                creeping && f[4] < 0.9);

        // ── a stream without partial messages: the Write arrives whole ──
        double[] c3 = {0};
        ExplainProgress whole3 = new ExplainProgress(mediumStd, () -> c3[0]);
        c3[0] = 20;
        whole3.onEvent(ev(toolUse("Write", "{\"file_path\":\"p.json\",\"content\":\"{}\"}")));
        check("progress: without partial messages a whole Write still moves it on, and teaches nothing",
                whole3.getPhase() == ExplainProgress.Phase.CHECKING && whole3.observed() == null);
    }

    /**
     * **The `set_plan` tool's run** (`Speed.md` #3): the argv grants the tool and nothing that writes or runs, the
     * prompt names the tool and never the POST, and the bar reads the tool's plain-JSON input as it read the
     * `Write`'s escaped one. The POST run is every control — it is also the fallback, so it must not have moved.
     */
    static void toolRoute() {
        Path config = Paths.get("/tmp/audio-explainer/plans/run-0/mcp.json");
        List<String> tool = ExplainPrompt.INSTANCE.buildArgs("P", "medium", "", PLAN_DIR, config);
        List<String> post = ExplainPrompt.INSTANCE.buildArgs("P", "medium", "", PLAN_DIR);
        String allowed = tool.get(tool.indexOf("--allowedTools") + 1);
        check("tool: the allow-list is the tool's — `" + allowed + "`",
                allowed.equals(ExplainPrompt.TOOL_ALLOWED_TOOLS) && allowed.contains(McpEndpoint.QUALIFIED_TOOL));
        check("  with nothing that writes or runs: no Write, no Bash, no PowerShell",
                java.util.Collections.disjoint(Arrays.asList(allowed.split(" ")), List.of("Write", "Bash", "PowerShell")));
        check("  control: the POST run's allow-list has all three, and not the tool",
                Arrays.asList(post.get(post.indexOf("--allowedTools") + 1).split(" ")).containsAll(List.of("Write", "Bash", "PowerShell"))
                        && !post.get(post.indexOf("--allowedTools") + 1).contains("mcp__"));
        int at = tool.indexOf("--mcp-config");
        check("tool: --mcp-config names the config file once, and a flag follows it (it takes a list)",
                at >= 0 && at == tool.lastIndexOf("--mcp-config") && tool.get(at + 1).equals(config.toString())
                        && tool.get(at + 2).startsWith("--"));
        check("  and --strict-mcp-config still keeps the user's servers out", tool.contains("--strict-mcp-config"));
        check("tool: the plan directory is not granted — the agent writes nothing", !tool.contains("--add-dir"));
        check("  control: the POST run grants it, and names no config", post.contains("--add-dir") && !post.contains("--mcp-config"));

        String cfg = McpEndpoint.INSTANCE.config("http://127.0.0.1:1234/mcp/abc", "t0k");
        kotlinx.serialization.json.JsonObject server = kotlinx.serialization.json.JsonElementKt.getJsonObject(
                ev(cfg).get("mcpServers")).get(McpEndpoint.SERVER_NAME) instanceof kotlinx.serialization.json.JsonObject o ? o : null;
        check("tool: the config is one http server, under the name the tool's qualified name carries",
                server != null && server.toString().contains("\"type\":\"http\"")
                        && server.toString().contains("\"url\":\"http://127.0.0.1:1234/mcp/abc\"")
                        && server.toString().contains("\"Authorization\":\"Bearer t0k\"")
                        && McpEndpoint.QUALIFIED_TOOL.equals("mcp__" + McpEndpoint.SERVER_NAME + "__set_plan"));

        String planPath = PLAN_DIR.toString().replace('\\', '/');
        int named = 0, clean = 0, posts = 0;
        List<Case> cases = goldenCases();
        for (Case c : cases) {
            String viaTool = ExplainPrompt.INSTANCE.buildPrompt(c.req, c.skill, c.lang, c.req.getDepth(),
                    c.req.getExtraInstructions(), PLAN_DIR, true);
            if (viaTool.split(java.util.regex.Pattern.quote(McpEndpoint.QUALIFIED_TOOL), -1).length == 2) named++;
            if (!viaTool.contains("POST") && !viaTool.contains("explainer.ps1") && !viaTool.contains(planPath)) clean++;
            if (prompt(c).contains("POST")) posts++;
        }
        check("tool: every golden case's tool prompt names the tool once (" + named + "/" + cases.size() + ")", named == cases.size());
        check("  and never the POST, the helper scripts or the plan directory (" + clean + "/" + cases.size() + ")", clean == cases.size());
        check("  control: every POST prompt does say POST (" + posts + "/" + cases.size() + ")", posts == cases.size());
        String extra = ExplainPrompt.INSTANCE.buildPrompt(request(ExplainScope.CURSOR, false, null, AudioExplainerSettings.DEPTH_STANDARD,
                "Use simple terms."), SKILL, Languages.INSTANCE.byCode("en"), AudioExplainerSettings.DEPTH_STANDARD,
                "Use simple terms.", PLAN_DIR, true);
        check("tool: the free text's fence holds the tool call fixed, not the POST",
                extra.contains("the line-range rules and the `set_plan` call are fixed") && extra.endsWith("Use simple terms.\n"));

        // ── the bar, on the tool's input: plain JSON, so its keys arrive unescaped ──
        String plan = "{\"title\":\"t\",\"frames\":[{\"keep\":[]},{\"keep\":[]}],\"steps\":[{\"ttsText\":\"a\"},"
                + "{\"ttsText\":\"b\"},{\"ttsText\":\"say \\\"keep\\\" and \\\"ttsText\\\"\"}]}";
        double[] clock = {0};
        ExplainProgress p = new ExplainProgress(new RunShape(30, plan.length(), plan.length() / 10.0, 1), () -> clock[0]);
        p.onEvent(ev(MESSAGE_START));
        clock[0] = 30;
        p.onEvent(ev(blockStart(1, McpEndpoint.QUALIFIED_TOOL)));
        check("tool: the tool's call starting is the plan being written",
                p.getPhase() == ExplainProgress.Phase.WRITING && "Writing the walkthrough…".equals(p.activity()));
        boolean sawFrame2 = false;
        int parts = (plan.length() + 2) / 3;
        for (int i = 0, k = 0; i < plan.length(); i += 3, k++) {
            clock[0] = 30 + 10.0 * (k + 1) / parts;
            p.onEvent(ev(delta(plan.substring(i, Math.min(plan.length(), i + 3)))));
            sawFrame2 |= p.activity().endsWith("frame 2…");
        }
        check("tool: 2 frames, then 3 steps — the prose's quoted \"keep\" and \"ttsText\" ignored",
                sawFrame2 && "Writing the walkthrough — step 3…".equals(p.activity()));
        p.onEvent(ev("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_stop\",\"index\":1}}"));
        p.onEvent(ev(toolUse(McpEndpoint.QUALIFIED_TOOL, plan)));
        check("tool: its end is checking", p.getPhase() == ExplainProgress.Phase.CHECKING && "Checking the walkthrough…".equals(p.activity()));
        p.onEvent(ev(TOOL_RESULT));
        check("  and its result is finishing", "Finishing…".equals(p.activity()));
        RunShape observed = p.observed();
        check("tool: the run's shape is the tool's input — " + plan.length() + " chars in 10 s",
                observed != null && observed.getWriteChars() == plan.length() && Math.abs(observed.getPreSeconds() - 30) < 1e-9);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--freeze")) { freeze(); return; }

        String BRIEF = AudioExplainerSettings.DEPTH_BRIEF;
        String STD = AudioExplainerSettings.DEPTH_STANDARD;
        String DEEP = AudioExplainerSettings.DEPTH_DEEP;

        // ── (a) Standard is today's prompt, byte for byte ─────────────────────
        // The one that matters. A user who never opens the new group must be asked for exactly what they
        // were asked for before depth existed — for every scope, both languages, with and without the skill.
        Map<String, String> golden = readGolden();
        List<Case> cases = goldenCases();
        check("the golden file holds one section per case (" + cases.size() + ")",
                golden.size() == cases.size());
        int identical = 0;
        for (Case c : cases) {
            String want = golden.get(c.name);
            String got = prompt(c);
            if (want != null && want.equals(got)) identical++;
            else check("  " + c.name + " differs from the frozen prompt", false);
        }
        check("Standard depth, no extra instructions: " + identical + "/" + cases.size()
                + " prompts byte-identical to the pre-depth prompt", identical == cases.size());
        // The control: the instrument can tell prompts apart at all.
        check("  control: a Brief prompt is NOT byte-identical to the frozen one",
                !golden.get("cursor-en-skill").equals(prompt(ExplainScope.CURSOR, SKILL, BRIEF, null)));

        // ── (b) the flags ─────────────────────────────────────────────────────
        List<String> bare = ExplainPrompt.INSTANCE.buildArgs("P", "", "");
        check("blank effort and blank model add no flags at all",
                !bare.contains("--effort") && !bare.contains("--model"));
        check("  control: the always-present flags are still there",
                bare.contains("-p") && bare.contains("--output-format") && bare.contains("--allowedTools"));
        check("every run loads no MCP server: one `--strict-mcp-config`, and no `--mcp-config` to load one from",
                bare.indexOf("--strict-mcp-config") >= 0
                        && bare.indexOf("--strict-mcp-config") == bare.lastIndexOf("--strict-mcp-config")
                        && !bare.contains("--mcp-config"));
        int effortOk = 0;
        for (String level : AudioExplainerSettings.Companion.getEFFORT_LEVELS()) {
            List<String> a = ExplainPrompt.INSTANCE.buildArgs("P", level, "");
            int at = a.indexOf("--effort");
            if (at >= 0 && a.lastIndexOf("--effort") == at && level.equals(a.get(at + 1))
                    && !a.contains("--model") && a.size() == bare.size() + 2) effortOk++;
        }
        check("each of the 5 effort levels adds exactly one `--effort <level>` pair and nothing else",
                effortOk == AudioExplainerSettings.Companion.getEFFORT_LEVELS().size());
        int modelOk = 0;
        for (String alias : AudioExplainerSettings.Companion.getMODEL_ALIASES()) {
            List<String> a = ExplainPrompt.INSTANCE.buildArgs("P", "", alias);
            int at = a.indexOf("--model");
            if (at >= 0 && a.lastIndexOf("--model") == at && alias.equals(a.get(at + 1))
                    && !a.contains("--effort") && a.size() == bare.size() + 2) modelOk++;
        }
        check("each model alias adds exactly one `--model <alias>` pair and nothing else",
                modelOk == AudioExplainerSettings.Companion.getMODEL_ALIASES().size());
        List<String> both = ExplainPrompt.INSTANCE.buildArgs("P", "max", "opus");
        check("both together add both pairs, in that order",
                both.size() == bare.size() + 4
                        && both.indexOf("--effort") < both.indexOf("--model")
                        && "max".equals(both.get(both.indexOf("--effort") + 1))
                        && "opus".equals(both.get(both.indexOf("--model") + 1)));
        check("  whitespace-only effort is blank, not a flag with an empty value",
                !ExplainPrompt.INSTANCE.buildArgs("P", "   ", "").contains("--effort"));
        check("the prompt itself is what `-p` carries, unchanged",
                "P".equals(both.get(both.indexOf("-p") + 1)));
        check("the shipped default is not blank — a fresh install passes `--effort medium`",
                AudioExplainerSettings.EFFORT_MEDIUM.equals(new AudioExplainerSettings.State().explainEffort));
        check("  control: the model default IS blank (inherit is right there)",
                "".equals(new AudioExplainerSettings.State().explainModel));

        // ── (c) what each depth asks for ──────────────────────────────────────
        String brief = prompt(ExplainScope.CURSOR, SKILL, BRIEF, null);
        String std = prompt(ExplainScope.CURSOR, SKILL, STD, null);
        String deep = prompt(ExplainScope.CURSOR, SKILL, DEEP, null);
        check("Brief asks for no relations, no nesting, no scenes, no note/example/beats",
                none(brief, ASKS));
        check("  control: Standard asks for all six", all(std, ASKS));
        check("  control: Deep asks for all six", all(deep, ASKS));
        check("Brief caps the plan at one frame and four steps",
                brief.contains("ONE frame") && brief.contains("at most 4 steps"));
        check("Deep raises the frame cap to four and the relation cap to five",
                deep.contains("up to 4 frames") && deep.contains("up to 5 per frame"));
        check("  control: Standard keeps the shipped 1-2 frames and 2-3 relations",
                std.contains("usually 1-2 frames") && std.contains("2-3 per frame at most"));
        check("Brief and Deep carry a DEPTH: line; Standard carries none",
                brief.contains("DEPTH: brief") && deep.contains("DEPTH: deep") && !std.contains("DEPTH:"));

        // The fallback mirror gets the same treatment, or Brief silently means Standard for anyone whose
        // skill install failed — a defect with no symptom, which is the kind this file exists for.
        String briefNo = prompt(ExplainScope.CURSOR, null, BRIEF, null);
        String stdNo = prompt(ExplainScope.CURSOR, null, STD, null);
        String deepNo = prompt(ExplainScope.CURSOR, null, DEEP, null);
        check("the no-skill fallback honours Brief too", none(briefNo, FALLBACK_ASKS));
        check("  control: the fallback at Standard carries every optional-field paragraph",
                all(stdNo, FALLBACK_ASKS));
        check("  control: the fallback at Deep does too", all(deepNo, FALLBACK_ASKS));
        check("  control: the fallback really is the fallback (no skill path in it)",
                !stdNo.contains("/home/u/.claude/skills") && std.contains("/home/u/.claude/skills"));

        // Every combination builds, and each depth is one distinct prompt per scope/skill/language.
        int built = 0, distinct = 0;
        for (ExplainScope scope : ExplainScope.values()) {
            for (Path skill : new Path[] { SKILL, null }) {
                String b = prompt(scope, skill, BRIEF, null);
                String s = prompt(scope, skill, STD, null);
                String d = prompt(scope, skill, DEEP, null);
                built += 3;
                if (!b.equals(s) && !s.equals(d) && !b.equals(d)) distinct++;
            }
        }
        check("all " + built + " scope x skill x depth prompts build, and the 3 depths differ in each of the 6",
                built == 18 && distinct == 6);

        // ── (d) a per-run override beats the setting; absent ones fall back ───
        // The resolution itself is one line of `ClaudeExplainer.explain` (`request.x ?: state.x`) and needs
        // an application, so what is asserted here is that it is expressible: the request carries the
        // override, a null leaves the setting standing, and the two produce different argv/prompts.
        AudioExplainerSettings.State state = new AudioExplainerSettings.State();
        state.explainEffort = AudioExplainerSettings.EFFORT_XHIGH;
        state.explainDepth = STD;
        ExplainRequest override = request(ExplainScope.CURSOR, false, AudioExplainerSettings.EFFORT_LOW, BRIEF, null);
        ExplainRequest plain = request(ExplainScope.CURSOR, false, null, null, null);
        String resolvedEffort = override.getEffort() != null ? override.getEffort() : state.explainEffort;
        String plainEffort = plain.getEffort() != null ? plain.getEffort() : state.explainEffort;
        String resolvedDepth = override.getDepth() != null ? override.getDepth() : state.explainDepth;
        String plainDepth = plain.getDepth() != null ? plain.getDepth() : state.explainDepth;
        check("a per-run effort beats the saved one", AudioExplainerSettings.EFFORT_LOW.equals(resolvedEffort));
        check("  control: the plain entry falls back to it", AudioExplainerSettings.EFFORT_XHIGH.equals(plainEffort));
        check("a per-run depth beats the saved one", BRIEF.equals(resolvedDepth));
        check("  control: the plain entry falls back to it", STD.equals(plainDepth));
        check("and the two resolve to different argv",
                !ExplainPrompt.INSTANCE.buildArgs("P", resolvedEffort, "")
                        .equals(ExplainPrompt.INSTANCE.buildArgs("P", plainEffort, "")));
        check("the plain entry carries no override at all (all three null)",
                plain.getEffort() == null && plain.getDepth() == null && plain.getExtraInstructions() == null);

        // ── (e) the free text ─────────────────────────────────────────────────
        String typed = "Use simple terms. Do not use links.\nAssume I know C# but not this codebase.";
        String withExtra = prompt(ExplainScope.CURSOR, SKILL, STD, typed);
        check("the user's text appears verbatim, exactly once", countOf(withExtra, typed) == 1);
        check("  it comes after the numbered steps",
                withExtra.indexOf(typed) > withExtra.indexOf("4. Do NOT print the walkthrough")
                        && withExtra.indexOf("4. Do NOT print the walkthrough") >= 0);
        check("  and it carries the sentence that keeps the contract fixed",
                withExtra.contains("They do NOT relax the `set_plan` contract"));
        check("  control: no text means no block at all",
                !std.contains("They do NOT relax the `set_plan` contract"));
        check("  control: blank text means no block either",
                !prompt(ExplainScope.CURSOR, SKILL, STD, "   \n  ")
                        .contains("They do NOT relax the `set_plan` contract"));
        check("the block is appended, not spliced — the Standard prompt is still its prefix",
                withExtra.startsWith(std));
        check("depth and free text compose: Brief plus instructions keeps both",
                prompt(ExplainScope.CURSOR, SKILL, BRIEF, typed).contains("DEPTH: brief")
                        && countOf(prompt(ExplainScope.CURSOR, SKILL, BRIEF, typed), typed) == 1);

        // ── (f) the plan directory (`Saving.md` B) ─────────────────────────────
        // The payloads the agent POSTs must not land in the project, where they showed up as blank rows in
        // the saved list. The prompt names the directory, and the argv grants it explicitly, so a headless
        // `Write` outside the working directory does not rest on the user's own permission settings.
        String planLine = "Write the JSON file you POST in "
                + "/tmp/audio-explainer/plans/run-0/";
        int namedDir = 0;
        for (Case c : cases) if (countOf(prompt(c), planLine) == 1) namedDir++;
        check("every golden prompt names the plan directory exactly once (" + namedDir + "/" + cases.size() + ")",
                namedDir == cases.size());
        check("  and says why: never inside the project", std.contains("never inside the project"));
        check("  in the shared tail, so the no-skill fallback keeps it too", stdNo.contains(planLine));
        check("  control: no plan dir means no line at all",
                !ExplainPrompt.INSTANCE.buildPrompt(request(ExplainScope.CURSOR, false, null, STD, null), SKILL,
                        Languages.INSTANCE.byCode("en"), STD, null, null).contains("Write the JSON file you POST"));
        check("  a Windows plan dir is written with forward slashes, like the skill dir",
                ExplainPrompt.INSTANCE.buildPrompt(request(ExplainScope.CURSOR, false, null, STD, null), SKILL,
                        Languages.INSTANCE.byCode("en"), STD, null, Paths.get("C:\\Temp\\plans\\run-1"))
                        .contains("C:/Temp/plans/run-1/"));
        List<String> granted = ExplainPrompt.INSTANCE.buildArgs("P", "medium", "", PLAN_DIR);
        check("the argv grants the plan dir with one `--add-dir <dir>` pair",
                countOf(String.join(" ", granted), "--add-dir") == 1
                        && PLAN_DIR.toString().equals(granted.get(granted.indexOf("--add-dir") + 1)));
        check("  last, so its variadic list swallows nothing after it",
                granted.indexOf("--add-dir") == granted.size() - 2);
        check("  control: no plan dir means no `--add-dir`",
                !ExplainPrompt.INSTANCE.buildArgs("P", "medium", "").contains("--add-dir"));

        // ── the diagnostics ──────────────────────────────────────
        // Two things are asserted without running anything: every check names a fix, and the OS filter
        // OMITS what does not apply rather than showing it as passing. Both are pure, so both are cheap.
        List<DiagnosticCheck> all = ExplainerDiagnostics.INSTANCE.allChecks();
        List<DiagnosticCheck> win = ExplainerDiagnostics.INSTANCE.checksFor(true);
        List<DiagnosticCheck> nix = ExplainerDiagnostics.INSTANCE.checksFor(false);
        int named = 0;
        for (DiagnosticCheck c : all) {
            if (!c.getName().trim().isEmpty() && !c.getRemedy().trim().isEmpty()) named++;
        }
        check("every check names a check AND a fix (" + all.size() + ")", named == all.size());
        List<String> winOnly = namesWithOs(all, CheckOs.WINDOWS);
        List<String> nixOnly = namesWithOs(all, CheckOs.UNIX);
        check("there are platform-only checks on both sides (" + winOnly.size() + " Windows, "
                + nixOnly.size() + " Unix)", !winOnly.isEmpty() && !nixOnly.isEmpty());
        check("the Windows set omits every Unix-only check",
                names(win).stream().noneMatch(nixOnly::contains) && names(win).containsAll(winOnly));
        check("  and the Unix set omits every Windows-only check",
                names(nix).stream().noneMatch(winOnly::contains) && names(nix).containsAll(nixOnly));
        check("both sets carry every platform-independent check",
                names(win).containsAll(namesWithOs(all, CheckOs.ANY))
                        && names(nix).containsAll(namesWithOs(all, CheckOs.ANY)));
        check("  control: neither set is simply the whole catalogue",
                win.size() < all.size() && nix.size() < all.size()
                        && win.size() + nix.size() == all.size() + namesWithOs(all, CheckOs.ANY).size());

        // The version comparison is the CLI-version row's entire verdict, and it is the one piece of
        // arithmetic in the file that can be wrong without anything looking wrong.
        check("2.1.27 is BELOW the 2.1.274 floor (component-wise, not lexical)",
                ExplainerDiagnostics.INSTANCE.compareVersions("2.1.27", "2.1.274") < 0);
        check("  2.1.274 meets it", ExplainerDiagnostics.INSTANCE.compareVersions("2.1.274", "2.1.274") == 0);
        check("  2.1.1000 and 2.2.0 and 3.0 all clear it",
                ExplainerDiagnostics.INSTANCE.compareVersions("2.1.1000", "2.1.274") > 0
                        && ExplainerDiagnostics.INSTANCE.compareVersions("2.2.0", "2.1.274") > 0
                        && ExplainerDiagnostics.INSTANCE.compareVersions("3.0", "2.1.274") > 0);
        check("  a build suffix does not confuse it",
                ExplainerDiagnostics.INSTANCE.compareVersions("2.1.274-rc1", "2.1.274") == 0);

        // And one real run on this machine. What it asserts is the CONTRACT, not the verdicts — whether
        // `claude` is installed here is not something a probe gets to have an opinion about. It does touch
        // subprocesses and one loopback POST, which is why it is last.
        DiagnosticContext ctx = new DiagnosticContext(
                new AudioExplainerSettings.State(), isWindows(), null, null);
        List<DiagnosticResult> results = ExplainerDiagnostics.INSTANCE.run(ctx);
        int wellFormed = 0;
        for (DiagnosticResult r : results) {
            if (r.getStatus() != null && !r.getDetail().trim().isEmpty() && !r.getRemedy().trim().isEmpty()) {
                wellFormed++;
            } else {
                check("  row \"" + r.getName() + "\" is missing a status, a detail or a fix", false);
            }
        }
        check("a real run returns " + results.size() + " rows, each with a status, a finding and a fix",
                wellFormed == results.size() && results.size() == (isWindows() ? win.size() : nix.size()));
        List<String> otherPlatform = isWindows() ? nixOnly : winOnly;
        check("  and not one of them belongs to the other platform",
                names(results).stream().noneMatch(otherPlatform::contains));
        System.out.println("      (verdicts on this machine, reported not asserted: " + verdicts(results) + ")");

        progress();
        toolRoute();

        System.out.println();
        System.out.println(pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("win");
    }

    static List<String> names(List<?> rows) {
        List<String> out = new ArrayList<>();
        for (Object o : rows) {
            if (o instanceof DiagnosticCheck) out.add(((DiagnosticCheck) o).getName());
            else out.add(((DiagnosticResult) o).getName());
        }
        return out;
    }

    static List<String> namesWithOs(List<DiagnosticCheck> checks, CheckOs os) {
        List<String> out = new ArrayList<>();
        for (DiagnosticCheck c : checks) if (c.getOs() == os) out.add(c.getName());
        return out;
    }

    /** Printed, never asserted: what passed here is a fact about this machine, not about the code. */
    static String verdicts(List<DiagnosticResult> results) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (DiagnosticResult r : results) tally.merge(r.getStatus().name(), 1, Integer::sum);
        return tally.toString();
    }
}
