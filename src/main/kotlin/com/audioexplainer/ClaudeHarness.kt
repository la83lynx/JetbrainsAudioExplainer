package com.audioexplainer

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key

/** How one agent run ended. [toolDown] is why it was stopped at its start: it needed the `set_plan` tool, and the tool did not connect. */
internal class RunResult(
    val exitCode: Int,
    val finalText: String?,
    val isError: Boolean,
    val cancelled: Boolean,
    val stderrTail: String,
    val toolDown: String? = null,
    /** Why an ACP run was stopped: its agent did something risky without asking ([com.audioexplainer.acp.UnaskedWatch]). */
    val policyBreach: String? = null,
)

/**
 * **Claude Code as the harness**: a headless `claude -p` whose `stream-json` stdout is split into
 * lines, each parsed by [ClaudeStream] and read by a [RunReader]. Everything about the stream's format is in the
 * parser; what is here is the process and the run loop.
 *
 * **One place moves the bar**, on a 200 ms beat: the stream can deliver hundreds of deltas a second, and the bar
 * has to advance when nothing is streaming at all. The same beat checks the three stops — the user cancelled,
 * the `set_plan` tool did not connect, a provider rejected the key.
 */
internal object ClaudeHarness {
    /** Under [ClaudeExplainer]'s category, so a run's whole timeline reads as one in `idea.log`. */
    private val log = Logger.getInstance(ClaudeExplainer::class.java)

    fun run(
        cmd: GeneralCommandLine,
        indicator: ProgressIndicator,
        progress: ExplainProgress,
        runLog: RunLog,
        onActivity: (String, Double?, String?) -> Unit,
        onPlanWritten: (String) -> Unit,
        needsTool: Boolean,
        provider: String? = null,
    ): RunResult {
        val handler = OSProcessHandler(cmd)
        val lock = Any()
        val lineBuf = StringBuilder()
        val stderr = StringBuilder()
        val stream = ClaudeStream()
        val reader = RunReader(progress, runLog, provider, needsTool, onPlanWritten, log::info)

        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.STDERR) {
                    synchronized(lock) { stderr.append(event.text) }
                    return
                }
                if (outputType != ProcessOutputTypes.STDOUT) return
                synchronized(lock) {
                    lineBuf.append(event.text)
                    var nl = lineBuf.indexOf("\n")
                    while (nl >= 0) {
                        val line = lineBuf.substring(0, nl)
                        lineBuf.delete(0, nl + 1)
                        reader.onLine(stream.parse(line))
                        nl = lineBuf.indexOf("\n")
                    }
                }
            }
        })

        return RunLoop(indicator, progress, runLog, reader, lock, onActivity, provider).run(
            start = { handler.startNotify() },
            waitFor = handler::waitFor,
            stop = { handler.destroyProcess(); handler.waitFor(2000) },
            exitCode = { handler.exitCode ?: -1 },
            stderr = { synchronized(lock) { stderr.toString() } },
        )
    }
}

/**
 * **The run loop either harness runs**: every 200 ms publish the bar, its activity line and the warning, then check
 * the stops in order — cancelled, the `set_plan` tool down, the key rejected.
 */
internal class RunLoop(
    private val indicator: ProgressIndicator,
    private val progress: ExplainProgress,
    private val runLog: RunLog,
    private val reader: RunReader,
    private val lock: Any,
    private val onActivity: (String, Double?, String?) -> Unit,
    private val provider: String?,
) {
    private val log = Logger.getInstance(ClaudeExplainer::class.java)
    private var shown: Triple<Int, String, String?>? = null
    private val text: String? = indicator.text

    private fun publish() {
        val (fraction, activity) = synchronized(lock) { progress.fraction() to (reader.retryNote ?: progress.activity()) }
        val warning = runLog.warning
        val now = Triple((fraction * 100).toInt(), activity, warning)
        if (now == shown) return
        if (warning != null && warning != shown?.third) log.info("explain run at ${reader.at()}: $warning")
        shown = now
        indicator.fraction = fraction
        // The warning takes the task's own line, so it shows in the status bar beside the task's stop button.
        indicator.text = warning?.let { "⚠ $it" } ?: text
        indicator.text2 = activity
        onActivity(activity, fraction, warning)
    }

    /** [waitFor] is true once the agent has exited; [stop] ends it and waits briefly. */
    fun run(start: () -> Unit, waitFor: (Long) -> Boolean, stop: () -> Unit, exitCode: () -> Int, stderr: () -> String): RunResult {
        indicator.isIndeterminate = false
        start()
        while (!waitFor(200)) {
            publish()
            if (indicator.isCanceled) {
                stop()
                log.info("explain run cancelled at ${reader.at()} after ${reader.tools} tool call(s)")
                runLog.note("Cancelled after ${reader.tools} tool call(s)")
                return RunResult(-1, null, isError = false, cancelled = true, stderrTail = stderrTail(stderr()))
            }
            // Stopped at its first event: a run asked to call a tool it does not have would only report that it
            // has not, after reading the code and writing the plan.
            reader.toolStatus?.takeIf { it != RunReader.CONNECTED }?.let { why ->
                stop()
                return RunResult(-1, null, isError = true, cancelled = false, stderrTail = "", toolDown = why)
            }
            // Stopped at the first 401/403: the CLI would retry a rejected key 10 times, ~3 minutes of backoff.
            reader.authStop?.let { retry ->
                stop()
                log.info("explain run stopped at ${reader.at()}: $provider rejected the API key (HTTP ${retry.status})")
                runLog.note("Stopped: $provider rejected the API key (HTTP ${retry.status})")
                return RunResult(
                    -1,
                    "$provider rejected the API key (HTTP ${retry.status}). Check it in Settings → Tools → " +
                        "Audio Explainer → Explanation → Manage….",
                    isError = true, cancelled = false, stderrTail = "",
                )
            }
        }

        publish()
        val exit = exitCode()
        return RunResult(
            exitCode = exit,
            finalText = reader.finalText,
            isError = reader.isError || exit != 0,
            cancelled = false,
            stderrTail = stderrTail(stderr()),
            toolDown = reader.toolStatus?.takeIf { it != RunReader.CONNECTED },
        )
    }

    companion object {
        /** The end of stderr, without the lines every provider run prints and none of which is a failure. */
        fun stderrTail(s: String): String {
            val t = Providers.dropHarmlessStderr(s)
            return if (t.length <= 600) t else "…" + t.substring(t.length - 600)
        }
    }
}
