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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
        val cmd = try {
            GeneralCommandLine(claudeExe.toString())
                .withParameters(buildArgs(buildPrompt(request, skillDir)))
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

    // ── Command / prompt construction ──────────────────────────────────────────

    private fun buildArgs(prompt: String): List<String> = buildList {
        add("-p"); add(prompt)
        add("--output-format"); add("stream-json")
        add("--verbose") // required alongside stream-json in print mode
        // Scoped allow-list: only these tools may run without an (impossible, headless) prompt.
        // Bash is intentionally command-unrestricted so the POST works cross-platform (curl on any
        // OS, powershell on Windows, the .sh helper elsewhere) — tighten ALLOWED_TOOLS to narrow it.
        add("--allowedTools"); add(ALLOWED_TOOLS)
    }

    private fun buildPrompt(request: ExplainRequest, skillDir: Path?): String {
        val skillPath = skillDir?.toString()?.replace('\\', '/')
        val langCode = AudioExplainerSettings.getInstance().state.explanationLanguage
        // If the configured language is a Windows SAPI-only one (Phase L7), it exists only after voice
        // discovery has run — warm it so [Languages.byCode] resolves its display name/autonym for the
        // prompt even on the first Explain This of a session. Cached → instant when already discovered.
        if (langCode != Languages.DEFAULT_CODE && Languages.byCode(langCode).code != langCode) {
            runCatching { TtsService.getInstance().sapiVoices(refresh = false) }
        }
        val lang = Languages.byCode(langCode)
        // Scope-specific framing (Phase 5): the editor cursor flow vs. a file / folder-or-project chosen in
        // the Solution Explorer. The set_plan schema is identical — only what Claude is asked to cover differs.
        val intro = when (request.scope) {
            ExplainScope.CURSOR ->
                "The user right-clicked \"Explain This (Audio)\" in their editor to get an automatic, " +
                    "narrated walkthrough of the code at their cursor."
            ExplainScope.FILE ->
                "The user right-clicked \"Explain This (Audio)\" on a file in the Solution Explorer to get " +
                    "an automatic, narrated walkthrough of that whole file."
            ExplainScope.DIRECTORY ->
                "The user right-clicked \"Explain This (Audio)\" on a folder / project (or several selected " +
                    "items) in the Solution Explorer to get an automatic, narrated tour of that code."
        }
        val contextLabel = when (request.scope) {
            ExplainScope.CURSOR -> "Cursor context"
            ExplainScope.FILE -> "Target file"
            ExplainScope.DIRECTORY -> "Target folder / project"
        }
        val step1 = when (request.scope) {
            ExplainScope.CURSOR ->
                "1. Read in/around the cursor and expand to the enclosing method/class — YOU do the symbol resolution."
            ExplainScope.FILE ->
                "1. Read the whole target file and pick the parts worth narrating — its key types, methods, and flow."
            ExplainScope.DIRECTORY ->
                "1. Explore the listed folder/project path(s) — read the important files and pick the pieces " +
                    "worth a guided tour. Keep it lean; don't try to cover every file."
        }
        return buildString {
            appendLine(
                "You are running headless inside the \"Audio Explainer\" IDE plugin. $intro",
            )
            appendLine()
            appendLine(
                "This is a NON-INTERACTIVE run: there is no human to answer questions. Do NOT ask anything, " +
                    "do NOT wait — act autonomously and finish in one pass.",
            )
            appendLine()
            if (lang.code != Languages.DEFAULT_CODE) {
                appendLine(
                    "LANGUAGE: Generate the walkthrough in ${lang.displayName} (${lang.autonym}). Write the " +
                        "`title`, every frame `title` and `explanation`, and every step `ttsText`, " +
                        "`explanation` and `note` in ${lang.displayName}. Keep code identifiers, file paths, and API names " +
                        "verbatim (do NOT translate code) — a beat's `token` and a frame's `callToken` especially, since " +
                        "both must match the source text exactly to resolve, and `example`, which is code. The same split " +
                        "applies to a frame's `relations`: their `note` is prose, their `token` and `symbol` are code. " +
                        "You MUST put \"language\":\"${lang.code}\" at the top " +
                        "level of the `set_plan` JSON.",
                )
                appendLine()
            }
            appendLine("$contextLabel (JSON):")
            appendLine(buildContextJson(request))
            appendLine()
            if (skillPath != null) {
                appendLine("Follow the headless flow documented here (read it first):")
                appendLine("  $skillPath/docs/explain-at-cursor.md")
                appendLine("The `set_plan` schema is documented ONCE, authoritatively — read it before writing the plan:")
                appendLine("  $skillPath/docs/plan-schema.md")
                appendLine("The helpers that POST the plan are in $skillPath/scripts/ (explainer.ps1 on Windows, explainer.sh elsewhere).")
                appendLine()
            }
            appendLine("What to do:")
            appendLine(step1)
            if (skillPath != null) {
                appendLine(
                    "2. Build a lean `set_plan` (usually 1-2 frames) with dense, pointer-style steps and good " +
                        "plain-text `ttsText`, following docs/plan-schema.md exactly. A plan is `frames` (each one a " +
                        "file plus the `keep` ranges visible in it — header rows as \"context\", the member itself as " +
                        "\"focus\") plus `steps` (the narration, in order, each naming its frame and the `ranges` it " +
                        "lights). EVERY line a step lights must be inside its frame's `keep`. Where a one-line aside " +
                        "next to the code would genuinely help (a constraint, a gotcha, the reason behind a magic " +
                        "number), add an optional one-sentence `note` to that step — sparingly, not on every one. " +
                        "Where a step is really about ONE identifier, add an optional `beats` entry — " +
                        "{\"line\":N,\"token\":\"…\"} with the name copied verbatim from THAT line, so the plugin " +
                        "can pulse it — again sparingly. Where a concrete " +
                        "case would clarify a tricky transformation or a surprising result, add an optional " +
                        "ONE-LINE `example` (\"Parse(\\\"2024-01\\\") → DateOnly(2024, 1, 1)\") — one line only, and " +
                        "only where it teaches something the code doesn't show. Where two places are genuinely " +
                        "linked — a value produced on one line and consumed on another, a call whose target is in " +
                        "another file — add an optional frame-level `relations` entry and the plugin will draw the " +
                        "connection; 2-3 per frame at most, and anchor an endpoint inside the step it belongs to. " +
                        "Declare a link ONCE: a cross-file one is drawn as a matched pair of portals, one in each " +
                        "place, so give its far end both `file` and `line`. " +
                        "Every plan renders as a tree of code — do not send `view` at all. When the explanation IS " +
                        "a caller-callee chain, give each nested frame `parent`, `callLine` and `callToken` (the " +
                        "call line must be a line the parent keeps). When it walks over several unrelated places " +
                        "in turn, group the frames into `scene`s: frames sharing a `scene` are on screen together " +
                        "and a step in another scene replaces them, so a long walkthrough keeps a handful of files " +
                        "visible rather than all of them. A `parent` must be in the same `scene`.",
                )
            } else {
                // No skill dir on disk, so docs/plan-schema.md is unreachable and the agent has nothing to
                // read. This inline copy is the FALLBACK mirror only — keep it in sync with that doc, which
                // stays the source of truth for every other caller.
                appendLine(
                    "2. Build a lean `set_plan` (usually 1-2 frames) with dense, pointer-style steps and good " +
                        "plain-text `ttsText`. Schema (1-based, inclusive line ranges; absolute file paths):",
                )
                appendLine(
                    "   {\"type\":\"set_plan\",\"title\":\"…\",\"language\":\"${lang.code}\"," +
                        "\"frames\":[{\"id\":0,\"file\":\"…\",\"title\":\"…\",\"explanation\":\"…\",\"scene\":0," +
                        "\"keep\":[{\"start\":N,\"end\":N,\"role\":\"context\"},{\"start\":N,\"end\":N,\"role\":\"focus\"}]," +
                        "\"relations\":[{\"kind\":\"flow\",\"from\":{\"line\":N,\"token\":\"…\"},\"to\":{\"line\":N,\"token\":\"…\"},\"note\":\"…\"}]}]," +
                        "\"steps\":[{\"frame\":0,\"ranges\":[{\"start\":N,\"end\":N}],\"ttsText\":\"…\",\"explanation\":\"…\"," +
                        "\"note\":\"…\",\"example\":\"…\",\"beats\":[{\"line\":N,\"token\":\"…\"}]}]}",
                )
                appendLine(
                    "   A frame is ONE file plus the `keep` ranges visible in it — everything else is folded away. List " +
                        "the class/method header rows yourself as \"context\" ranges (nothing derives them) and the " +
                        "member the frame is about as the one \"focus\" range. EVERY line a step lights must be inside " +
                        "its frame's `keep`, or it highlights a fold placeholder.",
                )
                appendLine(
                    "   Step `note` is optional: ONE plain-text sentence, drawn as a callout box beside the code. " +
                        "Use it only where a margin aside helps; omit it otherwise.",
                )
                appendLine(
                    "   Step `example` is optional: ONE line showing the code in action with concrete values " +
                        "(\"Parse(\\\"2024-01\\\") → DateOnly(2024, 1, 1)\"), drawn under the note. One line is a hard " +
                        "limit — no fences, no multi-statement snippets. Omit it unless a concrete case clarifies.",
                )
                appendLine(
                    "   Step `beats` is optional: [{\"line\":N,\"token\":\"…\"}], the ONE identifier that step is " +
                        "about, copied verbatim from that line (the plugin pulses it). The line must be one the " +
                        "step lights. Omit `beats` when there is no single focal name.",
                )
                appendLine(
                    "   Frame `relations` is optional: each entry links two places — `kind` \"flow\" (A feeds B) or " +
                        "\"backforth\", and `from`/`to` each {\"line\":N,\"token\":\"…\"}, or {\"file\":\"…\",\"line\":N,\"token\":\"…\"} " +
                        "for somewhere in another file, plus a short optional `note`. The plugin draws an arrow when both " +
                        "ends are lines of this file, and otherwise a matched pair of portals — one at each end, " +
                        "so give a cross-file end a `file`, a `line` AND a `token`. 2-3 per frame " +
                        "at most, and one endpoint MUST be inside the step the relation belongs to or it is never shown.",
                )
            }
            appendLine(
                "   Use field names frames/steps/keep/ranges/start/end/ttsText — never segments/highlights/" +
                    "startLine/endLine/label/subHighlights.",
            )
            appendLine("   The top-level \"language\" is the walkthrough's language code (\"en\" if English).")
            appendLine(
                "3. POST it to the loopback walkthrough server (port+token in ~/.claude-explainer-port and " +
                    "~/.claude-explainer-token). Use a skill helper script, or curl directly. A {\"ok\":true} reply = " +
                    "success. The reply also carries an `issues` array whenever the plan was partly dropped or " +
                    "degraded — READ IT: an [error] there means the user cannot see part of what you wrote " +
                    "(an unresolvable path, a step naming a frame that does not exist), so fix it and re-POST.",
            )
            appendLine(
                "4. Do NOT print the walkthrough as terminal text and do NOT send goto/resume — just `set_plan`. " +
                    "Print only a one-line status when done.",
            )
        }
    }

    private fun buildContextJson(r: ExplainRequest): String = buildJsonObject {
        put("file", r.file)
        put("absolutePath", r.absolutePath)
        if (r.scope == ExplainScope.CURSOR) {
            put("caretLine", r.caretLine)
            val sel = r.selectionText
            if (sel != null) {
                // Cap the inlined selection; Claude reads the file itself for anything longer.
                put("selectionText", StringUtil.shortenTextWithEllipsis(sel, MAX_SELECTION_CHARS, 0))
                put("selectionStartLine", r.selectionStartLine ?: r.caretLine)
                put("selectionEndLine", r.selectionEndLine ?: r.caretLine)
            } else {
                put("selectionText", JsonNull)
            }
        } else {
            // Solution-Explorer scope: the target is the whole file/folder (plus any extra selected items).
            put("scope", if (r.scope == ExplainScope.DIRECTORY) "directory" else "file")
            if (r.extraPaths.isNotEmpty()) {
                putJsonArray("targets") {
                    add(r.file)
                    r.extraPaths.forEach { add(it) }
                }
            }
        }
        r.projectBasePath?.let { put("projectRoot", it) }
    }.toString()

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
    private fun findClaudeExecutable(): Path? {
        val home = Path.of(System.getProperty("user.home"))
        return ExecutableFinder.find(
            "claude",
            AudioExplainerSettings.getInstance().state.claudeExecutablePath,
            listOf(
                home.resolve(".local/bin"),
                home.resolve(".claude/local"),
                home.resolve("AppData/Roaming/npm"),
                home.resolve("AppData/Local/Programs/claude"),
                home.resolve("bin"),
            ),
        )
    }

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
        private const val MAX_SELECTION_CHARS = 4000

        /** Enumerated tool allow-list for the headless run (see [buildArgs]). */
        private const val ALLOWED_TOOLS = "Read Grep Glob Task Write TodoWrite Bash"

        fun getInstance(): ClaudeExplainer = service()
    }
}
