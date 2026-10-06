package com.audioexplainer

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Immutable view of the walkthrough state, handed to HTTP handlers and listeners.
 *
 * **One position, and it is [stepIndex].** This used to carry `segments` + `currentIndex` +
 * `currentHighlightIndex` — a projected list and a two-level cursor into it, which four components had to
 * keep in agreement and which round 3's outline defect was a symptom of. Phase 3 flattened it: [plan] is the
 * source of truth, [outline] is a read-only view of it for drawing rows, and the position is one index into
 * the plan's own `steps`.
 */
data class WalkthroughSnapshot(
    val title: String,
    /** The sidebar's row model — see [PlanOutline]. Derived from [plan] on load, never a second timeline. */
    val outline: Outline,
    /** The plan step showing, or -1 before a plan is loaded. **The whole position model.** */
    val stepIndex: Int,
    val stepCount: Int,
    val status: String,
    /** Language code of the loaded walkthrough (see [Languages]); "en" by default. */
    val language: String = Languages.DEFAULT_CODE,
    /** The plan as authored. What gets saved, and what the tree surface renders. */
    val plan: Plan = Plan(),
    /**
     * **The saved walkthrough this plan is** — the file stem under `.walkthroughs/` — or null when it has
     * never been saved (`Saving.md` C). Save writes this file without asking; with none, Save asks for a name.
     */
    val savedAs: String? = null,
    /**
     * **The plan has content no file holds**: a generated plan that was never saved. What the unsaved prompt
     * asks about (`Saving.md` D).
     */
    val unsaved: Boolean = false,
) {
    /**
     * **Losing this plan loses work, so ask first** (`Saving.md` D). [unsaved] alone is not enough: a plan
     * with no title or no frames is one `saveCurrentWalkthrough` refuses to write, and offering Save on it
     * would offer a button that fails.
     */
    val worthAsking: Boolean get() = unsaved && title.isNotEmpty() && plan.frames.isNotEmpty()
}

/**
 * Observer of walkthrough changes — the tree surface and the sidebar each register one.
 *
 * Callbacks fire on the server's HTTP worker thread while the state lock is held, so implementations
 * must return quickly and NOT block: marshal any editor/UI work to the EDT asynchronously
 * (`invokeLater` / `WriteCommandAction`).
 */
interface WalkthroughListener {
    /**
     * **The position moved to plan step [index]** — the one navigation callback.
     *
     * It replaces `onSegment` + `onHighlight`, which were the two-level position arriving as two events: a
     * listener had to know that a segment change implied highlight 0 and that a highlight change implied the
     * segment was unchanged, and round 3's defect was a listener that consumed one and not the other. There
     * is nothing left to get wrong — every move is this, carrying the step it moved to.
     */
    fun onStep(index: Int) {}

    /** Plan set or structurally mutated. */
    fun onPlan(state: WalkthroughSnapshot) {}

    /** Playback status changed. */
    fun onStatus(status: String) {}

    /**
     * **The save state changed and nothing else did** — the plan was saved, or its unsaved content was
     * discarded (`Saving.md`). Only the sidebar cares: its Save button and saved list read it. A new plan
     * carries its save state in [onPlan]'s snapshot and does not fire this.
     */
    fun onSaveState(state: WalkthroughSnapshot) {}

    /**
     * **Playback started or resumed** — distinct from [onStatus] because it is what re-asserts the authored
     * viewport (Phase 3's scroll policy). A reader may scroll a node freely while a step is up; pressing
     * Play puts the step back where the plan put it, without the step itself having changed.
     */
    fun onResume() {}
}

/**
 * Walkthrough plan state, navigation, and mutations — a faithful port of code-explainer's
 * `vscode-extension/src/walkthrough.ts`. All public methods are synchronized, since HTTP handlers
 * mutate this from multiple worker threads.
 *
 * Status values match upstream `WalkthroughStatus`: "idle" | "paused" | "playing" | "stopped".
 * Semantics preserved from upstream: `setPlan` lands on "paused" (reveals step 0 without
 * auto-playing); the agent's `goto` maps to [navigateToStep] (no status change); `resume` → [play].
 */
class Walkthrough {
    private var title: String = ""

    /** The plan as authored — `frames` + `steps`, and the source of truth. */
    private var plan: Plan = Plan()

    /** [plan] after validation: what is renderable, plus every issue found. Null before the first plan. */
    private var resolved: ResolvedPlan? = null

    /** The sidebar's row model, rebuilt with every plan and never written to. */
    private var outline: Outline = Outline()

    /**
     * **The position: which plan step is showing.** -1 before a plan is loaded.
     *
     * Phase 3's flattening, and the whole of it. Everything that used to hold `(segment, highlightIndex)` —
     * this class, [NarrationController], [TreePlanController], the sidebar's outline, the `playback_started`
     * staleness check — names a step by this index now, so none of them can disagree about which step is up.
     */
    private var stepIndex: Int = -1

    private var status: String = STATUS_IDLE
    private var language: String = Languages.DEFAULT_CODE

    /**
     * **Which saved walkthrough this plan is**, as a file stem, or null (`Saving.md` C). It lives here and not
     * in the sidebar because this is the object that knows when the plan is replaced: [setPlan] clears it,
     * [markSaved] sets it, [stop] clears it.
     */
    private var savedAs: String? = null

    /**
     * **The plan has content no file holds** — see [WalkthroughSnapshot.unsaved]. A new plan is unsaved
     * unless it came *from* a file, and position never touches it: playback and navigation are not saved,
     * so they cannot make anything stale.
     */
    private var unsaved: Boolean = false

    /**
     * The walkthrough that was loaded immediately before the current one (Phase 7.5 #5). Captured by
     * [setPlan] whenever a *non-empty* plan is replaced, so the sidebar can offer a one-level **Undo**
     * ([restorePrevious]) rather than silently clobbering whatever the user had open.
     */
    private var previous: WalkthroughSnapshot? = null

    /**
     * Observers of walkthrough changes. [TreePlanController] renders the tree from them; the sidebar shows
     * the outline. Copy-on-write so callbacks can iterate without holding a separate lock and so a
     * listener may (de)register itself from within a callback without a concurrent-modification.
     */
    private val listeners = CopyOnWriteArrayList<WalkthroughListener>()

    fun addListener(listener: WalkthroughListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: WalkthroughListener) {
        listeners.remove(listener)
    }

    /** Snapshot of the current listeners (e.g. to find the [TreePlanController] for a live re-style). */
    fun getListeners(): List<WalkthroughListener> = listeners.toList()

    private fun fireStatus(status: String) = listeners.forEach { it.onStatus(status) }
    private fun firePlan(state: WalkthroughSnapshot) = listeners.forEach { it.onPlan(state) }
    private fun fireStep(index: Int) = listeners.forEach { it.onStep(index) }
    private fun fireResume() = listeners.forEach { it.onResume() }
    private fun fireSaveState(state: WalkthroughSnapshot) = listeners.forEach { it.onSaveState(state) }

    @Synchronized
    fun getState(): WalkthroughSnapshot =
        WalkthroughSnapshot(title, outline, stepIndex, stepCount(), status, language, plan, savedAs, unsaved)

    /** The validated plan, for the surface that renders `frames` directly. Null before the first plan. */
    @Synchronized
    fun getResolvedPlan(): ResolvedPlan? = resolved

    /** The sidebar's row model for the loaded plan. */
    @Synchronized
    fun getOutline(): Outline = outline

    /** The 0-based plan step currently showing, or null before a plan is loaded. */
    @Synchronized
    fun currentStepIndex(): Int? = stepIndex.takeIf { it >= 0 }

    /** How many plan steps there are. */
    @Synchronized
    fun stepCount(): Int = resolved?.steps?.size ?: 0

    /** What the sidebar shows for the step now playing, or null when nothing is. */
    @Synchronized
    fun currentStepView(): StepView? = resolved?.let { PlanOutline.stepView(it, stepIndex) }

    // ── Plan lifecycle ──

    /**
     * Load [newPlan]: validate it, build its outline, and land **paused** on its first step — upstream's
     * semantics, unchanged.
     *
     * Returns the [ResolvedPlan] so the caller can report what was wrong with it. **That return value is
     * the point**: the `set_plan` responder is the only channel that reaches the agent that authored the
     * plan, and §1b's second requirement is that a plan rendering three of its four frames says so.
     *
     * [savedAs] is the file the plan was read from, when it was (`Saving.md` C): a walkthrough loaded from the
     * saved list *is* that save, so it lands clean and its Save writes back to it. Every other plan — a
     * generation, an agent's own `set_plan` — lands unsaved with no identity. Passed in rather than set after,
     * so no listener ever sees a loaded save as an unsaved plan for the length of one callback.
     */
    @JvmOverloads
    @Synchronized
    fun setPlan(newPlan: Plan, savedAs: String? = null): ResolvedPlan {
        val resolvedPlan = PlanLoader.resolve(newPlan)
        // Preserve whatever was loaded so an unexpected replacement can be undone (Phase 7.5 #5).
        // Only snapshot a non-empty plan; the common "first load over idle" case leaves no undo.
        previous = if (stepCount() > 0) getState() else null
        this.plan = newPlan
        this.resolved = resolvedPlan
        this.outline = PlanOutline.of(resolvedPlan)
        this.title = newPlan.title
        this.language = newPlan.language.ifBlank { Languages.DEFAULT_CODE }
        this.stepIndex = if (resolvedPlan.steps.isEmpty()) -1 else 0
        this.status = STATUS_PAUSED
        this.savedAs = savedAs
        this.unsaved = savedAs == null
        firePlan(getState())
        fireStatus(status)
        // Reveal the first step's code location without starting playback.
        if (stepIndex >= 0) fireStep(stepIndex)
        return resolvedPlan
    }

    /**
     * [setPlan], unless [newPlan] is the plan already showing — then nothing happens and the showing one's
     * [ResolvedPlan] comes back for the reply.
     *
     * It exists for the plan an explain run loads from the agent's `Write` before the agent POSTs it
     * (`ClaudeExplainer.EarlyPlan`): the POST that follows carries the same plan, and a reload would put the
     * reader back on step 0 — mid-sentence, if they had already pressed Play — and offer to "undo" a
     * replacement of the walkthrough by itself. Equality is the decoded [Plan]'s, so a byte-identical file and
     * a reformatted one both count.
     */
    @Synchronized
    fun setPlanUnlessShowing(newPlan: Plan): ResolvedPlan {
        val showing = resolved
        if (showing != null && newPlan == plan) return showing
        return setPlan(newPlan)
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
     * false if there is nothing to restore. Comes back at the step the user was on, **paused** (never
     * auto-plays, even if the previous state was playing). Consumes the retained snapshot — undo is one
     * level deep.
     */
    @Synchronized
    fun restorePrevious(): Boolean {
        val prev = previous ?: return false
        previous = null
        title = prev.title
        plan = prev.plan
        // Re-validated rather than cached: the issue list is what a report reads, and a restored plan with
        // no issues would read as a clean one. Validation is pure structure over line numbers, so this is
        // arithmetic, not I/O.
        val revalidated = PlanLoader.resolve(prev.plan)
        resolved = revalidated
        outline = PlanOutline.of(revalidated)
        language = prev.language
        // The walkthrough comes back as the save it was, or as unsaved as it was — Undo restores the whole
        // snapshot, its identity included, so Save on it writes where Save wrote before.
        savedAs = prev.savedAs
        unsaved = prev.unsaved
        stepIndex = prev.stepIndex.coerceIn(-1, revalidated.steps.size - 1)
        status = if (revalidated.steps.isEmpty()) STATUS_IDLE else STATUS_PAUSED
        firePlan(getState())
        fireStatus(status)
        if (stepIndex >= 0) fireStep(stepIndex)
        return true
    }

    /**
     * End the walkthrough. Its save identity goes with it: a stopped walkthrough is no longer the one Save
     * would write, and whatever it held unsaved was asked about before the stop (`Saving.md` D), so there is
     * nothing left for a later prompt to ask about either.
     */
    @Synchronized
    fun stop() {
        status = STATUS_STOPPED
        savedAs = null
        unsaved = false
        fireStatus(status)
    }

    // ── Save identity (`Saving.md` C) ──

    /**
     * **[saved] was written to the file [name]** — the plan is now that save, and clean. Returns false, and
     * changes nothing, when the plan showing is no longer [saved]: a save reads a snapshot and writes it
     * outside this lock, and in between a new plan may have landed. Marking *that* plan clean would claim a
     * file holds what it does not, so the test is identity, not equality.
     */
    @Synchronized
    fun markSaved(name: String, saved: Plan): Boolean {
        if (plan !== saved) return false
        savedAs = name
        unsaved = false
        fireSaveState(getState())
        return true
    }

    /**
     * **The user chose "Don't save"** — drop the unsaved mark and keep everything else, identity included
     * (a walkthrough saved earlier keeps its file whatever was chosen). It exists so the next prompt does not
     * ask again about the same walkthrough: closing a second project, or a second generation.
     */
    @Synchronized
    fun discardUnsaved() {
        if (!unsaved) return
        unsaved = false
        fireSaveState(getState())
    }

    // ── Navigation ──

    @Synchronized
    fun play() {
        if (status == STATUS_PAUSED) {
            status = STATUS_PLAYING
            fireStatus(status)
            // **After the status, and separately from it.** Resuming re-asserts the authored viewport
            // (Phase 3's scroll policy): a reader who scrolled a node away from the step gets the step back
            // when playback starts, without the step index having changed for anyone to react to.
            fireResume()
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
     * **Move one plan step**, forwards or backwards — the unit the reader actually experiences, and since
     * the flattening the only unit there is.
     *
     * The defect this fixed, reported off G2 round 2: *"the 'next' and 'prev' buttons around the big 'Play'
     * button are skipping the whole step, not to the next substep."* `next`/`prev` moved by **segment** — a
     * run of consecutive steps in one frame — so on a ten-step plan with six runs, Next had six stops. Those
     * two methods are now gone rather than merely bypassed: with one position there is no coarser unit for a
     * caller to reach for by mistake.
     *
     * Returns false at either end of the plan, and settling a finished playback to paused is kept
     * deliberately: reaching the last step is the end of the narration whichever button got there.
     */
    @Synchronized
    fun nextStep(): Boolean = stepBy(+1)

    /** Move one plan step back — see [nextStep]. */
    @Synchronized
    fun prevStep(): Boolean = stepBy(-1)

    private fun stepBy(delta: Int): Boolean {
        val total = stepCount()
        if (total == 0) return false
        val target = stepIndex + delta
        if (target < 0 || target >= total) {
            // Nothing left to narrate, so a playing walkthrough becomes paused rather than sitting in
            // `playing` with no audio coming.
            if (delta > 0 && status == STATUS_PLAYING) {
                status = STATUS_PAUSED
                fireStatus(status)
            }
            return false
        }
        moveTo(target)
        return true
    }

    /**
     * **The one place the position changes**, so every mover fires exactly one event.
     *
     * The old code re-fired `onStatus` beside each move, because listeners inferred their position from
     * whatever snapshot a status push happened to carry. [WalkthroughListener.onStep] carries it, so the
     * second event is now only a duplicate render of the same state — and the sidebar pushed twice per step
     * because of it.
     */
    private fun moveTo(index: Int) {
        stepIndex = index
        fireStep(index)
    }

    /**
     * **Jump to a step by index, preserving play/pause status** — what an outline click does (Phase 7.5 #4,
     * revised): clicking a step navigates, it does not force playback. So a paused reader stays paused with
     * only the decorations and the outline moving.
     */
    @Synchronized
    fun gotoStep(index: Int): Boolean {
        if (index < 0 || index >= stepCount()) return false
        moveTo(index)
        return true
    }

    /** Sidebar "restart": go to the first step AND start playback (upstream `goto`). */
    @Synchronized
    fun restart(): Boolean {
        if (stepCount() == 0) return false
        status = STATUS_PLAYING
        fireStatus(status)
        moveTo(0)
        fireResume()
        return true
    }

    /**
     * Agent `goto`: navigate to a step WITHOUT changing playback status (no auto-play). Fires the heavier
     * `onPlan` fan-out, which hard-resets narration — an agent moving the reader is a bigger event than a
     * click.
     */
    @Synchronized
    fun navigateToStep(index: Int): Boolean {
        if (index < 0 || index >= stepCount()) return false
        stepIndex = index
        firePlan(getState())
        fireStep(index)
        return true
    }

    /**
     * The first step whose primary frame is [frameId], or null — what the agent's `goto {frameId}` resolves
     * through.
     *
     * It used to go via the projection's run list, because a sidebar click knew a *segment* id and the two
     * coincided only for a plan that never revisits a frame. With one position there is no id to translate:
     * this is a search over the plan's own steps, and a revisited frame honestly answers with its first.
     */
    @Synchronized
    fun firstStepOfFrame(frameId: Int): Int? =
        resolved?.steps?.indexOfFirst { it.primaryFrameId == frameId }?.takeIf { it >= 0 }

    // ── Plan mutations: retired in Phase 2 (decision 10) ──
    //
    // `insertAfter` / `replaceSegment` / `removeSegments` used to do segment-list surgery here. They edited
    // a list that is no longer the source of truth — mutating it would leave [plan], the saved file and the
    // tree surface disagreeing with what is on screen — and nothing reached them. A live plan is changed by
    // POSTing a new one, which the agent can do because it is holding the whole plan already.

    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_PAUSED = "paused"
        const val STATUS_PLAYING = "playing"
        const val STATUS_STOPPED = "stopped"
    }
}
