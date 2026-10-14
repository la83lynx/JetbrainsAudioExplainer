package com.audioexplainer.tree

/**
 * **How much of a frame's `keep` a step folds away for itself** — the 2026-10-14 round: *"all generated
 * walkthroughs make much less use of code collapsing, showing quite a lot of unrelevant code"*, with two
 * cases named: a step inside a nested frame playing under the whole of its parent, and a link from the bottom
 * of a frame to its top *"with quite a lot of lines in between"*.
 *
 * Measured on that round's plans, the agent's `keep` is not the problem — 80-95 % of a frame's kept lines are
 * narrated by *some* step. What a reader does not need is relative to the **current** step, which a plan
 * cannot say and the plugin can: `keep` stays the plan's word on what the frame is, and this folds part of it
 * for one step, openable like any other fold, and gives it back on the next.
 *
 * Each value includes the ones before it.
 */
enum class StepCondense {
    /** Every frame shows its whole `keep` at every step — what shipped before the round. */
    OFF,

    /**
     * The frames **above** a nested step show only what places it: their headers, the call line, and what the
     * step or its links touch there. [StepFold] can never fold an ancestor of the frame being narrated, so
     * without this a nested step plays under every line its parents keep.
     */
    ANCESTORS,

    /**
     * And in the step's own frame, **the stretch between the ends of a long link** — [LONG_LINK] or more
     * visible lines apart — folds, except what the step touches. The link then reads as a short arrow
     * instead of a leg across a screenful of code the step is not about.
     */
    LINKS,

    /** And the step's own frame shows only what the step touches: its ranges, headers, call lines, link ends. */
    STEP;

    companion object {
        /** Visible lines between a link's ends from which [LINKS] folds the stretch. */
        const val LONG_LINK = 12

        /** A shorter run stays on screen: a `⋯ 2 lines ⋯` costs a row and saves one. */
        const val MIN_FOLD = 5

        /**
         * The window to show: [keep]'s lines minus the foldable runs — lines in [zone] further than [margin] from
         * every [anchors] line. A run is consecutive *on screen*, so it may span a gap `keep` already elides.
         * It folds when it is at least [MIN_FOLD] rows, or when it touches a fold that is already there: then
         * it only grows that placeholder, which costs no row of its own. 1-based, as contiguous ranges.
         */
        fun window(keep: List<IntRange>, anchors: Set<Int>, zone: (Int) -> Boolean, margin: Int): List<IntRange> {
            val kept = keep.flatMap { it.toList() }.toSortedSet().toList()
            if (kept.isEmpty()) return emptyList()
            val near = HashSet<Int>()
            for (a in anchors) for (l in a - margin..a + margin) near += l
            val folded = HashSet<Int>()
            var i = 0
            while (i < kept.size) {
                if (!zone(kept[i]) || kept[i] in near) { i++; continue }
                var j = i
                while (j + 1 < kept.size && zone(kept[j + 1]) && kept[j + 1] !in near) j++
                val run = kept.subList(i, j + 1)
                // Already-elided lines before, after or inside it: before the first kept line of the file's
                // window there is the elision of everything above it, and after the last, of everything below.
                val bordersFold = (i == 0 && kept[0] > 1) || j == kept.size - 1 ||
                    (i > 0 && kept[i - 1] != kept[i] - 1) || kept[j + 1] != kept[j] + 1 ||
                    run.last() - run.first() + 1 != run.size
                if (run.size >= MIN_FOLD || bordersFold) folded += run
                i = j + 1
            }
            return runs(kept.filter { it !in folded })
        }

        /** Sorted 1-based lines as contiguous ranges. */
        fun runs(lines: Collection<Int>): List<IntRange> {
            val out = mutableListOf<IntRange>()
            for (l in lines.sorted()) {
                val last = out.lastOrNull()
                if (last != null && l <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, l)
                else out += l..l
            }
            return out
        }
    }
}
