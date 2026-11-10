package com.audioexplainer

import com.intellij.openapi.util.text.StringUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * **One run's events, read the same way whichever harness produced them**: each one goes to
 * the progress bar and the run log, and this keeps what the run itself needs — its timeline in `idea.log`, the
 * plan handed over the moment it is written, the result, whether the `set_plan` tool connected, and a
 * provider's retries.
 *
 * **The timeline** is a line per tool call with its elapsed time, and a closing line with the run's own
 * accounting. It exists because a sandbox round could not answer the two questions every speed measurement asks: how
 * long the run took, and what it spent the time on. INFO, so it reaches `idea.log` and stays out of the `runIde`
 * console.
 *
 * Fed under the harness's stream lock ([onLine]); the fields the run loop polls from its own thread are volatile.
 * [now] is injected so a recorded run can be replayed (`probes/RunEventGolden.java`).
 */
class RunReader(
    private val progress: ExplainProgress,
    private val runLog: RunLog,
    /** The provider profile's name, or null for Claude Code's own setup. */
    private val provider: String?,
    /** Whether the run was asked to send its plan with the `set_plan` tool, so the tool's status matters. */
    private val needsTool: Boolean,
    /** The whole plan, the moment a `Write` of it is in the stream — 10-15 s before the agent's POST lands. */
    private val onPlanWritten: (String) -> Unit,
    /** Where the timeline goes: `idea.log`, at INFO. */
    private val info: (String) -> Unit,
    private val now: () -> Double = { System.nanoTime() / 1e9 },
    /**
     * Whether the run's `set_plan` endpoint has heard the agent's `initialize` ([McpRun.connected]) — for a harness that
     * reports nothing about its MCP servers (ACP). Asked once, at the first tool call: an agent connects its servers
     * at the start of the session, so one that is calling tools and has not connected never will. Null: the harness
     * says itself ([RunEvent.Started]).
     */
    private val toolConnected: (() -> Boolean)? = null,
) {
    private val started = now()

    /** Tool calls so far. */
    var tools = 0
        private set

    @Volatile var finalText: String? = null
        private set
    @Volatile var isError = false
        private set
    /** [CONNECTED], why the `set_plan` server did not connect, or null until the harness has said. */
    @Volatile var toolStatus: String? = null
        private set
    /** The first retry that rejected the key: the run is stopped on it. */
    @Volatile var authStop: RunEvent.Retry? = null
        private set
    /** "Provider busy, retrying (2/10)…" while a provider retries; it takes the progress line's place. */
    @Volatile var retryNote: String? = null
        private set

    /** Seconds since the run started, for log lines. */
    fun at(): String = "%.1fs".format(now() - started)

    /**
     * The events of one line of the agent's output, in order. A line that is not a retry clears [retryNote]: the
     * provider answered.
     */
    fun onLine(events: List<RunEvent>) {
        var retried = false
        for (e in events) {
            progress.onEvent(e)
            runLog.onEvent(e)
            when (e) {
                is RunEvent.Started -> if (needsTool && toolStatus == null) toolStatus = e.toolStatus
                is RunEvent.ToolStart -> askServer()
                is RunEvent.ToolCall -> {
                    askServer()
                    tools++
                    info("explain run +${at()} ${e.tool.raw}${target(e.input)}")
                    val tool = e.tool
                    if (tool is Tool.PlanWrite && tool.path?.endsWith(".json") == true) {
                        (e.input?.get("content") as? JsonPrimitive)?.contentOrNull?.let(onPlanWritten)
                    }
                }
                is RunEvent.Retry -> if (provider != null) {
                    retried = true
                    if (e.authRejected) {
                        if (authStop == null) authStop = e
                    } else {
                        val note = "$provider busy (${e.status ?: e.error ?: "error"}), " +
                            "retrying (${e.attempt ?: "?"}/${e.maxRetries ?: "?"})…"
                        retryNote = note
                        runLog.retry(note)
                    }
                }
                is RunEvent.Finished -> finished(e)
                else -> {}
            }
        }
        if (!retried) retryNote = null
    }

    private fun askServer() {
        val connected = toolConnected ?: return
        if (needsTool && toolStatus == null) toolStatus = if (connected()) CONNECTED else "the agent never connected to it"
    }

    private fun finished(e: RunEvent.Finished) {
        // Off Claude the CLI still prices the tokens as Claude's ($0.68-2.02 for runs that cost $0-0.03 when
        // tested), so the number would only mislead.
        val cost = if (provider != null) "cost n/a ($provider)" else e.costUsd?.let { "cost \$$it" }
        val turns = e.turns?.let { "$it turn(s)" }
        val tokens = e.outputTokens?.let { "$it output token(s)" }
        info(
            "explain run finished at ${at()}: " +
                listOfNotNull("$tools tool call(s)", turns, e.apiMs?.let { "api $it ms" }, tokens, cost, e.reason).joinToString(", "),
        )
        runLog.note("Finished: " + listOfNotNull(turns, "$tools tool call(s)", tokens, cost).joinToString(", "))
        finalText = e.finalText
        if (!e.ok) isError = true
    }

    /** What a tool call acted on, short enough for one log line: its file, command or pattern. */
    private fun target(input: JsonObject?): String {
        if (input == null) return ""
        val raw = sequenceOf("file_path", "filePath", "command", "pattern", "path")
            .mapNotNull { (input[it] as? JsonPrimitive)?.contentOrNull }
            .firstOrNull() ?: return ""
        return ": " + StringUtil.shortenTextWithEllipsis(raw.trim().replace('\n', ' '), 160, 60)
    }

    companion object {
        const val CONNECTED = "connected"
    }
}
