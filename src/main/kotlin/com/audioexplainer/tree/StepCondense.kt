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
         * it only grows that placeholder, which costs no row of its own. [exact] lines stay without a margin —
         * a [chain]. 1-based, as contiguous ranges.
         */
        @JvmOverloads
        fun window(
            keep: List<IntRange>,
            anchors: Set<Int>,
            zone: (Int) -> Boolean,
            margin: Int,
            exact: Set<Int> = emptySet(),
        ): List<IntRange> {
            val kept = keep.flatMap { it.toList() }.toSortedSet().toList()
            if (kept.isEmpty()) return emptyList()
            val near = HashSet<Int>(exact)
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

        /**
         * **The lines that say where 1-based [line] sits** — the header of each block around it, innermost
         * first in the walk: the `if (…)` and its `{`, the method's signature and its `{`, the class's — the
         * 2026-10-15 round: a fold that keeps a step but hides the `if` it is inside, or keeps a link's end but
         * not even the method it lands in, leaves the reader to guess where they are.
         *
         * By indentation, not by a parser, so it is one rule for every language a frame can be in (and Rider's
         * frontend has no C# tree to ask). Walking up, the first line indented less than the current one opens
         * the block; when that line is a lone `{` (Allman), the statement it opens — the nearest line above at
         * its indent or less — comes too. Blank lines, comments, directives and lone closers (`}`, `});`) are
         * skipped: a closer at a lesser indent ends a sibling block, not ours. [text] gives a line, null past the
         * document; a blank [line] has no chain.
         */
        fun chain(line: Int, text: (Int) -> String?): Set<Int> {
            val out = LinkedHashSet<Int>()
            var indent = text(line)?.let(::indentOf) ?: return out
            var k = line - 1
            while (indent > 0 && k >= 1) {
                val s = text(k)
                val i = s?.let(::indentOf)
                if (s == null || i == null || i >= indent || skipped(s)) {
                    k--
                    continue
                }
                out += k
                indent = i
                if (s.trimStart().startsWith("{")) {
                    var h = k - 1
                    while (h >= 1) {
                        val t = text(h)
                        val ti = t?.let(::indentOf)
                        if (t != null && ti != null && ti <= i && !skipped(t)) break
                        h--
                    }
                    if (h >= 1) {
                        out += h
                        indent = indentOf(text(h)!!)!!
                        k = h
                    }
                }
                k--
            }
            return out
        }

        /** Columns of leading whitespace, a tab as 4; null for a blank line. */
        private fun indentOf(s: String): Int? {
            var col = 0
            for (c in s) {
                when (c) {
                    ' ' -> col++
                    '\t' -> col += 4
                    else -> return col
                }
            }
            return null
        }

        private val CLOSER = Regex("""^[}\])][}\])\s;,]*$""")

        private fun skipped(s: String): Boolean {
            val t = s.trim()
            return t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("#") ||
                CLOSER.matches(t)
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
