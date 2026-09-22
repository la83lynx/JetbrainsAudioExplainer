package com.audioexplainer

/**
 * What the "Explain This (Audio)" invocation targets:
 * - [CURSOR] — the editor caret / selection (the original headline flow).
 * - [FILE] — one whole file, chosen in the Solution Explorer.
 * - [DIRECTORY] — a folder / project / solution node (or a multi-selection), chosen in the Solution
 *   Explorer; Claude gives a higher-level tour of the subtree rather than a single-symbol walkthrough.
 */
enum class ExplainScope { CURSOR, FILE, DIRECTORY }

/**
 * The context an "Explain This (Audio)" request hands to Claude (Phase 5).
 *
 * Matches the plan's `{file, caretLine, selectionText}` contract, plus a few fields the Phase 6
 * subprocess needs (the project root as `claude`'s cwd, the absolute path as a fallback). The C#
 * symbol is intentionally *not* resolved here — expanding {caretLine, selection} to the enclosing
 * method/class is Claude's job (see the plan's decision 2).
 *
 * [file] is project-relative when the file lives under the project root (so it resolves against
 * Claude's cwd = [projectBasePath] in Phase 6), otherwise absolute. All line numbers are 1-based,
 * matching the plan/segment schema. [caretLine]/[selectionText] only carry meaning for [ExplainScope.CURSOR];
 * for the Solution-Explorer scopes the target is the whole [file] (plus [extraPaths] for a multi-selection).
 */
data class ExplainRequest(
    val projectBasePath: String?,
    val absolutePath: String,
    val file: String,
    val caretLine: Int = 1,
    val selectionText: String? = null,
    val selectionStartLine: Int? = null,
    val selectionEndLine: Int? = null,
    val scope: ExplainScope = ExplainScope.CURSOR,
    /** Additional selected files/folders (project-relative when possible) for a multi-selection; the
     *  primary target stays [file]. Empty for a single-target or cursor request. */
    val extraPaths: List<String> = emptyList(),
    /**
     * Per-run `claude --effort` override from the "Explain This with…" dialog, or null to take the saved
     * [AudioExplainerSettings.State.explainEffort]. **Null for the plain menu entry**, which is what keeps
     * the one-click path on exactly today's arguments.
     */
    val effort: String? = null,
    /** Per-run depth override, or null to take the saved [AudioExplainerSettings.State.explainDepth]. */
    val depth: String? = null,
    /**
     * Free text the user typed for this one run, appended to the prompt verbatim as its own trailing block
     * (see [ExplainPrompt.buildPrompt]). Null/blank for the plain entry.
     *
     * A one-off never becomes the default: only the *history* of these is persisted, never the value.
     */
    val extraInstructions: String? = null,
) {
    /** Bare file name, for progress/notification text. */
    val displayName: String
        get() = file.substringAfterLast('/')

    /** How the target is described to the user and (in Phase 6) to Claude. */
    val target: String
        get() = when (scope) {
            ExplainScope.CURSOR ->
                if (selectionText != null && selectionStartLine != null && selectionEndLine != null) {
                    if (selectionStartLine == selectionEndLine) "selection on line $selectionStartLine"
                    else "selection (lines $selectionStartLine–$selectionEndLine)"
                } else {
                    "symbol at line $caretLine"
                }
            ExplainScope.FILE -> "file $displayName"
            ExplainScope.DIRECTORY ->
                if (extraPaths.isEmpty()) "directory $displayName"
                else "${extraPaths.size + 1} selected items"
        }
}
