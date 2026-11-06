# JetBrains Audio Explainer (Rider)

Native IntelliJ-Platform plugin that adds **"Explain This (Audio)"** to the editor context menu:
audio-explains selected code / the symbol under the cursor, with synced line highlighting, using
**Claude Code (headless)** as the backbone.

**Everything the plugin does is built**, and all of it but the newest round is judged. The loopback server and the reused wire protocol, the
**tree of code** that renders a walkthrough, the JCEF sidebar (Swing fallback when JCEF is off), the headless
`claude -p` backbone, cross-platform narration across four TTS tiers (Kokoro, Piper, Windows SAPI, espeak-ng)
with ~50 languages, and read-along narration whose pulses land on the words being spoken. **What is left is
Phase 8 — packaging, signing and the Marketplace listing**; `JetbrainsAudioExplainer.md` has that checklist,
the known limitations, and the two `runIde` sessions still owed — one for the four narration tiers
(`ExplainerLanguages.md`), one for the effort/depth/model knobs, the per-run dialog and the diagnostics. The living reference for the tiers and the voice catalogue is `claude-skill/docs/tts.md`, which
ships to users.

## What's here
- **Where the cited rules went.** KDoc through `tree/`, `PlanLoader`, `LinkArt`, `Protocol` and
  `SpotlightRenderer` cites `TreeOfCode.rules.md` — its 23 numbered constraints, 11 schema decisions and the
  caps — and `MoreUI.md` by section. Both were the build plans for work that is now finished and judged, so
  they came out of the tree with the rest of the release cleanup. **They are not lost: `git show
  4fead42:TreeOfCode.rules.md` and `git show 4fead42:MoreUI.md`** — the commit immediately before the cleanup
  holds both, so every citation in the source still resolves. Read them before reopening a decision the tree
  already took; the *rules* those numbers stand for are restated in the KDoc that cites them, which is why the
  code is the surviving copy. **`Effort.md` went the same way** — the plan for the effort/depth/model knobs,
  the per-run dialog and the diagnostics, cited from `AudioExplainerSettings`, `ClaudeExplainer`,
  `ExplainPrompt`, `plugin.xml` and `probes/EffortProbe.java`, and readable at `git show f873b3d:Effort.md`.
  **`Saving.md` too** — the plan for the saved list's filter, the plan directory outside the project, save
  identity and the unsaved prompt, cited from `SaveFlow`, `Walkthrough`, `WalkthroughStorage`,
  `ExplainPrompt`, `ClaudeExplainer`, the sidebar and both probes: `git show 4a54373:Saving.md`.
  **`SourceCheck.md` as well** — the plan for checking a plan against the code in the POST reply, and the
  measurement that found no time to save at `medium`. Cited from `ExplainPrompt`, `Speed.md` and
  `PlanProbe`: `git show 2d3d4df:SourceCheck.md`.
  `Speed.md` stays in the tree until widening a stray `keep` is judged in a sandbox; its closed candidates are cut to
  one-line verdicts there, with the full write-ups at `git show e12d4ab:Speed.md`.
  `Models.md` is the open plan for other models: provider profiles behind Claude Code first, another agent
  harness later. Nothing in it is built.
- `build.gradle.kts` / `settings.gradle.kts` — Gradle + IntelliJ Platform Gradle Plugin 2.x, targeting Rider 2026.1.4 (branch 261).
- `src/main/resources/META-INF/plugin.xml` — tool window, context-menu action, startup activity, app service.
- `com/audioexplainer/ExplainerServer.kt` — HTTP loopback server (full wire contract: `/api/health|state|actions|message|save|load|walkthroughs`), drops `~/.claude-explainer-{port,token}`.
- `Walkthrough.kt` — walkthrough state machine (port of upstream `walkthrough.ts`).
- `TreePlanController.kt` — the walkthrough's `WalkthroughListener`, and the only rendering surface: resolves every frame's file off the EDT, opens one **tree-of-code** tab (`tree/TreePlanView.kt`), and tells its node editors which step to draw. Stepping into another *scene* replaces the tree; a one-frame scene renders as a one-node tree, which is why there is no longer an in-place spotlight over the user's own editor tab. Nodes are views of the **live** documents, so the view watches the VFS: a file deleted under an open tree drops its node and keeps the rest, and a file replaced at the same path is re-taken rather than dropped.
- `SpotlightRenderer.kt` — the editor spotlight *drawing* stack (port of `highlight.ts`): dim/active/gap layers, callout inlay, relation arrows and pointers, token flash. One instance per editor (decorations are per-editor, not per-document), so a multi-editor surface can reuse it. Reads appearance from the settings service on every render.
- `tree/CodeNodeView.kt` — one node of the **tree-of-code** surface (`TreeOfCode.rules.md` §1a′): a viewer editor over the *real* document, everything outside the node's kept line ranges folded away, chrome + size-to-content-with-a-cap around it, its own `SpotlightRenderer`, and height resync on fold / font / colour-scheme / **inlay** / width change (rendering a step changes a node's height, because the callout inlay wraps at node width). Driven by `TreePlanController`.
- `tree/CodeNestView.kt` — **the chosen layout** (`TreeOfCode.rules.md` §1a‴, "Arch C"): a child node embedded *inside* its parent's editor directly under the line that calls it, via `EditorEmbeddedComponentManager` (an `impl`-package API, measured working on a viewer editor — and `offsetToXY` counts the block inlay, so the host's text reflows below the child instead of being drawn over). The invoking token is boxed and two diagonals run from it to the nested frame's top corners, painted in the frame's own top margin — which is why this needs **no overlay** at all. A nesting collapses to a one-row stub and re-expands, unlike a `CustomFoldRegion`. Unrelated top-level nodes stack in a scroll pane as before.
- `../probes/` — **the automated checks, and there is no test source set.** Six `javac` probes that *import* the production classes (`PlanProbe` over `PlanLoader`/`PlanOutline`/`WalkthroughStorage`/`TreeStyle` — 137 assertions; `TokenProbe` over the anchor's `findNth` — 18; `EffortProbe` over `ExplainPrompt` and `ExplainerDiagnostics` — 49, of which 14 hold the generated prompt byte-identical to the frozen `prompt-standard.txt`; and three **benches** that paint production drawing into PNGs — `LinkBench` over the links, 217 and 13 PNGs, plus `TreeBench` and `AppearanceBench` over the two settings previews), one `node` probe over the webview's playback clock (`ClockProbe` — 44, extracted out of the shipped `sidebar.js` by name rather than copied), and `gates.py` over the repo (the `noCompiledSerializer` stub, `plugin.xml` class references, resource XML, line endings, the skill manifest, `VERSION`, fences, and that a plan has exactly one destination). They import rather than transcribe, which is what separated them from the deleted `spikes/` and is why they outlived the fixtures. `probes/README.md` has the commands.
- `AudioExplainerSettings.kt` — app-level `PersistentStateComponent` (`audioExplainer.xml`) holding the tunable highlight appearance and the tree's shape. Its `DEF_TREE_*` defaults are the verdicts nine judged rounds settled, so Reset-to-defaults restores what was actually approved.
- `tree/TreeStyle.kt` — the one place a persisted setting becomes a `NestStyle`/`CodeNodeStyle`. Values are enum constant names, so there is no second vocabulary; an unrecognised one degrades to the default rather than stopping the tree from drawing.
- `HighlightSettingsConfigurable.kt` — the **Settings → Tools → Audio Explainer** page (Kotlin UI DSL). Its first group is the **code tree's own shape** — the nesting leader (`BRACE` by default, or `CORNERS`/`ARROW`), the collapse control, the caption path, node de-emphasis, the elision style and the height ceiling — and the rest decorate the lines inside a node. On Apply it asks `TreePlanController.refresh()`, which re-styles the tree when the shape changed and re-renders the step when only a colour did; a **theme** change re-renders it too, via each node view's own colour-scheme subscription, because the palette is computed per render.
- `WalkthroughStorage.kt` — per-workspace save/load under `<project>/.walkthroughs/` (port of `storage.ts`). Lists and loads only what `save` could have written: a file with a top-level `type` is a wire message, and a blank title or no frames is not a save.
- `SaveFlow.kt` — the Save / Save As… dialogs and the unsaved prompt, shared by the sidebar, `ExplainThisAction.launch` and the `projectCloseHandler` (`Saving.md`).
- `ExplainThisAction.kt` — the **Explain This (Audio)** context-menu action: captures the caret/selection + project-relative file, packages an `ExplainRequest`, and runs a cancellable "Explaining…" background task.
- `ExplainThisWithAction.kt` / `ExplainOptionsDialog.kt` — **Explain This with…**, the per-run overrides: the same action with one hook overridden, opening a modal seeded from the saved settings (effort slider, depth, free-text instructions with a history drop-down). Per-run values deliberately do **not** write back to Settings — a one-off must not silently become the new default — but the instruction history does persist. The plain entry stays first in the menu and unchanged, so the common case never grows a click.
- `ExplainRequest.kt` — the context handed to Claude (`{file, caretLine, selectionText}` + project root / absolute path / selection line range), plus the three per-run overrides (`effort`, `depth`, `extraInstructions`), all null for the plain action.
- `ExplainPrompt.kt` — **the argv and the prompt, as a pure function** of the request, the skill dir, the language and the three knobs. It lives apart from `ClaudeExplainer` precisely so a probe can build every combination without an IDE; `probes/EffortProbe.java` holds its Standard-depth output byte-identical to `probes/prompt-standard.txt`, which is what makes a depth change safe for a user who never opens the new controls. Depth narrows what `plan-schema.md` allows and never widens it — in the no-skill fallback branch too, or Brief would silently mean Standard for anyone whose skill install failed.
- `ClaudeExplainer.kt` — app service that turns an `ExplainRequest` into a walkthrough: finds `claude`, ensures the skill is installed, runs headless `claude -p` (streaming progress + cancellation), then reports success/failure and activates the sidebar. It is also **the one resolution point** for the knobs — `request.x ?: state.x`, once, so a per-run override and a saved default cannot disagree anywhere else.
- `ExplainerDiagnostics.kt` / `DiagnosticsDialog.kt` — **Check permissions and settings** (Settings → Executables (advanced)): read-only checks that each name a fix, filtered by OS — an inapplicable check is *omitted*, not shown as passing. It exists because the defects worth catching here were all invisible from inside the IDE. The marquee one is a **round-trip**: an em dash, an arrow and a non-Latin line sent through the platform's real helper (`explainer.ps1` / `explainer.sh`) to the running server and echoed back byte for byte — which is what a corrupted POST payload looks like before it becomes a mystery. It rides on `ping`, added to `ExplainerServer` for it, because `set_plan` would have replaced whatever walkthrough the user has loaded.
- `ClaudeSkillInstaller.kt` — copies the bundled `audio-explainer` skill to `~/.claude/skills/audio-explainer/` (idempotent + versioned).
- `resources/claude-skill/` — the reused Claude skill: `SKILL.md`, `docs/*` (incl. the headless `explain-at-cursor.md`), `scripts/explainer.{sh,ps1}` (the PowerShell port avoids a `jq`/git-bash dependency), and `scripts/tts_server.py` (the Kokoro TTS server). Enumerated by `manifest.txt`.
- `TtsService.kt` — app service that owns the Kokoro `tts_server.py` process (TCP loopback), streams synthesis per highlight, detects prerequisites, and drives the pip-install + model-download setup.
- `NarrationController.kt` — the Phase 7 playback driver: walks each segment's sub-highlights, moves the spotlight, streams `audio_chunk`s to the webview, and advances on `playback_complete`. Single-thread executor (no locks; deadlock-free against the walkthrough).
- `TtsSetup.kt` / `ConfigureTtsAction.kt` — the guided first-run/on-demand TTS setup (offer + background install) and the **Tools → Audio Explainer → Set up TTS** action.
- `AudioExplainerToolWindowFactory.kt` — builds the sidebar: JCEF when supported, else Swing fallback.
- `AudioExplainerSidebar.kt` — JCEF sidebar hosting the reused webview (`resources/sidebar/*`), bridged to the walkthrough via `JBCefJSQuery`.
- `AudioExplainerSwingSidebar.kt` — no-JCEF fallback: basic transport controls driving the walkthrough.
- `resources/sidebar/sidebar.{html,js,css}` — the ported webview (html/js near-verbatim from upstream; css is a JetBrains-theme-aware rewrite).
- `Protocol.kt` — **the wire schema, and the one place a new field is declared**: `Plan` / `Frame` / `Step` / `Beat` / `Relation` / `Endpoint`, plus the `Outline*` / `StepView` projections the sidebar reads and `UserAction`. A `Beat` is `{ line, token, occurrence?, say? }` — the *only* anchor shape in the plugin, the same one a relation endpoint and a call line use. The canonical prose for it is `claude-skill/docs/plan-schema.md`; the two must move together.
- `LICENSE-code-explainer` — MIT attribution for reused code-explainer assets (keep in every distribution).

## Prerequisites
- **JDK 21** (Temurin) + `JAVA_HOME`. Kotlin is pulled by Gradle — no separate install.
- **Node is NOT needed** (the VS Code extension is not ported; sidebar assets are static).
- Claude CLI is a runtime prerequisite (Phase 6). Narration (Phase 7) needs **Python 3.10+** on PATH plus
  `kokoro-onnx`/`onnxruntime` and the Kokoro model — all installed for you by **Tools → Audio Explainer →
  Set up TTS** (or manually: `pip install kokoro-onnx onnxruntime numpy` then
  `python ~/.claude/skills/audio-explainer/scripts/tts_server.py --download`). No separate espeak-ng
  install is needed (`kokoro-onnx` bundles it via `espeakng-loader`).

## First run
The Gradle wrapper JAR is binary and is not included here. Generate it once with a system Gradle
(or copy `gradlew`/`gradlew.bat`/`gradle-wrapper.jar` from the JetBrains
`intellij-platform-plugin-template`):

```bash
gradle wrapper --gradle-version 8.10.2
```

Then launch a Rider sandbox with the plugin loaded:

```bash
./gradlew runIde          # Windows: .\gradlew.bat runIde
```

**Phase 1 gate:** Rider opens with an "Audio Explainer" tool window on the right and an
"Explain This (Audio)" entry in the editor right-click menu.

**Phase 2 check:** with a project open in the sandbox, read the port/token and hit the server:

```bash
PORT=$(cat ~/.claude-explainer-port); TOKEN=$(cat ~/.claude-explainer-token)
AUTH="Authorization: Bearer $TOKEN"                                          # skill uses Bearer; x-auth-token also works
curl -s -H "$AUTH" http://127.0.0.1:$PORT/api/health                         # -> {"status":"ok"}
curl -s -H "$AUTH" -X POST http://127.0.0.1:$PORT/api/message \
     -d '{"type":"set_plan","title":"demo",
          "frames":[{"id":0,"file":"a.cs","title":"A.Method","explanation":"the whole thing",
                     "keep":[{"start":1,"end":1,"role":"context"},{"start":10,"end":20,"role":"focus"}]}],
          "steps":[{"frame":0,"ranges":[{"start":10,"end":12}],"ttsText":"hello","token":"Method"}]}'
                                                                             # -> {"ok":true} (+ "issues" if degraded)
curl -s -H "$AUTH" http://127.0.0.1:$PORT/api/state                          # -> status "paused", segment echoed back
```

The wire schema is **`frames` + `steps`** — structure separate from timeline — and it is specified in
`src/main/resources/claude-skill/docs/plan-schema.md`, which is what the agent authors against. A frame is one
file plus the line ranges kept visible in it (`role: "context" | "focus"`); a step names a frame and the ranges
active at that moment. There is no `segments` key and no `view` key. The reply carries an `issues` array
whenever the loader dropped or degraded something — which is the difference between a plan that rendered and
one that rendered *partly*.

**Phase 3 check:** a `set_plan` whose `frames[].file` points at a real file (relative to the open project, or
absolute) opens a **tree-of-code** tab: one outlined node per frame, showing only that frame's kept lines with
the rest folded away. In the active node the step's ranges are at full brightness with a gold glow +
overview-ruler mark, non-active kept lines read as context, and the elided runs between them get a gold gutter
bar. `stop` (or a `stop` agent message) closes the tab. The layers are the IntelliJ analogues of upstream's four
`TextEditorDecorationType`s (see `SpotlightRenderer.kt` for the opacity→foreground-blend mapping).

**Phase 3.5 check:** open **Settings/Preferences → Tools → Audio Explainer**. The page is five collapsible sections, in the order of the job: **Audio engines** (set up once), **Language & narration** (chosen per walkthrough), **Appearance**, **Code tree**, **Executables (advanced)**. **Code tree** picks the nesting leader, the collapse control, the caption path, node de-emphasis, the elision style and the height ceiling; **Appearance** tunes the accent colour every other cue derives from, the active-line glow, the non-active-highlight tint, the gap gutter bar, the dim strengths, the callouts and the relationship arrows. With a walkthrough showing (send the `set_plan` above), change a value and click **Apply** — the live tree re-styles immediately, and switching the leader is the one to watch, because it changes which border edges the token box and the nested frame draw at all. **Reset to defaults** restores the judged shape. Values persist across IDE restarts (stored in `audioExplainer.xml`).

**Phase 4 check:** with a walkthrough active, open the **Audio Explainer** tool window (right stripe).
The sidebar shows the title, an outline, the current segment, and a progress bar. **Play/Pause**, **Next**,
**Prev**, and clicking an outline entry move the walkthrough — and the tree view follows. **Save** is a
split button (`Saving.md`): the first Save asks for a name and writes to `<project>/.walkthroughs/`, later
Saves write the same file with no dialog, the caret's **Save As…** always asks (and confirms replacing a
different walkthrough). An unsaved walkthrough is asked about before a new generation, the sidebar's
Close, or the project closing. The idle view lists saved walkthroughs to reload — only files the saver
could have written, so an agent's stray POST payload never shows as a blank row. Audio (speed/voice + narration) is stubbed until the TTS phase. If JCEF is unavailable, the
window falls back to a plain Swing control panel wired to the same walkthrough. While a walkthrough generates,
the banner's **Run log** (also **Tools → Audio Explainer → Show Run Log**, or the tool window's title button)
lists every step of the run as it happens: each read, each think and how long it took, the plan sent and the
plugin's reply, and how the run ended. The last run's log stays available after it ends.

**Phase 5 check:** open a file, right-click in the editor → **Explain This (Audio)** appears (and is
enabled only with a file open). Place the caret in a method (or select some code) and click it: an
"Explaining code…" background task runs and a balloon notification echoes the captured context — the
project-relative file, the target (selection line range or `symbol at line N`), the caret line, and a
selection preview. That is exactly the `{file, caretLine, selectionText}` Phase 6 will hand to a headless
`claude -p` (cwd = project root), which expands the symbol, plans, and POSTs the plan to the loopback
server. The C# symbol is deliberately **not** resolved in-plugin.

**Phase 6 check:** with the **Claude CLI on PATH**, right-click a method → **Explain This (Audio)**. The
plugin installs its `audio-explainer` skill to `~/.claude/skills/` (first run), then runs a headless
`claude -p` that reads the code under your cursor, builds a walkthrough plan, and POSTs it to the loopback
server. The **Audio Explainer** tool window activates, the sidebar shows the outline, the tree view
follows, and a "Walkthrough ready" balloon appears. If `claude` isn't found, or Claude errors, a
notification explains why. Audio narration is stubbed until Phase 7 — the walkthrough loads and highlights,
but nothing is spoken yet. The same skill also works **interactively** in Claude Code ("explain how X
works"), driving the sidebar over the same loopback contract.

**Phase 7 check:** run **Tools → Audio Explainer → Set up TTS** and choose **Install Kokoro** (or install
manually, see Prerequisites) — a background task pip-installs the deps and downloads the ~340 MB voice
model. Then load a walkthrough and press **Play** in the sidebar: each highlight is **spoken** while the
tree view and the sidebar outline advance in sync; **Pause** freezes audio and **Play** resumes it;
**Next/Prev** move by highlight (Shift = by segment); the **speed** buttons and **voice** dropdown take
effect on the next highlight. If TTS isn't set up, playback is silent (walkthrough still loads + highlights)
and a one-time setup offer appears. Verify the TTS server standalone with
`python ~/.claude/skills/audio-explainer/scripts/tts_server.py --check` (→ `"ready":true`) or, with no
model, `--synthetic` (a sine tone proves the audio path).

## Other models — unsupported by Anthropic
Explain This can run on a model other than Claude by pointing **your own Claude Code** at another provider.
The prompt and the skill stay the same; only the model behind the agent changes. Anthropic does not
endorse or support routing Claude Code to non-Claude models, and the plugin ships neither the CLI nor a key.

- **Settings → Tools → Audio Explainer → Explanation → Provider → Manage…** adds a profile from a preset:
  OpenRouter, DeepSeek, Kimi, GLM (Z.ai), Qwen, Ollama or Custom. Each profile has a name, a base URL (the
  Anthropic-style endpoint), a model id and an API key. The key goes to the IDE password safe, never into
  `audioExplainer.xml`.
- **Provider** selects a profile; "Claude Code (your own setup)" is the default and injects nothing. With a
  profile selected, **Model** is that profile's model id (editable), and Effort and Depth work as before.
  For OpenRouter, **Browse…** beside it fetches OpenRouter's public model list (no key is sent) and shows
  the models with tool use, with price per million tokens and context size; type to filter.
- **Test connection** asks the provider for one word through your Claude Code and reports the answer, a
  rejected key, or the provider's error.
- **A run that loops.** When the model re-reads what it already read for the 4th time, the banner, the
  status bar and the "Walkthrough ready" notification warn that it may have lost the task. The run log
  shows which OpenRouter host served each turn; if one host keeps looping, add it to **Ignored Providers**
  in your OpenRouter account's preferences.
- **Your code is sent to the provider.** Free models (`:free` on OpenRouter) may train on what they are sent.
  Cost is the provider's; the run log shows `cost n/a` because the CLI prices every token as Claude's.
- Verified on OpenRouter with `z-ai/glm-5.3-flash` and `deepseek/deepseek-v4.1-flash`; the other presets
  follow each provider's documentation.
- How it works: each run gets a short-lived `--settings` file whose `env` sets `ANTHROPIC_BASE_URL`, the
  token and every model slot. The file is deleted when the run ends, and a rejected key (401/403) stops the
  run at the first retry instead of after the CLI's ~3 minutes of retries.

## Notes
- Verify `org.jetbrains.intellij.platform` (2.5.0), Kotlin (2.1.0), and the Rider version against
  current releases before the first build — bump if Gradle reports resolution errors.
- `kotlinx-serialization-json` is bundled as `implementation`; if a version clash with the
  platform-bundled copy appears at runtime, switch it to `compileOnly`.
