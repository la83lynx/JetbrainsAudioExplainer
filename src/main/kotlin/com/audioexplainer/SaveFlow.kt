package com.audioexplainer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseHandler
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages

/**
 * **Save, Save As… and the unsaved prompt** (`Saving.md` C and D) — the dialogs, in one place, so the
 * sidebar's split button and the three moments that can lose a walkthrough all save the same way.
 *
 * Everything here is modal and runs **on the EDT**. The state it reads and writes is [Walkthrough]'s own
 * save identity (`savedAs` / `unsaved`); nothing here keeps a second copy of it.
 */
object SaveFlow {
    private const val UNSAVED_TITLE = "Unsaved Walkthrough"

    private val server get() = service<ExplainerServerService>().server

    /**
     * **The Save button.** A walkthrough that is already a save is written back to its file with no dialog
     * and no second entry; one that never was asks for a name, seeded with its title's slug. Refuses while
     * the plan is still streaming — the button is disabled then, and this is the backstop behind it.
     * Returns true when a file was written.
     */
    fun save(project: Project): Boolean {
        val state = server.walkthrough.getState()
        if (state.streaming) return false
        val name = state.savedAs ?: return askAndWrite(project, "Save Walkthrough", WalkthroughStorage.slugify(state.title))
        return write(project, name)
    }

    /** **Save As…** — always asks, seeded with the current name. Returns true when a file was written. */
    fun saveAs(project: Project): Boolean {
        val state = server.walkthrough.getState()
        if (state.streaming) return false
        return askAndWrite(project, "Save Walkthrough As", state.savedAs ?: WalkthroughStorage.slugify(state.title))
    }

    /**
     * **Ask before the walkthrough showing is lost** — Save · Save As… · Don't save · Cancel. Returns true to
     * go ahead with whatever was about to replace it, false to stop.
     *
     * Asks nothing, and answers true, when there is nothing unsaved worth asking about
     * ([WalkthroughSnapshot.worthAsking]). A plan still streaming cannot be saved, so it is offered only
     * Discard and continue · Cancel. **"Don't save" deletes nothing** — nothing was written — and a
     * walkthrough saved earlier keeps its file; it only clears the unsaved mark, so the next moment (a second
     * project closing) does not ask again about the same walkthrough.
     *
     * [consequence] finishes the sentence "…has not been saved." with what goes ahead if the user lets it.
     */
    fun confirmLoss(project: Project, consequence: String): Boolean {
        val walkthrough = server.walkthrough
        val state = walkthrough.getState()
        if (!state.worthAsking) return true
        val title = "“${state.title}”"
        if (state.streaming) {
            val choice = Messages.showOkCancelDialog(
                project,
                "$title is still being written, so it cannot be saved yet.\n$consequence",
                UNSAVED_TITLE,
                "Discard and Continue", "Cancel",
                Messages.getWarningIcon(),
            )
            if (choice != Messages.OK) return false
            walkthrough.discardUnsaved()
            return true
        }
        val choice = Messages.showDialog(
            project,
            "$title has not been saved.\n$consequence",
            UNSAVED_TITLE,
            arrayOf("Save", "Save As…", "Don't Save", "Cancel"),
            0,
            Messages.getWarningIcon(),
        )
        return when (choice) {
            // A Save that asks for a name and is cancelled there cancels the whole thing: the user was on
            // their way to keeping it, not to losing it.
            0 -> save(project)
            1 -> saveAs(project)
            2 -> { walkthrough.discardUnsaved(); true }
            else -> false // Cancel, or the dialog closed with Esc
        }
    }

    /** Ask for a name (seeded with [seed]), confirm replacing a different walkthrough, then write. */
    private fun askAndWrite(project: Project, dialogTitle: String, seed: String): Boolean {
        val dir = server.savedWalkthroughsDir()
        val typed = Messages.showInputDialog(
            project,
            if (dir != null) "Name for this walkthrough (saved in $dir):" else "Name for this walkthrough:",
            dialogTitle,
            null,
            seed,
            NonBlank,
        ) ?: return false // cancelled
        val name = WalkthroughStorage.slugify(typed.trim())
        // **Only a *different* walkthrough is confirmed.** Save As onto the name it already has is just Save.
        if (name != server.walkthrough.getState().savedAs && server.savedWalkthroughExists(name)) {
            val existing = server.listSavedWalkthroughs().firstOrNull { it.name == name }?.title
            val what = if (existing != null) "“$name” already holds the walkthrough “$existing”."
            else "A file named “$name.json” already exists there."
            val choice = Messages.showYesNoDialog(
                project,
                "$what\nReplace it?",
                dialogTitle,
                "Replace", "Cancel",
                Messages.getWarningIcon(),
            )
            if (choice != Messages.YES) return false
        }
        return write(project, name)
    }

    private fun write(project: Project, name: String): Boolean {
        if (server.saveCurrentWalkthrough(name) != null) return true
        Messages.showErrorDialog(
            project,
            "Could not save — no active walkthrough, it is still being written, or this project has no " +
                "workspace folder.",
            "Save Walkthrough",
        )
        return false
    }

    /** A name that slugifies to *something*: a blank one would save as `walkthrough.json`, which nobody typed. */
    private object NonBlank : InputValidator {
        override fun checkInput(inputString: String?): Boolean = !inputString.isNullOrBlank()
        override fun canClose(inputString: String?): Boolean = checkInput(inputString)
    }
}

/**
 * **The project is closing, or the IDE is exiting** — the third moment that can lose a walkthrough
 * (`Saving.md` D). A `projectCloseHandler` because [ProjectCloseHandler.canClose] is vetoable: Cancel keeps
 * the project open, which a `projectClosing` listener could not do.
 *
 * The walkthrough is app-wide, so with two projects open both close handlers see it. The first answer
 * settles it: Save marks it clean and "Don't save" clears the mark, so the second project closes without
 * asking again.
 */
class UnsavedWalkthroughCloseHandler : ProjectCloseHandler {
    override fun canClose(project: Project): Boolean {
        val app = ApplicationManager.getApplication()
        if (app.isHeadlessEnvironment || app.isUnitTestMode || !app.isDispatchThread) return true
        // `serviceIfCreated`: a session that never touched the plugin has no walkthrough, and closing it must
        // not be what starts the loopback server.
        serviceIfCreated<ExplainerServerService>() ?: return true
        return SaveFlow.confirmLoss(project, "Closing the project discards it.")
    }
}
