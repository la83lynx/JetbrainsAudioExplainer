package com.audioexplainer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * **The one place that knows Claude Code's `stream-json`**: each stdout line of a headless `claude -p` becomes
 * the [RunEvent]s it carries.
 *
 * One instance per run, because the stream is not self-describing line by line: an argument delta names only
 * its block's index within the message, so the parser keeps which tool call each index of the current message is.
 *
 * What it reads: `system` `init` (whether the `set_plan` server connected), `thinking_tokens` and `api_retry`;
 * the partial-message `stream_event`s (block starts, tool-argument deltas, block and message ends); the whole
 * `assistant` messages (tool calls, text, the upstream `provider`); the `user` tool results; and `result`.
 */
class ClaudeStream {
    /** The tool blocks of the message streaming now, by index. */
    private val blocks = HashMap<Int, String>()

    fun parse(line: String): List<RunEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return emptyList()
        val out = ArrayList<RunEvent>(2)
        when (obj.str("type")) {
            "stream_event" -> (obj["event"] as? JsonObject)?.let { streamEvent(it, out) }
            "system" -> when (obj.str("subtype")) {
                "init" -> out += RunEvent.Started(toolStatus(obj))
                "thinking_tokens" -> out += RunEvent.Thinking(tokens = obj.int("estimated_tokens_delta") ?: 0)
                "api_retry" -> out += RunEvent.Retry(obj.int("error_status"), obj.int("attempt"), obj.int("max_retries"), obj.str("error"))
            }
            "assistant" -> {
                val message = obj["message"] as? JsonObject ?: return out
                message.str("provider")?.takeIf { it.isNotBlank() }?.let { out += RunEvent.Host(it) }
                for (b in message.blocks()) {
                    when (b.str("type")) {
                        "tool_use" -> {
                            val name = b.str("name") ?: continue
                            val input = b["input"] as? JsonObject
                            out += RunEvent.ToolCall(b.str("id") ?: "", tool(name, input), input)
                        }
                        "text" -> b.str("text")?.let { out += RunEvent.Text(it) }
                    }
                }
            }
            "user" -> {
                val message = obj["message"] as? JsonObject ?: return out
                for (b in message.blocks()) {
                    if (b.str("type") != "tool_result") continue
                    out += RunEvent.ToolResult(b.str("tool_use_id") ?: "", resultText(b["content"]), b.str("is_error") == "true")
                }
            }
            "result" -> {
                val subtype = obj.str("subtype")
                val flagged = obj.str("is_error") == "true"
                out += RunEvent.Finished(
                    ok = !flagged && (subtype == null || subtype == "success"),
                    finalText = obj.str("result"),
                    turns = obj.int("num_turns"),
                    apiMs = (obj["duration_api_ms"] as? JsonPrimitive)?.longOrNull,
                    outputTokens = (obj["usage"] as? JsonObject)?.int("output_tokens"),
                    costUsd = obj.str("total_cost_usd"),
                    reason = subtype,
                )
            }
        }
        return out
    }

    private fun streamEvent(ev: JsonObject, out: MutableList<RunEvent>) {
        val index = ev.int("index")
        when (ev.str("type")) {
            "message_start" -> blocks.clear()
            "content_block_start" -> {
                val block = ev["content_block"] as? JsonObject
                when (block?.str("type")) {
                    "thinking" -> out += RunEvent.ThinkingStart
                    "tool_use" -> {
                        val id = block.str("id") ?: "#$index"
                        if (index != null) blocks[index] = id
                        out += RunEvent.ToolStart(id, tool(block.str("name"), null))
                    }
                    else -> out += RunEvent.TextStart
                }
            }
            "content_block_delta" -> {
                val id = index?.let(blocks::get) ?: return
                val part = (ev["delta"] as? JsonObject)?.str("partial_json") ?: return
                out += RunEvent.ToolInputDelta(id, part)
            }
            "content_block_stop" -> index?.let(blocks::get)?.let { out += RunEvent.ToolInputEnd(it) }
            "message_stop" -> out += RunEvent.TurnEnd
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Claude Code's tool names, as the readers of a run branch on them. */
        fun tool(name: String?, input: JsonObject?): Tool = when (name) {
            "Read" -> Tool.Read(name, input?.str("file_path"), input?.long("offset"), input?.long("limit"))
            "Write" -> Tool.PlanWrite(name, input?.str("file_path"))
            McpEndpoint.QUALIFIED_TOOL -> Tool.SetPlan(name)
            "Bash", "PowerShell" -> Tool.Shell(name, input?.str("command"))
            "Grep", "Glob" -> Tool.Search(name)
            "Task" -> Tool.Agent(name)
            else -> Tool.Other(name ?: "")
        }

        /**
         * From the `init` event, whether the `set_plan` server connected: [RunReader.CONNECTED], or why not. The CLI
         * lists each server with a `status` — `connected`, `failed`, `needs-auth`, `pending` — and leaves out one a
         * policy refused, so anything but `connected` is a run that cannot send its plan.
         */
        private fun toolStatus(init: JsonObject): String {
            val ours = (init["mcp_servers"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.str("name") == McpEndpoint.SERVER_NAME }
                ?: return "not listed in the run's MCP servers"
            val status = ours.str("status")
            return if (status == "connected") RunReader.CONNECTED else "status ${status ?: "unknown"}"
        }

        /** A `tool_result`'s content: a string, or a list of `{type: text, text}` blocks. */
        private fun resultText(content: Any?): String = when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n")
            else -> ""
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

        private fun JsonObject.blocks(): List<JsonObject> =
            (this["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    }
}
