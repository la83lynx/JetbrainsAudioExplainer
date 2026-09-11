package com.audioexplainer.spike

import com.audioexplainer.ExplainerServerService
import com.audioexplainer.IssueLevel
import com.audioexplainer.Plan
import com.audioexplainer.PlanOutline
import com.audioexplainer.ResolvedPlan
import com.audioexplainer.Walkthrough
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * **The G2 round of `TreeOfCode.rules.md`, as menu entries** — `Tools ▸ Audio Explainer`.
 *
 * The round's script asked the reader to POST the two JSON files in `fixtures/plans` with `explainer.ps1`, and
 * to hand-edit a field for its failure-path step. Both are avoidable: the plans are in the repo, the mutation
 * is one integer,
 * and a round that begins with a shell incantation is a round that can fail for reasons that say nothing about
 * the code. So each of those steps is an action here, and the reader's whole job is looking at the screen.
 *
 * **What this deliberately does not shortcut.** The plan is read from the same JSON file, deserialised by the
 * same `Plan` serializer and handed to the same [Walkthrough.setPlan] **on a pooled thread**, because that is
 * the thread the HTTP responder calls it from — so the listener fan-out, the file resolution and the EDT
 * marshalling are all exercised exactly as a POST exercises them. The only thing skipped is
 * `validateAgentMessage`'s envelope check, which is about the wire and not about the surfaces under judgement.
 * The balloon reports what the POST reply would have carried: `frames`, `steps`, and the issue list.
 *
 * **Throwaway**, like the rest of this package, and removed with it — the shipping path for a plan is an agent
 * POSTing one. Its own step navigation ([StepPlanAction]) exists because pressing Play needs a TTS server
 * running, and a round about what is *drawn* should not require narration to be installed to advance.
 */
internal object FixturePlans {

    private val log = Logger.getInstance(FixturePlans::class.java)

    /**
     * `ignoreUnknownKeys` is required, not defensive: the fixture files are whole `set_plan` **messages**, so
     * each carries a `"type"` key that `Plan` has no field for. A strict instance rejects both plans outright.
     */
    val json = Json { ignoreUnknownKeys = true }

    fun walkthrough(): Walkthrough = service<ExplainerServerService>().server.walkthrough

    /**
     * Locate `fixtures/plans/`, walking up from the open project.
     *
     * `-PspikeFixture` opens `fixtures/TreeSpike/TreeSpike.sln`, so the plans live two directories *above*
     * the project root; with the plugin project open they live directly under it. Walking up covers both
     * without either being special-cased, and returns null rather than guessing when neither is what is open.
     */
    fun plansDir(project: Project): Path? {
        var dir: Path? = project.basePath?.let { Paths.get(it) }
        var hops = 0
        while (dir != null && hops++ < 5) {
            val candidate = dir.resolve("fixtures").resolve("plans")
            if (Files.isDirectory(candidate)) return candidate
            dir = dir.parent
        }
        return null
    }

    fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Audio Explainer")
            .createNotification(title, content, type)
            .notify(project)
    }

    /**
     * The balloon the round reads instead of a POST reply: the same counts, the same issue list.
     *
     * `frames n/m` is **admitted over authored** — the pair matters, because a dropped frame is the whole
     * point of the loader and a plan that renders three of five frames is not a plan that rendered.
     */
    fun report(project: Project, source: String, plan: Plan, resolved: ResolvedPlan) {
        val head = "frames <b>${resolved.frames.size}/${plan.frames.size}</b>" +
            " · scenes <b>${resolved.scenes.size}</b> (<b>${resolved.sceneTransitions()}</b> rebuild(s))" +
            " · steps <b>${resolved.steps.size}/${plan.steps.size}</b>" +
            " · outline rows <b>${PlanOutline.of(resolved).scenes.sumOf { sc -> sc.groups.sumOf { it.steps.size } }}</b>"
        val body =
            if (resolved.issues.isEmpty()) {
                "$head<br/><br/>No issues — this is what a POST reply with no <code>issues</code> key means."
            } else {
                "$head<br/><br/>" + resolved.issueReport().replace("\n", "<br/>")
            }
        val type = when {
            resolved.issues(IssueLevel.ERROR).isNotEmpty() -> NotificationType.ERROR
            resolved.issues(IssueLevel.WARN).isNotEmpty() -> NotificationType.WARNING
            else -> NotificationType.INFORMATION
        }
        log.info(
            "loaded $source: " +
                "${resolved.frames.size}/${plan.frames.size} frames in ${resolved.scenes.size} scene(s) " +
                "(${resolved.sceneTransitions()} rebuild(s)), " +
                "${resolved.steps.size}/${plan.steps.size} steps, ${resolved.issues.size} issue(s)",
        )
        notify(project, "Loaded $source — ${plan.title}", body, type)
    }

    /**
     * Step by one **plan step**, through the production path.
     *
     * The projection arithmetic that used to live here is gone: it is [com.audioexplainer.Walkthrough.nextStep]
     * now, which is what the sidebar's buttons and the narration controller's boundary crossings call too. A
     * throwaway round action holding the only correct copy of "what the next step is" is exactly how the
     * sidebar came to be stepping by frame for two rounds — so this reads the real thing and reports it.
     */
    fun step(project: Project, delta: Int) {
        val walkthrough = walkthrough()
        val resolved = walkthrough.getResolvedPlan()
        if (resolved == null || resolved.steps.isEmpty()) {
            notify(project, "No plan loaded", "Load one of the fixture plans first.", NotificationType.INFORMATION)
            return
        }
        val total = walkthrough.stepCount()
        val before = walkthrough.currentStepIndex() ?: 0
        val moved = if (delta < 0) walkthrough.prevStep() else walkthrough.nextStep()
        if (!moved) {
            notify(
                project,
                if (delta < 0) "First step" else "Last step",
                "Step ${before + 1} of $total.",
                NotificationType.INFORMATION,
            )
            return
        }
        val after = walkthrough.currentStepIndex() ?: before
        val scene = resolved.sceneOfStep(after)
        log.info("step ${after + 1}/$total -> scene $scene")
    }
}

/**
 * Load one of the JSON files in `fixtures/plans`, optionally mutating it first (the failure-path step).
 *
 * Everything happens on a pooled thread: reading the file is IO, and `setPlan` is called from the server's
 * worker thread in production, so calling it from the EDT here would exercise a threading path that no POST
 * can produce.
 */
abstract class LoadFixturePlanAction(
    private val fileName: String,
    private val mutate: (Plan) -> Plan = { it },
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread { load(project) }
    }

    private fun load(project: Project) {
        val dir = FixturePlans.plansDir(project)
        if (dir == null) {
            FixturePlans.notify(
                project,
                "Cannot find fixtures/plans",
                "Looked upward from <code>${project.basePath}</code>. Open the plugin project or run " +
                    "<code>gradlew runIde -PspikeFixture</code>.",
                NotificationType.WARNING,
            )
            return
        }
        val file = dir.resolve(fileName)
        if (!Files.isRegularFile(file)) {
            FixturePlans.notify(project, "Plan file missing", "<code>$file</code>", NotificationType.WARNING)
            return
        }
        val plan = try {
            mutate(FixturePlans.json.decodeFromString<Plan>(Files.readString(file)))
        } catch (t: Throwable) {
            // A malformed fixture is a fixture bug, and saying which line failed beats a silent no-op.
            FixturePlans.notify(
                project,
                "Could not read $fileName",
                "${t.javaClass.simpleName}: ${t.message}",
                NotificationType.ERROR,
            )
            return
        }
        FixturePlans.report(project, fileName, plan, FixturePlans.walkthrough().setPlan(plan))
    }
}

/**
 * The round's subject: **wide and deep, with links that cross frames.**
 *
 * `Dispatch` with its three handler siblings, one of which nests the invoker a level deeper — so the tree has
 * a sibling row, a grandchild, and four cross-frame relations: parent → child, sibling → sibling (drawn from
 * both ends), child → grandchild, and one pointing at a file **no node holds**. Plus one in-file arrow as the
 * control, and one sibling with no relations at all as the other control.
 */
class LoadTreeLinksPlanAction : LoadFixturePlanAction("tree-links.json")

/**
 * **The routing fan** — round 6's own fixture, and the one thing `tree-links.json` cannot show.
 *
 * *"Create a test in spike to test 5 connections on the same line."* **Six steps, five links each**: five
 * token links off one row all **diving**, the same five **rising**, five **line-end** links off one row, the
 * two round 7 asked for — *"add 2 test spike steps with 5 outgoing links, 3 same editor and 2 portals, one for
 * token-level and one for line-level links"* — and round 8's staircase.
 *
 * Two of the diving five and two of the rising five land on **one row at the far end**, told apart only by
 * which token each names. That is deliberate and it is also what round 7's `pairKey` collapsed: keyed by frame
 * and line alone, the second of each pair read as a redeclaration of the first, and those two steps drew four
 * links instead of five. The step that regresses it is the step that first exposed it.
 *
 * **Step 6 is the routing staircase**, and the only step where per-lane routing is visible as such: five
 * links off five *different* pairs of rows in `SubInvoker` — 49, 59, 78 and 96 columns wide, plus a portal — so
 * each same-editor lane turns up its own column rather than all four being pushed out to the widest row on the
 * step. Every other step here hangs its links off **one** row, where every lane wants the same clearance and
 * they can only be stacked.
 *
 * **Steps 4 and 5 are the mixed lane layout**, and they are the only thing here that exercises the ordering:
 * the three same-editor links take the inner lanes (12 px apart, because a vertical is one stroke wide) and the
 * two portals the outer ones (a whole portal apart, because nothing may be routed through a portal). Their far
 * halves land in the `AuditHandler` node, so the alignment is judged across two editors at the same time.
 *
 * **Three root nodes, and no nesting anywhere in it.** A nested child is a block inlay, and a link whose leg
 * spans it has to be routed clear of it — which is correct and is exactly what would make a routing fixture
 * unreadable. `tree-links.json` is where nesting is judged; this is where routing is.
 *
 * Each step's active range covers **its own links' whole span**, for the same reason: the step's callout is
 * docked above the first active line, so a link reaching above it would be routed round the box instead of
 * into its lane. That is a real rule of the drawing, not a fixture trick — and stating it here is cheaper than
 * rediscovering it from a picture where all five lanes collapsed onto the right margin.
 */
class LoadLinkFanPlanAction : LoadFixturePlanAction("link-fan.json")

/**
 * **The failure path**, which is the round's most important step and the one a reader would otherwise have to
 * produce by hand-editing JSON.
 *
 * Frame 2's `callLine` becomes 140: a line its parent does not keep. The child cannot be embedded at a line
 * inside a collapsed fold, so `PlanLoader` re-roots it and says `frame.call-line-not-kept`. Frame 2 was chosen
 * over a leaf on purpose — it **has a child of its own**, so what is judged is that the whole subtree follows
 * its re-rooted parent to the top level rather than any part of it vanishing.
 */
class LoadBrokenTreeLinksPlanAction : LoadFixturePlanAction(
    "tree-links.json",
    { plan ->
        plan.copy(
            title = plan.title + " (deliberately broken call line)",
            frames = plan.frames.map { if (it.id == 2) it.copy(callLine = 140) else it },
        )
    },
)

/**
 * **The scene model's own case**: three scenes over five files, and one of them a single frame.
 *
 * Scene 0 is the dispatcher with one handler nested in it, scene 1 is the pricing handler with the invoker
 * nested in it, and scene 2 is `SubInvoker.Apply` alone — the shape every *"explain this method"* plan now
 * takes. The steps visit 0, 1, 2 and then **return to 0**, so the round sees three rebuilds rather than two
 * and can judge whether a revisited scene coming back at its defaults reads as correct or as a loss.
 *
 * Note what the loader reports about it: `scenes=3 rebuilds=3`, and no issue at all. Every scene is inside the
 * line budget on its own (28 / 26 / 19) where the five frames together are 73 — which is the budget becoming
 * per-scene, doing something rather than being renamed.
 */
class LoadScenesTourPlanAction : LoadFixturePlanAction("scenes-tour.json")

/**
 * **Item 4's gate**: one frame, rendered as a one-node tree.
 *
 * **Round 3 judged this and it won**: *"one-node tree looks as good, we can remove the old editor highlighter
 * in place mode."* It was half a pair while the in-place surface existed; the other half and that surface are
 * both deleted, so what this plan is now is the regression case for the commonest shape there is — one frame,
 * three steps, one in-file arrow.
 */
class LoadSingleFramePlanAction : LoadFixturePlanAction("single-frame.json")

/**
 * **`occurrence`, which nothing has exercised since the in-place surface was deleted.**
 *
 * Every step names a token appearing more than once in its own range, so the *only* thing deciding which
 * glyph is marked is the count. Two pairs make that falsifiable rather than merely plausible: occurrences 1
 * and 2 of `running` on one line, and occurrences 1 and 2 of the dotted `guarded.Amount` on another — if a
 * pair marks the same glyph twice, `occurrence` is being ignored. `Apply` at occurrence 2 spans two lines,
 * which is the case that separates "counted across the range" from "counted per line", and the last step
 * asks for a fifth `running` where four exist so the drop sits beside the hits rather than in its own plan.
 */
class LoadOccurrencePlanAction : LoadFixturePlanAction("plan2-occurrence.json")

/**
 * **The `token anchor dropped` path**, likewise unstaged since Phase 4.
 *
 * Five ways an anchor fails — absent from the file, wrong case, present in the file but outside the step's
 * ranges, blank after trimming, and one that resolves *only* on the substring pass — each next to a control
 * where the same mechanism succeeds. The plan opens and closes on a resolving step for one reason: a run
 * that silently stopped pulsing partway through would otherwise read exactly like a run where every drop
 * behaved. What is judged is that a dropped anchor costs the **pulse** and nothing else — the lines, the
 * callout and the link must all still draw.
 */
class LoadUnresolvablePlanAction : LoadFixturePlanAction("plan3-unresolvable.json")

/**
 * **The scene model's failure path**: a nested child whose parent is in another scene.
 *
 * Frame 3 (`MidInvoker`, scene 1) is moved to scene 2, where its parent's node does not exist. There is no
 * editor to embed into and no call line to sit under, so the loader cuts the link and says
 * `frame.parent-other-scene`. What the round judges is that scene 2 then shows **two** top-level nodes rather
 * than losing one — the same "degrade, never drop" rule the bad-call-line case tests one level down.
 */
class LoadCrossSceneParentPlanAction : LoadFixturePlanAction(
    "scenes-tour.json",
    { plan ->
        plan.copy(
            title = plan.title + " (parent in another scene)",
            frames = plan.frames.map { if (it.id == 3) it.copy(scene = 2) else it },
        )
    },
)

/**
 * **The line budget's own case**, and what it is for has changed twice under it.
 *
 * It was written when [com.audioexplainer.PlanLoader.LINE_BUDGET] was 80, the four fixtures kept 65 / 79 / 28
 * / 19, and `link-fan.json` sat *one line* under the cap on purpose — so the fold had never run in a judged
 * round and this was the only way to make it. Both halves of that are gone: K3 made the fold unconditional
 * ([com.audioexplainer.tree.StepFold]), and the measured viewport moved the cap to **60**, which the two
 * larger fixtures now exceed on their own.
 *
 * It is kept as the **extreme** rather than the only case: entry 3's plan with the **root** frame keeping the
 * rest of its file instead of one method, 88 kept lines against a cap of 60. The root is the one frame that
 * can grow without moving a call line or a relation endpoint, so the four nestings survive intact.
 *
 * Expect `[info] scene.line-budget-exceeded` at load, and on any step touching one handler, the other two
 * nestings — and the grandchild inside one of them — collapsed to their stubs, *unless* one of them holds the
 * far end of a link the step draws. Stepping into a collapsed node re-expands it, innermost-first, which is
 * the invariant that keeps a narrated step visible.
 */
class LoadOverBudgetPlanAction : LoadFixturePlanAction(
    "tree-links.json",
    { plan ->
        plan.copy(
            title = plan.title + " (over the line budget)",
            frames = plan.frames.map { frame ->
                if (frame.id != 0) frame else frame.copy(keep = frame.keep.map {
                    if (it.isFocus) it.copy(end = 82) else it
                })
            },
        )
    },
)

/**
 * **Beats — the one fixture that has to be *played* rather than stepped.**
 *
 * Every other entry in this menu is judged with Next/Previous and no narration server, because what they
 * show is drawn at render time. A beat is not: it fires when the webview's playback clock reaches the
 * sentence holding its phrase, so stepping through this plan shows the code and no pulses at all. That is
 * not a defect to report — it is the feature. Press Play.
 *
 * Seven steps, each posing one thing:
 *
 * 1. Three beats on three sentences, each naming its own line and token — `Validate` on 71,
 *    `ResolveEnabled` on 74, `GuardAgainstNegative` on 81. **Nothing pulses at audio start**: every beat
 *    here carries a `say`, and there is no separate step-level anchor any more to fire early.
 * 2. Three beats on the **same** line and token, three different phrases — one identifier re-pulsed as
 *    the narration comes back to it. The verbose case, and the honest cost of one anchor shape.
 * 3. **The falsifiable one.** `"…went negative, e.g. because a discount overshot…"` — the splitter cuts
 *    after `e.g.`, so what the author would call sentence two is the server's *third* piece. A beat counted
 *    by sentence number lands on the wrong words here; a beat matched by phrase does not.
 * 4. Three beats where the first two fall in **one** spoken sentence. Only the first fires:
 *    `startTokenFlash` self-cancels, so firing both would show a flicker and then the second. Expect
 *    `MidpointRounding` and `guarded` to pulse and `PrecisionScale` never to.
 * 5. **The commonest shape**: one beat, no `say`, so one pulse of `ApplySingle` as the audio starts —
 *    exactly what a bare `token` did before this tier. If this behaves differently from the old fixtures,
 *    the absorption was not additive.
 * 6. The **five-line callout**: a note long enough to be ellipsised at three lines over an example long
 *    enough to wrap to two. 3 + 2 is the tallest box the caps allow without a relation's pointer block,
 *    and nothing had been seen at that height.
 * 7. **The legacy shape, and a regression that used to be silent.** A bare `token` (`guarded`), no beats,
 *    over **two** ranges — 73 and 81 — with the token on the second. The loader reads it as one beat with
 *    `line = 0`, which resolves over the step's whole span; the old code searched the first range only and
 *    drew nothing. This is the `MaxLines` case from §2.7, posed where a round can see it.
 */
class LoadBeatsPlanAction : LoadFixturePlanAction("plan4-beats.json")

/** Step the walkthrough one plan step, in either direction, with no TTS involved. */
abstract class StepPlanAction(private val delta: Int) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread { FixturePlans.step(project, delta) }
    }
}

class NextPlanStepAction : StepPlanAction(1)

class PreviousPlanStepAction : StepPlanAction(-1)

/**
 * Clear the walkthrough — the `stop` message's local twin.
 *
 * Worth a menu entry of its own because "the tree closes when the walkthrough stops" is a claim about
 * disposal, and the round reads the editor count right after it.
 */
class ClearPlanAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().executeOnPooledThread { FixturePlans.walkthrough().stop() }
    }
}

