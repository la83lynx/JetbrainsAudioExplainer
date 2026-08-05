# JetBrains Audio Explainer — Plan

A Rider (and all-JetBrains-IDE) plugin that adds an **"Explain this (Audio)"** right-click
action: it audio-explains the selected code — or the method/class under the cursor — while
highlighting the relevant lines, using **Claude Code (headless)** as the reasoning backbone.

Strategy: **reuse ~70% of [code-explainer](https://github.com/Royal-lobster/code-explainer)**
(agent skill, wire protocol, plan schema, sidebar UI, Kokoro voice model) and **rewrite the
~30% IDE-adapter** as a native IntelliJ-Platform plugin. Because the plugin needs only editor
markup + a tool window + a loopback server + a CLI subprocess, it is a **pure JVM frontend
plugin** (no ReSharper backend module) and therefore runs unchanged in Rider, IDEA, PyCharm,
WebStorm, etc.

> **This document is a build log, phase by phase, and is not edited retroactively.** One rename since
> matters when following its code references: `TreeOfCode.md` Phase 1a split the drawing half out of
> `EditorHighlighter.kt` into **`SpotlightRenderer.kt`**, so `EditorHighlighter.colors()` /
> `.refresh()` / `clearTracked()` below are now `SpotlightRenderer.colors()` / `.flashToken()` +
> `EditorHighlighter.refresh()` / `SpotlightRenderer.clear()`. Where a section says `EditorHighlighter`
> means "the thing that draws", read `SpotlightRenderer`; where it means "the `WalkthroughListener`", the
> name is still right.

## License / attribution

code-explainer is **MIT (Copyright (c) 2026 Srujan Gurram)**. Reuse (including on the public
Marketplace, including modified) is permitted. **Obligation:** ship the original MIT text +
copyright line alongside the reused `docs/` / `scripts/` / `media/` — e.g. a
`LICENSE-code-explainer` file in the repo and inside the distributed `.zip`.

## Architecture

```
Rider ──"Explain this" action──▶ gather {file, caretLine, selectionText}
  │                                        │
  │                     shell out: claude -p  (skill loaded, context as prompt)
  │                                        │
  │      plugin loopback HTTP/WS  ◀──POST /api/message {set_plan …}── Claude
  │             (127.0.0.1:port, token in ~/.claude-explainer-*)
  ▼
MarkupModel highlights + ScrollingModel  +  JCEF ToolWindow (controls + audio)
                                               ▲
                                     kokoro-onnx TTS server (TCP loopback)
```

### Key design decisions (locked)

1. **Trigger = headless `claude -p`.** The IDE initiates; Claude generates the plan and POSTs
   it back. No chat window, one click. (A running-session long-poll is a possible fast-follow.)
2. **Symbol expansion delegated to Claude.** Do **not** resolve the enclosing C# method/class
   in-plugin — that requires Rider's ReSharper .NET backend (frontend/backend RdProtocol split).
   Instead pass `{file, caretLine, selectionText}` and let Claude (which reads the file) decide
   the method/class boundaries. This is what keeps the plugin a single JVM frontend module.
3. **TTS = Kokoro, cross-platform runtime.** Reuse the Kokoro-82M *model* and voices, but swap
   the engine from Apple-only `mlx-audio` to **`kokoro-onnx`** (see Phase 7).
4. **Distribution = public JetBrains Marketplace**, signed with a **self-generated** cert
   (no CA purchase; $0). See Phase 8.

## Reuse vs. rewrite

| From code-explainer | Action |
|---|---|
| `SKILL.md`, `docs/*`, `scripts/explainer.sh`, plan JSON schema | Reuse (add a cursor-trigger entry doc) |
| `media/sidebar.{html,css,js}` (controls + `AudioContext` playback) | Reuse inside JCEF |
| Wire protocol (endpoints, message types, port/token files) | Reuse the contract; reimplement server in Kotlin |
| Kokoro-82M weights + voice set | Reuse |
| `scripts/tts_server.py` (mlx-audio, Unix socket) | **Rewrite**: `kokoro-onnx` + TCP |
| `vscode-extension/src/*.ts` (extension, server, highlight, sidebar, tts-bridge) | **Rewrite** in Kotlin |
| `setup.sh` (Homebrew, `uname==Darwin` guard) | **Rewrite** for Windows/cross-platform |

Roughly **70% reused, 30% new**. The new part is the Kotlin editor/tool-window/action adapter,
the headless Claude invocation, and the cross-platform TTS swap.

## Phased plan

### Phase 1 — Scaffold & prove it runs in Rider
1. Start from the JetBrains **`intellij-platform-plugin-template`** (Gradle + Kotlin).
2. Configure the IntelliJ Platform Gradle plugin to target **Rider** (`platformType = RD`,
   a concrete Rider `platformVersion`); set `sinceBuild`/`untilBuild`.
3. `plugin.xml`: register a stub `AnAction`, a `ToolWindow`. `./gradlew runIde` launches a
   Rider sandbox with the plugin. **Gate:** empty plugin visibly loads in Rider.

### Phase 2 — Loopback server (port `server.ts` → Kotlin)
4. Host a JDK `HttpServer` (or Netty) + WebSocket on `127.0.0.1:<dynamic>`.
5. Reproduce the **exact** endpoints/messages so the skill is unchanged:
   `/api/health`, `/api/state`, `/api/actions` (long-poll), `/api/message`,
   `/api/save|load|walkthroughs`; messages
   `set_plan / insert_after / replace_segment / remove_segments / goto / resume / stop`.
6. Write port + token to `~/.claude-explainer-port` / `~/.claude-explainer-token` (identical
   discovery contract). **Gate:** `/api/health` → `{"status":"ok"}`; a hand-crafted `set_plan`
   POST is accepted. Plan shape (verified against upstream `types.ts`, NOT the stale
   `docs/walkthrough.md`): `{title, segments[]}`; segment =
   `{id, file, start, end, title, explanation, highlights[]}`; highlight =
   `{start, end, ttsText, explanation?}`. Line numbers are 1-based, ranges inclusive. Auth via
   `Authorization: Bearer <token>` (or `x-auth-token`). The upstream WebSocket channel is
   intentionally not ported (redundant with HTTP + long-poll; unused by the reused skill).

### Phase 3 — Editor highlighting (port `highlight.ts`) — DONE
7. Open file at line: `FileEditorManager.openTextEditor(OpenFileDescriptor(project, vFile, line, col), true)`.
8. Recreate the four decoration layers via `MarkupModel.addRangeHighlighter` + `TextAttributes`
   (dim-outside, dim-within-segment, active gold highlight, dotted gap); track handles per
   segment and clear on transition.
9. Scroll + caret: `editor.scrollingModel.scrollTo(pos, CENTER)`,
   `editor.caretModel.moveToOffset(...)`. All editor mutations on the **EDT**
   (`WriteCommandAction` / `invokeLater`); server callbacks arrive off-thread.
   **Gate:** a `set_plan` payload highlights and scrolls to the right ranges.

Implemented in `EditorHighlighter.kt` as the `Walkthrough`'s `WalkthroughListener`
(installed per-project in `ServerStartupActivity`). Notes / deltas from upstream:
- **opacity → foreground blend.** IntelliJ `RangeHighlighter`s have no per-highlighter opacity,
  so the two dim layers (0.35 outside, 0.55 within-segment/gap) are emulated by overriding the
  whole-line foreground with a colour blended that fraction toward the editor background. This
  flattens syntax colours on dimmed lines — the intended de-emphasis.
- **gap left-border → gutter bar.** Upstream's dotted gold CSS left border becomes a gold gutter
  `LineMarkerRenderer` bar (the closest native analogue). Active layer = whole-line background glow
  + gold error-stripe (overview-ruler) mark.
- **contiguous-run coalescing.** Outer dim uses at most two `LINES_IN_RANGE` highlighters; within
  the segment, consecutive same-role lines are coalesced into single highlighters (cheap even on
  large files, unlike per-line decorations).
- Colours are recomputed from the current `EditorColorsScheme` on every render, so a mid-session
  light/dark switch is honoured; listener callbacks marshal to the EDT via `invokeLater` and return
  immediately (they fire while the walkthrough lock is held).
- Sub-highlight is revealed immediately on `set_plan` (highlight 0) so one payload demonstrates all
  four layers; upstream defers it to playback. The Phase 4 playback loop will re-drive sub-ranges.
- **Readability gap — addressed in Phase 3.5 (DONE):** the original hardcoded styling read poorly —
  the 0.35 (outside) vs 0.55 (within-segment) dim levels looked nearly identical, and
  non-active-highlight lines vs context/"gap" lines were distinguishable *only* by the gold gutter bar.
  These are now user-tunable and the defaults are re-tuned (wider dim gap + a faint tint on non-active
  highlights). See Phase 3.5.

### Phase 3.5 — Plugin settings & appearance configuration — DONE

Motivation (from the Phase 3 review): highlight styling is currently hardcoded in `EditorHighlighter`
and reads poorly — the two dim levels are too close, and non-active highlights rely entirely on the
gutter bar to distinguish them from gap lines. Users' themes, contrast needs, and taste vary, so this
should be configurable. Placed here (its primary motivation) but implemented once there are enough
knobs to justify the page — it also aggregates settings from Phases 4–7. Can be pulled forward.

Deliver a native IntelliJ settings page:
- Register a `Configurable` (Kotlin UI DSL `BoundConfigurable` / `SearchableConfigurable`) via the
  `com.intellij.applicationConfigurable` extension point, under **Settings/Preferences → Tools →
  Audio Explainer** (`parentId="tools"`).
- Back it with an app-level `@Service` `PersistentStateComponent<State>`
  (`@State(name="AudioExplainerSettings", storages=[Storage("audioExplainer.xml")])`) so values
  survive restarts.
- Expose at minimum the highlight appearance:
  - outside-segment dim strength, within-segment dim strength (with enough default separation to be
    obviously different);
  - active-line glow colour + strength, accent ("gold") colour, overview-ruler mark on/off;
  - non-active-highlight treatment — give it its **own** cue (e.g. a faint background tint) so it no
    longer depends solely on the gutter bar;
  - gap indicator: gutter-bar on/off, colour, width (optionally a dotted variant);
  - a "Reset to defaults" button and, if practical, a small live preview.
- Later knobs as those features land: TTS voice + default speed (Phase 7); Claude CLI path / model /
  extra args (Phase 6); sidebar auto-reveal + smooth-scrolling toggle (Phase 4); optional fixed
  server port (Phase 2).
- `EditorHighlighter.colors()` reads the settings service instead of the current hardcoded constants;
  a settings change re-renders open editors (re-run the current segment's highlight, or apply on the
  next transition at minimum). Keep everything theme-aware (`JBColor` / derive from the colour scheme).
- **Ship better defaults regardless of the page** (quick win, need not wait for the UI): widen the
  dim-level gap and add the distinct non-active-highlight cue above.

Reuse note: upstream exposes config via VS Code `contributes.configuration` (`editor.smoothScrolling`,
TTS voice/speed); the IntelliJ analogue is `Configurable` + `PersistentStateComponent`.
**Gate:** the page appears under Settings, values persist across restarts, and changing the
dim strengths / colours visibly re-styles an active walkthrough.

**Implementation notes (what shipped):**
- `AudioExplainerSettings` — app-level `@Service` + `PersistentStateComponent<State>`,
  `@State(name="AudioExplainerSettings", storages=[Storage("audioExplainer.xml")])`. Values are
  primitives (Int percentages, `RRGGBB` hex strings, booleans) so the platform XML serializer
  round-trips them with no custom converters.
- `HighlightSettingsConfigurable` — `BoundConfigurable` built with the Kotlin UI DSL, registered via
  `applicationConfigurable parentId="tools" id="com.audioexplainer.settings"`. Spinners/checkboxes use
  DSL bindings (`bindIntValue`/`bindSelected`); the two `ColorPanel`s have no binding helper, so they
  go through the cell lifecycle hooks (`onReset`/`onApply`/`onIsModified`). "Reset to defaults" writes
  default values into the live components (persisted only on OK/Apply). *Dropped `SearchableConfigurable`*
  (the EP `id` gives the page its identity) to avoid a fragile supertype/`getId` assumption; *live
  re-render is triggered from a trailing `Cell.onApply` on the reset row rather than by overriding
  `apply()*`, since it runs after every other cell's write and avoids assuming `BoundConfigurable.apply`
  is open.
- Knobs shipped: outside- & within-segment brightness (%), active glow colour + strength, accent
  colour, overview-ruler mark on/off, non-active tint on/off + strength, gap gutter bar on/off + width.
  (A live *preview* pane and the later Phase 4–7 knobs — TTS voice/speed, Claude CLI path, smooth
  scrolling, fixed port — remain deferred to those phases.)
- `EditorHighlighter.colors()` now reads `AudioExplainerSettings.getInstance().state` per render (still
  theme-aware: dim foregrounds are blended from the live colour scheme; accent/glow are the user's
  absolute colours). Non-active highlight lines get their own faint accent-tinted **background** so they
  no longer read identically to gap lines. `EditorHighlighter.refresh()` re-applies the current
  segment's spotlight, and the settings page calls it on apply so a change re-styles the live
  walkthrough immediately.
- Re-tuned defaults (the "ship better defaults" quick win): outside 22 % / within 62 % (a ~40-pt gap
  vs the old ~20-pt), active glow 10 %, non-active tint 14 %, gap bar 3 px, accent `#FFBE3C`.
- **Runtime-verified:** plugin loads clean (no `ERROR`/`SEVERE`/slow-op from our frames); a demo
  `set_plan` opens the file and renders the four layers at the new defaults. The GUI checks (page
  visible under Settings → Tools → Audio Explainer, persistence across restart, live re-style on Apply)
  are visual and left to manual confirmation.

### Phase 4 — Sidebar tool window + audio (port `sidebar.ts` + webview) — DONE (audio deferred to Phase 7)
10. `ToolWindow` hosting a **`JBCefBrowser`** (JCEF) — the analog of the VS Code webview.
    **Reuse `media/sidebar.{html,css,js}` almost verbatim**, including `AudioContext` playback
    and play/pause/next/speed controls.
11. Bridge JS→plugin button presses via `JBCefJSQuery` into the server's `/api/actions`
    long-poll queue (agent side unchanged). **Fallback:** if JCEF is unavailable, a Swing panel
    + `javax.sound` player.

**Implementation notes (what shipped):**
- `AudioExplainerToolWindowFactory` now builds a real UI: `JBCefApp.isSupported()` → the JCEF sidebar,
  else (or if the browser throws) a Swing fallback (`AudioExplainerSwingSidebar`: title/status/segment
  labels + Prev/Play-Pause/Next/Stop, no webview/audio). Both tie their lifetime to
  `ToolWindow.getDisposable`.
- `AudioExplainerSidebar` hosts a `JBCefBrowser` loading the reused `sidebar.{html,css,js}` (bundled
  under `resources/sidebar/`) assembled into one document — `sidebar.js` is **verbatim** from upstream;
  `sidebar.html` is the upstream body markup; `sidebar.css` is a **fresh rewrite** (upstream's wasn't in
  the reused assets and used VS Code theme tokens), keyed to the same class names and driven by
  `:root` CSS variables injected from the live IDE theme (`UIUtil` colours + the settings accent).
- **The JS bridge, not `/api/actions`.** The plan floated routing button presses through the agent's
  long-poll queue, but that channel is agent-facing and playback is a local concern (upstream drives it
  in-process too). So a `JBCefJSQuery` shim supplies `acquireVsCodeApi().postMessage`, whose JSON is
  routed to the plugin and drives the `Walkthrough` **directly** (`togglePlayPause`/`next`/`prev`/
  `goto`/`stop` + save/load/list). Plugin→webview uses `cefBrowser.executeJavaScript` to call
  `window.__audioExplainerReceive`, re-dispatched as the `message` event the webview already listens for.
  `/api/actions` stays reserved for genuine agent-directed actions (e.g. "ask a question").
- **`Walkthrough` now fans out to multiple listeners** (`CopyOnWriteArrayList` + `addListener`/
  `removeListener`/`getListeners`, replacing the single `var listener`): the `EditorHighlighter` and the
  sidebar both observe it. Registrations are disposed with their project / tool window.
- Save/load/list are exposed in-process on `ExplainerServer` (`saveCurrentWalkthrough`/
  `listSavedWalkthroughs`/`loadSavedWalkthrough`) — the same storage the HTTP routes use; the sidebar's
  Save prompts for a name via `Messages.showInputDialog`.
- **Audio is a deliberate no-op until Phase 7**: the webview's `audio_chunk`/`audio_end`/`suspend`/
  `resume`/`highlight_advance` and the speed/voice controls are accepted but unwired (volume/mute act
  locally in the webview). Without TTS, Play toggles status and Next/Prev navigate segments; the editor
  spotlight follows via the existing `EditorHighlighter`. Highlight-level nav falls back to segment nav.
- **Runtime-verified (non-GUI):** clean load (no `ERROR`/`SEVERE`), server up, the multi-listener
  refactor drives correct `resume`→playing / `goto`→segment / `stop`→stopped transitions and the editor
  still opens/highlights, and `sidebar.{css,html,js}` are confirmed bundled in the plugin jar. The
  webview render + button-driven control are visual and left to manual confirmation (open the tool
  window).

**Gate:** open the **Audio Explainer** tool window during a walkthrough — the sidebar shows the title,
outline, current segment, and progress; Play/Next/Prev/outline-click move the walkthrough and the editor
spotlight follows; Save/Load round-trip through `.walkthroughs/`.

### Phase 5 — "Explain this" action (headline feature) — DONE (Claude wiring deferred to Phase 6)
12. Register a right-click action in `plugin.xml` under `EditorPopupMenu`, text
    "Explain this (Audio)".
13. `actionPerformed`: capture `editor.selectionModel` text if any, else caret line/offset;
    resolve project-relative file path; **do not** resolve the C# symbol in-plugin — package
    `{file, caretLine, selectionText}` as Claude context.
14. Show progress via `Task.Backgroundable` ("Explaining…"); enable/disable via `update()`
    (only when an editor + file are present).

**Implementation notes (what shipped):**
- The action was already registered (task 12) under `EditorPopupMenu` (`AudioExplainer.ExplainThis`).
  `update()` runs on the BGT and enables the item only when both an editor and a file are present.
- `actionPerformed` reads the caret/selection on the EDT and packages an **`ExplainRequest`** — the
  plan's `{file, caretLine, selectionText}` plus the fields Phase 6 needs: `projectBasePath` (Claude's
  cwd), `absolutePath` (fallback), and the selection's 1-based line range. `file` is project-relative
  when the file lives under the project root, else absolute. The C# symbol is **not** resolved here —
  expanding {caret, selection} to the enclosing method/class is Claude's job (decision 2).
- It then runs a **cancellable `Task.Backgroundable`** ("Explaining code…", indeterminate) whose body
  hands the request to `ClaudeExplainer.explain(project, request, indicator)` — the single seam Phase 6
  fills with the headless `claude -p` subprocess.
- `ClaudeExplainer` is an app-level `@Service`. In this phase its `explain` only surfaces the captured
  context as a balloon notification (registered `<notificationGroup id="Audio Explainer">`), proving the
  right context was gathered; Phase 6 replaces the body with the subprocess (honouring `indicator` for
  progress + cancellation) that lets Claude plan and POST to `ExplainerServer`.
- **Runtime-verified (non-GUI):** compiles clean; the plugin loads with the new service +
  notificationGroup descriptors and no `ERROR`/`SEVERE`. The right-click → menu → notification flow is a
  GUI interaction, left to manual confirmation.

**Gate:** right-click in an editor → **Explain This (Audio)** is present (and only when a file is open);
clicking it shows the "Explaining code…" progress and a notification echoing the captured file / target /
caret line / selection — the exact context Phase 6 hands to Claude.

### Phase 6 — Claude Code backbone (headless) — DONE (audio deferred to Phase 7)
15. Reuse `SKILL.md` + `docs/*` + `scripts/explainer.sh` with light edits; add an
    "explain-at-cursor" entry doc: given `{file, caretLine, selectionText}`, scout expands to
    the enclosing method/class, plans (`scan.md`→`plan.md`→`segments.md`), POSTs the plan.
16. Action invokes `claude -p "<explain-this prompt + context>"` headless, cwd = project root
    (so skill + relative paths resolve). Consider `--output-format stream-json` for progress.
    **Gate:** right-click → automatic audio walkthrough of the method under cursor.

**Implementation notes (what shipped):**
- **Bundled skill, renamed `audio-explainer`.** The reused `SKILL.md` + all `docs/*` + `explainer.sh`
  are bundled under `resources/claude-skill/` (enumerated by `manifest.txt` since a jar has no dir
  listing), plus a new **`docs/explain-at-cursor.md`** entry doc (the headless, autonomous cursor flow:
  no `AskUserQuestion`, expand the symbol, lean single-pass plan, POST, stop) and a new
  **`scripts/explainer.ps1`** (PowerShell port of `explainer.sh` — no `jq`/git-bash dependency; runs on
  Windows PowerShell 5.1 and pwsh 7). The skill is named `audio-explainer` (not `explainer`) so it can
  never clobber a separately-installed upstream code-explainer skill.
- **`ClaudeSkillInstaller`** copies the bundled skill to **`~/.claude/skills/audio-explainer/`**,
  idempotent + versioned (writes only when missing or the `VERSION` marker differs, so it won't clobber
  later user edits every run). This also makes the skill usable **interactively** in Claude Code (the
  full scout→plan→segments checklist stays in `SKILL.md` for that; the plugin's one-click path routes to
  `explain-at-cursor.md`). Per the locked decision, the user opted in to the plugin managing this global
  skill dir.
- **`ClaudeExplainer.explain`** now shells out for real: finds `claude` (PATH via
  `PathEnvironmentVariableUtil`, then `~/.local/bin`, `~/.claude/local`, npm dirs), ensures the skill is
  installed, then runs `claude -p "<prompt>" --output-format stream-json --verbose --allowedTools "…"`
  via `GeneralCommandLine`/`OSProcessHandler`, cwd = project root, inheriting the console env. The prompt
  carries the `{file, caretLine, selectionText, projectRoot}` context and points at
  `explain-at-cursor.md`, with the schema + POST mechanics inlined so it works even if the doc isn't read.
- **Permissions = scoped allow-list** (locked decision): `--allowedTools "Read Grep Glob Task Write
  TodoWrite Bash"`. Bash is intentionally command-unrestricted so the POST works cross-platform
  (curl anywhere, powershell on Windows, the `.sh` helper elsewhere); tighten `ALLOWED_TOOLS` to narrow.
  No `--dangerously-skip-permissions`.
- **Progress + cancellation:** the `stream-json` events are parsed live — tool-use / assistant-text
  activity updates the `Task.Backgroundable` sub-text; cancel kills the process. On finish it reads the
  walkthrough state: a loaded plan ⇒ success notification + auto-activates the **Audio Explainer** tool
  window; Claude error / no plan ⇒ error/warning notification with the detail. "claude not found" is its
  own actionable error.
- **The plan's `/api/actions` idea is not used here** — the action drives the loopback server's existing
  `POST /api/message` `set_plan` contract (the same one the skill/helpers already speak), which fans out
  to the highlighter + sidebar via the Phase 4 multi-listener `Walkthrough`.
- **Runtime-verified (standalone, no sandbox):** compiles + `buildPlugin` clean, skill resources confirmed
  bundled in the jar. Against a Python mock of `ExplainerServer`: both POST helpers succeed
  (`explainer.sh`; `explainer.ps1` on PS 5.1 **and** 7); and a real `claude -p` with the **exact** flags
  the plugin uses autonomously read the target file, expanded the symbol under the cursor, built a
  schema-valid 2-segment/6-highlight `set_plan` (correct `start/end/ttsText/highlights` field names,
  absolute paths, flowing `ttsText`), and POSTed it (`{"ok":true}`, result `subtype=success`). Finding
  folded back into the entry doc: Claude's first inline-`curl` POST corrupted the JSON via shell quoting,
  so the doc now mandates writing the plan to a temp file and never passing JSON inline. The in-IDE
  path (right-click → real server → spotlight/sidebar → notification) is a GUI flow, left to manual
  confirmation.

**Gate:** right-click **Explain This (Audio)** on a method → the "Explaining code…" task runs, Claude
generates a plan and POSTs it, and the walkthrough appears (editor spotlight + sidebar outline), the tool
window activates, and a "Walkthrough ready" notification shows. (Audio narration itself is Phase 7.)

### Phase 7 — TTS (cross-platform Kokoro) — DONE (Kokoro shipped; SAPI engine deferred to Phase 7b)
code-explainer's TTS is **Apple-Silicon-only** (`mlx-audio` on Apple MLX; macOS `say` fallback;
Unix-domain-socket transport; Homebrew setup). The *model* is portable, the *engine* is not.
Ported as follows:

| code-explainer (macOS) | This plugin (Windows/cross-platform) |
|---|---|
| `mlx-audio` (Apple MLX) | **`kokoro-onnx`** (ONNX Runtime) — same Kokoro-82M weights + voices |
| macOS `say` fallback | dropped for this pass; Windows SAPI (`System.Speech`) is the planned emergency fallback (Phase 7b) |
| Unix socket `/tmp/tts-server.sock` | **TCP loopback** `127.0.0.1:port` |
| `brew install` + `uname==Darwin` guard | `pip install kokoro-onnx onnxruntime numpy` (pulls **`espeakng-loader`** — no separate espeak-ng install) + model download; automated by the plugin |

17. Rewrite `tts_server.py`: swap the MLX loader for `kokoro-onnx`, switch transport to TCP,
    keep the JSON request shape (`{text, voice, speed}` / `{ping:true}`) and streaming behavior.
18. Keep Kokoro as primary (quality); SAPI only as a zero-dependency emergency fallback.

**Implementation notes (what shipped):** Phase 7 turned out to be more than a file swap — the *audio
narration orchestration* (the highlight-by-highlight playback loop) had been deferred here from Phase 4
too, so this pass built both the TTS server **and** the driver that feeds the webview.

- **`scripts/tts_server.py` (rewritten, bundled + installed).** Kokoro-82M via `kokoro-onnx` over a
  **TCP loopback** socket (was a Unix socket). Preserves the `{text,voice,speed}` / `{ping:true}` request
  shape. On start it prints a JSON handshake (`{"ready":true,"port":N,…}`) so the launcher learns the
  ephemeral port. Responses are newline-delimited JSON: `meta` → repeated `chunk` (base64 **float32-LE
  PCM**, the exact bytes Web Audio decodes) → `end`. Audio is **re-framed to ≤0.5 s chunks** so playback
  streams and no `executeJavaScript` payload gets large. Extras that power the setup flow: `--synthetic`
  (sine-tone engine, no model/deps — lets the whole pipeline be tested), `--download` (fetch model +
  voices with progress), `--check` (prerequisite status JSON). Bundled in the skill (also usable
  interactively); `espeakng-loader` removes the separate espeak-ng install the plan anticipated.
- **`TtsService` (`@Service(APP)`).** Owns the Python server process lifecycle (lazy start, reused, killed
  on dispose; reads the handshake, patient 120 s first-load timeout), and streams synthesis over a fresh
  socket per highlight (cancel = close socket → Python aborts). Detects prerequisites via `--check`
  (cached) and drives setup: `installDependencies` (pip) + `downloadModel`. Finds Python on PATH (`python`
  /`python3`/`py -3`).
- **`NarrationController` (per JCEF sidebar).** The playback driver: for each sub-highlight of the current
  segment it moves the editor spotlight (new `Walkthrough.advanceHighlight` → `WalkthroughListener.onHighlight`,
  implemented by `EditorHighlighter`), pushes `highlight_advance`, streams the highlight's `ttsText` as
  `audio_chunk`s, and on the webview's `playback_complete` advances to the next highlight, then the next
  segment. Pause → `audio_suspend` (TTS keeps buffering), resume → `audio_resume`; stop → `audio_stop`.
  **All state runs on a single-thread executor** — listener callbacks/webview messages just enqueue — so
  there are no locks and no lock-ordering deadlock against the `Walkthrough`'s `synchronized` fan-out.
  Gated on readiness: if TTS is off/unconfigured, playback stays silent (the walkthrough still loads +
  highlights) and a one-time first-run setup offer appears.
- **Sidebar wiring.** The previously no-op `audio_*`/`playback_complete`/`speed_change`/`voice_change`
  messages are now routed to the controller; speed/voice persist to settings and re-stream the current
  highlight. Volume/mute stay local to the webview.
- **Guided setup (`TtsSetup` + Tools action + Settings).** Per the locked decision, a plugin can't hook its
  own Marketplace install, so provisioning is a **first-run/on-demand** flow: on the first silent Play (and
  via **Tools → Audio Explainer → Set up TTS**, and the Settings "Narration (audio)" section) the user is
  offered a one-click Kokoro install (pip deps + ~340 MB model on a cancellable background task) or an
  opt-out. New settings: engine (`kokoro`/`sapi`/`off`), voice, speed %, model dir.
- **SAPI = Phase 7b (locked decision).** The opt-out *choice* is wired (preference stored, offered in the
  setup UI), but the Windows `System.Speech` engine itself is a follow-up; choosing it today = silent
  playback with a "coming soon" notice.
- **Runtime-verified (standalone, no sandbox — matches the [[verify-headless-claude-without-sandbox]]
  technique).** Installed `kokoro-onnx 0.5.0` + `onnxruntime 1.27.0` + the model (325 MB + 28 MB) on the dev
  machine. `tts_server.py` verified end-to-end: `--synthetic` (handshake/ping/meta/chunks/end + valid
  float32), `--check` (`ready:true`), `--download` (progress → files on disk), and a **real Kokoro synthesis**
  — 8.49 s of genuine speech (RMS 0.076, not the tone), delivered as 17 bounded ≤64 KB-base64 chunks, saved
  to a WAV. The wire contract matches the webview (`chunk.audio`+`sampleRate` → `audio_chunk{data,sampleRate}`
  → `atob`→`Float32Array`). Compiles + `buildPlugin` clean; the framed `tts_server.py` + 4 new classes +
  `TtsService`/Tools-action registrations confirmed in the jar. **The in-IDE loop (right-click → spoken,
  synced walkthrough; pause/resume; speed/voice) is a GUI flow left to manual confirmation.**

**Gate:** right-click **Explain This (Audio)** on a method with Kokoro set up → the walkthrough loads and,
on Play, each highlight is spoken while the editor spotlight and sidebar follow in sync; pause/resume and
next/prev-highlight work; speed/voice changes take effect. (Windows SAPI fallback = Phase 7b.)

### Phase 7.5 — First-run UX fixes (from manual `runIde` testing)

Several hands-on `runIde` rounds surfaced UX bugs and refinements, all fixed in place. Condensed here to the
decisions worth remembering. Almost everything lives in the webview assets (`sidebar.{html,css,js}`) +
`AudioExplainerSidebar`, which reload fresh each run — so **no `VERSION` bump**; the one exception (read-along
sync) is called out.

**Core behaviour**
- **No white-wash under a dark theme.** `EditorHighlighter.colors()` reads the *per-editor* `editor.colorsScheme`,
  not the global scheme (which can differ), so the de-emphasis/glow blend can't resolve against a mis-read light
  background. (Environment-dependent; if it recurs, derive the glow by lightening/darkening the actual bg rather
  than blending an absolute colour.)
- **Navigation preserves play/pause — only Play starts audio.** `Walkthrough.next/prev`, highlight-level
  `NarrationController.userAdvance`, and outline `gotoHighlight` move the spotlight/transcript without forcing
  playback when paused; a `restartPending` flag makes the next Play re-narrate the moved-to step fresh. (Fixed
  the original "audio plays but the button still says Play / two clicks to pause" bug.)
- **Transcript box** shows the segment's actual spoken `ttsText` (distinct from the Overview explanation), driven
  from the sidebar's plan state so it works with or without audio.
- **Outline lists every highlight as an individually clickable step** (plans are 1–2 segments × many highlights),
  rebuilt on a content-sensitive signature so a new plan / Undo always refreshes while plain navigation reuses the
  DOM and its per-step click handlers.

**Generation UX (the substantial item)**
- In-tool-window **loading banner** + one-level **Keep/Undo** on replace, via a project-level
  `ExplainSessionTracker` that `ClaudeExplainer` drives (and `show()`s the tool window at start, no focus steal).
  *Descoped* from the spec's server-side pending-buffer/adopt design: `ExplainerServer`/`Walkthrough` are
  app-level singletons and `set_plan` carries no project id, so adopt-then-Undo avoids all the
  request-correlation/cancel complexity — and with autoplay off a replacement loads paused anyway. Undo is one
  level deep.

**Read-along sync (the only `VERSION` bump)**
- The transcript doubles as a progress bar: spoken words fill as one continuous band, the current word is
  underlined, and you can **click a word / the bar to seek** (retained per-step decoded buffers; gated until the
  step's audio is fully buffered).
- Sync is **sentence-anchored** (`si`/`sw` tags per audio chunk), not per-word: Kokoro returns PCM with **no word
  timestamps**, and an earlier proportional-against-a-growing-total scheme drifted (rushed, then stalled).
  `tts_server.py` gained the sentence tagging and is version-gated, so this bumped the skill **`VERSION` →
  0.1.0-4**. *Deferred:* intra-word granularity + punctuation-pause modelling (would need a forced-alignment pass).

**Theme + smaller features**
- Panel **follows live IDE theme changes** (`LafManagerListener` re-pushes the theme vars; the injected
  `#ae-theme-vars` block is emitted last in `<head>` so live colours win the `:root` cascade over sidebar.css'
  dark fallback).
- Collapsible **Overview** pane; explicit **Finish** button (clears the tool window + editor highlights);
  clickable Now-Playing location link; Voice/Speed persist across reloads. Layout polish: Vol+Voice share one
  line and wrap to stacked below ~460 px, play controls sit just above the transcript, thicker clickable seek
  bar, larger collapse glyphs.

**JCEF/CEF gotchas (recurring — worth remembering)**
- CEF's off-screen renderer **won't repaint DOM changes without an input event** and **never shows native `title`
  tooltips**. Handled by `forceFullRepaint()` at layout transitions + a debounced `MutationObserver` → native
  `invalidate()` for non-layout mutations + a manual **Refresh UI** button; and a small **custom hover-tooltip**
  engine that reads each element's `title`.
- **Storage race:** the save dir + saved list were empty on first open because `WalkthroughStorage` is set by a
  `ProjectActivity` coroutine that raced the (startup-restored) tool window. The sidebar `init` now sets the
  workspace root *before* loading HTML, and `onLoadEnd` pushes the saved list authoritatively (the webview's own
  initial request lost to the `loaded` flag).

**Remaining:** dark-theme editor background is environment-dependent (re-verify if it recurs); intra-word sync
granularity and punctuation pauses are deferred; Undo is single-level. Otherwise Phase 7.5 is complete — the tool
window drives generation, playback, read-along, save/load, theming, and navigation as intended.

### Phase 8 — Package & deploy (Marketplace)
Signing uses a **self-generated** key pair (free, `openssl`) — **no CA certificate purchase**.
JetBrains re-signs on upload; the signature only proves integrity/authorship. Total cost $0.

19. `./gradlew buildPlugin` → distributable `.zip`.
20. Generate signing key pair once (`openssl`): `CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
    `PRIVATE_KEY_PASSWORD` (store as secrets; never commit). `./gradlew signPlugin`.
21. `./gradlew verifyPlugin` (IntelliJ Plugin Verifier) against the target Rider build range —
    run before every upload.
22. Marketplace: create a JetBrains account + **vendor profile** (free). First upload of the
    signed `.zip` (web UI, or `./gradlew publishPlugin` with a Marketplace **permanent token**)
    goes through **manual moderation** (~a couple business days); later updates are automated.
    Use `stable` vs. `eap`/`beta` channels to stage releases.
23. `plugin.xml` metadata: reverse-DNS `id`, `name`, `vendor`, HTML `description`,
    `change-notes`, `<idea-version since-build/until-build>`, `pluginIcon.svg`. List as
    compatible with all IntelliJ-based IDEs (pure frontend plugin), not just Rider.
24. Document external prerequisites (Claude CLI, Python + kokoro-onnx + espeak-ng) in the
    description; the plugin must show a clear first-run diagnostic if any is missing.
25. Ship `LICENSE-code-explainer` (MIT + Srujan Gurram copyright) in the repo and `.zip`.

## Rider-specific gotchas
- **EDT threading:** all editor/markup/tool-window mutations on the EDT; server callbacks are
  off-thread.
- **No in-plugin C# symbol resolution:** delegate method/class expansion to Claude (see decision 2).
- **JCEF availability:** present on standard runtimes but can be disabled — keep the Swing/SAPI
  fallback real.
- **Claude CLI latency/availability:** headless invocation is a subprocess with cold-start cost
  and an external dependency — surface "Claude not found / still thinking" states.
- **TTS engine, not model, is the macOS lock:** use `kokoro-onnx`, not `mlx-audio` (Phase 7).

## Prerequisites (developer machine, Windows)
- **JDK 21** (Temurin) + `JAVA_HOME`. Kotlin itself comes via Gradle — no separate install.
- **Git**. Optional but recommended dev IDE: **IntelliJ IDEA Community** (free; best plugin-devkit
  support). Rider can edit too, but IDEA is smoother for plugin dev.
- **Node is NOT required** — the VS Code extension is not ported; the sidebar HTML/CSS/JS are
  static assets served into JCEF (no build step).
- **Claude CLI** on PATH (already present via Claude Code).
- **Deferred to Phase 7:** Python 3.10+, `pip install kokoro-onnx`, espeak-ng, Kokoro model +
  voices download.
- **Deferred to Phase 8:** `openssl` for the signing key pair; JetBrains vendor account.
