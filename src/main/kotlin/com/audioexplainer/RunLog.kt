package com.audioexplainer

import com.intellij.openapi.util.text.StringUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **One run, as the user can read it**: a line per thing the model did — each read with its range, each think
 * with how long it took, each `set_plan` with the reply and its issues, the host that served each turn, retries,
 * and how it ended. The status bar and the sidebar show one line that the next one overwrites; this keeps them
 * all, for the run log window ([RunLogWindow]).
 *
 * **The loop warning.** A model that has lost the task re-reads what it already has: two runs seen looping read
 * the schema doc again 6 and 7 times, while clean runs re-read nothing and Claude's runs at most 3 times (67
 * transcripts, 2026-11-06). A `Read` that overlaps a range already read counts as a re-read; at [REREAD_WARN]
 * the run gets a [warning], which the status bar and the banner show beside Stop.
 *
 * Fed from the run's reader under its lock ([onEvent]) and from the run's own code ([note], [end]); listeners
 * are called on that thread and must marshal UI work themselves. [now] is injected so a probe can replay a run.
 */
class RunLog(val title: String, private val now: () -> Double = { System.nanoTime() / 1e9 }) {

    interface Listener {
        fun onLine(line: String)
        fun onEnd(ok: Boolean, summary: String)
    }

    private val started = now()
    private val lines = ArrayList<String>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Set once the model has re-read [REREAD_WARN] times; it says so in a line short enough for a status bar. */
    @Volatile var warning: String? = null
        private set
    @Volatile var rereads = 0
        private set
    /** The run's verdict once [end] has run: ok, and its one-line summary. The window prints it, not [lines]. */
    @Volatile var ended: Pair<Boolean, String>? = null
        private set

    private var host: String? = null
    private var thinkingTokens = 0
    private var thinkFrom = Double.NaN
    private var thinkTokensFrom = 0
    private var thinkingChars = 0
    private var thinkCharsFrom = 0
    private val tools = HashMap<String, Tool>()
    private val reads = HashMap<String, MutableList<LongRange>>()
    private var lastRetry: String? = null

    /**
     * Replay every line so far to [l] and add it, under the same lock, so a window opened mid-run misses nothing
     * and gets nothing twice; a finished run's verdict comes with it.
     */
    fun subscribe(l: Listener) {
        synchronized(this) {
            lines.forEach { runCatching { l.onLine(it) } }
            listeners.add(l)
            ended?.let { (ok, summary) -> runCatching { l.onEnd(ok, summary) } }
        }
    }

    fun unsubscribe(l: Listener) {
        listeners.remove(l)
    }

    fun snapshot(): List<String> = synchronized(this) { ArrayList(lines) }

    /** A line the run's own code writes: the start, a retry, a stop. */
    fun note(text: String) = add(now(), text)

    /** A provider retry, once per attempt — the CLI repeats the same one on every backoff tick. */
    fun retry(text: String) {
        if (text == lastRetry) return
        lastRetry = text
        note(text)
    }

    fun end(ok: Boolean, summary: String) {
        synchronized(this) {
            if (ended != null) return
            ended = ok to summary
        }
        closeThinking()
        listeners.forEach { runCatching { it.onEnd(ok, summary) } }
    }

    /** One event of the run. */
    fun onEvent(e: RunEvent) {
        when (e) {
            is RunEvent.ThinkingStart -> if (thinkFrom.isNaN()) {
                thinkFrom = now(); thinkTokensFrom = thinkingTokens; thinkCharsFrom = thinkingChars
            }
            is RunEvent.ToolStart, is RunEvent.TextStart, is RunEvent.TurnEnd -> closeThinking()
            is RunEvent.Thinking -> { thinkingTokens += e.tokens; thinkingChars += e.chars }
            is RunEvent.Host -> if (e.name != host) {
                host = e.name
                note("Host: ${e.name}")
            }
            is RunEvent.ToolCall -> {
                closeThinking()
                if (e.id.isNotEmpty()) tools[e.id] = e.tool
                val before = rereads
                note(toolLine(e.tool, e.input))
                if (before < REREAD_WARN && rereads >= REREAD_WARN) {
                    note("⚠ The model keeps re-reading what it already has, so it may have lost the task. " +
                        "Stop it, or let it finish and check the walkthrough.")
                }
            }
            is RunEvent.Text -> e.text.trim().takeIf { it.isNotEmpty() }?.let {
                closeThinking()
                note("Says: " + oneLine(it, 200))
            }
            is RunEvent.ToolResult -> {
                val tool = tools[e.id]
                when {
                    tool is Tool.SetPlan -> planReply(e.text, e.failed).forEach(::note)
                    e.failed -> note("  ✖ ${shortName(tool?.raw)} failed: ${oneLine(e.text, 200)}")
                }
            }
            else -> {}
        }
    }

    private fun toolLine(tool: Tool, input: JsonObject?): String {
        val path = input?.str("file_path")?.replace('\\', '/')
        return when {
            tool is Tool.Read && tool.path != null -> {
                val file = tool.path.replace('\\', '/')
                val offset = tool.offset
                val limit = tool.limit
                val from = offset ?: 1
                val range = from until from + (limit ?: READ_DEFAULT_LINES)
                val key = file.lowercase(Locale.ROOT)
                val again = reads[key].orEmpty().any { it.first <= range.last && range.first <= it.last }
                reads.getOrPut(key) { ArrayList() }.add(range)
                val span = if (offset != null || limit != null) " lines ${from}–${range.last}" else ""
                val base = file.substringAfterLast('/')
                if (again) {
                    rereads++
                    if (rereads >= REREAD_WARN) {
                        warning = "May be looping: $rereads re-reads of files it already read (last $base)"
                    }
                    "Read $base$span  ⟲ re-read $rereads"
                } else {
                    "Read $base$span"
                }
            }
            tool is Tool.SetPlan -> {
                val frames = (input?.get("frames") as? JsonArray)?.size
                val steps = (input?.get("steps") as? JsonArray)?.size
                "set_plan: ${frames ?: "?"} frame(s), ${steps ?: "?"} step(s)"
            }
            else -> {
                val target = path?.substringAfterLast('/')
                    ?: sequenceOf("command", "pattern", "query", "path").mapNotNull { input?.str(it) }.firstOrNull()
                shortName(tool.raw) + (target?.let { " " + oneLine(it, 120) } ?: "")
            }
        }
    }

    /** The `set_plan` reply: `→ ok, 3 frames, 8 steps` and one line per issue, or the refusal. */
    private fun planReply(text: String, failed: Boolean): List<String> {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (failed || obj == null) return listOf("  → " + (if (failed) "refused: " else "") + oneLine(text, 240))
        val ok = obj.str("ok") == "true"
        val issues = (obj["issues"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val head = buildString {
            append("  → ").append(if (ok) "ok" else "not ok")
            obj.str("frames")?.let { append(", $it frame(s)") }
            obj.str("steps")?.let { append(", $it step(s)") }
            append(if (issues.isEmpty()) ", clean" else ", ${issues.size} issue(s)")
            obj.str("error")?.let { append(": ").append(oneLine(it, 200)) }
        }
        return listOf(head) + issues.map { "      " + oneLine(it, 240) }
    }

    /** A think long enough to be worth a line, stamped where it began. */
    private fun closeThinking() {
        if (thinkFrom.isNaN()) return
        val took = now() - thinkFrom
        val tokens = thinkingTokens - thinkTokensFrom
        val chars = thinkingChars - thinkCharsFrom
        val from = thinkFrom
        thinkFrom = Double.NaN
        if (took < THINK_MIN_SECONDS) return
        val size = when {
            tokens > 0 -> ", ~${tokensText(tokens)} tokens"
            chars > 0 -> ", ${tokensText(chars)} chars"
            else -> ""
        }
        add(from, "Thinking %.1fs".format(Locale.ROOT, took) + size)
    }

    private fun add(at: Double, text: String) {
        val line = stamp(at, text)
        val listenersNow = synchronized(this) {
            if (lines.size >= MAX_LINES) lines.subList(0, MAX_LINES / 5).clear()
            lines.add(line)
            listeners.toList()
        }
        listenersNow.forEach { runCatching { it.onLine(line) } }
    }

    private fun stamp(at: Double, text: String) = "%7.1fs  %s".format(Locale.ROOT, at - started, text)

    companion object {
        /** Re-reads before the warning: above the 3 the worst Claude run made, below the 6 and 7 of the looping runs. */
        const val REREAD_WARN = 4
        /** Claude Code's `Read` without a limit returns this many lines. */
        private const val READ_DEFAULT_LINES = 2000L
        private const val THINK_MIN_SECONDS = 2.0
        private const val MAX_LINES = 5000
        private val json = Json { ignoreUnknownKeys = true }

        private fun shortName(name: String?) = when {
            name == null -> "tool"
            name.startsWith("mcp__") -> name.substringAfterLast("__")
            else -> name
        }

        private fun oneLine(s: String, max: Int) = StringUtil.shortenTextWithEllipsis(s.trim().replace(Regex("\\s+"), " "), max, 0)

        private fun tokensText(n: Int) = if (n >= 1000) "%.1fk".format(Locale.ROOT, n / 1000.0) else n.toString()

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
