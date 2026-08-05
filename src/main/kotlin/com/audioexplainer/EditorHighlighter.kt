package com.audioexplainer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Drives the editor "spotlight" from the walkthrough: **resolve a file → open its editor → tell a
 * [SpotlightRenderer] what to draw.**
 *
 * The drawing itself lives in [SpotlightRenderer] and no longer here. That split is Phase 1a of
 * `TreeOfCode.md`: the tree-of-code surface shows N editors over N real files that it lays out itself, so it
 * needs the decoration stack without the file resolution and tab opening this class exists for. Only the
 * third job was reusable, so only the third job moved.
 *
 * **One renderer per editor, created on demand.** Phase 0 measured that decorations are per-*editor*, not
 * per-document (`MultilineTreeFindings1.md` §6.4), so the renderer owns its editor and this class swaps
 * renderers when the spotlight moves to a different one — clearing the outgoing editor as it goes. The
 * in-place walkthrough only ever needs one at a time; the tree will hold N.
 *
 * Registered as the [Walkthrough]'s [WalkthroughListener]. Callbacks arrive on the server's HTTP
 * worker thread while the walkthrough lock is held, so each one returns immediately: file resolution
 * (which may hit disk / run a VFS refresh — forbidden on the EDT and undesirable under the lock) is
 * handed to a pooled thread, and only the editor mutations are then marshalled to the EDT.
 *
 * Threading of the mutable state below ([renderer]/[currentSegment]/[currentIndex]): only ever touched
 * inside the EDT bodies scheduled here, i.e. serially on the EDT — so no additional locking.
 *
 * [project] is readable so the sidebar can pick *its* project's highlighter out of the walkthrough's
 * listener list (the same discovery [HighlightSettingsConfigurable] uses for live re-render).
 */
class EditorHighlighter(internal val project: Project) : WalkthroughListener {

    private val log = Logger.getInstance(EditorHighlighter::class.java)

    /** The decoration stack for the editor currently spotlit; null before the first render and after a stop. */
    private var renderer: SpotlightRenderer? = null

    /** The segment currently spotlit (set once its editor is open), so [refresh] can re-render in place. */
    private var currentSegment: Segment? = null

    /** Active sub-highlight index within [currentSegment], so [refresh] re-renders the step actually showing. */
    private var currentIndex = 0

    init {
        // A theme switch changes every colour a render derives — the dim roles are blended toward the scheme's
        // background, the callout takes its text colour from it — but [SpotlightRenderer] computes its palette
        // *per render*, so what is already on screen keeps the old scheme's colours until something renders
        // again. Nothing did: [refresh] existed only for the settings page's Apply.
        //
        // Found from the other side, in the Phase 1a′ node-view bench: flipping dark→light left dark tints and
        // a white callout font until a repaint was forced by hand. The node view closes it with the same
        // subscription against its own `onRerenderNeeded`.
        //
        // Scoped to [project], which is also what unregisters this listener from the walkthrough
        // ([ServerStartupActivity]) — one lifetime, not two.
        ApplicationManager.getApplication().messageBus.connect(project)
            .subscribe(EditorColorsManager.TOPIC, EditorColorsListener { refresh() })
    }

    // ── WalkthroughListener ──

    override fun onSegment(segment: Segment) {
        resolveThenEdt(segment.file) { vFile ->
            val editor = openEditor(vFile, (segment.start - 1).coerceAtLeast(0)) ?: return@resolveThenEdt
            currentSegment = segment
            val r = rendererFor(editor)
            r.spotlightSegment(segment.start, segment.end)
            // Upstream defers the sub-highlight to playback (it stays on the plain spotlight while
            // paused). We reveal highlight 0 immediately so a single `set_plan` demonstrates the full
            // four-layer treatment; the Phase 4 playback loop will re-drive sub-ranges as audio advances.
            if (segment.highlights.isNotEmpty() && r.spotlightStep(segment, 0)) currentIndex = 0
        }
    }

    override fun onStatus(status: String) {
        if (status == Walkthrough.STATUS_STOPPED) {
            runOnEdt { clearHighlights() }
        }
    }

    /**
     * Move the spotlight to a specific sub-highlight of [segment] (Phase 7 narration). Opens the file
     * if needed and re-applies the four-layer treatment with [index] as the active highlight.
     */
    override fun onHighlight(segment: Segment, index: Int) {
        resolveThenEdt(segment.file) { vFile ->
            val editor = openEditor(vFile, (segment.start - 1).coerceAtLeast(0)) ?: return@resolveThenEdt
            currentSegment = segment
            val r = rendererFor(editor)
            // No segment render preceded this one, so the bounds a step render dims around have to be set
            // explicitly — otherwise it would expand from whatever the previous step left behind.
            r.setSegmentBounds(segment.start, segment.end)
            // Out-of-range index falls back to the first highlight, as before.
            val i = if (index in segment.highlights.indices) index else 0
            if (r.spotlightStep(segment, i)) currentIndex = i
        }
    }

    /**
     * Re-apply the current segment's spotlight with freshly read [AudioExplainerSettings] — called by
     * the settings page so an appearance change re-styles the live walkthrough immediately. No-op if
     * nothing is currently shown. Safe to call from any thread (marshals to the EDT).
     */
    fun refresh() {
        runOnEdt {
            val seg = currentSegment ?: return@runOnEdt
            val r = renderer ?: return@runOnEdt
            if (r.editor.isDisposed) return@runOnEdt
            r.spotlightSegment(seg.start, seg.end)
            // Re-render the step actually showing, not step 0 — otherwise a mid-walkthrough settings
            // change would silently rewind the spotlight.
            if (seg.highlights.isNotEmpty()) r.spotlightStep(seg, currentIndex)
            // Renders don't normally flash (it waits for audio), but this one is a direct "show me now" from
            // the settings page — where the flash toggle and duration live. Without it, the one decoration
            // you can only judge while it animates would be the one the preview never shows. Skipped when the
            // render above already flashed, i.e. when there is no audio to wait for.
            val hl = seg.highlights.getOrNull(currentIndex)
            if (hl != null && !SpotlightRenderer.flashOnRender()) r.flashToken(hl)
        }
    }

    /**
     * Pulse the current step's token anchor because its audio just started playing — the webview's
     * `playback_started`, relayed by [AudioExplainerSidebar]. This is the whole point of the message: the
     * flash is a *time-sensitive* cue, and step render is an unreliable proxy for "the user is hearing this"
     * (measurements in MoreUI.md §2.8). Safe to call from any thread.
     *
     * [segmentId]/[highlightIndex] identify the step the audio belongs to and are checked against what is
     * actually spotlit, so audio starting just after the user clicked Next cannot flash the *new* step's
     * token at the *old* step's audio start.
     */
    fun flashCurrentToken(segmentId: Int, highlightIndex: Int) {
        runOnEdt {
            val seg = currentSegment ?: return@runOnEdt
            val r = renderer ?: return@runOnEdt
            if (r.editor.isDisposed) return@runOnEdt
            if (seg.id != segmentId || currentIndex != highlightIndex) {
                // Stale: the spotlight moved on between the audio being scheduled and it becoming audible.
                if (log.isDebugEnabled) {
                    log.debug(
                        "playback_started for segment $segmentId step ${highlightIndex + 1} ignored — " +
                            "now showing segment ${seg.id} step ${currentIndex + 1}",
                    )
                }
                return@runOnEdt
            }
            val hl = seg.highlights.getOrNull(currentIndex) ?: return@runOnEdt
            r.flashToken(hl)
        }
    }

    /**
     * The [SpotlightRenderer] for [editor], reusing the current one when the spotlight has not moved
     * editors and building a fresh one when it has — clearing the outgoing editor first, since decorations
     * are per-editor and nothing else would remove them.
     *
     * EDT only, like everything it hands back.
     */
    private fun rendererFor(editor: Editor): SpotlightRenderer {
        val cur = renderer
        if (cur != null && cur.editor === editor && !editor.isDisposed) return cur
        cur?.clear()
        return SpotlightRenderer(editor, ::navigateTo).also { renderer = it }
    }

    /** Remove all decorations (upstream `clearHighlights`). EDT only. */
    private fun clearHighlights() {
        renderer?.clear()
        renderer = null
        currentSegment = null
        currentIndex = 0
    }

    /**
     * Open a relation's target — the gutter pointer's click action, handed to each [SpotlightRenderer] as its
     * navigator. A cross-file target goes through [resolveThenEdt], because resolving it may hit the VFS and
     * a click arrives on the EDT where that is a prohibited slow op. A target with no file is a line of the
     * file already showing, so it just scrolls there. Safe to call from any thread.
     */
    private fun navigateTo(ep: Endpoint) {
        val line = (ep.line - 1).coerceAtLeast(0)
        val path = ep.file?.trim()?.takeIf { it.isNotEmpty() }
        if (path == null) {
            runOnEdt {
                val r = renderer ?: return@runOnEdt
                if (!r.editor.isDisposed) r.reveal(line)
            }
            return
        }
        resolveThenEdt(path) { vFile -> openEditor(vFile, line) }
    }

    // ── Threading / file resolution ──

    /**
     * Resolve the file OFF the EDT (VFS refresh is a prohibited slow op on the EDT, and we don't want
     * disk I/O on the HTTP thread that still holds the walkthrough lock), then run [action] with the
     * resolved [VirtualFile] on the EDT.
     */
    private fun resolveThenEdt(filePath: String, action: (VirtualFile) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val vFile = resolveFile(filePath)
            if (vFile == null) {
                // The likeliest authoring fault in a generated plan: a path the agent invented or wrote
                // relative to the wrong root. Nothing highlights and, until now, nothing said why.
                log.warn("Walkthrough segment file could not be resolved, skipping highlight: $filePath")
                return@executeOnPooledThread
            }
            runOnEdt { action(vFile) }
        }
    }

    /**
     * Run [block] on the EDT, swallowing failures so a closed editor / vanished file cannot take down the
     * HTTP server's caller — but **logging** them, because a silently-caught render failure is otherwise
     * indistinguishable from a successful one (nothing here is on a path anyone watches). `warn` rather
     * than `error` deliberately: a decoration that failed to draw is not worth the red "IDE internal
     * error" balloon `error` raises, and this is a background render, not a user action.
     *
     * [ProcessCanceledException] is rethrown, never logged: it is the platform's cancellation *signal*,
     * not a fault, and swallowing it breaks the cancellation contract.
     */
    private fun runOnEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                try {
                    block()
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (t: Throwable) {
                    log.warn("Walkthrough decoration render failed", t)
                }
            }
        }
    }

    /** Off-EDT only: performs a synchronous VFS refresh so newly written files are found. */
    private fun resolveFile(filePath: String): VirtualFile? = try {
        val nio = Path.of(filePath)
        val abs = if (nio.isAbsolute) nio else project.basePath?.let { Path.of(it).resolve(nio) } ?: nio
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(abs.normalize())
    } catch (_: Exception) {
        null
    }

    /** EDT only. */
    private fun openEditor(vFile: VirtualFile, line: Int): Editor? =
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vFile, line, 0), true)
}
