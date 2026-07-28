package com.audioexplainer

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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
    /** Failure detail (FAILED only). */
    val message: String = "",
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

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    fun startGenerating(displayName: String) =
        set(ExplainSessionState(ExplainPhase.GENERATING, displayName, activity = "Starting…"))

    /** Update the activity line — no-op unless a generation is in progress (ignores late/stray pings). */
    fun progress(activity: String) {
        val s = state.get()
        if (s.phase == ExplainPhase.GENERATING) set(s.copy(activity = activity))
    }

    /** Generation done (the plan, if any, arrives via the walkthrough) — clear the banner. */
    fun finish() = set(ExplainSessionState(ExplainPhase.IDLE))

    fun fail(message: String) =
        set(ExplainSessionState(ExplainPhase.FAILED, state.get().displayName, message = message))

    private fun set(s: ExplainSessionState) {
        state.set(s)
        listeners.forEach { runCatching { it.onChange(s) } }
    }

    companion object {
        fun getInstance(project: Project): ExplainSessionTracker = project.service()
    }
}
