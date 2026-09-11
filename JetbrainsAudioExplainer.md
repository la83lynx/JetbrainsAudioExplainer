# JetBrains Audio Explainer — what is left

A Rider (and all-JetBrains-IDE) plugin that adds an **"Explain this (Audio)"** right-click
action: it audio-explains the selected code — or the method/class under the cursor — while
highlighting the relevant lines, using **Claude Code (headless)** as the reasoning backbone.

> **This file used to be the phase-by-phase build log. Phases 1–7.5 are done, judged, and cut** — what they
> decided lives in the code and its KDoc, and the log itself is in git history (`git show
> 4fead42:JetbrainsAudioExplainer.md`) for anyone who needs the reasoning behind a delta from upstream.
> What follows is only what has not been done.
>
> The multi-language work (L1–L9: Piper, Windows SAPI, espeak-ng, the voice manager, ~50 languages) has its
> own document — `ExplainerLanguages.md`. It is all built and engine-gated; what it still wants is one
> `runIde` session, and that session is listed below too.

---

## Phase 8 — Package & deploy (Marketplace) · **the remaining work**

Signing uses a **self-generated** key pair (free, `openssl`) — **no CA certificate purchase**.
JetBrains re-signs on upload; the signature only proves integrity/authorship. Total cost $0.

1. `./gradlew buildPlugin` → distributable `.zip`.
2. Generate the signing key pair once (`openssl`): `CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
   `PRIVATE_KEY_PASSWORD` (store as secrets; **never commit**). `./gradlew signPlugin`.
3. `./gradlew verifyPlugin` (IntelliJ Plugin Verifier) against the target Rider build range —
   run before every upload.
4. Marketplace: create a JetBrains account + **vendor profile** (free). First upload of the
   signed `.zip` (web UI, or `./gradlew publishPlugin` with a Marketplace **permanent token**)
   goes through **manual moderation** (~a couple of business days); later updates are automated.
   Use `stable` vs. `eap`/`beta` channels to stage releases.
5. `plugin.xml` metadata: reverse-DNS `id`, `name`, `vendor`, HTML `description`,
   `change-notes`, `<idea-version since-build/until-build>`, `pluginIcon.svg`. List as
   compatible with all IntelliJ-based IDEs (it is a pure JVM frontend plugin), not just Rider.
6. Document the external prerequisites (Claude CLI; Python + a TTS engine) in the description; the plugin
   must show a clear first-run diagnostic if any is missing.
7. Ship `LICENSE-code-explainer` (MIT + Srujan Gurram copyright) in the repo **and** inside the `.zip`.

**Two things to settle before the first upload**, both currently pinned to this machine:

- **`build.gradle.kts` targets a local Rider install** (`local("C:/Program Files/JetBrains/…")`) because
  `runIde` needs the .NET/ReSharper backend that the downloadable `riderRD` package does not carry. For a
  release build — and for any CI — swap to `rider("<version>")`, which is compile-only and reproducible.
- **`untilBuild = "261.*"`.** Widen or clear it once the supported range is decided; as it stands the plugin
  refuses to load on the next branch.

---

## Also open

- **One `runIde` session over the narration tiers** — seven checks, listed in `ExplainerLanguages.md`. All of
  L1–L9 is code-complete and gated end-to-end against the real engines; what none of it has had is a human
  driving the UI. The two that carry real risk are **L4** (set a language, right-click Explain This — the
  full Claude → prompt → wire → voice path, never once run end to end) and **L9** (the Piper voice-manager
  dialog, which nobody has seen). The same session should pick up the unjudged UX batch: the localized
  `Explain This` label, the Solution Explorer entry, and the saved list's filter / delete / delete-all.

---

## Known limitations — recorded, deliberately not fixed

- **`ensureInstalled()` compares a version marker, not content.** A skill file edited *after* an install at
  the same version never propagates: the installed copy is only refreshed when `claude-skill/VERSION`
  differs. It bites a **dev machine** — where a bundled doc gets corrected without a bump — and not a clean
  install, which is why it is recorded rather than fixed. If it ever bites a user, the fix is to compare
  content, not the marker.
- **Undo after a re-explain is one level deep.** The server-side pending-buffer/adopt design was descoped:
  `ExplainerServer`/`Walkthrough` are app-level singletons and `set_plan` carries no project id, so
  adopt-then-Undo avoids the whole request-correlation and cancel problem. With autoplay off a replacement
  loads paused anyway.
- **Read-along sync is sentence-anchored, not per-word.** No engine in any tier reports word timings —
  espeak's callback gets `espeak_EVENT*` and drops it, SAPI is called without `SpeakProgress`, Kokoro and
  Piper have none — so a word's position inside its sentence is `floor(local × wordCount)`, worth about ±1 s
  mid-sentence. Sentence starts are exact (they are sums of decoded buffer durations). Intra-word granularity
  and punctuation-pause modelling would need a forced-alignment pass.
- **`audioExplainer.xml` edited while the IDE is running does nothing.** A `PersistentStateComponent` holds
  its state in memory and writes the file on save, so the in-memory copy wins and then overwrites. Edited
  with the IDE closed, it is read back correctly into both the editor decorations and the tool window.
  File-watching was considered and **declined** — not worth the resources.
- **The dark-theme editor background was environment-dependent.** `SpotlightRenderer.colors()` reads the
  *per-editor* `editor.colorsScheme` rather than the global one, which fixed it. If a white wash ever
  recurs, derive the glow by lightening/darkening the actual background instead of blending an absolute
  colour.

---

## License / attribution

code-explainer is **MIT (Copyright (c) 2026 Srujan Gurram)**. Reuse — including on the public
Marketplace, including modified — is permitted. **The obligation is to ship the original MIT text + copyright
line alongside the reused assets**, which is `LICENSE-code-explainer` in the repo and inside the distributed
`.zip` (Phase 8 item 7).

What is reused, and therefore what the obligation covers: the agent skill (`SKILL.md`, `docs/*`), the plan
JSON schema and wire protocol, the sidebar webview (`sidebar.{html,css,js}`), and the Kokoro-82M model +
voice set. What was rewritten and is this project's own: the whole Kotlin IDE adapter, the loopback server,
the headless Claude invocation, and `tts_server.py` (upstream's was Apple-only `mlx-audio` over a Unix
socket; this is `kokoro-onnx` + Piper + SAPI + espeak-ng over TCP).

---

## Architecture

```
Rider ──"Explain this" action──▶ gather {file, caretLine, selectionText}
  │                                        │
  │                     shell out: claude -p  (skill loaded, context as prompt)
  │                                        │
  │      plugin loopback HTTP  ◀──POST /api/message {set_plan …}── Claude
  │             (127.0.0.1:port, token in ~/.claude-explainer-*)
  ▼
tree-of-code tab (N node editors + links)  +  JCEF ToolWindow (controls + audio)
                                               ▲
                                        TTS server (TCP loopback)
```

### Key design decisions (locked)

1. **Trigger = headless `claude -p`.** The IDE initiates; Claude generates the plan and POSTs
   it back. No chat window, one click.
2. **Symbol expansion delegated to Claude.** Do **not** resolve the enclosing C# method/class
   in-plugin — that needs Rider's ReSharper .NET backend (frontend/backend RdProtocol split).
   Pass `{file, caretLine, selectionText}` and let Claude, which reads the file, decide the
   boundaries. This is what keeps the plugin a single JVM frontend module, and therefore what makes
   it run unchanged in IDEA, PyCharm, WebStorm and the rest.
3. **Locators are text, never indices.** A plan anchors on a line plus a token plus an occurrence —
   never a column, never a sentence number. An agent can quote a name out of code it just read and
   cannot predict where a regex or a renderer will cut it.
4. **Distribution = public JetBrains Marketplace**, signed with a self-generated cert (no CA purchase; $0).

---

## Rider-specific gotchas

- **EDT threading:** all editor/markup/tool-window mutations on the EDT; server callbacks arrive
  off-thread.
- **No in-plugin C# symbol resolution** — see decision 2.
- **JCEF availability:** present on standard runtimes but can be disabled — keep the Swing fallback real.
- **Claude CLI latency/availability:** a subprocess with cold-start cost and an external dependency —
  surface "Claude not found / still thinking" states.
- **A sandbox IDE holds a Windows jar lock**, so `runIde`/`buildPlugin` fail in `prepareSandbox` while one
  is still running. Close it first.

---

## Prerequisites (developer machine, Windows)

- **JDK 21** (Temurin) + `JAVA_HOME`. Kotlin comes via Gradle — no separate install.
- **Git.** Optional but recommended dev IDE: IntelliJ IDEA Community (best plugin-devkit support).
- **Node is NOT required** — the VS Code extension is not ported; the sidebar HTML/CSS/JS are
  static assets served into JCEF, with no build step. (`node` is used only to run `probes/ClockProbe.js`.)
- **Claude CLI** on PATH.
- **Narration:** Python 3.10+, plus whichever engine tier is being tested — `kokoro-onnx`/`onnxruntime`
  and the Kokoro model, `piper-tts`, espeak-ng. **Tools → Audio Explainer → Audio engines…** installs them.
- **Phase 8:** `openssl` for the signing key pair; a JetBrains vendor account.
