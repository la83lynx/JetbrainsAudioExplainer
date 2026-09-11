# Step 2: Planner (Deep Dive only)

> **Overview mode** skips this step — a single Haiku agent builds plan + steps in one pass and sends `set_plan` directly.

Dispatch a **`LARGE`** sub-agent to turn the scout's file map into a narrative plan. The planner decides *how to tell the story*, not just what files exist.

After the planner finishes, proceed to dispatch frame agents. Do NOT send anything to the sidebar yet — wait until all frame agents have completed and you have full steps for every frame.

## Planner sub-agent

Agent tool parameters:
- `model`: `LARGE` ← replace with model from SKILL.md
- `description`: `Plan walkthrough narrative for {feature}`

### Prompt template

```
You are planning a code walkthrough for "{feature}".

The scout found these files (in call-flow order):

{scout_output}

Your job is to produce an ordered list of walkthrough frames with narrative transition objects.
Do NOT read the actual code — work only from the scout's summaries.

For each frame output:

{
  "id": <sequential integer>,
  "file": "<absolute path>",
  "start": <1-based line number>,
  "end": <1-based line number>,
  "title": "<short frame label, usually the member>",
  "complexity": "<core|wiring|supporting>",
  "previousContext": "<what the previous frame established, or 'Entry point' for first>",
  "role": "<what this frame does in 1-2 sentences>",
  "nextContext": "<what this frame hands off to the next>",
  "narrativeHook": "<how to open the explanation — what angle makes this frame interesting>"
}

Rules:
- Order by pedagogical flow, not just call order. Sometimes it is clearer to show the data shape before the code that creates it.
- Keep [wiring] frames brief — flag them so the frame agent breezes through.
- The narrativeHook should give the frame agent a concrete angle, not just "explain this file".
- previousContext / nextContext are the connective tissue that makes frames feel like one continuous story.
- `start`/`end` here are the *span the frame agent reads*, not a field of the plan. The agent turns that
  span into the frame's `keep` ranges (header rows as context, the member as focus).
- **Most of these keys are planner-only and must not reach the wire**: `start`, `end`, `complexity`,
  `previousContext`, `nextContext` and `narrativeHook` are all dropped silently if sent. So is `role` — and
  that one is a trap, because `role` **is** a real field on a `keep` range, where it means
  `"context"`/`"focus"` and nothing like the narrative role above. If you carry the planner's object forward,
  strip it; `docs/plan-schema.md` is the list of what a frame may actually contain.
- The planner also decides **nesting and scenes** — which frame is called from which line of which parent,
  and which frames share a screen. Pass `parent`/`callLine`/`callToken`/`scene` to the frame agents; they
  cannot work it out from one frame's span.

Return a JSON array of frame objects.
```

## After the planner finishes

Show the plan outline in chat so the user can reorder or skip frames before generation begins:

```
I'll walk through {feature} in {N} frames:

1. src/controllers/auth.controller.ts:10-45 — HTTP endpoint handler [core]
2. src/modules/auth.module.ts:1-30 — Module registration [wiring]
3. src/services/auth.service.ts:20-65 — Core authentication logic [core]
...

Say "go" to start generating, or adjust the plan first.
```

Once the user approves, proceed to Step 3 — pass the planner's transition objects to the frame agents.
