package com.audioexplainer

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import java.nio.file.Path

/**
 * On project open: touches the app service (starting the loopback server on first access), points
 * per-workspace save/load at this project's base path, and installs the editor-highlighting listener
 * for this project. The last opened project wins the per-workspace save/load root; the highlighter is
 * registered per project (each ignores segments whose file isn't in its own workspace) and removed on
 * project close.
 */
class ServerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val server = service<ExplainerServerService>().server
        project.basePath?.let { server.setWorkspaceRoot(Path.of(it)) }
        // Drive editor spotlight highlighting from walkthrough state changes (Phase 3).
        val highlighter = EditorHighlighter(project)
        server.walkthrough.addListener(highlighter)
        Disposer.register(project, Disposable { server.walkthrough.removeListener(highlighter) })
    }
}
