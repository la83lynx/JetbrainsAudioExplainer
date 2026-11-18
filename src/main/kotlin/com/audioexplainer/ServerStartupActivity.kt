package com.audioexplainer

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer

/**
 * On project open: creates this project's [ExplainerWorkspace] (starting the shared loopback server on first
 * access) and installs the tree surface's listener on *its* walkthrough, removed on project close. The tree is
 * the only rendering surface, and a plan for another project window never reaches this one.
 */
class ServerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Once per IDE session, before any run of it exists: a key file an IDE kill left behind.
        ProviderFiles.sweepOnce()
        val walkthrough = ProjectExplainer.of(project).walkthrough
        // Drive the tree of code views from walkthrough state changes.
        val tree = TreePlanController(project, walkthrough)
        walkthrough.addListener(tree)
        Disposer.register(project, Disposable { walkthrough.removeListener(tree) })
    }
}
