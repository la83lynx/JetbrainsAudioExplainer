package com.audioexplainer

import kotlinx.serialization.json.JsonObject

/**
 * **What an explain run did, in words that are not any one agent's** — the events the progress bar
 * ([ExplainProgress]), the run log ([RunLog]) and the run's own bookkeeping ([RunReader]) read.
 *
 * One parser per harness makes them: [ClaudeStream] from Claude Code's `stream-json`, and the ACP client from
 * `session/update`. Nothing downstream of that parser knows which agent ran. What a harness cannot report is
 * simply not sent — ACP streams no tool arguments, so a run there has no [ToolInputDelta]; and Claude's
 * thinking arrives as token counts, ACP's as text.
 */
sealed interface RunEvent {
    /** The agent is up. [toolStatus] is [RunReader.CONNECTED] when the `set_plan` server connected, else why not; null = this harness cannot tell yet. */
    data class Started(val toolStatus: String?) : RunEvent

    /** A thinking block began. */
    data object ThinkingStart : RunEvent

    /** More thinking: Claude reports [tokens], ACP agents stream [chars]. */
    data class Thinking(val tokens: Int = 0, val chars: Int = 0) : RunEvent

    /** A block that is neither thinking nor a tool call began — text, in practice. It ends a think. */
    data object TextStart : RunEvent

    /** The model's turn ended (`message_stop`). */
    data object TurnEnd : RunEvent

    /** A whole text block the model wrote. */
    data class Text(val text: String) : RunEvent

    /** A tool call began; its input is still to come. */
    data class ToolStart(val id: String, val tool: Tool) : RunEvent

    /** Some of [ToolStart]'s input, as it streams — Claude only. */
    data class ToolInputDelta(val id: String, val partialJson: String) : RunEvent

    /** [ToolStart]'s input has all streamed — Claude only. */
    data class ToolInputEnd(val id: String) : RunEvent

    /** A tool call with its whole input: the call is being made. */
    data class ToolCall(val id: String, val tool: Tool, val input: JsonObject?) : RunEvent

    /** What a tool call returned. */
    data class ToolResult(val id: String, val text: String, val failed: Boolean) : RunEvent

    /** The upstream host that served the turn — Claude Code through OpenRouter only. */
    data class Host(val name: String) : RunEvent

    /** The agent is about to retry a failed API call. */
    data class Retry(val status: Int?, val attempt: Int?, val maxRetries: Int?, val error: String?) : RunEvent {
        /** A rejected key: the CLI would retry it 10 times with backoff to ~40 s, about 3 minutes for nothing. */
        val authRejected: Boolean get() = status == 401 || status == 403
    }

    /**
     * The run ended, with its own accounting. Every field but [ok] is what the agent reported, or null where it
     * reports nothing; [costUsd] is the number exactly as the agent printed it.
     */
    data class Finished(
        val ok: Boolean,
        val finalText: String?,
        val turns: Int? = null,
        val apiMs: Long? = null,
        val outputTokens: Int? = null,
        val costUsd: String? = null,
        val reason: String? = null,
    ) : RunEvent
}

/**
 * **A tool call, as the readers of a run branch on it** — instead of on Claude's tool names. [raw] is the name the
 * agent used, for logs.
 */
sealed interface Tool {
    val raw: String

    /** A file read; [path] null when the call names none (yet). */
    data class Read(override val raw: String, val path: String?, val offset: Long? = null, val limit: Long? = null) : Tool

    /** A search of the code: grep, glob. */
    data class Search(override val raw: String) : Tool

    /** A sub-agent exploring on the model's behalf. */
    data class Agent(override val raw: String) : Tool

    /** The run's own `set_plan` tool. */
    data class SetPlan(override val raw: String) : Tool

    /** Claude's `Write` of the plan file it then POSTs. */
    data class PlanWrite(override val raw: String, val path: String?) : Tool

    /** A shell command. */
    data class Shell(override val raw: String, val command: String?) : Tool

    data class Other(override val raw: String) : Tool

    /** The call that carries the plan: its `set_plan`, or the `Write` of it. */
    val isPlan: Boolean get() = this is SetPlan || this is PlanWrite
}
