package com.audioexplainer

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import java.util.WeakHashMap

/**
 * **The run log window**: a [RunLog] in the provisioning console ([InstallConsoleDialog]) — non-modal, follows
 * the end while the run writes, Copy, and closing it does not stop the run. One window per run: asking again
 * brings it to the front.
 */
object RunLogWindow {
    private val open = WeakHashMap<RunLog, InstallConsoleDialog>()

    /** On the EDT. [log] null means no run yet in this IDE session, and says so. */
    fun show(project: Project, log: RunLog?) {
        if (log == null) {
            Messages.showInfoMessage(project, "No Explain This run yet since the IDE started.", "Audio Explainer")
            return
        }
        synchronized(open) { open[log] }?.takeIf { !it.isDisposed && it.isShowing }?.let {
            it.window.toFront()
            return
        }
        val console = InstallConsoleDialog.open(project, "Run Log — ${log.title}")
        val listener = object : RunLog.Listener {
            override fun onLine(line: String) = console.append(line)
            override fun onEnd(ok: Boolean, summary: String) = console.finish(ok, summary)
        }
        log.subscribe(listener)
        Disposer.register(console.disposable) {
            log.unsubscribe(listener)
            synchronized(open) { open.remove(log) }
        }
        synchronized(open) { open[log] = console }
    }
}

/**
 * The tool window's stop button beside Show Run Log, there only while a run is: the status bar's × for the run,
 * where the run is being watched.
 */
class CancelExplainAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ExplainSessionTracker.getInstance(project).cancel()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.let { ExplainSessionTracker.getInstance(it).isRunning } == true
    }
}

/** Tools → Audio Explainer → Show Run Log, and the tool window's title button: the latest run in this project. */
class ShowRunLogAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        RunLogWindow.show(project, ExplainSessionTracker.getInstance(project).lastLog)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
