package com.audioexplainer

import com.audioexplainer.acp.AcpHarness
import com.audioexplainer.acp.AcpSession
import com.audioexplainer.acp.AgentPresets
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Claude Code backbone.
 *
 * Turns an [ExplainRequest] into an audio walkthrough by shelling out to a headless `claude -p`
 * (cwd = project root) with a prompt that carries the cursor context and triggers the bundled
 * `audio-explainer` skill's explain-at-cursor flow. Claude expands the symbol under the cursor,
 * builds a `set_plan`, and sends it with the run's own `set_plan` MCP tool ([McpEndpoint]) to this
 * plugin's loopback server ([ExplainerServer]) — which drives the walkthrough, editor spotlight, and
 * sidebar. The C# symbol is NOT resolved in-plugin; that's Claude's job.
 *
 * **When the tool does not connect, the run is started again by POST** — the agent `Write`s the plan
 * and POSTs it with a helper script. The tool is ~12 s faster, but the POST is the path
 * that needs nothing from the CLI's MCP support or the user's policy on it.
 *
 * Runs on the background thread of [ExplainThisAction]'s `Task.Backgroundable`. The process and its output are
 * [ClaudeHarness]'s; what is here is the run around it — the plan directory, the tool endpoint, the retry by POST,
 * and how the run is reported.
 *
 * **The argv and the prompt are built in [ExplainPrompt]**, which is pure string building and therefore
 * measurable by `probes/EffortProbe.java`. What stays here is everything that needs an IDE. The three
 * generation knobs — effort, model, depth — are resolved in exactly one place, [explain], as
 * `request.x ?: state.x`.
 */
@Service(Service.Level.APP)
class ClaudeExplainer {
    private val log = Logger.getInstance(ClaudeExplainer::class.java)

    fun explain(project: Project, request: ExplainRequest, indicator: ProgressIndicator) {
        // Surface the generation inside the tool window: flip the project-scoped tracker
        // and make the sidebar visible so its loading banner shows while Claude works.
        val tracker = ExplainSessionTracker.getInstance(project)
        val runLog = RunLog(request.displayName)
        tracker.startGenerating(request.displayName, runLog, indicator)
        ensureToolWindowVisible(project)
        // What was showing before the run, so a walkthrough left from an earlier run never counts as this one's.
        val planBefore = showingPlan(project)

        val harness = request.harness ?: AudioExplainerSettings.getInstance().state.explainHarness
        if (harness in AgentPresets.ACP) {
            explainWithAgent(project, request, indicator, tracker, runLog, harness, planBefore)
            return
        }

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
        // **The one resolution point**: a per-run override from the "Explain This with…" dialog wins, and
        // everything it leaves null falls back to the saved default. The plain menu entry sends nulls for
        // all three, so it takes exactly the saved settings.
        val state = AudioExplainerSettings.getInstance().state
        val effort = request.effort ?: state.explainEffort
        val model = state.explainModel // no per-run model control: the dialog offers none
        val depth = request.depth ?: state.explainDepth
        // **A provider profile** swaps the service behind Claude Code, not the prompt. Its model is
        // the model, so the timings learn it apart from Claude's; null is Claude Code's own setup, untouched.
        val profile = Providers.selected(state, request.provider)
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
        // The run's own directory: the plans it sends are kept there, and the JSON it POSTs
        // when it has to, so no payload lands in the project.
        val planDir = createPlanDir()
        // **Kept unless the run succeeds**: a failed run's payloads are the evidence of what it sent.
        var keepPlanDir = true
        val server = service<ExplainerServerService>().server
        val early = EarlyPlan(project, tracker, runLog)
        var tool = planDir?.let { openTool(server, project, it, early) }
        var settingsFile: Path? = null
        try {
            if (profile != null) {
                settingsFile = try {
                    ProviderFiles.write(Providers.settingsJson(profile, providerKey))
                } catch (e: Exception) {
                    log.warn("could not write the provider settings file", e)
                    tracker.fail("Could not write the provider settings file.")
                    notify(project, NotificationType.ERROR, "Explain This failed", errorHtml(e.message), runLog)
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

                val startLine = "${request.displayName} (${request.scope.name.lowercase()}), " +
                    "effort=${effort.ifBlank { "inherited" }}, depth=$depth, " +
                    (profile?.let { "provider=${it.name}, " } ?: "") + "model=${runModel.ifBlank { "default" }}, " +
                    "plan via ${if (tool != null) "the set_plan tool" else "POST"}"
                log.info("explain run started: $startLine")
                runLog.note("Started: $startLine")
                indicator.text = "Claude is reading ${request.displayName} and planning the walkthrough…"
                tracker.progress("Reading ${request.displayName} and planning…")
                val timings = ExplainTimings.getInstance()
                val progress = ExplainProgress(timings.expected(effort, depth, runModel))
                val result = try {
                    ClaudeHarness.run(cmd, indicator, progress, runLog, tracker::progress, early::offer, needsTool = tool != null, provider = profile?.name)
                } catch (e: Exception) {
                    log.warn("claude invocation failed", e)
                    tracker.fail(e.message ?: "Claude invocation failed.")
                    notify(project, NotificationType.ERROR, "Explain This failed", errorHtml(e.message), runLog)
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
                runLog.note("The set_plan tool did not connect (${result.toolDown}); starting again, sending the plan by POST")
                tool?.close(server)
                tool = null
                result = attempt(null) ?: return
            }
            keepPlanDir = !reportResult(project, request, result, tracker, runLog, early, planBefore)
        } finally {
            tool?.close(server)
            // Always, even when the plan directory is kept as evidence: the key in it is live.
            ProviderFiles.delete(settingsFile)
            disposePlanDir(planDir, keepPlanDir)
        }
    }

    /**
     * **An Explain This run on an ACP agent**: the same request, skill, prompt, plan directory,
     * `set_plan` endpoint, bar and report as a Claude run, with [AcpHarness] in place of `claude -p`.
     *
     * The tool is the only way back for its plan: the read-only policy refuses the `Write` and the shell a POST needs,
     * so a run whose agent never connects to the tool fails, saying so, instead of starting again by POST.
     */
    private fun explainWithAgent(
        project: Project,
        request: ExplainRequest,
        indicator: ProgressIndicator,
        tracker: ExplainSessionTracker,
        runLog: RunLog,
        harness: String,
        planBefore: Plan?,
    ) {
        val state = AudioExplainerSettings.getInstance().state
        val label = AgentPresets.label(harness)
        fun fail(title: String, why: String, log: RunLog? = null) {
            tracker.fail(why)
            notify(project, NotificationType.ERROR, title, StringUtil.escapeXmlEntities(why), log)
        }

        val (exe, customArgs) = AcpHarness.find(harness, state)
        if (exe == null) {
            fail("$label not found", AgentPresets.notFound(harness))
            return
        }

        indicator.text = "Preparing the Audio Explainer skill…"
        tracker.progress("Preparing the Audio Explainer skill…")
        val skillDir = ClaudeSkillInstaller.ensureInstalled()
        val workDir = request.projectBasePath
            ?: request.absolutePath.substringBeforeLast('/', "").ifEmpty { System.getProperty("user.home") }
        val effort = request.effort ?: state.explainEffort
        val depth = request.depth ?: state.explainDepth
        val profile = Providers.selected(state, request.provider)
        val providerKey = profile?.let { runCatching { ProviderKeys.get(it.id) }.getOrNull() }
        if (profile != null && providerKey == null && Providers.needsKey(profile)) {
            fail("Explain This: no API key", "No API key is set for ${profile.name}. Set it in Settings → Tools → Audio Explainer → Explanation → Manage….")
            return
        }
        val launch = AgentPresets.launch(harness, workDir, profile, providerKey, effort, customArgs)
        launch.problem?.let { fail("Explain This: $label cannot run", it); return }
        // The bar learns each agent apart: its pace is the agent's as much as the model's.
        val runModel = "$harness:" + (profile?.model?.trim()?.ifBlank { null } ?: "default")

        val planDir = createPlanDir()
        var keepPlanDir = true
        val server = service<ExplainerServerService>().server
        val early = EarlyPlan(project, tracker, runLog)
        val tool = planDir?.let { openTool(server, project, it, early) }
        if (tool == null) {
            fail("Explain This failed", "Could not open the run's set_plan endpoint.")
            disposePlanDir(planDir, keep = false)
            return
        }
        try {
            val language = resolveLanguage()
            val prompt = ExplainPrompt.buildPrompt(request, skillDir, language, depth, request.extraInstructions, planDir, viaTool = true)
            // An agent that takes embedded resources gets the two docs with the prompt, and is told so.
            val docs = ExplainPrompt.SKILL_DOCS.mapNotNull { rel ->
                val f = skillDir?.resolve(rel) ?: return@mapNotNull null
                runCatching { f.toString() to Files.readString(f) }.getOrNull()
            }
            val promptFor = { embedded: Boolean ->
                if (embedded && docs.size == ExplainPrompt.SKILL_DOCS.size) {
                    AcpSession.blocks(
                        ExplainPrompt.buildPrompt(request, skillDir, language, depth, request.extraInstructions, planDir, viaTool = true, docsAttached = true),
                        docs,
                    )
                } else {
                    AcpSession.blocks(prompt)
                }
            }
            val startLine = "${request.displayName} (${request.scope.name.lowercase()}), agent=$label, " +
                "effort=${effort.ifBlank { "inherited" }}, depth=$depth, " +
                (profile?.let { "provider=${it.name}, model=${it.model.trim()}, " } ?: "model=the agent's own, ") +
                "plan via the set_plan tool"
            log.info("explain run started: $startLine")
            runLog.note("Started: $startLine")
            indicator.text = "$label is reading ${request.displayName} and planning the walkthrough…"
            tracker.progress("Reading ${request.displayName} and planning…")
            val timings = ExplainTimings.getInstance()
            val progress = ExplainProgress(timings.expected(effort, depth, runModel), starting = "Starting $label…")
            val roots = buildList {
                add(workDir)
                add(skillDir.toString())
                request.absolutePath.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(::add)
                request.extraPaths.forEach { add(if (runCatching { Path.of(it).isAbsolute }.getOrDefault(false)) it else "$workDir/$it") }
            }
            val result = try {
                AcpHarness.run(
                    exe, launch, workDir, AcpSession.McpServer(server.mcpUrl(tool.run), tool.run.token), promptFor, roots,
                    indicator, progress, runLog, tracker::progress, profile?.name,
                    toolConnected = { tool.run.connected }, clientVersion = CLIENT_VERSION,
                )
            } catch (e: Exception) {
                log.warn("ACP agent invocation failed", e)
                fail("Explain This failed", "$label could not be started: ${e.message ?: e.javaClass.simpleName}", runLog)
                return
            }
            if (result.toolDown != null) {
                log.info("explain run: $label did not connect to the set_plan tool (${result.toolDown})")
                runLog.end(false, "$label did not connect to the set_plan tool.")
                fail("Explain This failed", "$label did not connect to the run's set_plan tool (${result.toolDown}), so it has no way to send the walkthrough.", runLog)
                return
            }
            if (!result.cancelled) {
                log.info("explain run shape ${ExplainTimings.key(effort, depth, runModel)}: ${progress.report()}")
                if (!result.isError) progress.observed()?.let { timings.record(effort, depth, runModel, it) }
            }
            keepPlanDir = !reportResult(project, request, result, tracker, runLog, early, planBefore, agent = label)
        } finally {
            tool.close(server)
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
     * Open the run's endpoint and write its config into [planDir], or null — the run then POSTs its plan.
     * Each plan the tool loads is offered to [early], so the reader hears "ready" as it lands.
     */
    private fun openTool(server: ExplainerServer, project: Project, planDir: Path, early: EarlyPlan): ToolRoute? {
        // The run's plans reach the project window it was started from, and no other.
        val run = server.openRun(ProjectExplainer.of(project), planDir) { resolved, issues -> early.arrived(resolved, issues) }
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
     * A fresh `run-*` directory under the IDE's temp path for this run's POST payloads, or
     * null when one cannot be made — the prompt then names none.
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
     * A Windows SAPI-only language exists only after voice discovery has run, so this warms it
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
        runLog: RunLog,
        early: EarlyPlan,
        planBefore: Plan?,
        agent: String = "Claude",
    ): Boolean {
        val announced = early.loaded
        if (result.cancelled) {
            runLog.end(false, "Cancelled.")
            tracker.finish() // clear the banner; the balloon reports the cancellation
            notify(project, NotificationType.INFORMATION, "Explain This cancelled", "The walkthrough request was cancelled.", runLog)
            return true
        }
        // Failed even when its plan arrived first: the run broke the one promise the agent setting makes.
        result.policyBreach?.let { why ->
            runLog.end(false, why)
            tracker.fail(StringUtil.shortenTextWithEllipsis(why, 300, 0))
            notify(project, NotificationType.ERROR, "Explain This stopped: $agent acted without asking", errorHtml(why), runLog)
            return false
        }

        val state = runCatching { ProjectExplainer.of(project).walkthrough.getState() }.getOrNull()
        // Frames, not projected segments: a plan whose frames all resolved but whose steps were dropped is
        // still a plan that arrived, and the notification below says how much of it there is. **This run's
        // plan**: one the run delivered (the tool, the early Write), or a plan other than the one showing when
        // it started (a POST). The walkthrough an earlier run left showing is not this run's success.
        val planLoaded = state != null && state.title.isNotEmpty() && state.plan.frames.isNotEmpty() &&
            (early.received || state.plan !== planBefore)

        if (result.isError && !planLoaded) {
            val detail = result.finalText?.takeIf { it.isNotBlank() } ?: result.stderrTail.takeIf { it.isNotBlank() }
            // The run log takes the whole reason (the banner gets a line of it): it is where a failure is read.
            runLog.end(false, "$agent did not produce a walkthrough (exit ${result.exitCode})." +
                (detail?.let { "\n" + StringUtil.shortenTextWithEllipsis(it.trim(), 4000, 0) } ?: ""))
            tracker.fail(detail?.let { StringUtil.shortenTextWithEllipsis(it.trim(), 300, 0) }
                ?: "$agent did not produce a walkthrough (exit ${result.exitCode}).")
            notify(
                project, NotificationType.ERROR, "Explain This failed",
                buildString {
                    append("$agent did not produce a walkthrough (exit ${result.exitCode}).")
                    if (detail != null) append("<br/><br/>").append(errorHtml(detail))
                },
                runLog,
            )
            return false
        }

        if (planLoaded) {
            runLog.end(true, "Walkthrough loaded: ${state.plan.frames.size} frame(s), ${state.plan.steps.size} step(s).")
            tracker.finish() // plan arrived via set_plan → walkthrough; the banner's job is done
            if (!announced) announceReady(project, state, runLog)
            return true
        } else {
            // The agent reported success but no plan reached the server.
            runLog.end(false, "$agent finished but no walkthrough was received by the plugin." +
                (result.finalText?.takeIf { it.isNotBlank() }?.let { "\n" + StringUtil.shortenTextWithEllipsis(it.trim(), 4000, 0) } ?: ""))
            tracker.fail("$agent finished but no walkthrough was received by the plugin.")
            notify(
                project, NotificationType.WARNING, "No walkthrough loaded",
                buildString {
                    append("$agent finished but no walkthrough was received by the plugin.")
                    result.finalText?.takeIf { it.isNotBlank() }?.let { append("<br/><br/>").append(errorHtml(it)) }
                },
                runLog,
            )
        }
        return false
    }

    /** With the loop warning when the run had one: the plan arrived, but from a run that may have lost the task. */
    private fun announceReady(project: Project, state: WalkthroughSnapshot, runLog: RunLog) {
        activateSidebar(project)
        notify(
            project, NotificationType.INFORMATION, "Walkthrough ready",
            "<b>${StringUtil.escapeXmlEntities(state.title)}</b> — ${state.plan.frames.size} frame(s), " +
                "${state.plan.steps.size} step(s). " +
                "Open the <b>Audio Explainer</b> tool window and press Play." +
                (runLog.warning?.let {
                    "<br/><br/>⚠ ${StringUtil.escapeXmlEntities(it)}. Check that the walkthrough covers what you asked."
                } ?: ""),
            runLog,
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
     * spinner over a walkthrough that already existed. So the plugin takes it
     * from the stream and loads it through the server's own validation.
     *
     * **Only a clean plan loads early** ([ExplainerServer.preload]): one whose reply would carry an `[error]` or
     * a `[warn]` is one the agent is about to fix and re-POST, and showing it first would put a broken
     * walkthrough on screen and then swap it. The agent's POST of the same file is then a no-op
     * ([Walkthrough.setPlanUnlessShowing]) — no reload, no "replaced" banner, the reader's position kept.
     */
    private inner class EarlyPlan(
        private val project: Project,
        private val tracker: ExplainSessionTracker,
        private val runLog: RunLog,
    ) {
        @Volatile
        var loaded = false
            private set

        /** A plan of this run's reached the walkthrough — clean or not, announced or not. */
        @Volatile
        var received = false
            private set

        fun offer(content: String) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val resolved = runCatching { service<ExplainerServerService>().server.preload(ProjectExplainer.of(project), content) }
                    .onFailure { log.warn("explain run: loading the written plan early failed", it) }
                    .getOrNull()
                if (resolved == null) {
                    log.info("explain run: the written plan is not clean, so it waits for the agent's POST")
                    return@executeOnPooledThread
                }
                received = true
                log.info("explain run: plan loaded from its Write — ${resolved.frames.size} frame(s), ${resolved.steps.size} step(s)")
                announce()
            }
        }

        /**
         * A plan the tool has loaded. It is already showing; what waits is only the "ready" — for a plan whose
         * reply carries more than `[info]`, because the agent is about to send a fixed one.
         */
        fun arrived(resolved: ResolvedPlan, issues: List<PlanIssue>) {
            received = true
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
            announceReady(project, ProjectExplainer.of(project).walkthrough.getState(), runLog)
        }
    }

    /** The plan the walkthrough shows now, by identity: every plan a run delivers is a newly decoded one. */
    private fun showingPlan(project: Project): Plan? =
        runCatching { ProjectExplainer.of(project).walkthrough.getState().plan }.getOrNull()

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

    /** With a "Run log" link when there is a run to show. */
    private fun notify(project: Project, type: NotificationType, title: String, content: String, runLog: RunLog? = null) {
        ApplicationManager.getApplication().invokeLater {
            val n = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(title, content, type)
            if (runLog != null) n.addAction(NotificationAction.createSimple("Run log") { RunLogWindow.show(project, runLog) })
            n.notify(project)
        }
    }

    private fun errorHtml(message: String?): String =
        StringUtil.escapeXmlEntities(StringUtil.shortenTextWithEllipsis(message ?: "Unknown error", 600, 0))

    companion object {
        const val NOTIFICATION_GROUP = "Audio Explainer"
        private const val TOOL_WINDOW_ID = "Audio Explainer"
        /** What this client tells an ACP agent it is. */
        private const val CLIENT_VERSION = "1"

        fun getInstance(): ClaudeExplainer = service()
    }
}
