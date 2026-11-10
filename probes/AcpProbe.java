import com.audioexplainer.ExplainProgress;
import com.audioexplainer.ProviderProfile;
import com.audioexplainer.Providers;
import com.audioexplainer.RunEvent;
import com.audioexplainer.RunLog;
import com.audioexplainer.RunReader;
import com.audioexplainer.RunShape;
import com.audioexplainer.acp.AcpConnection;
import com.audioexplainer.acp.AcpException;
import com.audioexplainer.acp.AcpFs;
import com.audioexplainer.acp.AcpPolicy;
import com.audioexplainer.acp.AcpSession;
import com.audioexplainer.acp.AgentLaunch;
import com.audioexplainer.acp.AgentPresets;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonArray;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonElementKt;
import kotlinx.serialization.json.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * **The ACP client, with no agent and no IDE** (`Models.B.md` B2b): the permission policy as a table, the client's
 * file reads, each preset's launch, the JSON-RPC framing against an in-process fake agent, and the two recorded
 * sessions in `probes/fixtures/acp/` (opencode and Qwen Code on this repo's `RunLog.kt`, 2026-11-06) replayed
 * through the real `AcpSession` into a `RunReader`, the run log and the bar — on the recording's own clock.
 */
public final class AcpProbe {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (ok) pass++; else fail++;
    }

    static JsonObject obj(String json) {
        return JsonElementKt.getJsonObject(Json.Default.parseToJsonElement(json));
    }

    static final String PROJECT = "C:/_code/JetbrainsAudioExplainer";
    static final String SKILL = "C:/Users/dev/.claude/skills/audio-explainer";
    static final List<String> ROOTS = List.of(PROJECT, SKILL);
    static final String OPTIONS = "[{\"optionId\":\"once\",\"kind\":\"allow_once\"},{\"optionId\":\"always\",\"kind\":\"allow_always\"},"
            + "{\"optionId\":\"reject\",\"kind\":\"reject_once\"}]";

    static AcpPolicy.Decision ask(String toolCall) {
        return AcpPolicy.INSTANCE.decide(obj(toolCall), JsonElementKt.getJsonArray(Json.Default.parseToJsonElement(OPTIONS)), ROOTS);
    }

    public static void main(String[] args) throws Exception {
        policy();
        fs();
        presets();
        attached();
        framing();
        unasked();
        replay("opencode", "OpenRouter");
        replay("qwen", "OpenRouter");
        System.out.println();
        System.out.println(pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    // ── what the policy cannot answer: a call never asked about ────────────────

    /** opencode as a Custom agent writing `notes.txt` (sandbox, 2026-11-09), its three updates verbatim but the content. */
    static final String[] WRITE = {
        "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"call_w\",\"title\":\"write\",\"kind\":\"edit\",\"status\":\"pending\",\"locations\":[],\"rawInput\":{}}",
        "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"call_w\",\"status\":\"in_progress\",\"kind\":\"edit\",\"title\":\"write\","
            + "\"locations\":[{\"path\":\"C:/temp/cs/LazyDotIde/notes.txt\"}],\"rawInput\":{\"filePath\":\"C:/temp/cs/LazyDotIde/notes.txt\",\"content\":\"x\"}}",
        "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"call_w\",\"status\":\"completed\",\"title\":\"notes.txt\","
            + "\"content\":[{\"type\":\"content\",\"content\":{\"type\":\"text\",\"text\":\"Wrote file successfully.\"}}]}",
    };

    static void unasked() {
        com.audioexplainer.acp.UnaskedWatch watch = new com.audioexplainer.acp.UnaskedWatch();
        List<String> seen = new ArrayList<>();
        for (String u : WRITE) { String r = watch.update(obj(u)); if (r != null) seen.add(r); }
        check("unasked: the sandbox's unasked write is seen once it completes, naming the file — " + seen,
                seen.equals(List.of("wrote C:/temp/cs/LazyDotIde/notes.txt")));
        check("  and said once, however often the agent repeats the call's end", watch.update(obj(WRITE[2])) == null);

        com.audioexplainer.acp.UnaskedWatch askedFirst = new com.audioexplainer.acp.UnaskedWatch();
        askedFirst.asked(obj("{\"toolCallId\":\"call_w\",\"kind\":\"edit\"}"));
        boolean quiet = true;
        for (String u : WRITE) quiet &= askedFirst.update(obj(u)) == null;
        check("  a call that was asked about is the policy's, not a breach", quiet);

        com.audioexplainer.acp.UnaskedWatch shell = new com.audioexplainer.acp.UnaskedWatch();
        shell.update(obj("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"b\",\"kind\":\"execute\",\"status\":\"pending\",\"rawInput\":{\"command\":\"del x\"}}"));
        check("  a command run unasked is named by its command",
                "ran del x".equals(shell.update(obj("{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"b\",\"status\":\"completed\"}"))));

        com.audioexplainer.acp.UnaskedWatch benign = new com.audioexplainer.acp.UnaskedWatch();
        boolean none = true;
        for (String k : List.of("read", "search", "think", "other")) {
            benign.update(obj("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"" + k + "\",\"kind\":\"" + k + "\",\"status\":\"pending\"}"));
            none &= benign.update(obj("{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"" + k + "\",\"status\":\"completed\"}")) == null;
        }
        String failed = new com.audioexplainer.acp.UnaskedWatch().update(obj(WRITE[0]));
        check("  reads, searches, thinking and the plan (`other`) complete unasked as they should", none && failed == null);
        com.audioexplainer.acp.UnaskedWatch denied = new com.audioexplainer.acp.UnaskedWatch();
        denied.update(obj(WRITE[0]));
        check("  a write the agent's own config denied ends failed, and is no breach",
                denied.update(obj("{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"call_w\",\"status\":\"failed\"}")) == null);
    }

    // ── the permission policy ──────────────────────────────────────────────────

    static void policy() {
        AcpPolicy.Decision read = ask("{\"kind\":\"read\",\"title\":\"read\",\"locations\":[{\"path\":\"" + PROJECT + "/src/A.kt\"}]}");
        check("policy: a read in the project is allowed, once — " + read.getWhy(), read.getAllowed() && "once".equals(read.getOptionId()));
        AcpPolicy.Decision secret = ask("{\"kind\":\"read\",\"locations\":[{\"path\":\"C:/Users/dev/.ssh/id_ed25519\"}]}");
        check("  a read outside the project and the skill is not — " + secret.getWhy(), !secret.getAllowed() && "reject".equals(secret.getOptionId()));
        check("  a search naming no location is allowed", ask("{\"kind\":\"search\",\"title\":\"Grep\"}").getAllowed());
        for (String k : List.of("edit", "delete", "move", "execute", "fetch")) {
            AcpPolicy.Decision d = ask("{\"kind\":\"" + k + "\",\"title\":\"x\",\"locations\":[{\"path\":\"" + PROJECT + "/a\"}]}");
            check("policy: a " + k + " is refused, even in the project", !d.getAllowed() && "reject".equals(d.getOptionId()));
        }
        String ext = "{\"kind\":\"other\",\"title\":\"x\",\"locations\":[{\"path\":\"C:\\\\Users\\\\dev\\\\.claude\\\\skills\\\\audio-explainer\\\\docs\\\\plan-schema.md\"}],"
                + "\"rawInput\":{\"filepath\":\"C:\\\\Users\\\\dev\\\\.claude\\\\skills\\\\audio-explainer\\\\docs\\\\plan-schema.md\",\"parentDir\":\"C:\\\\Users\\\\dev\\\\.claude\\\\skills\\\\audio-explainer\\\\docs\"}}";
        check("policy: opencode's external_directory for the skill is allowed, backslashes and all — " + ask(ext).getWhy(), ask(ext).getAllowed());
        check("  outside it, it is not", !ask(ext.replace("audio-explainer\\\\docs", "other\\\\docs").replace("audio-explainer", "other")).getAllowed());
        check("  nor a path that climbs out with ..", !ask(ext.replace("\\\\docs\\\\plan-schema.md\"", "\\\\..\\\\..\\\\..\\\\.ssh\\\\id\"")).getAllowed());
        String plan = "{\"kind\":\"other\",\"title\":\"{\\\"title\\\":\\\"T\\\",\\\"frames\\\":[]}\",\"rawInput\":{\"title\":\"T\",\"frames\":[],\"steps\":[]}}";
        check("policy: Qwen's nameless set_plan ask — the plan as its title and input — is allowed", ask(plan).getAllowed());
        check("  control: the same input on an edit is not", !ask(plan.replace("\"kind\":\"other\"", "\"kind\":\"edit\"")).getAllowed());
        check("policy: set_plan by any name an agent gives it",
                ask("{\"kind\":\"other\",\"title\":\"audio-explainer_set_plan\"}").getAllowed()
                        && ask("{\"title\":\"set_plan (audio-explainer MCP Server): {...}\"}").getAllowed()
                        && ask("{\"title\":\"ToolCall\",\"_meta\":{\"toolLifecycle\":{\"toolName\":\"mcp__audio-explainer__set_plan\"}}}").getAllowed());
        check("  control: another server's set_plan, or an unknown tool, is refused",
                !ask("{\"kind\":\"other\",\"title\":\"evil_set_plan\"}").getAllowed() && !ask("{\"kind\":\"other\",\"title\":\"mystery\"}").getAllowed());
        AcpPolicy.Decision onlyAlways = AcpPolicy.INSTANCE.decide(obj("{\"kind\":\"read\"}"),
                JsonElementKt.getJsonArray(Json.Default.parseToJsonElement("[{\"optionId\":\"a\",\"kind\":\"allow_always\"},{\"optionId\":\"r\",\"kind\":\"reject_once\"}]")), ROOTS);
        check("policy: never allow_always — an agent offering only that is answered cancelled ("
                + AcpPolicy.INSTANCE.result(onlyAlways) + ")",
                onlyAlways.getAllowed() && onlyAlways.getOptionId() == null && AcpPolicy.INSTANCE.result(onlyAlways).toString().contains("cancelled"));
        check("roots: case and separators ignored on Windows, a sibling with a longer name is not inside",
                AcpPolicy.INSTANCE.inRoots("c:\\_CODE\\JetbrainsAudioExplainer\\x.kt", ROOTS, true)
                        && !AcpPolicy.INSTANCE.inRoots("C:/_code/JetbrainsAudioExplainer2/x.kt", ROOTS, true)
                        && !AcpPolicy.INSTANCE.inRoots("c:/_code/jetbrainsaudioexplainer/x.kt", ROOTS, false));
    }

    // ── fs/read_text_file ──────────────────────────────────────────────────────

    static void fs() {
        String text = "a\r\nb\nc\nd";
        check("fs: line 2, limit 2 is lines 2-3 with their own line ends", "b\nc\n".equals(AcpFs.INSTANCE.slice(text, 2, 2)));
        check("  line 1 keeps a CRLF; no limit runs to the end; past the end is empty",
                "a\r\n".equals(AcpFs.INSTANCE.slice(text, 1, 1)) && "c\nd".equals(AcpFs.INSTANCE.slice(text, 3, null))
                        && "".equals(AcpFs.INSTANCE.slice(text, 9, 5)) && text.equals(AcpFs.INSTANCE.slice(text, null, null)));
        check("  a limit past the end stops at it", "c\nd".equals(AcpFs.INSTANCE.slice(text, 3, 50)));
        JsonObject got = AcpFs.INSTANCE.read(obj("{\"path\":\"" + PROJECT + "/A.kt\",\"line\":2,\"limit\":1}"), ROOTS, p -> "one\ntwo\nthree\n");
        check("fs: a read in the project is served — " + got, got.toString().equals("{\"content\":\"two\\n\"}"));
        boolean refused = false;
        try { AcpFs.INSTANCE.read(obj("{\"path\":\"C:/Windows/win.ini\"}"), ROOTS, p -> "x"); } catch (AcpException e) { refused = e.getCode() == AcpConnection.INVALID_PARAMS; }
        check("  outside the roots it is refused, unread", refused);
    }

    // ── the presets ────────────────────────────────────────────────────────────

    static ProviderProfile profile(String preset, String model) {
        ProviderProfile p = Providers.INSTANCE.newProfile(Providers.INSTANCE.preset(preset), "P");
        p.model = model;
        return p;
    }

    static void presets() {
        String key = "sk-or-v1-probe-key-0123456789";
        AgentLaunch oc = AgentPresets.INSTANCE.launch(AgentPresets.OPENCODE, PROJECT, profile(Providers.PRESET_OPENROUTER, "z-ai/glm-5.3-flash"), key, "high", List.of());
        String cfg = oc.getEnv().get("OPENCODE_CONFIG_CONTENT");
        check("opencode: `acp --pure --cwd <project>`, the key in OPENROUTER_API_KEY and nowhere in argv",
                oc.getArgs().equals(List.of("acp", "--pure", "--cwd", PROJECT)) && key.equals(oc.getEnv().get("OPENROUTER_API_KEY"))
                        && !String.join(" ", oc.getArgs()).contains(key) && !cfg.contains(key));
        check("  the config: model openrouter/<id>, edit/bash/webfetch denied, effort high as reasoning — " + cfg,
                cfg.contains("\"model\":\"openrouter/z-ai/glm-5.3-flash\"") && cfg.contains("\"edit\":\"deny\"") && cfg.contains("\"bash\":\"deny\"")
                        && cfg.contains("\"webfetch\":\"deny\"") && cfg.contains("\"reasoning\":{\"effort\":\"high\"}"));
        String inherited = AgentPresets.INSTANCE.launch(AgentPresets.OPENCODE, PROJECT, profile(Providers.PRESET_OPENROUTER, "m"), key, "", List.of())
                .getEnv().get("OPENCODE_CONFIG_CONTENT");
        check("  a blank effort sends no reasoning", !inherited.contains("reasoning"));
        AgentLaunch own = AgentPresets.INSTANCE.launch(AgentPresets.OPENCODE, PROJECT, null, null, "high", List.of());
        check("opencode with no profile: the agent's own model — no model, no key, the deny-list still",
                !own.getEnv().get("OPENCODE_CONFIG_CONTENT").contains("\"model\"") && own.getEnv().size() == 2
                        && own.getEnv().get("OPENCODE_CONFIG_CONTENT").contains("\"bash\":\"deny\""));
        check("  opencode leaves Claude Code's skills out, with a profile and without",
                "1".equals(own.getEnv().get("OPENCODE_DISABLE_CLAUDE_CODE_SKILLS")) && "1".equals(oc.getEnv().get("OPENCODE_DISABLE_CLAUDE_CODE_SKILLS")));
        // The sandbox round of 2026-11-09: Qwen Code with no profile, verbatim.
        String qwenAuth = "Authentication required: Use /auth to sign in or configure a model first.";
        String ownHint = AgentPresets.INSTANCE.authHint(qwenAuth, "Qwen Code 0.26.8", null);
        check("an agent's auth refusal with no provider says to choose one — " + ownHint,
                ownHint != null && ownHint.contains("No Provider is selected") && ownHint.contains("Settings → Tools → Audio Explainer → Explanation"));
        String keyHint = AgentPresets.INSTANCE.authHint("401 Unauthorized", "OpenCode", "OpenRouter");
        check("  with a provider it names the profile's key", keyHint != null && keyHint.contains("OpenRouter") && keyHint.contains("key"));
        check("  and an error that is not about auth gets no hint",
                AgentPresets.INSTANCE.authHint("The agent started no session", "Qwen Code", null) == null
                        && AgentPresets.INSTANCE.authHint(null, "Qwen Code", null) == null);
        AgentLaunch ds = AgentPresets.INSTANCE.launch(AgentPresets.OPENCODE, PROJECT, profile("deepseek", "deepseek-flash"), key, "", List.of());
        String dsc = ds.getEnv().get("OPENCODE_CONFIG_CONTENT");
        check("opencode with another provider: a provider of the run's own, Anthropic-style at <base>/v1, the key by env reference",
                dsc.contains("\"npm\":\"@ai-sdk/anthropic\"") && dsc.contains("\"baseURL\":\"https://api.deepseek.com/anthropic/v1\"")
                        && dsc.contains("{env:" + AgentPresets.OPENCODE_KEY_VAR + "}") && key.equals(ds.getEnv().get(AgentPresets.OPENCODE_KEY_VAR)) && !dsc.contains(key));
        AgentLaunch qw = AgentPresets.INSTANCE.launch(AgentPresets.QWEN, PROJECT, profile(Providers.PRESET_OPENROUTER, "z-ai/glm-5.3-flash"), key, "high", List.of());
        check("qwen: `--acp --approval-mode default --auth-type openai --model <id>` with OPENAI_* in the env",
                qw.getArgs().equals(List.of("--acp", "--approval-mode", "default", "--auth-type", "openai", "--model", "z-ai/glm-5.3-flash"))
                        && "https://openrouter.ai/api/v1".equals(qw.getEnv().get("OPENAI_BASE_URL")) && key.equals(qw.getEnv().get("OPENAI_API_KEY")));
        check("  a Custom profile has no OpenAI-compatible twin, so it says so",
                AgentPresets.INSTANCE.launch(AgentPresets.QWEN, PROJECT, profile(Providers.PRESET_CUSTOM, "m"), key, "", List.of()).getProblem() != null);
        AgentLaunch custom = AgentPresets.INSTANCE.launch(AgentPresets.CUSTOM, PROJECT, profile(Providers.PRESET_OPENROUTER, "m1"), key, "", List.of("--acp", "-v"));
        check("custom: its own args, no env, the profile's model asked of the session",
                custom.getArgs().equals(List.of("--acp", "-v")) && custom.getEnv().isEmpty() && "m1".equals(custom.getSessionModel()));
        check("qwen with no profile still asks before edits and commands",
                AgentPresets.INSTANCE.launch(AgentPresets.QWEN, PROJECT, null, null, "", List.of()).getArgs().equals(List.of("--acp", "--approval-mode", "default")));
        check("secrets: the keys are listed for scrubbing", AgentPresets.INSTANCE.secrets(oc).equals(List.of(key)) && AgentPresets.INSTANCE.secrets(ds).equals(List.of(key)));
    }

    // ── the prompt with the docs attached ─────────────────────────────────────

    static void attached() {
        com.audioexplainer.ExplainRequest req = new com.audioexplainer.ExplainRequest(PROJECT, PROJECT + "/src/A.kt", "src/A.kt", 1, null, null, null,
                com.audioexplainer.ExplainScope.FILE, List.of(), "high", "standard", null);
        java.nio.file.Path skill = Paths.get(SKILL), plans = Paths.get("C:/tmp/plans/run-1");
        com.audioexplainer.Language en = com.audioexplainer.Languages.INSTANCE.byCode("en");
        String plain = com.audioexplainer.ExplainPrompt.INSTANCE.buildPrompt(req, skill, en, "standard", null, plans, true, false);
        String att = com.audioexplainer.ExplainPrompt.INSTANCE.buildPrompt(req, skill, en, "standard", null, plans, true, true);
        check("attached: the prompt says the docs are attached, names both, and asks for no read of them",
                att.contains("ATTACHED to this message in full") && att.contains("docs/explain-at-cursor.md") && att.contains("docs/plan-schema.md")
                        && att.contains("do not read them again") && !att.contains("read it first"));
        String[] pl = plain.split("\n", -1), at = att.split("\n", -1);
        int head = 0;
        while (head < Math.min(pl.length, at.length) && pl[head].equals(at[head])) head++;
        int tail = 0;
        while (tail < Math.min(pl.length, at.length) - head && pl[pl.length - 1 - tail].equals(at[at.length - 1 - tail])) tail++;
        check("  and differs from the tool prompt only in that block (" + (pl.length - head - tail) + " lines for "
                + (at.length - head - tail) + ")", pl.length - head - tail <= 5 && at.length - head - tail <= 6 && head > 5 && tail > 5);
        JsonArray blocks = AcpSession.Companion.blocks("P", List.of(new kotlin.Pair<>("C:\\x\\docs\\a.md", "# A")));
        check("attached: a doc is a resource block with a file URI and its whole text — " + blocks,
                blocks.size() == 2 && blocks.get(1).toString().equals("{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///C:/x/docs/a.md\",\"mimeType\":\"text/markdown\",\"text\":\"# A\"}}"));
    }

    // ── the framing ────────────────────────────────────────────────────────────

    /** Two ends of a loopback socket: the client's connection, and the fake agent's reader and writer. */
    static final class Wire {
        final Socket client, agent;
        final BufferedReader fromClient;
        final OutputStream toClient;

        Wire() throws Exception {
            try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                CompletableFuture<Socket> accepted = CompletableFuture.supplyAsync(() -> { try { return ss.accept(); } catch (Exception e) { throw new RuntimeException(e); } });
                client = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort());
                agent = accepted.get();
            }
            fromClient = new BufferedReader(new InputStreamReader(agent.getInputStream(), StandardCharsets.UTF_8));
            toClient = agent.getOutputStream();
        }

        void send(String json) throws Exception {
            toClient.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            toClient.flush();
        }

        JsonObject read() throws Exception {
            String l = fromClient.readLine();
            return l == null ? null : obj(l);
        }
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e instanceof kotlinx.serialization.json.JsonPrimitive p ? p.getContent() : null;
    }

    static void framing() throws Exception {
        Wire w = new Wire();
        List<String> logged = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        AcpConnection c = new AcpConnection(w.client.getInputStream(), w.client.getOutputStream(),
                (m, p) -> { throw new AcpException(AcpConnection.METHOD_NOT_FOUND, m + " is not supported"); },
                (m, p) -> { notes.add(m); return kotlin.Unit.INSTANCE; },
                l -> { synchronized (logged) { logged.add(l); } return kotlin.Unit.INSTANCE; },
                s -> s.replace("s3cret", "***"));
        c.start("probe reader");
        CompletableFuture<JsonElement> a = c.requestAsync("one", obj("{\"x\":\"s3cret\"}"));
        CompletableFuture<JsonElement> b = c.requestAsync("two", obj("{}"));
        JsonObject ra = w.read(), rb = w.read();
        // Out of order, with a garbage line and a blank one between, and a notification first.
        w.send("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{}}");
        w.send("not json");
        w.send("");
        w.send("{\"jsonrpc\":\"2.0\",\"id\":" + str(rb, "id") + ",\"result\":{\"n\":2}}");
        w.send("{\"jsonrpc\":\"2.0\",\"id\":" + str(ra, "id") + ",\"result\":{\"n\":1}}");
        check("framing: responses matched by id, out of order, past a garbage line",
                a.get().toString().equals("{\"n\":1}") && b.get().toString().equals("{\"n\":2}") && notes.equals(List.of("session/update")));
        w.send("{\"jsonrpc\":\"2.0\",\"id\":77,\"method\":\"craft/drainMidTurnQueue\",\"params\":{}}");
        JsonObject reply = w.read();
        check("framing: an unknown agent request is answered method-not-found, its id echoed — " + reply,
                "77".equals(str(reply, "id")) && reply.toString().contains("-32601"));
        CompletableFuture<JsonElement> bad = c.requestAsync("three", obj("{}"));
        JsonObject r3 = w.read();
        w.send("{\"jsonrpc\":\"2.0\",\"id\":" + str(r3, "id") + ",\"error\":{\"code\":-32602,\"message\":\"no\"}}");
        int code = 0;
        try { bad.get(); } catch (java.util.concurrent.ExecutionException e) { code = ((AcpException) e.getCause()).getCode(); }
        check("framing: an error reply is an AcpException with its code", code == -32602);
        int timedOut = 0;
        try { c.request("four", obj("{}"), 300); } catch (AcpException e) { timedOut = e.getCode(); }
        w.read();
        check("framing: no answer in time is a timeout", timedOut == AcpConnection.TIMED_OUT);
        CompletableFuture<JsonElement> left = c.requestAsync("five", obj("{}"));
        w.read();
        w.agent.close();
        int closed = 0;
        try { left.get(); } catch (java.util.concurrent.ExecutionException e) { closed = ((AcpException) e.getCause()).getCode(); }
        check("framing: the agent going away fails what was pending", closed == AcpConnection.CLOSED);
        for (int i = 0; i < 50 && !c.getClosed(); i++) Thread.sleep(10);
        int after = 0;
        try { c.requestAsync("six", obj("{}")).get(); } catch (java.util.concurrent.ExecutionException e) { after = ((AcpException) e.getCause()).getCode(); }
        check("  and a request after that fails at once", after == AcpConnection.CLOSED);
        boolean leaked;
        synchronized (logged) { leaked = logged.stream().anyMatch(l -> l.contains("s3cret")); }
        check("framing: every message is logged, scrubbed (" + logged.size() + " lines, the secret in none)", !leaked && logged.size() > 8);
        c.close();
        w.client.close();
    }

    // ── a recorded session, replayed ──────────────────────────────────────────

    static void replay(String name, String provider) throws Exception {
        List<JsonObject> rows = new ArrayList<>();
        for (String l : Files.readAllLines(Paths.get("probes/fixtures/acp/" + name + ".jsonl"), StandardCharsets.UTF_8)) rows.add(obj(l));
        double[] clock = {0};
        ExplainProgress progress = new ExplainProgress(new RunShape(30, 8000, 320, 1), () -> clock[0]);
        RunLog runLog = new RunLog(name, () -> clock[0]);
        List<String> info = new ArrayList<>();
        boolean[] connected = {false};
        RunReader reader = new RunReader(progress, runLog, provider, true, c -> kotlin.Unit.INSTANCE,
                m -> { info.add(m); return kotlin.Unit.INSTANCE; }, () -> clock[0], () -> connected[0]);
        List<String> phases = new ArrayList<>();
        List<String> activities = new ArrayList<>();
        AtomicInteger handled = new AtomicInteger();
        Object lock = new Object();
        List<String> refused = new ArrayList<>();
        List<String> fsReads = new ArrayList<>();
        List<String> unasked = new ArrayList<>();
        Wire w = new Wire();
        AcpSession session = new AcpSession(w.client.getInputStream(), w.client.getOutputStream(),
                events -> {
                    synchronized (lock) {
                        // One event at a time, to see every phase: a call named and sent in one update is
                        // writing and checking within that update.
                        for (RunEvent e : events) {
                            reader.onLine(List.of(e));
                            String ph = progress.getPhase().name();
                            if (phases.isEmpty() || !phases.get(phases.size() - 1).equals(ph)) phases.add(ph);
                            String act = progress.activity();
                            if (activities.isEmpty() || !activities.get(activities.size() - 1).equals(act)) activities.add(act);
                        }
                    }
                    handled.incrementAndGet();
                    return kotlin.Unit.INSTANCE;
                },
                (what, why) -> { refused.add(what + " — " + why); return kotlin.Unit.INSTANCE; },
                ROOTS,
                p -> { fsReads.add(p); return "line\n".repeat(50); },
                l -> kotlin.Unit.INSTANCE, s -> s,
                what -> { unasked.add(what); return kotlin.Unit.INSTANCE; });
        session.start();

        // The fake agent: answers our requests with the recorded results, and sends the recorded traffic in order —
        // each update only once the client has handled the one before, so the clock is the recording's.
        Map<String, String> ourIds = new HashMap<>();
        Map<String, String> recordedAnswers = new LinkedHashMap<>();
        Map<String, String> ourAnswers = new LinkedHashMap<>();
        CompletableFuture<Void> agent = CompletableFuture.runAsync(() -> {
            try {
                int sent = 0;
                for (JsonObject row : rows) {
                    JsonObject msg = (JsonObject) row.get("msg");
                    String dir = str(row, "dir");
                    String method = str(msg, "method");
                    String id = msg.get("id") == null ? null : msg.get("id").toString();
                    if (dir.equals("out")) {
                        if (method != null && id != null) {
                            JsonObject ours = w.read();
                            while (ours != null && !method.equals(str(ours, "method"))) ours = w.read();
                            if (ours != null) ourIds.put(id, ours.get("id").toString());
                        } else if (method == null && id != null) {
                            recordedAnswers.put(id, String.valueOf(msg.get("result")));
                            JsonObject ours = w.read();
                            if (ours != null) ourAnswers.put(ours.get("id").toString(), String.valueOf(ours.get("result")));
                        }
                        continue;
                    }
                    clock[0] = Double.parseDouble(row.get("t").toString());
                    if (method == null && id != null) {
                        String to = ourIds.get(id);
                        String m = msg.toString().replaceFirst("\"id\":" + java.util.regex.Pattern.quote(id), "\"id\":" + to);
                        w.send(m);
                        continue;
                    }
                    if ("session/update".equals(method)) {
                        // The MCP handshake happens before any tool call, as both agents do it.
                        connected[0] = true;
                        sent++;
                        w.send(msg.toString());
                        for (int k = 0; k < 2000 && handled.get() < sent; k++) Thread.sleep(1);
                    } else {
                        w.send(msg.toString());
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        boolean opened = session.open(PROJECT, new AcpSession.McpServer("http://127.0.0.1:1/mcp/run", "t0k"), null, "probe");
        if (opened) session.prompt(JsonElementKt.getJsonArray(Json.Default.parseToJsonElement("[{\"type\":\"text\",\"text\":\"P\"}]")));
        agent.get();
        session.getConnection().close();
        w.agent.close();

        List<String> lines = runLog.snapshot();
        System.out.println("  ── " + name + " run log ──");
        for (String l : lines) System.out.println("  " + l);
        String all = String.join("\n", lines);
        check(name + ": the session opened, the agent is " + session.getAgent(), opened && session.getAgent() != null);
        check(name + ": every read the agent made is a line, by file", all.contains("Read explain-at-cursor.md") && all.contains("Read plan-schema.md")
                && all.contains("Read RunLog.kt"));
        check(name + ": each set_plan is a line with its sizes, and its reply under it", all.matches("(?s).*set_plan: \\d+ frame\\(s\\), \\d+ step\\(s\\)\n[^\n]*→ ok, .*"));
        check(name + ": the last reply is clean", lines.stream().filter(l -> l.contains("→ ")).reduce((x, y) -> y).orElse("").endsWith(", clean"));
        List<String> oursAsked = ourAnswers.values().stream().filter(v -> v.contains("\"outcome\"")).toList();
        List<String> spikeAsked = recordedAnswers.values().stream().filter(v -> v.contains("\"outcome\"")).toList();
        check(name + ": nothing was refused (" + refused + "), and each of the " + spikeAsked.size()
                + " permission(s) answered as the spike's client did — " + oursAsked, refused.isEmpty() && !spikeAsked.isEmpty() && oursAsked.equals(spikeAsked));
        check(name + ": the run's end is ok and its last words kept — " + reader.getFinalText(),
                !reader.isError() && reader.getFinalText() != null && all.contains("Finished: ") && all.contains("cost n/a (" + provider + ")"));
        check(name + ": the tool counted as connected", RunReader.CONNECTED.equals(reader.getToolStatus()));
        check(name + ": nothing ran unasked (" + unasked + ") — its reads and its plan are no breach", unasked.isEmpty());
        check(name + ": the bar went preparing → writing → checking → done (" + phases + ")",
                phases.equals(List.of("PREPARING", "WRITING", "CHECKING", "DONE")));
        RunShape seen = progress.observed();
        check(name + ": and learned a shape from the plan's call — " + (seen == null ? "none" : seen.encode()), seen != null && seen.getWriteChars() > 1000);
        check(name + ": thinking shows its size in characters (" + activities.stream().filter(a -> a.contains("chars")).findFirst().orElse("none") + ")",
                activities.stream().anyMatch(a -> a.matches("Thinking it through — [\\d.]+k? chars…")));
        if (name.equals("qwen")) {
            check("qwen: its reads came to the client, all inside the roots (" + fsReads.size() + ")",
                    fsReads.size() >= 3 && fsReads.stream().allMatch(p -> AcpPolicy.INSTANCE.inRoots(p, ROOTS, true)));
            check("qwen: the generic `tool_call` that turned out to be set_plan was started again as the plan",
                    all.contains("set_plan: 1 frame(s), 4 step(s)"));
        }
        w.client.close();
    }
}
