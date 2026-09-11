package com.audioexplainer

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import java.nio.file.Path

/**
 * On project open: touches the app service (starting the loopback server on first access), points
 * per-workspace save/load at this project's base path, and installs the tree surface's listener for this
 * project. The last opened project wins the per-workspace save/load root; the listener is registered per
 * project and removed on project close.
 *
 * There used to be two listeners here, one per rendering surface, and a plan chose between them. Round 3
 * settled that: the tree is the only surface, so there is nothing to choose and nothing to guard.
 */
class ServerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val server = service<ExplainerServerService>().server
        project.basePath?.let { server.setWorkspaceRoot(Path.of(it)) }
        // Drive the tree of code views from walkthrough state changes.
        val tree = TreePlanController(project, server.walkthrough)
        server.walkthrough.addListener(tree)
        Disposer.register(project, Disposable { server.walkthrough.removeListener(tree) })
    }
}
