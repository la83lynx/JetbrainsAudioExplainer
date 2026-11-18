package com.audioexplainer

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * **One project's explainer**: its walkthrough, its `.walkthroughs` folder, and the root a plan's relative paths
 * are read against. Every open project window has its own ([ProjectExplainer]), so a plan, a save or a load in one
 * window never reaches another. The loopback server is shared by all of them and routes each request to one
 * ([ExplainerServer.workspaceFor]).
 *
 * Free of [Project] on purpose, so a probe can build one around a temp folder.
 */
class ExplainerWorkspace(root: Path?) {
    private val log = Logger.getInstance(ExplainerWorkspace::class.java)

    /** The project's base path, or null for a project with none — then there is nowhere to save. */
    val root: Path? = root?.toAbsolutePath()?.normalize()

    /** The walkthrough state machine; this project's tree, sidebar and narration register on it. */
    val walkthrough = Walkthrough()

    private val storage: WalkthroughStorage? = this.root?.let { WalkthroughStorage(it) }

    /** Whether this workspace has a folder to save in. */
    val hasStorage: Boolean get() = storage != null

    /** Whether [path] is this workspace's root or inside it. */
    fun contains(path: Path): Boolean = root != null && path.startsWith(root)

    /**
     * Save the current walkthrough. `name` overrides the file stem (else the title is slugified), and the
     * walkthrough becomes that save — the next Save writes the same file without asking.
     * Returns what was written, or null if there is no storage, no active walkthrough, or the write failed.
     */
    fun saveCurrentWalkthrough(name: String?): SavedWalkthrough? {
        val storage = this.storage ?: return null
        val state = walkthrough.getState()
        if (state.title.isEmpty() || state.plan.frames.isEmpty()) return null
        val stem = WalkthroughStorage.slugify(name ?: state.title)
        return try {
            val filePath = storage.save(state.plan, stem)
            walkthrough.markSaved(stem, state.plan)
            SavedWalkthrough(stem, filePath)
        } catch (e: Exception) {
            log.warn("save failed", e)
            null
        }
    }

    /** Whether saving under [name] would replace a file already in `.walkthroughs` (false with no storage). */
    fun savedWalkthroughExists(name: String): Boolean =
        storage?.let { try { it.exists(name) } catch (_: Exception) { false } } ?: false

    /** The saved walkthroughs of this workspace (empty if none / no storage). */
    fun listSavedWalkthroughs(): List<WalkthroughEntry> =
        storage?.let { try { it.list() } catch (_: Exception) { emptyList() } } ?: emptyList()

    /** Absolute path of the directory walkthroughs are saved in, or null with no storage. */
    fun savedWalkthroughsDir(): String? = storage?.directory

    /** Load a saved walkthrough by name into this workspace's walkthrough; null if it is not there. */
    fun loadSavedWalkthrough(name: String): ResolvedPlan? {
        val data = storage?.load(name) ?: return null
        return walkthrough.setPlan(data, savedAs = WalkthroughStorage.slugify(name))
    }

    /** Delete a saved walkthrough by name. False with no storage or no such file. */
    fun deleteSavedWalkthrough(name: String): Boolean =
        storage?.let { try { it.delete(name) } catch (_: Exception) { false } } ?: false

    /** Delete every saved walkthrough of this workspace; returns the count. */
    fun deleteAllSavedWalkthroughs(): Int =
        storage?.let { try { it.deleteAll() } catch (_: Exception) { 0 } } ?: 0

    /**
     * Everything wrong with [resolved] **including what only the code can show** — the loader's structural
     * issues, then [PlanSourceCheck]'s against *this* workspace's files: a token not on its line, a line past
     * the end, a file that does not resolve. **A failure costs the check and never the plan**, which is already
     * showing by the time this runs.
     */
    fun replyIssues(resolved: ResolvedPlan): List<PlanIssue> {
        val source = try {
            PlanSourceCheck.check(resolved, ProjectSourceLines(root?.toString()))
        } catch (e: Exception) {
            log.warn("source check failed; replying with the structural issues only", e)
            emptyList()
        }
        for (issue in source) log.warn("plan source: $issue")
        return resolved.issues + source
    }
}

/**
 * The [ExplainerWorkspace] of one open project: created on first use, registered with the shared server, and
 * unregistered when the project closes.
 */
@Service(Service.Level.PROJECT)
class ProjectExplainer(project: Project) : Disposable {
    val workspace = ExplainerWorkspace(project.basePath?.let { Path.of(it) })
    private val server = service<ExplainerServerService>().server

    init {
        server.register(workspace)
    }

    override fun dispose() = server.unregister(workspace)

    companion object {
        /** [project]'s workspace, creating it (and starting the server) on first use. */
        fun of(project: Project): ExplainerWorkspace = project.service<ProjectExplainer>().workspace

        /** [project]'s workspace if the plugin has been used in it, without creating one. */
        fun ifCreated(project: Project): ExplainerWorkspace? =
            if (project.isDisposed) null else project.serviceIfCreated<ProjectExplainer>()?.workspace
    }
}
