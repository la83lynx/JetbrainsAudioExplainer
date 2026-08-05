# `set_plan` wire schema — SINGLE SOURCE OF TRUTH

**This file is authoritative.** Every other doc and prompt in this skill points here instead of
restating the schema, because field-name drift between copies is the single most common failure mode.
If you are writing a plan, the shape below is the contract; do not infer fields from examples elsewhere.

The plugin decodes this with `ignoreUnknownKeys`, so an unknown field is silently dropped rather than
rejected — a typo'd field name fails **quietly**. That is why the names matter more than anything else here.

## The message

```json
{
  "type": "set_plan",
  "title": "<short title of what's being explained>",
  "language": "en",
  "segments": [
    {
      "id": 0,
      "file": "<absolute path, or the exact path style you were given>",
      "start": 40,
      "end": 78,
      "title": "<short segment label>",
      "explanation": "<markdown explanation of the whole segment>",
      "highlights": [
        {
          "start": 42,
          "end": 45,
          "ttsText": "<1-2 plain-text sentences, flowing narration>",
          "explanation": "<2-5 word sidebar label>",
          "note": "<optional: one sentence shown as a callout in the editor>",
          "example": "<optional: ONE line, input → output, shown under the note>",
          "token": "<optional: the one identifier this step is about, copied from the code>"
        }
      ],
      "relations": [
        {
          "kind": "flow",
          "from": { "line": 44, "token": "raw" },
          "to": { "line": 51, "token": "parsed" },
          "note": "<optional: what flows, 2-5 words>"
        }
      ]
    }
  ]
}
```

## Fields

**Line numbers are 1-based and ranges are inclusive on BOTH ends.**

| Field | Where | Required | Notes |
|---|---|---|---|
| `type` | message | yes | Always `"set_plan"` |
| `title` | message | yes | Short label for the whole walkthrough |
| `language` | message | yes | Language code — see **Language** below. `"en"` if English |
| `segments[]` | message | yes | 1–4 segments; usually 1–2 for a cursor target |
| `id` | segment | yes | Sequential from 0. Targets later `goto` / `replace_segment` messages |
| `file` | segment | yes | Absolute path preferred, so the editor can open it |
| `start` / `end` | segment | yes | The segment's line range |
| `title` | segment | yes | Short segment label |
| `explanation` | segment | yes | Markdown; the sidebar overview for this segment |
| `highlights[]` | segment | yes | The reading order — see `docs/segments.md` for how to author them |
| `relations[]` | segment | no | Links between two places in the code; the plugin draws them — see below |
| `kind` | relation | no | `"flow"` (default: A feeds B) or `"backforth"` (they refer to each other) |
| `from` / `to` | relation | yes | Endpoints: `{ file?, line, token?, occurrence?, symbol? }` — see below |
| `note` | relation | no | 2–5 words naming what flows or why they're linked |
| `start` / `end` | highlight | yes | Line range for this step |
| `ttsText` | highlight | yes | **Plain text only.** No markdown, backticks, line refs, or paths |
| `explanation` | highlight | no | 2–5 word sidebar label. Think tooltip, not prose |
| `note` | highlight | no | One sentence, rendered as a callout box in the editor — see below |
| `example` | highlight | no | **One line**, `input → output`, drawn under the `note` in the same box — see below |
| `token` | highlight | no | A literal substring of the highlighted lines; the plugin flashes it — see below |
| `occurrence` | highlight | no | 1-based, which match of `token` to use. Default `1`. Only needed when it repeats |

There is **no segment-level `ttsText`**. Narration lives on each highlight. (An old example in
`docs/walkthrough.md` once showed one; it was wrong and has been removed.)

## Field names — the #1 failure

Use `start` / `end` / `title` / `ttsText` / `highlights` / `note` / `example` / `token` / `relations`.

**Never** `startLine`, `endLine`, `label`, `subHighlights`, `text`, `comment`, `description`, `sample`,
`links`, `arrows`, or `edges`. These decode to nothing and the walkthrough renders as if you never wrote them.

## `note` — the in-editor callout

Optional. When present, the plugin draws it as a small box docked above the highlighted lines in the
editor, so it sits next to the code it describes. `explanation` is a 2–5 word label and reads thin in a
box, so `note` wins when both are given.

Write a `note` when a one-line aside in the code margin would genuinely help — a constraint, a gotcha,
the reason behind a magic number, a consequence that isn't visible locally:

```json
{ "start": 42, "end": 45, "ttsText": "The retry budget is capped at three…",
  "explanation": "Retry budget",
  "note": "Capped at 3 so a broken transaction can't loop forever." }
```

Guidance:

- **One sentence.** It is rendered on up to 3 wrapped lines and then ellipsised, so anything longer is
  silently cut. Prose belongs in `ttsText` (spoken) or the segment `explanation` (sidebar).
- **Plain text.** No markdown — it is drawn directly, so backticks and asterisks appear literally.
- **Skip it when it would just restate the line.** Sparse and useful beats present-on-every-highlight;
  a callout on every step becomes wallpaper the reader stops seeing.
- **Don't duplicate `ttsText`.** The user hears that. The note is for what's worth *reading* while looking
  at the code.

## `example` — a one-line worked illustration

Optional. **One line** showing the code in action — an input→output pair, a call and its result, a
before/after. It is drawn in the same callout box as `note`, directly under it, in the editor's upright
font so it reads as code rather than prose:

```json
{ "start": 42, "end": 45, "ttsText": "Parse accepts a year-month and returns the first of that month…",
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
  edge case, a surprising return shape. As with `note`, sparse beats always-present; skip it on steps
  whose behaviour is already obvious from the code.
- `note` and `example` can both be present, and then the box shows both — but that box is up to five lines
  tall and pushes the code down, so only do it where both really earn their place.

## `token` — the identifier to flash

Optional. Names the **one identifier this step is really about**. When present, the plugin finds it
inside the highlighted lines and briefly pulses it, so the eye lands on the right word instead of
scanning the whole range.

It must be a **literal substring of lines `start`–`end`** — copy it out of the code you just read, don't
retype it from memory and don't give a line or column number:

```json
{ "start": 42, "end": 45, "ttsText": "The retry budget is capped at three…",
  "explanation": "Retry budget",
  "token": "maxRetries" }
```

Guidance:

- **Copy it verbatim**, including case. Matching is exact.
- **One identifier, not a phrase.** `"maxRetries"`, not `"the maxRetries constant"`. A short expression
  is fine when that is genuinely the subject (`"?? 0"`), but prefer a name.
- **Only where a step has an obvious focal word.** A 4-line highlight about control flow as a whole has
  no single token — omit the field. As with `note`, sparse beats always-present.
- **Prefer a distinctive name.** Matching prefers a standalone whole word, so `"rate"` finds the variable
  `rate` before the `rate` inside `rateTable`. If the name you want appears several times and you mean a
  later one, add `"occurrence": 2`.
- **A token that isn't found is ignored** — no flash, no error, nothing else about the step changes. So a
  wrong guess is harmless, but it is also silently wasted; verify it's really in those lines.
- **The pulse fires as the step's narration starts**, so it works best when `ttsText` is *about* that token
  from its opening words. A token the sentence only reaches at the end will have stopped pulsing by then.

## `relations` — links between two places in the code

Optional, and it lives on the **segment**, not the highlight, because a relation spans lines that belong to
different steps. Each entry states that two places are connected; the plugin decides how to show it:

- **Both ends are lines of this file, on different lines, within ~40 lines of each other** → it draws a real
  **arrow** from one line to the other, routed clear of the code, with the `note` beside it.
- **Anything else** — another file, a target named only by `symbol`, a line this file doesn't have, ends too
  far apart — → a **clickable pointer** in the gutter plus a line in the callout box
  (`→ RateTable.Lookup · RateTable.cs:88 — where the rate comes from`).

You do not choose between those, and you should not try to: you can't know how wide the editor is or whether
both ends are on screen. **State the relationship; the plugin picks the picture.**

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

An **endpoint** is `{ file?, line, token?, occurrence?, symbol? }`:

- `file` — omit for this segment's own file, which is the usual case. A different file makes the endpoint
  "outside", so the relation renders as a pointer. Relative to the project root is fine.
- `line` — 1-based, in `file`. Required for anything that should be drawn or navigated to; omit only for an
  off-screen target you can name but can't locate.
- `token` — a literal substring **of that line**. Optional but worth giving: the plugin checks it is really
  there, which catches a mistyped line number, and it names the endpoint in the pointer form. If the token
  isn't on that line the relation quietly degrades to a pointer rather than drawing a wrong arrow.
- `symbol` — a label for an off-screen target (`"RateTable.Lookup"`). Display only; it is never resolved.

Guidance:

- **One endpoint must fall inside the highlight the relation belongs to.** Relations are drawn per *step*: a
  relation shows only while a step whose line range contains `from` or `to` is active. A relation between two
  lines that no highlight covers is never seen.
- **At most 2–3 per segment**, and only where the link *is* the point — a value produced here and consumed
  there, a call and its implementation, two halves of a back-and-forth. Three arrows on one step is already
  busy; anything past three on a single step is not drawn at all.
- **Keep both ends close** when you want an arrow — same screenful, ideally within 40 lines. Further apart and
  it becomes a pointer, which is fine, but say `note` in a way that still reads as words.
- **`note` is 2–5 words**, plain text, no markdown. It is drawn beside the arrow, so a sentence gets
  ellipsised.
- **Get the line numbers right.** This is the field with the most room to be confidently wrong: two lines
  joined by an arrow that don't actually relate is worse than no arrow. Add `token` on both ends and the
  plugin will catch a miscount for you.
- **Skip it entirely** when the code is linear. Most segments have no relation worth drawing, and a plan with
  no `relations` renders exactly as it did before the field existed.

## Language

`language` is the walkthrough's single language code: `en`, `es`, `fr`, `hi`, `it`, `pt`, `ja`, `zh`.
Default `"en"`. **One language per walkthrough.**

If asked for another language (or the prompt carries a **LANGUAGE:** instruction), set `language` to that
code and write ALL prose in it — `title`, every segment `title` / `explanation`, and every highlight
`ttsText` / `explanation` / `note`. Keep code identifiers, file paths, and API names verbatim; never
translate code. **`token` is never translated** — it has to match the source text exactly to resolve.
`example` is code too: keep the call, the values and the `→` as they are. A relation's `note` is prose and
gets translated; its `token` and `symbol` do not.

## Sending it

Write the JSON to a temp file, then POST the file — never pass JSON inline on a command line, where
shell quoting corrupts embedded quotes and newlines. See `docs/explain-at-cursor.md` (headless) or
`docs/walkthrough.md` (interactive) for the helper scripts and the exact call.
