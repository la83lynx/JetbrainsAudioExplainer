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

- **The source check's disk path is not judged in a sandbox.** `PlanSourceCheck` is asserted in `PlanProbe`
  over in-memory files, and four headless runs used it through a mock server. But `ProjectSourceLines` (VFS
  refresh plus a document read under a read action on the HTTP thread) has only been compiled. One
  synthetic `set_plan` with a planted bad token, POSTed into a sandbox, closes it. Also untested: whether the
  "do not write a validator" prompt line holds at `high`/`xhigh`, the only effort that ever wrote one.
  Plan: `git show 2d3d4df:SourceCheck.md`.
- **The effort/depth/model knobs, the "Explain This with…" dialog and the diagnostics are built but not
  judged in a sandbox.** Everything pure about them is asserted — `probes/EffortProbe.java`, 49 checks,
  including the 14 that hold the Standard-depth prompt byte-identical to what shipped before depth existed —
  but no session has yet seen the Explanation group, the per-run dialog or the diagnostics list on screen.
  The one behaviour a probe cannot reach is the default itself: a fresh install now passes `--effort medium`
  instead of inheriting `~/.claude/settings.json`, which is a **deliberate override of a setting the user made
  elsewhere** and needs a line in the release notes. Two deltas from the plan, both behavioural: `PowerShell`
  is now on `ALLOWED_TOOLS` (the plan had a diagnostic row and no fix, which would have shipped a check nobody
  could act on — `Bash` was already command-unrestricted, so it grants nothing new), and Depth's combo has no
  blank item. Plan: `git show f873b3d:Effort.md`; measurements: `git show 13bba05:Speed.md`.
- **Re-check Japanese narration by ear.** Everything else in the narration tiers is judged — the `runIde`
  session of 2026-09-16 passed all seven checks plus the UX batch, the install console and the C++ row, and
  confirmed the three fixes from the round before (see `ExplainerLanguages.md`). The one thing it found is
  the one thing still open: Kokoro's Japanese was being phonemized by espeak, which reads kanji aloud as
  *"Chinese letter"*. `KokoroEngine` now routes Japanese through misaki and its embedded identifiers through
  espeak-en. Verified by measurement, **not** by ear — 48.8 s of audio for one step became 13.6 s and a
  sentence that used to die on the 510-phoneme cap now survives — so what it still wants is somebody
  listening to a Japanese walkthrough. Same for the Latin identifiers inside it: `Interlocked.Exchange`
  should now sound like English words, not letters.
- **The C++ build-tools row is reported but unproven on a machine that lacks a compiler.** Everything it
  says was measured on a machine that *has* one; the "no C++ build tools found" branch has never been on
  screen. Low stakes — it neither blocks nor is load-bearing — but it is the one state nobody has seen.

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
5. **Links are declared on the frame; the step's ranges decide when they show.** A `relations` entry belongs
   to a *frame* because a relation outlives any one step, and it is drawn on whichever step's **first active
   range** holds one of its two endpoints (`Endpoint.inStepBand`, one owner — `PlanLoader` reports a relation
   no step will ever draw). A step's `beats` are a different thing entirely: token *pulses* timed to a phrase
   of the narration. So steps do control which links appear, by what they light rather than by listing them.
   **The cost, and it is real:** an author cannot say "show this link on step 5" without step 5's opening
   range covering an endpoint. Per-step link lists were considered and not built — they would let the same
   link be declared five times and drift.

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
