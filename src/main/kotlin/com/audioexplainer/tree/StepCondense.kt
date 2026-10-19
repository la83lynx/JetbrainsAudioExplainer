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

    /**
     * And in the step's own frame, **the members the step is not in** fold: the method the step narrates
     * stays whole, the frame's other methods go. The 2026-10-19 round's shape — 11 of 14 saved plans put one
     * 50-60-line frame per scene and step through it ~6 lines at a time, so at any step most of the frame
     * is other steps' members. A member is the outermost block around a step line that does not hold the
     * whole `keep` ([member]); the class does, so it is never the one kept.
     */
    MEMBER,

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

        /**
         * **The lines of the block 1-based [header] opens**, by indentation like [chain]: the lines below it
         * indented deeper, an Allman `{` at its indent before them, and the closer at its indent after them. A
         * header with no deeper line under it is a block of itself.
         */
        fun block(header: Int, text: (Int) -> String?): IntRange {
            val indent = text(header)?.let(::indentOf) ?: return header..header
            var last = header
            var body = false
            var k = header + 1
            while (true) {
                val s = text(k) ?: break
                val i = indentOf(s)
                if (i == null || (i > indent && skipped(s) && !CLOSER.matches(s.trim()))) {
                    k++
                    continue
                }
                val t = s.trim()
                when {
                    i > indent -> { body = true; last = k }
                    !body && t.startsWith("{") -> last = k
                    body && t.startsWith("}") -> { last = k; break }
                    else -> break
                }
                k++
            }
            return header..last
        }

        /**
         * **The member 1-based [line] is in** — the outermost of its own block and its [chain]'s blocks below
         * the first type or namespace header ([TYPE_HEADER]) and below any block holding every [kept] line;
         * null when [line] is such a header itself. Outermost, so a step on one statement keeps the whole
         * method; below the type, so the class is never the member even when the frame also keeps the
         * interfaces above it — which "below the block holding everything" alone got wrong on hig-standard-1.
         */
        fun member(line: Int, kept: Collection<Int>, text: (Int) -> String?): IntRange? {
            val lo = kept.minOrNull() ?: return null
            val hi = kept.maxOrNull() ?: return null
            var best: IntRange? = null
            for (h in listOf(line) + chain(line, text)) {
                val s = text(h) ?: continue
                // An Allman `{` is not a header: its block misses the signature above it, which is next in the chain.
                if (s.trimStart().startsWith("{")) continue
                if (isTypeHeader(s)) break
                val b = block(h, text)
                if (b.first <= lo && b.last >= hi) break
                best = b
            }
            return best
        }

        /**
         * A class, interface, namespace… declaration: modifiers, the keyword, a name. Read before the first `(`
         * or `=`, so `where T : class` and a variable called `record` are not one. Kotlin's `object` only as a
         * capitalised name or a companion, since C# fields are declared `object _lock`.
         */
        private val TYPE_HEADER = Regex(
            """^\s*(?:[\w@\[\]]+\s+)*?(?:(?:class|struct|interface|record|enum|namespace|trait|impl|module|protocol|extension)\s+[A-Za-z_]|object\s+[A-Z]|companion\s+object\b)""",
        )

        fun isTypeHeader(s: String): Boolean = TYPE_HEADER.containsMatchIn(s.substringBefore('(').substringBefore('='))

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
