---
name: audio-explainer
description: "Use when the user asks to explain, walk through, or understand a feature, module, or code flow in the codebase. Triggers on 'explain', 'walk me through', 'how does X work', 'what does this code do'. Also the backbone for the Audio Explainer IDE plugin's right-click 'Explain This (Audio)' action."
---

# Audio Explainer

Interactive code walkthrough. Scans the codebase for a feature, builds a plan of **frames** (the code on screen) and **steps** (the narration), then walks through it — highlighting code in the editor and explaining at their chosen depth. Adapted from [code-explainer](https://github.com/Royal-lobster/code-explainer) (MIT, © Srujan Gurram) for the JetBrains/Rider Audio Explainer plugin, which reuses this skill unchanged via a loopback HTTP server (same `~/.claude-explainer-{port,token}` discovery contract).

## Plugin-triggered (headless) mode — READ FIRST

If this run was launched by the **Audio Explainer IDE plugin** (a `claude -p` invocation whose prompt hands you a cursor-context JSON like `{"file":…, "caretLine":…, "selectionText":…}` and says it is a headless right-click "Explain This"), **do not run the interactive checklist below.** Instead read `docs/explain-at-cursor.md` and follow it exactly: you are fully autonomous (no `AskUserQuestion`, no waiting), you expand the symbol under the cursor yourself, generate a lean plan, POST `set_plan`, and stop. The rest of this file is for interactive sessions.

## Models

Configure your preferred models here. All docs reference these tiers by name — change them once and the whole skill updates.

| Tier | Default | Role |
|------|---------|------|
| `LARGE` | `opus` | Deep Dive planner — narrative reasoning, transition objects |
| `MEDIUM` | `sonnet` | Deep Dive frame agents — deep code reading, dense steps |
| `SMALL` | `haiku` | Scout, Overview plan+steps — fast exploration and scanning |

When dispatching sub-agents, look up the model for the tier and use that exact model name.

## Checklist (interactive sessions)

Complete these steps in order:

0. **Parallel init** — Dispatch both in a **single response**:
   - **Sidebar check (Bash):** `PORT=$(cat ~/.claude-explainer-port 2>/dev/null) && TOKEN=$(cat ~/.claude-explainer-token 2>/dev/null) && curl -sf -H "Authorization: Bearer $TOKEN" "http://localhost:$PORT/api/health"` — `{"status":"ok"}` means the sidebar is active. When active, **NEVER output walkthrough content as terminal text**; all output goes through the sidebar HTTP API only.
   - **Ask preferences (AskUserQuestion):** Read `docs/assess.md` and ask all three questions listed there (familiarity + depth level + delivery mode) in a single call. Do NOT skip any or invent new ones.

1. **Scout** — Read `docs/scan.md`. Dispatch `SMALL` sub-agent to discover relevant files and map the call chain. No steps yet — discovery only.
2. **Plan + generate** — Two paths depending on depth:
   - **Overview** — Single `SMALL` sub-agent reads scout output, builds plan, generates steps in one pass. Send `set_plan` when done.
   - **Deep Dive** — Read `docs/plan.md`. Dispatch `LARGE` planner to build narrative + transition objects. Then read `docs/segments.md` and dispatch parallel `MEDIUM` frame agents (all at once). Create a unique temp dir with `mktemp -d` and have each agent write its frame plus that frame's steps there. Wait for ALL agents to complete, then assemble from files with `jq` and send one full `set_plan`. Clean up the temp dir after sending. Do NOT send anything to the sidebar until everything is ready.
3. **Execute walkthrough** — Read the doc for chosen mode: `docs/walkthrough.md`, `docs/read.md`, or `docs/podcast.md`. Walkthrough and podcast reference `docs/tts.md`.
4. **Wrap up** — 3-5 key takeaways, how feature fits the broader architecture, offer to dive deeper or explain related features.

**First-time setup?** Read `docs/setup.md`.

## Language (optional)

Walkthroughs carry a top-level `language` code (`en` default; also `es`, `fr`, `hi`, `it`, `pt`, `ja`,
`zh`). If the user asks for a walkthrough in one of these languages, set `language` in the `set_plan` and
write ALL prose (title, frame titles/explanations, step `ttsText`/labels/notes) in that language,
keeping code identifiers/paths/API names verbatim — including any beat `token` or `callToken` anchor, which must
match the source text exactly. One language per walkthrough. The plugin loads that
language's voices automatically. No new question is required — infer it from the user's request. See
`docs/plan-schema.md` (schema), `docs/walkthrough.md` and `docs/tts.md`.

## Q&A on Active Walkthrough

When the user says they're in an active explainer walkthrough and asks a question (even in a new chat), skip the full checklist above and instead:

1. **Get context:** Run `~/.claude/skills/audio-explainer/scripts/explainer.sh state` (or `explainer.ps1 state` on Windows) — the response includes a `segment` field describing what is on screen right now (`file`, `start`, `end`, `title`, `explanation`, and the steps in it). Use this to understand what code the user is currently viewing.
2. **Read the code:** Use that `file`, `start`, and `end` to read the relevant source code.
3. **Answer in context:** Ground your answer in the specific code the user is looking at. Reference those line numbers, not abstract concepts.

If `state` returns `status: "idle"` (no active walkthrough), check `.walkthroughs/` for saved plans and ask the user which one they mean.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Scope too large | Stick to frame boundaries. **The budget is ~60 lines per *scene*, not per frame** — a measured tool-window viewport, and every frame of a scene is on screen at once, so a three-frame chain shares that 60 (~18 lines of member each). `docs/plan-schema.md` has the caps table; the remedy for a fat scene is to trim what each frame keeps, or to split the scene where the nesting allows |
| Not connecting frames | Include a context line linking to the previous frame |
| Reading entire file | Use offset+limit on Read for just the frame |
| Not waiting for user (interactive only) | Pause after each frame for questions. Headless cursor mode never waits |
| ttsText missing or has markdown | Include plain `ttsText` in every step — strip backticks, bold, line refs from spoken text |
| A step lighting a line its frame elides | Every line a step names must be inside its frame's `keep`, or it highlights a fold placeholder and reads as a lost step. The POST reply says so — read its `issues` |
| Forgetting `keep`, or leaving out the header lines | A frame is a file plus the lines kept visible in it. Nothing derives the enclosing class/method rows for you: list them as `"role": "context"` ranges |
| Explaining obvious code, missing the "why" | Skip standard patterns (loops, imports, null checks). Always explain intent before mechanism |
| Ignoring complexity tags | `[core]` = thorough, `[wiring]` = breeze through, `[supporting]` = brief |
| Text output when sidebar active | If health check returned ok, send plan JSON only — no terminal text |
| Steps too many or too granular | Deep Dive: 6-12 steps per frame, 1-4 lines each. Steps are a moving pointer over one continuous voice stream — ttsText across steps is concatenated and spoken as one TTS call, so write it as flowing narration, not self-contained slides. Overview: 1-4 lines too — there is no Overview exception — and 3-6 steps per frame |
| Wrong field names in sidebar JSON | Use `frames`/`steps`/`keep`/`ranges`/`start`/`end`/`ttsText` — NOT `segments`/`highlights`/`startLine`/`endLine`/`label`/`subHighlights`. **`docs/plan-schema.md` is the single source of truth** for the schema (`docs/plan.md` is the Deep-Dive planner step, a different object) |
| A beat `token` that isn't on its `line` | A beat is `{ line, token }`: the token must be a literal substring of **that line**, copied verbatim. A paraphrase, a remembered name, or a line off by one resolves to nothing and the pulse silently doesn't happen. The reply checks both: the line against the step (`step.beat-line-outside-ranges`), the token against the code (`step.beat-token-not-on-line`) |
| A `relations` entry that never appears | Relations live on the **frame** and are drawn per step: one endpoint must fall **inside a step's own ranges**, or nothing shows. Cross-file targets render as a matched pair of coloured portals (one at each end) rather than an arrow — a different picture for a different fact, not a degraded one. Give **both** ends a `file`, a `line` and a `token`: the line is the row a portal hangs off, the token is what gets tinted. **The cap is 5 on any one *step*, and it is hard — the sixth is dropped silently.** Two or three is what reads well. The reply's `issues` reports ends that are never drawn, and tokens that are not on their line; see `docs/plan-schema.md` |
| Not reading the POST reply | `set_plan` answers with `frames`/`steps` counts and an `issues` array. An `[error]` there means part of what you wrote is not on screen — fix it and re-POST instead of reporting success. It checks your tokens and lines against the code too, so do not write a validator of your own |
| Skipping `set_plan` before `goto` | Sidebar needs the full plan loaded first. Always send `set_plan` before any `goto` messages |
| Sending plan before agents finish | Wait for ALL parallel frame agents to complete. Each writes to a unique temp dir (created via `mktemp -d`). Assemble from files with `jq`, then send one `set_plan`. Clean up temp dir after. Never send stubs or partial plans |
| Scout generating steps | Scout only maps files and call chain. Steps are generated in step 2 (Overview: single agent, Deep Dive: parallel agents) |
| Running planner + parallel agents for Overview | Overview uses one fast `SMALL` agent for plan + steps. Planner and frame agents are Deep Dive only |
| Sending a `view` field | There is no `view` any more: every plan renders as a tree of code, and one frame is a one-node tree. Nest a callee with `parent`/`callLine`/`callToken`; split subjects with `scene` — see `docs/plan-schema.md` |
| Using tier names as literal model names | `LARGE`, `MEDIUM`, `SMALL` are placeholders — always resolve to the actual model name from the Models table before dispatching |
