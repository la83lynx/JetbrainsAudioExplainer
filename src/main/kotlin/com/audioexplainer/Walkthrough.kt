package com.audioexplainer

import java.util.concurrent.CopyOnWriteArrayList

/** Immutable view of the walkthrough state, handed to HTTP handlers and listeners. */
data class WalkthroughSnapshot(
    val title: String,
    val segments: List<Segment>,
    val currentIndex: Int,
    val currentHighlightIndex: Int,
    val status: String,
    /** Language code of the loaded walkthrough (see [Languages]); "en" by default. */
    val language: String = Languages.DEFAULT_CODE,
)

/**
 * Observer of walkthrough changes — Phase 3 (editor highlighting) and Phase 4 (sidebar) register one.
 *
 * Callbacks fire on the server's HTTP worker thread while the state lock is held, so implementations
 * must return quickly and NOT block: marshal any editor/UI work to the EDT asynchronously
 * (`invokeLater` / `WriteCommandAction`).
 */
interface WalkthroughListener {
    /** Current segment changed (agent should reveal/scroll to it). */
    fun onSegment(segment: Segment) {}
    /** Plan set or structurally mutated. */
    fun onPlan(state: WalkthroughSnapshot) {}
    /** Playback status changed. */
    fun onStatus(status: String) {}
    /**
     * The active sub-highlight within the current segment changed (Phase 7 narration). Drives the
     * per-highlight editor spotlight; the sidebar tracks its own highlight progress from the
     * `highlight_advance` message the narration controller pushes.
     */
    fun onHighlight(segment: Segment, index: Int) {}
}

/**
 * Walkthrough plan state, navigation, and mutations — a faithful port of code-explainer's
 * `vscode-extension/src/walkthrough.ts`. All public methods are synchronized, since HTTP handlers
 * mutate this from multiple worker threads.
 *
 * Status values match upstream `WalkthroughStatus`: "idle" | "paused" | "playing" | "stopped".
 * Semantics preserved from upstream: `setPlan` lands on "paused" (reveals segment 0 without
 * auto-playing); the agent's `goto` maps to [navigateTo] (no status change); `resume` → [play].
 */
class Walkthrough {
    private var title: String = ""
    private val segments: MutableList<Segment> = mutableListOf()
    private var currentIndex: Int = -1
    private var currentHighlightIndex: Int = 0
    private var status: String = STATUS_IDLE
    private var language: String = Languages.DEFAULT_CODE

    /**
     * The walkthrough that was loaded immediately before the current one (Phase 7.5 #5). Captured by
     * [setPlan] whenever a *non-empty* plan is replaced, so the sidebar can offer a one-level **Undo**
     * ([restorePrevious]) rather than silently clobbering whatever the user had open.
     */
    private var previous: WalkthroughSnapshot? = null

    /**
     * Observers of walkthrough changes. Phase 3 registers the [EditorHighlighter]; Phase 4 registers
     * the sidebar. Copy-on-write so callbacks can iterate without holding a separate lock and so a
     * listener may (de)register itself from within a callback without a concurrent-modification.
     */
    private val listeners = CopyOnWriteArrayList<WalkthroughListener>()

    fun addListener(listener: WalkthroughListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: WalkthroughListener) {
        listeners.remove(listener)
    }

    /** Snapshot of the current listeners (e.g. to find the [EditorHighlighter] for a live re-render). */
    fun getListeners(): List<WalkthroughListener> = listeners.toList()

    private fun fireStatus(status: String) = listeners.forEach { it.onStatus(status) }
    private fun firePlan(state: WalkthroughSnapshot) = listeners.forEach { it.onPlan(state) }
    private fun fireSegment(segment: Segment) = listeners.forEach { it.onSegment(segment) }
    private fun fireHighlight(segment: Segment, index: Int) = listeners.forEach { it.onHighlight(segment, index) }

    @Synchronized
    fun getState(): WalkthroughSnapshot =
        WalkthroughSnapshot(title, segments.toList(), currentIndex, currentHighlightIndex, status, language)

    @Synchronized
    fun getCurrentSegment(): Segment? = segments.getOrNull(currentIndex)

    // ── Plan lifecycle ──

    @Synchronized
    fun setPlan(title: String, segments: List<Segment>, language: String = Languages.DEFAULT_CODE) {
        // Preserve whatever was loaded so an unexpected replacement can be undone (Phase 7.5 #5).
        // Only snapshot a non-empty plan; the common "first load over idle" case leaves no undo.
        previous = if (this.segments.isNotEmpty()) getState() else null
        this.title = title
        this.segments.clear()
        this.segments.addAll(segments)
        this.language = language.ifBlank { Languages.DEFAULT_CODE }
        this.currentIndex = if (segments.isEmpty()) -1 else 0
        this.currentHighlightIndex = 0
        this.status = STATUS_PAUSED
        firePlan(getState())
        fireStatus(status)
        // Reveal the first segment's code location without starting playback.
        segments.firstOrNull()?.let { fireSegment(it) }
    }

    /** Whether a replaced walkthrough is retained for [restorePrevious] (Phase 7.5 #5 "Undo"). */
    @Synchronized
    fun hasPrevious(): Boolean = previous != null

    /** Title of the walkthrough that [restorePrevious] would bring back, or null if there is none. */
    @Synchronized
    fun previousTitle(): String? = previous?.title

    /** Forget the retained previous walkthrough — the user chose to **Keep** the current one. */
    @Synchronized
    fun clearPrevious() {
        previous = null
    }

    /**
     * Restore the walkthrough that was replaced by the last [setPlan] (Phase 7.5 #5 "Undo"). Returns
     * false if there is nothing to restore. Comes back at the segment the user was on, **paused** (never
     * auto-plays, even if the previous state was playing), with the sub-highlight reset to the segment
     * start. Consumes the retained snapshot — undo is one level deep.
     */
    @Synchronized
    fun restorePrevious(): Boolean {
        val prev = previous ?: return false
        previous = null
        title = prev.title
        segments.clear()
        segments.addAll(prev.segments)
        language = prev.language
        currentIndex = prev.currentIndex.coerceIn(-1, segments.size - 1)
        currentHighlightIndex = 0
        status = if (segments.isEmpty()) STATUS_IDLE else STATUS_PAUSED
        firePlan(getState())
        fireStatus(status)
        segments.getOrNull(currentIndex)?.let { fireSegment(it) }
        return true
    }

    @Synchronized
    fun stop() {
        status = STATUS_STOPPED
        fireStatus(status)
    }

    // ── Navigation ──

    @Synchronized
    fun play() {
        if (status == STATUS_PAUSED) {
            status = STATUS_PLAYING
            fireStatus(status)
        }
    }

    @Synchronized
    fun pause() {
        if (status == STATUS_PLAYING) {
            status = STATUS_PAUSED
            fireStatus(status)
        }
    }

    @Synchronized
    fun togglePlayPause() {
        when (status) {
            STATUS_PLAYING -> pause()
            STATUS_PAUSED -> play()
        }
    }

    /**
     * Step to the next segment, **preserving the current play/pause status** (no forced auto-play).
     *
     * Rationale (Phase 7.5 #3): the Prev/Next buttons are navigation, not a transport command — starting
     * audio when the user was paused/stopped is surprising. During continuous playback the status is
     * already `playing`, so the narration loop's own advance keeps playing; a user stepping while paused
     * stays paused (spotlight + sidebar move, no audio). Only the explicit outline jump ([goto]) plays.
     */
    @Synchronized
    fun next(): Boolean {
        val nextIdx = currentIndex + 1
        if (nextIdx >= segments.size) {
            // Reached the end. If we were playing, settle to paused (nothing left to narrate).
            if (status == STATUS_PLAYING) {
                status = STATUS_PAUSED
                fireStatus(status)
            }
            return false
        }
        currentIndex = nextIdx
        currentHighlightIndex = 0
        fireStatus(status) // status unchanged — re-syncs listeners to the new position
        fireSegment(segments[nextIdx])
        return true
    }

    /** Step to the previous segment, preserving play/pause status (see [next]). */
    @Synchronized
    fun prev(): Boolean {
        val prevIdx = currentIndex - 1
        if (prevIdx < 0) return false
        currentIndex = prevIdx
        currentHighlightIndex = 0
        fireStatus(status)
        fireSegment(segments[prevIdx])
        return true
    }

    /** Sidebar "jump to segment": moves AND starts playback (upstream `goto`). */
    @Synchronized
    fun goto(segmentId: Int): Boolean {
        val idx = segments.indexOfFirst { it.id == segmentId }
        if (idx == -1) return false
        currentIndex = idx
        currentHighlightIndex = 0
        status = STATUS_PLAYING
        fireStatus(status)
        fireSegment(segments[idx])
        return true
    }

    /** Agent `goto`: navigate to a segment WITHOUT changing playback status (no auto-play). */
    @Synchronized
    fun navigateTo(segmentId: Int): Boolean {
        val idx = segments.indexOfFirst { it.id == segmentId }
        if (idx == -1) return false
        currentIndex = idx
        currentHighlightIndex = 0
        firePlan(getState())
        fireSegment(segments[idx])
        return true
    }

    /**
     * Jump to a segment by id **preserving play/pause status** — the outline-click analogue of [next]/
     * [prev] (Phase 7.5 #4, revised): clicking a step navigates, it does not force playback. Fires the
     * light `onStatus`/`onSegment` fan-out (not [navigateTo]'s `onPlan`, which hard-resets narration), so
     * a paused user stays paused with only the spotlight + sidebar moving.
     */
    @Synchronized
    fun navigateToPreservingStatus(segmentId: Int): Boolean {
        val idx = segments.indexOfFirst { it.id == segmentId }
        if (idx == -1) return false
        currentIndex = idx
        currentHighlightIndex = 0
        fireStatus(status) // status unchanged — re-syncs listeners to the new position
        fireSegment(segments[idx])
        return true
    }

    @Synchronized
    fun setHighlightIndex(index: Int) {
        currentHighlightIndex = index
    }

    @Synchronized
    fun getHighlightIndex(): Int = currentHighlightIndex

    /**
     * Move the active sub-highlight within the current segment and notify listeners (Phase 7). Called
     * by the narration controller as audio advances; the editor spotlight follows via [onHighlight].
     */
    @Synchronized
    fun advanceHighlight(index: Int) {
        currentHighlightIndex = index
        val seg = segments.getOrNull(currentIndex) ?: return
        fireHighlight(seg, index)
    }

    // ── Plan mutations ──

    @Synchronized
    fun insertAfter(afterSegmentId: Int, newSegments: List<Segment>) {
        val idx = segments.indexOfFirst { it.id == afterSegmentId }
        if (idx == -1) return
        segments.addAll(idx + 1, newSegments)
        // Keep the current position pointing at the same segment if insertion was before it.
        if (idx < currentIndex) currentIndex += newSegments.size
        firePlan(getState())
    }

    @Synchronized
    fun replaceSegment(id: Int, segment: Segment) {
        val idx = segments.indexOfFirst { it.id == id }
        if (idx == -1) return
        segments[idx] = segment
        firePlan(getState())
        if (idx == currentIndex) fireSegment(segment)
    }

    @Synchronized
    fun removeSegments(ids: List<Int>) {
        val idSet = ids.toSet()
        val currentSegment = getCurrentSegment()
        segments.removeAll { it.id in idSet }
        when {
            currentSegment != null && currentSegment.id !in idSet ->
                currentIndex = segments.indexOfFirst { it.id == currentSegment.id }
            segments.isEmpty() -> {
                currentIndex = -1
                status = STATUS_IDLE
                fireStatus(status)
            }
            else -> currentIndex = minOf(currentIndex, segments.size - 1)
        }
        firePlan(getState())
    }

    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_PAUSED = "paused"
        const val STATUS_PLAYING = "playing"
        const val STATUS_STOPPED = "stopped"
    }
}
