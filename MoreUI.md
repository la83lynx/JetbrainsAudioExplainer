# MoreUI — richer explanation-view details

> Status: **§1–§2 are normative** (they describe code that exists; if they disagree with the source, the
> source wins and this file is stale — fix it). **§3–§5 keep only what still constrains the one unbuilt
> tier (B5 / B1b);** shapes there are illustrative, nothing committed.
>
> Two axes throughout: **Axis A — rendering** (what the editor can draw) and **Axis B — authoring data**
> (what the explainer agent must emit to drive it). The original draft's thesis — *most of the work is
> Axis B, not Axis A* — held up through every tier, including A4, the feature the draft rated hardest to
> render.
>
> Section numbers are referenced from source comments (`§2.4`, `§2.8`, `§4.3 B3`, `§4.3 B4`) — don't renumber.

---

## §1 — What shipped

Baseline (`SpotlightRenderer.kt`, split out of `EditorHighlighter.kt` in `TreeOfCode.md` Phase 1a — every
`EditorHighlighter.<draw-method>` named in older sections below now lives there): whole-line
`RangeHighlighter`s on `editor.markupModel` (`LINES_IN_RANGE`), four layers (dim / segmentDim / gap /
active-gold), gap gutter bar via `LineMarkerRenderer`. Driven by
`WalkthroughListener.onSegment/onHighlight` marshalled to the EDT by `EditorHighlighter`; all decorations
tracked in `tracked`, torn down in `clear()` (was `clearTracked()`).

**`SpotlightRenderer` now has two callers.** `TreeOfCode.md` Phase 1a′ added `tree/CodeNodeView`, which builds
one renderer per node editor. Anything below that changes the renderer's API, its palette or its
`revealCenter` behaviour lands on the node views as well — and two of its assumptions are already known to
break there: the callout is laid out at *node* width rather than tab width, and `revealCenter` scrolls the
editor while nothing scrolls the node into the tree's viewport (`CodeNodeView.onRevealRequest`).

Added since, all settings-gated and additive — **a plan with none of the new fields renders exactly as
it did before**:

| # | Feature | Setting | Notes |
|---|---------|---------|-------|
| ~~A1~~ | ~~**Gutter step badge** — accent disc with the step number, click = navigate to that step~~ | — | **Built, shipped, then removed on use.** The number answered a question the sidebar's step list already answers, and the click did what its outline already does — so it spent gutter width and a `GutterIconRenderer` on duplication. Its two lasting contributions are the tracking discipline it proved (§1 disposal) and the `currentIndex` bug it exposed (§2.5). |
| A2 | **Docked callout** — box of wrapped text docked by the active lines | `callout`, `calloutTintPercent`, `calloutOpacityPercent`, `calloutFrame`, `calloutCornerRadius`, `calloutBarWidth`, `calloutTextScalePercent`, `calloutProseFont`, `calloutBelow`, `calloutLeader` | Indented to the code it describes. Holds one or more independently-wrapped paragraphs, each with its own font/colour (`BlockStyle`) and line cap: prose 3, B4 example 2, B3 pointer 2 each. Five frame styles incl. `comment` (`// ` lines in the scheme's comment colour); the description font is settable, the example is not. |
| B2 | **`Highlight.note`** — optional sentence-length aside | — | Callout shows `note ?: explanation`. |
| B3.1 | **One canonical schema doc** — `claude-skill/docs/plan-schema.md` | — | Cross-cutting prerequisite; done before B2's field was added. |
| B1a | **`Highlight.token` / `occurrence`** — text anchor naming a step's key identifier | — | Resolved by text search over the highlight's own lines; never PSI. |
| A3 | **Token flash** — the anchored identifier pulses from accent into the line's glow | `tokenFlash`, `tokenFlashMs` | `EXACT_RANGE` highlighter on `HighlighterLayer.LAST`, faded over 12 frames. |
| A3.1 | **`playback_started`** — webview → plugin "the playhead reached this step's audio", so the flash fires with the voice instead of 1.8–6.0 s ahead of it | — | Sent from the existing rAF progress loop; carries `segmentId`/`highlightIndex` so a stale one is dropped. See §2.8. |
| B4 | **`Highlight.example`** — optional one-line `input → output` illustration | `calloutExample` | Second paragraph of the *same* callout box, drawn upright (not italic) so it reads as code. §4.3 B4. |
| B3 | **`Segment.relations`** — kind + two endpoint locators | `relations` | Per-segment data, per-*step* rendering: only relations with an endpoint inside the active highlight are drawn. Endpoint `file`s join `Segment.file` in `WalkthroughStorage`'s path rewrite. §4.3 B3. |
| A4 | **Relation arrow / pointer** — the plugin picks the picture | `relations`, `arrowUseAccent`/`arrowColor`, `arrowThicknessTenths`, `arrowHeadSize`, `arrowSquareElbow`, `arrowLabels` | Inside → elbow arrow via `CustomHighlighterRenderer`, routed clear of the code *and of any block inlay* (§2.9), note beside it. Outside → gutter triangle (click = open target) + a third callout block naming it. §4.3 B3. |

### Corrections to the original draft's API notes

Recorded because the wrong version is the intuitive one:

- **A1 needs no disposal bookkeeping.** A `GutterIconRenderer` is a *property* of the `RangeHighlighter`,
  so it dies when the highlighter is removed. (Same for B3's gutter triangle.)
- **A2 uses `relatesToPrecedingText = false`, not `true`.** The box annotates the line *below* it; an edit
  at that offset must carry the callout along with its code, not strand it on the line above.
- **A2's callout is added before `revealCenter`.** The inlay adds height above the active line, so
  scrolling first leaves the block off-centre.

### Disposal discipline

```kotlin
private val tracked = mutableListOf<RangeHighlighter>()        // markup model owns these
private val trackedDisposables = mutableListOf<Disposable>()   // it does NOT own these
```

`clearTracked()` clears both — highlighters via `markupModel.removeHighlighter`, disposables via
`Disposer.dispose`, unconditionally and each in its own `try` (a disposed editor already dropped its
inlays; disposing twice is a no-op). **`trackedDisposables` is the extension point for every remaining
tier** — B5's scheduler belongs there, as A3's `Timer` and A4's renderer do. Anything the markup model
doesn't own goes in that list, or it survives a segment transition and lingers on screen.

---

## §2 — Lessons that constrain the remaining tier

Hard-won during A1–A4. Each one still applies to B5 / B1b.

### 2.1 A skill change is four files, not one

`ClaudeSkillInstaller` copies a **hand-maintained `manifest.txt`**, and `ensureInstalled()` early-returns
when the installed `VERSION` matches the bundled one. So a missing `manifest.txt` entry means the new doc
never installs (the prompt points at a path that doesn't exist), and a missing `VERSION` bump means
existing users keep the old docs — **same symptom, both silent, both only at agent runtime** inside a
headless `claude -p` you aren't watching.

Checklist for anything touching `claude-skill/`: *edit doc → add to `manifest.txt` → bump `VERSION` →
confirm against the built jar.* Currently at `0.9.3`.

### 2.2 Log silence proves nothing (partly fixed)

`runOnEdt` used to catch and discard `Throwable` unlogged — a decoration that failed to render looked
identical to one that rendered perfectly. Now it logs `warn` with the stack trace and rethrows
`ProcessCanceledException` (a cancellation *signal*, never a fault). `resolveThenEdt` warns when a
segment's file can't be resolved — the likeliest fault in a generated plan, previously silent with no
exception at all. Deliberately still unlogged (and commented as such): `clearTracked`'s two teardown
catches (routine, per highlighter per transition) and `indentPx`'s fallback (cosmetic).

**Where the log is:** `.intellijPlatform/sandbox/JetbrainsAudioExplainer/<ide>/log/idea.log`.
`build/idea-sandbox/` is a **decoy** — an older Gradle-plugin layout whose `log/` and `system/` dirs are
empty and always will be, which reads convincingly as "the platform writes no log". The IDE prints
`Log directory: file:///…` at startup; trust that line over any remembered path.

- **`runIde` stdout carries WARN and above only** (measured: 31 WARN, 0 INFO, 0 FINE) — good for startup
  and crash diagnosis, but our `debug`/`info` will never be there. Never pipe it through
  `Select-Object -Last N`: that buffers the whole stream and discards the startup lines.
- `build.gradle.kts` sets `-Didea.log.debug.categories=#com.audioexplainer` on `runIde`, so every dev run
  emits the decoration/narration timeline: `render step i/n`, `token flash: '<tok>' -> line L offsets a-b`,
  `token anchor dropped: …` (otherwise completely invisible), `narrate: spotlight -> …`,
  `narrate: requesting TTS …`, `narrate: first audio chunk after Nms`. They log at **FINE**
  (`java.util.logging`'s name for DEBUG), so grep `#com.audioexplainer`, not the word "DEBUG". This is
  what made §2.8 measurable — and why a *bespoke* log file was the wrong instinct: the platform's file
  appender already had everything, at the level we wanted, where nobody had looked.

**Logs now tell you when a render broke; only a screenshot tells you it rendered.** The failure mode that
costs time throws nothing: a settings gate off, a relation with no endpoint in the active step so nothing
is drawn, `note` absent *and* `explanation` blank so `addCallout` returns early, a colour blend landing
invisible, a measured width of zero. All silent, all "successful" to any log. The working loop:

1. `runIde` sandbox (be patient — the port file can take 4–7 min to appear).
2. POST a hand-written `set_plan` with the exact shape under test. No `claude -p` in the loop.
3. Screenshot via PowerShell `CopyFromScreen` and *look at it*.

Two operational constraints: `CopyFromScreen` returns a fully black bitmap on a sleeping or locked
display — indistinguishable from "the IDE didn't draw", so this loop needs someone at the machine. And
transient decorations race the shutter: a 700 ms fade is usually over before a capture lands, so capture
a burst of region-only frames immediately after the POST, or lengthen the effect first (pre-seeding
`config/options/audioExplainer.xml` before launch is the intended way, still unconfirmed — §2.7).

Synthetic plans also test what an agent won't reliably produce on demand: `note`-over-`explanation`
precedence was proven with a decoy `explanation: "SHOULD NOT APPEAR"` beside a real `note`.

### 2.3 Inlays are measured once; wrap depends on the viewport

`calcHeightInPixels` runs once, but the text re-wraps on every resize — a narrowed editor wants more
lines than the reserved height holds, and the box clips. `CalloutRenderer` handles it with
`measuredForWidth` / `measuredLineCounts` drift detection: `paint` draws only what fits and schedules one
`inlay.update()` via `invokeLater`, guarded by `updateScheduled` so repaints can't queue a pile of
re-measures. `update()` re-enters layout, so it must never be called from inside the paint pass. **Any
future inlay inherits this contract.**

### 2.4 Renderer caps become authoring rules

`MAX_CALLOUT_LINES = 3` is a rendering constant, but it dictates the skill instruction: `note` must be
**one sentence**, and sparse, because a note on every highlight becomes wallpaper. Generalise: when a tier
adds a renderer with a limit, the limit belongs in `plan-schema.md` as an authoring rule *in the same
commit* — otherwise the agent writes to no budget and the renderer silently ellipsises good content.

Three flavours of limit now exist:

- **Truncating, enforced twice.** `MAX_EXAMPLE_LINES = 2` shipped with "one line" in the doc *and*
  enforcement in the renderer (`firstLine` keeps line 1, appends `…`). Where a cap can be enforced
  visibly, do both — the doc tells a well-behaved agent what to write; the renderer keeps a badly-behaved
  one from producing something that looks like a plugin bug.
- **Picture-changing.** `MAX_ARROW_SPAN = 40` and "both ends on different lines" don't cut content — cross
  them and the relation renders as a pointer instead. The authoring rule is "or you get a different
  picture", which an agent can actually reason about.
- **Ordinary.** `MAX_RELATIONS_PER_STEP = 3`: past it nothing is drawn, and a debug line says how many
  were skipped.

### 2.5 Stateful decorations need the step index to be right

A1 was the first decoration that *displays which step it is*, and it exposed a latent bug: `refresh()`
re-rendered from highlight 0, so a mid-walkthrough settings change rewound the spotlight — invisible until
a badge read "1" while narration was on step 3. Fixed by tracking `currentIndex` and re-rendering that.
Anything encoding step identity (flash, arrows, B5's beats) depends on `currentIndex` being accurate
through *every* re-render path, not just `onHighlight`. The badge itself is gone (§1), but note what it
cost to find this: the bug needed a decoration that *shows* the index. Losing that means the next such
regression is invisible again — worth remembering when `currentIndex` next changes hands.

### 2.6 The interaction-wiring pattern

A decoration that has to reach the narration controller — which lives in the tool window — goes through the
listener list, not through a reference:

```kotlin
// AudioExplainerSidebar — finds its own project's highlighter among the walkthrough's listeners
walkthrough.getListeners().filterIsInstance<EditorHighlighter>().filter { it.project == project }
```

`EditorHighlighter.project` is `internal` for exactly this lookup — the same discovery
`HighlightSettingsConfigurable` uses for live re-render, and the route `playback_started` →
`flashCurrentToken` takes today. The badge's click handler (a `@Volatile var onStepClick` written from the
tool window's thread, degrading to `walkthrough.advanceHighlight` when the window had never been opened)
was the original instance and went with A1; the pattern is worth keeping because the *next* interactive
decoration needs it. Note what does **not**: B3's gutter click merely opens a file, so it goes straight
through `resolveThenEdt` → `openEditor` with no route back to narration at all — the cheaper wiring, and
the one to reach for first.

### 2.7 Verification status

**Verified on screen** (synthetic `set_plan` probes, screenshotted — the recipe is in the
`verify-rendering-via-synthetic-set-plan` memory):

- **A3 flash** renders — `HighlighterLayer.LAST` does paint over the active-line glow — and its timing is
  fixed and measured (§2.8). Whole-word-over-substring preference confirmed via the log, which is the
  cheaper loop for anything token-resolution-shaped.
- **B4 callout, three probes** (2026-07-28): note + example as two paragraphs in one box, example-only with
  no leading gap, and the worst case — a 3-line ellipsised note plus a truncated example rendering a
  4-line box with no clipping, i.e. §2.3's measure/paint agreement holding under multi-block layout.
- **B3/A4, six probes** (2026-07-29): flow arrow, `backforth` with both arrowheads, and an outside pointer
  carrying all three `BlockStyle`s in one box. Plus the cases that would have been the real bugs — an arrow
  in a **scrolled** viewport (the only probe that distinguishes content from viewport coordinates, since
  every other one fit on screen); four unusable relations all degrading to pointers with the fourth dropped
  by the cap; the clamped leg (a viewport-filling line pinned it at `room` and suppressed the label); and a
  **synthesized gutter click** opening `FlashProbe.kt` at `7:1` — locate the icon by scanning the gutter
  strip for accent-coloured pixels, then `SetCursorPos` + `mouse_event(LEFTDOWN|LEFTUP)`. Zero
  `decoration render failed`, zero `could not be resolved`; arrows and the flash don't interfere.

**Still open:**

- **Callout resize re-measure** (§2.3) — reasoned, never observed. Also unseen: a **narrow editor**
  re-wrapping several blocks through one shared `measuredForWidth`, and a 5-line box (B3's probes put four
  blocks in one box, but each fit its budget).
- **Whether the agent actually emits `note` / `token` / `relations`.** Needs a real "Explain This" run,
  which would also exercise `ensureInstalled()` and confirm the §2.1 checklist propagated.
- **`occurrence` counting** (`plan2-occurrence.json` staged, not run) and the `token anchor dropped` path
  (`plan3-unresolvable.json`).
- **`findNth` boundary behaviour** traced by hand, not tested: a token ending exactly at `to` resolves, one
  starting at `to` is rejected, `i = idx + 1` guarantees progress, and the empty needle that would spin is
  blocked upstream. There is **no test infrastructure in this project at all** (no `src/test`, no test
  deps) — that absence is the real gap behind this row and the one above it, and it is the cheapest thing
  that would make pure logic verifiable without a 3-minute IDE launch.
- **Whether a hand-written `audioExplainer.xml` in the sandbox config is picked up** (§2.2).
- **§2.9's overlap fix and the appearance knobs** — compiled, reasoned, and not yet seen on screen. The
  case to shoot is the reported one: a relation from a line above into the active step, with a note long
  enough to make the box wider than the code. Then walk the frame styles and `calloutBelow` with the
  settings page open, since it re-renders live.
- **Whether any appearance knob has ever been applied.** The one session that reached the settings page hit
  the spinner ParseException above, so `audioExplainer.xml` still holds no callout/arrow key: every value
  on screen so far has been a default. The clamp is proven at the Swing level but not yet in the real
  dialog — the check there is that OK *closes* and the value lands in `audioExplainer.xml`.
- **§2.10's changes** — compiled, never seen. In order of what could plausibly be wrong: the example
  rule's position inside its own gap; the `comment` frame's wrap (the prefix is charged twice or not at all —
  a clipped last word is the tell); a proportional description font next to a monospace example; and the
  leader's direction under `calloutBelow`. The A1 removal needs only that nothing else vanished with it —
  the callout, the flash and the gutter pointer all still draw. The leader-as-tail is the one part of this
  batch that has been checked *below* the pixel level — `LeaderProbe.java` covers the mouth erase in both
  dock directions, the fill/stroke split per frame, the comment colour, the 25 % clamp on a one-word box and
  the tip staying inside the reserved band, all passing. What that probe cannot answer is whether the shape
  reads as a tail at 14 × 9 px next to real code, and whether 25 % is the right place for it. The font sample
  needs only that picking a family changes it and that the size spinner moves it too.

### 2.8 Time-sensitive decorations must key off audio, never off step render

`NarrationController.narrateCurrent` moves the spotlight **first** and requests TTS **after**:

```kotlin
walkthrough.advanceHighlight(boundedHl)   // ← all decorations fire here, incl. the token flash
…
stream = tts.speak(text, voice, speed, tune, …)   // ← synthesis latency starts here
```

So every decoration precedes audio by the whole synthesis latency. Static decorations (callout, arrows)
don't care — they persist. **For the token flash it was a defect:** measured over three back-to-back
rounds, the flash led the first audio chunk by **1780 ms warm, 5966 ms cold**, so the ~700 ms pulse was
completely over before the first audio byte arrived in *every* round, leaving 1.1–5.3 s of dead air before
the sentence discussing the token was spoken. The same run exposed a **double render** — `set_plan` renders
step 1, then `resume` renders it again (the real Explain flow does this too: plan arrives, user presses
Play), so the flash played twice ~3 s apart for one step.

**`playback_started` is what shipped.** The only component that knows when sound begins is the webview,
because it owns the Web Audio clock. `updateStepProgress` — the rAF loop already driving the read-along
cursor — posts `playback_started` the first time `audioCtx.currentTime` reaches `stepStartClock`, i.e.
when the playhead arrives at the step's first scheduled buffer. Reported from the loop rather than at
scheduling time so a non-zero start offset is honoured; once per step, cleared by `resetStepAudioModel()`.
The message carries `segmentId` + `highlightIndex`, and `flashCurrentToken` drops it if the spotlight has
since moved — otherwise audio starting just after a Next click would flash the *new* step's token at the
*old* step's start time. **Verified** on a 4-step walkthrough with real audio: flash now lands **+6…+9 ms**
after the first audio chunk, down from −1780…−5966 ms. Steps with no `token` correctly flashed nothing.

**Why not the one-line "flash on first chunk" fix.** Not the buffer-depth argument that was originally
made — decode-and-schedule turned out to be 6–9 ms, so for a warm step the cheap fix would have been
nearly as good. What justifies `playback_started` is the case the measurement didn't exercise: with the
AudioContext **suspended** (before the first click in the panel, or while paused), chunks queue in
`pendingChunks` and become audible seconds later or never. "First chunk" keys off a moment that can precede
sound indefinitely; the playhead cannot. That, plus the channel §5 wanted anyway.

Consequences:

- **Renders no longer flash**, which removes the double flash for free: `set_plan` renders step 1 while
  paused and silent, and nothing pulses until audio begins. Two deliberate exceptions — `refresh()` (the
  settings page's live re-render, where the flash toggle and duration live, so a preview that never shows
  the animation is useless) and narration switched off entirely (`ttsEngine == off`), where no audio event
  will ever come. That check reads the *setting*, not narration runtime state, so there is no stale flag.
- **Engine selected but not installed** is the remaining gap: narration is silently disabled, so no
  `playback_started` and no flash. That state produces no audio at all and prompts the user to finish
  setup, so a missing pulse is not the complaint they'd have. Same for a walkthrough loaded with the tool
  window never opened — no webview, no audio, no `playback_complete`, and it can't advance past step 1.

Generalises: step render is an unreliable proxy for "the user is hearing this", and the plugin *cannot*
answer that question — only the webview can. This message is the beginning of the playback-clock channel
B1b/B5 need (§5).

### 2.9 Inlays paint over custom renderers, and no layer can change that

Reported from real use: relation arrows and their labels disappeared **behind** the callout box.

`ARROW_LAYER` is already `HighlighterLayer.LAST`, but `HighlighterLayer` only orders highlighters against
*each other* — the platform paints block inlays in a later pass than custom highlighter renderers, so an
inlay always wins against a `CustomHighlighterRenderer` no matter what layer it sits on. **There is no
z-index for this pair.** The choices are geometry (route clear of the box) or transparency (let the leg
read through it), and the first one is the real fix.

Three things had made the collision the *normal* case, not an edge case:

- The channel was placed from the code's line ends only, so **the box was invisible to the routing**.
- The box wraps to the full viewport, so it is usually *wider* than the code — which pushed the channel
  onto the clamp at `room`, i.e. straight through the box.
- The box is docked above the active line, and an arrow is drawn only when an endpoint is in the active
  highlight — so for the ordinary "from a line above into this step" relation, the box's band lies exactly
  between the two ends. The **label** is worse off than the leg: it is drawn at the vertical midpoint,
  which is inside that band almost by construction.

Fixed by making each half of the problem the other half's business: `inlayRight()` adds any block inlay
crossing the leg (ours *and* other plugins') to the channel `max`, and `arrowReserve()` shrinks the box's
wrap width by the leg's clearance — plus label room when a label exists — but **only for steps that
actually have arrows**, so an ordinary walkthrough's box is unchanged. `Inlay.bounds` is the right query
(null = folded away, cannot collide); the reserve is floored by `MIN_TEXT_W`, so an editor too narrow to
honour it loses the reserve rather than the text. `calloutOpacityPercent` remains as the escape hatch for
the case geometry can't win: code genuinely wider than the window, where the leg is clamped over both.

Generalises: **a decoration that reserves layout beats one that only draws.** §3's "prefer a custom
renderer when you don't need to displace text" still holds, but the two must be designed as neighbours —
whenever both are on screen at once, the drawn one has to be told where the reserved one is.

**A bounded spinner rejects out-of-range text by throwing from OK.** Found the first time the new knobs
were opened for real: typing `0` into the then-`20..100` opacity spinner and pressing OK produced
`java.text.ParseException: Value not within min/max range` out of `RiderSettingsDialog.doOKAction`, logged
SEVERE against this plugin, and the dialog would not close. No frame of ours is in that stack —
`JSpinner.NumberEditorFormatter` delegates its min/max to the spinner model, `InternationalFormatter`
refuses the *text*, and the dialog commits every formatted field on OK. So the trap was never specific to
the appearance rows: **all 14 spinners on the page had it**, including brightness percentages that predate
this work. Fixed once, centrally, with a `clampedSpinner` helper whose formatter clamps in `stringToValue`
instead of throwing (unparseable text reverts to the last committed number), and the opacity floor dropped
to 0 — legitimate, because only the box's *fill* carries that alpha, so at 0 the text, bar and outline all
still draw, which is the one look no frame style gives on its own.

Both halves are **verified headlessly**, without an IDE launch: `scratchpad/SpinnerClampProbe.java` mirrors
the helper in plain Swing and drives `commitEdit()` the way `doOKAction` does. The bounded spinners
reproduce the log's message verbatim on `0`, on `500` and on a cleared field — including a `0..100`
brightness spinner that predates all of this — and every clamped one commits, snaps to the bound and keeps
the model's own bounds on the arrow buttons. That pattern is worth reusing: **a Swing-level defect can be
reproduced in a `javac` one-liner**, which is a 3-second loop against a 3-minute one. (Invalid text stays
in the field in that probe only because nothing there ever loses focus; the editor's default
`COMMIT_OR_REVERT` re-installs the formatter on focus loss, which re-renders from the model.)

Generalises: **a settings control that can hold a value the dialog will reject is a way to wedge the
dialog**, and the range you picked to be helpful is exactly where someone types the value you excluded.

**Appearance knobs shipped with the fix**, since both decorations had their look wired to constants: the
callout gets a frame style (bar / outline / solid / none), corner radius, bar width, text scale and
above-or-below placement; arrows get their own colour (independent of `accentColor`), thickness, head size,
square-or-rounded elbow and a label toggle. All of them default to the values the renderers used as
constants, so a fresh install draws exactly what it drew before. Two notes for anyone extending them:
the text scale goes through the *same* `metrics()` both `calcHeightInPixels` and `paint` use, which is what
keeps §2.3's contract intact; and an arrow's label follows the *arrow's* colour while the callout's pointer
block stays on the accent, because the pointer line belongs to the box, not to the arrow.

### 2.10 A decoration has to answer something nothing else answers

The first thing use produced was a **deletion**: the A1 gutter badge went, because the number it drew was
already in the sidebar's step list and its click already existed as that list's outline. It read as a
feature while it was a plan and as clutter once there was a sidebar beside it. The rule that survives is
about *the set*, not the decoration: an editor decoration competes with every panel already on screen, so
the test is not "is this useful" but "is this the only place this is answered". Cost of the deletion was
~120 lines across four files, and two things had to be said out loud rather than deleted quietly — §2.5's
`currentIndex` bug was *found* by the badge, and §2.6's click-wiring pattern was *demonstrated* by it.

Four appearance changes shipped with it, and three are worth a line each because they push on contracts:

- **A spacer with a rule before the worked example.** The gap comes from one `gapBefore(block, drawn)` that
  both `textHeight` and `paint` call — the §2.3 contract again, and the reason the rule itself needs no
  height: it is drawn *inside* the gap it was given.
- **A `comment` frame style** — no panel, every line prefixed `// `, all text in the scheme's own
  `LINE_COMMENT` colour, so the aside reads as a comment somebody wrote in the file. This is the one frame
  that is not pure decoration: the prefix is real width, so it is charged to the wrap budget in `lines()`
  *and* added back in `textWidth()`. A prefix charged on one side only is how a box clips its own last word.
- **A settable description font, and deliberately not for the example.** Prose and pointer lines follow
  `calloutProseFont`; `BlockStyle.CODE` stays on the editor font, because code in a proportional face stops
  reading as code. An unknown family costs the family (`java.awt.Font` falls back), not the callout.
- **A leader** from the box to the line it annotates, which *reserves* the few px it draws in
  (`calcHeightInPixels` adds `LEADER_LEN`) rather than borrowing the margin — §2.9's generalisation applied
  deliberately for once, instead of discovered by collision. It flips direction with `calloutBelow`, since
  the point of a leader is which way it points.

The leader is also the first decoration here that had to **inherit from a sibling decoration** rather than
from the scheme, and that turned out to be the whole difficulty. It began as a hairline with an arrowhead —
frame-independent, and therefore wrong beside four of the five frames. As a speech-bubble tail it has to
answer two questions the frame owns: whether there is a background to continue (`panelFrame` — None and
Comment paint none, so the tail stays unfilled rather than inventing the panel the frame deliberately
omits), and whether it must define its own edge (`leaderBorder` — every frame but Bar). The part worth
remembering is the third step: after stroking the tail's two free edges, the shared edge is **redrawn in the
tint**, which erases the box's own outline across the mouth of the tail. Without it an outlined box with a
tail is an outlined box with a triangle stuck under it. It is also why the leader must be painted *after*
the frame branch and not inside it. The erase is only as opaque as the fill it borrows, so below full opacity
it thins that edge rather than removing it — the same bargain the box's fill makes with the code it covers.

Two things about that erase were **wrong until the pixels were read** (`scratchpad/LeaderProbe.java`, the
§2.9 javac-probe technique pointed at Java2D instead of Swing: render the frame + leader to a
`BufferedImage`, classify each pixel against the three colours in play). First, it was written as a two-row
band on the theory that antialiasing smears a 1px stroke across two half-intensity rows — it does not, for
axis-aligned strokes, and the second row only nibbled a pixel off the slanted edge. Second, and the reason
this is worth a paragraph: docked **above**, the erase does nothing at all. The tail's own fill starts on the
shared row and has already covered it, so the probe's no-erase baseline passes. The erase earns its place
only when the callout is docked **below**, where the fill stops one row short of the base and the frame's
edge runs straight across the mouth — 12 accent pixels wide, in the run that proved it. A decoration whose
justification exists in one of two orientations is exactly the kind of thing a screenshot of the default
would have certified as correct.

One settings-page note from the same batch: the description-font combo now has a **live sample line**, and
the sample resolves its face by the same rule `CalloutRenderer.font` applies to a prose block — editor
italic, named family if one is named, times the text-size percentage. A preview that computes its own answer
is a second implementation of the thing being previewed, and the day they disagree the preview is what gets
believed. It follows the size spinner as well as the family combo for that reason, and it keeps the dialog's
own text colour rather than the callout's: over a settings background the softened body colour reads as
disabled text, and what is being judged here is the font.

---

## §3 — Rendering (Axis A): complete

**No unbuilt Axis-A feature remains** — A4 shipped with B3. Everything the editor draws for a walkthrough
exists; what's left (B5 / B1b) is timing and choreography over the same primitives.

`RangeHighlighter.setCustomRenderer(CustomHighlighterRenderer)` was the right API for A4 and cost about
what "Low / Medium" implied. Three notes that generalise to anything drawing *between* two places rather
than *on* one:

- **A custom renderer needs no state.** `paint` recomputes both endpoints from their offsets via
  `Editor.offsetToXY`, which returns content-component coordinates — the same space the `Graphics` is in.
  So the arrow follows its code through scrolling, folding, font changes and resizes with no cache, no
  invalidation, and none of the measure/paint drift the A2 inlay needed (§2.3). Inlays reserve layout;
  custom renderers only draw. **Prefer the latter whenever the decoration doesn't need to displace text.**
- **The `JBPopupFactory` balloon "cheap option" was never a real alternative.** A balloon is transient
  chrome floating over the editor; it can't sit still while narration talks about the connection, and it
  would fight the A2 callout for the same space.
- **A highlighter carrying a custom renderer wants no `TextAttributes`** — pass `null`, or it tints the
  lines it merely spans.

---

## §4 — Authoring (Axis B)

### 4.1 How a walkthrough is authored

```
ExplainThisAction
  └─ ClaudeExplainer.explain
       ├─ buildPrompt(): points the agent at docs/plan-schema.md as the ONE authoritative schema
       │                 (ClaudeExplainer.kt:178) + context JSON + docs/explain-at-cursor.md.
       │                 An inline schema string survives ONLY as the skillPath == null fallback.
       └─ runClaude(): headless `claude -p`; agent reads code, writes set_plan JSON to a
                       temp file, POSTs to the loopback server
ExplainerServer.dispatchAgentMessage("set_plan")
  └─ json.decodeFromJsonElement<Plan>(obj)   // ignoreUnknownKeys = true (ExplainerServer.kt:54)
       └─ walkthrough.setPlan(...) → fires WalkthroughListener
            ├─ EditorHighlighter (resolve file → open editor → drive a per-editor
            │                     SpotlightRenderer: spotlight + callout + flash + relations)
            └─ NarrationController (audio + per-highlight advance on `playback_complete`)
```

Data model (`Protocol.kt`): `Plan { title, language, segments[] }` → `Segment { id, file, start, end,
title, explanation, highlights[], relations[] }` → `Highlight { start, end, ttsText, explanation?, note?,
token?, occurrence?, example? }`. Line numbers are **1-based, inclusive, line-only** — no columns.

### 4.2 Invariants

1. **Additive & forward-compatible.** New optional fields with defaults decode cleanly on old IDEs
   (`ignoreUnknownKeys`) and old saved files stay valid. Every field is *opt-in*.
2. **A bare plan must render exactly as today.** Richness is something the agent *adds where it helps*,
   never a requirement. B2 is the template: no `note` → falls back to `explanation` → blank → nothing drawn.
3. **Locator reliability is the whole game.** Anything token-precise depends on the agent emitting a
   correct anchor. Locators are **text search over the highlight's own line range** — Rider-safe, no PSI.
4. **One schema doc — hold the line.** `docs/plan-schema.md` is authoritative; `explain-at-cursor.md`,
   `segments.md`, `walkthrough.md` and `SKILL.md` *point* at it instead of restating it, and the inline
   `buildPrompt` string is labelled as the no-skill-dir fallback. Re-introducing a second copy
   re-introduces the #1 failure mode: wrong field names decode to nothing, silently, because
   `ignoreUnknownKeys = true`.
5. **Timing is a webview concern, not a plugin concern** (§5, §2.8).

### 4.3 The tiers

---

#### Tier B1a — Token anchor · **shipped**

```jsonc
{ "start": 42, "end": 45, "ttsText": "…", "token": "customerId", "occurrence": 1 }
```

- **A string, deliberately, not a column.** The agent can copy a token out of code it just read; a column
  number is arithmetic it would get wrong. This is the general principle for every locator.
- **Whole-word first, substring fallback.** `token: "rate"` prefers standalone `rate` over the one inside
  `rateTable`, but a non-identifier anchor (`=>`, `?? 0`) has no word boundaries and must still resolve.
  `occurrence` counts *qualifying* matches, so `occurrence: 2` under whole-word means the second standalone
  hit — the reading an author intends.
- **Deviation from the draft: `javax.swing.Timer`, not `com.intellij.util.Alarm`** — EDT-native by
  construction, no platform-version surface; wrapped as `Disposable { timer.stop(); removeFlash() }` into
  `trackedDisposables`, which is what the draft actually wanted from `Alarm`.
- **The fade re-adds a highlighter per frame** rather than mutating one `TextAttributes` in place, because
  replacing a highlighter is how every other layer here repaints. Twelve frames is cheap.
- **`flashHighlighter` is held outside `tracked`** (per-frame churn would turn that list into garbage);
  `clearTracked` removes the flash explicitly *and* the timer's disposable does too, because the first
  frame is painted before the disposable is registered.

**B1b (deferred):** honour a `start`/`duration` fraction of the highlight's audio. Needs §5.
**Still open and now cheap:** position the callout at the token's x-offset instead of the line indent —
`indentPx` already establishes the measurement, so it's a change to *which* offset gets measured.

---

#### Tier B2 — In-code notes · **shipped**

Kept for the rationale: `explanation` is a 2–5 word sidebar label, too short for an in-editor callout, and
widening it would have wrecked the sidebar outline. A distinct `note` let the two diverge — `explanation`
→ sidebar label, `note` → editor callout, `ttsText` → spoken. Three audiences, three fields, no compromise.

---

#### Tier B3 — Relationships / arrows · feeds A4 · **shipped**

```jsonc
"relations": [                                     // on the Segment
  { "kind": "flow",      "from": { "line": 44, "token": "raw" },
                         "to":   { "line": 51, "token": "parsed" } },
  { "kind": "backforth", "from": { "line": 60 }, "to": { "line": 72 } },
  { "kind": "flow",      "from": { "line": 45, "token": "rate" },
                         "to":   { "file": "Pricing/RateTable.cs", "symbol": "RateTable.Lookup" },
                         "note": "value comes from RateTable.Lookup()" }
]
```

`Endpoint = { file?, line, token?, occurrence?, symbol? }` — `file` defaults to the segment's file, `line`
is 1-based (`0` = unspecified), `symbol` is a display label for off-screen targets, and the locator is
B1a's. `kind` is `flow` | `backforth`; anything else reads as `flow`, because an unfamiliar kind should
cost an arrowhead, not the relation.

**The central bet — *agent describes semantics, plugin picks the picture* — is what makes this tier work,
and it is stronger than it looks.** The agent **cannot** make the rendering choice: it doesn't know the
viewport width, the fold state, whether the target file is open, or how far apart the lines land once the
callout has pushed them down. All of that is known only at paint time. So "inside vs outside" is not a
field, and there is deliberately no way for a plan to ask for an arrow.

- **Per-segment data, per-*step* rendering.** `relations` sits on the segment (a relation spans lines
  belonging to different highlights), but drawing all of them during step 1 would be a thicket. A relation
  is drawn only while a step whose range contains one of its endpoints is active — which makes it a step
  decoration like the callout and flash, in time with the narration. Cost: one authoring rule —
  **anchor an endpoint inside the highlight the relation belongs to, or it is never seen.**
- **Arrows leave from the *end of the line*, not from the endpoint token.** The one place the draft's
  "`token` makes it precise" reading had to give way: a leg starting at a mid-line token runs back over the
  rest of that line's code. The vertical leg sits clear of the longest line the arrow spans (`max` of
  `offsetToXY(trimmedLineEnd)`). `token` still does real work — it *validates* the endpoint (a token that
  isn't on the line the agent claims condemns it, the only cheap check available on a line number worked
  out by counting) and names the endpoint in the pointer form.
- **A wrong arrow is worse than no arrow** → validate-and-*degrade*, not validate-and-drop. Bad token,
  line past EOF, both ends on one row, ends 200 lines apart, target in another file: all fall back to the
  pointer form, which still says something true. Only a relation naming nothing and carrying no note is
  dropped outright.
- **The pointer form is a third callout block, not a second box.** `CalloutBlock` gained a `BlockStyle`
  (`PROSE` italic / `CODE` upright / `POINTER` upright accent-tinted) instead of B4's `code: Boolean`, so
  prose, example and pointers share one measure/paint contract in one box (§2.3) — which is why this tier
  needed no new inlay machinery.
- **The gutter triangle carries the click**, not the callout text: no disposal needed, and a free tooltip,
  which is where the untruncated text lives when the box has ellipsised it.
- **One setting, not two.** `relations` gates both forms — two renderings of one authored fact, and a
  reader who doesn't want arrows over their code doesn't want half of them.
- **Save/load** — `WalkthroughStorage`'s `relativize`/`absolutize` were factored out of
  `toRelativePaths`/`toAbsolutePaths` and now apply to endpoint files too. The draft's most
  easily-forgotten item; about four lines.
- **Not built, deliberately: animation.** `backforth` is a double-headed arrow, not the draft's "animated
  pulse" — a decoration that moves while the voice talks belongs to B5, with A3.1's playback clock.

Residual risk is unchanged and unfixable plugin-side: the agent must emit accurate line/token pairs for
both ends. The `token`-on-the-line check turns the most likely failure (an off-by-a-few line number) from a
confidently wrong arrow into a pointer, which is why `plan-schema.md` asks for a `token` on both ends.
One thing the probes showed that the design didn't anticipate: when the spanned code is wider than the
window, the leg is pinned at the viewport edge and can graze a long line — the right trade (an arrow
inside the window beats one off it), but worth knowing before someone reports it as a bug.

---

#### Tier B4 — Example blocks · feeds A2 · **one-liner shipped**

```jsonc
{ "start": 42, "end": 45, "ttsText": "…", "example": "Parse(\"2024-01\") → DateOnly(2024, 1, 1)" }
```

Agents are good at examples, so authoring cost was the low half as predicted. What's worth keeping:

- **`wrap()` splits on `'\n'` along with spaces and tabs, so it destroys line structure by design** — a
  multi-line snippet would reflow into a run-on paragraph. `firstLine()` keeps line 1 and appends `…` so
  the truncation is visible (§2.4). Fenced multi-line blocks remain a separate, unbuilt renderer.
- **The "code wants monospace" objection was wrong.** The callout was never drawing in a proportional
  font: `editor.colorsScheme.getFont(EditorFontType.ITALIC)` is *the editor's own monospace family*,
  italicised. So example-vs-prose is `EditorFontType.PLAIN` vs `ITALIC` — one parameter, same family, same
  metrics contract; plus full scheme foreground against the softened `calloutFg` for prose.
- **The real work was two paragraphs in one box.** `CalloutRenderer` takes a `List<CalloutBlock>` and
  wraps, measures and paints each independently, because a note and an example must not be wrapped
  together. `textWidth()`/`textHeight()` are shared by `calcHeightInPixels` and `paint`, and
  `measuredLineCounts` is per-block, so reserved and painted height cannot drift (§2.3). **One box, not
  two inlays** — stacked inlays would double that machinery and put vertical order at the mercy of
  `addBlockElement`'s `priority` semantics. One renderer owns the order.
- **Sidebar deliberately skipped**, despite the tier heading: the outline is one compact row per step,
  `note` set the precedent of editor-only, and an example per step would bloat the thing it exists to
  compress.
- **Per-block caps** (`MAX_CALLOUT_LINES = 3`, `MAX_EXAMPLE_LINES = 2`) rather than one shared budget, so a
  long note can't eat the example's room. Worst case is a 5-line box, which is why `plan-schema.md` says
  to use both fields together only where both earn it.

---

#### Tier B5 / B1b — Choreography over the playback clock · **next, and the only tier left**

An arrow that draws itself, a token that pulses on the word being spoken, stepping through an example in
time with the narration.

- Needs a per-highlight **beat timeline** + a scheduler synced to audio — hung off §5 tier (b), on top of
  A3.1's channel. The scheduler's disposable goes in `trackedDisposables` (§1).
- Illustrative shape only: `"beats": [ { "at": {"sentence": 1}, "do": "flash", "token": "rate" },
  { "at": {"sentence": 2}, "do": "arrow", "rel": 0 } ]`.
- Note what it no longer needs: §3 is empty, so this is a **scheduling** problem over existing renderers
  (flash, arrow, callout), not a painting one. Still a research spike — highest complexity, least certain
  payoff.

### 4.4 Cross-cutting rules

1. **One canonical schema doc** — §4.2 invariant 4. New fields go in `plan-schema.md`, referenced from
   everywhere else.
2. **Locators are always text-search, never PSI** (Rider parity). Standard shape
   `{ line, token?, occurrence? }`, reused by B1a and B3.
3. **Validate-and-drop** every new anchor, mirroring the existing range coercion — or
   validate-and-*degrade* where a partial statement is still true (B3).
4. **Keep everything optional and sparse.** More required structure = more the agent gets wrong + more
   tokens/latency per Explain.
5. **Renderer limits ship as authoring rules** in the same commit (§2.4).
6. **Save/load parity** for any new `file`-bearing field (`WalkthroughStorage`).
7. **Verify visually** (§2.2) — and for time-sensitive behaviour, against an audio event (§2.8).

---

## §5 — Timing: where the clock actually is

The draft claimed per-sentence timing was "already available for free" plugin-side via `TtsService`'s
`onChunk(… sentenceIndex, sentenceWords)`, and that only per-percent needed a new channel. **Both halves
were wrong, in opposite directions.**

**`onChunk` is the wrong hook.** It fires on `TtsService`'s pooled thread at *synthesis* time, and its only
job in narration is to forward the chunk to the webview as `si`/`sw` (`NarrationController.kt:249`). Audio
is played by **Web Audio in the sidebar**, buffered — so `onChunk` means "sentence N was synthesized and
queued", not "sentence N is being heard". (`NativePcmPlayer` does play natively, but it's only the
voice-editor Test button.)

**It's cheaper than the draft feared anyway,** because the webview already maintains a playback-time
sentence schedule for the read-along cursor:

- `stepSentences[si] = { wordStart, wordCount, tStart, dur }` — built in `recordChunk`, in AudioContext
  time (`sidebar.js:150`).
- `updateStepProgress` — a `requestAnimationFrame` loop calling
  `applyReadAlong(audioCtx.currentTime - stepStartClock)` every frame while audio plays (`sidebar.js:395`).
- `playback_complete` already proves the webview→plugin direction.

| Tier | What | Cost |
|---|---|---|
| (a) **per-highlight** | today's `advanceAfterPlayback` on `playback_complete` | exists |
| (a½) **step start** | A3.1's `playback_started` from the rAF loop | exists |
| (b) **per-sentence** | `postMessage` when the active sentence index changes, + a plugin-side handler | small — one message type, reuses the running loop |
| (c) **per-percent** | same loop, throttled position ticks | small math, but don't cross the process boundary at 60 Hz — throttle to ~10 Hz or send only on beat boundaries |

**B1a needed none of this** (fixed-duration pulse). B1b/B5 hang off (b), and should copy `playback_started`'s
staleness check rather than reinvent one — with audio in a different process than the decoration, *every*
playback message can arrive after the state it describes has moved on. The (b)→(c) climb is still worth
respecting, but the reason is payload volume, not missing infrastructure.

---

## §6 — Build order

Done, in order: **A1** gutter badge (since removed — see §1; it proved the tracking discipline, and A2 was bundled in because only A2
exercised `trackedDisposables`) → **B3.1** canonical schema doc (pulled ahead of B2 as its prerequisite;
adding a field to four competing schema statements would have entrenched the drift) → **B2 + A2** note +
callout (the biggest change to the explanation-view feel) → **B1a + A3** token + fixed-pulse flash (first
real Axis-B *locator*) → **A3.1** `playback_started` (unplanned; measurement forced it — §2.8) → **B4**
one-line examples (cheap, but the monospace objection was wrong and two wrapped paragraphs in one box was
the real work) → **B3 + A4** relations/arrows (the ambitious one, and the first tier where the *rendering*
decision had to move into the plugin).

**Next: B5 / B1b** — §4.3, spike on §5 tier (b).

Ordering rationale worth keeping: **B-tiers that introduce a new locator kind precede the A-features that
consume them**, and a shared prerequisite (B3.1) precedes the first field that would otherwise duplicate
it. Rendering has consistently been the cheap half — including A4, the one feature the draft rated highest
on the rendering axis.

---

## §7 — Touchpoints

- **Schema types:** `Protocol.kt` (`Highlight` / `Segment` / `Plan`).
- **Canonical schema doc:** `claude-skill/docs/plan-schema.md` — **the** place new fields are defined.
- **Prompt:** `ClaudeExplainer.buildPrompt` — points at `plan-schema.md` (`ClaudeExplainer.kt:178`); the
  inline string is the no-skill-dir fallback only.
- **Skill docs referencing the schema:** `explain-at-cursor.md`, `segments.md`, `walkthrough.md`,
  `SKILL.md` — these keep *pointing*, not restating.
- **Skill install (§2.1):** `claude-skill/manifest.txt` **and** `claude-skill/VERSION` — both needed for
  any new or changed skill file to reach users.
- **Rendering:** `SpotlightRenderer.kt` — extend `tracked` (markup-owned) or `trackedDisposables`
  (everything else). `EditorHighlighter.kt` is only the listener side (file resolution, editor opening,
  threading) since `TreeOfCode.md` Phase 1a split the two.
- **Settings:** `AudioExplainerSettings.kt` + `HighlightSettingsConfigurable.kt` — each visual decoration
  gets a toggle, and the toggle goes in Reset-to-defaults too.
- **Interaction wiring:** `AudioExplainerSidebar.forEachOwnHighlighter` (§2.6) — also how a webview
  playback message reaches the editor (`playback_started` → `flashCurrentToken`).
- **Timing/beats:** `sidebar/sidebar.js` — `recordChunk` / `stepSentences` / `updateStepProgress` (§5,
  §2.8). *Not* `NarrationController.onChunk`. Anything that must coincide with the voice is emitted from
  `updateStepProgress`, because the Web Audio clock lives only there.
- **Save/load:** `WalkthroughStorage` — relative-path rewrite for any new `file` fields.
- **Server decode is already tolerant:** `ExplainerServer.kt:54` `Json { ignoreUnknownKeys = true }`.
  Tolerant means *silent* — the reason §4.2 invariant 4 matters.
