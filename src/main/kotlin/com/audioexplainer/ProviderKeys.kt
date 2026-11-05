package com.audioexplainer

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **A provider profile's API key, in the IDE password safe** — never in `audioExplainer.xml`.
 *
 * Off the EDT only: some password-safe backends (KeePass, a locked keychain) block. Every read is in a
 * background task — the explain run, the Test button's task, the Manage dialog's load.
 */
object ProviderKeys {
    private fun attributes(profileId: String) =
        CredentialAttributes(generateServiceName("Audio Explainer", "provider $profileId"))

    fun get(profileId: String): String? =
        PasswordSafe.instance.getPassword(attributes(profileId))?.takeIf { it.isNotBlank() }

    /** Blank or null removes the credential. */
    fun set(profileId: String, key: String?) =
        PasswordSafe.instance.setPassword(attributes(profileId), key?.trim()?.takeIf { it.isNotEmpty() })
}

/**
 * **The short-lived `--settings` files that carry a key to Claude Code.**
 *
 * Not in the run's plan directory: a run that POSTs is granted that directory with `--add-dir`, and a key has
 * no business inside a directory the agent may read. Each file is deleted when its run ends — even when the
 * plan directory is kept as evidence, because the key is live and the MCP token is not — and [sweepOnce]
 * removes any an IDE kill left behind, once per IDE session, before any run of this session can exist.
 */
object ProviderFiles {
    private val log = Logger.getInstance(ProviderFiles::class.java)
    private val swept = AtomicBoolean(false)

    private fun dir(): Path = Path.of(PathManager.getTempPath(), "audio-explainer", "provider")

    /** A new file holding [content], user-only on POSIX (the IDE temp dir is already user-only on Windows). */
    fun write(content: String): Path {
        val dir = Files.createDirectories(dir())
        val file = if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createTempFile(dir, "run-", ".json", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createTempFile(dir, "run-", ".json")
        }
        Files.writeString(file, content)
        return file
    }

    fun delete(file: Path?) {
        if (file == null) return
        runCatching { Files.deleteIfExists(file) }.onFailure { log.warn("could not delete the provider settings file $file", it) }
    }

    fun sweepOnce() {
        if (!swept.compareAndSet(false, true)) return
        val dir = dir()
        if (!Files.isDirectory(dir)) return
        runCatching {
            Files.list(dir).use { files ->
                files.filter { it.fileName.toString().startsWith("run-") }.forEach { f ->
                    log.info("removing a provider settings file left by an earlier session: ${f.fileName}")
                    delete(f)
                }
            }
        }.onFailure { log.warn("could not sweep $dir", it) }
    }
}
