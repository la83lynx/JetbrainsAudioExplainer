import com.audioexplainer.Frame;
import com.audioexplainer.LineRange;
import com.audioexplainer.Plan;
import com.audioexplainer.PlanIssue;
import com.audioexplainer.PlanLoader;
import com.audioexplainer.ResolvedPlan;
import com.audioexplainer.ResolvedStep;
import com.audioexplainer.Step;
import com.audioexplainer.Walkthrough;
import com.audioexplainer.WalkthroughListener;
import com.audioexplainer.WalkthroughSnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * **Streaming the plan** (`Stream.md`): a walkthrough that starts narrating on a partial plan and grows
 * while the reader listens.
 *
 * It drives the REAL {@link Walkthrough} and the REAL {@link PlanLoader}. Both are pure — the walkthrough
 * imports one `java.util.concurrent` class and nothing from the platform, and the loader is arithmetic over
 * line numbers — so the whole state machine is reachable in milliseconds with no IDE, no editor and no
 * server. What is NOT reachable here is the narration loop, which needs a `Project` and a TTS service; what
 * this asserts is every decision that loop consults, which is where the streaming behaviour actually lives.
 *
 * <h2>The assertion that matters</h2>
 *
 * **Prefix stability.** Appending steps must not move a step that has already been narrated. That is a
 * property of {@link PlanLoader}, not a promise: `resolveSteps` is a per-step function of `(step, frames)`
 * with no cross-step state, and `frames` cannot change under an append. If that ever stops being true, a
 * reader listening to step 4 would find themselves on someone else's step 4, and nothing else in the system
 * would notice. So it is measured directly, over a growing list, with a **step that drops** in the middle —
 * because a dropped step is exactly where an index would shift if it could.
 *
 * Every "X does not happen" assertion sits beside a case where X does happen: the tree listener must not be
 * rebuilt by an append, so the control is `set_plan`, which must still rebuild it.
 */
public final class StreamProbe {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (ok) pass++; else fail++;
    }

    // ── Plan building ────────────────────────────────────────────────────────────

    /** One frame keeping lines 10-40 of a file, which every step below lights inside. */
    static Frame frame(int id) {
        List<LineRange> keep = Arrays.asList(
                new LineRange(10, 10, "context"),
                new LineRange(20, 40, "focus"));
        return new Frame(id, "/src/A.cs", "A.M" + id, "explains frame " + id,
                /*scene*/ 0, /*parent*/ null, /*callLine*/ 0, /*callToken*/ null, /*callOccurrence*/ 1,
                keep, Collections.emptyList());
    }

    static Step step(int frameId, int start, int end, String text) {
        return new Step(frameId, Collections.singletonList(new LineRange(start, end, "focus")),
                Collections.emptyList(), text, null, null, null, null, 1, Collections.emptyList());
    }

    /** A step naming a frame the plan does not render — the loader drops it, and the index after it must not move. */
    static Step droppedStep(String text) {
        return step(999, 20, 21, text);
    }

    static Plan plan(List<Step> steps, boolean streaming) {
        return new Plan("T", "en", Collections.singletonList(frame(0)), steps, streaming);
    }

    /** The ttsTexts of a resolved plan, in order — the identity a reader would notice moving. */
    static List<String> texts(ResolvedPlan p) {
        List<String> out = new ArrayList<>();
        for (ResolvedStep s : p.getSteps()) out.add(s.getStep().getTtsText());
        return out;
    }

    // ── A listener that counts which callback it got ─────────────────────────────

    static final class Counting implements WalkthroughListener {
        int plans = 0, extended = 0, steps = 0, statuses = 0;
        String lastStatus = "";
        int lastStep = -99;
        boolean lastStreaming = false;

        @Override public void onPlan(WalkthroughSnapshot s) { plans++; }
        @Override public void onPlanExtended(WalkthroughSnapshot s) { extended++; lastStreaming = s.getStreaming(); }
        @Override public void onStep(int i) { steps++; lastStep = i; }
        @Override public void onStatus(String s) { statuses++; lastStatus = s; }
    }

    public static void main(String[] args) {
        prefixStability();
        nonStreamingUnchanged();
        theOpenEnd();
        closing();
        events();
        issues();

        System.out.println();
        System.out.println(pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    // ── 1. Prefix stability — the load-bearing one ───────────────────────────────

    static void prefixStability() {
        System.out.println("\n-- prefix stability: an append cannot move a step already narrated --");

        // Nine steps, with a DROPPED one in the middle. The drop is the point: resolveSteps skips it, so
        // every step after it already sits at a lower resolved index than its authored one. If resolution
        // carried any cross-step state, that offset is where it would show up as the list grows.
        List<Step> all = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            all.add(i == 4 ? droppedStep("dropped-" + i) : step(0, 20 + i, 21 + i, "step-" + i));
        }

        ResolvedPlan full = PlanLoader.INSTANCE.resolve(plan(all, false));
        check("the dropped step is really dropped (8 of 9 resolve)", full.getSteps().size() == 8);
        check("  control: a list with no dropped step resolves all of them",
                PlanLoader.INSTANCE.resolve(plan(all.subList(0, 4), false)).getSteps().size() == 4);

        boolean everyPrefixStable = true;
        String firstDivergence = null;
        for (int n = 1; n <= all.size(); n++) {
            List<String> shorter = texts(PlanLoader.INSTANCE.resolve(plan(all.subList(0, n), false)));
            List<String> longerPrefix = texts(full).subList(0, shorter.size());
            if (!shorter.equals(longerPrefix)) {
                everyPrefixStable = false;
                if (firstDivergence == null) firstDivergence = "n=" + n + ": " + shorter + " vs " + longerPrefix;
            }
        }
        check("resolving steps[0..n] is a prefix of resolving steps[0..9], for every n"
                + (firstDivergence == null ? "" : " — " + firstDivergence), everyPrefixStable);

        // And through the live walkthrough, one batch at a time, which is how it actually happens.
        Walkthrough w = new Walkthrough();
        w.setPlan(plan(all.subList(0, 2), true));
        List<String> seen = texts(w.getResolvedPlan());
        boolean growsOnly = true;
        for (int n = 2; n < all.size(); n++) {
            w.appendSteps(Collections.singletonList(all.get(n)), false);
            List<String> now = texts(w.getResolvedPlan());
            if (!now.subList(0, seen.size()).equals(seen)) growsOnly = false;
            seen = now;
        }
        check("appending one step at a time never rewrites an earlier one", growsOnly);
        check("  and the whole thing equals one-shot resolution of the same steps", seen.equals(texts(full)));
    }

    // ── 2. A plan that never streams behaves exactly as before ───────────────────

    static void nonStreamingUnchanged() {
        System.out.println("\n-- a non-streaming plan is untouched --");

        Walkthrough w = new Walkthrough();
        Counting c = new Counting();
        w.addListener(c);
        w.setPlan(plan(Arrays.asList(step(0, 20, 21, "a"), step(0, 22, 23, "b")), false));

        check("streaming defaults to false", !w.isOpen());
        check("  and the snapshot says so", !w.getState().getStreaming());
        check("it is never awaiting steps", !w.awaitingSteps());

        w.play();
        check("playing", "playing".equals(w.getState().getStatus()));
        check("nextStep reaches the last step", w.nextStep());
        check("  and running off the end returns false", !w.nextStep());
        check("  and settles a playing walkthrough to paused, as it always has",
                "paused".equals(w.getState().getStatus()));
        check("closePlan on a plan that never streamed does nothing", !w.closePlan());
    }

    // ── 3. The open end ──────────────────────────────────────────────────────────

    static void theOpenEnd() {
        System.out.println("\n-- the open end: out of steps is not the end of the plan --");

        Walkthrough w = new Walkthrough();
        w.setPlan(plan(Arrays.asList(step(0, 20, 21, "a"), step(0, 22, 23, "b")), true));
        check("a streaming plan is open", w.isOpen());
        check("  and the snapshot carries it", w.getState().getStreaming());
        check("  but it is NOT awaiting steps while there are steps ahead", !w.awaitingSteps());

        w.play();
        w.nextStep();
        check("on the last known step, it IS awaiting steps", w.awaitingSteps());
        check("  and running off the end still returns false", !w.nextStep());
        check("  but does NOT settle to paused — the rest is being written",
                "playing".equals(w.getState().getStatus()));

        w.appendSteps(Collections.singletonList(step(0, 24, 25, "c")), false);
        check("an append makes it not-awaiting again", !w.awaitingSteps());
        check("  and the step is there to move to", w.nextStep());
        check("  at the index right after the last one narrated", w.getState().getStepIndex() == 2);
        check("  carrying its own text", "c".equals(w.getResolvedPlan().getSteps().get(2).getStep().getTtsText()));

        // The control for "awaiting": a CLOSED plan at its last step is not awaiting anything.
        w.closePlan();
        check("control: at the last step of a CLOSED plan it is not awaiting", !w.awaitingSteps());
    }

    // ── 4. Closing ───────────────────────────────────────────────────────────────

    static void closing() {
        System.out.println("\n-- closing: `final`, and the process-exit backstop --");

        // (a) the agent's own `final`
        Walkthrough a = new Walkthrough();
        a.setPlan(plan(Collections.singletonList(step(0, 20, 21, "a")), true));
        a.play();
        a.nextStep(); // already at the last one; parks
        check("open + parked is playing", "playing".equals(a.getState().getStatus()));
        a.appendSteps(Collections.<Step>emptyList(), true);
        check("`final` with an EMPTY batch closes the plan", !a.isOpen());
        check("  and settles the parked playback to paused", "paused".equals(a.getState().getStatus()));

        // (b) the backstop: closePlan() alone, which is what ClaudeExplainer calls on process exit
        Walkthrough b = new Walkthrough();
        b.setPlan(plan(Collections.singletonList(step(0, 20, 21, "a")), true));
        b.play();
        b.nextStep();
        check("closePlan() reports that it was the one to close it", b.closePlan());
        check("  reaching the same state as `final` did", !b.isOpen()
                && "paused".equals(b.getState().getStatus()) && !b.awaitingSteps());
        check("  and is idempotent", !b.closePlan());

        // (c) closing does NOT settle a walkthrough that has somewhere left to go: the reader may be on
        // step 1 of 5 when the agent finishes writing, and ending their playback would be absurd.
        Walkthrough d = new Walkthrough();
        d.setPlan(plan(Arrays.asList(step(0, 20, 21, "a"), step(0, 22, 23, "b")), true));
        d.play();
        d.closePlan();
        check("closing mid-walkthrough leaves playback playing", "playing".equals(d.getState().getStatus()));

        // (d) a plan that opens with NO steps: legal while streaming, and the first append has to land it.
        Walkthrough e = new Walkthrough();
        e.setPlan(plan(Collections.<Step>emptyList(), true));
        check("a streaming plan may open with no steps at all", e.getState().getStepIndex() == -1);
        e.appendSteps(Collections.singletonList(step(0, 20, 21, "a")), false);
        check("  and the first append lands on step 0", e.getState().getStepIndex() == 0);
        check("  paused, exactly as setPlan would have landed it", "paused".equals(e.getState().getStatus()));
    }

    // ── 5. Which callback fires ──────────────────────────────────────────────────

    static void events() {
        System.out.println("\n-- events: an append must not look like a new plan --");

        Walkthrough w = new Walkthrough();
        Counting c = new Counting();
        w.addListener(c);

        w.setPlan(plan(Arrays.asList(step(0, 20, 21, "a"), step(0, 22, 23, "b")), true));
        int plansAfterSet = c.plans;
        check("control: set_plan DOES fire onPlan (the tree is built here)", plansAfterSet == 1);
        check("  and not onPlanExtended", c.extended == 0);

        int stepsAfterSet = c.steps, statusesAfterSet = c.statuses;

        w.appendSteps(Collections.singletonList(step(0, 24, 25, "c")), false);
        check("an append fires onPlanExtended", c.extended == 1);
        check("  and NEVER onPlan — that one closes and reopens the tree tab", c.plans == plansAfterSet);
        check("  the snapshot it carries still says streaming", c.lastStreaming);
        // A reader listening to step 0 must not be moved by steps arriving for later. Asserting the event as
        // well as the index matters: an `onStep` with the same index still re-renders the tree node and
        // re-asserts the viewport, which would yank the scroll mid-sentence.
        check("  and fires no onStep at all — the reader is not moved", c.steps == stepsAfterSet);
        check("  and no onStatus — appending is not a playback event", c.statuses == statusesAfterSet);

        w.appendSteps(Collections.<Step>emptyList(), true);
        check("closing fires onPlanExtended too", c.extended == 2);
        check("  with streaming now false, which is how a listener learns it is over", !c.lastStreaming);
        check("  and still never onPlan", c.plans == plansAfterSet);

        // The position must not be dragged around by an append: the reader is listening to step 0.
        check("no append moved the position", w.getState().getStepIndex() == 0);

        // The control for "closing fires no status": closing a plan the reader has run OUT of does, because
        // that is the parked-to-paused settle. Same call, opposite answer, decided by where the reader is.
        Walkthrough parked = new Walkthrough();
        Counting pc = new Counting();
        parked.addListener(pc);
        parked.setPlan(plan(Collections.singletonList(step(0, 20, 21, "a")), true));
        parked.play();
        int before = pc.statuses;
        parked.closePlan();
        check("control: closing a walkthrough parked at its end DOES fire onStatus", pc.statuses > before);
        check("  and the status it reports is paused", "paused".equals(pc.lastStatus));

        // An append to nothing is a no-op that reports it, rather than half-building a plan.
        Walkthrough empty = new Walkthrough();
        check("appendSteps with no plan loaded returns null",
                empty.appendSteps(Collections.singletonList(step(0, 20, 21, "x")), false) == null);
    }

    // ── 6. Issues on an append ───────────────────────────────────────────────────

    static void issues() {
        System.out.println("\n-- issues: an append reports what IT broke --");

        // A plan whose first batch already has one bad step, then a second bad step appended.
        List<Step> first = Arrays.asList(step(0, 20, 21, "a"), droppedStep("bad-1"));
        Walkthrough w = new Walkthrough();
        w.setPlan(plan(first, true));
        List<PlanIssue> before = w.getResolvedPlan().getIssues();
        check("the first batch reports its own bad step", !before.isEmpty());

        w.appendSteps(Collections.singletonList(droppedStep("bad-2")), false);
        List<PlanIssue> after = w.getResolvedPlan().getIssues();
        check("the re-resolution still reports both", after.size() > before.size());

        // This is the server's diff, reproduced: what is new is the difference, matched on rendered text.
        List<String> beforeText = new ArrayList<>();
        for (PlanIssue i : before) beforeText.add(i.toString());
        List<String> newOnes = new ArrayList<>();
        for (PlanIssue i : after) {
            if (!beforeText.remove(i.toString())) newOnes.add(i.toString());
        }
        // One bad step raises TWO issues (`step.unknown-frame`, then `step.dropped`), so "new" is two, not
        // one — and the number is asserted rather than the shape, because a diff that let a repeat through
        // would also produce "some new issues".
        check("exactly the appended step's 2 issues are NEW", newOnes.size() == 2);
        boolean allAboutTheNewStep = true;
        for (String n : newOnes) if (!n.contains("step 3")) allAboutTheNewStep = false;
        check("  every one of them is about step 3, not the first batch's step 2", allAboutTheNewStep);
        check("  and every issue the first batch reported was matched away, none left over",
                beforeText.isEmpty());

        // Control: a clean append adds no issues at all, so "new issues" cannot be an artefact of counting.
        Walkthrough clean = new Walkthrough();
        clean.setPlan(plan(Collections.singletonList(step(0, 20, 21, "a")), true));
        int cleanBefore = clean.getResolvedPlan().getIssues().size();
        clean.appendSteps(Collections.singletonList(step(0, 22, 23, "b")), false);
        check("control: a clean append adds no issues",
                clean.getResolvedPlan().getIssues().size() == cleanBefore);
    }
}
