package com.audioexplainer.acp

import com.audioexplainer.RunEvent
import com.audioexplainer.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * **An ACP agent's `session/update`s as [RunEvent]s** — the second parser beside
 * [com.audioexplainer.ClaudeStream], one instance per run.
 *
 * | update | events |
 * |---|---|
 * | `agent_thought_chunk` | [RunEvent.ThinkingStart] on the first after anything else, then [RunEvent.Thinking] with its characters |
 * | `agent_message_chunk` | [RunEvent.TextStart] on the first; the chunks are kept and sent as one [RunEvent.Text] when something else arrives |
 * | `tool_call` | [RunEvent.ToolStart] |
 * | `tool_call_update` with `rawInput` | [RunEvent.ToolCall], once per call; for the plan, its whole input as one [RunEvent.ToolInputDelta] and [RunEvent.ToolInputEnd] first |
 * | `tool_call_update` `completed` / `failed` | [RunEvent.ToolResult] |
 * | `session/prompt`'s result | [RunEvent.Finished] ([finished]) |
 *
 * **Tool arguments do not stream over ACP**: an agent sends a call's input whole. So the plan's "writing" is the
 * time from the call's start to its input, and its size arrives in one delta — which is what lets the progress
 * bar learn a shape for an ACP run exactly as it does for Claude's.
 *
 * **A call's name can arrive late.** Qwen Code starts the `set_plan` call as a generic `tool_call` and names it in
 * a later update; when a call turns out to be the plan, it is started again as one.
 */
class AcpEvents {
    private val tools = HashMap<String, Tool>()
    private val called = HashSet<String>()
    private val done = HashSet<String>()
    private var thinking = false
    private val text = StringBuilder()
    private var lastText: String? = null

    fun update(u: JsonObject?): List<RunEvent> {
        if (u == null) return emptyList()
        val out = ArrayList<RunEvent>(2)
        when (u.str("sessionUpdate")) {
            "agent_thought_chunk" -> {
                val t = chunkText(u)
                if (t.isEmpty()) return out
                flushText(out)
                if (!thinking) { thinking = true; out += RunEvent.ThinkingStart }
                out += RunEvent.Thinking(chars = t.length)
            }
            "agent_message_chunk" -> {
                val t = chunkText(u)
                if (t.isEmpty()) return out
                thinking = false
                if (text.isEmpty()) out += RunEvent.TextStart
                text.append(t)
            }
            "tool_call" -> {
                val id = u.str("toolCallId") ?: return out
                flushText(out)
                thinking = false
                val tool = identify(u, null)
                tools[id] = tool
                out += RunEvent.ToolStart(id, tool)
                input(u)?.let { call(id, tool, it, out) }
                finish(id, u, out)
            }
            "tool_call_update" -> {
                val id = u.str("toolCallId") ?: return out
                val prev = tools[id]
                val tool = identify(u, prev)
                tools[id] = tool
                if (prev == null) {
                    flushText(out)
                    thinking = false
                    out += RunEvent.ToolStart(id, tool)
                } else if (!prev.isPlan && tool.isPlan && id !in called) {
                    out += RunEvent.ToolStart(id, tool)
                }
                input(u)?.let { call(id, tool, it, out) }
                finish(id, u, out)
            }
        }
        return out
    }

    /** The prompt's answer: `{stopReason, usage?}`, or [error] when it failed. */
    fun finished(result: JsonObject?, error: String? = null): List<RunEvent> {
        val out = ArrayList<RunEvent>(2)
        flushText(out)
        if (error != null) {
            out += RunEvent.Finished(ok = false, finalText = error, reason = "error")
            return out
        }
        val reason = result?.str("stopReason")
        val usage = result?.get("usage") as? JsonObject
        out += RunEvent.Finished(
            ok = reason == "end_turn",
            finalText = lastText,
            outputTokens = (usage?.get("outputTokens") as? JsonPrimitive)?.intOrNull,
            reason = reason,
        )
        return out
    }

    private fun call(id: String, tool: Tool, input: JsonObject, out: MutableList<RunEvent>) {
        if (!called.add(id)) return
        val full = identify(null, tool, input)
        tools[id] = full
        if (full.isPlan) {
            out += RunEvent.ToolInputDelta(id, input.toString())
            out += RunEvent.ToolInputEnd(id)
        }
        out += RunEvent.ToolCall(id, full, input)
    }

    private fun finish(id: String, u: JsonObject, out: MutableList<RunEvent>) {
        val status = u.str("status")
        if (status != "completed" && status != "failed") return
        if (!done.add(id)) return
        // A call that reports its end without ever sending its input still happened.
        if (id !in called) {
            called += id
            out += RunEvent.ToolCall(id, tools[id] ?: Tool.Other(""), null)
        }
        out += RunEvent.ToolResult(id, resultText(u), failed = status == "failed")
    }

    private fun flushText(out: MutableList<RunEvent>) {
        if (text.isEmpty()) return
        val t = text.toString()
        text.setLength(0)
        lastText = t
        out += RunEvent.Text(t)
    }

    companion object {
        /**
         * What a call is, from what the agent has said of it so far. The name is the agent's own (`_meta.toolName`,
         * else the title it first gave); the plan is recognised by any name that names it.
         */
        internal fun identify(u: JsonObject?, prev: Tool?, input: JsonObject? = u?.let(::input)): Tool {
            val names = u?.let(AcpPolicy::names).orEmpty()
            names.firstOrNull(AcpPolicy::namesSetPlan)?.let { return Tool.SetPlan(it.substringBefore(':').trim()) }
            if (prev is Tool.SetPlan) return prev
            val kind = u?.str("kind") ?: kindOf(prev)
            val raw = prev?.raw?.takeIf { it.isNotEmpty() }
                ?: ((u?.get("_meta") as? JsonObject)?.str("toolName") ?: u?.str("title") ?: kind ?: "")
            val locations = (u?.get("locations") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            return when (kind) {
                "read" -> Tool.Read(
                    raw,
                    path = input?.firstStr("file_path", "filePath", "absolute_path", "path")
                        ?: locations.firstNotNullOfOrNull { it.str("path") } ?: (prev as? Tool.Read)?.path,
                    offset = input?.long("offset") ?: (prev as? Tool.Read)?.offset,
                    limit = input?.long("limit") ?: (prev as? Tool.Read)?.limit,
                )
                "search" -> Tool.Search(raw)
                "execute" -> Tool.Shell(raw, input?.firstStr("command", "cmd") ?: (prev as? Tool.Shell)?.command)
                else -> Tool.Other(raw)
            }
        }

        private fun kindOf(t: Tool?): String? = when (t) {
            is Tool.Read -> "read"
            is Tool.Search -> "search"
            is Tool.Shell -> "execute"
            else -> null
        }

        private fun input(u: JsonObject): JsonObject? = (u["rawInput"] as? JsonObject)?.takeIf { it.isNotEmpty() }

        private fun chunkText(u: JsonObject): String = ((u["content"] as? JsonObject)?.str("text")).orEmpty()

        /** A call's result: its text content blocks, else its `rawOutput` (a string, or opencode's `{output}`). */
        internal fun resultText(u: JsonObject): String {
            val content = (u["content"] as? JsonArray)?.mapNotNull { block ->
                val b = block as? JsonObject ?: return@mapNotNull null
                ((b["content"] as? JsonObject) ?: b).takeIf { it.str("type") == "text" }?.str("text")
            }.orEmpty()
            if (content.isNotEmpty()) return content.joinToString("\n")
            return when (val raw = u["rawOutput"]) {
                is JsonPrimitive -> raw.contentOrNull.orEmpty()
                is JsonObject -> raw.str("output") ?: raw.toString()
                else -> ""
            }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.firstStr(vararg keys: String): String? = keys.firstNotNullOfOrNull { str(it) }

        private fun JsonObject.long(key: String): Long? = str(key)?.toDoubleOrNull()?.toLong()
    }
}
