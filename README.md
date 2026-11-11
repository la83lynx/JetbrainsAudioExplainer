# Audio Explainer for JetBrains IDEs

Right-click code → **Explain This (Audio)**. An AI agent reads the code under the cursor (or the selection, or
a file/folder in the project view), writes a walkthrough, and the plugin plays it: a **tree of code** tab
shows only the lines that matter, nests each callee under the line that calls it, highlights the active lines
and draws the links between them, while a local TTS voice narrates each step.

A pure frontend plugin: it works in Rider and every other IntelliJ-based IDE. The C# (or any) symbol is never
resolved in-plugin — the agent reads the file and decides the boundaries.

## Requirements

- **An agent:** the [Claude Code](https://docs.claude.com/en/docs/claude-code) CLI on PATH (the default), or
  an [ACP](https://agentclientprotocol.com) agent — see [Other agents and models](#other-agents-and-models).
- **Narration:** Python 3.10+ on PATH. **Tools → Audio Explainer → Audio engines…** installs Kokoro
  (`kokoro-onnx`, `onnxruntime`, the ~340 MB model), Piper, the Japanese/Chinese packs and the rest; the first
  silent walkthrough also offers the setup. Without TTS a walkthrough still loads and highlights, silently.

## Using it

- **Explain This (Audio)** — editor and project-view context menu. The first run installs the bundled
  `audio-explainer` skill into `~/.claude/skills/`, then runs the agent headless. The **Audio Explainer**
  tool window opens with the outline when the plan arrives.
- **Explain This (Audio) with…** — one run with different effort, depth, agent, provider or extra
  instructions. Per-run values never change the saved defaults.
- **The sidebar** — play/pause, next/prev (Shift = by segment), speed, voice, the outline and a read-along
  transcript. **Save** / **Save As…** write to `<project>/.walkthroughs/`; the idle view lists saved
  walkthroughs to reload. An unsaved walkthrough is asked about before it is replaced or the project closes.
- **Run log** — every read, think, the plan sent and how the run ended, live while generating (the banner
  link, the tool window's title button, or **Tools → Audio Explainer → Show Run Log**). A run that re-reads the
  same thing a 4th time is flagged as possibly looping.
- **Settings → Tools → Audio Explainer** — Audio engines · Explanation (agent, provider, model, effort,
  depth) · Language & narration · Appearance · Code tree · Executables (advanced), which holds **Check
  permissions and settings**: read-only diagnostics that each name a fix.
- The same skill works **interactively** in Claude Code ("explain how X works") and drives the sidebar over
  the same loopback contract.

### Narration and languages

Walkthroughs can be written and narrated in ~50 languages; code identifiers stay untranslated. The engine is a
property of the voice, so one language can offer several tiers and the voice list says which is which:

| Tier | Quality | Coverage |
|---|---|---|
| Kokoro | neural, local | 8 languages (Japanese needs the opt-in `misaki[ja]` pack) |
| Piper | neural, offline | ~40 more; **Manage voices…** downloads them |
| Windows SAPI | whatever is installed; neural Edge voices via NaturalVoiceSAPIAdapter (online) | Windows only |
| espeak-ng | robotic | ~140, bundled with Kokoro |

The full reference — voices, the TTS wire protocol, privacy notes — ships as `claude-skill/docs/tts.md`.

## Other agents and models

**Unsupported by Anthropic.** Anthropic does not endorse or support routing Claude Code to non-Claude models.
The plugin ships neither a CLI nor a key. **Your code is sent to the provider you choose**; free models
(`:free` on OpenRouter) may train on it, and the cost is the provider's.

### Provider profiles (behind Claude Code)

- **Settings → Explanation → Provider → Manage…** adds a profile from a preset: OpenRouter, DeepSeek, Kimi,
  GLM (Z.ai), Qwen, Ollama or Custom — a name, an Anthropic-style base URL, a model id and an API key. The key
  goes to the IDE password safe, never into `audioExplainer.xml`.
- "Claude Code (your own setup)" is the default and injects nothing. **Browse…** lists OpenRouter's public
  models with tool use, price and context size (no key is sent). **Test connection** asks the provider for one
  word.
- Each run gets a short-lived `--settings` file whose `env` sets the base URL, token and model slots; it is
  deleted when the run ends. A rejected key (401/403) stops the run at the first retry.
- The run log shows which OpenRouter host served each turn; if one keeps looping, add it to **Ignored
  Providers** in your OpenRouter preferences. It shows `cost n/a`, since the CLI prices every token as Claude's.
- Verified on OpenRouter with `z-ai/glm-5.3-flash` and `deepseek/deepseek-v4.1-flash`.

### ACP agents (instead of Claude Code)

- **Settings → Explanation → Agent**: Claude Code (default), **opencode** (`npm i -g opencode-ai`), **Qwen
  Code** (`npm i -g @qwen-code/qwen-code`, Node.js 22+), or **Custom** — any command that speaks ACP on stdio.
  Leave the executable path blank to search PATH and the usual install directories.
- **The provider profile applies too:** opencode and Qwen Code get its endpoint, model and key in their
  environment; "None" uses the agent's own model. The agent's own configuration is never rewritten.
- **Read-only.** The agent may read and search the project and the skill, and send the plan; every other
  request — edits, shell, fetches — is refused and logged. opencode is configured not to attempt them;
  Qwen Code runs in its `default` approval mode.
- **A Custom agent must be set to ask.** The plugin can refuse only what it is asked about. A tool that
  completes without asking stops the run at once and fails it, naming what it touched — after the fact, so
  check the change.
- An agent that reads files through the IDE (Qwen Code does) sees unsaved editor text. An ACP run cannot
  show the upstream host, the cost, or the plan's frame/step counts while it is being written.
- **Effort:** opencode maps it to the model's reasoning effort via OpenRouter (blank = the model's default);
  Qwen Code uses its own setting. **Diagnostics** shows whether the agent is found, its version and HTTP MCP
  support (and Node.js 22+ for Qwen Code).

## Known limitations

- **Effort defaults to `medium`** and is passed as `--effort`, overriding an `effortLevel` in
  `~/.claude/settings.json`. Choose "Default — inherit" in Settings to use yours.
- **Read-along is sentence-anchored.** No engine reports word timings, so a word's position inside its
  sentence is estimated (±1 s mid-sentence); for Japanese and Chinese it advances per sentence.
- **Undo after a re-explain is one level deep.**
- **`audioExplainer.xml` edited while the IDE runs is overwritten** — edit it with the IDE closed.
- **The installed skill refreshes only when the bundled `VERSION` changes**, not when its content does.
- **Japanese needs `misaki[ja]`,** which on CPython 3.13 compiles `mojimoji` and so needs the Microsoft C++
  Build Tools; Settings detects them and links the download. Chinese works through espeak.
- **SAPI and espeak voices need the Python TTS server running.** Windows' neural Narrator voices are only
  reachable through the user-installed NaturalVoiceSAPIAdapter; Edge voices are online and send the text to
  Microsoft.

## Development

- **JDK 21** + `JAVA_HOME`; Kotlin and the IntelliJ Platform Gradle Plugin come via the wrapper. Node is only
  needed for `probes/ClockProbe.js`.
- `./gradlew runIde` launches a Rider sandbox (close a running one first — it holds a Windows jar lock).
  Our `debug`/`info` lines go to the sandbox `idea.log`, not the console (grep `#com.audioexplainer`).
- `build.gradle.kts` builds against a **local** Rider install, because `runIde` needs the .NET backend the
  downloadable package lacks; switch to `rider("<version>")` for CI or a release build, and decide
  `untilBuild` (now `261.*`) before publishing.
- **Tests are the `probes/`** — `javac`/`node`/`python` checks that import the production classes; there is
  no Gradle test source set. `probes/README.md` has the commands.
- Smoke-test the loopback server against a running sandbox:

  ```bash
  PORT=$(cat ~/.claude-explainer-port); TOKEN=$(cat ~/.claude-explainer-token)
  curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:$PORT/api/health        # {"status":"ok"}
  curl -s -H "Authorization: Bearer $TOKEN" -X POST http://127.0.0.1:$PORT/api/message \
       -d '{"type":"set_plan","title":"demo",
            "frames":[{"id":0,"file":"a.cs","title":"A.Method","explanation":"the whole thing",
                       "keep":[{"start":1,"end":1,"role":"context"},{"start":10,"end":20,"role":"focus"}]}],
            "steps":[{"frame":0,"ranges":[{"start":10,"end":12}],"ttsText":"hello","token":"Method"}]}'
  ```

### Where things are (`src/main/kotlin/com/audioexplainer/`)

| Area | Files |
|---|---|
| Wire schema and plan loading | `Protocol.kt` (the one place a field is declared; its prose is `claude-skill/docs/plan-schema.md` — keep both in step), `PlanLoader.kt`, `PlanOutline.kt`, `PlanSourceCheck.kt` |
| Loopback server | `ExplainerServer.kt` (`/api/*`, writes `~/.claude-explainer-{port,token}`), `McpEndpoint.kt` |
| Walkthrough state, save/load | `Walkthrough.kt`, `WalkthroughStorage.kt`, `SaveFlow.kt` |
| Rendering | `TreePlanController.kt` → `tree/` (`TreePlanView`, `CodeNestView`, `CodeNodeView`, `LeaderArt`), `SpotlightRenderer.kt`, `LinkArt.kt` |
| Running the agent | `ExplainThisAction.kt`, `ClaudeExplainer.kt` (resolves per-run overrides against settings, once), `ExplainPrompt.kt` (argv + prompt as a pure function), `ClaudeHarness.kt`, `acp/`, `Providers.kt`, `RunLog.kt` |
| Narration | `TtsService.kt` (owns `tts_server.py`), `NarrationController.kt`, `Languages.kt`, `TtsSetup.kt` |
| UI | `AudioExplainerSidebar.kt` (JCEF, `resources/sidebar/*`), `AudioExplainerSwingSidebar.kt` (no-JCEF fallback), `HighlightSettingsConfigurable.kt`, `AudioExplainerSettings.kt` |
| Bundled skill | `resources/claude-skill/` — `SKILL.md`, `docs/`, `scripts/explainer.{sh,ps1}`, `scripts/tts_server.py`; listed in `manifest.txt`, versioned by `VERSION` |

## License

The agent skill, plan schema, wire protocol, sidebar webview and Kokoro voice configuration are adapted from
**code-explainer** (MIT, © 2026 Srujan Gurram); its license is `LICENSE-code-explainer`, which must ship in
every distribution. The Kotlin IDE adapter, loopback server, agent harnesses and `tts_server.py` are this
project's own.
