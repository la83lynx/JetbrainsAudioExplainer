package com.audioexplainer

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Lifecycle phase of an "Explain This" generation, surfaced inside the Audio Explainer tool window. */
enum class ExplainPhase { IDLE, GENERATING, FAILED }

/** Immutable snapshot of the current generation, pushed to the sidebar's loading banner. */
data class ExplainSessionState(
    val phase: ExplainPhase,
    /** What is being explained (e.g. `Foo.cs:42`) — shown while generating. */
    val displayName: String = "",
    /** Latest activity line (mirrors `ClaudeExplainer`'s `indicator.text2`). */
    val activity: String = "",
    /** How far the run has got, 0..1 ([ExplainProgress]), or null before the estimate starts. */
    val fraction: Double? = null,
    /** Failure detail (FAILED only). */
    val message: String = "",
    /** [RunLog.warning]: the run may be looping. Empty while it is not. */
    val warning: String = "",
)

/**
 * Phase 7.5 #5 — project-scoped signal of an in-flight generation.
 *
 * [ClaudeExplainer] is an app-level service that runs on a background task but holds the [Project]; the
 * sidebar is per-tool-window. This tracker bridges them: `ClaudeExplainer` flips the state as generation
 * starts / progresses / finishes / fails, and the sidebar (JCEF or Swing) subscribes and renders a
 * loading banner. Listeners are notified on whatever thread mutates the state, so subscribers must
 * marshal UI work themselves (both sidebars already `invokeLater` / push to the webview off-thread).
 */
@Service(Service.Level.PROJECT)
class ExplainSessionTracker {
    fun interface Listener { fun onChange(state: ExplainSessionState) }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val state = AtomicReference(ExplainSessionState(ExplainPhase.IDLE))

    /** Current state — read by a freshly-created sidebar to catch up on a generation already running. */
    val current: ExplainSessionState get() = state.get()

    /** The latest run's log in this project, kept after it ends for Show Run Log. */
    @Volatile var lastLog: RunLog? = null
        private set

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    /**
     * The running task's indicator, for the tool window's Cancel Explanation: cancelling it is what the status bar's
     * × does, so the run stops the same way. Kept past [finish] — a plan announced early ends the banner while the
     * agent may still be fixing it — and asked [isRunning] instead of cleared.
     */
    @Volatile private var indicator: ProgressIndicator? = null

    @JvmOverloads
    fun startGenerating(displayName: String, log: RunLog? = null, indicator: ProgressIndicator? = null) {
        if (log != null) lastLog = log
        if (indicator != null) this.indicator = indicator
        set(ExplainSessionState(ExplainPhase.GENERATING, displayName, activity = "Starting…"))
    }

    /** An Explain This run is in progress here and can still be cancelled. */
    val isRunning: Boolean get() = indicator?.let { it.isRunning && !it.isCanceled } == true

    fun cancel() {
        indicator?.takeIf { it.isRunning }?.cancel()
    }

    /** Update the activity line — no-op unless a generation is in progress (ignores late/stray pings). */
    @JvmOverloads
    fun progress(activity: String, fraction: Double? = null, warning: String? = null) {
        val s = state.get()
        if (s.phase == ExplainPhase.GENERATING) {
            set(s.copy(activity = activity, fraction = fraction ?: s.fraction, warning = warning ?: s.warning))
        }
    }

    /** Generation done (the plan, if any, arrives via the walkthrough) — clear the banner. */
    fun finish() = set(ExplainSessionState(ExplainPhase.IDLE))

    /** Also the run log's verdict, unless the run already gave it one. */
    fun fail(message: String) {
        lastLog?.end(false, message)
        set(ExplainSessionState(ExplainPhase.FAILED, state.get().displayName, message = message))
    }

    private fun set(s: ExplainSessionState) {
        state.set(s)
        listeners.forEach { runCatching { it.onChange(s) } }
    }

    companion object {
        fun getInstance(project: Project): ExplainSessionTracker = project.service()
    }
}
