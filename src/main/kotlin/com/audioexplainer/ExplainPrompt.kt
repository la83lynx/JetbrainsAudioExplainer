package com.audioexplainer

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.intellij.openapi.util.text.StringUtil
import java.nio.file.Path

/**
 * The argv and the prompt of one headless `claude -p` run — **string building and nothing else**.
 *
 * It lives apart from [ClaudeExplainer] because everything here is a pure function of an
 * [ExplainRequest], the installed skill directory, the walkthrough [Language] and the three
 * generation knobs, which is what makes `probes/EffortProbe.java` able to build every combination in
 * milliseconds and assert on the exact bytes. [ClaudeExplainer] keeps the parts that need an IDE: the
 * settings lookup, the voice discovery warm-up, the process, the notifications.
 *
 * **Standard depth with no extra instructions is byte-identical to the prompt that shipped before
 * depth existed** — `probes/prompt-standard.txt` is that prompt, frozen, and the probe compares
 * against it. An upgrade must not silently change what every user who never opens the new controls
 * gets asked for.
 */
object ExplainPrompt {

    /**
     * Enumerated tool allow-list for the headless run (see [buildArgs]).
     *
     * **`PowerShell` is on it because the docs send the agent there.** On Windows `docs/explain-at-cursor.md`
     * tells it to POST the plan with `powershell -File explainer.ps1`, and the tool it reaches for first is
     * the `PowerShell` one — which this list forbade, so the call was refused and the turn was spent
     * discovering that. It grants nothing new: `Bash` is here and is command-unrestricted, so the same
     * command was always reachable, just not by the name the agent tried first.
     * `ExplainerDiagnostics`' "`PowerShell` tool allowed" row is the guard that keeps the two in step.
     */
    const val ALLOWED_TOOLS = "Read Grep Glob Task Write TodoWrite Bash PowerShell"

    /**
     * The allow-list of a run that sends its plan with the `set_plan` tool ([McpEndpoint]): no `Write`, no shell.
     * The plan was the only thing the run wrote and the POST the only thing it ran, so the tool replaces both —
     * and a run that cannot write or execute is one a hostile comment in the code under explanation cannot
     * talk into doing either.
     */
    const val TOOL_ALLOWED_TOOLS = "Read Grep Glob Task TodoWrite ${McpEndpoint.QUALIFIED_TOOL}"

    private const val MAX_SELECTION_CHARS = 4000

    /**
     * [planDir] is the run's own directory for the JSON it POSTs (`Saving.md` B), granted with `--add-dir`:
     * it is outside the working directory by design. On the dev machine a headless `Write` there succeeded
     * with or without the flag (measured 2026-09-23); it is passed anyway so the grant is explicit rather
     * than resting on whatever permission settings the user has, where a refused `Write` would have no one
     * to ask. **Last**, because `--add-dir` takes a variadic list and would swallow whatever positional came
     * after it. Null adds nothing, so the argv `EffortProbe` measures is unchanged.
     */
    /**
     * [mcpConfig] is the run's `--mcp-config` file ([McpEndpoint.config]): given, the plan comes back through the
     * `set_plan` tool, the allow-list is [TOOL_ALLOWED_TOOLS], and [planDir] is not granted — the agent writes
     * nothing.
     */
    @JvmOverloads
    fun buildArgs(
        prompt: String,
        effort: String,
        model: String,
        planDir: Path? = null,
        mcpConfig: Path? = null,
    ): List<String> = buildList {
        add("-p"); add(prompt)
        add("--output-format"); add("stream-json")
        add("--verbose") // required alongside stream-json in print mode
        // **The run's progress bar reads these** ([ExplainProgress]): the plan `Write` streams in as it is
        // generated instead of arriving whole at the end, and the thinking reports its token count as it goes.
        // Output only — what the model is asked and how it answers are unchanged.
        add("--include-partial-messages")
        // Scoped allow-list: only these tools may run without an (impossible, headless) prompt.
        // Bash is intentionally command-unrestricted so the POST works cross-platform (curl on any
        // OS, powershell on Windows, the .sh helper elsewhere) — tighten ALLOWED_TOOLS to narrow it.
        add("--allowedTools"); add(if (mcpConfig != null) TOOL_ALLOWED_TOOLS else ALLOWED_TOOLS)
        // **No MCP server of the user's loads**, only the run's own — none of theirs is on the allow-list, so each
        // was startup spent on tools the run cannot call. Speed.md measured it at ~2.3s of startup (3.3-4.4s with
        // the dev machine's servers, 1.2-1.4s without). `--mcp-config` takes a list, so a flag must follow it.
        if (mcpConfig != null) { add("--mcp-config"); add(mcpConfig.toString()) }
        add("--strict-mcp-config")
        // **Two `if`s, and the argv is deliberately NOT what shipped.** Passing neither flag meant
        // every run inherited `~/.claude/settings.json`'s `effortLevel` — a value chosen for interactive
        // coding, and the four-minute walkthrough Speed.md measured. Blank still means inherit, for the
        // two people who want it; it is simply no longer the default.
        if (effort.isNotBlank()) { add("--effort"); add(effort.trim()) }
        if (model.isNotBlank()) { add("--model"); add(model.trim()) }
        if (planDir != null && mcpConfig == null) { add("--add-dir"); add(planDir.toString()) }
    }

    /**
     * [planDir] is where the agent writes the JSON it POSTs (`Saving.md` B) — a per-run directory outside the
     * project, because a payload written inside it lands in `.walkthroughs/` often enough to fill the user's
     * saved list with blank rows. Null (no directory could be made) leaves the line out rather than naming a
     * directory that does not exist.
     *
     * [viaTool] asks for the plan through the `set_plan` tool instead ([McpEndpoint]): step 3 names the tool, and
     * the helper scripts and the plan directory go unmentioned. False is the POST prompt, byte for byte — the
     * fallback when the tool does not connect.
     */
    @JvmOverloads
    fun buildPrompt(
        request: ExplainRequest,
        skillDir: Path?,
        lang: Language,
        depth: String,
        extraInstructions: String?,
        planDir: Path?,
        viaTool: Boolean = false,
    ): String {
        val skillPath = skillDir?.toString()?.replace('\\', '/')
        val planPath = planDir?.toString()?.replace('\\', '/')
        // Scope-specific framing (Phase 5): the editor cursor flow vs. a file / folder-or-project chosen in
        // the Solution Explorer. The set_plan schema is identical — only what Claude is asked to cover differs.
        val intro = when (request.scope) {
            ExplainScope.CURSOR ->
                "The user right-clicked \"Explain This (Audio)\" in their editor to get an automatic, " +
                    "narrated walkthrough of the code at their cursor."
            ExplainScope.FILE ->
                "The user right-clicked \"Explain This (Audio)\" on a file in the Solution Explorer to get " +
                    "an automatic, narrated walkthrough of that whole file."
            ExplainScope.DIRECTORY ->
                "The user right-clicked \"Explain This (Audio)\" on a folder / project (or several selected " +
                    "items) in the Solution Explorer to get an automatic, narrated tour of that code."
        }
        val contextLabel = when (request.scope) {
            ExplainScope.CURSOR -> "Cursor context"
            ExplainScope.FILE -> "Target file"
            ExplainScope.DIRECTORY -> "Target folder / project"
        }
        val step1 = when (request.scope) {
            ExplainScope.CURSOR ->
                "1. Read in/around the cursor and expand to the enclosing method/class — YOU do the symbol resolution."
            ExplainScope.FILE ->
                "1. Read the whole target file and pick the parts worth narrating — its key types, methods, and flow."
            ExplainScope.DIRECTORY ->
                "1. Explore the listed folder/project path(s) — read the important files and pick the pieces " +
                    "worth a guided tour. Keep it lean; don't try to cover every file."
        }
        return buildString {
            appendLine(
                "You are running headless inside the \"Audio Explainer\" IDE plugin. $intro",
            )
            appendLine()
            appendLine(
                "This is a NON-INTERACTIVE run: there is no human to answer questions. Do NOT ask anything, " +
                    "do NOT wait — act autonomously and finish in one pass.",
            )
            appendLine()
            if (lang.code != Languages.DEFAULT_CODE) {
                appendLine(
                    "LANGUAGE: Generate the walkthrough in ${lang.displayName} (${lang.autonym}). Write the " +
                        "`title`, every frame `title` and `explanation`, and every step `ttsText`, " +
                        "`explanation` and `note` in ${lang.displayName}. Keep code identifiers, file paths, and API names " +
                        "verbatim (do NOT translate code) — a beat's `token` and a frame's `callToken` especially, since " +
                        "both must match the source text exactly to resolve, and `example`, which is code. The same split " +
                        "applies to a frame's `relations`: their `note` is prose, their `token` and `symbol` are code. " +
                        "You MUST put \"language\":\"${lang.code}\" at the top " +
                        "level of the `set_plan` JSON.",
                )
                appendLine()
            }
            // **Depth (Effort.md).** Standard emits nothing here at all — that is what keeps the prompt
            // of a user who never opens the new control byte-identical to the one that shipped before depth
            // existed. Brief and Deep say what shape to aim for; neither ever contradicts
            // docs/plan-schema.md, which stays the authority on what the fields mean.
            when (depth) {
                AudioExplainerSettings.DEPTH_BRIEF -> {
                    appendLine(
                        "DEPTH: brief. ONE frame, at most 4 steps, and the flat plan shape — no frame " +
                            "`relations`, no nested frames (`parent` / `callLine` / `callToken`), no `scene`s, " +
                            "and no step `note`, `example` or `beats`. This is narrower than what " +
                            "docs/plan-schema.md allows; a depth never widens it.",
                    )
                    appendLine()
                }
                AudioExplainerSettings.DEPTH_DEEP -> {
                    appendLine(
                        "DEPTH: deep. Up to 4 frames, and the optional fields are welcome wherever they teach " +
                            "something — step `note`s, one-line `example`s, `beats`, up to 5 `relations` per " +
                            "frame, nested frames and `scene`s. Still bounded by docs/plan-schema.md; a depth " +
                            "never widens what it allows.",
                    )
                    appendLine()
                }
            }
            appendLine("$contextLabel (JSON):")
            appendLine(buildContextJson(request))
            appendLine()
            if (skillPath != null) {
                appendLine("Follow the headless flow documented here (read it first):")
                appendLine("  $skillPath/docs/explain-at-cursor.md")
                appendLine("The `set_plan` schema is documented ONCE, authoritatively — read it before writing the plan:")
                appendLine("  $skillPath/docs/plan-schema.md")
                if (!viaTool) {
                    appendLine("The helpers that POST the plan are in $skillPath/scripts/ (explainer.ps1 on Windows, explainer.sh elsewhere).")
                }
                appendLine()
            }
            appendLine("What to do:")
            appendLine(step1)
            if (skillPath != null) {
                when (depth) {
                    AudioExplainerSettings.DEPTH_BRIEF -> appendLine(
                        "2. Build a lean `set_plan` — ONE frame and at most 4 steps — with dense, " +
                            "pointer-style steps and good plain-text `ttsText`, following docs/plan-schema.md " +
                            "exactly. A plan is `frames` (each one a file plus the `keep` ranges visible in it — " +
                            "header rows as \"context\", the member itself as \"focus\") plus `steps` (the " +
                            "narration, in order, each naming its frame and the `ranges` it lights). EVERY line a " +
                            "step lights must be inside its frame's `keep`. Keep it flat: do not send `view` at " +
                            "all, and leave every optional field out at this depth — this is the oldest and " +
                            "cheapest plan shape, and the one that renders on the least screen.",
                    )
                    AudioExplainerSettings.DEPTH_DEEP -> appendLine(
                        "2. Build a `set_plan` of up to 4 frames with dense, pointer-style steps and good " +
                            "plain-text `ttsText`, following docs/plan-schema.md exactly. A plan is `frames` (each " +
                            "one a file plus the `keep` ranges visible in it — header rows as \"context\", the " +
                            "member itself as \"focus\") plus `steps` (the narration, in order, each naming its " +
                            "frame and the `ranges` it lights). EVERY line a step lights must be inside its " +
                            "frame's `keep`. Where a one-line aside next to the code would genuinely help (a " +
                            "constraint, a gotcha, the reason behind a magic number), add an optional " +
                            "one-sentence `note` to that step — at this depth they are welcome wherever they " +
                            "teach something. Where a step is really about ONE identifier, add an optional " +
                            "`beats` entry — {\"line\":N,\"token\":\"…\"} with the name copied verbatim from " +
                            "THAT line, so the plugin can pulse it. Where a concrete case would clarify a tricky " +
                            "transformation or a surprising result, add an optional ONE-LINE `example` " +
                            "(\"Parse(\\\"2024-01\\\") → DateOnly(2024, 1, 1)\") — one line only. Where two " +
                            "places are genuinely linked — a value produced on one line and consumed on another, " +
                            "a call whose target is in another file — add an optional frame-level `relations` " +
                            "entry and the plugin will draw the connection; up to 5 per frame, and anchor an " +
                            "endpoint inside the step it belongs to. Declare a link ONCE: a cross-file one is " +
                            "drawn as a matched pair of portals, one in each place, so give its far end both " +
                            "`file` and `line`. Every plan renders as a tree of code — do not send `view` at " +
                            "all. When the explanation IS a caller-callee chain, give each nested frame `parent`, " +
                            "`callLine` and `callToken` (the call line must be a line the parent keeps) — use " +
                            "that shape wherever the code has it. When it walks over several unrelated places in " +
                            "turn, group the frames into `scene`s: frames sharing a `scene` are on screen " +
                            "together and a step in another scene replaces them, so a long walkthrough keeps a " +
                            "handful of files visible rather than all of them. A `parent` must be in the same " +
                            "`scene`.",
                    )
                    // **Standard is the branch that must not move**, so it is the shipped paragraph
                    // verbatim rather than a reassembly of it — `probes/EffortProbe.java` compares what
                    // it produces against `probes/prompt-standard.txt` byte for byte.
                    else -> {
                        appendLine(
                            "2. Build a lean `set_plan` (usually 1-2 frames) with dense, pointer-style steps and good " +
                                "plain-text `ttsText`, following docs/plan-schema.md exactly. A plan is `frames` (each one a " +
                                "file plus the `keep` ranges visible in it — header rows as \"context\", the member itself as " +
                                "\"focus\") plus `steps` (the narration, in order, each naming its frame and the `ranges` it " +
                                "lights). EVERY line a step lights must be inside its frame's `keep`. Where a one-line aside " +
                                "next to the code would genuinely help (a constraint, a gotcha, the reason behind a magic " +
                                "number), add an optional one-sentence `note` to that step — sparingly, not on every one. " +
                                "Where a step is really about ONE identifier, add an optional `beats` entry — " +
                                "{\"line\":N,\"token\":\"…\"} with the name copied verbatim from THAT line, so the plugin " +
                                "can pulse it — again sparingly. Where a concrete " +
                                "case would clarify a tricky transformation or a surprising result, add an optional " +
                                "ONE-LINE `example` (\"Parse(\\\"2024-01\\\") → DateOnly(2024, 1, 1)\") — one line only, and " +
                                "only where it teaches something the code doesn't show. Where two places are genuinely " +
                                "linked — a value produced on one line and consumed on another, a call whose target is in " +
                                "another file — add an optional frame-level `relations` entry and the plugin will draw the " +
                                "connection; 2-3 per frame at most, and anchor an endpoint inside the step it belongs to. " +
                                "Declare a link ONCE: a cross-file one is drawn as a matched pair of portals, one in each " +
                                "place, so give its far end both `file` and `line`. " +
                                "Every plan renders as a tree of code — do not send `view` at all. When the explanation IS " +
                                "a caller-callee chain, give each nested frame `parent`, `callLine` and `callToken` (the " +
                                "call line must be a line the parent keeps). When it walks over several unrelated places " +
                                "in turn, group the frames into `scene`s: frames sharing a `scene` are on screen together " +
                                "and a step in another scene replaces them, so a long walkthrough keeps a handful of files " +
                                "visible rather than all of them. A `parent` must be in the same `scene`.",
                        )
                    }
                }
            } else {
                // No skill dir on disk, so docs/plan-schema.md is unreachable and the agent has nothing to
                // read. This inline copy is the FALLBACK mirror only — keep it in sync with that doc, which
                // stays the source of truth for every other caller.
                // The shape phrase is the ONE part of the mirror depth moves; Standard interpolates to
                // "usually 1-2 frames", which is the wording that shipped.
                val shape = when (depth) {
                    AudioExplainerSettings.DEPTH_BRIEF -> "ONE frame, at most 4 steps"
                    AudioExplainerSettings.DEPTH_DEEP -> "up to 4 frames"
                    else -> "usually 1-2 frames"
                }
                appendLine(
                    "2. Build a lean `set_plan` ($shape) with dense, pointer-style steps and good " +
                        "plain-text `ttsText`. Schema (1-based, inclusive line ranges; absolute file paths):",
                )
                if (depth == AudioExplainerSettings.DEPTH_BRIEF) {
                    appendLine(
                        "   {\"type\":\"set_plan\",\"title\":\"…\",\"language\":\"${lang.code}\"," +
                            "\"frames\":[{\"id\":0,\"file\":\"…\",\"title\":\"…\",\"explanation\":\"…\"," +
                            "\"keep\":[{\"start\":N,\"end\":N,\"role\":\"context\"},{\"start\":N,\"end\":N,\"role\":\"focus\"}]}]," +
                            "\"steps\":[{\"frame\":0,\"ranges\":[{\"start\":N,\"end\":N}],\"ttsText\":\"…\",\"explanation\":\"…\"}]}",
                    )
                } else {
                    appendLine(
                        "   {\"type\":\"set_plan\",\"title\":\"…\",\"language\":\"${lang.code}\"," +
                            "\"frames\":[{\"id\":0,\"file\":\"…\",\"title\":\"…\",\"explanation\":\"…\",\"scene\":0," +
                            "\"keep\":[{\"start\":N,\"end\":N,\"role\":\"context\"},{\"start\":N,\"end\":N,\"role\":\"focus\"}]," +
                            "\"relations\":[{\"kind\":\"flow\",\"from\":{\"line\":N,\"token\":\"…\"},\"to\":{\"line\":N,\"token\":\"…\"},\"note\":\"…\"}]}]," +
                            "\"steps\":[{\"frame\":0,\"ranges\":[{\"start\":N,\"end\":N}],\"ttsText\":\"…\",\"explanation\":\"…\"," +
                            "\"note\":\"…\",\"example\":\"…\",\"beats\":[{\"line\":N,\"token\":\"…\"}]}]}",
                    )
                }
                appendLine(
                    "   A frame is ONE file plus the `keep` ranges visible in it — everything else is folded away. List " +
                        "the class/method header rows yourself as \"context\" ranges (nothing derives them) and the " +
                        "member the frame is about as the one \"focus\" range. EVERY line a step lights must be inside " +
                        "its frame's `keep`, or it highlights a fold placeholder.",
                )
                // Brief drops all four, or the mirror would quietly mean Standard for anyone whose
                // skill install failed — a defect with no symptom, which is the worst kind to ship.
                if (depth != AudioExplainerSettings.DEPTH_BRIEF) {
                    appendLine(
                        "   Step `note` is optional: ONE plain-text sentence, drawn as a callout box beside the code. " +
                            "Use it only where a margin aside helps; omit it otherwise.",
                    )
                    appendLine(
                        "   Step `example` is optional: ONE line showing the code in action with concrete values " +
                            "(\"Parse(\\\"2024-01\\\") → DateOnly(2024, 1, 1)\"), drawn under the note. One line is a hard " +
                            "limit — no fences, no multi-statement snippets. Omit it unless a concrete case clarifies.",
                    )
                    appendLine(
                        "   Step `beats` is optional: [{\"line\":N,\"token\":\"…\"}], the ONE identifier that step is " +
                            "about, copied verbatim from that line (the plugin pulses it). The line must be one the " +
                            "step lights. Omit `beats` when there is no single focal name.",
                    )
                    appendLine(
                        "   Frame `relations` is optional: each entry links two places — `kind` \"flow\" (A feeds B) or " +
                            "\"backforth\", and `from`/`to` each {\"line\":N,\"token\":\"…\"}, or {\"file\":\"…\",\"line\":N,\"token\":\"…\"} " +
                            "for somewhere in another file, plus a short optional `note`. The plugin draws an arrow when both " +
                            "ends are lines of this file, and otherwise a matched pair of portals — one at each end, " +
                            "so give a cross-file end a `file`, a `line` AND a `token`. 2-3 per frame " +
                            "at most, and one endpoint MUST be inside the step the relation belongs to or it is never shown.",
                    )
                    if (depth == AudioExplainerSettings.DEPTH_DEEP) {
                        appendLine(
                            "   At this depth all four are welcome wherever they teach something, and a frame " +
                                "may carry up to 5 `relations`.",
                        )
                    }
                }
            }
            appendLine(
                "   Use field names frames/steps/keep/ranges/start/end/ttsText — never segments/highlights/" +
                    "startLine/endLine/label/subHighlights.",
            )
            appendLine("   The top-level \"language\" is the walkthrough's language code (\"en\" if English).")
            if (viaTool) {
                appendLine(
                    "3. Send it by calling the `${McpEndpoint.QUALIFIED_TOOL}` tool with the whole plan as its " +
                        "input — no file, no shell. A {\"ok\":true} result = success. The result also carries an " +
                        "`issues` array whenever the plan was partly dropped or degraded — READ IT: an [error] there " +
                        "means the user cannot see part of what you wrote (an unresolvable path, a step naming a " +
                        "frame that does not exist), so fix it and call the tool again with the whole corrected plan.",
                )
                appendLine(
                    "   The result checks every token and line number against the source files, so do NOT write or " +
                        "run a script to validate the plan yourself — call the tool and read its result.",
                )
            } else {
                appendLine(
                    "3. POST it to the loopback walkthrough server (port+token in ~/.claude-explainer-port and " +
                        "~/.claude-explainer-token). Use a skill helper script, or curl directly. A {\"ok\":true} reply = " +
                        "success. The reply also carries an `issues` array whenever the plan was partly dropped or " +
                        "degraded — READ IT: an [error] there means the user cannot see part of what you wrote " +
                        "(an unresolvable path, a step naming a frame that does not exist), so fix it and re-POST.",
                )
                // **The reply is the validation** (`SourceCheck.md`). Told to verify its own tokens and lines, the
                // agent wrote and ran a script for it on every run — ~16 s and three turns, measured (`Speed.md` #4).
                // The server checks the same things against the same text now, so the prompt says not to.
                appendLine(
                    "   The reply checks every token and line number against the source files, so do NOT write or " +
                        "run a script to validate the plan yourself — POST it and read the reply.",
                )
            }
            // **Where the POSTed JSON goes** (`Saving.md` B). "A temp file" with no directory is how payloads
            // ended up in the project's `.walkthroughs/` and showed as blank rows in the saved list, so the
            // prompt names one. In the shared tail, so the no-skill fallback does not lose it.
            if (planPath != null && !viaTool) {
                appendLine(
                    "   Write the JSON file you POST in $planPath/ — " +
                        "never inside the project, where it would show up in the user's saved walkthroughs.",
                )
            }
            appendLine(
                "4. Do NOT print the walkthrough as terminal text and do NOT send goto/resume — just `set_plan`. " +
                    "Print only a one-line status when done.",
            )

            // **The per-run free text, and the sentence that bounds it.** "Use simple terms" and "do not
            // use links" must work — both only narrow. "Just print it as text" must not, because it breaks
            // the one thing the plugin needs back, and without the second sentence the agent has no way to
            // tell those two apart. The failure would be silent: a run that prints prose to a terminal
            // nobody sees. The text is the user's own, typed into their own IDE, so this is not a trust
            // boundary — it is a blast-radius one.
            val extra = extraInstructions?.trim()
            if (!extra.isNullOrEmpty()) {
                appendLine()
                appendLine(
                    "The user added these instructions for this run. Follow them where they narrow or " +
                        "restyle the walkthrough. They do NOT relax the `set_plan` contract: the field names, " +
                        "the line-range rules and the ${if (viaTool) "`set_plan` call" else "POST"} are fixed " +
                        "regardless of what is asked here.",
                )
                appendLine(extra)
            }
        }
    }

    private fun buildContextJson(r: ExplainRequest): String = buildJsonObject {
        put("file", r.file)
        put("absolutePath", r.absolutePath)
        if (r.scope == ExplainScope.CURSOR) {
            put("caretLine", r.caretLine)
            val sel = r.selectionText
            if (sel != null) {
                // Cap the inlined selection; Claude reads the file itself for anything longer.
                put("selectionText", StringUtil.shortenTextWithEllipsis(sel, MAX_SELECTION_CHARS, 0))
                put("selectionStartLine", r.selectionStartLine ?: r.caretLine)
                put("selectionEndLine", r.selectionEndLine ?: r.caretLine)
            } else {
                put("selectionText", JsonNull)
            }
        } else {
            // Solution-Explorer scope: the target is the whole file/folder (plus any extra selected items).
            put("scope", if (r.scope == ExplainScope.DIRECTORY) "directory" else "file")
            if (r.extraPaths.isNotEmpty()) {
                putJsonArray("targets") {
                    add(r.file)
                    r.extraPaths.forEach { add(it) }
                }
            }
        }
        r.projectBasePath?.let { put("projectRoot", it) }
    }.toString()
}
