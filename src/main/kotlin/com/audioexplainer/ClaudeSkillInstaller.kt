package com.audioexplainer

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Installs the bundled Claude skill into `~/.claude/skills/audio-explainer/` so both the headless
 * `claude -p` invocation ([ClaudeExplainer]) and an interactive Claude Code session can load it.
 *
 * Idempotent and versioned: copies only when the target is missing or its `VERSION` marker differs
 * from the bundled one, so it won't clobber later user edits on every run (bump [RESOURCE_ROOT]'s
 * `VERSION` when the bundled skill changes). Named `audio-explainer` — not `explainer` — so it can
 * never overwrite a separately-installed upstream code-explainer skill.
 *
 * The bundled tree is enumerated via `claude-skill/manifest.txt` (generated at build authoring time),
 * since a jar has no directory listing.
 */
object ClaudeSkillInstaller {
    private val log = Logger.getInstance(ClaudeSkillInstaller::class.java)

    const val SKILL_NAME = "audio-explainer"
    private const val RESOURCE_ROOT = "/claude-skill"

    /** `~/.claude/skills/audio-explainer`. */
    fun skillDir(): Path =
        Path.of(System.getProperty("user.home"), ".claude", "skills", SKILL_NAME)

    /**
     * Ensure the skill is present and current. Returns the skill directory, or null if the bundled
     * resources could not be read/written (logged, non-fatal — [ClaudeExplainer] falls back to an
     * inline prompt). Safe and cheap to call on every explain().
     */
    @Synchronized
    fun ensureInstalled(): Path? {
        val dir = skillDir()
        return try {
            val bundledVersion = readResource("VERSION")?.trim()
            if (bundledVersion.isNullOrEmpty()) {
                log.warn("bundled skill VERSION missing; skipping skill install")
                return null
            }
            val installedVersion = runCatching { Files.readString(dir.resolve("VERSION")).trim() }.getOrNull()
            if (installedVersion == bundledVersion && Files.isDirectory(dir)) {
                return dir // already current
            }
            install(dir)
            log.info("installed '$SKILL_NAME' skill v$bundledVersion -> $dir")
            dir
        } catch (e: Exception) {
            log.warn("failed to install '$SKILL_NAME' skill to $dir", e)
            null
        }
    }

    private fun install(dir: Path) {
        val manifest = readResource("manifest.txt") ?: error("bundled skill manifest.txt missing")
        Files.createDirectories(dir)
        manifest.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { rel ->
                val bytes = readResourceBytes(rel) ?: error("bundled skill resource missing: $rel")
                val target = dir.resolve(rel)
                Files.createDirectories(target.parent)
                Files.write(target, bytes)
                if (rel.endsWith(".sh")) makeExecutable(target)
            }
    }

    /** Best-effort +x on POSIX so `explainer.sh` is runnable; a no-op on Windows. */
    private fun makeExecutable(file: Path) {
        try {
            val perms = Files.getPosixFilePermissions(file).toMutableSet()
            perms += PosixFilePermission.OWNER_EXECUTE
            perms += PosixFilePermission.GROUP_EXECUTE
            perms += PosixFilePermission.OTHERS_EXECUTE
            Files.setPosixFilePermissions(file, perms)
        } catch (_: Exception) {
            // non-POSIX filesystem (Windows) — ignore
        }
    }

    private fun readResource(rel: String): String? = readResourceBytes(rel)?.toString(Charsets.UTF_8)

    private fun readResourceBytes(rel: String): ByteArray? =
        ClaudeSkillInstaller::class.java.getResourceAsStream("$RESOURCE_ROOT/$rel")?.use { it.readBytes() }
}
