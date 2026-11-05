# Step 3: Parallel Frame Agents (Deep Dive only)

> **Overview mode** skips this step — the Overview agent already sent `set_plan` with complete steps.

Dispatch **one sub-agent per frame** in parallel. Each agent reads its file deeply and generates dense,
granular narration steps for it. Wait for ALL agents to complete before sending anything to the sidebar.

**Vocabulary:** a plan is `frames` (the code on screen) plus `steps` (the narration, in order). What earlier
versions of this skill called a *segment* is now a **frame**, and what it called a *highlight* is now a
**step**. `docs/plan-schema.md` is the single source of truth for every field name.

## Per-frame sub-agent

Agent tool parameters:
- `model`: `MEDIUM` ← replace with model from SKILL.md
- `description`: `Generate steps for {frame.title}`

### Prompt template

Substitute every `{placeholder}` before dispatching — including `{skillDir}`, the absolute path to this
skill's directory, so the sub-agent can read the schema doc. A sub-agent that cannot find
`plan-schema.md` will guess field names, which is exactly the failure this avoids.

```
You are generating one frame of a code explanation, and the narration steps for it.

Feature: "{feature}"
File: {file}
Lines: {start}–{end}
Complexity: {complexity}
Depth: {overview|deep-dive}

Narrative context:
- Previous frame established: {previousContext}
- Your role in the story: {role}
- You hand off to next frame: {nextContext}
- Suggested opening angle: {narrativeHook}

Read the code:
{file_content}   ← use offset={start} limit={end-start+1}

READ {skillDir}/docs/plan-schema.md FIRST — it is the single source of truth for the frame and step
field names. Do not guess field names from memory: unknown fields are dropped silently, so a wrong
name produces a walkthrough with pieces mysteriously missing.

Produce ONE JSON object of the shape:

  { "frame": { "id": {id}, "file": "{file}", "title": "{title}", "explanation": "…", "keep": [ … ],
                 "parent": {parentId}, "callLine": {callLine}, "callToken": "{callToken}",
                 "scene": {scene} },
    "steps": [ { "frame": {id}, "ranges": [ { "start": N, "end": N } ], "ttsText": "…", … } ] }

  `parent`/`callLine`/`callToken` and `scene` are **the planner's decision, handed to you** — copy them
  through. Omit `parent` and its two companions for a top-level frame; omit `scene` for scene 0. Leaving them
  out of a frame that needs them is the one way this pipeline produces a flat plan for a call chain, and the
  assembly step below preserves whatever you put in `.frame` without adding anything.

  **And the *parent's* agent has to be told too.** `plan-schema.md` requires the parent frame to `keep` the
  line its child hangs off — but in this flow the parent is a different agent, which never sees its children.
  So the planner must hand each parent frame **the list of call lines its children need kept**, and that agent
  must spend a `keep` range on them. Miss it and every nested frame earns a `frame.call-line-not-kept`, which
  is a warning per child rather than a single visible failure.

Frame rules:

- `keep` is what the reader sees; everything else in the file is folded away. List **2-3 ranges**: the
  class/method header lines as `"role": "context"`, and the member this frame is about as
  `"role": "focus"`. You must list header lines yourself — the plugin never derives them.
- Every line any of your steps lights must be inside `keep`. A few lines just past a kept range are
  widened into it for you; a step pointing further away renders as a highlight on a fold placeholder,
  which reads as the plugin having lost the step.
- `explanation` is markdown, for the sidebar. `title` is a short label — usually the member name.

Step rules:

Think of steps as a **teacher's pointer moving across the board while talking continuously**. All
`ttsText` is concatenated into one TTS call — the voice never stops, and the pointer advances
mid-speech as each step's audio finishes playing. Write ttsText as **flowing narration**, not
self-contained mini-lectures.

- **6-12 steps per frame** in Deep Dive. Quality over quantity.
- **1-4 lines per range**, usually one range per step. Group related lines (a condition + its body, a
  variable + its usage). Only use single-line ranges for truly standalone key lines.
- **Skip boilerplate**: imports, obvious field declarations, closing braces, standard enum values,
  trivial assignments. If a line is self-explanatory to someone reading code, don't narrate it.
- **Cover what's interesting**: non-obvious logic, key design decisions, the "why" lines, surprising
  patterns, error handling strategies, the lines that make this code *this* code.
- `ttsText`: **1-2 sentences**, plain text only. Explain the *intent* or *why*, not just what the line
  does. Because steps are spoken as one continuous stream, each ttsText should flow naturally from the
  previous one — use connectives ("Next," "From here," "Once that's done,") rather than restating
  context. "The retry budget is set to three — enough to recover from a nonce collision but not so many
  that a broken transaction loops forever." not "This sets the retry limit to three."
- `explanation`: 2-5 word label shown in the sidebar. "Retry budget", "Nonce recovery", "Balance gate".
  Think tooltip, not prose.
- `note` (optional): ONE plain-text sentence, drawn as a callout box next to the code in the editor. Use
  it for the aside a reader would want in the margin — a constraint, a gotcha, the reason behind a magic
  number, a consequence not visible locally. "Capped at 3 so a broken transaction can't loop forever."
  Be **sparse**: a note on every step becomes wallpaper. Skip it when it would restate the line, and
  don't repeat `ttsText` — the user already hears that.
- `example` (optional): ONE line showing the code in action, drawn under the `note` in the same callout
  box — an input→output pair with concrete values. `"Parse(\"2024-01\") → DateOnly(2024, 1, 1)"`. One
  line is a hard limit (the box wraps text, so extra lines are dropped with an ellipsis): no fences, no
  diffs, no multi-statement snippets. Sparse, like `note`.
- `beats` (optional): `[{ line, token }]` — the ONE identifier this step is really about, **copied verbatim from the line the
  step lights** (matching is exact, so retyping from memory breaks it). The plugin flashes it, pointing
  the eye at the right word inside a multi-line range. `"maxRetries"`, not `"the maxRetries constant"`.
  Omit it when the step has no single focal name. Add `"occurrence": 2` only when you mean a later match
  of a repeated name. An unfound token is ignored silently.
- `relations` (optional, on the **frame** not the step): **5 on any one step is the hard cap** and the sixth is dropped silently; 2-3 is what reads well. Each links two places that are
  genuinely connected — a value produced on one line and consumed on another
  (`{"kind":"flow","from":{"line":44,"token":"raw"},"to":{"line":51,"token":"parsed"},"note":"parsed value"}`),
  a back-and-forth pair (`"kind":"backforth"`, drawn with an arrowhead at **both** ends), or a call
  whose target is in another file
  (`"to":{"file":"Pricing/RateTable.cs","symbol":"RateTable.Lookup","line":88}`). `kind` is those two values
  and no others; it defaults to `"flow"`, and **it does not currently change the drawing** — see
  `docs/plan-schema.md`. The plugin draws an arrow when both ends are nearby lines of this file and a matched
  pair of coloured portals otherwise — **you don't choose which**. One endpoint must be inside the step the relation belongs to, or it is never
  shown. Get the line numbers right; give a `token` on both ends and the plugin catches a miscount.
- First step: open with a one-liner referencing previousContext. ("Picking up from the controller,
  here's where credentials are actually checked.")
- `[wiring]` frames: **3-5 steps max**. Hit only the non-obvious config choices. "Registers the auth
  module." and move on.
- `[core]` frames: **8-12 steps**. Cover every important decision, skip standard patterns.

Create a unique temp directory for this session: `TMPDIR=$(mktemp -d)` (run once before dispatching
agents, pass the path to each agent). Write the JSON object to `$TMPDIR/frame-{id}.json` using the Write
tool. No prose, no wrapping — just the raw JSON object in the file.
```

### Example — constructor with 4 args

Good — pointer style, flowing narration (these are spoken as one continuous stream):
```json
{ "frame": { "id": 0, "file": "src/auth/auth.service.ts", "title": "AuthService constructor",
             "explanation": "How AuthService gets its dependencies.",
             "keep": [ { "start": 8, "end": 8, "role": "context" },
                       { "start": 12, "end": 18, "role": "focus" } ] },
  "steps": [
    { "frame": 0, "ranges": [ { "start": 13, "end": 16 } ], "ttsText": "Four services are injected — the interesting ones are UserRepository for database access and JwtService for token signing. The mailer and config service are standard NestJS plumbing.", "explanation": "DI dependencies", "beats": [ { "line": 14, "token": "UserRepository" } ] },
    { "frame": 0, "ranges": [ { "start": 18, "end": 18 } ], "ttsText": "And right below, token expiry is set from config in the constructor — no magic number buried deep in a method where you'd never find it.", "explanation": "Token expiry from config" }
  ] }
```

Not this — one step per line, self-contained statements that sound choppy when concatenated:
```json
"steps": [
  { "frame": 0, "ranges": [ { "start": 12, "end": 12 } ], "ttsText": "The constructor opens here.", "explanation": "Constructor" },
  { "frame": 0, "ranges": [ { "start": 13, "end": 13 } ], "ttsText": "This injects the user repository for database access.", "explanation": "UserRepository" },
  { "frame": 0, "ranges": [ { "start": 14, "end": 14 } ], "ttsText": "This injects the JWT service for token signing.", "explanation": "JwtService" },
  { "frame": 0, "ranges": [ { "start": 15, "end": 15 } ], "ttsText": "This injects the mailer for sending verification emails.", "explanation": "MailerService" }
]
```

## Wait for all agents, then assemble from files

Do NOT send anything to the sidebar until every agent has returned. Each agent writes its frame plus that
frame's steps to `$TMPDIR/frame-{id}.json`. Once all agents finish, assemble and send with one bash command:

```bash
jq -s --arg title "{feature} Walkthrough" --arg lang "en" \
  '{ type: "set_plan", title: $title, language: $lang,
     frames: map(.frame), steps: map(.steps) | add }' \
  $(for i in $(seq 0 {lastFrameId}); do echo "$TMPDIR/frame-$i.json"; done) \
  > "$TMPDIR/walkthrough-plan.json" \
  && ~/.claude/skills/audio-explainer/scripts/explainer.sh plan "$TMPDIR/walkthrough-plan.json" \
  && rm -rf "$TMPDIR"
```

`jq -s` slurps the per-frame files into one array, so `map(.frame)` is the frame list and
`map(.steps) | add` is every frame's steps concatenated **in frame order** — which is the narration order
this flow intends. The main agent never has to parse or re-serialize any of the JSON. The `rm -rf` cleans up.

**Check the reply.** `explainer.sh plan` prints the server's response; it carries an `issues` array whenever
something was dropped or degraded (a path that didn't resolve, a step naming a frame id that doesn't exist, a
step lighting a line its frame elides). An `[error]` there means the reader is missing part of the picture —
fix it and re-POST rather than moving on.

**Language:** `--arg lang "en"` sets the walkthrough's language code. For a non-English walkthrough, pass
that code instead (e.g. `--arg lang "es"`) **and** have every frame agent write its `title`/`explanation` and
step `ttsText`/`explanation` in that language (code identifiers, a beat's `token` and `callToken` stay verbatim). One
language per walkthrough — see `docs/walkthrough.md`.

**There is no `view` field** — every plan renders as a tree of code, and a single frame is a one-node tree.
What this flow *does* have to decide is whether the frames **nest**: if the explanation is a call chain and
seeing caller and callee at once is the point, the frames need `parent` / `callLine` / `callToken` to say how.
`docs/plan-schema.md` has the rules. This parallel-agent flow does not set them up for you, because which
frame calls which is a decision the planning step made, not one a per-file agent can see — and if the frames
belong to different **scenes**, that is the planning step's decision too.

## Concurrency

Dispatch all frame agents at once — no artificial cap. If you hit rate limits, back off and retry the failed
agents.

## After `set_plan` is sent

Proceed to the walkthrough execution doc for the chosen delivery mode:
- **Walkthrough mode** → `docs/walkthrough.md` (sidebar handles playback)
- **Read mode** → `docs/read.md` (step through the plan in terminal)
- **Podcast mode** → `docs/podcast.md` (render single audio file)
