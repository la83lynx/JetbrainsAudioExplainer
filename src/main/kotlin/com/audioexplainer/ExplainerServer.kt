package com.audioexplainer

import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** What [ExplainerServer.saveCurrentWalkthrough] wrote: the name it answers to, and the file. */
data class SavedWalkthrough(val name: String, val filePath: String)

/**
 * **One headless run's own way in**: `/mcp/<id>`, answered only to its own [token] and only until
 * [ExplainerServer.closeRun]. The run is never handed the server's token, so nothing it is given outlives it.
 *
 * [planDir] keeps each plan the tool receives, as the POST's payload file used to be kept — the evidence when a
 * run fails. [onPlan] is told about every plan that loads, with its issues, so the run can say it is ready
 * without waiting for the agent to finish.
 */
class McpRun internal constructor(
    val id: String,
    val token: String,
    internal val planDir: Path?,
    internal val onPlan: (ResolvedPlan, List<PlanIssue>) -> Unit,
) {
    internal val calls = java.util.concurrent.atomic.AtomicInteger()

    /**
     * When the agent first said `initialize` here (`System.nanoTime`), or 0: whether the tool connected, as the server
     * saw it. Claude Code also reports it in its `init` event; an ACP agent reports nothing, so this is its only sign.
     */
    @Volatile
    var connectedAt: Long = 0L
        private set

    val connected: Boolean get() = connectedAt != 0L

    internal fun heard(method: String) {
        if (method == "initialize" && connectedAt == 0L) connectedAt = System.nanoTime()
    }
}

/**
 * Loopback HTTP server, port + auth token published to `~/.claude-explainer-{port,token}` for discovery.
 * It began as a byte-for-byte port of code-explainer's `vscode-extension/src/server.ts`; the `set_plan`
 * body is the `frames` + `steps` schema since Phase 2 and the three segment-surgery messages are retired
 * with it (`TreeOfCode.rules.md` Phase 2 decision 10), so the wire contract is this project's own now.
 *
 * Routes (all JSON, auth via `Authorization: Bearer <token>` or `x-auth-token`, `OPTIONS` un-authed):
 *   GET  /api/health        -> {"status":"ok"}
 *   GET  /api/state         -> {title,step,stepCount,status,totalFrames,totalScenes,frame,...}
 *   GET  /api/actions?timeout=<sec>  -> long-poll: 200 UserAction | 204 on timeout (1..120s, default 30)
 *   POST /api/message       -> {"ok":true,"frames":N,"steps":N,"issues":[...]}  (set_plan|goto|resume|stop|ping)
 *   POST /api/save          -> {"ok":true,"filePath":...}
 *   GET  /api/walkthroughs  -> {"walkthroughs":[{name,title,filePath}]}
 *   POST /api/load          -> {"ok":true,"title":...,"frames":N,"steps":N}
 *   POST /mcp/<run>         -> MCP JSON-RPC, one `set_plan` tool ([McpEndpoint]); the run's own token, not this one
 *
 * **`issues` on a `set_plan` reply is the one channel that reaches the agent that authored the plan.** A
 * frame dropped, a cycle cut, a step lighting a line its frame elides — all of it comes back on the POST,
 * because §1b's requirement is that a plan rendering three of its four frames says so. So does what only the
 * code can show — a token not on its line, a file that does not resolve ([PlanSourceCheck]).
 *
 * The upstream WebSocket channel is intentionally NOT ported: it only carried `state` broadcasts
 * and inbound AgentMessages (both redundant with HTTP), no reused client ever connects to it, and
 * its `queueAction` producer is dead code upstream. The JCEF sidebar (Phase 4) bridges via
 * JBCefJSQuery, not WS. Revisit only if a genuine WS client appears.
 */
class ExplainerServer {
    private val log = Logger.getInstance(ExplainerServer::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val token: String = generateToken()

    /** The headless runs with an MCP endpoint open, by the id in their path. */
    private val runs = java.util.concurrent.ConcurrentHashMap<String, McpRun>()

    /** Pending user actions for the `/api/actions` long-poll (FIFO; produced by the sidebar in Phase 4). */
    private val actions = LinkedBlockingDeque<UserAction>()

    /** Walkthrough state machine; Phase 3/4 register a [WalkthroughListener] on it to drive editor/UI. */
    val walkthrough = Walkthrough()

    @Volatile
    private var storage: WalkthroughStorage? = null

    /** The open project's base path — what a relative plan path is read against. Last project wins, like [storage]. */
    @Volatile
    private var workspaceRoot: String? = null

    /**
     * The `echo` string of the last `ping`, exactly as this process decoded it off the wire.
     *
     * Read by [ExplainerDiagnostics]' round-trip check, and it is read here rather than out of the helper's
     * stdout on purpose: a reply printed by `explainer.ps1` goes through the console's output encoding on
     * the way back, so comparing *that* would fail on a correct POST whose printing was lossy. What the
     * check needs to know is what arrived.
     */
    @Volatile
    private var lastPing: String? = null

    /** See [lastPing]. Null until a `ping` has been received. */
    fun lastPingEcho(): String? = lastPing

    private var server: HttpServer? = null

    var port: Int = 0
        private set

    /** Bind the loopback server to an ephemeral port and publish the discovery files. */
    fun start() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "audio-explainer-http").apply { isDaemon = true }
        }
        srv.createContext("/") { ex -> handle(ex) }
        srv.start()

        server = srv
        port = srv.address.port
        writeDiscoveryFiles()
        log.info("Audio Explainer server listening on 127.0.0.1:$port")
    }

    fun stop() {
        server?.stop(0)
        server = null
        deleteDiscoveryFiles()
    }

    /** Point per-workspace save/load at [root] (the open project's base path). Last project wins. */
    fun setWorkspaceRoot(root: Path) {
        storage = WalkthroughStorage(root)
        workspaceRoot = root.toString()
    }

    /** Open a headless run's `set_plan` endpoint; see [McpRun]. Close it with [closeRun] when the process exits. */
    fun openRun(planDir: Path?, onPlan: (ResolvedPlan, List<PlanIssue>) -> Unit): McpRun {
        val run = McpRun(generateToken().take(16), generateToken(), planDir, onPlan)
        runs[run.id] = run
        return run
    }

    /** Revoke [run]'s endpoint and token. */
    fun closeRun(run: McpRun) {
        runs.remove(run.id)
    }

    /** Where [run]'s endpoint answers. */
    fun mcpUrl(run: McpRun): String = "http://127.0.0.1:$port$MCP_PREFIX${run.id}"

    /** Queue a user action for the agent's next `/api/actions` long-poll (Phase 4 sidebar). */
    fun enqueueAction(action: UserAction) {
        actions.offerLast(action)
    }

    // ── In-process helpers for the sidebar (Phase 4) — same storage the HTTP routes use ──

    /**
     * Save the current walkthrough. `name` overrides the file stem (else the title is slugified), and the
     * walkthrough becomes that save (`Saving.md` C) — the next Save writes the same file without asking.
     * Returns what was written, or null if there is no workspace storage or no active walkthrough.
     */
    fun saveCurrentWalkthrough(name: String?): SavedWalkthrough? {
        val storage = this.storage ?: return null
        val state = walkthrough.getState()
        if (state.title.isEmpty() || state.plan.frames.isEmpty()) return null
        val stem = WalkthroughStorage.slugify(name ?: state.title)
        return try {
            val filePath = storage.save(state.plan, stem)
            walkthrough.markSaved(stem, state.plan)
            SavedWalkthrough(stem, filePath)
        } catch (e: Exception) {
            log.warn("sidebar save failed", e)
            null
        }
    }

    /** Whether saving under [name] would replace a file already in `.walkthroughs` (false with no storage). */
    fun savedWalkthroughExists(name: String): Boolean =
        storage?.let { try { it.exists(name) } catch (_: Exception) { false } } ?: false

    /** List saved walkthroughs for the current workspace (empty if none / no storage). */
    fun listSavedWalkthroughs(): List<WalkthroughEntry> =
        storage?.let { try { it.list() } catch (_: Exception) { emptyList() } } ?: emptyList()

    /** Absolute path of the directory walkthroughs are saved in, or null if no workspace storage is set. */
    fun savedWalkthroughsDir(): String? = storage?.directory

    /** Load a saved walkthrough by name into the active walkthrough. Returns false if not found. */
    fun loadSavedWalkthrough(name: String): Boolean {
        val storage = this.storage ?: return false
        val data = storage.load(name) ?: return false
        walkthrough.setPlan(data, savedAs = WalkthroughStorage.slugify(name))
        return true
    }

    /** Delete a saved walkthrough by name (sidebar delete). Returns false if no storage / file absent. */
    fun deleteSavedWalkthrough(name: String): Boolean =
        storage?.let { try { it.delete(name) } catch (_: Exception) { false } } ?: false

    /** Delete every saved walkthrough in the current workspace (sidebar "Delete all"); returns the count. */
    fun deleteAllSavedWalkthroughs(): Int =
        storage?.let { try { it.deleteAll() } catch (_: Exception) { 0 } } ?: 0

    // ── Request dispatch ──────────────────────────────────────────────────────

    private fun handle(ex: HttpExchange) {
        try {
            dispatch(ex)
        } catch (t: Throwable) {
            log.warn("request failed: ${ex.requestMethod} ${ex.requestURI}", t)
            trySend { sendJson(ex, 500, error("Internal error")) }
        } finally {
            ex.close()
        }
    }

    private fun dispatch(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "application/json")
        val method = ex.requestMethod
        val path = ex.requestURI.path

        if (method == "OPTIONS") {
            sendEmpty(ex, 204)
            return
        }
        // Before the server's own auth: a run's endpoint takes the run's token, and only that.
        if (path.startsWith(MCP_PREFIX)) {
            handleMcp(ex, runs[path.removePrefix(MCP_PREFIX)])
            return
        }
        if (!authorized(ex)) {
            sendJson(ex, 401, error("Unauthorized"))
            return
        }

        when {
            method == "GET" && path == "/api/health" -> sendJson(ex, 200, buildJsonObject { put("status", "ok") })
            method == "GET" && path == "/api/state" -> handleGetState(ex)
            method == "GET" && path == "/api/actions" -> handleGetActions(ex)
            method == "POST" && path == "/api/message" -> handleMessage(ex)
            method == "POST" && path == "/api/save" -> handleSave(ex)
            method == "GET" && path == "/api/walkthroughs" -> handleListWalkthroughs(ex)
            method == "POST" && path == "/api/load" -> handleLoad(ex)
            else -> sendJson(ex, 404, error("Not found"))
        }
    }

    private fun handleGetState(ex: HttpExchange) {
        val state = walkthrough.getState()
        val now = walkthrough.currentStepView()
        sendJson(ex, 200, buildJsonObject {
            put("title", state.title)
            // **One position.** `currentSegment` + `currentIndex` + `currentHighlight` were three spellings
            // of a two-level cursor into a projected list that no longer exists; an agent polling this now
            // reads the plan's own step index, which is the same number the sidebar and the tree hold.
            put("step", state.stepIndex)
            put("stepCount", state.stepCount)
            put("status", state.status)
            put("totalFrames", state.plan.frames.size)
            put("totalSteps", state.plan.steps.size)
            put("totalScenes", walkthrough.getResolvedPlan()?.scenes?.size ?: 0)
            put("language", state.language)
            // The step showing, as the sidebar's Now Playing sees it: which frame, its prose, the narration.
            put("frame", if (now != null) json.encodeToJsonElement(now) else JsonNull)
        })
    }

    private fun handleGetActions(ex: HttpExchange) {
        val timeoutMs = parseTimeoutMs(ex.requestURI.rawQuery)
        val action = actions.pollFirst(timeoutMs, TimeUnit.MILLISECONDS)
        if (action == null) {
            sendEmpty(ex, 204)
            return
        }
        try {
            sendJson(ex, 200, json.encodeToJsonElement(action))
        } catch (t: Throwable) {
            actions.offerFirst(action) // client vanished mid-write — don't drop the action
            throw t
        }
    }

    private fun handleMessage(ex: HttpExchange) {
        val body = readBody(ex) ?: return
        val obj = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            sendJson(ex, 400, error("Invalid JSON")); return
        }
        if (!validateAgentMessage(obj)) {
            sendJson(ex, 400, error("Invalid message format")); return
        }
        // **`ping` answers and returns**: it is the one message with no walkthrough in it, added so a
        // diagnostic can drive the real POST path end to end without replacing whatever the reader is
        // currently looking at. `set_plan` could not be used for that — a check that wipes your
        // walkthrough is a rude check.
        if (obj["type"]!!.jsonPrimitive.content == "ping") {
            val echo = stringField(obj, "echo") ?: ""
            lastPing = echo
            sendJson(ex, 200, buildJsonObject { put("ok", true); put("echo", echo) })
            return
        }
        val resolved = try {
            dispatchAgentMessage(obj)
        } catch (e: Exception) {
            // A decode failure here means a malformed payload; the *contents* of a well-formed plan are
            // reported below rather than rejected.
            log.warn("agent message dispatch failed", e)
            sendJson(ex, 400, error("Invalid message format")); return
        }
        sendJson(ex, 200, resolved?.let { planReply(it, replyIssues(it)) } ?: buildJsonObject { put("ok", true) })
    }

    /**
     * The reply to a plan, POSTed or sent with the tool: everything wrong with it, back to the only reader who can
     * fix it — the agent that just wrote it. §1b's second requirement: a plan that renders three of its four
     * frames has to say so, or the fourth reads as a renderer defect. `issues` is absent when the plan was clean,
     * so a well-formed plan's reply is byte-identical to what it always was.
     */
    private fun planReply(resolved: ResolvedPlan, issues: List<PlanIssue>): JsonObject = buildJsonObject {
        put("ok", true)
        put("frames", resolved.frames.size)
        put("steps", resolved.steps.size)
        if (issues.isNotEmpty()) {
            putJsonArray("issues") { issues.forEach { add(it.toString()) } }
        }
    }

    private fun handleMcp(ex: HttpExchange, run: McpRun?) {
        if (run == null) {
            sendJson(ex, 404, error("Not found")); return
        }
        if (ex.requestHeaders.getFirst("Authorization") != "Bearer ${run.token}") {
            sendJson(ex, 401, error("Unauthorized")); return
        }
        val body = readBody(ex) ?: return
        val response = McpEndpoint.handle(ex.requestMethod, body, { plan -> runPlan(run, plan) }, run::heard)
        if (response.body == null) {
            sendEmpty(ex, response.status); return
        }
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(response.status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /**
     * A plan sent with [run]'s tool: the same validation and the same reply a POST gets. A plan that does not
     * decode is an error *result* rather than an HTTP error, so the agent reads why and can send it again.
     */
    private fun runPlan(run: McpRun, obj: JsonObject): McpEndpoint.ToolResult {
        run.planDir?.let { dir ->
            runCatching { Files.writeString(dir.resolve("plan-${run.calls.incrementAndGet()}.json"), obj.toString()) }
                .onFailure { log.info("could not keep the run's plan in $dir", it) }
        }
        if (!validateAgentMessage(obj)) {
            return McpEndpoint.ToolResult("Invalid plan: it needs a string `title` and a `frames` array", isError = true)
        }
        val resolved = try {
            walkthrough.setPlanUnlessShowing(json.decodeFromJsonElement<Plan>(obj))
        } catch (e: Exception) {
            log.warn("set_plan tool: the plan did not decode", e)
            val why = (e.message ?: e.javaClass.simpleName).lineSequence().first().take(300)
            return McpEndpoint.ToolResult("Invalid plan: $why", isError = true)
        }
        val issues = replyIssues(resolved)
        runCatching { run.onPlan(resolved, issues) }.onFailure { log.warn("set_plan tool: the run's listener failed", it) }
        return McpEndpoint.ToolResult(planReply(resolved, issues).toString(), isError = false)
    }

    /**
     * Run one agent message, returning the [ResolvedPlan] when the message loaded one (so the responder can
     * report its issues) and null otherwise.
     *
     * **`insert_after` / `replace_segment` / `remove_segments` are gone** (Phase 2 decision 10): they edited
     * a segment list that is no longer the source of truth, and a live plan is now changed by POSTing a new
     * `set_plan` — which the agent can do, because it is holding the whole plan already.
     */
    private fun dispatchAgentMessage(obj: JsonObject): ResolvedPlan? {
        when (obj["type"]!!.jsonPrimitive.content) {
            "set_plan" -> return walkthrough.setPlanUnlessShowing(json.decodeFromJsonElement<Plan>(obj))
            "goto" -> {
                // `frameId` is the name since Phase 2; `segmentId` is still read as an alias, because an
                // agent holding an old prompt spells it that way. Both resolve the same: the **first step**
                // whose primary frame is that id. A `stepIndex` may also be given, which is exact — an agent
                // that knows the plan's own step order no longer has to name a frame to reach a step.
                val step = obj["stepIndex"]?.jsonPrimitive?.int
                    ?: (obj["frameId"] ?: obj["segmentId"])?.jsonPrimitive?.int
                        ?.let { walkthrough.firstStepOfFrame(it) }
                    ?: return null
                walkthrough.navigateToStep(step)
            }
            "resume" -> walkthrough.play()
            "stop" -> walkthrough.stop()
        }
        return null
    }

    private fun handleSave(ex: HttpExchange) {
        val body = readBody(ex) ?: return
        val storage = this.storage
        if (storage == null) {
            sendJson(ex, 500, error("Storage not available")); return
        }
        val state = walkthrough.getState()
        if (state.title.isEmpty() || state.plan.frames.isEmpty()) {
            sendJson(ex, 400, error("No active walkthrough")); return
        }
        var name: String? = null
        if (body.isNotBlank()) {
            val parsed = try {
                json.parseToJsonElement(body)
            } catch (_: Exception) {
                sendJson(ex, 400, error("Invalid JSON")); return
            }
            name = stringField(parsed as? JsonObject, "name")
        }
        val stem = WalkthroughStorage.slugify(name ?: state.title)
        val filePath = try {
            storage.save(state.plan, stem)
        } catch (e: Exception) {
            log.warn("save failed", e)
            sendJson(ex, 500, error("Failed to save walkthrough")); return
        }
        // The same identity the sidebar's Save gives it: an agent that saved the walkthrough has saved it.
        walkthrough.markSaved(stem, state.plan)
        sendJson(ex, 200, buildJsonObject { put("ok", true); put("filePath", filePath) })
    }

    private fun handleListWalkthroughs(ex: HttpExchange) {
        val storage = this.storage
        val entries = if (storage == null) {
            emptyList()
        } else {
            try {
                storage.list()
            } catch (_: Exception) {
                emptyList()
            }
        }
        sendJson(ex, 200, buildJsonObject {
            putJsonArray("walkthroughs") {
                entries.forEach { e ->
                    add(buildJsonObject {
                        put("name", e.name)
                        put("title", e.title)
                        put("filePath", e.filePath)
                    })
                }
            }
        })
    }

    private fun handleLoad(ex: HttpExchange) {
        val body = readBody(ex) ?: return
        val storage = this.storage
        if (storage == null) {
            sendJson(ex, 500, error("Storage not available")); return
        }
        val parsed = try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            sendJson(ex, 400, error("Invalid JSON")); return
        }
        val name = stringField(parsed as? JsonObject, "name")
        if (name.isNullOrEmpty()) {
            sendJson(ex, 400, error("Missing 'name' field")); return
        }
        val data = storage.load(name)
        if (data == null) {
            sendJson(ex, 404, error("Walkthrough not found")); return
        }
        val resolved = walkthrough.setPlan(data, savedAs = WalkthroughStorage.slugify(name))
        sendJson(ex, 200, buildJsonObject {
            put("ok", true)
            put("title", data.title)
            put("frames", resolved.frames.size)
            put("steps", resolved.steps.size)
            put("language", data.language)
            val issues = replyIssues(resolved)
            if (issues.isNotEmpty()) {
                putJsonArray("issues") { issues.forEach { add(it.toString()) } }
            }
        })
    }

    /**
     * **Load a plan the agent has written but not yet POSTed** — `ClaudeExplainer.EarlyPlan`'s way in, so it goes
     * through the same validation and the same source check a POST would.
     *
     * Returns the loaded plan, or null when it was not loaded: not a `set_plan`, not decodable, empty, or carrying
     * anything above `[info]` — a reply the agent would act on by fixing the plan and POSTing again, so showing
     * this one first would only swap a broken walkthrough for a fixed one in front of the reader.
     */
    fun preload(body: String): ResolvedPlan? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (!validateAgentMessage(obj) || obj["type"]?.jsonPrimitive?.content != "set_plan") return null
        val plan = runCatching { json.decodeFromJsonElement<Plan>(obj) }.getOrNull() ?: return null
        val resolved = PlanLoader.resolve(plan)
        if (resolved.isEmpty() || replyIssues(resolved).any { it.level != IssueLevel.INFO }) return null
        return walkthrough.setPlanUnlessShowing(plan)
    }

    /**
     * Everything wrong with [resolved] **including what only the code can show** — the loader's structural
     * issues, then [PlanSourceCheck]'s: a token not on its line, a line past the end, a file that does not
     * resolve. That second half is what the agent used to check for itself, with a script, on every run.
     *
     * It runs here, on the HTTP thread, because this is where the reply is built and nothing else needs to
     * wait for it: a few files read once each. **A failure costs the check and never the POST**, since the
     * plan is already loaded and showing by the time this runs.
     */
    private fun replyIssues(resolved: ResolvedPlan): List<PlanIssue> {
        val source = try {
            PlanSourceCheck.check(resolved, ProjectSourceLines(workspaceRoot))
        } catch (e: Exception) {
            log.warn("source check failed; replying with the structural issues only", e)
            emptyList()
        }
        for (issue in source) log.warn("plan source: $issue")
        return resolved.issues + source
    }

    // ── Validation (mirrors upstream validateAgentMessage) ─────────────────────

    private fun validateAgentMessage(obj: JsonObject): Boolean {
        val typePrim = obj["type"] as? JsonPrimitive ?: return false
        if (!typePrim.isString) return false
        val type = typePrim.content
        if (type !in VALID_AGENT_MESSAGE_TYPES) return false
        return when (type) {
            // `steps` is not required here: a plan with frames and no steps renders as structure with no
            // narration, which the loader reports (`plan.no-steps`) rather than rejects. Rejecting at the
            // wire level would turn a reportable authoring mistake into a 400 with no detail.
            "set_plan" -> isJsonString(obj["title"]) && obj["frames"] is JsonArray
            "goto" -> isJsonNumber(obj["frameId"]) || isJsonNumber(obj["segmentId"]) ||
                isJsonNumber(obj["stepIndex"])
            // `echo` is optional and free-form; a `ping` with none is still a valid liveness poke.
            "ping" -> true
            "resume", "stop" -> true
            else -> false
        }
    }

    // ── Auth ───────────────────────────────────────────────────────────────────

    private fun authorized(ex: HttpExchange): Boolean {
        val headers = ex.requestHeaders
        if (headers.getFirst("Authorization") == "Bearer $token") return true
        if (headers.getFirst("x-auth-token") == token) return true
        return false
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    /** Reads the request body, enforcing the 1 MB cap (sends 413 and returns null if exceeded). */
    private fun readBody(ex: HttpExchange): String? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        ex.requestBody.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                total += n
                if (total > MAX_BODY_SIZE) {
                    sendJson(ex, 413, error("Request body too large"))
                    return null
                }
                out.write(buf, 0, n)
            }
        }
        return out.toString(StandardCharsets.UTF_8)
    }

    private fun sendJson(ex: HttpExchange, code: Int, body: JsonElement) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        // Say the charset. A client left to guess uses the HTTP default (ISO-8859-1), and the
        // reply quotes the plan's own text back in `issues` — which in a non-English
        // walkthrough is entirely non-ASCII.
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sendEmpty(ex: HttpExchange, code: Int) {
        ex.sendResponseHeaders(code, -1)
    }

    private inline fun trySend(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            // response already committed / client gone
        }
    }

    // ── Discovery files ─────────────────────────────────────────────────────

    private fun writeDiscoveryFiles() {
        val home = homeDir()
        Files.writeString(home.resolve(PORT_FILE), port.toString())
        val tokenFile = home.resolve(TOKEN_FILE)
        Files.writeString(tokenFile, token)
        restrictToOwner(tokenFile)
    }

    private fun deleteDiscoveryFiles() {
        try {
            val home = homeDir()
            Files.deleteIfExists(home.resolve(PORT_FILE))
            Files.deleteIfExists(home.resolve(TOKEN_FILE))
        } catch (_: IOException) {
        }
    }

    /** Best-effort owner-only permissions on POSIX; a no-op on Windows (token lives in the user home). */
    private fun restrictToOwner(file: Path) {
        try {
            val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            Files.setPosixFilePermissions(file, perms)
        } catch (_: Exception) {
            // Non-POSIX filesystem (Windows) — skip.
        }
    }

    private fun homeDir(): Path = Path.of(System.getProperty("user.home"))

    private fun error(message: String): JsonElement = buildJsonObject { put("error", message) }

    companion object {
        private const val MAX_BODY_SIZE = 1024 * 1024 // 1 MB
        private const val MAX_LONG_POLL_TIMEOUT_MS = 120_000L
        private const val MIN_LONG_POLL_TIMEOUT_MS = 1_000L
        private const val PORT_FILE = ".claude-explainer-port"
        private const val TOKEN_FILE = ".claude-explainer-token"
        private const val MCP_PREFIX = "/mcp/"

        private val VALID_AGENT_MESSAGE_TYPES = setOf("set_plan", "goto", "resume", "stop", "ping")

        private fun generateToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** `?timeout=<sec>` → ms, matching upstream: default 30s, clamped to [1s, 120s]. */
        private fun parseTimeoutMs(rawQuery: String?): Long {
            val seconds = rawQuery
                ?.split('&')
                ?.firstOrNull { it.startsWith("timeout=") }
                ?.substringAfter('=')
                ?.toIntOrNull()
                ?: 30
            return (seconds * 1000L).coerceIn(MIN_LONG_POLL_TIMEOUT_MS, MAX_LONG_POLL_TIMEOUT_MS)
        }

        private fun isJsonString(e: JsonElement?): Boolean = (e as? JsonPrimitive)?.isString == true

        private fun isJsonNumber(e: JsonElement?): Boolean {
            val p = e as? JsonPrimitive ?: return false
            return !p.isString && p.doubleOrNull != null
        }

        private fun stringField(obj: JsonObject?, key: String): String? {
            val p = obj?.get(key) as? JsonPrimitive ?: return null
            return if (p.isString) p.content else null
        }
    }
}
