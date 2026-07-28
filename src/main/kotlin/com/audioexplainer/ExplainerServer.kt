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

/**
 * Loopback HTTP server reproducing code-explainer's `vscode-extension/src/server.ts` wire contract
 * byte-for-byte, so the reused agent skill (`explainer.sh` + SKILL.md + docs) works unchanged.
 * Port + auth token are published to `~/.claude-explainer-{port,token}` for discovery.
 *
 * Routes (all JSON, auth via `Authorization: Bearer <token>` or `x-auth-token`, `OPTIONS` un-authed):
 *   GET  /api/health        -> {"status":"ok"}
 *   GET  /api/state         -> {title,currentSegment,status,totalSegments,currentIndex,segment}
 *   GET  /api/actions?timeout=<sec>  -> long-poll: 200 UserAction | 204 on timeout (1..120s, default 30)
 *   POST /api/message       -> {"ok":true}  (set_plan|insert_after|replace_segment|remove_segments|goto|resume|stop)
 *   POST /api/save          -> {"ok":true,"filePath":...}
 *   GET  /api/walkthroughs  -> {"walkthroughs":[{name,title,filePath}]}
 *   POST /api/load          -> {"ok":true,"title":...,"segments":N}
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

    /** Pending user actions for the `/api/actions` long-poll (FIFO; produced by the sidebar in Phase 4). */
    private val actions = LinkedBlockingDeque<UserAction>()

    /** Walkthrough state machine; Phase 3/4 register a [WalkthroughListener] on it to drive editor/UI. */
    val walkthrough = Walkthrough()

    @Volatile
    private var storage: WalkthroughStorage? = null

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
    }

    /** Queue a user action for the agent's next `/api/actions` long-poll (Phase 4 sidebar). */
    fun enqueueAction(action: UserAction) {
        actions.offerLast(action)
    }

    // ── In-process helpers for the sidebar (Phase 4) — same storage the HTTP routes use ──

    /**
     * Save the current walkthrough. `name` overrides the file stem (else the title is slugified).
     * Returns the absolute file path, or null if there is no workspace storage / no active walkthrough.
     */
    fun saveCurrentWalkthrough(name: String?): String? {
        val storage = this.storage ?: return null
        val state = walkthrough.getState()
        if (state.title.isEmpty() || state.segments.isEmpty()) return null
        return try {
            storage.save(state.title, state.segments, state.language, name)
        } catch (e: Exception) {
            log.warn("sidebar save failed", e)
            null
        }
    }

    /** List saved walkthroughs for the current workspace (empty if none / no storage). */
    fun listSavedWalkthroughs(): List<WalkthroughEntry> =
        storage?.let { try { it.list() } catch (_: Exception) { emptyList() } } ?: emptyList()

    /** Absolute path of the directory walkthroughs are saved in, or null if no workspace storage is set. */
    fun savedWalkthroughsDir(): String? = storage?.directory

    /** Load a saved walkthrough by name into the active walkthrough. Returns false if not found. */
    fun loadSavedWalkthrough(name: String): Boolean {
        val storage = this.storage ?: return false
        val data = storage.load(name) ?: return false
        walkthrough.setPlan(data.title, data.segments, data.language)
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
        val currentSeg = state.segments.getOrNull(state.currentIndex)
        sendJson(ex, 200, buildJsonObject {
            put("title", state.title)
            put("currentSegment", currentSeg?.id ?: -1)
            put("status", state.status)
            put("totalSegments", state.segments.size)
            put("currentIndex", state.currentIndex)
            put("language", state.language)
            put("segment", if (currentSeg != null) json.encodeToJsonElement(currentSeg) else JsonNull)
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
        try {
            dispatchAgentMessage(obj)
        } catch (e: Exception) {
            // Upstream trusts segment internals; a decode failure here means a malformed payload.
            log.warn("agent message dispatch failed", e)
            sendJson(ex, 400, error("Invalid message format")); return
        }
        sendJson(ex, 200, buildJsonObject { put("ok", true) })
    }

    private fun dispatchAgentMessage(obj: JsonObject) {
        when (obj["type"]!!.jsonPrimitive.content) {
            "set_plan" -> {
                val plan = json.decodeFromJsonElement<Plan>(obj)
                walkthrough.setPlan(plan.title, plan.segments, plan.language)
            }
            "insert_after" -> {
                val after = obj["afterSegment"]!!.jsonPrimitive.int
                val segs = json.decodeFromJsonElement<List<Segment>>(obj["segments"]!!)
                walkthrough.insertAfter(after, segs)
            }
            "replace_segment" -> {
                val id = obj["id"]!!.jsonPrimitive.int
                val seg = json.decodeFromJsonElement<Segment>(obj["segment"]!!)
                walkthrough.replaceSegment(id, seg)
            }
            "remove_segments" -> {
                val ids = json.decodeFromJsonElement<List<Int>>(obj["ids"]!!)
                walkthrough.removeSegments(ids)
            }
            "goto" -> walkthrough.navigateTo(obj["segmentId"]!!.jsonPrimitive.int)
            "resume" -> walkthrough.play()
            "stop" -> walkthrough.stop()
        }
    }

    private fun handleSave(ex: HttpExchange) {
        val body = readBody(ex) ?: return
        val storage = this.storage
        if (storage == null) {
            sendJson(ex, 500, error("Storage not available")); return
        }
        val state = walkthrough.getState()
        if (state.title.isEmpty() || state.segments.isEmpty()) {
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
        val filePath = try {
            storage.save(state.title, state.segments, state.language, name)
        } catch (e: Exception) {
            log.warn("save failed", e)
            sendJson(ex, 500, error("Failed to save walkthrough")); return
        }
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
        walkthrough.setPlan(data.title, data.segments, data.language)
        sendJson(ex, 200, buildJsonObject {
            put("ok", true)
            put("title", data.title)
            put("segments", data.segments.size)
            put("language", data.language)
        })
    }

    // ── Validation (mirrors upstream validateAgentMessage) ─────────────────────

    private fun validateAgentMessage(obj: JsonObject): Boolean {
        val typePrim = obj["type"] as? JsonPrimitive ?: return false
        if (!typePrim.isString) return false
        val type = typePrim.content
        if (type !in VALID_AGENT_MESSAGE_TYPES) return false
        return when (type) {
            "set_plan" -> isJsonString(obj["title"]) && obj["segments"] is JsonArray
            "insert_after" -> isJsonNumber(obj["afterSegment"]) && obj["segments"] is JsonArray
            "replace_segment" -> isJsonNumber(obj["id"]) && obj["segment"] is JsonObject
            "remove_segments" -> obj["ids"] is JsonArray
            "goto" -> isJsonNumber(obj["segmentId"])
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

        private val VALID_AGENT_MESSAGE_TYPES = setOf(
            "set_plan", "insert_after", "replace_segment", "remove_segments", "goto", "resume", "stop",
        )

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
