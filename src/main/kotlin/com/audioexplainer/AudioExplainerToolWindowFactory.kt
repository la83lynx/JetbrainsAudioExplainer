package com.audioexplainer

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import javax.swing.JComponent

/**
 * Builds the "Audio Explainer" tool window (Phase 4). Prefers the JCEF sidebar (the reused webview UI);
 * falls back to a plain Swing control panel when JCEF is unavailable or fails to initialise. Both
 * variants tie their walkthrough-listener + browser lifetime to [ToolWindow.getDisposable].
 */
class AudioExplainerToolWindowFactory : ToolWindowFactory {
    private val log = Logger.getInstance(AudioExplainerToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val parent = toolWindow.disposable
        val component: JComponent = if (JBCefApp.isSupported()) {
            try {
                AudioExplainerSidebar(project, parent).component
            } catch (t: Throwable) {
                log.warn("JCEF sidebar init failed; using Swing fallback", t)
                AudioExplainerSwingSidebar(project, parent).component
            }
        } else {
            AudioExplainerSwingSidebar(project, parent).component
        }
        val content = ContentFactory.getInstance().createContent(component, "", false)
        toolWindow.contentManager.addContent(content)
        // The last run's log, after the banner is gone — a run that succeeds clears it as its plan lands.
        ActionManager.getInstance().getAction("AudioExplainer.ShowRunLog")?.let { toolWindow.setTitleActions(listOf(it)) }
    }
}
