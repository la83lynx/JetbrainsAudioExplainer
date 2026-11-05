package com.audioexplainer

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 6 — the Claude Code backbone.
 *
 * Turns an [ExplainRequest] into an audio walkthrough by shelling out to a headless `claude -p`
 * (cwd = project root) with a prompt that carries the cursor context and triggers the bundled
 * `audio-explainer` skill's explain-at-cursor flow. Claude expands the symbol under the cursor,
 * builds a `set_plan`, and sends it with the run's own `set_plan` MCP tool ([McpEndpoint]) to this
 * plugin's loopback server ([ExplainerServer]) — which drives the walkthrough, editor spotlight, and
 * sidebar. The C# symbol is NOT resolved in-plugin (decision 2); that's Claude's job.
 *
 * **When the tool does not connect, the run is started again the old way** — the agent `Write`s the plan
 * and POSTs it with a helper script. The tool is ~12 s faster (`Speed.md` #3), but the POST is the path
 * that needs nothing from the CLI's MCP support or the user's policy on it.
 *
 * Runs on the background thread of [ExplainThisAction]'s `Task.Backgroundable`, streaming Claude's
 * `stream-json` events into the progress indicator and honouring cancellation.
 *
 * **The argv and the prompt are built in [ExplainPrompt]**, which is pure string building and therefore
 * measurable by `probes/EffortProbe.java`. What stays here is everything that needs an IDE. The three
 * generation knobs — effort, model, depth — are resolved in exactly one place, [explain], as
 * `request.x ?: state.x`.
 */
@Service(Service.Level.APP)
class ClaudeExplainer {
    private val log = Logger.getInstance(ClaudeExplainer::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun explain(project: Project, request: ExplainRequest, indicator: ProgressIndicator) {
        // Surface the generation inside the tool window (Phase 7.5 #5): flip the project-scoped tracker
        // and make the sidebar visible so its loading banner shows while Claude works.
        val tracker = ExplainSessionTracker.getInstance(project)
        tracker.startGenerating(request.displayName)
        ensureToolWindowVisible(project)

        val claudeExe = findClaudeExecutable()
        if (claudeExe == null) {
            tracker.fail("Couldn't find the claude executable on PATH.")
            notify(
                project, NotificationType.ERROR, "Claude CLI not found",
                "Couldn't find the <code>claude</code> executable on PATH. Install Claude Code and make " +
                    "sure <code>claude</code> is on your PATH, then try again.",
            )
            return
        }

        indicator.text = "Preparing the Audio Explainer skill…"
        tracker.progress("Preparing the Audio Explainer skill…")
        val skillDir = ClaudeSkillInstaller.ensureInstalled()

        val workDir = request.projectBasePath
            ?: request.absolutePath.substringBeforeLast('/', "").ifEmpty { System.getProperty("user.home") }
        // **The one resolution point** (Effort.md): a per-run override from the "Explain This with…" dialog
        // wins, and everything it leaves null falls back to the saved default. The plain menu entry sends
        // nulls for all three, so it takes exactly the saved settings — which is what keeps its path the
        // one every earlier round judged.
        val state = AudioExplainerSettings.getInstance().state
        val effort = request.effort ?: state.explainEffort
        val model = state.explainModel // no per-run model control — see Effort.md's dialog table
        val depth = request.depth ?: state.explainDepth
        // **A provider profile** swaps the service behind Claude Code, not the prompt. Its model is
        // the model, so the timings learn it apart from Claude's; null is Claude Code's own setup, untouched.
        val profile = Providers.selected(state)
        val runModel = profile?.model?.trim() ?: model
        val providerKey = profile?.let { runCatching { ProviderKeys.get(it.id) }.getOrNull() }
        if (profile != null && providerKey == null && Providers.needsKey(profile)) {
            val why = "No API key is set for ${profile.name}."
            tracker.fail(why)
            notify(
                project, NotificationType.ERROR, "Explain This: no API key",
                "${StringUtil.escapeXmlEntities(why)} Set it in Settings → Tools → Audio Explainer → Explanation → Manage….",
            )
            return
        }
        // The run's own directory (`Saving.md` B): the plans it sends are kept there, and the JSON it POSTs
        // when it has to, so no payload lands in the project.
        val planDir = createPlanDir()
        // **Kept unless the run succeeds**: a failed run's payloads are the evidence of what it sent.
        var keepPlanDir = true
        val server = service<ExplainerServerService>().server
        val early = EarlyPlan(project, tracker)
        var tool = planDir?.let { openTool(server, it, early) }
        var settingsFile: Path? = null
        try {
            if (profile != null) {
                settingsFile = try {
                    ProviderFiles.write(Providers.settingsJson(profile, providerKey))
                } catch (e: Exception) {
                    log.warn("could not write the provider settings file", e)
                    tracker.fail("Could not write the provider settings file.")
                    notify(project, NotificationType.ERROR, "Explain This failed", errorHtml(e.message))
                    keepPlanDir = false
                    return
                }
            }
            fun attempt(tool: ToolRoute?): RunResult? {
                val prompt = ExplainPrompt.buildPrompt(
                    request, skillDir, resolveLanguage(), depth, request.extraInstructions, planDir,
                    viaTool = tool != null,
                )
                val cmd = try {
                    GeneralCommandLine(claudeExe.toString())
                        .withParameters(ExplainPrompt.buildArgs(prompt, effort, model, planDir, tool?.config, settingsFile))
                        .withWorkDirectory(workDir)
                        .withCharset(StandardCharsets.UTF_8)
                        .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                } catch (e: Exception) {
                    log.warn("failed to build claude command line", e)
                    tracker.fail(e.message ?: "Failed to start Claude.")
                    notify(project, NotificationType.ERROR, "Explain This failed", errorHtml(e.message))
                    return null
                }

                log.info(
                    "explain run started: ${request.displayName} (${request.scope.name.lowercase()}), " +
                        "effort=${effort.ifBlank { "inherited" }}, depth=$depth, " +
                        (profile?.let { "provider=${it.name}, " } ?: "") + "model=${runModel.ifBlank { "default" }}, " +
                        "plan via ${if (tool != null) "the set_plan tool" else "POST"}",
                )
                indicator.text = "Claude is reading ${request.displayName} and planning the walkthrough…"
                tracker.progress("Reading ${request.displayName} and planning…")
                val timings = ExplainTimings.getInstance()
                val progress = ExplainProgress(timings.expected(effort, depth, runModel))
                val result = try {
                    runClaude(cmd, indicator, progress, tracker::progress, early::offer, needsTool = tool != null, provider = profile?.name)
                } catch (e: Exception) {
                    log.warn("claude invocation failed", e)
                    tracker.fail(e.message ?: "Claude invocation failed.")
                    notify(project, NotificationType.ERROR, "Explain This failed", errorHtml(e.message))
                    return null
                }
                if (!result.cancelled && result.toolDown == null) {
                    log.info("explain run shape ${ExplainTimings.key(effort, depth, runModel)}: ${progress.report()}")
                    // Only a run that finished cleanly teaches the bar what this combination takes.
                    if (!result.isError) progress.observed()?.let { timings.record(effort, depth, runModel, it) }
                }
                return result
            }

            var result = attempt(tool) ?: return
            if (result.toolDown != null) {
                log.info("explain run: the set_plan tool did not connect (${result.toolDown}); starting again with the POST")
                tool?.close(server)
                tool = null
                result = attempt(null) ?: return
            }
            keepPlanDir = !reportResult(project, request, result, tracker, announced = early.loaded)
        } finally {
            tool?.close(server)
            // Always, even when the plan directory is kept as evidence: the key in it is live.
            ProviderFiles.delete(settingsFile)
            disposePlanDir(planDir, keepPlanDir)
        }
    }

    /** A run's `set_plan` endpoint, and the `--mcp-config` file that points the CLI at it. */
    private class ToolRoute(val run: McpRun, val config: Path) {
        fun close(server: ExplainerServer) {
            server.closeRun(run)
            // The token is dead now, but a kept plan directory is evidence, and this file is not.
            runCatching { Files.deleteIfExists(config) }
        }
    }

    /**
     * Open the run's endpoint and write its config into [planDir], or null — the run then POSTs, as every run did
     * before the tool. Each plan the tool loads is offered to [early], so the reader hears "ready" as it lands.
     */
    private fun openTool(server: ExplainerServer, planDir: Path, early: EarlyPlan): ToolRoute? {
        val run = server.openRun(planDir) { resolved, issues -> early.arrived(resolved, issues) }
        return try {
            val config = planDir.resolve("mcp.json")
            Files.writeString(config, McpEndpoint.config(server.mcpUrl(run), run.token))
            ToolRoute(run, config)
        } catch (e: Exception) {
            log.warn("could not write the run's MCP config; it will POST its plan", e)
            server.closeRun(run)
            null
        }
    }

    /**
     * A fresh `run-*` directory under the IDE's temp path for this run's POST payloads (`Saving.md` B), or
     * null when one cannot be made — the prompt then names none, which is how every run behaved before.
     */
    private fun createPlanDir(): Path? = try {
        val base = Path.of(PathManager.getTempPath(), "audio-explainer", "plans")
        Files.createDirectories(base)
        Files.createTempDirectory(base, "run-")
    } catch (e: Exception) {
        log.warn("could not create a plan directory; the prompt will name none", e)
        null
    }

    /**
     * The process has exited, so nothing can write here again: delete the directory, or keep it and say
     * where it is when the run failed, because then its payloads are the evidence.
     */
    private fun disposePlanDir(planDir: Path?, keep: Boolean) {
        if (planDir == null) return
        if (keep) {
            log.info("explain run failed; its plan payloads are kept in $planDir")
            return
        }
        if (!FileUtil.delete(planDir.toFile())) log.info("could not delete the plan directory $planDir")
    }

    /**
     * The walkthrough language for a new run, warmed if need be.
     *
     * A Windows SAPI-only language (Phase L7) exists only after voice discovery has run, so this warms it
     * — cached, i.e. instant once discovered — and [Languages.byCode] can then answer with its display
     * name and autonym on the first Explain This of a session rather than the second.
     */
    private fun resolveLanguage(): Language {
        val langCode = AudioExplainerSettings.getInstance().state.explanationLanguage
        if (langCode != Languages.DEFAULT_CODE && Languages.byCode(langCode).code != langCode) {
            runCatching { TtsService.getInstance().sapiVoices(refresh = false) }
        }
        return Languages.byCode(langCode)
    }

    // ── Process execution ────────────────────────────────────────────────────

    /** [toolDown] is why the run was stopped at its start: it needed the `set_plan` tool, and the tool did not connect. */
    private class RunResult(
        val exitCode: Int,
        val finalText: String?,
        val isError: Boolean,
        val cancelled: Boolean,
        val stderrTail: String,
        val toolDown: String? = null,
    )

    private fun runClaude(
        cmd: GeneralCommandLine,
        indicator: ProgressIndicator,
        progress: ExplainProgress,
        onActivity: (String, Double?) -> Unit,
        onPlanWritten: (String) -> Unit,
        needsTool: Boolean,
        provider: String? = null,
    ): RunResult {
        val handler = OSProcessHandler(cmd)
        val lock = Any()
        val lineBuf = StringBuilder()
        val stderr = StringBuilder()
        val finalText = AtomicReference<String?>(null)
        val isError = AtomicBoolean(false)
        val toolDown = AtomicReference<String?>(null)
        // A provider run only: a rejected key stops the run, any other retry shows in the progress text.
        val authStop = AtomicReference<Providers.Retry?>(null)
        var retryNote: String? = null
        val timeline = RunTimeline()

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
                        processStreamLine(line, finalText, isError, timeline, progress, onPlanWritten, provider)
                        if (needsTool && toolDown.get() == null) toolStatus(line)?.let(toolDown::set)
                        if (provider != null) {
                            val retry = Providers.retry(line)
                            when {
                                retry == null -> retryNote = null
                                retry.authRejected -> authStop.compareAndSet(null, retry)
                                else -> retryNote = "$provider busy (${retry.status ?: retry.error ?: "error"}), " +
                                    "retrying (${retry.attempt ?: "?"}/${retry.maxRetries ?: "?"})…"
                            }
                        }
                        nl = lineBuf.indexOf("\n")
                    }
                }
            }
        })

        // **One place moves the bar**, on a 200 ms beat: the stream can deliver hundreds of deltas a second,
        // and the bar has to advance when nothing is streaming at all.
        indicator.isIndeterminate = false
        var shown: Pair<Int, String>? = null
        fun publish() {
            val (fraction, activity) = synchronized(lock) { progress.fraction() to (retryNote ?: progress.activity()) }
            val now = (fraction * 100).toInt() to activity
            if (now == shown) return
            shown = now
            indicator.fraction = fraction
            indicator.text2 = activity
            onActivity(activity, fraction)
        }

        handler.startNotify()
        while (!handler.waitFor(200)) {
            publish()
            if (indicator.isCanceled) {
                handler.destroyProcess()
                handler.waitFor(2000)
                log.info("explain run cancelled at ${timeline.at()} after ${timeline.tools} tool call(s)")
                return RunResult(-1, null, isError = false, cancelled = true, stderrTail = stderrTail(synchronized(lock) { stderr.toString() }))
            }
            // Stopped at its first event: a run asked to call a tool it does not have would only report that it
            // has not, after reading the code and writing the plan.
            toolDown.get()?.takeIf { it != CONNECTED }?.let { why ->
                handler.destroyProcess()
                handler.waitFor(2000)
                return RunResult(-1, null, isError = true, cancelled = false, stderrTail = "", toolDown = why)
            }
            // Stopped at the first 401/403: the CLI would retry a rejected key 10 times, ~3 minutes of backoff.
            authStop.get()?.let { retry ->
                handler.destroyProcess()
                handler.waitFor(2000)
                log.info("explain run stopped at ${timeline.at()}: $provider rejected the API key (HTTP ${retry.status})")
                return RunResult(
                    -1,
                    "$provider rejected the API key (HTTP ${retry.status}). Check it in Settings → Tools → " +
                        "Audio Explainer → Explanation → Manage….",
                    isError = true, cancelled = false, stderrTail = "",
                )
            }
        }

        publish()
        val exit = handler.exitCode ?: -1
        return RunResult(
            exitCode = exit,
            finalText = finalText.get(),
            isError = isError.get() || exit != 0,
            cancelled = false,
            stderrTail = stderrTail(synchronized(lock) { stderr.toString() }),
            toolDown = toolDown.get()?.takeIf { it != CONNECTED },
        )
    }

    /**
     * **One run's timeline, written to `idea.log`** — a line per tool call with its elapsed time, and a closing
     * line with the run's own accounting.
     *
     * It exists because a sandbox round could not answer the two questions `Speed.md` keeps asking: how long
     * the run took, and what it spent the time on (did it write a validator, did it re-POST). The headless
     * bench answers them from `stream-json`; this is the same reading, kept where a round can see it. INFO,
     * so it reaches `idea.log` and stays out of the `runIde` console.
     *
     * Touched only under the stream lock in [runClaude], so the counters need no synchronisation of their own.
     */
    private class RunTimeline {
        private val started = System.nanoTime()
        var tools = 0

        fun at(): String = "%.1fs".format((System.nanoTime() - started) / 1e9)
    }

    /**
     * From the run's `init` event, whether the `set_plan` server connected: [CONNECTED], or why not. Null for any
     * other line. The CLI lists each server with a `status` — `connected`, `failed`, `needs-auth`, `pending` — and
     * leaves out one a policy refused, so anything but `connected` is a run that cannot send its plan.
     */
    private fun toolStatus(line: String): String? {
        if (!line.contains("\"init\"")) return null
        val obj = runCatching { json.parseToJsonElement(line.trim()).jsonObject }.getOrNull() ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "system" || obj["subtype"]?.jsonPrimitive?.contentOrNull != "init") return null
        val servers = runCatching { obj["mcp_servers"]?.jsonArray }.getOrNull()
        val ours = servers?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == McpEndpoint.SERVER_NAME }
            ?: return "not listed in the run's MCP servers"
        val status = ours["status"]?.jsonPrimitive?.contentOrNull
        return if (status == "connected") CONNECTED else "status ${status ?: "unknown"}"
    }

    /** What a tool call acted on, short enough for one log line: its file, command or pattern. */
    private fun toolTarget(input: JsonObject?): String {
        if (input == null) return ""
        val raw = sequenceOf("file_path", "command", "pattern", "path")
            .mapNotNull { input[it]?.jsonPrimitive?.contentOrNull }
            .firstOrNull() ?: return ""
        return ": " + StringUtil.shortenTextWithEllipsis(raw.trim().replace('\n', ' '), 160, 60)
    }

    /**
     * Parse one `stream-json` event line: feed the progress estimate, log the timeline, hand a written plan to
     * [onPlanWritten], capture the final result. The activity label is [ExplainProgress]'s now.
     */
    private fun processStreamLine(
        line: String,
        finalText: AtomicReference<String?>,
        isError: AtomicBoolean,
        timeline: RunTimeline,
        progress: ExplainProgress,
        onPlanWritten: (String) -> Unit,
        provider: String?,
    ) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return
        progress.onEvent(obj)
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "assistant" -> {
                val content = obj["message"]?.jsonObject?.get("content") ?: return
                val blocks = runCatching { content.jsonArray }.getOrNull() ?: return
                for (block in blocks) {
                    val b = runCatching { block.jsonObject }.getOrNull() ?: continue
                    if (b["type"]?.jsonPrimitive?.contentOrNull != "tool_use") continue
                    val name = b["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    timeline.tools++
                    val input = runCatching { b["input"]?.jsonObject }.getOrNull()
                    log.info("explain run +${timeline.at()} $name${toolTarget(input)}")
                    // The whole plan, the moment it is generated — 10-15 s before the agent's POST lands.
                    if (name == "Write" && input?.get("file_path")?.jsonPrimitive?.contentOrNull?.endsWith(".json") == true) {
                        input["content"]?.jsonPrimitive?.contentOrNull?.let(onPlanWritten)
                    }
                }
            }
            "result" -> {
                fun num(key: String) = obj[key]?.jsonPrimitive?.contentOrNull
                val usage = runCatching { obj["usage"]?.jsonObject }.getOrNull()
                log.info(
                    "explain run finished at ${timeline.at()}: ${timeline.tools} tool call(s), " +
                        "${num("num_turns")} turn(s), api ${num("duration_api_ms")} ms, " +
                        "${usage?.get("output_tokens")?.jsonPrimitive?.contentOrNull} output token(s), " +
                        // Off Claude the CLI still prices the tokens as Claude's ($0.68-2.02 for runs that cost
                        // $0-0.03 when tested), so the number would only mislead.
                        (if (provider != null) "cost n/a ($provider)" else "cost \$${num("total_cost_usd")}") +
                        ", ${num("subtype")}",
                )
                finalText.set(obj["result"]?.jsonPrimitive?.contentOrNull)
                val subtype = obj["subtype"]?.jsonPrimitive?.contentOrNull
                val flagged = (obj["is_error"]?.jsonPrimitive?.contentOrNull == "true")
                if (flagged || (subtype != null && subtype != "success")) isError.set(true)
            }
        }
    }

    // ── Result reporting ───────────────────────────────────────────────────────

    /**
     * Report how the run ended. Returns true when it did not fail — a plan arrived, or the user cancelled —
     * which is what decides whether its plan directory is evidence worth keeping.
     */
    private fun reportResult(
        project: Project,
        request: ExplainRequest,
        result: RunResult,
        tracker: ExplainSessionTracker,
        announced: Boolean,
    ): Boolean {
        if (result.cancelled) {
            tracker.finish() // clear the banner; the balloon reports the cancellation
            notify(project, NotificationType.INFORMATION, "Explain This cancelled", "The walkthrough request was cancelled.")
            return true
        }

        val state = runCatching { service<ExplainerServerService>().server.walkthrough.getState() }.getOrNull()
        // Frames, not projected segments: a plan whose frames all resolved but whose steps were dropped is
        // still a plan that arrived, and the notification below says how much of it there is.
        val planLoaded = state != null && state.title.isNotEmpty() && state.plan.frames.isNotEmpty()

        if (result.isError && !planLoaded) {
            val detail = result.finalText?.takeIf { it.isNotBlank() } ?: result.stderrTail.takeIf { it.isNotBlank() }
            tracker.fail(detail?.let { StringUtil.shortenTextWithEllipsis(it.trim(), 300, 0) }
                ?: "Claude did not produce a walkthrough (exit ${result.exitCode}).")
            notify(
                project, NotificationType.ERROR, "Explain This failed",
                buildString {
                    append("Claude did not produce a walkthrough (exit ${result.exitCode}).")
                    if (detail != null) append("<br/><br/>").append(errorHtml(detail))
                },
            )
            return false
        }

        if (planLoaded) {
            tracker.finish() // plan arrived via set_plan → walkthrough; the banner's job is done
            if (!announced) announceReady(project, state)
            return true
        } else {
            // Claude reported success but no plan reached the server.
            tracker.fail("Claude finished but no walkthrough was received by the plugin.")
            notify(
                project, NotificationType.WARNING, "No walkthrough loaded",
                buildString {
                    append("Claude finished but no walkthrough was received by the plugin.")
                    result.finalText?.takeIf { it.isNotBlank() }?.let { append("<br/><br/>").append(errorHtml(it)) }
                },
            )
        }
        return false
    }

    private fun announceReady(project: Project, state: WalkthroughSnapshot) {
        activateSidebar(project)
        notify(
            project, NotificationType.INFORMATION, "Walkthrough ready",
            "<b>${StringUtil.escapeXmlEntities(state.title)}</b> — ${state.plan.frames.size} frame(s), " +
                "${state.plan.steps.size} step(s). " +
                "Open the <b>Audio Explainer</b> tool window and press Play.",
        )
    }

    /**
     * **The plan, announced the moment it exists** rather than when the run ends — from the `set_plan` tool as its
     * call lands ([arrived]), or, on a run that POSTs, from the `Write` that holds it ([offer]).
     *
     * **Loaded the moment the agent writes it** rather than when its POST lands.
     *
     * The `Write` that holds the plan is in the stream as soon as it is generated, and the agent then spends
     * 10-15 s on a shell call to POST the same file and a closing summary — time the reader spent looking at a
     * spinner over a walkthrough that already existed (`Speed.md`, bench2 and bench3). So the plugin takes it
     * from the stream and loads it through the server's own validation.
     *
     * **Only a clean plan loads early** ([ExplainerServer.preload]): one whose reply would carry an `[error]` or
     * a `[warn]` is one the agent is about to fix and re-POST, and showing it first would put a broken
     * walkthrough on screen and then swap it. The agent's POST of the same file is then a no-op
     * ([Walkthrough.setPlanUnlessShowing]) — no reload, no "replaced" banner, the reader's position kept.
     */
    private inner class EarlyPlan(private val project: Project, private val tracker: ExplainSessionTracker) {
        @Volatile
        var loaded = false
            private set

        fun offer(content: String) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val resolved = runCatching { service<ExplainerServerService>().server.preload(content) }
                    .onFailure { log.warn("explain run: loading the written plan early failed", it) }
                    .getOrNull()
                if (resolved == null) {
                    log.info("explain run: the written plan is not clean, so it waits for the agent's POST")
                    return@executeOnPooledThread
                }
                log.info("explain run: plan loaded from its Write — ${resolved.frames.size} frame(s), ${resolved.steps.size} step(s)")
                announce()
            }
        }

        /**
         * A plan the tool has loaded. It is already showing; what waits is only the "ready" — for a plan whose
         * reply carries more than `[info]`, because the agent is about to send a fixed one.
         */
        fun arrived(resolved: ResolvedPlan, issues: List<PlanIssue>) {
            if (resolved.isEmpty() || issues.any { it.level != IssueLevel.INFO }) {
                log.info("explain run: the plan sent with the tool is not clean, so ready waits for the agent's fix or its end")
                return
            }
            log.info("explain run: plan loaded from the set_plan tool — ${resolved.frames.size} frame(s), ${resolved.steps.size} step(s)")
            announce()
        }

        @Synchronized
        private fun announce() {
            if (loaded) return
            loaded = true
            tracker.finish()
            announceReady(project, service<ExplainerServerService>().server.walkthrough.getState())
        }
    }

    private fun activateSidebar(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
        }
    }

    /**
     * Make the tool window visible (without stealing focus) so the loading banner shows while Claude
     * works. `show` also builds the tool-window content on first use, creating the sidebar that
     * subscribes to [ExplainSessionTracker] — which then catches up on the state we just set.
     */
    private fun ensureToolWindowVisible(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show(null)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Find `claude` robustly: an explicit settings path, then PATH (trying `.exe`/`.cmd`/… on Windows),
     * then common install dirs directly (so a narrow IDE PATH still resolves). See [ExecutableFinder].
     */
    private fun findClaudeExecutable(): Path? =
        ExecutableFinder.findClaude(AudioExplainerSettings.getInstance().state.claudeExecutablePath)

    private fun notify(project: Project, type: NotificationType, title: String, content: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(title, content, type)
                .notify(project)
        }
    }

    private fun errorHtml(message: String?): String =
        StringUtil.escapeXmlEntities(StringUtil.shortenTextWithEllipsis(message ?: "Unknown error", 600, 0))

    /** The end of stderr, without the lines every provider run prints and none of which is a failure. */
    private fun stderrTail(s: String): String = tail(Providers.dropHarmlessStderr(s))

    private fun tail(s: String, max: Int = 600): String =
        if (s.length <= max) s else "…" + s.substring(s.length - max)

    companion object {
        const val NOTIFICATION_GROUP = "Audio Explainer"
        private const val TOOL_WINDOW_ID = "Audio Explainer"
        private const val CONNECTED = "connected"

        fun getInstance(): ClaudeExplainer = service()
    }
}
