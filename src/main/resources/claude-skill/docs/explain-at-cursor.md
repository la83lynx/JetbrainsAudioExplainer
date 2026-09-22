# Explain at Cursor (headless, plugin-triggered)

This is the entry flow when the **Audio Explainer IDE plugin** invokes you headlessly (`claude -p`)
because the user right-clicked **"Explain This (Audio)"** in their editor. It is a variant of the main
`SKILL.md` checklist, specialised for a single cursor target and a non-interactive run.

## Golden rules for this mode

- **You are fully autonomous. There is NO interactive user.** Never call `AskUserQuestion`, never wait
  for input, never stop to ask permission. Make sensible choices and finish the job in one pass.
- **The sidebar/editor is already live.** Your ONLY user-visible output is the walkthrough plan you POST
  to the loopback server. Do **not** print the walkthrough as terminal text. When done, print just a
  one-line status (e.g. `Sent walkthrough: <title> (<N> frames, <N> steps)`).
- **Do the symbol expansion yourself.** The plugin does NOT resolve the enclosing method/class — that is
  your job. You are given a file + cursor line (and maybe a selection); read the file and decide the
  boundaries.
- **Prefer a lean single pass.** A cursor target is usually one method/class. Do the reading + step
  generation yourself (Overview-style). Only dispatch a `SMALL` scout sub-agent if you genuinely need to
  follow the call chain into other files to tell the story. Do not run the parallel Deep-Dive machinery
  for a single symbol.
- **A plan is `frames` + `steps`.** Frames are the code on screen (one file plus the lines kept visible in
  it); steps are the narration, in order, naming the frame each one is about. `docs/plan-schema.md` is the
  contract — read it before writing the plan, every time.
- **Send the first steps early and append the rest** (step 5). The reader hears step 1 while you are writing
  step 6, instead of hearing nothing until you are done.

## Context you are given

The plugin passes a JSON object in the prompt:

```json
{ "file": "<project-relative or absolute path>", "caretLine": 42, "selectionText": "<text or null>",
  "selectionStartLine": 40, "selectionEndLine": 55, "projectRoot": "<abs path>" }
```

Your working directory is already the project root, so a project-relative `file` resolves directly.

**Target scope.** The user may invoke this from the editor (a cursor target) or from the Solution
Explorer (a whole file, or a folder/project). A `"scope"` field tells you which:

- **no `scope` / `"scope"` absent** — a cursor target (the `caretLine`/`selectionText` case above).
- **`"scope": "file"`** — explain that whole file. There is no caret; read the file and cover its key
  types, methods, and flow as 1–4 frames. (**A limit of this mode, not of the schema** — a full
  walkthrough has no cap on frames; one cursor position rarely justifies more than a few.)
- **`"scope": "directory"`** — a folder / project / solution node, or a multi-selection. A `"targets"`
  array then lists the selected paths. Give a **lean guided tour**: read the important files, pick the
  pieces worth narrating, and build one frame per place worth showing. Don't try to cover everything —
  favour the entry points and the code that tells the story.

**Depth.** The prompt may carry a `DEPTH: brief` or `DEPTH: deep` line. It says how much walkthrough to
ask for — how many frames and steps, and which of the optional fields to use. **It only ever narrows or
re-weights what `docs/plan-schema.md` already allows; it never widens it and never contradicts it**, so
when the two seem to disagree, the schema wins and the depth line is asking for less of it. No depth line
means the standard shape this document describes throughout (1–2 frames, the optional fields used
sparingly). `brief` is the older, flatter shape: one frame, at most four steps, no `relations`, no nested
frames, no `scene`s and no step `note` / `example` / `beats` — fastest to generate and the one that renders
on the least screen. `deep` is up to four frames with those fields welcome wherever they teach something.

**Extra instructions.** The prompt may end with a block of the user's own text for that one run. Follow it
where it narrows or restyles the walkthrough ("use simple terms", "skip the error handling"). It does
**not** relax anything here: the field names, the line-range rules and the POST are fixed whatever it
asks, because the plugin needs the walkthrough back as data — printing it as prose reaches nobody.

## Steps

1. **Locate the target.**
   - **File/directory scope** (see above): there is no caret — read the target file(s)/folder and choose
     the coherent units worth narrating (for a directory, a handful of key files; for a file, its main
     types/methods). Skip to building the plan.
   - If `selectionText` is present, the target is that line range (`selectionStartLine`–`selectionEndLine`),
     expanded outward to whole-statement / whole-method boundaries.
   - Otherwise, read around `caretLine` and expand to the **enclosing method/function** (or the class/type
     if the caret is at type level). Use `Read` with `offset`/`limit` — don't slurp the whole file.
   - Keep scope tight: a frame should be a coherent unit. The budget is **~60 lines per *scene*** (every
     frame of a scene is on screen together — `docs/plan-schema.md` binds), which in this mode usually
     means one or two frames anyway; split into
     multiple frames if the enclosing unit is larger.

2. **(Optional) Scout for context.** If understanding the target requires its immediate callers/callees in
   other files, dispatch ONE `SMALL` (`Explore`) sub-agent per `docs/scan.md` to map them. Skip this for
   self-contained code.

3. **Build the plan.** Produce 1–4 frames (usually 1–2) covering the target and any essential supporting
   code, plus the `steps` that narrate them. Steps are dense and pointer-style per the rules in
   `docs/segments.md` (flowing `ttsText`, skip boilerplate, explain the *why*). Even though audio is not
   played yet, always write good `ttsText` — the steps double as the reading order and the TTS phase will
   speak them.

   **Schema: read `docs/plan-schema.md` — it is the single source of truth** for the `set_plan` shape, the
   exact field names, `keep` and its `context`/`focus` roles, the caps, the `language` rule, the optional
   per-step `note` / `example` / `beats`, and the optional per-frame `relations`. Do not work from memory or
   from examples in other docs; the plugin drops unknown fields silently, so a wrong field name fails
   quietly.

   Frame `id`s are sequential from 0. `file` should be the **absolute path** (or the exact path you were
   given) so the editor can open it. Each frame's `keep` lists the lines the reader sees — the class/method
   header rows as `context`, the member itself as `focus`. **Every line a step lights must be inside its
   frame's `keep`.**

   Where a one-line aside next to the code would genuinely help the reader — a constraint, a gotcha, the
   reason behind a magic number — add a `note` to that step; the plugin renders it as a callout box
   in the editor. Keep it to one sentence, plain text, and skip it when it would only restate the line.

   Where a step is really about **one identifier**, add a beat — `{ line, token }`, the name copied verbatim from the
   lines the step lights; the plugin flashes it so the eye lands on the right word. Omit it when the step has
   no single focal name.

   Where a concrete case would clarify a tricky transformation or a surprising result, add a one-line
   `example` with real values (`Parse("2024-01") → DateOnly(2024, 1, 1)`); it is drawn under the `note` in
   the same box. **One line only** — extra lines are dropped. All three fields are optional, and sparse
   beats always-present — see `plan-schema.md`.

   Where two places in the code are genuinely linked — a value produced on one line and consumed on another,
   a call whose target lives in another file — add a frame-level `relations` entry (`{"kind":"flow","from":
   {"line":44,"token":"raw"},"to":{"line":51,"token":"parsed"},"note":"parsed value"}`). The plugin draws an
   arrow between nearby lines of the same file and a matched pair of coloured portals for anything else. **The
   hard cap is 5 on any one step** and the sixth is dropped silently; 2-3 is what reads well. One endpoint must
   fall inside the step it belongs to, and the line numbers have to be ones you checked — no relation mistake
   is ever reported in `issues`.

   **Do not send `view`** — every plan renders as a tree of code, and one frame is a one-node tree, which is
   the normal shape for a single method. When the explanation *is* a caller→callee chain, give each nested
   frame `parent` / `callLine` / `callToken`, and remember the call line has to be a line the parent keeps.
   When it moves on to a different subject, put the later frames in a different `scene` so the earlier files
   leave the screen. `docs/plan-schema.md` has the depth cap, the scene rules and the rest.

4. **POST the plan.** ALWAYS write the full `set_plan` JSON to a temp file first (use the `Write` tool),
   then send that file. **Never pass the JSON inline on a command line** — shell quoting will corrupt it
   (embedded quotes/newlines break `curl -d '<json>'`). Send the file with the helper for your platform
   (both live in this skill's `scripts/` dir):

   - **Windows:** `powershell -NoProfile -ExecutionPolicy Bypass -File "<skillDir>/scripts/explainer.ps1" plan "<planFile>"`
   - **macOS/Linux/git-bash:** `<skillDir>/scripts/explainer.sh plan "<planFile>"`
   - **Fallback (any platform with curl):**
     ```
     curl -s -X POST "http://127.0.0.1:$(cat ~/.claude-explainer-port)/api/message" \
       -H "Authorization: Bearer $(cat ~/.claude-explainer-token)" \
       -H "Content-Type: application/json" --data-binary @<planFile>
     ```

   A `{"ok":true,"frames":N,"steps":N}` response means the walkthrough is loaded; the tree-of-code view and the
   sidebar update automatically. A `{"error":...}` or connection failure means the plugin server isn't
   reachable — print that error and stop.

   **The reply may also carry an `issues` array** — everything the plugin dropped, degraded or found over a
   cap. An `[error]` there means the reader is missing part of what you wrote (a path that did not resolve, a
   step naming a frame that does not exist): fix it and re-POST. `[warn]` renders differently from what you
   authored. `[info]` is a soft cap or a value the plugin derived for you.

5. **Stream the rest** (strongly preferred). Do not make the reader wait in silence for the whole plan.
   Put `"streaming": true` on the `set_plan` above, send it with only your **first 2-3 steps**, and then
   append the rest in batches as you write them:

   ```json
   { "type": "append_steps", "steps": [ …the next few… ] }
   { "type": "append_steps", "steps": [ …the last few… ], "final": true }
   ```

   Sent with the **same helper and the same verb** as the plan (`explainer.ps1 plan <file>` /
   `explainer.sh plan <file>` — it POSTs whatever message the file holds), so each batch is another `Write`
   plus another send.

   **Why it is worth the extra POSTs:** the walkthrough starts playing the moment the first batch lands, so
   the reader is listening to step 1 while you are still writing step 6. The run takes exactly as long
   either way — what changes is that they spend it *hearing the explanation* instead of watching a spinner.

   The constraints, in full:

   - **All frames go in the `set_plan`.** `append_steps` carries `steps` and nothing else. Decide the frames
     first — they are the code on screen, the plugin builds it once, and a frame arriving later would rebuild
     the view under a reader who is in the middle of it.
   - **Append-only and in order.** A step you sent is already being narrated: it cannot be edited, replaced
     or re-ordered, and re-POSTing `set_plan` mid-stream replaces the whole walkthrough instead of extending
     it.
   - **Always finish with `"final": true`**, even if the last batch is empty
     (`{"type":"append_steps","steps":[],"final":true}`). Until it arrives the walkthrough stays open and
     waits at its last step. (If your process dies first the plugin closes it for you — but then the reader
     waited for nothing.)
   - Each reply's `issues` covers **only that batch**; anything already reported is not repeated.

   Skip streaming only when the whole plan is 2-3 steps anyway, and for `DEPTH: brief` (at most four steps,
   where the second POST costs more than it saves).

6. **Done.** Do NOT send `goto`/`resume` (the plugin/sidebar drives playback). Print one status line.

## Reminders

- The plugin loads the plan into a **paused** walkthrough positioned at the first step — the user presses
  Play in the sidebar. Sending `set_plan` is enough; nothing else.
- **Streaming changes nothing about that.** The walkthrough still lands paused on step 1 and still waits for
  Play; the only difference is that the steps after your first batch arrive while the reader is already
  listening. If they reach the last step you have sent, playback waits there for the next batch rather than
  ending.
- If the server is unreachable, the discovery files (`~/.claude-explainer-port`, `~/.claude-explainer-token`)
  are missing — the plugin isn't running. Report it plainly; don't retry in a loop.
- Field-name mistakes are the most common failure: it is `frames`/`steps`/`keep`/`ranges`/`start`/`end`/
  `ttsText`, never `segments`/`highlights`/`startLine`/`endLine`/`label`/`subHighlights`.
  `docs/plan-schema.md` is authoritative.
- A **LANGUAGE:** line in the prompt means translate all prose to that language (see step 3) and set the
  top-level `"language"` code — but never translate code identifiers, paths, or API names.
- A **DEPTH:** line narrows the plan's shape; it is never permission to break the schema. No line = standard.
