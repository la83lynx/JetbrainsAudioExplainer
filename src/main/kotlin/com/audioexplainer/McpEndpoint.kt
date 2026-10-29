package com.audioexplainer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/**
 * **The `set_plan` tool, spoken as MCP** — how a headless run hands the plugin its plan (`Speed.md` #3).
 *
 * The run used to `Write` the plan to a file and POST the file with a helper script. A `Write` carries the plan
 * as a JSON *string*, every quote and newline escaped, and the POST was a turn of its own with a tail after it;
 * a tool's input is the plan as plain JSON, and its result is the reply. Spiked over 9 + 12 runs: ~12 s faster,
 * half of it the escaping.
 *
 * Only what Claude Code's HTTP transport needs: Streamable HTTP answered with plain JSON — no SSE stream, no
 * session id, no batches. `initialize`, `ping`, `tools/list` and `tools/call`; a notification gets 202, a GET
 * 405 (so the client knows there is no stream to open), anything else `-32601` — including the CLI's
 * `server/discover` probe, which it sends before `initialize` and is content to see refused.
 *
 * String in, string out, so `probes/McpProbe.java` drives it without a socket. [ExplainerServer] owns the
 * route, the run's token, and what a plan does when it arrives.
 */
object McpEndpoint {
    const val SERVER_NAME = "audio-explainer"
    private const val TOOL_NAME = "set_plan"

    /** The name the CLI gives the tool: what the allow-list grants and what the stream reports. */
    const val QUALIFIED_TOOL = "mcp__${SERVER_NAME}__$TOOL_NAME"

    /** [body] null is an empty response. */
    class Response(val status: Int, val body: String?)

    /** What a `tools/call` came to: the text the agent reads, and whether it is an error result. */
    class ToolResult(val text: String, val isError: Boolean)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Loosely typed on purpose. The schema is `docs/plan-schema.md`, which the agent has read; restating it here
     * would be a second copy to drift, and a strict one would turn a plan the loader *reports on* into a call the
     * CLI refuses before it arrives.
     */
    private val TOOL = buildJsonObject {
        put("name", TOOL_NAME)
        put(
            "description",
            "Send the walkthrough plan to the IDE. The input is the set_plan object documented in the " +
                "audio-explainer skill's docs/plan-schema.md (every top-level field; `type` may be omitted). The " +
                "result is the plugin's reply: {\"ok\":true,\"frames\":N,\"steps\":N} plus an `issues` array whenever " +
                "something was dropped, degraded or over a cap. To fix a plan, call this tool again with the whole " +
                "corrected plan.",
        )
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
                putJsonObject("language") { put("type", "string") }
                putJsonObject("frames") { put("type", "array"); putJsonObject("items") { put("type", "object") } }
                putJsonObject("steps") { put("type", "array"); putJsonObject("items") { put("type", "object") } }
            }
            putJsonArray("required") { add("title"); add("frames"); add("steps") }
            put("additionalProperties", true)
        }
    }

    /** One HTTP request to the run's endpoint. [setPlan] receives the tool's arguments with `type` set. */
    fun handle(method: String, body: String, setPlan: (JsonObject) -> ToolResult): Response {
        if (method != "POST") return Response(405, null)
        val msg = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return Response(400, error(null, PARSE_ERROR, "Parse error: one JSON-RPC message per request"))
        val id = msg["id"]
        val name = (msg["method"] as? JsonPrimitive)?.contentOrNull
            ?: return Response(400, error(id, INVALID_REQUEST, "Invalid request"))
        // A notification (`notifications/initialized` and the like) wants no answer.
        if (id == null) return Response(202, null)
        val params = msg["params"] as? JsonObject
        val result: JsonElement = when (name) {
            "initialize" -> buildJsonObject {
                // Whatever the client speaks: nothing used here differs between the versions it might.
                put("protocolVersion", (params?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull ?: PROTOCOL)
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") { put("name", SERVER_NAME); put("version", "1") }
            }
            "ping" -> buildJsonObject {}
            "tools/list" -> buildJsonObject { putJsonArray("tools") { add(TOOL) } }
            "tools/call" -> {
                val tool = (params?.get("name") as? JsonPrimitive)?.contentOrNull
                if (tool != TOOL_NAME) return Response(200, error(id, INVALID_PARAMS, "Unknown tool: $tool"))
                val args = params?.get("arguments") as? JsonObject
                val outcome = if (args == null) {
                    ToolResult("Invalid plan: the input must be the set_plan object", isError = true)
                } else {
                    setPlan(JsonObject(args + ("type" to JsonPrimitive("set_plan"))))
                }
                buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", outcome.text) })
                    }
                    put("isError", outcome.isError)
                }
            }
            else -> return Response(200, error(id, METHOD_NOT_FOUND, "Method not found"))
        }
        return Response(200, buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString())
    }

    /**
     * The `--mcp-config` document for one run: this server only, at [url], with the run's own [token]. A file
     * rather than an argument, so the token is not on a command line every local process can read.
     */
    fun config(url: String, token: String): String = buildJsonObject {
        putJsonObject("mcpServers") {
            putJsonObject(SERVER_NAME) {
                put("type", "http")
                put("url", url)
                putJsonObject("headers") { put("Authorization", "Bearer $token") }
            }
        }
    }.toString()

    private fun error(id: JsonElement?, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        putJsonObject("error") { put("code", code); put("message", message) }
    }.toString()

    private const val PROTOCOL = "2025-06-18"
    private const val PARSE_ERROR = -32700
    private const val INVALID_REQUEST = -32600
    private const val METHOD_NOT_FOUND = -32601
    private const val INVALID_PARAMS = -32602
}
