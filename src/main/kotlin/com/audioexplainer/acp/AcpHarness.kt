package com.audioexplainer.acp

import com.audioexplainer.AudioExplainerSettings
import com.audioexplainer.ClaudeExplainer
import com.audioexplainer.ExecutableFinder
import com.audioexplainer.ExplainProgress
import com.audioexplainer.RunLog
import com.audioexplainer.RunLoop
import com.audioexplainer.RunReader
import com.audioexplainer.RunResult
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.execution.ParametersListUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * **An ACP agent as the harness**: the agent's process, one [AcpSession] over its stdio, and the
 * same [RunLoop] Claude's runs use — so the bar, the run log, the stops and the result are the ones a Claude run
 * gets.
 *
 * **Spawned per run**: 2-3 s of startup in the spike, and nothing carried between runs or projects. The key
 * travels in the child's environment only ([AgentLaunch.env]) and is scrubbed from every logged message.
 *
 * **Cancel is `session/cancel`, then the process tree after 3 s** — a Node agent leaves its children otherwise.
 */
internal object AcpHarness {
    private val log = Logger.getInstance(ClaudeExplainer::class.java)

    fun run(
        exe: Path,
        launch: AgentLaunch,
        cwd: String,
        server: AcpSession.McpServer,
        /** The prompt's blocks, given whether the agent takes embedded resources ([AcpSession.embeddedContext]). */
        prompt: (embedded: Boolean) -> JsonArray,
        roots: List<String>,
        indicator: ProgressIndicator,
        progress: ExplainProgress,
        runLog: RunLog,
        onActivity: (String, Double?, String?) -> Unit,
        provider: String?,
        toolConnected: () -> Boolean,
        clientVersion: String,
    ): RunResult {
        val lock = Any()
        val reader = RunReader(progress, runLog, provider, needsTool = true, onPlanWritten = {}, info = log::info, toolConnected = toolConnected)
        val secrets = AgentPresets.secrets(launch)
        val scrub = { s: String -> secrets.fold(s) { acc, k -> acc.replace(k, "***") } }
        val process = GeneralCommandLine(listOf(exe.toString()) + launch.args)
            .withWorkDirectory(cwd)
            .withEnvironment(launch.env)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .createProcess()
        val stderr = StringBuilder()
        Thread({
            runCatching {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                    synchronized(stderr) {
                        stderr.append(scrub(line)).append('\n')
                        if (stderr.length > 20_000) stderr.delete(0, stderr.length - 10_000)
                    }
                }
            }
        }, "Audio Explainer ACP stderr").apply { isDaemon = true; start() }

        // **Stopped at once, not cancelled**: an agent that acts without asking would go on acting in a cancel's grace.
        val breach = AtomicReference<String?>()
        val session = AcpSession(
            process.inputStream, process.outputStream,
            onEvents = { events -> synchronized(lock) { reader.onLine(events) } },
            onRefused = { what, why -> runLog.note("  ✖ refused $what — $why") },
            roots = roots,
            readText = ::ideText,
            log = { if (log.isDebugEnabled) log.debug("acp $it") },
            scrub = scrub,
            onUnasked = { what ->
                if (breach.compareAndSet(null, what)) {
                    log.warn("explain run: the ACP agent $what without asking for permission; stopping it")
                    runLog.note("  ✖ $what WITHOUT ASKING — the agent was stopped")
                    Thread({ kill(process) }, "Audio Explainer ACP stop").apply { isDaemon = true; start() }
                }
            },
        )
        val done = CountDownLatch(1)
        val worker = Thread({
            try {
                session.start()
                if (session.open(cwd, server, launch.sessionModel, clientVersion)) {
                    runLog.note("Agent: ${session.agent ?: exe.fileName}" + if (session.embeddedContext) ", the skill docs attached" else "")
                    session.prompt(prompt(session.embeddedContext))
                }
            } catch (e: Exception) {
                log.warn("ACP run failed", e)
                synchronized(lock) { reader.onLine(AcpEvents().finished(null, e.message ?: e.javaClass.simpleName)) }
            } finally {
                done.countDown()
            }
        }, "Audio Explainer ACP run")

        try {
            val result = RunLoop(indicator, progress, runLog, reader, lock, onActivity, provider).run(
                start = { worker.start() },
                waitFor = { ms -> done.await(ms, TimeUnit.MILLISECONDS) },
                stop = {
                    runCatching { session.cancel() }
                    done.await(CANCEL_GRACE_MS, TimeUnit.MILLISECONDS)
                    kill(process)
                },
                exitCode = { if (process.isAlive) 0 else process.exitValue() },
                stderr = { synchronized(stderr) { stderr.toString() } },
            )
            breach.get()?.let { what ->
                val agent = session.agent ?: exe.fileName.toString()
                return RunResult(-1, null, isError = true, cancelled = false, stderrTail = "", policyBreach =
                    "$agent $what without asking for permission, so the run was stopped. Explain This lets an agent " +
                        "only read and send the plan, and refuses everything else it asks about — but an agent " +
                        "whose own config allows a tool runs it without asking. Check what it changed. Set the agent " +
                        "to ask before edits, commands and fetches, or use the opencode or Qwen Code preset, which do.")
            }
            if (!result.isError || result.cancelled || result.toolDown != null) return result
            // A run that failed before its prompt ended says why on stderr, more often than in its JSON-RPC error;
            // an agent that refuses for want of a login says it in its own terms ("Use /auth"), so say ours.
            val why = listOfNotNull(
                result.finalText?.takeIf { it.isNotBlank() },
                AgentPresets.authHint(result.finalText,session.agent ?: exe.fileName.toString(), provider),
                result.stderrTail.takeIf { it.isNotBlank() }?.let { "Its stderr:\n$it" },
            ).joinToString("\n")
            return RunResult(result.exitCode, why.ifBlank { null }, isError = true, cancelled = false, stderrTail = result.stderrTail)
        } finally {
            session.connection.close()
            if (!process.waitFor(EXIT_GRACE_MS, TimeUnit.MILLISECONDS)) kill(process)
        }
    }

    /**
     * The agent's executable and the arguments a Custom command line adds; null when it cannot be found. One lookup
     * for the run and for its diagnostic, so the two cannot look in different places.
     */
    fun find(harness: String, state: AudioExplainerSettings.State): Pair<Path?, List<String>> {
        if (harness == AgentPresets.CUSTOM) {
            val parts = ParametersListUtil.parse(state.customAgentCommand.trim())
            val head = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null to emptyList()
            val path = runCatching { Path.of(head) }.getOrNull()
            val exe = if (path != null && path.isAbsolute) path.takeIf { Files.isRegularFile(it) }
            else ExecutableFinder.find(head, null, emptyList())
            return exe to parts.drop(1)
        }
        val override = if (harness == AgentPresets.OPENCODE) state.opencodePath else state.qwenPath
        return ExecutableFinder.findAgent(AgentPresets.command(harness) ?: harness, override) to emptyList()
    }

    /** What an agent says of itself: its `agentInfo`, and whether it can reach an HTTP MCP server. */
    class Hello(val agent: String?, val httpMcp: Boolean, val error: String?)

    /**
     * Start the agent, say `initialize`, and stop it — the diagnostic's look at an agent, within [timeoutMs]. No
     * session, no provider, no key.
     */
    fun hello(exe: Path, args: List<String>, cwd: String, timeoutMs: Long = 8_000): Hello {
        val process = try {
            GeneralCommandLine(listOf(exe.toString()) + args)
                .withWorkDirectory(cwd)
                .withCharset(StandardCharsets.UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .createProcess()
        } catch (e: Exception) {
            return Hello(null, false, e.message ?: e.javaClass.simpleName)
        }
        val conn = AcpConnection(process.inputStream, process.outputStream,
            { m, _ -> throw AcpException(AcpConnection.METHOD_NOT_FOUND, "$m is not supported") }, { _, _ -> })
        return try {
            conn.start("Audio Explainer ACP diagnostic")
            val init = conn.request("initialize", buildJsonObject {
                put("protocolVersion", 1)
                put("clientCapabilities", buildJsonObject {
                    put("fs", buildJsonObject { put("readTextFile", true); put("writeTextFile", false) })
                    put("terminal", false)
                })
                put("clientInfo", buildJsonObject { put("name", "audio-explainer"); put("version", "1") })
            }, timeoutMs) as? JsonObject
            fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
            val info = init?.get("agentInfo") as? JsonObject
            val agent = listOfNotNull(info?.str("title") ?: info?.str("name"), info?.str("version")).joinToString(" ").ifBlank { null }
            val http = ((init?.get("agentCapabilities") as? JsonObject)?.get("mcpCapabilities") as? JsonObject)?.str("http") == "true"
            Hello(agent, http, null)
        } catch (e: AcpException) {
            Hello(null, false, e.message)
        } finally {
            conn.close()
            if (!process.waitFor(EXIT_GRACE_MS, TimeUnit.MILLISECONDS)) kill(process)
        }
    }

    private const val CANCEL_GRACE_MS = 3_000L
    private const val EXIT_GRACE_MS = 2_000L

    private fun kill(process: Process) {
        runCatching { process.toHandle().descendants().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }

    /**
     * What the agent reads: the editor's text when the file is open — unsaved edits included — else the file. A
     * document's text has `\n` line ends whatever the file's own.
     */
    private fun ideText(path: String): String? {
        val nio = runCatching { Path.of(path) }.getOrNull() ?: return null
        val file = LocalFileSystem.getInstance().findFileByNioFile(nio)
        if (file != null && !file.isDirectory) {
            ReadAction.compute<String?, RuntimeException> { FileDocumentManager.getInstance().getCachedDocument(file)?.text }
                ?.let { return it }
        }
        return runCatching { Files.readString(nio) }.getOrNull()
    }
}
