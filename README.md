# JetBrains Audio Explainer (Rider) — Phase 1–2 skeleton

Native IntelliJ-Platform plugin that adds **"Explain This (Audio)"** to the editor context menu:
audio-explains selected code / the symbol under the cursor, with synced line highlighting, using
**Claude Code (headless)** as the backbone. See `../JetbrainsAudioExplainer.md` for the full plan.

This skeleton covers **Phase 1** (loads in Rider), **Phase 2** (loopback server implementing the
reused wire protocol), **Phase 3** (editor spotlight highlighting driven by walkthrough state),
**Phase 3.5** (a Settings page under **Tools → Audio Explainer** to tune the highlight appearance),
**Phase 4** (the JCEF sidebar tool window that reuses the upstream webview UI, with a Swing fallback),
**Phase 5** (the **Explain This (Audio)** editor context-menu action that captures context and runs a
background task), **Phase 6** (the headless Claude backbone: the action shells out to `claude -p`,
which generates a walkthrough plan for the code under the cursor and POSTs it back), and **Phase 7**
(cross-platform **Kokoro TTS**: a local `kokoro-onnx` server over TCP plus the narration driver that
speaks each highlight in sync with the editor spotlight, with a guided one-click setup). Phase 8
(packaging) and Phase 7b (a Windows SAPI emergency fallback) are `TODO`.

## What's here
- `build.gradle.kts` / `settings.gradle.kts` — Gradle + IntelliJ Platform Gradle Plugin 2.x, targeting Rider 2026.1.4 (branch 261).
- `src/main/resources/META-INF/plugin.xml` — tool window, context-menu action, startup activity, app service.
- `com/audioexplainer/ExplainerServer.kt` — HTTP loopback server (full wire contract: `/api/health|state|actions|message|save|load|walkthroughs`), drops `~/.claude-explainer-{port,token}`.
- `Walkthrough.kt` — walkthrough state machine (port of upstream `walkthrough.ts`).
- `EditorHighlighter.kt` — editor spotlight highlighting (port of `highlight.ts`); the walkthrough's `WalkthroughListener`. Reads appearance from the settings service.
- `AudioExplainerSettings.kt` — app-level `PersistentStateComponent` (`audioExplainer.xml`) holding the tunable highlight appearance.
- `HighlightSettingsConfigurable.kt` — the **Settings → Tools → Audio Explainer** page (Kotlin UI DSL); live-re-styles an active walkthrough on Apply.
- `WalkthroughStorage.kt` — per-workspace save/load under `<project>/.walkthroughs/` (port of `storage.ts`).
- `ExplainThisAction.kt` — the **Explain This (Audio)** context-menu action: captures the caret/selection + project-relative file, packages an `ExplainRequest`, and runs a cancellable "Explaining…" background task.
- `ExplainRequest.kt` — the context handed to Claude (`{file, caretLine, selectionText}` + project root / absolute path / selection line range).
- `ClaudeExplainer.kt` — app service that turns an `ExplainRequest` into a walkthrough: finds `claude`, ensures the skill is installed, runs headless `claude -p` (streaming progress + cancellation), then reports success/failure and activates the sidebar.
- `ClaudeSkillInstaller.kt` — copies the bundled `audio-explainer` skill to `~/.claude/skills/audio-explainer/` (idempotent + versioned).
- `resources/claude-skill/` — the reused Claude skill: `SKILL.md`, `docs/*` (incl. the headless `explain-at-cursor.md`), `scripts/explainer.{sh,ps1}` (the PowerShell port avoids a `jq`/git-bash dependency), and `scripts/tts_server.py` (the Kokoro TTS server). Enumerated by `manifest.txt`.
- `TtsService.kt` — app service that owns the Kokoro `tts_server.py` process (TCP loopback), streams synthesis per highlight, detects prerequisites, and drives the pip-install + model-download setup.
- `NarrationController.kt` — the Phase 7 playback driver: walks each segment's sub-highlights, moves the spotlight, streams `audio_chunk`s to the webview, and advances on `playback_complete`. Single-thread executor (no locks; deadlock-free against the walkthrough).
- `TtsSetup.kt` / `ConfigureTtsAction.kt` — the guided first-run/on-demand TTS setup (offer + background install) and the **Tools → Audio Explainer → Set up TTS** action.
- `AudioExplainerToolWindowFactory.kt` — builds the sidebar: JCEF when supported, else Swing fallback.
- `AudioExplainerSidebar.kt` — JCEF sidebar hosting the reused webview (`resources/sidebar/*`), bridged to the walkthrough via `JBCefJSQuery`.
- `AudioExplainerSwingSidebar.kt` — no-JCEF fallback: basic transport controls driving the walkthrough.
- `resources/sidebar/sidebar.{html,js,css}` — the ported webview (html/js near-verbatim from upstream; css is a JetBrains-theme-aware rewrite).
- `Protocol.kt` — `Plan` / `Segment` / `Highlight` / `UserAction` matching upstream `types.ts` exactly.
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
     -d '{"type":"set_plan","title":"demo","segments":[{"id":1,"file":"a.cs","start":1,"end":5,"title":"intro","explanation":"the whole thing","highlights":[{"start":2,"end":3,"ttsText":"hello"}]}]}'
                                                                             # -> {"ok":true}
curl -s -H "$AUTH" http://127.0.0.1:$PORT/api/state                          # -> status "paused", segment echoed back
```

Note the corrected segment schema: `{id, file, start, end, title, explanation, highlights:[{start, end, ttsText, explanation?}]}` — 1-based, inclusive. (An earlier draft mistakenly put `ttsText` on the segment; that came from a stale upstream doc.)

**Phase 3 check:** a `set_plan` whose `segment.file` points at a real file (relative to the open
project, or absolute) makes Rider open that file and spotlight it: the segment lines are shown at
full brightness, everything outside is dimmed, the first highlight gets a gold glow + overview-ruler
mark, other highlights are lightly dimmed, and context ("gap") lines between highlights get a gold
gutter bar. `stop` (or a `stop` agent message) clears all decorations. The four layers are the
IntelliJ analogues of upstream's four `TextEditorDecorationType`s (see `EditorHighlighter.kt` for the
opacity→foreground-blend mapping).

**Phase 3.5 check:** open **Settings/Preferences → Tools → Audio Explainer**. The page exposes the
dim strengths, glow/accent colours, the non-active-highlight tint, and the gap gutter bar. With a
walkthrough showing (send the `set_plan` above), change a value and click **Apply** — the live
spotlight re-styles immediately. Values persist across IDE restarts (stored in `audioExplainer.xml`).

**Phase 4 check:** with a walkthrough active, open the **Audio Explainer** tool window (right stripe).
The sidebar shows the title, an outline, the current segment, and a progress bar. **Play/Pause**, **Next**,
**Prev**, and clicking an outline entry move the walkthrough — and the editor spotlight follows. **Save**
prompts for a name and writes to `<project>/.walkthroughs/`; the idle view lists saved walkthroughs to
reload. Audio (speed/voice + narration) is stubbed until the TTS phase. If JCEF is unavailable, the
window falls back to a plain Swing control panel wired to the same walkthrough.

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
server. The **Audio Explainer** tool window activates, the sidebar shows the outline, the editor spotlight
follows, and a "Walkthrough ready" balloon appears. If `claude` isn't found, or Claude errors, a
notification explains why. Audio narration is stubbed until Phase 7 — the walkthrough loads and highlights,
but nothing is spoken yet. The same skill also works **interactively** in Claude Code ("explain how X
works"), driving the sidebar over the same loopback contract.

**Phase 7 check:** run **Tools → Audio Explainer → Set up TTS** and choose **Install Kokoro** (or install
manually, see Prerequisites) — a background task pip-installs the deps and downloads the ~340 MB voice
model. Then load a walkthrough and press **Play** in the sidebar: each highlight is **spoken** while the
editor spotlight and the sidebar outline advance in sync; **Pause** freezes audio and **Play** resumes it;
**Next/Prev** move by highlight (Shift = by segment); the **speed** buttons and **voice** dropdown take
effect on the next highlight. If TTS isn't set up, playback is silent (walkthrough still loads + highlights)
and a one-time setup offer appears. Verify the TTS server standalone with
`python ~/.claude/skills/audio-explainer/scripts/tts_server.py --check` (→ `"ready":true`) or, with no
model, `--synthetic` (a sine tone proves the audio path).

## Notes
- Verify `org.jetbrains.intellij.platform` (2.5.0), Kotlin (2.1.0), and the Rider version against
  current releases before the first build — bump if Gradle reports resolution errors.
- `kotlinx-serialization-json` is bundled as `implementation`; if a version clash with the
  platform-bundled copy appears at runtime, switch it to `compileOnly`.
