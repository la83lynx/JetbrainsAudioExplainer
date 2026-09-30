package com.audioexplainer

/**
 * The lines of the file a plan path names, or null when it does not resolve to a readable file.
 *
 * An interface rather than a VFS call so that [PlanSourceCheck] stays what [PlanLoader] is — arithmetic over
 * strings a probe can drive with a map. The server's implementation is the one that touches the disk.
 */
fun interface SourceLines {
    fun lines(path: String): List<String>?
}

/**
 * **The half of validation that needs the code** — [PlanLoader] checks a plan against itself and says so; this
 * checks it against the files it names.
 *
 * It exists because the loader's silence about the code was being filled by the agent: `plan-schema.md` told it
 * to verify its own tokens and lines before POSTing, and it did, with a script it wrote and ran every time —
 * measured at ~16 s and three turns of a run (`Speed.md` #4). Every one of those checks is a string comparison
 * the server can make in microseconds, against the same text the tree will render, and report in the same
 * `issues` array the agent already reads. So the agent stops checking, and the check stops being optional.
 *
 * **What it reports is what a renderer would get wrong, not what it would refuse.** Every renderer here
 * degrades: a call token that is not found boxes the whole line, a relation end without its token loses its
 * tint, a beat without its token does not pulse, a range past the end is clamped. That is why nothing below is
 * an error except an unresolved file, which drops the frame. What the check adds is that each degrade is said
 * out loud — and a token that is not on its line is, most of the time, the symptom of a *line* the author
 * miscounted, which is the defect that draws a link in the wrong place saying something false about the code.
 *
 * Paths are resolved by whoever supplies [SourceLines]; the rule for a relation end that names a file is
 * [sameFilePath], the one the renderers use, so a short sibling path reads the frame's file rather than being
 * resolved against the project root and reported missing.
 */
object PlanSourceCheck {

    /** How much of a line an issue quotes back, so the author sees what is actually there. */
    private const val QUOTE_CHARS = 90

    fun check(plan: ResolvedPlan, source: SourceLines): List<PlanIssue> {
        val issues = mutableListOf<PlanIssue>()
        val cache = HashMap<String, List<String>?>()
        fun linesOf(path: String): List<String>? = cache.getOrPut(path) { source.lines(path) }
        val byId = plan.frames.associateBy { it.id }

        for (f in plan.frames) {
            val lines = linesOf(f.file)
            if (lines == null) {
                issues += PlanIssue(
                    IssueLevel.ERROR, "frame.file-unresolved",
                    "frame ${f.id}: ${f.file} does not resolve to a file in this project; the frame is not " +
                        "rendered. Use the absolute path of a file you read",
                )
                continue
            }
            val past = f.keep.firstOrNull { it.end > lines.size }
            if (past != null) {
                issues += PlanIssue(
                    IssueLevel.WARN, "frame.keep-past-eof",
                    "frame ${f.id} keeps lines ${past.start}-${past.end}, but ${f.file} has ${lines.size} " +
                        "lines; the range is clamped, and the numbers are probably off — recount them",
                )
            }
        }

        // The call site is a line of the *parent's* file. Only for a frame that still nests: one the loader
        // re-rooted has already been reported and draws no box to miss.
        for (f in plan.frames) {
            if (!f.nests()) continue
            val token = f.frame.callToken?.takeIf { it.isNotBlank() } ?: continue
            val parent = byId[f.parentId] ?: continue
            val lines = linesOf(parent.file) ?: continue
            val miss = miss(lines, f.frame.callLine, token, f.frame.callOccurrence) ?: continue
            issues += PlanIssue(
                IssueLevel.WARN, "frame.call-token-not-on-line",
                "frame ${f.id}'s callToken \"$token\" is not on line ${f.frame.callLine} of frame " +
                    "${parent.id}$miss; the whole line is boxed instead. Fix the callLine, or copy the token " +
                    "from it",
            )
        }

        for ((s, step) in plan.steps.withIndex()) {
            val primary = byId[step.primaryFrameId] ?: continue
            val lines = linesOf(primary.file) ?: continue
            for ((b, beat) in step.step.beats.withIndex()) {
                // Line 0 is the legacy shape, resolved over the whole step span rather than one line.
                if (beat.line <= 0) continue
                val token = beat.token?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val miss = miss(lines, beat.line, token, beat.occurrence) ?: continue
                issues += PlanIssue(
                    IssueLevel.WARN, "step.beat-token-not-on-line",
                    "step ${s + 1} beat ${b + 1} pulses \"$token\" on line ${beat.line} of frame " +
                        "${primary.id}, which does not hold it$miss; that pulse never happens",
                )
            }
        }

        for (f in plan.frames) {
            for ((i, r) in f.frame.relations.withIndex()) {
                for ((end, ep) in listOf("from" to r.from, "to" to r.to)) {
                    if (ep.line <= 0) continue
                    val where = "relation ${i + 1} of frame ${f.id}: its `$end`"
                    val named = ep.file?.trim()?.takeIf { it.isNotEmpty() }
                    val path = when {
                        named == null -> f.file
                        sameFilePath(named, f.file) -> f.file
                        else -> plan.frames.firstOrNull { sameFilePath(named, it.file) }?.file ?: named
                    }
                    val lines = linesOf(path)
                    if (lines == null) {
                        // The frame's own file is already reported above, once.
                        if (path != f.file) {
                            issues += PlanIssue(
                                IssueLevel.WARN, "relation.file-unresolved",
                                "$where names $named, which does not resolve to a file in this project; " +
                                    "that end cannot be shown or followed",
                            )
                        }
                        continue
                    }
                    val token = ep.token?.trim()?.takeIf { it.isNotEmpty() }
                    if (token == null) {
                        if (ep.line > lines.size) {
                            issues += PlanIssue(
                                IssueLevel.WARN, "relation.line-past-eof",
                                "$where is on line ${ep.line}, but $path has ${lines.size} lines",
                            )
                        }
                        continue
                    }
                    val miss = miss(lines, ep.line, token, ep.occurrence) ?: continue
                    issues += PlanIssue(
                        IssueLevel.WARN, "relation.token-not-on-line",
                        "$where names \"$token\" on line ${ep.line}$miss. The token is the check on the line " +
                            "number: most likely the line is miscounted, and the link is drawn at the wrong " +
                            "place",
                    )
                }
            }
        }
        return issues
    }

    /**
     * Null when [token]'s [occurrence]-th match is on 1-based [line]; otherwise the clause saying what is
     * there instead — the line's own text, so the author can see the miscount rather than re-read the file.
     */
    private fun miss(lines: List<String>, line: Int, token: String, occurrence: Int): String? {
        if (line > lines.size) return " — the file has only ${lines.size} lines"
        val text = lines[line - 1]
        if (tokenOnLine(text, token, occurrence)) return null
        if (tokenOnLine(text, token, 1)) {
            val times = (1 until occurrence).last { tokenOnLine(text, token, it) }
            return " as occurrence $occurrence — it occurs there only $times time(s)"
        }
        val quote = text.trim().let { if (it.length > QUOTE_CHARS) it.take(QUOTE_CHARS) + "…" else it }
        return " — that line reads `$quote`"
    }
}
