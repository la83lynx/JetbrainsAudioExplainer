package com.audioexplainer

import com.intellij.openapi.util.SystemInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** How a check came out. [INFO] is a row that reports rather than judges — it never counts as a failure. */
enum class CheckStatus { PASS, WARN, FAIL, INFO }

/** One finished row: what was checked, how it came out, and what to do about it. */
data class DiagnosticResult(
    val name: String,
    val status: CheckStatus,
    val detail: String,
    val remedy: String,
)

/** Which platforms a check applies to. A check that does not apply is omitted, never shown passing. */
enum class CheckOs { ANY, WINDOWS, UNIX }

/** What a check reports. A null [remedy] takes the check's own — see [DiagnosticCheck.remedy]. */
class Outcome(val status: CheckStatus, val detail: String, val remedy: String? = null)

/**
 * One check: its name, where it applies, **the fix it names** and how to run it.
 *
 * [remedy] is declared here rather than produced by [run] because "every check names its fix or it does
 * not earn a row" is a property of the check, not of how it happened to come out this time. A row that
 * says something is wrong and leaves the reader to guess is a row that costs attention and returns none.
 * An outcome may still override it when the fix genuinely depends on what was found.
 */
class DiagnosticCheck(
    val name: String,
    val os: CheckOs,
    val remedy: String,
    val run: (DiagnosticContext) -> Outcome,
)

/**
 * Everything the checks read, passed in rather than looked up, so `probes/EffortProbe.java` can run them
 * with no application: [state] is a plain `State()` there, and [server] is null.
 */
class DiagnosticContext(
    val state: AudioExplainerSettings.State,
    val isWindows: Boolean = SystemInfo.isWindows,
    /** The running loopback server, when there is one in this process — see [ExplainerServer.lastPingEcho]. */
    val server: ExplainerServer? = null,
    /** An open project's root, for the per-project `.mcp.json`. Null is fine; that check is informational. */
    val projectPath: String? = null,
)

/**
 * **"Check permissions and settings"** — read-only checks over the things that decide whether an
 * Explain This run can work at all, each with the fix it needs.
 *
 * It exists because the speed round's three defects were all invisible from inside the IDE: a POST helper
 * that corrupted its payload and printed nothing, a tool the agent reaches for that the allow-list
 * forbade, and an inherited `effortLevel` costing minutes per walkthrough. None of the three would show
 * up in a log anybody reads, and two of them produced no error at all.
 *
 * **Nothing here writes**, with one bounded exception: the round-trip check POSTs a `ping` (and writes a
 * temp file to send). `ping` was added to [ExplainerServer] for exactly this — `set_plan` would have
 * replaced whatever walkthrough the reader currently has loaded, which is a rude thing for a diagnostic
 * to do.
 */
object ExplainerDiagnostics {

    /** The CLI version `--effort` needs. Below this the flag is not understood and the run fails outright. */
    const val EFFORT_CLI_FLOOR = "2.1.274"

    /**
     * The round-trip payload: an em dash, an arrow, a middle dot and two non-Latin scripts.
     *
     * Not decoration — `E2 80 94` (the em dash) is the sequence that came back as `E2 3F 22` through a
     * single-byte code page, and that `0x22` is a double quote landing inside a JSON string, so the
     * *document* broke rather than the word. Any check that sends ASCII proves nothing.
     */
    const val ROUND_TRIP_TEXT = "em\u2014dash \u2192 arrow \u00b7 \u65e5\u672c\u8a9e \u00b7 \u0413\u0440\u0435\u0447\u0435\u0441\u043a\u0438\u0439"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── the catalogue ─────────────────────────────────────────────────────────

    /**
     * Every check, in report order. Pure — it builds the list and runs nothing, which is what lets the
     * probe assert the OS filter and the remedies without touching a process or the network.
     */
    fun allChecks(): List<DiagnosticCheck> = listOf(
        DiagnosticCheck(
            "claude on PATH", CheckOs.ANY,
            "Install Claude Code and make sure `claude` is on the PATH the IDE inherits, or set an explicit " +
                "path in Settings → Tools → Audio Explainer → Executables (advanced).",
        ) { ctx ->
            val exe = ExecutableFinder.findClaude(ctx.state.claudeExecutablePath)
            if (exe == null) Outcome(CheckStatus.FAIL, "not found on PATH or in the usual install directories")
            else Outcome(CheckStatus.PASS, exe.toString())
        },

        DiagnosticCheck(
            "CLI version", CheckOs.ANY,
            "Update Claude Code (`claude update`, or your installer). Below $EFFORT_CLI_FLOOR the `--effort` " +
                "flag this plugin now passes is not understood and the run fails before it starts.",
        ) { ctx ->
            val exe = ExecutableFinder.findClaude(ctx.state.claudeExecutablePath)
                ?: return@DiagnosticCheck Outcome(CheckStatus.FAIL, "no `claude` to ask")
            val out = exec(listOf(exe.toString(), "--version"))
            val version = Regex("""\d+\.\d+\.\d+""").find(out.text)?.value
            when {
                version == null -> Outcome(
                    CheckStatus.WARN,
                    "could not read a version out of: " + out.text.trim().take(120),
                )
                compareVersions(version, EFFORT_CLI_FLOOR) < 0 ->
                    Outcome(CheckStatus.FAIL, "$version — below the $EFFORT_CLI_FLOOR that `--effort` needs")
                else -> Outcome(CheckStatus.PASS, version)
            }
        },

        DiagnosticCheck(
            "Effort in force", CheckOs.ANY,
            "Set Effort in Settings → Tools → Audio Explainer → Explanation. A blank effort inherits " +
                "`~/.claude/settings.json`'s `effortLevel`, which is chosen for interactive coding: it was " +
                "measured costing a walkthrough 255s where `medium` took 118s for the same plan shape.",
        ) { ctx ->
            val cli = claudeSettings()
            val inherited = stringAt(cli, "effortLevel")
            val plugin = ctx.state.explainEffort.trim()
            // A provider profile drops `--model`: its model comes in the `--settings` env.
            val model = if (Providers.selected(ctx.state) != null) "" else ctx.state.explainModel.trim()
            val modelTail = if (model.isEmpty()) "" else "; `--model $model`"
            when {
                plugin.isNotEmpty() -> Outcome(
                    CheckStatus.PASS,
                    "every run passes `--effort $plugin`$modelTail" +
                        (inherited?.let { ", so your CLI default (`$it`) does not apply" } ?: ""),
                )
                inherited != null -> Outcome(
                    CheckStatus.WARN,
                    "Effort is blank, so every run inherits `$inherited` from ~/.claude/settings.json",
                )
                else -> Outcome(
                    CheckStatus.WARN,
                    "Effort is blank, so every run takes whatever the CLI defaults to — which this check " +
                        "cannot see, and which nothing in the IDE will report",
                )
            }
        },

        DiagnosticCheck(
            "Explain provider", CheckOs.ANY,
            "Open Settings → Tools → Audio Explainer → Explanation → Manage… and fix the profile: a base URL " +
                "with http(s) and a host, a model id, and an API key. \"Test connection\" beside it makes a real call.",
        ) { ctx ->
            val profile = Providers.selected(ctx.state)
                ?: return@DiagnosticCheck Outcome(CheckStatus.PASS, "Claude Code (your own setup) — nothing injected")
            val problems = buildList {
                Providers.baseUrlProblem(profile.baseUrl)?.let { add("the base URL $it") }
                if (profile.model.isBlank()) add("no model id")
                if (Providers.needsKey(profile) && runCatching { ProviderKeys.get(profile.id) }.getOrNull() == null) {
                    add("no API key stored")
                }
            }
            if (problems.isEmpty()) Outcome(
                CheckStatus.PASS,
                "${profile.name}: ${profile.model} at ${Providers.host(profile.baseUrl)} (not called here; Test does that)",
            )
            else Outcome(CheckStatus.FAIL, "${profile.name}: " + problems.joinToString(", "))
        },

        DiagnosticCheck(
            "MCP servers", CheckOs.ANY,
            "Nothing to fix — this row reports rather than judges. A walkthrough run passes `--strict-mcp-config`, " +
                "so none of these servers starts for it: none of their tools is on the allow-list " +
                "(`${ExplainPrompt.TOOL_ALLOWED_TOOLS}`), and loading them cost about 2.3s of startup. The one " +
                "server it does load is the plugin's own `set_plan` endpoint, named on its command line and " +
                "registered nowhere.",
        ) { ctx ->
            val n = countMcpServers(ctx.projectPath)
            Outcome(CheckStatus.INFO, "$n configured; a walkthrough run loads none of them, only its own")
        },

        DiagnosticCheck(
            "Skill installed and current", CheckOs.ANY,
            "Run Explain This once — the skill is (re)installed on every invocation. If it stays stale, check " +
                "that `~/.claude/skills/audio-explainer` is writable.",
        ) { _ ->
            val bundled = ClaudeSkillInstaller.bundledVersion()
            val installed = runCatching {
                Files.readString(ClaudeSkillInstaller.skillDir().resolve("VERSION")).trim()
            }.getOrNull()
            when {
                bundled == null -> Outcome(CheckStatus.WARN, "the plugin's own bundled VERSION could not be read")
                installed == null -> Outcome(
                    CheckStatus.WARN,
                    "not installed yet (bundled $bundled) — the next Explain This installs it",
                )
                installed != bundled -> Outcome(CheckStatus.WARN, "installed $installed, bundled $bundled")
                else -> Outcome(CheckStatus.PASS, "v$installed at ${ClaudeSkillInstaller.skillDir()}")
            }
        },

        DiagnosticCheck(
            "Loopback server", CheckOs.ANY,
            "The server starts with the first open project. If the files are missing, reopen a project; if " +
                "they are there and health does not answer, another process is holding a stale " +
                "`~/.claude-explainer-port` — close it and restart the IDE.",
        ) { _ ->
            val d = discovery()
            when {
                d == null -> Outcome(CheckStatus.FAIL, "~/.claude-explainer-port or -token is missing")
                else -> {
                    val health = httpGet("http://127.0.0.1:${d.port}/api/health", d.token)
                    if (health != null && health.contains("\"ok\"")) {
                        Outcome(CheckStatus.PASS, "127.0.0.1:${d.port} answers /api/health")
                    } else {
                        Outcome(CheckStatus.FAIL, "port ${d.port} is published but /api/health did not answer")
                    }
                }
            }
        },

        DiagnosticCheck(
            "POST round-trip (encoding)", CheckOs.ANY,
            "The helper script is reinstalled by the next Explain This. If the bytes still differ, the plan " +
                "is being re-encoded on the way out — that is the defect this check exists for, and it breaks " +
                "the JSON document, not just the word.",
        ) { ctx -> roundTrip(ctx) },

        // ── Windows ───────────────────────────────────────────────────────────

        DiagnosticCheck(
            "PowerShell present", CheckOs.WINDOWS,
            "Install PowerShell 7 (`pwsh`) or repair Windows PowerShell 5.1. Without one of them the " +
                "`explainer.ps1` helper cannot run and the plan has to fall back to curl.",
        ) { _ ->
            val found = listOf("pwsh", "powershell").mapNotNull { ExecutableFinder.find(it, null, emptyList()) }
            val builtin = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows",
                "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
            when {
                found.isNotEmpty() -> Outcome(CheckStatus.PASS, found.joinToString(", "))
                Files.isRegularFile(builtin) -> Outcome(CheckStatus.PASS, builtin.toString())
                else -> Outcome(CheckStatus.FAIL, "neither `pwsh` nor Windows PowerShell 5.1 resolves")
            }
        },

        DiagnosticCheck(
            "`PowerShell` tool allowed", CheckOs.WINDOWS,
            "The headless run's allow-list is `ExplainPrompt.ALLOWED_TOOLS`. The skill docs tell the agent to " +
                "POST the plan with `explainer.ps1`, so the tool it reaches for first has to be on it — " +
                "otherwise the call is refused and the turn is spent discovering that.",
        ) { _ ->
            val allowed = ExplainPrompt.ALLOWED_TOOLS.split(' ').filter { it.isNotBlank() }
            if ("PowerShell" in allowed) Outcome(CheckStatus.PASS, ExplainPrompt.ALLOWED_TOOLS)
            else Outcome(CheckStatus.FAIL, "the allow-list is `${ExplainPrompt.ALLOWED_TOOLS}`")
        },

        DiagnosticCheck(
            "Execution policy", CheckOs.WINDOWS,
            "A machine policy can refuse `-File` even under `-ExecutionPolicy Bypass`. Ask for an " +
                "`ExecutionPolicy` of at least RemoteSigned for CurrentUser, or unblock the script file.",
        ) { _ ->
            val script = Files.createTempFile("ae-policy", ".ps1")
            try {
                Files.write(script, "Write-Output 'AE_POLICY_OK'\n".toByteArray(StandardCharsets.UTF_8))
                val out = exec(
                    listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString()),
                )
                if (out.text.contains("AE_POLICY_OK")) Outcome(CheckStatus.PASS, "-File runs under Bypass")
                else Outcome(CheckStatus.FAIL, "running a script with -File produced: " + out.text.trim().take(160))
            } finally {
                runCatching { Files.deleteIfExists(script) }
            }
        },

        // ── Linux / macOS ─────────────────────────────────────────────────────

        DiagnosticCheck(
            "curl present", CheckOs.UNIX,
            "Install curl. `explainer.sh` has no fallback without it, so the plan has no way back to the IDE.",
        ) { _ ->
            val curl = ExecutableFinder.find("curl", null, listOf(Path.of("/usr/bin"), Path.of("/bin")))
            if (curl != null) Outcome(CheckStatus.PASS, curl.toString())
            else Outcome(CheckStatus.FAIL, "not found on PATH")
        },

        DiagnosticCheck(
            "explainer.sh executable", CheckOs.UNIX,
            "Delete `~/.claude/skills/audio-explainer` and run Explain This once; the installer sets +x on " +
                "copy. A file copied out of a jar carries no permission bits of its own.",
        ) { _ ->
            val sh = ClaudeSkillInstaller.skillDir().resolve("scripts/explainer.sh")
            when {
                !Files.isRegularFile(sh) -> Outcome(CheckStatus.WARN, "not installed yet: $sh")
                !Files.isExecutable(sh) -> Outcome(CheckStatus.FAIL, "present but not executable: $sh")
                else -> Outcome(CheckStatus.PASS, sh.toString())
            }
        },

        DiagnosticCheck(
            "\$HOME writable", CheckOs.UNIX,
            "The port and token files and the plan temp file all land in `\$HOME`. Fix its ownership, or " +
                "point HOME at a directory this IDE can write.",
        ) { _ ->
            val home = Path.of(System.getProperty("user.home"))
            val probe = home.resolve(".claude-explainer-writetest")
            val ok = runCatching {
                Files.write(probe, byteArrayOf())
                Files.deleteIfExists(probe)
                true
            }.getOrDefault(false)
            if (ok) Outcome(CheckStatus.PASS, home.toString())
            else Outcome(CheckStatus.FAIL, "cannot create a file in $home")
        },
    )

    /**
     * The checks that apply to [isWindows]. A check for another platform is **omitted**, not reported as
     * passing: a green "PowerShell: n/a" on Linux teaches the reader nothing and costs them a row.
     */
    fun checksFor(isWindows: Boolean): List<DiagnosticCheck> = allChecks().filter {
        when (it.os) {
            CheckOs.ANY -> true
            CheckOs.WINDOWS -> isWindows
            CheckOs.UNIX -> !isWindows
        }
    }

    /** Run every applicable check. Slow (subprocesses, one HTTP round trip) — never call this on the EDT. */
    fun run(ctx: DiagnosticContext): List<DiagnosticResult> = checksFor(ctx.isWindows).map { check ->
        val outcome = try {
            check.run(ctx)
        } catch (e: Exception) {
            // A throwing check still owes the reader a row: an exception here is itself a finding.
            Outcome(CheckStatus.FAIL, "the check itself failed: " + (e.message ?: e.javaClass.simpleName))
        }
        DiagnosticResult(check.name, outcome.status, outcome.detail, outcome.remedy ?: check.remedy)
    }

    /** The report as plain text, for the dialog's Copy button. */
    fun asText(results: List<DiagnosticResult>): String = buildString {
        appendLine("Audio Explainer — check permissions and settings")
        appendLine(java.time.LocalDateTime.now().withNano(0).toString())
        appendLine(System.getProperty("os.name") + " " + System.getProperty("os.version"))
        appendLine()
        for (r in results) {
            appendLine("[${r.status}] ${r.name} — ${r.detail}")
            appendLine("        fix: ${r.remedy}")
        }
    }

    // ── the round trip ────────────────────────────────────────────────────────

    /**
     * Send [ROUND_TRIP_TEXT] **through the platform's real helper** to the running server and compare what
     * arrived with what was sent.
     *
     * This is the only check that exercises the path end to end, and it is the one that would have caught
     * the encoding defect on day one instead of during a performance investigation.
     *
     * The verdict is taken from [ExplainerServer.lastPingEcho] — what this process decoded off the wire —
     * and not from the helper's printed reply, because printing is a second encoding hop: a correct POST
     * whose *output* went through a single-byte console code page would otherwise be reported as a failure
     * of the POST. With no server object in hand (the probe's case) the reply is all there is, and the row
     * says so.
     */
    private fun roundTrip(ctx: DiagnosticContext): Outcome {
        val d = discovery() ?: return Outcome(CheckStatus.FAIL, "no port/token file — the server is not running")
        val skill = ClaudeSkillInstaller.skillDir()
        val helper = if (ctx.isWindows) skill.resolve("scripts/explainer.ps1") else skill.resolve("scripts/explainer.sh")
        if (!Files.isRegularFile(helper)) {
            return Outcome(CheckStatus.WARN, "the helper is not installed yet: $helper")
        }
        val payload = "{\"type\":\"ping\",\"echo\":\"$ROUND_TRIP_TEXT\"}"
        val file = Files.createTempFile("ae-ping", ".json")
        try {
            Files.write(file, payload.toByteArray(StandardCharsets.UTF_8))
            val cmd = if (ctx.isWindows) {
                listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                    helper.toString(), "plan", file.toString())
            } else {
                listOf(helper.toString(), "plan", file.toString())
            }
            val out = exec(cmd, timeoutSec = 30)
            val received = ctx.server?.lastPingEcho()
            return when {
                received != null && received == ROUND_TRIP_TEXT ->
                    Outcome(CheckStatus.PASS, "${helper.fileName} delivered all ${ROUND_TRIP_TEXT.length} characters intact")
                received != null ->
                    Outcome(CheckStatus.FAIL, "the server received \"$received\" where \"$ROUND_TRIP_TEXT\" was sent")
                out.text.contains(ROUND_TRIP_TEXT) ->
                    Outcome(CheckStatus.PASS, "${helper.fileName} echoed the payload back (read from its output, " +
                        "not from the server — this process holds no server)")
                else ->
                    Outcome(CheckStatus.FAIL, "the reply did not carry the payload back: " + out.text.trim().take(200))
            }
        } finally {
            runCatching { Files.deleteIfExists(file) }
        }
    }

    // ── small helpers ─────────────────────────────────────────────────────────

    private class Discovery(val port: Int, val token: String)

    private fun discovery(): Discovery? {
        val home = Path.of(System.getProperty("user.home"))
        val port = runCatching { Files.readString(home.resolve(".claude-explainer-port")).trim().toInt() }.getOrNull()
        val token = runCatching { Files.readString(home.resolve(".claude-explainer-token")).trim() }.getOrNull()
        return if (port != null && !token.isNullOrEmpty()) Discovery(port, token) else null
    }

    private fun httpGet(url: String, token: String): String? = runCatching {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }.getOrNull()

    private class Exec(val exit: Int, val text: String)

    /** Run [cmd], merging stderr into stdout, decoding UTF-8. Never throws; a failure becomes its text. */
    private fun exec(cmd: List<String>, timeoutSec: Long = 15): Exec = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = p.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        Exec(if (finished) p.exitValue() else -1, text)
    } catch (e: Exception) {
        Exec(-1, e.message ?: e.javaClass.simpleName)
    }

    /** `~/.claude/settings.json`, or null when it is absent or unreadable. */
    private fun claudeSettings(): JsonObject? = runCatching {
        val f = Path.of(System.getProperty("user.home"), ".claude", "settings.json")
        if (!Files.isRegularFile(f)) null
        else json.parseToJsonElement(Files.readString(f)).jsonObject
    }.getOrNull()

    private fun stringAt(obj: JsonObject?, key: String): String? =
        (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /**
     * How many MCP servers a headless run would load: the user-level `~/.claude.json` and
     * `~/.claude/settings.json`, plus the project's own `.mcp.json`. Counted from configuration rather than
     * by asking the CLI, because this row is informational and must not cost the reader a subprocess.
     */
    private fun countMcpServers(projectPath: String?): Int {
        val files = listOfNotNull(
            Path.of(System.getProperty("user.home"), ".claude.json"),
            Path.of(System.getProperty("user.home"), ".claude", "settings.json"),
            projectPath?.let { Path.of(it, ".mcp.json") },
        )
        var n = 0
        for (f in files) {
            val obj = runCatching {
                if (Files.isRegularFile(f)) json.parseToJsonElement(Files.readString(f)).jsonObject else null
            }.getOrNull() ?: continue
            n += (obj["mcpServers"] as? JsonObject)?.size ?: 0
        }
        return n
    }

    /**
     * Compare two dotted numeric versions component by component; missing components count as 0.
     *
     * Public because it is the whole verdict of the CLI-version row and `probes/EffortProbe.java` measures
     * it: `2.1.27` is *below* `2.1.274`, which a string comparison gets right by luck and a "major.minor"
     * comparison misses entirely.
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrNull(i) ?: 0).compareTo(pb.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }
}
