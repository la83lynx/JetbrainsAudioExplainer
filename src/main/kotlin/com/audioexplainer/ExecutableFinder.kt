package com.audioexplainer

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path

/**
 * Robust, cross-platform lookup of a CLI executable (`claude`, `python`, …).
 *
 * Why this exists: IntelliJ's [PathEnvironmentVariableUtil.findInPath] matches the **exact** file name
 * and does NOT append `PATHEXT`, so on Windows `findInPath("claude")` never matches the real
 * `claude.exe`/`claude.cmd`. We therefore try each executable extension explicitly. We also probe
 * well-known install directories **directly** (independent of `PATH`), because the IDE/Gradle launcher
 * can inherit a narrower environment than the user's interactive shell (a `~/.local/bin` added by a
 * shell profile or a just-run installer may be missing from the process `PATH`). Finally, an explicit
 * user-configured path always wins.
 */
object ExecutableFinder {

    /**
     * Resolve [name] to a concrete executable path, or null. Order: explicit [overridePath] →
     * `PATH` (trying `PATHEXT` variants) → each of [candidateDirs] (trying `PATHEXT` variants).
     */
    fun find(name: String, overridePath: String?, candidateDirs: List<Path>): Path? {
        overridePath?.trim()?.takeIf { it.isNotEmpty() }?.let { ov ->
            val p = runCatching { Path.of(ov) }.getOrNull()
            if (p != null) {
                if (Files.isRegularFile(p)) return p
                if (Files.isDirectory(p)) resolveInDir(p, name)?.let { return it }
            }
        }
        for (candidate in withExtensions(name)) {
            PathEnvironmentVariableUtil.findInPath(candidate)?.let { if (it.isFile) return it.toPath() }
        }
        for (dir in candidateDirs) resolveInDir(dir, name)?.let { return it }
        return null
    }

    /**
     * The `claude` CLI: [overridePath] (the settings field), then `PATH`, then the install directories a
     * narrow IDE environment tends to miss.
     *
     * **One list, two callers** — [ClaudeExplainer] runs it and [ExplainerDiagnostics] reports on it, and a
     * diagnostic that looked in different places from the thing it diagnoses would be worse than none.
     */
    fun findClaude(overridePath: String?): Path? {
        val home = Path.of(System.getProperty("user.home"))
        return find(
            "claude",
            overridePath,
            listOf(
                home.resolve(".local/bin"),
                home.resolve(".claude/local"),
                home.resolve("AppData/Roaming/npm"),
                home.resolve("AppData/Local/Programs/claude"),
                home.resolve("bin"),
            ),
        )
    }

    private fun resolveInDir(dir: Path, name: String): Path? {
        for (candidate in withExtensions(name)) {
            val p = runCatching { dir.resolve(candidate) }.getOrNull() ?: continue
            if (Files.isRegularFile(p)) return p
        }
        return null
    }

    /** `name` plus each Windows executable extension from `PATHEXT`; just `name` elsewhere. */
    private fun withExtensions(name: String): List<String> {
        if (!SystemInfo.isWindows || name.contains('.')) return listOf(name)
        val exts = (System.getenv("PATHEXT") ?: ".EXE;.CMD;.BAT;.COM")
            .split(';').map { it.trim() }.filter { it.startsWith(".") }
        // Prefer real executables (.exe/.cmd/…) then the bare name (some tools ship extensionless).
        return (exts.map { name + it.lowercase() } + name).distinct()
    }
}
