import com.audioexplainer.AudioExplainerSettings;
import com.audioexplainer.CheckOs;
import com.audioexplainer.DiagnosticCheck;
import com.audioexplainer.DiagnosticContext;
import com.audioexplainer.DiagnosticResult;
import com.audioexplainer.ExplainerDiagnostics;
import com.audioexplainer.ExplainProgress;
import com.audioexplainer.ExplainPrompt;
import com.audioexplainer.ExplainRequest;
import com.audioexplainer.ExplainScope;
import com.audioexplainer.Language;
import com.audioexplainer.Languages;

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

    /**
     * **The run's progress bar** (`ExplainProgress`), fed a synthetic run on a hand-stepped clock. The plan
     * `Write` arrives in 3-character deltas, so **no delta holds a whole key** — asserted, as the control, because
     * that is what bench3 recorded (1 key of 13 whole) and what a per-delta count gets wrong.
     */
    static void progress() {
        List<String> argv = ExplainPrompt.INSTANCE.buildArgs("P", "medium", "", PLAN_DIR);
        check("progress: the argv asks for partial messages, once, before the variadic --add-dir",
                argv.indexOf("--include-partial-messages") >= 0
                        && argv.indexOf("--include-partial-messages") == argv.lastIndexOf("--include-partial-messages")
                        && argv.indexOf("--include-partial-messages") < argv.indexOf("--add-dir"));

        double[] clock = {0};
        ExplainProgress p = new ExplainProgress("medium", "standard", () -> clock[0]);
        List<Double> seen = new ArrayList<>();
        java.util.function.Consumer<String> feed = j -> { p.onEvent(ev(j)); seen.add(p.fraction()); };

        feed.accept("{\"type\":\"system\",\"subtype\":\"init\"}");
        clock[0] = 3;
        double starting = p.fraction();
        seen.add(starting);
        check("progress: starting is time-driven and stays inside its band", starting > 0 && starting < 0.06);
        feed.accept(MESSAGE_START);
        feed.accept(toolUse("Read", "{\"file_path\":\"C:/src/Tracker.cs\"}"));
        check("progress: a Read names its file", "Reading Tracker.cs…".equals(p.activity()));
        feed.accept(TOOL_RESULT);
        clock[0] = 10;
        double timeOnly = p.fraction();
        feed.accept("{\"type\":\"system\",\"subtype\":\"thinking_tokens\",\"estimated_tokens_delta\":1000}");
        check("progress: thinking tokens move the planning band with the clock standing still",
                p.fraction() > timeOnly && p.fraction() < 0.50);

        // The third step's prose says "keep" and "ttsText" — unescaped quotes there would count; escaped ones don't.
        String plan = "{\"frames\":[{\"keep\":[]},{\"keep\":[]}],\"steps\":[{\"ttsText\":\"a\"},{\"ttsText\":\"b\"},"
                + "{\"ttsText\":\"say \\\"keep\\\" and ttsText\"}]}";
        String input = "{\"file_path\": \"p.json\", \"content\": \"" + esc(plan) + "\"}";
        clock[0] = 20;
        feed.accept(MESSAGE_START);
        feed.accept(blockStart(1, "Write"));
        check("progress: the plan Write starts the writing band at 50 %", Math.abs(p.fraction() - 0.50) < 1e-9);
        int whole = 0;
        boolean sawFrame2 = false;
        for (int i = 0; i < input.length(); i += 3) {
            String part = input.substring(i, Math.min(input.length(), i + 3));
            if (part.contains("\\\"keep\\\"") || part.contains("\\\"ttsText\\\"")) whole++;
            feed.accept(delta(part));
            sawFrame2 |= p.activity().endsWith("frame 2…");
        }
        check("  control: no delta held a whole key", whole == 0);
        check("progress: frames counted as they stream, then steps — 2 frames, 3 steps, the prose ignored",
                sawFrame2 && "Writing the walkthrough — step 3…".equals(p.activity()));
        feed.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_stop\",\"index\":1}}");
        check("progress: the Write's end is checking and sending, at 88 %",
                p.getBand() == ExplainProgress.Band.SENDING && Math.abs(p.fraction() - 0.88) < 1e-9);
        feed.accept(toolUse("PowerShell", "{\"command\":\"powershell -File explainer.ps1 plan p.json\"}"));
        feed.accept(TOOL_RESULT);
        check("progress: the POST's reply is finishing", p.getBand() == ExplainProgress.Band.FINISHING);
        feed.accept(MESSAGE_START);
        feed.accept(blockStart(0, "Write"));
        check("progress: a second Write (a fix) relabels and does not refill",
                "Fixing the walkthrough…".equals(p.activity()) && p.getBand() == ExplainProgress.Band.FINISHING);
        clock[0] = 600;
        check("  and time alone never finishes the bar", p.fraction() < 1.0);
        feed.accept("{\"type\":\"result\",\"subtype\":\"success\"}");
        check("progress: the result line is 100 %", p.fraction() == 1.0);
        boolean monotone = true;
        for (int i = 1; i < seen.size(); i++) monotone &= seen.get(i) >= seen.get(i - 1);
        check("progress: never decreased over " + seen.size() + " samples", monotone);
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
