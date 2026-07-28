package com.audioexplainer

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** One entry in the `/api/walkthroughs` listing. [language] is the saved plan's language code (see
 *  [Languages]); "en" for plans saved before the field existed (the [Plan.language] default). */
data class WalkthroughEntry(
    val name: String,
    val title: String,
    val filePath: String,
    val language: String = Languages.DEFAULT_CODE,
)

/**
 * Per-workspace persistence of walkthroughs — a port of code-explainer's
 * `vscode-extension/src/storage.ts`. Files live in `<workspaceRoot>/.walkthroughs/<slug>.json`
 * with the shape `{title, segments}`; segment `file` paths are stored relative to the workspace
 * root and resolved back to absolute on load.
 */
class WalkthroughStorage(private val workspaceRoot: Path) {
    private val dir: Path = workspaceRoot.resolve(".walkthroughs")

    /** Absolute path of the directory walkthroughs are saved in (exists as a path whether or not created). */
    val directory: String get() = dir.toAbsolutePath().toString()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Save the active walkthrough. `name` overrides the file stem; otherwise the title is slugified. */
    fun save(title: String, segments: List<Segment>, language: String, name: String?): String {
        Files.createDirectories(dir)
        val file = dir.resolve("${slugify(name ?: title)}.json")
        val data = Plan(title, toRelativePaths(segments), language)
        Files.writeString(file, json.encodeToString(data))
        return file.toAbsolutePath().toString()
    }

    /** Load by name; returns null if the file is absent or malformed. Paths resolved to absolute. */
    fun load(name: String): Plan? {
        val file = dir.resolve("${slugify(name)}.json")
        if (!Files.exists(file)) return null
        val data = try {
            json.decodeFromString<Plan>(Files.readString(file))
        } catch (_: Exception) {
            return null
        }
        return data.copy(segments = toAbsolutePaths(data.segments))
    }

    /** Delete a saved walkthrough by name. Returns true if a file was removed (false if absent / on error). */
    fun delete(name: String): Boolean = try {
        Files.deleteIfExists(dir.resolve("${slugify(name)}.json"))
    } catch (_: Exception) {
        false
    }

    /** Delete every saved walkthrough in this workspace. Returns the number of files removed. */
    fun deleteAll(): Int {
        if (!Files.isDirectory(dir)) return 0
        var removed = 0
        try {
            Files.newDirectoryStream(dir, "*.json").use { stream ->
                for (path in stream) {
                    if (try { Files.deleteIfExists(path) } catch (_: Exception) { false }) removed++
                }
            }
        } catch (_: Exception) {
            // Directory vanished mid-iteration or similar — report whatever we managed to delete.
        }
        return removed
    }

    /** List all saved walkthroughs (malformed files are skipped). */
    fun list(): List<WalkthroughEntry> {
        if (!Files.isDirectory(dir)) return emptyList()
        val results = mutableListOf<WalkthroughEntry>()
        Files.newDirectoryStream(dir, "*.json").use { stream ->
            for (path in stream) {
                try {
                    val data = json.decodeFromString<Plan>(Files.readString(path))
                    results += WalkthroughEntry(
                        name = path.fileName.toString().removeSuffix(".json"),
                        title = data.title,
                        filePath = path.toAbsolutePath().toString(),
                        language = data.language,
                    )
                } catch (_: Exception) {
                    // Skip unreadable/malformed files, like upstream.
                }
            }
        }
        return results
    }

    private fun toRelativePaths(segments: List<Segment>): List<Segment> = segments.map { seg ->
        val rel = try {
            workspaceRoot.relativize(Path.of(seg.file)).toString().replace('\\', '/')
        } catch (_: Exception) {
            seg.file // e.g. different drive on Windows — keep absolute.
        }
        seg.copy(file = rel)
    }

    private fun toAbsolutePaths(segments: List<Segment>): List<Segment> = segments.map { seg ->
        val abs = try {
            workspaceRoot.resolve(seg.file).normalize().toString()
        } catch (_: Exception) {
            seg.file
        }
        seg.copy(file = abs)
    }

    companion object {
        /** Lowercase, non-alphanumeric runs → hyphens, trim hyphens; empty → "walkthrough". */
        fun slugify(title: String): String =
            title.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .replace(Regex("^-+|-+$"), "")
                .ifEmpty { "walkthrough" }
    }
}
