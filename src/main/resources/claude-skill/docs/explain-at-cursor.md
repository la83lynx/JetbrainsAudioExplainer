# Explain at Cursor (headless, plugin-triggered)

This is the entry flow when the **Audio Explainer IDE plugin** invokes you headlessly (`claude -p`)
because the user right-clicked **"Explain This (Audio)"** in their editor. It is a variant of the main
`SKILL.md` checklist, specialised for a single cursor target and a non-interactive run.

## Golden rules for this mode

- **You are fully autonomous. There is NO interactive user.** Never call `AskUserQuestion`, never wait
  for input, never stop to ask permission. Make sensible choices and finish the job in one pass.
- **The sidebar/editor is already live.** Your ONLY user-visible output is the walkthrough plan you POST
  to the loopback server. Do **not** print the walkthrough as terminal text. When done, print just a
  one-line status (e.g. `Sent walkthrough: <title> (<N> segments)`).
- **Do the symbol expansion yourself.** The plugin does NOT resolve the enclosing method/class — that is
  your job. You are given a file + cursor line (and maybe a selection); read the file and decide the
  boundaries.
- **Prefer a lean single pass.** A cursor target is usually one method/class. Do the reading + highlight
  generation yourself (Overview-style). Only dispatch a `SMALL` scout sub-agent if you genuinely need to
  follow the call chain into other files to tell the story. Do not run the parallel Deep-Dive machinery
  for a single symbol.

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
  types, methods, and flow as 1–4 segments.
- **`"scope": "directory"`** — a folder / project / solution node, or a multi-selection. A `"targets"`
  array then lists the selected paths. Give a **lean guided tour**: read the important files, pick the
  pieces worth narrating, and build segments across them (segments may span multiple files). Don't try
  to cover everything — favour the entry points and the code that tells the story.

## Steps

1. **Locate the target.**
   - **File/directory scope** (see above): there is no caret — read the target file(s)/folder and choose
     the coherent units worth narrating (for a directory, a handful of key files; for a file, its main
     types/methods). Skip to building the plan.
   - If `selectionText` is present, the target is that line range (`selectionStartLine`–`selectionEndLine`),
     expanded outward to whole-statement / whole-method boundaries.
   - Otherwise, read around `caretLine` and expand to the **enclosing method/function** (or the class/type
     if the caret is at type level). Use `Read` with `offset`/`limit` — don't slurp the whole file.
   - Keep scope tight: a segment should be a coherent unit. Overview target ≤ ~80 lines; split into
     multiple segments if the enclosing unit is larger.

2. **(Optional) Scout for context.** If understanding the target requires its immediate callers/callees in
   other files, dispatch ONE `SMALL` (`Explore`) sub-agent per `docs/scan.md` to map them. Skip this for
   self-contained code.

3. **Build the plan.** Produce 1–4 segments (usually 1–2) covering the target and any essential
   supporting code. For each segment generate dense, pointer-style highlights per the rules in
   `docs/segments.md` (flowing `ttsText`, skip boilerplate, explain the *why*). Even though audio is not
   played yet, always write good `ttsText` — the highlights double as the reading order and the TTS phase
   will speak them.

   **Exact schema** (1-based line numbers, inclusive ranges — do NOT use `startLine`/`endLine`/`label`):

   ```json
   {
     "type": "set_plan",
     "title": "<short title of what's being explained>",
     "language": "en",
     "segments": [
       {
         "id": 0,
         "file": "<same path style you were given>",
         "start": 40,
         "end": 78,
         "title": "<short segment label>",
         "explanation": "<markdown explanation of the whole segment>",
         "highlights": [
           { "start": 42, "end": 45, "ttsText": "<1-2 plain-text sentences, flowing narration>", "explanation": "<2-5 word sidebar label>" }
         ]
       }
     ]
   }
   ```

   Segment `id`s are sequential from 0. `file` should be the **absolute path** (or the exact path you were
   given) so the editor can open it.

   **Language.** The top-level `"language"` is the walkthrough's language code (`en`, `es`, `fr`, `hi`,
   `it`, `pt`, `ja`, `zh`). Default to `"en"`. If the prompt contains a **LANGUAGE:** instruction, obey it:
   set `"language"` to that code and write ALL prose — `title`, every segment `title`/`explanation`, and
   every highlight `ttsText`/`explanation` — in that language, while keeping code identifiers, file paths,
   and API names verbatim (never translate code). One language per walkthrough.

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

   A `{"ok":true}` response means the walkthrough is loaded; the editor spotlight and sidebar update
   automatically. A `{"error":...}` or connection failure means the plugin server isn't reachable — print
   that error and stop.

5. **Done.** Do NOT send `goto`/`resume` (the plugin/sidebar drives playback). Print one status line.

## Reminders

- The plugin loads the plan into a **paused** walkthrough positioned at segment 0 — the user presses Play
  in the sidebar. Sending `set_plan` is enough; nothing else.
- If the server is unreachable, the discovery files (`~/.claude-explainer-port`, `~/.claude-explainer-token`)
  are missing — the plugin isn't running. Report it plainly; don't retry in a loop.
- Field-name mistakes are the most common failure: it's `start`/`end`/`title`/`ttsText`/`highlights`, never
  `startLine`/`endLine`/`label`/`subHighlights`.
- A **LANGUAGE:** line in the prompt means translate all prose to that language (see step 3) and set the
  top-level `"language"` code — but never translate code identifiers, paths, or API names.
