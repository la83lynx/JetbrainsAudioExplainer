package com.audioexplainer

/**
 * **The sidebar's outline, built from the plan's own grouping** — a pure read-only *view* of the plan for
 * drawing rows, holding no position at all. Position is a single **plan step index**, owned by [Walkthrough];
 * nothing maps back through this object.
 *
 * **Three levels, and the middle one is the only one the outline invents.** A scene row per *mount*, a group
 * per run of consecutive steps sharing a primary frame, a row per step. The scene is the plan's own grouping
 * and the step is the plan's own timeline; the run in between is the one thing a reader wants that the schema
 * does not spell out.
 *
 * Two rules, both of which the schema exists to express:
 *
 * - **It must not merge a revisit.** A frame two non-adjacent steps return to produces **two** groups, and a
 *   scene the narration leaves and comes back to produces **two scene rows**. Merging either would show the
 *   reader a walk the plan did not author — and a second scene row is not noise, it is the honest picture of
 *   a tree that gets rebuilt twice ([ResolvedPlan.sceneTransitions] counts the same crossings).
 * - **The step order is the plan's.** The walk below is over `steps` in index order and nothing sorts it.
 *
 * **What it deliberately does not carry: line numbers and file names.** The outline answers *which step am I
 * on*, and nothing else; the location link under Now Playing is where a file and a line belong. That is also
 * why [OutlineStep] is an index and a label and has no third field.
 */
object PlanOutline {

    /**
     * Build the whole outline for [resolved]. Pure arithmetic over the resolved plan, so `PlanProbe` can ask
     * it anything without a project.
     */
    fun of(resolved: ResolvedPlan): Outline {
        val scenes = mutableListOf<OutlineScene>()
        var groups = mutableListOf<OutlineGroup>()
        var rows = mutableListOf<OutlineStep>()

        var sceneId = Int.MIN_VALUE
        var frameId = Int.MIN_VALUE
        // Scene **numbers** are assigned on first appearance and reused on a revisit, so "Scene 2" names the
        // same set of nodes wherever it turns up. The row order is still the walk's.
        val sceneNumbers = LinkedHashMap<Int, Int>()

        fun flushGroup() {
            if (rows.isEmpty()) return
            val frame = resolved.frame(frameId)
            groups += OutlineGroup(
                frame = frameId,
                title = groupTitle(frame),
                steps = rows.toList(),
            )
            rows = mutableListOf()
        }

        fun flushScene() {
            flushGroup()
            if (groups.isEmpty()) return
            scenes += OutlineScene(
                id = sceneId,
                label = "Scene ${sceneNumbers[sceneId] ?: (sceneNumbers.size + 1)}",
                groups = groups.toList(),
            )
            groups = mutableListOf()
        }

        for ((i, step) in resolved.steps.withIndex()) {
            val scene = resolved.sceneOfStep(i) ?: continue
            val frame = step.primaryFrameId
            if (scene != sceneId) {
                flushScene()
                sceneId = scene
                sceneNumbers.getOrPut(scene) { sceneNumbers.size + 1 }
                frameId = Int.MIN_VALUE
            }
            if (frame != frameId) {
                flushGroup()
                frameId = frame
            }
            // Numbered **within its group**, which is what the row says when a step has no label of its own.
            rows += OutlineStep(index = i, label = stepLabel(step, rows.size + 1))
        }
        flushScene()

        return Outline(
            scenes = scenes,
            // The scene row is drawn only when there is more than one of them: on a single-scene plan a header
            // saying "Scene 1" over the whole outline is a level of indent that separates nothing.
            scened = scenes.size > 1,
            steps = resolved.steps.size,
        )
    }

    /**
     * What the sidebar shows for the step now playing: the frame's identity and prose, the step's own
     * narration, and the one place a file and a line survive — the location link.
     *
     * Derived per call rather than stored. It is four field reads off the resolved plan, and a cached copy
     * is one more thing that can disagree with [Walkthrough.stepIndex].
     */
    fun stepView(resolved: ResolvedPlan, index: Int): StepView? {
        val step = resolved.steps.getOrNull(index) ?: return null
        val frame = resolved.frame(step.primaryFrameId) ?: return null
        val ranges = step.rangesIn(frame.id)
        // The enclosing span of the step's ranges in its primary frame — all the location link needs.
        val lo = ranges.minOfOrNull { it.lines().first } ?: 0
        val hi = ranges.maxOfOrNull { it.lines().last } ?: 0
        return StepView(
            index = index,
            title = groupTitle(frame),
            explanation = frame.frame.explanation,
            ttsText = step.step.ttsText,
            label = stepLabel(step, index + 1),
            file = frame.file,
            start = lo,
            end = hi,
            beats = step.step.beats,
        )
    }

    /**
     * A group's heading: the frame's own title.
     *
     * The fallback is the file's **stem** rather than its name, because a blank title is a plan defect and
     * `MidInvoker` reads as the thing being explained where `Invokers.cs` reads as a path, which the outline
     * deliberately does not show.
     */
    private fun groupTitle(frame: ResolvedFrame?): String {
        if (frame == null) return "?"
        val title = frame.frame.title.trim()
        if (title.isNotEmpty()) return title
        return frame.file.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
            .ifBlank { "Frame ${frame.id}" }
    }

    /** A step row's text: the plan's own 2–5 word label, else its position. */
    private fun stepLabel(step: ResolvedStep, ordinal: Int): String =
        step.step.explanation?.trim()?.takeIf { it.isNotEmpty() } ?: "Step $ordinal"
}
