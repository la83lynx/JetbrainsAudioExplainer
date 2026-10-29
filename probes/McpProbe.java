import com.audioexplainer.ExplainerServer;
import com.audioexplainer.McpEndpoint;
import com.audioexplainer.McpRun;
import com.audioexplainer.PlanIssue;
import com.audioexplainer.ResolvedPlan;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * **The `set_plan` tool's endpoint, over a real socket** (`Speed.md` #3): the real `ExplainerServer`, started on
 * loopback, spoken to as Claude Code's HTTP transport speaks to it — the `server/discover` probe it opens with,
 * `initialize`, the notification, the GET it tries for a stream, `tools/list`, `tools/call`.
 *
 * What matters most is the token. A run is given its own, good for its own path and nothing else, and dead
 * once the run is closed — each asserted beside the control that the right token on the right path works.
 *
 * `start()` writes `~/.claude-explainer-{port,token}`, which a running IDE owns too: they are backed up first
 * and put back after, whatever happens.
 */
public final class McpProbe {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (ok) pass++; else fail++;
    }

    static final HttpClient HTTP = HttpClient.newHttpClient();

    static HttpResponse<String> post(String url, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static String call(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\""
                + (params == null ? "" : ",\"params\":" + params) + "}";
    }

    public static void main(String[] args) throws Exception {
        Path home = Paths.get(System.getProperty("user.home"));
        Path[] discovery = { home.resolve(".claude-explainer-port"), home.resolve(".claude-explainer-token") };
        Path backup = Files.createTempDirectory("mcp-probe-discovery");
        for (Path f : discovery) if (Files.exists(f)) Files.copy(f, backup.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        ExplainerServer server = new ExplainerServer();
        try {
            server.start();
            run(server, Files.readString(discovery[1]).trim());
        } finally {
            server.stop();
            for (Path f : discovery) {
                Path saved = backup.resolve(f.getFileName());
                if (Files.exists(saved)) Files.copy(saved, f, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        System.out.println();
        System.out.println(pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static void run(ExplainerServer server, String serverToken) throws Exception {
        Path planDir = Files.createTempDirectory("mcp-probe-run");
        Path code = planDir.resolve("Code.cs");
        Files.writeString(code, "class A {\n  void Run() {\n    Go();\n  }\n}\n");
        List<Integer> heard = new ArrayList<>();
        McpRun run = server.openRun(planDir, (ResolvedPlan r, List<PlanIssue> issues) -> {
            heard.add(r.getSteps().size());
            return kotlin.Unit.INSTANCE;
        });
        String url = server.mcpUrl(run);
        String base = "http://127.0.0.1:" + server.getPort();

        // ── the token ──
        check("token: the run's own token on its own path is answered",
                post(url, run.getToken(), call(1, "ping", null)).statusCode() == 200);
        check("token: no token is 401", post(url, null, call(1, "ping", null)).statusCode() == 401);
        check("token: the SERVER's token is 401 here — a run's endpoint takes the run's token only",
                post(url, serverToken, call(1, "ping", null)).statusCode() == 401);
        check("token: the run's token is 401 on the server's own routes", HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/health"))
                .header("Authorization", "Bearer " + run.getToken()).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 401);
        check("  control: the server's token is answered there", HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/health"))
                .header("Authorization", "Bearer " + serverToken).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200);
        check("token: a path naming no run is 404", post(base + "/mcp/nope", run.getToken(), call(1, "ping", null)).statusCode() == 404);

        // ── the handshake, in the CLI's order ──
        HttpResponse<String> discover = post(url, run.getToken(), "{\"jsonrpc\":\"2.0\",\"id\":\"server-discover-probe-1\",\"method\":\"server/discover\"}");
        check("handshake: the CLI's server/discover probe is method-not-found, its string id echoed",
                discover.statusCode() == 200 && discover.body().contains("-32601") && discover.body().contains("\"id\":\"server-discover-probe-1\""));
        HttpResponse<String> init = post(url, run.getToken(), call(0, "initialize",
                "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"probe\",\"version\":\"0\"}}"));
        check("handshake: initialize answers the client's version, a tools capability and the server's name",
                init.statusCode() == 200 && init.body().contains("\"protocolVersion\":\"2025-11-25\"")
                        && init.body().contains("\"tools\":{}") && init.body().contains("\"name\":\"" + McpEndpoint.SERVER_NAME + "\""));
        HttpResponse<String> note = post(url, run.getToken(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        check("handshake: a notification is 202 with no body", note.statusCode() == 202 && note.body().isEmpty());
        HttpResponse<String> get = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + run.getToken()).build(),
                HttpResponse.BodyHandlers.ofString());
        check("handshake: a GET for a stream is 405, so the client opens none", get.statusCode() == 405);
        HttpResponse<String> list = post(url, run.getToken(), call(1, "tools/list", null));
        check("handshake: tools/list is the one tool, with an input schema",
                list.body().contains("\"name\":\"set_plan\"") && list.body().contains("\"inputSchema\"")
                        && list.body().split("\"name\":", -1).length == 2);
        check("handshake: garbage is a parse error, 400", post(url, run.getToken(), "not json").statusCode() == 400);

        // ── the call ──
        String file = code.toString().replace('\\', '/');
        String plan = "{\"title\":\"Probe\",\"frames\":[{\"id\":0,\"file\":\"" + file + "\",\"title\":\"A\",\"explanation\":\"e\","
                + "\"keep\":[{\"start\":1,\"end\":1,\"role\":\"context\"},{\"start\":2,\"end\":4,\"role\":\"focus\"}]}],"
                + "\"steps\":[{\"frame\":0,\"ranges\":[{\"start\":2,\"end\":4}],\"ttsText\":\"Run calls Go.\",\"explanation\":\"x\"}]}";
        HttpResponse<String> ok = post(url, run.getToken(), call(2, "tools/call", "{\"name\":\"set_plan\",\"arguments\":" + plan + "}"));
        check("call: a plan is loaded, and the result is the POST's reply as text — " + ok.body(),
                ok.statusCode() == 200 && ok.body().contains("\"isError\":false")
                        && ok.body().contains("{\\\"ok\\\":true,\\\"frames\\\":1,\\\"steps\\\":1"));
        check("call: with no `type` in the input — the endpoint supplies it",
                "Probe".equals(server.getWalkthrough().getState().getTitle()));
        check("call: the run heard it, once, with its step", heard.equals(List.of(1)));
        check("call: the plan is kept in the run's directory as plan-1.json",
                Files.readString(planDir.resolve("plan-1.json")).contains("\"title\":\"Probe\""));
        HttpResponse<String> bad = post(url, run.getToken(), call(3, "tools/call", "{\"name\":\"set_plan\",\"arguments\":{\"title\":\"No frames\"}}"));
        check("call: a plan without frames is an error RESULT the agent can read, not an HTTP error",
                bad.statusCode() == 200 && bad.body().contains("\"isError\":true") && bad.body().contains("Invalid plan"));
        check("  and it replaced nothing", "Probe".equals(server.getWalkthrough().getState().getTitle()) && heard.size() == 1);
        HttpResponse<String> undecodable = post(url, run.getToken(), call(4, "tools/call",
                "{\"name\":\"set_plan\",\"arguments\":{\"title\":\"T\",\"frames\":[{\"id\":\"zero\"}]}}"));
        check("call: a plan that does not decode is an error result too",
                undecodable.body().contains("\"isError\":true") && undecodable.body().contains("Invalid plan"));
        HttpResponse<String> other = post(url, run.getToken(), call(5, "tools/call", "{\"name\":\"rm\",\"arguments\":{}}"));
        check("call: any other tool is invalid-params", other.body().contains("-32602") && other.body().contains("Unknown tool"));

        // ── closing ──
        server.closeRun(run);
        check("close: the run's endpoint is gone once it is closed — its token with it",
                post(url, run.getToken(), call(6, "ping", null)).statusCode() == 404);
    }
}
