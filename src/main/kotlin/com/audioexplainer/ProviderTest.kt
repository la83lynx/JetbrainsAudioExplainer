package com.audioexplainer

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * **"Test connection"**: the user's real Claude Code, pointed at the profile, asked for one word.
 *
 * The same `--settings` file a run gets, so a pass here is a pass for the run's provider wiring; ~3–10 s when tested
 * (2026-11-05). Stops at the first 401/403 `api_retry`, as a run does — otherwise a bad key would hold the Settings
 * dialog for the CLI's ~3 minutes of retries — and gives up after [TIMEOUT_S].
 */
object ProviderTest {
    private const val TIMEOUT_S = 30L
    private const val PROMPT = "Reply with the single word ok."
    private val json = Json { ignoreUnknownKeys = true }

    class Outcome(val ok: Boolean, val message: String)

    /** Off the EDT. [key] is the stored one; a keyless preset sends [Providers.OLLAMA_TOKEN]. */
    fun run(profile: ProviderProfile, key: String?, claudePath: String, indicator: ProgressIndicator): Outcome {
        Providers.baseUrlProblem(profile.baseUrl)?.let { return Outcome(false, "The base URL $it.") }
        if (profile.model.isBlank()) return Outcome(false, "Set a model id first.")
        if (key == null && Providers.needsKey(profile)) return Outcome(false, "No API key is stored for ${profile.name}.")
        val exe = ExecutableFinder.findClaude(claudePath) ?: return Outcome(false, "Couldn't find the claude executable.")
        val file = ProviderFiles.write(Providers.settingsJson(profile, key))
        val started = System.nanoTime()
        try {
            val cmd = GeneralCommandLine(
                exe.toString(), "-p", PROMPT,
                "--output-format", "stream-json", "--verbose",
                "--strict-mcp-config", "--settings", file.toString(),
            )
                .withWorkDirectory(PathManager.getTempPath())
                .withCharset(StandardCharsets.UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withRedirectErrorStream(true)
            val proc = cmd.createProcess()
            runCatching { proc.outputStream.close() }
            val lines = LinkedBlockingQueue<String>()
            Thread({
                runCatching { proc.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { lines.offer(it) } }
                lines.offer(EOF)
            }, "Audio Explainer provider test").apply { isDaemon = true; start() }
            val other = StringBuilder()
            val deadline = started + TimeUnit.SECONDS.toNanos(TIMEOUT_S)
            try {
                while (true) {
                    if (indicator.isCanceled) return Outcome(false, "Cancelled.")
                    if (System.nanoTime() > deadline) return Outcome(false, "No answer within ${TIMEOUT_S}s.")
                    val line = lines.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (line == EOF) break
                    Providers.retry(line)?.let { r ->
                        if (r.authRejected) return Outcome(false, "Key rejected (HTTP ${r.status}).")
                        indicator.text2 = "Provider busy (${r.status ?: r.error}), retrying ${r.attempt}/${r.maxRetries}…"
                        return@let
                    }
                    val obj = runCatching { json.parseToJsonElement(line.trim()).jsonObject }.getOrNull()
                    if (obj == null) { other.append(line).append('\n'); continue }
                    if (obj["type"]?.jsonPrimitive?.contentOrNull != "result") continue
                    val text = obj["result"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val error = obj["is_error"]?.jsonPrimitive?.contentOrNull == "true" ||
                        obj["subtype"]?.jsonPrimitive?.contentOrNull != "success"
                    val secs = "%.1f".format((System.nanoTime() - started) / 1e9)
                    return if (error) Outcome(false, text.ifBlank { "The run ended in an error." }.take(300))
                    else Outcome(true, "OK — ${profile.model.trim()} answered in ${secs}s: \"${text.take(40)}\"")
                }
            } finally {
                if (proc.isAlive) proc.destroyForcibly()
            }
            val rest = Providers.dropHarmlessStderr(other.toString())
            return Outcome(false, "Claude Code exited without an answer (exit ${runCatching { proc.waitFor() }.getOrNull()})" +
                (if (rest.isNotBlank()) ": ${rest.takeLast(300)}" else "."))
        } finally {
            ProviderFiles.delete(file)
        }
    }

    private const val EOF = "\u0000eof"
}
