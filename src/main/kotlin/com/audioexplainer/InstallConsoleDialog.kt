package com.audioexplainer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * **The provisioning console** — a non-modal window carrying the raw output of whatever install the user
 * just started: `pip`, the model download, a Piper voice fetch.
 *
 * It exists because the progress bar is not a diagnosis. Everything a failing install actually says — a
 * permission denied on the model directory, a proxy refusing the index, a C extension with no wheel asking
 * for a compiler — arrived on `indicator.text2`, one line at a time, each overwriting the last, and was gone
 * the moment the task ended. The notification then said *"see the IDE log"*, which until this round held
 * nothing at all, and even now holds only the tail and only on failure. This is the place the user can read
 * it while it happens, and copy it.
 *
 * **Non-modal, and closing it does not cancel.** The install is a background task with its own progress bar
 * and its own ✕; this window is a view of it. A modal console would block the IDE for the length of a 340 MB
 * download, and one that cancelled on close would make "I've read enough" destructive.
 *
 * **Threading.** [append] is called from the process-reader thread, once per output line, and pip is happy to
 * emit thousands. So lines land in a buffer under [lock] and a *single* repaint is scheduled per burst
 * ([flushScheduled]) rather than one `invokeLater` per line — the difference between a console and an EDT
 * flood. [MAX_LINES] bounds the document; past it the oldest fifth goes, because the end is the part that
 * explains a failure.
 *
 * **Follow-the-tail, unless the reader is reading.** The view sticks to the bottom only while it is already
 * at the bottom: scrolling up to look at an error mid-install is a deliberate act and must not be undone by
 * the next line.
 */
class InstallConsoleDialog : DialogWrapper {

    private val what: String

    /** Owned by the project frame — the ordinary case, e.g. the first-run notification's Install action. */
    private constructor(project: Project?, what: String) : super(project, false) {
        this.what = what
        setUp()
    }

    /**
     * Owned by [owner], which is how this reaches the screen at all when it is started from **Settings** or
     * from the (modal) Piper voice manager.
     *
     * An application-modal dialog blocks every other window in the application except its own children, so a
     * console owned by the project frame would appear and then refuse the click that scrolls or copies it —
     * the two things it exists for. The cost of the fix is that closing the owning dialog disposes the
     * console with it while the background task runs on; the task's notification and the IDE log are what
     * outlive it, and writes to a disposed console are harmless no-ops.
     */
    private constructor(owner: Component, what: String) : super(owner, false) {
        this.what = what
        setUp()
    }

    private fun setUp() {
        title = what
        isModal = false
        init()
    }

    private val area = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12.0f))
    }
    private val scroll = JBScrollPane(area)
    private val status = JBLabel("Working…")

    /** Guards [pending] and [flushScheduled] — touched by the reader thread and by the EDT flush. */
    private val lock = Any()
    private val pending = StringBuilder()
    private var flushScheduled = false

    /** Set by the first [finish]; later calls are ignored so an early `return` cannot overwrite a verdict. */
    @Volatile private var finished = false

    override fun getDimensionServiceKey(): String = "AudioExplainer.InstallConsoleDialog"

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(6)))
        scroll.preferredSize = Dimension(JBUI.scale(760), JBUI.scale(420))
        panel.add(scroll, BorderLayout.CENTER)
        panel.add(status, BorderLayout.SOUTH)
        // Whatever arrived between `open` and the panel being built — the command echo usually beats it here.
        flushNow()
        return panel
    }

    override fun createActions(): Array<Action> = arrayOf(copyAction(), okAction)

    private fun copyAction(): Action = object : AbstractAction("Copy output") {
        override fun actionPerformed(e: ActionEvent) {
            CopyPasteManager.getInstance().setContents(StringSelection(area.text))
        }
    }

    /**
     * Add one line of subprocess output. Safe from any thread and cheap per call — the EDT work is
     * coalesced into at most one flush per burst.
     */
    fun append(line: String) {
        synchronized(lock) {
            pending.append(line.trimEnd()).append('\n')
            if (flushScheduled) return
            flushScheduled = true
        }
        ApplicationManager.getApplication().invokeLater({ flushNow() }, ModalityState.any())
    }

    /**
     * Close the console's account of the run: a verdict on the status line, and the button relabelled from
     * the neutral "Close" to something that says it is over. Idempotent — the first verdict wins.
     */
    fun finish(ok: Boolean, summary: String) {
        if (finished) return
        finished = true
        append(if (ok) "[done] $summary" else "[failed] $summary")
        ApplicationManager.getApplication().invokeLater({
            status.text = (if (ok) "✔ " else "✖ ") + summary
            setOKButtonText("Close")
        }, ModalityState.any())
    }

    /** The default verdict, for a path that returned without stating one. No-op once [finish] has run. */
    fun finishQuietly() = finish(true, "Finished.")

    private fun flushNow() {
        val chunk = synchronized(lock) {
            flushScheduled = false
            if (pending.isEmpty()) return
            val s = pending.toString()
            pending.setLength(0)
            s
        }
        val bar = scroll.verticalScrollBar
        val atBottom = bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(8)
        area.append(chunk)
        trimIfHuge()
        if (atBottom) area.caretPosition = area.document.length
    }

    /** Keep the document bounded. The tail explains the failure, so the head is what goes. */
    private fun trimIfHuge() {
        if (area.lineCount <= MAX_LINES) return
        val drop = runCatching { area.getLineEndOffset(area.lineCount / 5) }.getOrNull() ?: return
        runCatching { area.document.remove(0, drop) }
    }

    companion object {
        private const val MAX_LINES = 5000

        /**
         * Open a console for [what] and return it.
         *
         * The window is shown on the EDT but the handle comes back immediately, so the caller can start a
         * background task that writes to it before it is on screen — [createCenterPanel] flushes whatever
         * accumulated in the meantime.
         *
         * Call it from the EDT: every install starts from a click, and the owner test below needs the focus
         * manager. Off the EDT it still works, it just falls back to the project frame.
         */
        fun open(project: Project?, what: String): InstallConsoleDialog {
            val owner = modalOwner()
            val dialog = if (owner != null) InstallConsoleDialog(owner, what) else InstallConsoleDialog(project, what)
            ApplicationManager.getApplication().invokeLater({ dialog.show() }, ModalityState.any())
            return dialog
        }

        /**
         * The active window when it is a **modal dialog** — Settings, or the Piper voice manager — and null
         * otherwise.
         *
         * Only that case needs overriding: a modeless console owned by the project frame is what we want
         * everywhere else, and it is exactly what a modal dialog would block. Null off the EDT rather than
         * reading the focus manager from another thread.
         */
        private fun modalOwner(): Component? {
            if (!SwingUtilities.isEventDispatchThread()) return null
            val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            return if (active is Dialog && active.isModal) active else null
        }
    }
}
