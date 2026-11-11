# Step 5: Walkthrough Mode

Sidebar-driven playback. Send the walkthrough plan to the sidebar extension which handles highlighting, TTS
streaming, and auto-advancing.

**Prerequisite:** Sidebar status already determined in step 0 (parallel init).

## Steps

1. **Build the plan JSON.** The schema lives in **`docs/plan-schema.md` — the single source of truth**;
   read it for the exact field names rather than copying the illustration below.

   A plan is two lists: **`frames`** (the code on screen — one file plus the lines kept visible in it) and
   **`steps`** (the narration, in order, each naming the frame it is about).

```json
{
    "type": "set_plan",
    "title": "Feature Name Walkthrough",
    "language": "en",
    "frames": [
        {
            "id": 0,
            "file": "/absolute/path/to/file.ts",
            "title": "Module definition",
            "explanation": "This is the **module definition**. It imports all the services and sets up the constructor.",
            "keep": [
                { "start": 1,  "end": 8,  "role": "context" },
                { "start": 10, "end": 40, "role": "focus"   }
            ]
        }
    ],
    "steps": [
        { "frame": 0, "ranges": [ { "start": 5, "end": 8 } ], "ttsText": "The imports pull in the two services order matching needs." },
        { "frame": 0, "ranges": [ { "start": 10, "end": 12 } ], "ttsText": "Next, the class itself, and the decorator that makes it injectable." },
        { "frame": 0, "ranges": [ { "start": 14, "end": 17 } ], "ttsText": "Its dependencies arrive through the constructor, which is what makes the matcher swappable in tests.", "note": "Constructor injection — swappable in tests.", "beats": [ { "line": 14, "token": "constructor" } ] },
        { "frame": 0, "ranges": [ { "start": 30, "end": 33 } ], "ttsText": "Finally, initialization loads the existing orders into the in-memory book." }
    ]
}
```

Narration lives on each **step**; a frame has no `ttsText`. A frame's `keep` decides what the reader sees —
mark the header rows `context` and the member itself `focus`, and make sure **every line a step lights is
inside its frame's `keep`**. The optional per-step `note` is drawn as a callout box beside the code, the
optional one-line `example` (`"Parse(\"2024-01\") → DateOnly(2024, 1, 1)"`) under it in the same box, and the
optional `beats` — each `{ line, token }`, the token a literal substring of that line — briefly pulse so the eye lands on the step's key
identifier. A **frame** may also carry an optional `relations` list linking two places in the code; the
plugin draws an arrow between nearby lines of the same file and a matched pair of coloured portals for a target
elsewhere. See `docs/plan-schema.md` for all of them, for `parent` / `callLine` / `callToken` — which nest a
callee's frame inside its caller at the call site — and for `scene`, which decides the frames on screen
together when a walkthrough moves from one subject to the next.

2. **Send to sidebar:**

```bash
cat > /tmp/walkthrough-plan.json << 'EOF'
{ "type": "set_plan", "title": "...", "language": "en", "frames": [...], "steps": [...] }
EOF
~/.claude/skills/audio-explainer/scripts/explainer.sh plan /tmp/walkthrough-plan.json
```

The reply is `{"ok":true,"frames":N,"steps":N}`, plus an **`issues`** array whenever something was dropped or
degraded. Read it: an `[error]` means the reader is missing part of what you wrote — fix and re-POST.

The sidebar loads the plan and shows the first step's code location. Playback does NOT start automatically —
the user must press the Play button on the sidebar to begin. Tell the user: **"Press ▶ Play on the sidebar to
start the walkthrough."**

3. **Navigate (optional):** `goto` names **a frame** (`{"type":"goto","frameId":2}`) and lands on the first step that frame is the
primary of, or **a step outright** (`{"type":"goto","stepIndex":7}`) when you know the plan's own step order
— which you do, because you wrote it. `{"type":"resume"}` starts playback.
**To change a loaded plan, send a new `set_plan`** — there are no partial-update messages, because you are
holding the whole plan and one POST is cheaper than keeping two copies in step.

4. **Other commands:** `explainer.sh state` (check state), `explainer.sh stop` (stop walkthrough).

## Language

Top-level `"language"` is the walkthrough's language code (`en`, `es`, `fr`, `hi`, `it`, `pt`, `ja`,
`zh`); default `"en"`. **One language per walkthrough.** If the user asks for another language (or the
plugin's configured explanation language is non-English), set `"language"` to that code and write ALL
prose — `title`, every frame `title`/`explanation`, and every step `ttsText`/`explanation`/`note` — in that
language, keeping code identifiers, file paths, and API names verbatim (never translate code, and never
translate a beat's `token`, `callToken` or `example` — the first two must match the source text to resolve, and an
`example` is code; a relation's `note` is prose and does get translated, its `token`/`symbol` do not). The
plugin loads that language's voices automatically; no `voice` field is needed here.

## Frame guidelines

- **2-3 `keep` ranges per frame** (3 is the cap). There is no per-frame line number to hit: the budget is **~60 per scene**, shared by every frame on screen together, which in a three-deep chain is ~18 lines of member each — `docs/plan-schema.md` binds
- `explanation`: supports markdown. Step `ttsText`: plain text only, no markdown/line refs/paths
- TTS: **1-2 sentences** per step, conversational — the step is a pointer, not a paragraph
- **No cap on frames per plan**, and none on scenes. What binds is the per-scene shape: 3 levels of nesting, 6 siblings under one parent, ~60 lines on screen — see `docs/plan-schema.md`

### Steps

Required for every frame — a frame nobody narrates renders as structure with no voice.

- **One concept per step** — each step explains exactly one thing:
  - One function call, one assignment, one conditional, one return
  - **Not one step per argument, and not one step per line** — that is the choppy failure mode
    `docs/segments.md` names. Group what is understood together: a condition with its body, a call with the
    arguments that matter to it
  - For sequential operations (e.g., 3 DB updates): one step per operation
- **1-4 lines per range**, usually one range per step
- **6-12 steps per frame** for a Deep Dive, 3-6 for an Overview. Quality over granularity; nothing in
  the loader enforces a step count, so this is taste rather than a cap
- Each step has its own `ttsText` (1-2 sentences)
- **Not a partition** — OK to skip boilerplate lines between steps
- Target **50-80% coverage** of the frame's kept lines
- A step may name **two frames** (`"frames": [{...}, {...}]`) when the point of it is the relationship
  between them — a value crossing a call boundary. `frames[0]` is primary and gets the callout

## Narration style

- Speak as a live code tour to a colleague, not "line 1 through 8 of file.ts"
- Connect frames: "Moving down..." / "Next up..." / "This feeds into..."
- Lead with **WHY** before HOW
- Ground in concrete scenarios: "When a user places an order, this is what runs"
- `[wiring]`: breeze through. `[core]`: detail. Vary pacing.

## User controls

The sidebar handles Play/Pause, Next/Prev, speed, volume, voice, and outline navigation; the agent does not
need to drive playback.

See `docs/tts.md` for voice config.
