package com.audioexplainer

import com.audioexplainer.tree.TreePlanView
import com.audioexplainer.tree.TreePlanVirtualFile
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * **Drives the tree surface from walkthrough state, and it is now the only surface.** Resolves N files, opens
 * one tab and tells N node editors what to draw.
 *
 * There was a second renderer beside this one — an in-place spotlight over the user's own editor — and a
 * `Plan.view` field chose between them. Round 3 judged a one-frame scene rendered as a one-node tree as good
 * as the in-place surface for the commonest case, so both are gone and there is no guard here any more: every
 * plan reaches this.
 *
 * **One timeline, and since Phase 3 one number.** This does not run a step counter of its own and no longer
 * has to translate one: [WalkthroughListener.onStep] carries the plan's own step index, so the narration
 * controller, the sidebar and the tree cannot disagree about which step is showing. The `(segment,
 * highlight)` pair that used to arrive here and be mapped back through the projection is gone with it.
 *
 * Threading, for measured reasons: callbacks arrive on the server's HTTP worker thread while the walkthrough
 * lock is held, so file resolution goes to a pooled thread (a VFS refresh is a prohibited slow op on the EDT
 * and unwelcome under the lock) and only the editor mutations are marshalled to the EDT.
 */
class TreePlanController(private val project: Project, private val walkthrough: Walkthrough) : WalkthroughListener {

    private val log = Logger.getInstance(TreePlanController::class.java)

    /** The live view, or null when no tree is open. Touched only on the EDT. */
    private var view: TreePlanView? = null

    /** The file the tab hangs on, kept so a new plan can close the old tab rather than stack tabs. */
    private var openFile: TreePlanVirtualFile? = null

    override fun onPlan(state: WalkthroughSnapshot) {
        val resolved = walkthrough.getResolvedPlan() ?: return
        if (resolved.frames.isEmpty()) {
            notify(
                "Nothing to render",
                "The plan asked for the tree view but no frame in it is renderable.<br/>" +
                    resolved.issueReport().replace("\n", "<br/>"),
                NotificationType.WARNING,
            )
            return
        }
        // Resolve every frame's file off the EDT, in one pass, before anything is built: a tree is N files
        // and a per-node resolution would put N VFS refreshes on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val files = HashMap<Int, VirtualFile>()
            for (frame in resolved.frames) {
                resolveFile(frame.file)?.let { files[frame.id] = it }
            }
            runOnEdt { openTree(resolved, files) }
        }
    }

    /** The position moved: paint that step. The tree is already built by [onPlan]. */
    override fun onStep(index: Int) {
        runOnEdt { view?.applyStep(index) }
    }

    override fun onStatus(status: String) {
        if (status == Walkthrough.STATUS_STOPPED) runOnEdt { closeTree() }
    }

    /**
     * **Playback resumed — put the step back where the plan put it.**
     *
     * Phase 3's scroll policy: a reader may scroll a node freely while a step is up, and pressing Play
     * re-asserts the authored viewport. It is a separate callback from [onStep] because the step has *not*
     * changed — re-painting it would be wrong (the callout would re-dock, the links would re-allocate) and
     * doing nothing would leave the reader's own scroll in front of the narration that is about to start.
     */
    override fun onResume() {
        runOnEdt { view?.reassertViewport() }
    }

    /**
     * Pulse the current step's token in the tree, because its audio just started playing. Checked against the
     * step index the webview reports, so audio starting just after a step change cannot flash the new step's
     * token at the old step's audio start.
     */
    fun flashStartBeat(step: Int) {
        if (step < 0) return
        runOnEdt {
            val v = view ?: return@runOnEdt
            if (v.stepIndex == step) v.flashStartBeat()
        }
    }

    /**
     * Pulse the [beat]-th beat of [step] — the webview reports one whenever the voice reaches the sentence
     * that beat is timed to. Checked against the painted step for the same reason [flashCurrentToken] is.
     */
    fun flashBeat(step: Int, beat: Int) {
        if (step < 0 || beat < 0) return
        runOnEdt {
            val v = view ?: return@runOnEdt
            if (v.stepIndex == step) v.flashBeat(beat)
        }
    }

    /**
     * **Show a file/line inside the tree instead of in a tab** — the sidebar's Now-Playing location link.
     *
     * Same reasoning as [followRelation], reached from a different click: while a tree is showing, the code
     * the link names is already on screen in a node, so opening a tab of it would take focus away from the
     * thing the link was describing. Returns false when no node owns the file, which is the sidebar's cue to
     * fall back to opening it.
     *
     * EDT only.
     */
    fun revealInTree(filePath: String, line1: Int): Boolean =
        view?.revealEndpoint(Endpoint(file = filePath, line = line1)) == true

    /** The live view, for a bench or a report. EDT only. */
    fun currentView(): TreePlanView? = view

    /**
     * **Re-style the open tree with whatever the settings now say** — Settings ▸ Apply's live path.
     *
     * This replaces `EditorHighlighter.refresh()`, which was the only thing the settings dialog knew how to
     * reach — deleting that surface without putting this here would have left Apply compiling and doing
     * nothing (constraint 11).
     *
     * **Two halves, and Phase 4 added the first one.** The tree's *shape* — the leader mode, the height
     * ceiling, the elision style — is not re-derived by painting a step, so [TreePlanView.restyle] has to be
     * asked; every *colour* is derived per render, so [TreePlanView.repaintStep] is enough for the rest.
     * The `else` is not a fallback: `restyle` repaints on its way out when it changed something, and doing
     * both would paint the step twice on every Apply.
     */
    fun refresh() {
        runOnEdt {
            val v = view ?: return@runOnEdt
            if (!v.restyle()) v.repaintStep()
        }
    }

    // ── Internals ──

    /** EDT only. */
    private fun openTree(resolved: ResolvedPlan, files: Map<Int, VirtualFile>) {
        closeTree()
        val state = walkthrough.getState()
        // **Built here, not in the supplier.** The FileEditor creates its component lazily, so a view
        // constructed in the supplier exists only once the platform decides to show the tab — and the issue
        // report, the first step and the editor count below would all be reading a null. Building it first
        // makes "the tree exists" a fact of this method rather than a hope about the platform's timing.
        val v = TreePlanView(project, resolved, files, navigate = ::followRelation)
        // A file deleted under an open tree drops its node and the tree carries on — but silently dropping a
        // node looks exactly like a rendering defect, so it is a balloon for the same reason an unresolvable
        // path at open is one. A file merely *replaced* at the same path is re-taken and says nothing, which
        // is the common case and not an event the reader needs.
        v.onFramesDropped = { dropped ->
            notify(
                "A file under the tree was deleted",
                dropped.joinToString("<br/>") { id ->
                    "frame $id (${resolved.frame(id)?.file ?: "unknown file"}) is no longer rendered"
                },
                NotificationType.WARNING,
            )
        }
        val file = TreePlanVirtualFile(
            name = state.title.ifBlank { "Code tree" },
            componentSupplier = { v },
            // The tab's own teardown is what releases N editors; going through the file's callback means
            // there is one path for "the user closed it" and "a new plan replaced it".
            onDispose = {
                v.dispose()
                if (view === v) view = null
            },
        )
        view = v
        openFile = file
        FileEditorManager.getInstance(project).openFile(file, true)
        val issues = v.issues()
        if (issues.isNotEmpty()) {
            // The reader's channel for the same list the agent got on its POST. Errors are worth a balloon
            // because something is missing from the picture; warnings and info are in the log only, or every
            // plan with an inferred focus range would raise one.
            log.info("tree plan issues:\n${issues.joinToString("\n")}")
            val errors = issues.filter { it.level == IssueLevel.ERROR }
            if (errors.isNotEmpty()) {
                notify(
                    "Plan partly rendered",
                    errors.joinToString("<br/>") { it.message },
                    NotificationType.WARNING,
                )
            }
        }
        // Land on whatever step the walkthrough is already on — normally the first, but a tree opened after
        // a `goto` has to arrive where the narration is rather than at the beginning.
        v.applyStep(walkthrough.currentStepIndex() ?: 0)
    }

    /**
     * **A relation pointer was clicked** — follow it inside the tree when the tree contains its target.
     *
     * The in-place surface answers this by opening the target's file, which is right when a file tab is what
     * the reader is looking at. In the tree it would be the same defect the G2 round found: a tab in front of
     * the tree. So the tree is asked first, and only a target no node owns falls back to a file — and that
     * fallback is a *click*, not a side effect of stepping, which is the whole difference.
     */
    private fun followRelation(ep: Endpoint) {
        if (view?.revealEndpoint(ep) == true) return
        val path = ep.file?.trim()?.takeIf { it.isNotEmpty() }
        if (path == null) {
            log.info("relation target names no file and no node owns it — nothing to follow")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val file = resolveFile(path) ?: return@executeOnPooledThread
            log.info("relation target is outside the tree, opening $path:${ep.line}")
            runOnEdt {
                FileEditorManager.getInstance(project)
                    .openTextEditor(OpenFileDescriptor(project, file, (ep.line - 1).coerceAtLeast(0), 0), true)
            }
        }
    }

    /** EDT only. */
    private fun closeTree() {
        val file = openFile ?: return
        openFile = null
        if (!project.isDisposed) FileEditorManager.getInstance(project).closeFile(file)
        // closeFile disposes the FileEditor, which calls back into onDispose; this is belt-and-braces for
        // the case where the tab was never opened.
        view?.dispose()
        view = null
    }

    /** Off-EDT only: performs a synchronous VFS refresh so newly written files are found. */
    private fun resolveFile(filePath: String): VirtualFile? = try {
        val nio = Path.of(filePath)
        val abs = if (nio.isAbsolute) nio else project.basePath?.let { Path.of(it).resolve(nio) } ?: nio
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(abs.normalize())
    } catch (_: Exception) {
        null
    }

    private fun runOnEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                try {
                    block()
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (t: Throwable) {
                    log.warn("tree render failed", t)
                }
            }
        }
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Audio Explainer")
            .createNotification(title, content, type)
            .notify(project)
    }
}
