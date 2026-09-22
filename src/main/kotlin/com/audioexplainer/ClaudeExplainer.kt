package com.audioexplainer

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 6 — the Claude Code backbone.
 *
 * Turns an [ExplainRequest] into an audio walkthrough by shelling out to a headless `claude -p`
 * (cwd = project root) with a prompt that carries the cursor context and triggers the bundled
 * `audio-explainer` skill's explain-at-cursor flow. Claude expands the symbol under the cursor,
 * builds a `set_plan`, and POSTs it to this plugin's loopback server ([ExplainerServer]) — which
 * drives the walkthrough, editor spotlight, and sidebar. The C# symbol is NOT resolved in-plugin
 * (decision 2); that's Claude's job.
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
        val prompt = ExplainPrompt.buildPrompt(request, skillDir, resolveLanguage(), depth, request.extraInstructions)
        val cmd = try {
            GeneralCommandLine(claudeExe.toString())
                .withParameters(ExplainPrompt.buildArgs(prompt, effort, model))
                .withWorkDirectory(workDir)
                .withCharset(StandardCharsets.UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        } catch (e: Exception) {
            log.warn("failed to build claude command line", e)
            tracker.fail(e.message ?: "Failed to start Claude.")
            notify(project, NotificationType.ERROR, "Explain This failed", errorHtml(e.message))
            return
        }

        indicator.text = "Claude is reading ${request.displayName} and planning the walkthrough…"
        tracker.progress("Reading ${request.displayName} and planning…")
        val result = try {
            runClaude(cmd, indicator, tracker::progress)
        } catch (e: Exception) {
            log.warn("claude invocation failed", e)
            tracker.fail(e.message ?: "Claude invocation failed.")
            notify(project, NotificationType.ERROR, "Explain This failed", errorHtml(e.message))
            return
        }

        reportResult(project, request, result, tracker)
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

    private class RunResult(
        val exitCode: Int,
        val finalText: String?,
        val isError: Boolean,
        val cancelled: Boolean,
        val stderrTail: String,
    )

    private fun runClaude(cmd: GeneralCommandLine, indicator: ProgressIndicator, onActivity: (String) -> Unit): RunResult {
        val handler = OSProcessHandler(cmd)
        val lock = Any()
        val lineBuf = StringBuilder()
        val stderr = StringBuilder()
        val finalText = AtomicReference<String?>(null)
        val isError = AtomicBoolean(false)

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
                        processStreamLine(line, indicator, finalText, isError, onActivity)
                        nl = lineBuf.indexOf("\n")
                    }
                }
            }
        })

        handler.startNotify()
        while (!handler.waitFor(200)) {
            if (indicator.isCanceled) {
                handler.destroyProcess()
                handler.waitFor(2000)
                return RunResult(-1, null, isError = false, cancelled = true, stderrTail = tail(synchronized(lock) { stderr.toString() }))
            }
        }

        val exit = handler.exitCode ?: -1
        return RunResult(
            exitCode = exit,
            finalText = finalText.get(),
            isError = isError.get() || exit != 0,
            cancelled = false,
            stderrTail = tail(synchronized(lock) { stderr.toString() }),
        )
    }

    /** Parse one `stream-json` event line: surface activity as progress, capture the final result. */
    private fun processStreamLine(
        line: String,
        indicator: ProgressIndicator,
        finalText: AtomicReference<String?>,
        isError: AtomicBoolean,
        onActivity: (String) -> Unit,
    ) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "assistant" -> {
                val content = obj["message"]?.jsonObject?.get("content") ?: return
                val blocks = runCatching { content.jsonArray }.getOrNull() ?: return
                for (block in blocks) {
                    val b = runCatching { block.jsonObject }.getOrNull() ?: continue
                    when (b["type"]?.jsonPrimitive?.contentOrNull) {
                        "tool_use" -> b["name"]?.jsonPrimitive?.contentOrNull?.let {
                            val activity = "Claude: $it…"
                            indicator.text2 = activity
                            onActivity(activity)
                        }
                        "text" -> b["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                            val activity = "Claude: " + StringUtil.shortenTextWithEllipsis(it.trim().replace('\n', ' '), 80, 0)
                            indicator.text2 = activity
                            onActivity(activity)
                        }
                    }
                }
            }
            "result" -> {
                finalText.set(obj["result"]?.jsonPrimitive?.contentOrNull)
                val subtype = obj["subtype"]?.jsonPrimitive?.contentOrNull
                val flagged = (obj["is_error"]?.jsonPrimitive?.contentOrNull == "true")
                if (flagged || (subtype != null && subtype != "success")) isError.set(true)
            }
        }
    }

    // ── Result reporting ───────────────────────────────────────────────────────

    private fun reportResult(project: Project, request: ExplainRequest, result: RunResult, tracker: ExplainSessionTracker) {
        if (result.cancelled) {
            tracker.finish() // clear the banner; the balloon reports the cancellation
            notify(project, NotificationType.INFORMATION, "Explain This cancelled", "The walkthrough request was cancelled.")
            return
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
            return
        }

        if (planLoaded) {
            tracker.finish() // plan arrived via set_plan → walkthrough; the banner's job is done
            activateSidebar(project)
            notify(
                project, NotificationType.INFORMATION, "Walkthrough ready",
                "<b>${StringUtil.escapeXmlEntities(state.title)}</b> — ${state.plan.frames.size} frame(s), " +
                    "${state.plan.steps.size} step(s). " +
                    "Open the <b>Audio Explainer</b> tool window and press Play.",
            )
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

    private fun tail(s: String, max: Int = 600): String =
        if (s.length <= max) s else "…" + s.substring(s.length - max)

    companion object {
        const val NOTIFICATION_GROUP = "Audio Explainer"
        private const val TOOL_WINDOW_ID = "Audio Explainer"

        fun getInstance(): ClaudeExplainer = service()
    }
}
