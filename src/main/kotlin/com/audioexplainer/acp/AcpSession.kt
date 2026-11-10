package com.audioexplainer.acp

import com.audioexplainer.McpEndpoint
import com.audioexplainer.RunEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream

/**
 * **One explain run's ACP session** (`Models.B.md` B2b), over any pair of streams: `initialize` → `session/new` with
 * the run's `set_plan` server → the model, when asked for one → `session/prompt` → its stop reason. The agent's
 * updates go to [onEvents] as [RunEvent]s ([AcpEvents]), its permission requests are answered by [AcpPolicy] (each
 * refusal reported to [onRefused]), and its file reads by [AcpFs] with [readText].
 *
 * No process and no IDE here, so `probes/AcpProbe.java` runs it against a fake agent replaying a recorded session.
 * The process is [AcpHarness]'s.
 */
class AcpSession @JvmOverloads constructor(
    input: InputStream,
    output: OutputStream,
    /** Called on the connection's reader thread, in order; the caller takes its own lock. */
    private val onEvents: (List<RunEvent>) -> Unit,
    private val onRefused: (title: String, why: String) -> Unit,
    private val roots: List<String>,
    private val readText: (String) -> String?,
    log: (String) -> Unit = {},
    scrub: (String) -> String = { it },
    /** A risky call the agent ran without asking ([UnaskedWatch]): what it did, on the reader thread. */
    private val onUnasked: (what: String) -> Unit = {},
) {
    private val events = AcpEvents()
    private val unasked = UnaskedWatch()

    val connection = AcpConnection(input, output, ::onRequest, ::onNotification, log, scrub)

    @Volatile var sessionId: String? = null
        private set
    /** `agentInfo` from `initialize`: "OpenCode 1.19.5". */
    @Volatile var agent: String? = null
        private set
    /** Whether the agent accepts `resource` blocks in a prompt (`promptCapabilities.embeddedContext`). */
    @Volatile var embeddedContext = false
        private set

    /** What the run's `set_plan` endpoint is, for `session/new`. */
    class McpServer(val url: String, val token: String)

    fun start() = connection.start()

    /**
     * The handshake, up to a session: false (with the reason reported as the run's end) when the agent cannot take
     * part — no answer, or no HTTP MCP, which the run's tool needs.
     */
    fun open(cwd: String, server: McpServer, model: String? = null, clientVersion: String = "1"): Boolean {
        try {
            val init = connection.request("initialize", buildJsonObject {
                put("protocolVersion", 1)
                put("clientCapabilities", buildJsonObject {
                    put("fs", buildJsonObject { put("readTextFile", true); put("writeTextFile", false) })
                    put("terminal", false)
                })
                put("clientInfo", buildJsonObject { put("name", "audio-explainer"); put("version", clientVersion) })
            }, INIT_TIMEOUT_MS) as? JsonObject
            val info = init?.obj("agentInfo")
            agent = listOfNotNull(info?.str("title") ?: info?.str("name"), info?.str("version")).joinToString(" ").ifBlank { null }
            val caps = init?.obj("agentCapabilities")
            embeddedContext = caps?.obj("promptCapabilities")?.str("embeddedContext") == "true"
            if (caps?.obj("mcpCapabilities")?.str("http") != "true") {
                fail("${agent ?: "The agent"} cannot connect to an HTTP MCP server, which the run's set_plan tool is")
                return false
            }
            val created = connection.request("session/new", buildJsonObject {
                put("cwd", cwd)
                put("mcpServers", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "http")
                        put("name", McpEndpoint.SERVER_NAME)
                        put("url", server.url)
                        put("headers", buildJsonArray {
                            add(buildJsonObject { put("name", "Authorization"); put("value", "Bearer ${server.token}") })
                        })
                    })
                })
            }, NEW_TIMEOUT_MS) as? JsonObject
            sessionId = created?.str("sessionId") ?: run { fail("The agent started no session"); return false }
            if (!model.isNullOrBlank()) selectModel(created, model.trim())
            return true
        } catch (e: AcpException) {
            fail(e.message ?: "The agent did not start a session")
            return false
        }
    }

    /** Send the prompt and wait for its end; the end is reported as [RunEvent.Finished]. */
    fun prompt(blocks: JsonArray) {
        val sid = sessionId ?: return
        try {
            val result = connection.request("session/prompt", buildJsonObject { put("sessionId", sid); put("prompt", blocks) }) as? JsonObject
            onEvents(events.finished(result))
        } catch (e: AcpException) {
            onEvents(events.finished(null, e.message ?: "The prompt failed"))
        }
    }

    /** Ask the agent to stop the turn; it answers the prompt with `cancelled`. */
    fun cancel() {
        sessionId?.let { sid -> connection.notify("session/cancel", buildJsonObject { put("sessionId", sid) }) }
    }

    private fun fail(why: String) = onEvents(events.finished(null, why))

    /**
     * A model for an agent whose preset cannot set it at launch: through `session/set_config_option` when the session
     * offers a `model` option with that value (opencode), else `session/set_model` when it lists the model (Qwen Code).
     * An agent that offers neither keeps its own.
     */
    private fun selectModel(created: JsonObject?, model: String) {
        val sid = sessionId ?: return
        val option = (created?.get("configOptions") as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.str("id") == "model" || it.str("category") == "model" }
        val values = (option?.get("options") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("value") }.orEmpty()
        val wanted = values.firstOrNull { it == model } ?: values.firstOrNull { it.endsWith("/$model") }
        runCatching {
            if (option != null && wanted != null) {
                connection.request("session/set_config_option", buildJsonObject {
                    put("sessionId", sid); put("configId", option.str("id") ?: "model"); put("value", wanted)
                }, NEW_TIMEOUT_MS)
                return
            }
            val models = (created?.obj("models")?.get("availableModels") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("modelId") }.orEmpty()
            val id = models.firstOrNull { it == model } ?: models.firstOrNull { it.endsWith("/$model") || it.endsWith(":$model") } ?: return
            connection.request("session/set_model", buildJsonObject { put("sessionId", sid); put("modelId", id) }, NEW_TIMEOUT_MS)
        }
    }

    private fun onRequest(method: String, params: JsonObject?): JsonElement = when (method) {
        "session/request_permission" -> {
            val call = params?.get("toolCall") as? JsonObject
            unasked.asked(call)
            val decision = AcpPolicy.decide(call, params?.get("options") as? JsonArray, roots)
            if (!decision.allowed) onRefused(call?.let(::describe) ?: "a request", decision.why)
            AcpPolicy.result(decision)
        }
        "fs/read_text_file" -> AcpFs.read(params, roots, readText)
        else -> throw AcpException(AcpConnection.METHOD_NOT_FOUND, "$method is not supported by this client")
    }

    private fun onNotification(method: String, params: JsonObject?) {
        if (method != "session/update") return
        val update = params?.obj("update")
        onEvents(events.update(update))
        unasked.update(update)?.let(onUnasked)
    }

    companion object {
        /**
         * A prompt's content blocks: the text, then each attached file as an embedded `resource` — `file://` URI,
         * Markdown, its whole text — for an agent that takes them ([embeddedContext]).
         */
        fun blocks(text: String, attached: List<Pair<String, String>> = emptyList()): JsonArray = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", text) })
            for ((path, content) in attached) {
                add(buildJsonObject {
                    put("type", "resource")
                    put("resource", buildJsonObject {
                        put("uri", "file:///" + path.replace('\\', '/').trimStart('/'))
                        put("mimeType", "text/markdown")
                        put("text", content)
                    })
                })
            }
        }

        const val INIT_TIMEOUT_MS = 30_000L
        const val NEW_TIMEOUT_MS = 60_000L

        /** A permission request, short enough for one run-log line: its kind and title, or what it would touch. */
        fun describe(call: JsonObject): String {
            val kind = call.str("kind")
            val title = call.str("title")?.takeIf { !it.startsWith("{") }
            val input = call.obj("rawInput")
            val target = input?.let { i -> sequenceOf("command", "file_path", "filePath", "filepath", "path", "url").mapNotNull { i.str(it) }.firstOrNull() }
            return listOfNotNull(kind?.takeIf { it != "other" }, title ?: target).joinToString(" ").ifBlank { "a tool call" }.take(160)
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    }
}
