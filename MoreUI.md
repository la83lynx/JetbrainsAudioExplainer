# MoreUI — richer explanation-view details

> Status: **§1–§2 are normative** (they describe code that exists; if they disagree with the source, the
> source wins and this file is stale — fix it). **§3–§5 are now history plus the last tier's record
> (B1b, built);** no tier is outstanding, so what those sections constrain is the next change rather than
> the next feature.
>
> **Everything here draws inside the tree's node editors now.** The in-place surface that decorated the
> user's own tab — `EditorHighlighter`, `Plan.view`, and everything that chose between the two — was deleted
> in `TreeOfCode.rules.md` Phase 4. `SpotlightRenderer` has exactly one caller, `tree/CodeNodeView`, which
> builds one renderer per node. Where a rule below says "the editor", read "a node's editor"; where it says
> a viewport width, read "per node, and there are several on screen".
>
> **The schema was renamed under this document too.** Phase 2 replaced `Segment`/`Highlight` with
> `Frame`/`Step`: `note` / `token` / `occurrence` / `example` are `Step` fields, `relations` is a `Frame`
> field, and a plan is `frames` + `steps` (`Protocol.kt`). Tier names below (B1a, B2…) are unchanged.
>
> Two axes throughout: **Axis A — rendering** (what the editor can draw) and **Axis B — authoring data**
> (what the explainer agent must emit to drive it). The original draft's thesis — *most of the work is
> Axis B, not Axis A* — held up through every tier, including A4, the feature the draft rated hardest to
> render.
>
> Section numbers are cited from source comments (`§2.4`, `§2.8`, `§2.9`, `§4.3 B3`, `§4.3 B4`, `§5`) —
> don't renumber.

---

## §1 — What shipped

Baseline (`SpotlightRenderer.kt`): whole-line `RangeHighlighter`s on `editor.markupModel`
(`LINES_IN_RANGE`), four layers (dim / segmentDim / gap / active-gold), gap gutter bar via
`LineMarkerRenderer`. Driven by `WalkthroughListener.onStep`, marshalled to the EDT by `TreePlanController`;
everything tracked in `tracked` (markup-owned) or `trackedDisposables` (not), torn down in `clear()`.

Added since, all settings-gated and additive — **a plan with none of the new fields renders exactly as
it did before**:

| # | Feature | Setting | Notes |
|---|---------|---------|-------|
| ~~A1~~ | ~~**Gutter step badge** — accent disc with the step number, click = navigate to that step~~ | — | **Built, shipped, then removed on use** (§2.10). Its two lasting contributions are the tracking discipline it proved and the index bug it exposed (§2.5). |
| A2 | **Docked callout** — box of wrapped text docked by the active lines | `callout`, `calloutTintPercent`, `calloutOpacityPercent`, `calloutFrame`, `calloutCornerRadius`, `calloutBarWidth`, `calloutTextScalePercent`, `calloutProseFont`, `calloutBelow`, `calloutLeader` | Indented to the code it describes. Holds several independently-wrapped paragraphs, each with its own font/colour (`BlockStyle`) and its own line cap (§4.3 B4). Five frame styles incl. `comment` (`// ` lines in the scheme's comment colour); the description font is settable, the example is not. |
| B2 | **`Step.note`** — optional sentence-length aside | — | Callout shows `note ?: explanation`. |
| B3.1 | **One canonical schema doc** — `claude-skill/docs/plan-schema.md` | — | Cross-cutting prerequisite; done before B2's field was added. |
| B1a | **`Step.token` / `occurrence`** — text anchor naming a step's key identifier | — | Resolved by text search over the step's own lines; never PSI. |
| A3 | **Token flash** — the anchored identifier pulses from accent into the line's glow | `tokenFlash`, `tokenFlashMs` | `EXACT_RANGE` highlighter on `HighlighterLayer.LAST`, faded over 12 frames. |
| A3.1 | **`playback_started`** — webview → plugin "the playhead reached this step's audio", so the flash fires with the voice instead of 1.8–6.0 s ahead of it | — | Sent from the existing rAF progress loop; carries the step index so a stale one is dropped. See §2.8. |
| B4 | **`Step.example`** — optional one-line `input → output` illustration | `calloutExample` | Second paragraph of the *same* callout box, drawn upright (not italic) so it reads as code. §4.3 B4. |
| B3 | **`Frame.relations`** — kind + two endpoint locators | `relations` | Per-frame data, per-*step* rendering: only relations with an endpoint inside the active step are drawn. Endpoint `file`s join `Frame.file` in `WalkthroughStorage`'s path rewrite. §4.3 B3. |
| A4 | **Relation rendering — the plugin picks the picture** | `relations`, `arrowUseAccent`/`arrowColor`, `arrowThicknessTenths`, `arrowHeadSize`, `arrowSquareElbow`, `arrowLabels` | Shipped as an in-file elbow arrow plus a gutter pointer for anything off-screen. **Both were replaced by `LinkArt`'s single link system** — one palette, one hover that brightens both halves, one router across every node on screen. `rules.md` *Retired* has what went and why; the tier's **authoring** contract (§4.3 B3) never changed, which is the point of the split. |

Two API notes from the original draft that still bind, recorded because the wrong version is the intuitive one:

- **A2 uses `relatesToPrecedingText = false`, not `true`.** The box annotates the line *below* it; an edit
  at that offset must carry the callout along with its code, not strand it on the line above.
- **A2's callout is added before `revealCenter`.** The inlay adds height above the active line, so
  scrolling first leaves the block off-centre.

### Disposal discipline

```kotlin
private val tracked = mutableListOf<RangeHighlighter>()        // markup model owns these
private val trackedDisposables = mutableListOf<Disposable>()   // it does NOT own these
```

`clear()` clears both — highlighters via `markupModel.removeHighlighter`, disposables via `Disposer.dispose`,
unconditionally and each in its own `try` (a disposed editor already dropped its inlays; disposing twice is a
no-op). **`trackedDisposables` is the extension point for every remaining tier** — B1b's beat scheduler belongs
there, as A3's `Timer` does. Anything the markup model doesn't own goes in that list, or it survives a step
transition and lingers on screen.

---

## §2 — Lessons that constrain the remaining tier

Hard-won during A1–A4. Each one still applies to B1b.

### 2.1 A skill change is four files, not one

`ClaudeSkillInstaller` copies a **hand-maintained `manifest.txt`**, and `ensureInstalled()` early-returns
when the installed `VERSION` matches the bundled one. So a missing `manifest.txt` entry means the new doc
never installs (the prompt points at a path that doesn't exist), and a missing `VERSION` bump means existing
users keep the old docs — **same symptom, both silent, both only at agent runtime** inside a headless
`claude -p` you aren't watching.

Checklist for anything touching `claude-skill/`: *edit doc → add to `manifest.txt` → bump `VERSION` →
confirm against the built jar → **and know that agents do not read the jar***. The copy to
`~/.claude/skills/audio-explainer/` happens on the next plugin run, so between the edit and that run every
`claude -p` is still authoring against the old text — a re-audit that reads the installed copy will
correctly report *nothing has changed*. **Bumping `VERSION` is what makes the fix deliverable; running the
plugin is what makes it delivered.** If a check needs the new text before then, sync the directory by hand.

Currently **0.21.0**; `git log` carries the bump-by-bump history and it does not belong here. One lesson
from it does. `0.19.0`/`0.19.1` were the first bumps the **docs** earned rather than the schema: three
agents put through the installed skill with no example plans found a required field the wire type defaults,
legal values written down nowhere, and four docs stating the line budget four different ways — and the
*fix* pass then introduced three faults of its own, one of them a tightened rule that made two docs' own
examples illegal. **A doc pass needs the same re-read a code pass does**, and `plan-schema.md` binds while
the rest defer to it (§4.2 invariant 4).

### 2.2 Log silence proves nothing (partly fixed)

`runOnEdt` used to catch and discard `Throwable` unlogged — a decoration that failed to render looked
identical to one that rendered perfectly. `TreePlanController.runOnEdt` now logs `warn` with the stack
trace and rethrows `ProcessCanceledException` (a cancellation *signal*, never a fault). Deliberately still
unlogged (and commented as such): `clear()`'s teardown catches (routine, per highlighter per transition)
and `indentPx`'s fallback (cosmetic).

Log mechanics — where `idea.log` is, why the console carries WARN and above only, the `#com.audioexplainer`
FINE categories, how to slice a log to one launch — live in `TreeOfCode.rules.md` *Operational notes*.
What belongs here is what the log **cannot** tell you.

**Logs tell you when a render broke; only a screenshot tells you it rendered.** The failure mode that costs
time throws nothing: a settings gate off, a relation with no endpoint in the active step so nothing is
drawn, `note` absent *and* `explanation` blank so `addCallout` returns early, a colour blend landing
invisible, a measured width of zero, a node too narrow to honour the right-hand reserve. All silent, all
"successful" to any log. The loop that answers it — `runIde`, POST a hand-written `set_plan` with the
exact shape under test, screenshot, *look at it* — is in `rules.md` *Operational notes*, and no `claude -p`
belongs anywhere in it. Two constraints on the shutter do belong here. `CopyFromScreen` returns a fully
black bitmap on a sleeping or locked display — indistinguishable from "the IDE didn't draw", so this loop
needs someone at the machine. And
transient decorations race the shutter: a 700 ms fade is usually over before a capture lands, so capture a
burst of region-only frames immediately after the POST, or lengthen the effect first (pre-seeding
`config/options/audioExplainer.xml` before launch is the intended way, still unconfirmed — §2.7).

Synthetic plans also test what an agent won't reliably produce on demand: `note`-over-`explanation`
precedence was proven with a decoy `explanation: "SHOULD NOT APPEAR"` beside a real `note`.

### 2.3 Inlays are measured once; wrap depends on the viewport

`calcHeightInPixels` runs once, but the text re-wraps on every resize — a narrowed editor wants more
lines than the reserved height holds, and the box clips. `CalloutRenderer` handles it with
`measuredForWidth` / `measuredLineCounts` drift detection: `paint` draws only what fits and schedules one
`inlay.update()` via `invokeLater`, guarded by `updateScheduled` so repaints can't queue a pile of
re-measures. `update()` re-enters layout, so it must never be called from inside the paint pass. **Any
future inlay inherits this contract** — and in the tree it is exercised harder than it was in one tab,
because a node's width changes with the nest's layout, not only with the window.

### 2.4 Renderer caps become authoring rules

`MAX_CALLOUT_LINES = 3` is a rendering constant, but it dictates the skill instruction: `note` must be
**one sentence**, and sparse, because a note on every step becomes wallpaper. Generalise: when a tier
adds a renderer with a limit, the limit belongs in `plan-schema.md` as an authoring rule *in the same
commit* — otherwise the agent writes to no budget and the renderer silently ellipsises good content.

Three flavours of limit now exist:

- **Truncating, enforced twice.** `MAX_EXAMPLE_LINES = 2` shipped with "one line" in the doc *and*
  enforcement in the renderer (`firstLine` keeps line 1, appends `…`). Where a cap can be enforced
  visibly, do both — the doc tells a well-behaved agent what to write; the renderer keeps a badly-behaved
  one from producing something that looks like a plugin bug.
- **Picture-changing.** `MAX_ARROW_SPAN = 40` and "both ends on different lines" don't cut content — cross
  them and the relation renders as a portal pair instead of a drawn leg. The authoring rule is "or you get
  a different picture", which an agent can actually reason about.
- **Ordinary.** `MAX_RELATIONS_PER_STEP = LinkArt.slots()` = **5**: past it nothing is drawn, a debug line
  says how many were skipped, and `PlanLoader` reports it as `relation.step-exceeded`. It is *one* number
  because the cap, the palette and the routing lanes are the same number — a step cannot run out of colours
  or of columns.

### 2.5 Stateful decorations need the step index to be right

A1 was the first decoration that *displayed which step it is*, and it exposed a latent bug: `refresh()`
re-rendered from step 0, so a mid-walkthrough settings change rewound the spotlight — invisible until a
badge read "1" while narration was on step 3. Fixed by tracking the current index and re-rendering that.
Anything encoding step identity (flash, links, B1b's beats) depends on it being accurate through *every*
re-render path, not just the advance. The badge is gone, but note what it cost to find this: the bug needed
a decoration that *shows* the index. **Losing that means the next such regression is invisible again** —
worth remembering when the index next changes hands.

### 2.6 The interaction-wiring pattern

A decoration that has to reach the narration controller — which lives in the tool window — goes through the
listener list, not through a reference:

```kotlin
// AudioExplainerSidebar.forEachOwnTreeController
walkthrough.getListeners().filterIsInstance<TreePlanController>().forEach(action)
```

The controller registers itself as a walkthrough listener at project open (`ServerStartupActivity`), and
that registration is the **only** handle on it — the same discovery `HighlightSettingsConfigurable` uses for
its live re-style, and the route `playback_started` → `flashCurrentToken` takes today. The pattern outlived
both its first instances: A1's click handler went with the badge, and the lookup itself used to filter for
`EditorHighlighter`s. **That rename is the lesson.** Deleting a surface leaves this kind of lookup compiling
and matching nothing — a silent no-op, not a build error (`rules.md` constraint 11).

Note what does **not** need a route back to narration: a click that merely opens a file goes straight
through `navigate` → `openEditor`. Reach for the cheaper wiring first.

### 2.7 Verification status

**Verified** — on screen means a synthetic `set_plan` probe, screenshotted (the recipe is in the
`verify-rendering-via-synthetic-set-plan` memory); headless means an assertion in `probes/` that imports the
production class:

- **A3 flash** renders — `HighlighterLayer.LAST` does paint over the active-line glow — and its timing is
  fixed and measured (§2.8). Whole-word-over-substring preference confirmed via the log, which is the
  cheaper loop for anything token-resolution-shaped.
- **B4 callout, three probes**: note + example as two paragraphs in one box, example-only with no leading
  gap, and the worst case — a 3-line ellipsised note plus a truncated example rendering a 4-line box with
  no clipping, i.e. §2.3's measure/paint agreement holding under multi-block layout.
- **The token anchor's boundaries**, headless (`probes/TokenProbe.java`, 18 assertions over the real
  `findNth`): a token ending exactly at `to` resolves and one starting at `to` does not, `occurrence` counts
  *qualifying* matches, `_`/`$` flank an identifier and `-` does not, and `Parse(` is the case that fails
  whole-word and resolves as a substring — the pair that justifies `resolveToken`'s two passes. **The hand trace was wrong
  about one thing**: the empty needle does not spin. `i = idx + 1` makes the loop terminate on its own; the
  upstream guard means it never arrives, which is a different claim. `resolveToken` needs an `Editor`, so
  that guard and the order of the two passes are still read rather than asserted.
- **Every appearance knob, walked** (round K1, 34 rows, script in the session scratchpad). The five frame
  styles, the leader against each of them, `calloutBelow`, the prose font, the text scale, bar width,
  corner radius and opacity — including the `comment` frame under the longest note any fixture carries.
  **The `comment` prefix is charged correctly**: it comes off the wrap budget in `lines` and goes back on
  in `textWidth`, so the suspicion recorded here for two phases ("charged twice or not at all") was wrong,
  and reading the code answered it before the round did. The reader's verdict was *"all works and is OK"*.
- **Appearance knobs reach `audioExplainer.xml` through the real dialog** — the K1 round's most useful
  single artifact. Before it the file held three narration values and no appearance value at all; after it,
  six. The clamp (§2.9) therefore holds in the dialog and not only at the Swing level.
- **`refresh()`'s two branches are exclusive** — the log carries one `tree restyled` line per tree-shape
  save and **none** for a callout-only one, so `if (!restyle()) repaintStep()` never paints the step twice.
  Measured over a whole round rather than argued.
- **The three link knobs, working** (round K2). `arrowThicknessTenths`, `arrowHeadSize` and
  `arrowSquareElbow` had only ever been seen doing *nothing*; the round walked each one, plus the five
  per-slot colour pickers and the `Clear` that puts them back to derived. The five gates K1 deleted took
  nothing with them — callout, worked example, token flash and links all still draw. Verdict: *"everything
  worked well"*.
- **The lane-pitch bound is real, measured at ~2.5 px, and deliberately not enforced.** `railStep`
  divides the row's leading as if the hovered weight were 2 px, and K2 confirms the consequence: past about
  25 tenths, several links leaving the same row start to intersect. The spinner keeps its 8..40 range
  anyway — *"this intersection situation should be sufficiently rare in real explanations, and hovering
  still helps to distinguish, and someone might like really big lines"*. **A measured limit and an enforced
  limit are different decisions**, and conflating them is what nearly clamped this range to 25 on the
  strength of a number nobody had chosen to act on.
- **`arrowSlotColors` round-trips as a list** — five entries, blanks included, in slot order, which is a
  different serializer path from every scalar setting beside it.
- **The agent writes `note`, `token` and `relations` unprompted** — the longest-open question here, answered
  twice in round K3. Headless first (`claude -p` against a Python stand-in for `ExplainerServer`, so the
  4–7 minute sandbox is not in the loop): 4 frames, 13 steps, `token` on **12 of 13**, `note` on 5,
  `example` on 1, `relations` on **4 of 4** frames (9 in all), 3 nested frames, `language: en`, **0 schema
  issues**. Then through the real server by right-click, which is the half a mock cannot stand in for
  (`ensureInstalled()`, the VFS resolve, the tree). Every fixture before this was hand-written, so this is
  the first plan in the project's history whose shape the renderer did not already know.
  **`occurrence` it never used** — 0 steps out of 13 — which is why `plan2-occurrence.json` exercises
  something nothing else does rather than repeating what the agent does anyway.
- **`occurrence` and validate-and-drop, on screen** (K3, fixtures 5 and 6). Two same-line pairs, a dotted
  `guarded.Amount`, counting across a range rather than per line, occurrence 4 resolving and 5 dropping, and
  five anchor failure modes each beside a control that resolves. Verdict: *"everything is ok imho"*.
  **The picture's verdict, not the log's** — row 10 (the `token anchor` lines) was not run, so what is
  confirmed is that the right glyph pulses, not that each drop was recorded for the reason claimed.
- **`explainer.ps1` posts a real plan to the live server** — `{"ok":true,"frames":1,"steps":7}`. The
  connection-closed failure chased in K2 reproduces only against the Python mock, so the skill's own helper
  is not implicated and was left alone. **Two rows of the K3 script measured my own bug instead**: they put
  `$env:USERPROFILE` inside a `cmd` line, where it is a literal — a PowerShell path spelled for a shell that
  was not going to run it.
- **The brace lands on the header, and the corner moved to the frame** (K3, judged K4). Measured off the two
  paths drawn into **separate** images: the run's end and the frame's rounded corner overlap at **0 px**,
  where the same run against the old square top measures a **5 px** gap — the artifact itself, as a number.
  On screen in K4: *"the link now meet sub-editor top line, diagonals look ok, rounding restored"*, which is
  all three leader modes and the reason the one boolean had to become two.
- **The fold is the default because a round asked for it, not because it was tidy** (K4). K3 asked whether a
  step could say which frames are open; the answer was that openness belongs to the reader's screen, so it
  ships as one setting (`StepFold.ALWAYS`). K4's verdict: *"folding really really helps — it must be a
  default option."* The log carries nine folds over the round, 1–3 nestings each, with all three modes walked.
- **The line budget was arithmetic for two phases; K4 and K5 measured it** — *"my IDE shows 25-40 lines"* on
  one monitor, *"I now see 55 lines"* on another. The tree is a tool window beside the sidebar, not an editor
  filling a window, and it moves between screens, so the honest answer is a **range** and the cap belongs at
  the top of it. **80 → 40 → 60**: back where the arithmetic first put it, now meaning "the largest real
  viewport" rather than "half of two estimated ones". The fixture chain the caps were once calibrated *to*
  (66 lines) is over it either way — that chain always scrolled and nobody had been asked about it.
- **The fold has to OPEN, not merely refrain from closing** (K5, and the K4 cut was half a fix).
  `collapseAllExcept` only ever sets `collapsed = true`, so a nesting an *earlier* step folded stayed folded
  however wanted it now was, and the only thing that reopened anything was `setActiveNode` walking the
  primary's chain. On `tree-links` step 2 — three portal links from the root into three children step 1 had
  just folded — all three pairs drew one end into a stub. The round found it from the other side too:
  *"step 3 expands ValidationHandler and going back to step 2 leaves it expanded, so its second end is
  visible then."* The step now decides the whole open set in both directions.
- **A lane was paying to clear an obstruction it cannot clear** (K5): *"same-editor links, that pass lines
  with expandable (but collapsed) nodes are routed through far-right side of the view."* A `LINE` leg is
  routed clear of every block inlay it crosses, because an inlay paints over it — right for our callout box,
  wrong for a nested node, which is as wide as the editor. There is no x that clears it, so the demand bought
  a fan pinned to the viewport edge **and** a leg still crossed by the node. `LinkArt.obstructionClearable`
  honours the demand only when the fan's own minimum fits past it: callout 420 + 58 ≤ 1200 clears, nested
  node 1190 + 58 > 1200 does not, measured either side of the boundary one pixel apart.
- **A node opened *for* a link scrolls to that link's row** (K5's third correction, judged
  *"Ok, it works."*). `revealNodes` scrolls the nodes the **step** names, and a node holding a link's far
  end is by definition not one of them — `ValidationHandler` opened at its focus range, lines 18–24, with the
  link on 37 below the fold. `paintLinkPairs` now reports the topmost row per node as well as the node, and
  `revealLinkRows` runs **last**, after the step's own reveal, so it outranks that reveal for exactly the
  nodes the reveal did not name. One report — *"sub-editors stay open when they shouldn't"* — turned out to
  be four defects stacked, each visible only once the one before it was fixed: fold gated on the wrong
  trigger → keep-set ignoring link ends → a fold that never opened → a node open but not scrolled.
  **None of the three K5 fixes has a headless instrument**: the fold needs a live tree and `laneDemands`
  needs an `Editor`. The arithmetic under the routing change is asserted (`LinkBench`, 5 checks), which is
  a different claim from the picture being right.
- **The read-along drift, and the first instrument the webview has ever had.** `sidebar.js` carried no probe
  at all, which is why a cursor running ahead of the voice survived every round: it reads as "roughly
  right". `probes/ClockProbe.js` **extracts** the functions out of the shipped file by name and runs them in
  a `vm` context — not a copy, which is what separated `probes/` from the deleted `spikes/`, and a rename
  fails it loudly. Measured: a 1.5 s starvation leaves the cursor **three words of a six-word sentence**
  ahead, permanently, and the correction is asserted **inert** when nothing starves. The clamp that inserts
  the gap came out of `playAudioChunk` as `scheduleSlot` for the same reason `obstructionClearable` came out
  of `laneDemands` — arithmetic that decides something has to be assertable on its own.
- **Beats resolve against what was spoken, on both sides of the wire.** The `e.g.` case is the falsifiable
  one and it is asserted: the splitter cuts `"If it's missing, e.g."` off as its own piece, so the phrase an
  author would call sentence two lives in the server's *third*, and a phrase match lands on it where a
  sentence number would not. `probes/beat-normalize.tsv` holds the matching rule **once** and both
  `PlanProbe` (Kotlin's `Beat.normalize`) and `ClockProbe` (the webview's `beatKey`) are held to it — a
  phrase the loader accepts and the webview never matches is the one failure in this tier with no symptom,
  so it is the one thing not left to two implementations agreeing by eye.
- **B1b on screen** (round K6): *"pulses landed with sentences — all steps worked as predicted."* All six
  steps of `plan4-beats.json`, judged **played** rather than stepped, and the log carries **16** `fired on`
  lines all dated to the round. The two that were designed to be falsifiable both held: step 3's beat landed
  on *"because a discount overshot"*, which is the clause the splitter cuts off after `e.g.` and therefore
  the one a sentence *number* gets wrong; and step 4 fired beats **0 and 2 only**, never beat 1 — the
  one-pulse-per-sentence rule, confirmed against real synthesized audio rather than against the probe's
  synthetic sentence map. No beat token dropped, so every one of those lines is a pulse that was seen. The
  installed skill was 0.24.0 with `stext` in it and the sandbox spawned that copy, so the **primary** match
  path ran (against spoken text) and not the transcript fallback.
- **The `token anchor dropped` lines, at last — but not from a round.** `idea.log` is cumulative across
  sandbox launches, and the drops were sitting in it from the **09-08** run, three sessions after the row
  was first written and never once executed. All three of `plan3-unresolvable`'s predicted failures are
  there with their reasons: `nonexistentSymbol` absent from 68–85, `Running` wrong case at 76–78, and
  `running` present in the file but outside the step's range at 82–84. So the entry that stood for three
  rounds — *every drop is known to have dropped, none for the reason claimed* — is answered by reading
  rather than by running. **The lesson is about the instrument, not the feature**: a cumulative log will
  hand you another session's evidence as if it were this one's, and it did — the same read initially
  credited all of it to K6. Date-filter before attributing.
- **The agent authors `beats` unprompted** (K6 row 5) — the last open question about the tier, and the one
  that decides whether it is a feature or a fixture. One `Explain This`, and step 1 came back with three:
  *"Validation runs first"* → `_validation.Handle`, *"Pricing reads that verdict"* → `_pricing.Handle`,
  *"The audit handler then takes the price"* → `_audit.Handle`. Exactly the shape the tier exists for — one
  step narrating three things and pointing at each as it is spoken — chosen without being asked for, and
  with **0** `step.beat-unspoken`: every phrase it quoted was one it had actually written.
- **A hand-written `audioExplainer.xml` IS read back** (K6 row 6, and §2.2's long-standing other direction).
  Edited on disk with the IDE closed, and a fresh start picked the accent up in **both** the editor
  decorations and the tool window. Editing it while the IDE runs does nothing, which is not a defect: a
  `PersistentStateComponent` holds its state in memory and writes the file on save, so the in-memory copy
  wins and then overwrites. The reader's call on going further: *"we don't really need to spend resources
  tracking `audioExplainer.xml` changes from within the IDE"* — so file-watching is **declined**, not
  pending.
- **The accent reached half the plugin, and Settings ▸ Apply was the half that did not move** (K6, found
  sideways while answering row 6): *"changing accent color only changed tree of code default color, not the
  color of audio explainer window accents."* `themeVars()` has always read `accentColor`, so the panel was
  built to follow it — but `pushTheme()`'s only caller was `lookAndFeelChanged`, so the play button, the
  playback fill, the volume slider and the read-along wash held the old gold until the tool window was
  rebuilt or the IDE theme changed. Startup was always right, which is why it survived: the gap was only
  ever live Apply. Fixed by telling the sidebar the same way the tree is told — the walkthrough's own
  listener list, which is how Apply already finds anything.
- **The token search window, closed by deleting the question** (§4.3). Two plans lost an anchor to it —
  `MaxLines` on a step's *second* range, `DispatchResult` two lines past what its step lit — and the fix
  was not a wider window but a **line on the beat**, which is what every other anchor in the plugin already
  carries. The legacy shape keeps the widened window (the step's whole span, not its first range), so plans
  nobody will rewrite get the `MaxLines` case fixed too. Asserted both ways, including the control that a
  legacy beat has no line so the new load-time check cannot fire on it.
- **The callout inside a node** is exercised by every judged tree round, which is what retired the original
  "compiled, never seen" row: it has its own allowance in the nest's height ceiling and its own margin in
  the reveal (`rules.md` constraints 8, 9, 18, 21).
- **B3/A4's six arrow probes** proved a rendering that is now deleted, and two results outlived it: only
  the probe in a **scrolled** viewport distinguished content coordinates from viewport coordinates, and a
  synthesized click (scan the strip for accent-coloured pixels, then `SetCursorPos` + `mouse_event`) is
  still the way to drive a decoration nothing else can reach.

**Not done yet** — the standing list:

- **The absorbed-pulse schema on screen.** `Step.token` → `beats` is code-complete and headless-clean (134
  schema assertions, 44 over the clock and the matching), and the round that judged B1b judged the *old*
  two-anchor shape. Fixture 10 is rewritten to the new one: seven steps, including the legacy shape (step 7,
  a bare `token` over two ranges with the token in the **second**) which is the `MaxLines` regression, and
  three steps whose pulses now carry lines.
- **`ensureInstalled()` compares a version marker, not content.** A skill file edited *after* an install at
  the same version never propagates — found on this machine at 0.21.0, where the installed
  `plan-schema.md` predated a correction the bundled copy already carried. It bites a dev machine, not a
  clean install, so it is recorded rather than fixed.

### 2.8 Time-sensitive decorations must key off audio, never off step render

`NarrationController.narrateCurrent` moves the spotlight **first** and requests TTS **after**, so every
decoration precedes audio by the whole synthesis latency. Static decorations (callout, links)
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
The message carries the step `index`, and `flashCurrentToken` drops it if the spotlight has since moved —
otherwise audio starting just after a Next click would flash the *new* step's token at the *old* step's
start time. **Verified** on a 4-step walkthrough with real audio: flash lands **+6…+9 ms** after the first
audio chunk, down from −1780…−5966 ms. Steps with no `token` correctly flashed nothing.

**Why not the one-line "flash on first chunk" fix.** Not the buffer-depth argument that was originally
made — decode-and-schedule turned out to be 6–9 ms, so for a warm step the cheap fix would have been
nearly as good. What justifies `playback_started` is the case the measurement didn't exercise: with the
AudioContext **suspended** (before the first click in the panel, or while paused), chunks queue in
`pendingChunks` and become audible seconds later or never. "First chunk" keys off a moment that can precede
sound indefinitely; the playhead cannot. That, plus the channel §5 wanted anyway.

Consequences:

- **Renders no longer flash**, which removes the double flash for free: `set_plan` renders step 1 while
  paused and silent, and nothing pulses until audio begins. One deliberate exception is left,
  `flashOnRender()`: narration switched off entirely (`ttsEngine == off`), where no audio event will ever
  come. It reads the *setting*, not narration runtime state, so there is no stale flag. (A link click is
  *not* an exception — `revealEndpoint` lands the caret on the target token rather than pulsing it.)
- **Engine selected but not installed** is the remaining gap: narration is silently disabled, so no
  `playback_started` and no flash. That state produces no audio at all and prompts the user to finish
  setup, so a missing pulse is not the complaint they'd have. Same for a walkthrough loaded with the tool
  window never opened — no webview, no audio, no `playback_complete`, and it can't advance past step 1.

Generalises: step render is an unreliable proxy for "the user is hearing this", and the plugin *cannot*
answer that question — only the webview can. This message is the beginning of the playback-clock channel
B1b needs (§5).

### 2.9 Inlays paint over custom renderers, and no layer can change that

Reported from real use: relation arrows and their labels disappeared **behind** the callout box.

`ARROW_LAYER` is already `HighlighterLayer.LAST`, but `HighlighterLayer` only orders highlighters against
*each other* — the platform paints block inlays in a later pass than custom highlighter renderers, so an
inlay always wins against a `CustomHighlighterRenderer` no matter what layer it sits on. **There is no
z-index for this pair.** The choices are geometry (route clear of the box) or transparency (let the leg
read through it), and the first one is the real fix. This outlived the arrow renderer that found it:
`LinkArt` inherited the rule verbatim, and `SpotlightRenderer.laneDemands` still names it.

Three things had made the collision the *normal* case, not an edge case:

- The channel was placed from the code's line ends only, so **the box was invisible to the routing**.
- The box wraps to the full viewport, so it is usually *wider* than the code — which pushed the channel
  onto the clamp at `room`, i.e. straight through the box.
- The box is docked above the active line, and a link is drawn only when an endpoint is in the active
  step — so for the ordinary "from a line above into this step" relation, the box's band lies exactly
  between the two ends. The **label** is worse off than the leg: it is drawn at the vertical midpoint,
  which is inside that band almost by construction.

Fixed by making each half of the problem the other half's business — `inlayRight()` adds any block inlay
crossing the leg (ours *and* other plugins') to the channel `max`, and `linkReserve()` shrinks the box's
wrap width by the leg's clearance, only for steps that actually have links. Both KDocs carry the terms,
including why a portal is deliberately *not* reserved for. `calloutOpacityPercent` remains the escape hatch
for the case geometry can't win: code genuinely wider than the window, where the leg is clamped over both.

Generalises: **a decoration that reserves layout beats one that only draws.** §3's "prefer a custom
renderer when you don't need to displace text" still holds, but the two must be designed as neighbours —
whenever both are on screen at once, the drawn one has to be told where the reserved one is.

**A bounded spinner rejects out-of-range text by throwing from OK**, and that wedges the dialog. Typing `0`
into the then-`20..100` opacity spinner produced `java.text.ParseException: Value not within min/max range`
out of `RiderSettingsDialog.doOKAction`, logged SEVERE against this plugin with no frame of ours in the
stack, and the dialog would not close. The trap was never specific to the appearance rows: **every spinner
on the page had it**, including brightness percentages that predate this work. `clampedSpinner`'s KDoc has
the mechanism and the fix; two things belong here instead. The opacity floor could then drop to 0, which is
legitimate because only the box's *fill* carries that alpha — at 0 the text, bar and outline still draw,
the one look no frame style gives on its own. And both halves were **verified headlessly** by a plain
`javac` probe mirroring the helper in Swing and driving `commitEdit()` the way `doOKAction` does — the
ancestor of `probes/`, and a 3-second loop against a 3-minute one.

Generalises: **a settings control that can hold a value the dialog will reject is a way to wedge the
dialog**, and the range you picked to be helpful is exactly where someone types the value you excluded.

**Appearance knobs shipped with the fix**, since both decorations had their look wired to constants; §1's
table lists them, and every one defaults to the value its renderer used as a constant, so a fresh install
draws exactly what it drew before. Two notes for anyone extending them: the text scale goes through the
*same* `metrics()` both `calcHeightInPixels` and `paint` use, which is what
keeps §2.3's contract intact; and a link's label follows the *link's* colour while the callout's pointer
block stays on the accent, because the pointer line belongs to the box, not to the link.

### 2.10 A decoration has to answer something nothing else answers

The first thing use produced was a **deletion**: the A1 gutter badge went, because the number it drew was
already in the sidebar's step list and its click already existed as that list's outline. It read as a
feature while it was a plan and as clutter once there was a sidebar beside it. The rule that survives is
about *the set*, not the decoration: an editor decoration competes with every panel already on screen, so
the test is not "is this useful" but "is this the only place this is answered". Cost of the deletion was
~120 lines across four files, and two things had to be said out loud rather than deleted quietly — §2.5's
index bug was *found* by the badge, and §2.6's click-wiring pattern was *demonstrated* by it.

Four appearance changes shipped with it, and three push on contracts:

- **A spacer with a rule before the worked example.** The gap comes from one `gapBefore(block, drawn)` that
  both `textHeight` and `paint` call — the §2.3 contract again, and the reason the rule itself needs no
  height: it is drawn *inside* the gap it was given.
- **A `comment` frame style** — no panel, every line prefixed `// `, all text in the scheme's own
  `LINE_COMMENT` colour, so the aside reads as a comment somebody wrote in the file. This is the one frame
  that is not pure decoration: the prefix is real width, so it is charged to the wrap budget in `lines()`
  *and* added back in `textWidth()`. **A prefix charged on one side only is how a box clips its own last
  word.**
- **A settable description font, and deliberately not for the example.** Prose and pointer lines follow
  `calloutProseFont`; `BlockStyle.CODE` stays on the editor font, because code in a proportional face stops
  reading as code. An unknown family costs the family (`java.awt.Font` falls back), not the callout.
- **A leader** from the box to the line it annotates, which *reserves* the few px it draws in
  (`calcHeightInPixels` adds `LEADER_LEN`) rather than borrowing the margin — §2.9's generalisation applied
  deliberately for once, instead of discovered by collision.

The leader is the first decoration here that had to **inherit from a sibling decoration** rather than from
the scheme, and that was the whole difficulty. It began as a hairline with an arrowhead — frame-independent,
and therefore wrong beside four of the five frames. As a speech-bubble tail it has to ask the frame whether
there is a background to continue and whether it must draw its own edge, and then erase the box's own
outline across its own mouth; `drawLeader`'s KDoc has all three, and why the leader is painted *after* the
frame branch rather than inside it.

What belongs here is what the pixels said when a `javac` probe read them (§2.9's technique pointed at
Java2D instead of Swing). The erase had been written as a two-row band on the theory that antialiasing
smears a 1px stroke across two half-intensity rows — **it does not, for axis-aligned strokes**. And docked
**above** it does nothing at all, because the tail's own fill starts on the shared row and has already
covered it, so a no-erase baseline passes; it earns its place only docked **below**, where the frame's edge
runs across the mouth 12 accent pixels wide. **A decoration whose justification exists in one of two
orientations is exactly what a screenshot of the default certifies as correct.**

One settings-page note from the same batch: the description-font combo has a **live sample line**, and the
sample resolves its face by the same rule `CalloutRenderer.font` applies to a prose block — editor italic,
named family if one is named, times the text-size percentage. **A preview that computes its own answer is a
second implementation of the thing being previewed, and the day they disagree the preview is what gets
believed.** It follows the size spinner as well as the family combo for that reason, and it keeps the
dialog's own text colour rather than the callout's: over a settings background the softened body colour
reads as disabled text, and what is being judged here is the font.

**The same rule applied to a *switch*, one round later.** Round K1 deleted the whole "Step decorations"
group — `callout`, `calloutExample`, `tokenFlash`, `tokenFlashMs`, `relations`. Five switches over four
things, and the test above disposes of all five at once: each was already inert on a step carrying no
`note` / `example` / `token` / `relations`, which is most steps, so the only power they had left was to
hide a step that *did* have something to say. **A switch whose off state is "be shown less than the
narrator told you" is not a preference.** They are gone rather than pinned to `true`, because a setting
with one useful value is still a dialog row, an `audioExplainer.xml` field and a renderer branch, all of
them read by somebody later. The flash's duration went with them: `DEF_TOKEN_FLASH_MS` is simply what the
fade takes now, and it stayed a named constant so the code and its KDoc still cite one number.

**And the knobs that were not switches at all.** The same round found `arrowThicknessTenths`,
`arrowHeadSize` and `arrowSquareElbow` reading into an `ArrowStyle` that **nothing consumed**: round 6
replaced the elbow arrow with `LinkArt`, which carries its own constants, and the three dialog rows were
left pointing at the surface it replaced. They were wired up rather than deleted — a link's weight is a
real preference on a dense file, unlike the five above — and the wiring is `LinkMetrics`, *passed* to
`LinkArt` rather than looked up by it, because `probes/LinkBench.java` imports that file and runs with no
application to ask. Two of the three defaults already agreed with the drawing (`HEAD` is 7 and so is
`DEF_ARROW_HEAD`; both corners are 4), which is what the head's own KDoc had predicted. **The stroke had
drifted — 1.0 drawn against a 1.4 default — and the fix moved the default to the drawing, not the drawing
to the default, because 1 px is what nine rounds judged.** `arrowUseAccent` + a single `arrowColor` became
five per-slot pickers in the same pass: the old pair could only move the *base* of the hue rotation, and
what a reader wants to fix is one slot that reads badly against their theme. Blank means derive, so the
shipped palette is still `LinkArt.color(accent, slot)` and `LinkBench`'s hue-distance assertions still
measure it.

---

## §3 — Rendering (Axis A): complete

**No unbuilt Axis-A feature remains.** Everything the editor draws for a walkthrough exists; what's left
(B1b) was *when* an existing decoration fires, not a new thing to draw — and it is built.

`RangeHighlighter.setCustomRenderer(CustomHighlighterRenderer)` was the right API, and three notes
generalise to anything drawing *between* two places rather than *on* one:

- **A custom renderer needs no state.** `paint` recomputes both endpoints from their offsets via
  `Editor.offsetToXY`, which returns content-component coordinates — the same space the `Graphics` is in.
  So the drawing follows its code through scrolling, folding, font changes and resizes with no cache, no
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
       │                 + context JSON + docs/explain-at-cursor.md.
       │                 An inline schema string survives ONLY as the skillPath == null fallback.
       └─ runClaude(): headless `claude -p`; agent reads code, writes set_plan JSON to a
                       temp file, POSTs to the loopback server
ExplainerServer.dispatchAgentMessage("set_plan")
  └─ json.decodeFromJsonElement<Plan>(obj)        // ignoreUnknownKeys = true
       └─ walkthrough.setPlan(...) → fires WalkthroughListener
            ├─ TreePlanController (PlanLoader resolves files → opens the tree → one
            │                      SpotlightRenderer per node: spotlight + callout + flash;
            │                      links allocated across nodes by LinkArt)
            └─ NarrationController (audio + per-step advance on `playback_complete`)
```

Data model (`Protocol.kt`): `Plan { title, language, frames[], steps[] }` → `Frame { id, file, title,
explanation, scene, parent, callLine/callToken/callOccurrence, keep[], relations[] }` → `Step { frame,
ranges[]/frames[], ttsText, explanation?, note?, token?, occurrence?, example? }`. Line numbers are
**1-based, inclusive, line-only** — no columns. `TreeOfCode.rules.md` owns the frame/scene half of that
schema; this document owns the per-step fields the decorations read.

### 4.2 Invariants

1. **Additive & forward-compatible.** New optional fields with defaults decode cleanly on old IDEs
   (`ignoreUnknownKeys`) and old saved files stay valid. Every field is *opt-in*.
2. **A bare plan must render exactly as today.** Richness is something the agent *adds where it helps*,
   never a requirement. B2 is the template: no `note` → falls back to `explanation` → blank → nothing drawn.
3. **Locator reliability is the whole game.** Anything token-precise depends on the agent emitting a
   correct anchor. Locators are **text search over the step's own line range** — Rider-safe, no PSI.
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
{ "frame": 1, "ranges": [ { "start": 42, "end": 45 } ], "ttsText": "…",
  "token": "customerId", "occurrence": 1 }
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
- **The flash highlighter is held outside `tracked`** (per-frame churn would turn that list into garbage);
  `clear()` removes the flash explicitly *and* the timer's disposable does too, because the first frame is
  painted before the disposable is registered.

**B1b (next):** fire the flash on a phrase in the narration instead of once at audio start (§4.3).
*Not* a `start`/`duration` fraction of the audio — §4.3 records why a position is the wrong locator.
**Still open and now cheap:** position the callout at the token's x-offset instead of the line indent —
`indentPx` already establishes the measurement, so it's a change to *which* offset gets measured.

---

#### Tier B2 — In-code notes · **shipped**

Kept for the rationale: `explanation` is a 2–5 word sidebar label, too short for an in-editor callout, and
widening it would have wrecked the sidebar outline. A distinct `note` let the two diverge — `explanation`
→ sidebar label, `note` → editor callout, `ttsText` → spoken. Three audiences, three fields, no compromise.

---

#### Tier B3 — Relationships / links · feeds A4 · **shipped**

```jsonc
"relations": [                                     // on the Frame
  { "kind": "flow",      "from": { "line": 44, "token": "raw" },
                         "to":   { "line": 51, "token": "parsed" } },
  { "kind": "backforth", "from": { "line": 60 }, "to": { "line": 72 } },
  { "kind": "flow",      "from": { "line": 45, "token": "rate" },
                         "to":   { "file": "Pricing/RateTable.cs", "symbol": "RateTable.Lookup" },
                         "note": "value comes from RateTable.Lookup()" }
]
```

`Endpoint` is B1a's locator plus an optional `file` (absent ⇒ the frame's own) and a `symbol` label for an
off-screen target; `kind` is `flow` | `backforth`, and anything else reads as `flow` because an unfamiliar
kind should cost a nuance, not the relation. `Protocol.kt` defines both field by field — including what
`backforth` actually draws, which is *both ends arrived at* rather than the draft's second arrowhead.

**The central bet — *agent describes semantics, plugin picks the picture* — is what makes this tier work,
and it is stronger than it looks.** The agent **cannot** make the rendering choice: it doesn't know the
node's width, the fold state, whether the target file is in another node on screen, or how far apart the
lines land once the callout has pushed them down. All of that is known only at paint time. So "one drawn
leg vs a portal at each end" is not a field, and there is deliberately no way for a plan to ask for an
arrow. **That is also what let the whole picture be replaced** — arrow and gutter pointer out, `LinkArt`'s
one link system in — without a single authored plan changing.

- **Per-frame data, per-*step* rendering.** `relations` sits on the frame (a relation spans lines belonging
  to different steps), but drawing all of them during step 1 would be a thicket. A relation is drawn only
  while a step whose range contains one of its endpoints is active — which makes it a step decoration like
  the callout and flash, in time with the narration. Cost: one authoring rule — **anchor an endpoint inside
  the step the relation belongs to, or it is never seen.**
- **A drawn leg leaves from the *end of the line*, not from the endpoint token** — the one place the draft's
  "`token` makes it precise" reading had to give way, because a leg starting mid-line runs back over the rest
  of that line's code. `token` still does real work: it *validates* the endpoint (the only cheap check on a
  line number worked out by counting), it names the endpoint in the pointer form, and a **portal is anchored
  to it**, which is what made a number-on-a-chip unnecessary.
- **A wrong link is worse than no link** → validate-and-*degrade*, not validate-and-drop. Bad token, line
  past EOF, both ends on one row, ends 200 lines apart, target in another file: all fall back to a form that
  still says something true. Only a relation naming nothing and carrying no note is dropped outright.
- **The pointer form is a third callout block, not a second box.** `CalloutBlock` gained a `BlockStyle`
  (`PROSE` italic / `CODE` upright / `POINTER` upright accent-tinted) instead of B4's `code: Boolean`, so
  prose, example and pointers share one measure/paint contract in one box (§2.3) — which is why this tier
  needed no new inlay machinery. It is drawn even for a relation that became a portal: the prose sentence
  says where the link goes, which the mark alone cannot.
- **One setting, not two.** `relations` gates every form — they are renderings of one authored fact, and a
  reader who doesn't want links over their code doesn't want half of them.
- **Save/load** — `WalkthroughStorage`'s `relativize`/`absolutize` apply to endpoint files too. The draft's
  most easily-forgotten item; about four lines.
- **Not built, deliberately: animation.** `backforth` arrives at both ends rather than the draft's "animated
  pulse". It now belongs nowhere: B5's choreography half is dropped (§4.3), and `backforth` already says
  statically what the animation would have said over time.

Residual risk is unchanged and unfixable plugin-side: the agent must emit accurate line/token pairs for
both ends. The `token`-on-the-line check turns the most likely failure (an off-by-a-few line number) from a
confidently wrong picture into a degraded one, which is why `plan-schema.md` asks for a `token` on both
ends. One thing the probes showed that the design didn't anticipate: when the spanned code is wider than the
window, the leg is pinned at the viewport edge and can graze a long line — the right trade (a link inside
the window beats one off it), but worth knowing before someone reports it as a bug.

---

#### Tier B4 — Example blocks · feeds A2 · **one-liner shipped**

```jsonc
{ "frame": 1, "ranges": [ { "start": 42, "end": 45 } ], "ttsText": "…",
  "example": "Parse(\"2024-01\") → DateOnly(2024, 1, 1)" }
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
- **Per-block caps, not one shared budget** (§2.4), so a long note can't eat the example's room. The worst
  case is a tall box, which is why `plan-schema.md` says to use the fields together only where each earns it.

---

#### Tier B1b — one anchor shape, timed to the narration · **built and judged (K6)**

```jsonc
{ "frame": 0, "ranges": [ { "start": 42, "end": 51 } ],
  "ttsText": "The dispatcher looks up the handler. If it is missing it throws.",
  "beats": [ { "line": 44, "token": "TryGetValue", "say": "looks up the handler" },
             { "line": 48, "token": "throw",       "say": "if it is missing" } ] }
```

Today's one flash per step, at `playback_started`, became several, each tied to a point in the step's own
narration. The renderer needed nothing: `SpotlightRenderer.flashToken` already took an arbitrary token and
line window (`:696`) and `startTokenFlash` already opened with `cancelFlash()` (`:1928`), so repeat calls
mid-step were the designed case. §3 is empty; this was a **scheduling** problem over an existing renderer.

**B5's choreography — links and callouts drawing themselves in time — is dropped, not deferred.**

1. §2.10 decides it. A link that draws itself answers the same question as a link already drawn: it is
   motion, not information. The flash answers *which of these words is she saying now*, which nothing else
   answers. That asymmetry is the tier.
2. Neither is a feature on top of what exists. Links have no per-pair add/remove — `paintLinks` replaces the
   set and `setHoveredPair` changes brightness only — so revealing them one at a time means re-running
   `TreePlanView.paintLinkPairs`, the tree-global lane allocator, which would re-lane the links already on
   screen. `CalloutRenderer` takes its blocks as a constructor parameter; new text is a new inlay and a
   height change under a reader mid-sentence.
3. Neither can fall back on a re-render: `paintStep` opens with `clear()` (`SpotlightRenderer.kt:613`),
   which disposes `trackedDisposables` and kills the in-flight flash.

**The locator is a phrase, never a sentence number** — the draft's `"at": {"sentence": 1}` is rejected.
Three parties count sentences and none compares notes: the author counts prose; `split_sentences` splits on
`(?<=[.!?])\s+` (`tts_server.py:144`), so `e.g.` splits, and re-chops anything over 320 chars on commas
(`:166`); the webview tokenises the raw `ttsText` itself, on a different punctuation set for CJK
(`sidebar.js:380`). Only the *number* `si` crosses the wire, never the text, so nothing can detect the
divergence. This is the decision B1a already made (a token, not a column): an agent can quote a phrase out
of prose it just wrote and cannot predict where a regex will cut it. A sentence number would have been the
project's first index-based locator, against §4.4 rule 2.

**Echo the sentence's text with each chunk** — one field beside `si`/`sw` (`tts_server.py:842`). It turns
counting into searching: the webview matches the beat's phrase against what was *actually spoken*, so the
beat stays right when the split changes — and it does change, because the installed script is user-editable
and refreshed only on a VERSION difference (§2.1). Field absent ⇒ fall back to the transcript's own words.

**Round every beat to its sentence's start. Word granularity is not honestly buildable.** No engine reports
word timings: espeak's callback receives `espeak_EVENT *events` and drops it (`tts_server.py:497`), SAPI
calls `Speak` with no `SpeakProgress`, and Kokoro — the primary tier — has none to give. Word position is
`floor(local * wordCount)` (`sidebar.js:460`), a flat interpolation worth about ±1 s mid-sentence against a
700 ms pulse. A sentence's `tStart` is a sum of real decoded buffer durations and is accurate to a frame.

**`Step.token` was absorbed, not kept beside `beats`.** The first cut shipped both and needed a four-row
table to say which won, a suppression rule in `flashCurrentToken`, and a two-source resolution in
`flashBeat` where a beat's `occurrence` had to travel with whichever token was chosen. The reader cut
through it in one question — *"do we need `token`/`tokenLine` at all if we can put the info in `beats`?"* —
and the answer was to flip which part is optional:

- **no `say`** → fires when the audio starts. *That is exactly what `token` did.*
- **`say`** → fires when that sentence starts.

So a beat is `{ line, token, occurrence?, say? }` — **the same anchor an `Endpoint` and a `callLine` already
use**, and there was only ever one mechanism underneath all three: `findNth`, a plain scan of a window for
the nth match of a string, never PSI and never a column (§4.4 rule 2). What made the step's pulse the odd
one out was not the mechanism but the **missing line**. Everything the second spelling needed went with it:
the table, the suppression, the two-source resolution, and `flashCurrentToken` itself (now `flashStartBeat`,
which just calls `flashBeat` on the at-start one).

**And a line is checkable where a token never was.** `PlanLoader` has no VFS — it is arithmetic over line
numbers — so it could never say anything about a token. It *can* say whether a line is among the ones the
step lights, the same check `step.range-outside-keep` already makes. That is `step.beat-line-outside-ranges`,
and it turns the whole family of failure that cost two plans (`MaxLines`, `DispatchResult` — §2.7) from a
FINE log line nobody opens into a warning in the `set_plan` reply the agent reads. Reported, **not** dropped:
the author named that line, and honouring it beats guessing.

**Legacy is one synthesis, at the loader.** A step with `token` and no `beats` becomes a single beat with no
`say` and `line = 0`; `line = 0` selects the step's **whole span** rather than one line, which is also what
retires the `MaxLines` case for plans nobody will rewrite. Nothing downstream of `PlanLoader` reads
`Step.token`, so the two spellings cannot drift.

**As built**, in wire order: `tts_server.py` adds `stext` beside `si`/`sw`; `TtsListener.onChunk` grew a
fifth parameter and `NarrationController` forwards it; `StepView.beats` carries the beats out; `sidebar.js`
gains `sentenceForBeat` / `fireDueBeats` off the same `stepElapsed()` the cursor reads, posting
`{type:"beat", index, beat}` and leaving at-start beats to the plugin; `AudioExplainerSidebar` routes both
`beat` and `playback_started` into `TreePlanController` → `TreePlanView.flashBeat` → the existing
`SpotlightRenderer.flashToken`. Nothing new paints. Two rules the code enforces that the schema only asks
for: **one pulse per spoken sentence**, and **at most one beat without a `say`** — both because
`startTokenFlash` self-cancels, so two pulses on one instant show only the second.

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
job in narration is to forward the chunk to the webview as `si`/`sw`. Audio is played by **Web Audio in the
sidebar**, buffered — so `onChunk` means "sentence N was synthesized and queued", not "sentence N is being
heard". (`NativePcmPlayer` does play natively, but it's only the voice-editor Test button.)

**It's cheaper than the draft feared anyway,** because the webview already maintains a playback-time
sentence schedule for the read-along cursor (`sidebar.js`):

- `stepSentences[si] = { wordStart, wordCount, tStart, dur }` — built in `recordChunk`, in AudioContext time.
- `updateStepProgress` — a `requestAnimationFrame` loop calling
  `applyReadAlong(audioCtx.currentTime - stepStartClock)` every frame while audio plays.
- `playback_complete` already proves the webview→plugin direction.

| Tier | What | Cost |
|---|---|---|
| (a) **per-step** | today's advance on `playback_complete` | exists |
| (a½) **step start** | A3.1's `playback_started` from the rAF loop | exists |
| (b) **per-sentence** | `postMessage` when the playhead crosses a sentence start, + a plugin-side handler | small — one message type, reuses the running loop |
| (c) **per-percent** | same loop, throttled position ticks | small math, but don't cross the process boundary at 60 Hz — throttle to ~10 Hz or send only on beat boundaries |

**B1a needed none of this** (fixed-duration pulse). B1b hangs off (b), and should copy `playback_started`'s
staleness check rather than reinvent one — with audio in a different process than the decoration, *every*
playback message can arrive after the state it describes has moved on. The (b)→(c) climb is still worth
respecting, but the reason is payload volume, not missing infrastructure.

---

## §6 — Build order

Done, in §1's table order: A1 (+A2, bundled in because only A2 exercised `trackedDisposables`) → B3.1 →
B2 + A2 → B1a + A3 → A3.1 (unplanned; measurement forced it — §2.8) → B4 → B3 + A4.
… → **B1b** (§4.3, on §5 tier (b)), preceded by its prerequisite, the underrun drift. B5's choreography half
is dropped rather than queued; §4.3 records the three reasons.

**No tier is left, the last one has been heard, and the agent writes for it** (K6). What remains in §2.7
is one open decision — how wide the token search window should be — and one recorded non-fix. The ordering rule that paid here was the older one restated —
**fix the clock before hanging anything off it**: the drift had been latent for two phases and only became
a defect worth fixing when something other than a cursor was going to read that number.

Two ordering rules worth keeping, both of which paid: **a B-tier that introduces a new locator kind precedes
the A-feature that consumes it**, and **a shared prerequisite precedes the first field that would otherwise
duplicate it** (B3.1 was pulled ahead of B2 because adding a field to four competing schema statements would
have entrenched the drift). Rendering has consistently been the cheap half — including A4, the one feature
the draft rated highest on the rendering axis, and the one that has since been rebuilt from scratch
(`LinkArt`) without the schema moving.

---

## §7 — Touchpoints

- **Schema types:** `Protocol.kt` (`Step` / `Frame` / `Plan` / `Relation` / `Endpoint`).
- **Canonical schema doc:** `claude-skill/docs/plan-schema.md` — **the** place new fields are defined.
- **Prompt:** `ClaudeExplainer.buildPrompt` — points at `plan-schema.md`; the inline string is the
  no-skill-dir fallback only.
- **Skill docs referencing the schema:** `explain-at-cursor.md`, `segments.md`, `walkthrough.md`,
  `SKILL.md` — these keep *pointing*, not restating.
- **Skill install (§2.1):** `claude-skill/manifest.txt` **and** `claude-skill/VERSION` — both needed for
  any new or changed skill file to reach users.
- **Rendering:** `SpotlightRenderer.kt` — extend `tracked` (markup-owned) or `trackedDisposables`
  (everything else). `LinkArt.kt` owns every link's palette, lane and routing across nodes; `tree/` owns
  the nodes the renderers draw in.
- **Listener side:** `TreePlanController.kt` — file resolution, tree open/close, EDT marshalling, and the
  live re-style Settings ▸ Apply reaches (`refresh()`).
- **Settings:** `AudioExplainerSettings.kt` + `HighlightSettingsConfigurable.kt` — each visual decoration
  gets a toggle, the toggle goes in Reset-to-defaults too, and every spinner goes through `clampedSpinner`
  (§2.9).
- **Interaction wiring:** `AudioExplainerSidebar.forEachOwnTreeController` (§2.6) — also how a webview
  playback message reaches the decorations (`playback_started` → `flashCurrentToken`).
- **Timing/beats:** `sidebar/sidebar.js` — `recordChunk` / `stepSentences` / `updateStepProgress` (§5,
  §2.8). *Not* `NarrationController.onChunk`. Anything that must coincide with the voice is emitted from
  `updateStepProgress`, because the Web Audio clock lives only there.
- **Save/load:** `WalkthroughStorage` — relative-path rewrite for any new `file` fields.
- **Server decode is already tolerant:** `ExplainerServer`'s `Json { ignoreUnknownKeys = true }`.
  Tolerant means *silent* — the reason §4.2 invariant 4 matters.
