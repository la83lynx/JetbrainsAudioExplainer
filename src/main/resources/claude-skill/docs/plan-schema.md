# `set_plan` wire schema — SINGLE SOURCE OF TRUTH

**This file is authoritative.** Every other doc and prompt in this skill points here instead of
restating the schema, because field-name drift between copies is the single most common failure mode.
If you are writing a plan, the shape below is the contract; do not infer fields from examples elsewhere.

The plugin decodes this with `ignoreUnknownKeys`, so an unknown field is silently dropped rather than
rejected — a typo'd field name fails **quietly**. That is why the names matter more than anything else here.

**A plan has two halves.** `frames` say *what code is on screen*; `steps` say *what is narrated, in order*.
They are separate lists because the tree of code has to stand still while the narration walks it: a step can
light up ranges in several frames at once, and it can come back to a frame the narration has already left.

## The message

```json
{
  "type": "set_plan",
  "title": "How someParam reaches SubInvoker",
  "language": "en",
  "frames": [
    {
      "id": 0,
      "scene": 0,
      "file": "<absolute path, or the exact path style you were given>",
      "title": "MainInvoker.MainLoop",
      "explanation": "<markdown explanation of this frame, for the sidebar>",
      "keep": [
        { "start": 18, "end": 18, "role": "context" },
        { "start": 68, "end": 92, "role": "focus"   }
      ],
      "relations": [
        { "kind": "flow",
          "from": { "line": 70, "token": "raw" },
          "to":   { "line": 85, "token": "someParam" },
          "note": "<optional: what flows, 2-5 words>" }
      ]
    },
    {
      "id": 1,
      "parent": 0,
      "callLine": 85,
      "callToken": "SomeSysCall",
      "file": "<the callee's file>",
      "title": "MidInvoker.InvokeOn",
      "explanation": "…",
      "keep": [
        { "start": 17, "end": 17, "role": "context" },
        { "start": 36, "end": 60, "role": "focus"   }
      ]
    }
  ],
  "steps": [
    { "frame": 0, "ranges": [ { "start": 68, "end": 70 } ],
      "ttsText": "<1-2 plain-text sentences, flowing narration>",
      "explanation": "<2-5 word sidebar label>",
      "note": "<optional: one sentence shown as a callout in the editor>",
      "example": "<optional: ONE line, input → output, shown under the note>",
      "beats": [ { "line": 69, "token": "<optional: an identifier ON line 69, pulsed>" } ] },

    { "frame": 1, "ranges": [ { "start": 36, "end": 38 } ], "ttsText": "…" },

    { "frames": [ { "frame": 0, "ranges": [ { "start": 85, "end": 85 } ] },
                  { "frame": 1, "ranges": [ { "start": 36, "end": 36 } ] } ],
      "ttsText": "<a step that spans two frames — the caller's line and the callee's entry>" }
  ]
}
```

## Fields

**Line numbers are 1-based and ranges are inclusive on BOTH ends.**

| Field | Where | Required | Notes |
|---|---|---|---|
| `type` | message | yes | Always `"set_plan"` |
| `title` | message | yes | Short label for the whole walkthrough |
| `language` | message | no, but **send it** | Language code — see **Language** below. The wire type defaults it, so omitting it is legal — but it defaults to **`"en"`**, not to whatever voice the user has configured, and a blank string is read as `"en"` too. So an omitted `language` means *English prose*, whichever language you actually wrote. Send it whenever you know it, and always when it is not English |
| `frames[]` | message | yes | The code on screen — see **Scenes** and the caps |
| `steps[]` | message | yes | The narration, in order |
| `id` | frame | yes | Unique integer. **The only identity a frame has** — two frames may share one `file` |
| `file` | frame | yes | **Absolute or relative to the project root — both resolve** (a relative path is resolved against the open project). Absolute is safer when you cannot be sure which directory the user opened. A path that does not resolve comes back in the reply as `frame.file-unresolved` |
| `title` | frame | yes | Short label — usually the member this frame is about (`"MidInvoker.InvokeOn"`) |
| `explanation` | frame | yes | Markdown; the sidebar overview for this frame |
| `scene` | frame | no | Which set of frames this one is on screen with. Default `0`. See **Scenes** |
| `keep[]` | frame | yes | The lines to show. Everything else in the file is elided — see **`keep`** below |
| `relations[]` items | frame | no | `{ kind?, from, to, note? }` — `from`/`to` are endpoints and are the only required part; see **`relations`** |
| `kind` | relation | no | `"flow"` (default) or `"backforth"`. A third value is read as `"flow"` |
| `from` / `to` | relation | yes | Endpoints: `{ file?, line, token?, occurrence?, symbol? }` |
| `note` | relation | no | 2–5 words, drawn beside the link on hover |
| `parent` | frame | no | The `id` of the frame this one is **called from**. Omit for a top-level frame |
| `callLine` | frame | with `parent` | 1-based line **in the parent's file** where the call happens — see **The call site** |
| `callToken` | frame | recommended with `parent` | The invoking token on `callLine`, copied verbatim |
| `callOccurrence` | frame | no | 1-based, which match of `callToken` on that line. Default `1` |
| `relations[]` | frame | no | Links between two places in this frame's code — see **`relations`** |
| `start` / `end` | keep range | yes | The line range to show |
| `role` | keep range | no | `"context"` (default) or `"focus"` — see **`keep`** |
| `frame` | step | one of | The frame this step narrates (with `ranges`) |
| `ranges[]` | step | with `frame` | The lines this step lights up, inside that frame |
| `frames[]` | step | one of | `[{ frame, ranges }]` — for a step spanning several frames. `frames[0]` is primary |
| `ttsText` | step | yes | **Plain text only.** No markdown, backticks, line refs, or paths |
| `explanation` | step | no | 2–5 word sidebar label. Think tooltip, not prose |
| `note` | step | no | One sentence, rendered as a callout box in the editor — see below |
| `example` | step | no | **One line**, `input → output`, drawn under the `note` — see below |
| `beats[]` | step | no | `[{ line, token, occurrence?, say? }]` — the identifiers this step pulses. See **`beats`** |
| `line` | beat | yes | 1-based line **in the step's primary frame** holding the token |
| `token` | beat | yes | The identifier on that line, copied verbatim |
| `occurrence` | beat | no | 1-based, which match of `token` on `line`. Default `1` |
| `say` | beat | no | A phrase from this step's `ttsText` saying **when** to pulse. Omit to pulse as the audio starts. **Never a sentence number** |

Use `frame` + `ranges` for the ordinary single-frame step. Use `frames` only when a step genuinely spans two
frames at once. **Never both.**

## Field names — the #1 failure

Use `frames` / `steps` / `keep` / `ranges` / `role` / `scene` / `parent` / `callLine` / `callToken` /
`start` / `end` / `ttsText` / `title` / `note` / `example` / `relations` / `beats` / `line` / `token` / `say`.

**Never** `segments`, `highlights`, `startLine`, `endLine`, `lines`, `label`, `subHighlights`, `text`,
`comment`, `description`, `sample`, `links`, `arrows`, `edges`, `children`, `linkFrom`, `callSite`, or
`elide`. These decode to nothing and the walkthrough renders as if you never wrote them.

Two renames worth calling out if you have seen an older plan: what used to be a **segment** is now a
**frame**, and what used to be a **highlight** is now a **step** in the top-level `steps` list. A frame no
longer carries its own step list, and it no longer has `start`/`end` — its extent is whatever its `keep`
ranges cover.

## Read the reply

Sending a plan returns `{"ok":true,"frames":N,"steps":N}` — and an `issues` array whenever something in the plan
was dropped, degraded or over a cap:

```json
{"ok":true,"frames":3,"steps":7,
 "issues":["[warn] frame.call-line-not-kept — frame 2 is called from line 140 of frame 1, which frame 1 does not keep; …",
           "[info] frame.focus-inferred — frame 0 marks no range as focus; using 68-92 …"]}
```

**Read it.** `[error]` means something is *missing from the picture* — a frame the reader will not see, a
step that will not be narrated — and it is nearly always a fixable authoring mistake: a path that does not
resolve, a step naming a frame id that does not exist, a duplicate `id`. Fix it and send it again. `[warn]` means it
renders differently from what you wrote. `[info]` is a soft cap or a value the plugin had to derive for you.

## `keep` — the lines a frame shows

A frame is **one file plus the lines kept visible in it**. Everything else is folded away behind a
`⋯ N lines ⋯` placeholder. You state what to show; the plugin picks the placeholder and the elision — the
same division of labour as `relations`: *you state the semantics, the plugin picks the picture.*

Two roles:

- **`"context"`** (the default) — lines that let the reader place the code: the class header, the method
  signature, a field the focus refers to. Drawn de-emphasised.
- **`"focus"`** — **the excerpt of interest**: the part of the file this frame is really about. In the tree
  it decides the node's height and the line it opens on. **Mark exactly one range `focus`** per frame.
  If you mark none the plugin guesses (the second kept range) and says so in `issues`; if you mark several,
  the first wins.

Guidance:

- **List the header lines yourself.** The plugin will not derive them: it never uses PSI or the language
  backend, so "the enclosing class and method declaration" is not something it can look up. A typical frame
  is `[class header line, focus range]` — measured across the reference fixture, the first kept line is
  usually 9–18, never line 1.
- **2–3 ranges per frame.** One for the class header, one for the member, occasionally one for a field or a
  constant the member depends on.
- **Keep the call line.** If another frame declares `parent: <this frame>` and `callLine: N`, then line `N`
  must be inside this frame's `keep` — see below.
- **Start a member's range at its signature.** At each step the plugin folds the code that step does not
  use (see **What a step folds** below), and keeps the lines that place it: the signature and the `if` / loop
  headers around it. It can only show lines you kept, so a range starting mid-method leaves a step with
  nothing above it.
- **Do not list what you are not going to narrate.** A frame nobody's step names renders as structure with
  no narration; that is legal (and reported as `[info]`), but it is usually a leftover.

## The call site — how a frame nests inside another

A child frame is drawn **inside its parent's editor, directly under the line that calls
it**, with a leader from the invoking token down to the child's frame. Three fields say where:

```json
{ "id": 1, "parent": 0, "callLine": 85, "callToken": "SomeSysCall", … }
```

- **`parent`** is the calling frame's `id`. A frame has **at most one parent** — it is drawn in one place. If
  a callee is genuinely called from two sites that both matter, make it **two frames** (two ids, one file —
  that is legal) or leave it at top level.
- **`callLine`** is the line in the *parent's* file. It must be a line the parent `keep`s: a call line the
  parent elided would put the whole child frame inside a collapsed fold, where the reader cannot see it. The
  plugin detects this and renders the child at top level instead, with a `[warn]`.
- **`callToken`** is the invoking token *on that line*, copied verbatim — the plugin boxes exactly those
  characters. Give it whenever two calls can share a line (`_pricing.Handle(order, verdict)` and
  `_audit.Handle(order, priced)` are two different call sites on two lines, but the same pattern happens on
  one). Without it the whole trimmed line is boxed. Add `callOccurrence` only when the same token appears
  twice on the call line.
- **Omit all three** for a top-level frame. Frames with no parent stack vertically, which is the right shape
  for "here are three unrelated places" — nesting is for a genuine caller→callee chain.

## The caps — authoring rules

These are measured limits, not style advice. Each one has a defined behaviour on the other side of it, and
the plugin reports whenever you cross one.

| Rule | Limit | What happens past it |
|---|---|---|
| **Nesting depth** | **3 levels** — a root plus two levels of nesting | The over-deep frame is rendered **at top level instead of nested**, with a `[warn]`. Depth compounds: a node's height is its own excerpt *plus* its children at full height, so a fourth level puts a few hundred pixels of code inside roughly two thousand and the reader is scrolling a container to find a container |
| **Nested frames in one parent** | **6** | A taller parent, and nothing worse. Siblings are additive rather than nested. Three read cleanly on screen; **nothing above 3 has actually been rendered**, so 6 is an extrapolation from that case |
| **`keep` ranges per frame** | **3** | A taller frame. Elision is per-gap so the mechanism does not care, but nothing above 3 has been tested |
| **Parents per frame** | **exactly 1** | Not expressible: `parent` is a single field. Two call sites means two frames |
| **Cycles** | **forbidden** | Frame A parented to B parented to A is rejected at load: the link that closes the loop is cut, that frame becomes top-level, and you get a `[warn]` |
| **A step's ranges** | **inside its frame's `keep`** | A few lines just past a kept range (**at most 6 added**, gap included) and the plugin widens that range for you: `[info] step.keep-widened`, nothing to re-send for. Further away, the step highlights a **fold placeholder** — it renders, it is simply pointing at code the frame elided, and it reads as the plugin having dropped the highlight: `[warn] step.range-outside-keep` |
| **Lines on screen** | **~60 per scene** | Nothing is clamped; it is a `[info]` on that scene. Every frame *of a scene* is on screen together, so this — not any single frame's size, and not the whole plan's total — is the budget that binds. **60 is a measured viewport**, not arithmetic: the tree renders in a tool window beside the sidebar, which shows 25–55 lines of code depending on the monitor and the zoom, and the cap sits at the top of that range. For reference, a three-frame chain keeping a header plus a ~25-line member each lands at ~66, just over this cap — the one shape it overstates, since at a nested step the ancestors fold to their headers and the call line. **The remedy is to keep less per frame**, or to split the scene where the nesting lets you. **Counted as: every line inside every `keep` range of every frame in the scene** — blank lines and comment lines included, because they occupy rows on screen. In a heavily commented file the doc comment above a member can be a third of what that frame spends |
| **Frames per scene** | **the depth, sibling and line caps above** | There is no separate number. A scene is one tree, so the tree's caps *are* the scene's caps |
| **Scenes per plan** | **no cap** | Each crossing rebuilds the tree, which is cheap and measured. Cross scenes because the subject moved, not to be tidy |

**Nodes a step does not use are folded away — over budget or not.** Every nesting whose subtree the current
step does not narrate collapses to a one-row stub, and stepping into one re-expands it; nothing is dropped and
no `keep` range is second-guessed. A frame holding the far end of a `relations` link counts as used, so a
relation never comes out with one end drawn and the other inside a stub.

**What a step folds.** Inside the frames that stay open, the step folds lines too — never what it narrates,
never a call line or a link end, never the headers of the blocks those sit in. The frames *above* a nested
step shrink to their headers and the call line. In the step's own frame, a long link's stretch folds (and,
at the reader's choice, the members the step is not in, or everything else). A reader can open any fold.

**So the budget still counts every kept line, but depth costs less than it did.** At a nested step its
ancestors are a few rows each, not their whole `keep`; at a step in the root, untouched children are stubs.
What fills the screen is the frame a step is in — so keep each frame to what its steps need, and do not
trim a callee out of a chain only to save its parent's lines.

**Do not budget against pixels.** Frame heights are the plugin's business entirely: a nested frame is sized
to its focus range, a top-level one grows to its content, and the reader can drag any of them. The caps you
can actually control are **depth and ranges**.

**Fold state is never in the plan.** You say which lines to *keep*; what else is folded, at which step, is
not yours to state (the IDE's own daemon adds 13–25 fold regions per frame on its own).

## Scenes — the frames on screen together

Every plan renders as a **tree of code**: the frames you send, each child drawn inside its parent's editor at
the call site. There is no `view` field to set. A one-frame plan is a one-node tree, which is the right and
normal shape for *"explain this method"*.

A **scene** is the set of frames on screen at one time. Frames sharing a `scene` are built together; a step in
another scene **replaces the whole tree** with that scene's frames.

```json
"frames": [
  { "id": 0, "scene": 0, "file": "…/Parser.cs",   "title": "Parser.Parse",    "keep": [ … ] },
  { "id": 1, "scene": 0, "file": "…/Lexer.cs",    "title": "Lexer.Next",
    "parent": 0, "callLine": 42, "callToken": "_lexer.Next", "keep": [ … ] },

  { "id": 2, "scene": 1, "file": "…/Emitter.cs",  "title": "Emitter.Write",   "keep": [ … ] },
  { "id": 3, "scene": 1, "file": "…/Buffer.cs",   "title": "Buffer.Flush",
    "parent": 2, "callLine": 88, "callToken": "_buffer.Flush", "keep": [ … ] }
]
```

Steps 1–4 name frames 0 and 1 and the reader sees the parser and its lexer. Step 5 names frame 2, and the
parser pair is replaced by the emitter pair.

**Use one scene** — omit the field entirely — when the whole explanation is one place and its callees. That is
most plans, and a plan that never mentions `scene` behaves exactly as if scenes did not exist.

**Use several** when the walkthrough moves on: *"first how a request is parsed, then how it is emitted"* is
two subjects, and the emitter half does not need the parser still on screen. This is what lets a long
explanation stay readable — each scene is inside the line budget on its own, where all the frames together
would not be.

**How many, and where the boundary falls.** Every cap in this file converges on the same shape, so use it as
the target rather than deriving it each time: **a scene is about three frames** — a root plus two levels of
nesting — **each keeping a header and a ~18-line member**, which lands near 57. A ~25-line member each puts
that same chain at ~66, just over the cap — which counts all three, though at a nested step its ancestors fold
to a few rows each. A walkthrough over twelve files is
therefore **four scenes of three**, not one tree of twelve; the latter is over every budget here at once, with
no remedy that does not cut content.

**Let the subject pick the boundary, not the arithmetic.** If a scene comes out over budget, the tempting move
is to cut it wherever the line count crosses 60 — which puts a narrative break in the middle of a thought, and
often separates a call from the frame it calls. That is a signal your **frames** are too fat, not that you need
another scene: trim what each one `keep`s. A 70-line member does not have to be shown whole to be explained.

**Splitting one member across two frames** is not forbidden — two frames may share a file, and nothing checks
whether they overlap — but know what it costs before you do it: there is no field that says *these two frames
are two halves of one method*, so the reader meets the same signature row twice with nothing connecting them.
Prefer a tighter `keep`. If you must split, say so in both frames' `title` and in the `ttsText`, because the
schema cannot say it for you.

Three rules, and the plugin reports every one of them rather than guessing:

- **`parent` must be in the same `scene`.** The parent's node does not exist while the child is showing, so
  there is nothing to nest into: the link is cut, the frame renders at top level, and you get a
  `[warn] frame.parent-other-scene`.
- **A multi-frame step must stay in one scene.** `frames[0]`'s scene wins and the other references are
  dropped with a `[warn] step.frames-cross-scene`.
- **A scene is rebuilt, not restored.** Returning to a scene you left builds it fresh — anything the reader
  had collapsed or resized in it is back at its default. Returning is fine; just do not treat it as free.

## `note` — the in-editor callout

Optional. When present, the plugin draws it as a small box docked above the step's lines in the editor, so it
sits next to the code it describes. `explanation` is a 2–5 word label and reads thin in a box, so `note`
wins when both are given.

Write a `note` when a one-line aside in the code margin would genuinely help — a constraint, a gotcha, the
reason behind a magic number, a consequence that isn't visible locally:

```json
{ "frame": 0, "ranges": [{ "start": 42, "end": 45 }],
  "ttsText": "The retry budget is capped at three…",
  "explanation": "Retry budget",
  "note": "Capped at 3 so a broken transaction can't loop forever." }
```

Guidance:

- **One sentence.** It is rendered on up to 3 wrapped lines and then ellipsised, so anything longer is
  silently cut. Prose belongs in `ttsText` (spoken) or the frame `explanation` (sidebar).
- **Plain text.** No markdown — it is drawn directly, so backticks and asterisks appear literally.
- **Skip it when it would just restate the line.** Sparse and useful beats present-on-every-step; a callout
  on every step becomes wallpaper the reader stops seeing.
- **Don't duplicate `ttsText`.** The user hears that. The note is for what's worth *reading* while looking
  at the code.
- Only the **primary** frame of a step gets a callout.

## `example` — a one-line worked illustration

Optional. **One line** showing the code in action — an input→output pair, a call and its result, a
before/after. It is drawn in the same callout box as `note`, directly under it, in the editor's upright
font so it reads as code rather than prose:

```json
{ "frame": 0, "ranges": [{ "start": 42, "end": 45 }],
  "ttsText": "Parse accepts a year-month and returns the first of that month…",
  "explanation": "Month parsing",
  "example": "Parse(\"2024-01\") → DateOnly(2024, 1, 1)" }
```

Guidance:

- **One line. This is a hard limit** — the callout word-wraps, so extra lines would be reflowed into a
  run-on paragraph. The plugin keeps the first line and appends `…`; everything after it is lost. Do not
  send a fenced block, a diff, or a multi-statement snippet.
- **Short enough to read at a glance.** It wraps to at most two lines in the box and is then ellipsised.
  If it needs more than about 70 characters, it is explaining too much — put that in `ttsText`.
- **No markdown and no fences.** It is drawn directly, so backticks and ``` appear literally.
- **Concrete values, not placeholders.** `Parse("2024-01") → DateOnly(2024, 1, 1)` teaches; `Parse(input)
  → result` does not. Use `→` for "yields".
- **Only where a concrete case genuinely clarifies** — a tricky transformation, a non-obvious default, an
  edge case, a surprising return shape. As with `note`, sparse beats always-present.
- `note` and `example` can both be present, and then the box shows both — but that box is up to five lines
  tall and pushes the code down, so only do it where both really earn their place.

## `beats` — the identifiers this step pulses

Optional. A beat briefly pulses **one identifier**, so the eye lands on the right word instead of scanning
the whole range. A step may have several, and each can be timed to a point in the narration.

```json
{ "frame": 0, "ranges": [{ "start": 42, "end": 45 }],
  "ttsText": "The retry budget is capped at three…",
  "beats": [ { "line": 43, "token": "maxRetries" } ] }
```

That is the commonest shape: one identifier, pulsed as the step's audio starts. **`line` + `token` +
`occurrence` is the same anchor `relations` endpoints and `callToken` use** — a line, a word on it, and
which match when it repeats. There is one such mechanism in the plugin and this is it.

### `line` and `token`

- **The line is 1-based and lives in the step's primary frame.** If it is not among the lines the step
  lights, you get `step.beat-line-outside-ranges` — a warning, not a rejection: the pulse still fires, but
  it lands away from the highlight, which is usually a miscount.
- **Copy the token verbatim out of that line**, including case. Matching is exact and prefers a standalone
  whole word, so `"rate"` finds the variable `rate` before the `rate` inside `rateTable`. If the name
  appears twice on the line and you mean the second, add `"occurrence": 2`.
- **One identifier, not a phrase.** `"maxRetries"`, not `"the maxRetries constant"`. A short expression is
  fine when that is genuinely the subject (`"?? 0"`), but prefer a name.
- **A token that isn't on that line does not pulse**, and nothing else about the step changes. The reply
  names it (`step.beat-token-not-on-line`) and quotes the line as it actually reads, so fix it and send it again.
- **Only where a step has an obvious focal word.** A step about control flow as a whole has none — omit
  `beats` entirely.

### `say` — when the pulse fires

Omit it and the pulse fires as the step's audio starts. Give it a phrase from this step's own `ttsText` and
the pulse waits until the narration reaches it:

```json
{ "frame": 0, "ranges": [{ "start": 42, "end": 51 }],
  "ttsText": "The dispatcher looks up the handler. If it is missing it throws, so the caller sees a clear failure.",
  "beats": [
    { "line": 44, "token": "TryGetValue", "say": "looks up the handler" },
    { "line": 48, "token": "throw",       "say": "if it is missing" }
  ] }
```

**A phrase, never a number.** There is no `"sentence": 2` and there will not be one — you, the speech
synthesizer and the player each divide narration differently (the synthesizer splits on a full stop
followed by a space, so `e.g.` starts a new piece, and it re-splits anything over 320 characters at
commas), and nothing downstream can notice the disagreement. A phrase needs no agreement.

Guidance:

- **Copy the phrase out of your own `ttsText`.** Matching ignores case and collapses runs of whitespace,
  and is otherwise exact. A phrase the narration does not contain is reported as `step.beat-unspoken` and
  that beat is dropped; the rest of the step is unaffected.
- **Quote from the start of the thought, not the end.** A beat fires at the beginning of the *sentence*
  containing its phrase, because that is the only instant known exactly — no speech engine here reports
  per-word timings, so a word's position inside its sentence is an estimate worth about a second. Quoting
  the opening words gives the tightest result; quoting the last words fires early.
- **One beat per sentence.** Two beats whose phrases land in the same spoken sentence fire at the same
  instant and the second erases the first, so only the first fires. Split the sentence if you need two.
- **At most one beat may omit `say`** — they would all fire on the same instant. The extras are reported as
  `step.beat-many-at-start` and dropped.
- **Three or four beats at most.** A pulse every few seconds stops reading as emphasis.
- **With narration switched off there is no clock**, so only the beat without a `say` can fire. A step whose
  beats are all timed shows a reader with audio off nothing — which is fine when the step has several
  pulses and no single focal word, and worth avoiding when it has one.

## `relations` — links between two places in the code

Optional, and it lives on the **frame**, not the step, because a relation spans lines that belong to
different steps. Each entry states that two places are connected; the plugin decides how to show it, along
two independent axes:

**What each end hangs off** — a **token** when that end names one that is really on its line (the token is
tinted, and the connector leaves the tint's own corner), a **line end** otherwise (the connector leaves the
end of the row's text and turns onto the same rail). Decided per end, so a relation may be token-anchored at
one end and line-anchored at the other.

**How the two ends meet** — a **drawn line** when both are lines of this frame's file, on different lines,
within ~40 rows of each other: one polyline routed clear of the code, out of one end and into the other.
**A portal at each end** otherwise — another file, a target named only by `symbol`, a line this file doesn't
have, ends too far apart. A portal is a small flattened ring with a hole in it, and the connector **dives
when the other end is below and climbs when it is above**, so each end shows which way its partner lies, with
an arrow on the run saying whether the link leaves from there or arrives. Both portals are the same colour and
sit on the same vertical line.

Either way: hovering either end brightens **both** (and shows the `note` beside the hovered one), and clicking
either lands the caret on the far end's token. A relation that leaves the file also gets a line in the callout
box naming that end (`→ RateTable.Lookup · RateTable.cs:88 — where the rate comes from`).

You do not choose between those, and you should not try to: you can't know how wide the editor is or whether
both ends are on screen. **State the relationship; the plugin picks the picture.**

**A relation whose target is another frame of the same scene is a link between nodes**, and the plugin draws
*both* halves of it. Clicking either chip scrolls the tree to the other node, scrolls that node to the line
and pulses the token — so a relation from a parent into its child, or from one sibling to another, is worth
authoring: it is the only thing that says *these two nodes are connected*. Three consequences for how you
write them:

- **Give both ends a `file`, a `line` AND a `token`.** All three do work now. The `line` is the row a half is
  drawn on; the `token` is what gets tinted, and it must be a **literal substring of that line**. A `symbol`
  alone reads well in the callout but has no row to resolve to, so it gets no portal at either end; a `line`
  with no `token` still draws the link, but from the end of the row's text with nothing tinted.
- **Keep both ends in one scene.** A target in another scene is not on screen, so there is no second half to
  draw and following the link opens the file in a tab. That is a reasonable outcome for a deliberate click,
  but it is not a link between nodes — if you meant one, put both frames in the same scene.
- **Declare each link once.** You do *not* need to author it from both ends to make it visible from both:
  one entry produces one chip in each node, and the glyphs (`→` outgoing, `←` incoming) are the plugin's.
  Declaring the same link twice is harmless — the two are recognised as one pair rather than drawn as two —
  but it buys nothing, and the second entry counts against the five per step you have.

A target no frame in the plan holds still works: no pair is drawn, the callout line still names it, and a
click opens that file. That is the right way to point at a leaf you deliberately did not make a node for.

```json
"relations": [
  { "kind": "flow",
    "from": { "line": 44, "token": "raw" },
    "to":   { "line": 51, "token": "parsed" },
    "note": "parsed value" },

  { "kind": "backforth",
    "from": { "line": 60 },
    "to":   { "line": 72 },
    "note": "mutual recursion" },

  { "kind": "flow",
    "from": { "line": 45, "token": "rate" },
    "to":   { "file": "Pricing/RateTable.cs", "symbol": "RateTable.Lookup", "line": 88 },
    "note": "where the rate comes from" }
]
```

**The only two values that mean anything are `"flow"` and `"backforth"`** — `"flow"` for A feeds B,
`"backforth"` for two places that refer to each other. `kind` defaults to `"flow"` when omitted, and a third
value is **not rejected**: it is read as `"flow"`. That second sentence is the one to author against — the set
is advice, not a constraint the decoder enforces, so a typo costs a nuance rather than the plan.

> **What `kind` changes in the picture.** A `flow` link *arrives* at one end: that end gets an arrowhead on
> its token and its rail indicator points back at it, while the other end's points away. A `backforth` link
> arrives at **both** ends, so both get the arrowhead and both indicators point outward. Nothing else differs —
> same colour, same lane, same routing, same hover — so the choice is a nuance and never a layout decision.
> (It went a while drawing nothing at all, between the link system being unified and the two ends being told
> apart again. If you are reading an older plan whose `backforth` looks like a `flow`, that is why.)

An **endpoint** is `{ file?, line, token?, occurrence?, symbol? }`:

- `file` — omit for this frame's own file, which is the usual case. A different file makes the endpoint
  "outside", so the relation renders as a portal pair. Relative to the project root is fine.
- `line` — 1-based, in `file`. Required for anything that should be drawn or navigated to; omit only for an
  off-screen target you can name but can't locate.
- `token` — a literal substring **of that line**. Optional, and it does real work: it is what gets tinted, it
  is what a click lands the caret on, and it names the endpoint in the callout line and the hover. An end that
  has one is drawn from the token; an end that does not is drawn from the end of the row's text. A token that
  isn't on that line costs the tint and nothing else — so it is still worth adding as a check on a line number
  you worked out by counting, but a miscount no longer silently removes the link.
- `symbol` — a label for an off-screen target (`"RateTable.Lookup"`). Display only; it is never resolved.

Guidance:

- **One endpoint must fall inside the step the relation belongs to.** Relations are drawn per *step*: a
  relation shows only while a step whose ranges contain `from` or `to` is active. A relation between two
  lines that no step covers is never seen.
- **At most five on any one step**, and only where the link *is* the point. Each link on a step gets its own
  routing lane and its own colour, and there are five of each; anything past the fifth on a single step is not
  drawn at all. Two or three is still the number that reads well — five is what the drawing survives, not what
  it flatters. **Where they start matters as much as how many**: five links leaving *one* line make that line
  grow to hold five separate routes, where five links leaving five different lines cost nothing at all. Spread
  them if the code lets you.
- **Keep both ends close** when you want an arrow — one frame, ideally within 40 lines. Farther apart in one
  frame is fine: from ~12 lines the plugin folds the stretch between for that step.
- **`note` is 2–5 words**, plain text, no markdown. It is drawn beside the link while it is hovered, so a
  sentence gets ellipsised.
- **Get the line numbers right.** Two lines joined by a link that don't actually relate is worse than no
  link, and the `line` is what decides where a link is drawn.
- **Skip it entirely** when the code is linear. Most frames have no relation worth drawing.

**What `issues` tells you about relations.** The loader reports every way a
relation can fail to be **drawn**:

| Code | What it caught |
|---|---|
| `relation.endpoint-not-kept` | an end on a line the frame elided, often one only another scene keeps. It would land on a `⋯ N lines ⋯` placeholder, so the plugin drops the relation and says so at `[info]` — nothing to re-send for |
| `relation.no-step` | no step's **first** range holds either end, so the relation is never on screen at all |
| `relation.step-exceeded` | more relations on one step than there are routing lanes; the ones past the cap are not drawn |
| `relation.endpoint-unnamed` | an end with neither a `line` nor a `symbol` — nowhere to draw it and nothing to call it |
| `relation.unknown-kind` | a `kind` that is neither `flow` nor `backforth`, read as `flow` |

**The reply also checks the plan against the code.** The plugin opens every file the plan names and reports
what does not match:

| Code | What it caught |
|---|---|
| `frame.file-unresolved` | a frame's `file` that does not exist — the frame is not rendered (`[error]`) |
| `frame.keep-past-eof` | a kept range running past the last line of the file |
| `frame.call-token-not-on-line` | a `callToken` that is not on its `callLine` |
| `step.beat-token-not-on-line` | a beat `token` that is not on its `line` — the pulse never happens |
| `relation.token-not-on-line` | an endpoint `token` that is not on its `line` |
| `relation.line-past-eof` | a token-less endpoint on a line the file does not have |
| `relation.file-unresolved` | an endpoint `file` that does not exist |

Each miss quotes the line as it actually reads. A token that is not on its line almost always means the
*line* was miscounted, and a miscounted line still draws the link, in the wrong place, saying something
false about the code. So **write a `token` on every endpoint**: it is what makes the check possible. **Do not
write your own validator script** — the reply already does this check, against the text the reader will
see.

## Steps — the narration order

- **The order is yours.** Steps play in the order you write them, and a step may return to a frame an
  earlier step already visited. That is the whole reason `steps` is a separate list from `frames`.
- **One frame per step, normally.** Use the `frames` array only when the point of the step is the
  *relationship* between two frames (a value crossing a call boundary). `frames[0]` is primary: it gets the
  callout, the token flash and the scroll.
- **1–4 lines per range**, and usually one range. Group related lines (a condition and its body, a variable
  and its use). Two disjoint ranges in one step are kept as two — the plugin does not join them — so use
  that where the step is genuinely about two places in one file.
- **`ttsText` flows.** All of a walkthrough's narration is spoken as one continuous stream, with the visual
  attention point moving as each step's audio finishes. Write connectives ("Next," "From here," "Once that's
  done,") rather than restating context.
- **6–12 steps** for a deep dive on one frame; fewer for wiring and configuration.

## Language

`language` is the walkthrough's single language code: `en`, `es`, `fr`, `hi`, `it`, `pt`, `ja`, `zh`.
Default `"en"`. **One language per walkthrough.**

If asked for another language (or the prompt carries a **LANGUAGE:** instruction), set `language` to that
code and write ALL prose in it — `title`, every frame `title` / `explanation`, and every step `ttsText` /
`explanation` / `note`. Keep code identifiers, file paths, and API names verbatim; never translate code.
**`token` and `callToken` are never translated** — they have to match the source text exactly to resolve.
`example` is code too: keep the call, the values and the `→` as they are. A relation's `note` is prose and
gets translated; its `token` and `symbol` do not.

## Sending it

A run the IDE plugin started headlessly sends it with the `mcp__audio-explainer__set_plan` tool, the plan as the tool's
input — no file, no shell — whenever it has that tool.

Otherwise, write the JSON to a temp file **outside the project**, then POST the file — never pass JSON inline on a
command line, where shell quoting corrupts embedded quotes and newlines. A payload written inside the
project can land among the user's saved walkthroughs. See `docs/explain-at-cursor.md` (headless) or
`docs/walkthrough.md` (interactive) for the helper scripts and the exact call. Then read the reply's
`issues` array, as above.

To change a plan that is already loaded, send a **new `set_plan`**. There are no partial-update messages:
you are holding the whole plan, and one send is cheaper than keeping the plugin's copy and yours in step.
