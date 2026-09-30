package com.audioexplainer

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
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
 * Per-workspace persistence of walkthroughs. Files live in `<workspaceRoot>/.walkthroughs/<slug>.json` and
 * hold a whole [Plan] — `{title, language, frames, steps}` (frames and steps since Phase 2; the `view` key
 * went with the in-place surface). A file saved while `view` still existed loads unchanged: the decoder below
 * sets `ignoreUnknownKeys`, so the dropped field is ignored rather than failing the load.
 *
 * **`frames[].file` is what gets rewritten**, relative on save and absolute again on load, so a saved
 * walkthrough survives being opened from a different absolute root (§4.4 rule 6). It replaces the segment
 * paths that used to be rewritten here for the same reason and by the same rule: the frame is where a file
 * path lives now, and a relation endpoint's own `file` still travels with it.
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

    /** Save [plan]. `name` overrides the file stem; otherwise the plan's title is slugified. */
    fun save(plan: Plan, name: String?): String {
        Files.createDirectories(dir)
        val file = dir.resolve("${slugify(name ?: plan.title)}.json")
        // `streaming = false` on the way out, and again on the way in (`Stream.md`): a *saved* walkthrough is
        // by definition finished, so the field that means "more steps are coming" must not reach disk. A file
        // that carried it would park playback at its last step forever, waiting for an agent that stopped
        // running long ago.
        Files.writeString(
            file,
            json.encodeToString(plan.copy(frames = toRelativePaths(plan.frames), streaming = false)),
        )
        return file.toAbsolutePath().toString()
    }

    /**
     * Load by name; returns null if the file is absent, malformed, or not a save at all (see [readSave]).
     * Paths resolved to absolute.
     */
    fun load(name: String): Plan? {
        val file = dir.resolve("${slugify(name)}.json")
        if (!Files.exists(file)) return null
        val data = readSave(file) ?: return null
        return data.copy(frames = toAbsolutePaths(data.frames), streaming = false)
    }

    /** Whether a file named [name] exists here — any file, save or not, since it is what a save would replace. */
    fun exists(name: String): Boolean = Files.exists(dir.resolve("${slugify(name)}.json"))

    /**
     * **The file as a save, or null when it is not one** — the one test [list] and [load] share, so a file the
     * list hides cannot be opened through a stale link either.
     *
     * It accepts exactly what [save] could have written, and the negative form is what makes it work on the
     * files already on disk, which carry no marker:
     *  - **a top-level `type` means a wire message, not a save.** [Plan] has no `type` field, so [save] cannot
     *    write one; a file with it is an agent's `set_plan` / `append_steps` payload, written into the project
     *    by an agent that was told "a temp file" and not where (`Saving.md`). Decoded leniently it becomes a
     *    [Plan] with a blank title and no frames — the blank rows that errored on open.
     *  - **a blank `title` or no `frames`** is what `saveCurrentWalkthrough` refuses to write, so it is not a
     *    save whatever else it is.
     */
    private fun readSave(file: Path): Plan? = try {
        val element = json.parseToJsonElement(Files.readString(file))
        val obj = element as? JsonObject
        if (obj == null || "type" in obj) {
            null
        } else {
            json.decodeFromJsonElement<Plan>(obj).takeIf { it.title.isNotBlank() && it.frames.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
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

    /**
     * List all saved walkthroughs. Malformed files are skipped, and so is every file that is not a save —
     * see [readSave]. **Skipped, not deleted**: nothing here removes a file this plugin did not write.
     */
    fun list(): List<WalkthroughEntry> {
        if (!Files.isDirectory(dir)) return emptyList()
        val results = mutableListOf<WalkthroughEntry>()
        Files.newDirectoryStream(dir, "*.json").use { stream ->
            for (path in stream) {
                val data = readSave(path) ?: continue
                results += WalkthroughEntry(
                    name = path.fileName.toString().removeSuffix(".json"),
                    title = data.title,
                    filePath = path.toAbsolutePath().toString(),
                    language = data.language,
                )
            }
        }
        return results
    }

    private fun toRelativePaths(frames: List<Frame>): List<Frame> = frames.map { f ->
        f.copy(file = relativize(f.file), relations = mapEndpointFiles(f.relations, ::relativize))
    }

    private fun toAbsolutePaths(frames: List<Frame>): List<Frame> = frames.map { f ->
        f.copy(file = absolutize(f.file), relations = mapEndpointFiles(f.relations, ::absolutize))
    }

    private fun relativize(file: String): String = try {
        workspaceRoot.relativize(Path.of(file)).toString().replace('\\', '/')
    } catch (_: Exception) {
        file // e.g. different drive on Windows, or already relative — keep as written.
    }

    private fun absolutize(file: String): String = try {
        workspaceRoot.resolve(file).normalize().toString()
    } catch (_: Exception) {
        file
    }

    /**
     * Apply the same path rewrite to relation endpoints that carry their own [Endpoint.file] — a saved
     * walkthrough must survive being opened from a different absolute root, and a cross-file relation is
     * exactly as root-dependent as [Frame.file] is. Endpoints without a file (the common case: the frame's
     * own file) are untouched.
     */
    private fun mapEndpointFiles(relations: List<Relation>, f: (String) -> String): List<Relation> {
        if (relations.isEmpty()) return relations
        fun map(ep: Endpoint) = ep.file?.let { ep.copy(file = f(it)) } ?: ep
        return relations.map { it.copy(from = map(it.from), to = map(it.to)) }
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
