# Multi-block / multi-line substeps — technical evaluation #1

Evaluation of two proposals for letting one narration substep focus **several** line blocks:
(A) collapsing the irrelevant lines between blocks via editor folding, (B) several sub-editors, each
showing one block. Plus a recommended third path.

Sources: `Protocol.kt`, `EditorHighlighter.kt` (all 1867 lines), `NarrationController.kt`,
`Walkthrough.kt`, `WalkthroughStorage.kt`, `plan-schema.md`, `sidebar.js`, MoreUI.md §2.9/§3/§4.4/§5–7.

> **Line citations below are pre-split.** `TreeOfCode.md` Phase 1a moved the whole drawing stack out of
> `EditorHighlighter.kt` into `SpotlightRenderer.kt`, so every `:NNN` reference to that file is stale and
> `highlightSubRange` is now `SpotlightRenderer.spotlightStep`. The *analysis* is unaffected — nothing about
> the role walk, the effective-range expansion or the blast radius changed, only where it lives. This
> document is the **third-tier fallback** (Stage 1) if both Arch B and Arch A fail their gates.

> **Confidence.** Everything about *this codebase* is read from it. Everything about **platform
> folding / embedded-component APIs is from knowledge, not verified in Rider 261** — flagged inline and
> collected in §6.

> **Addendum (after writing):** back-compatibility is **not** required — the plugin is unshipped and the
> current state is committed to git on a dev branch. So the `start`/`end`-stays-the-span trick in §4
> Stage 1 is an *option*, not a constraint: `ranges` may simply replace `start`/`end` outright. The
> renderer work is identical either way; only the schema doc and `sidebar.js` label change.

---

## 1. What you already have (the delta is smaller than it looks)

`highlightSubRange` (`EditorHighlighter.kt:223-308`) does **not** assume one block per segment. It already:

- expands an "effective range" over *every* highlight in the segment (`:243-250`),
- builds `highlightedLines` from all of them and walks the range assigning **three** roles — active /
  non-active-highlight (`segmentDim` + optional accent tint) / gap (`c.gap` + gutter bar) — coalescing
  runs of the same role (`:261-279`).

So the sketch — *"highlighted block 1, collapsed lines, highlighted block 2, 1 grayed line, highlighted
block 3"* — is already ~70 % rendered today, minus the collapsing. The **only** single-range assumption is
that `activeHl` contributes one `zeroStart..zeroEnd` pair (`:239-240, :270, :282`).

Blast radius, verified: `Highlight.start/end` is read in **exactly two places** — `EditorHighlighter.kt`
and `sidebar.js`. `NarrationController` drives playback purely by highlight *index* and `ttsText`
(`:217-243`); `Walkthrough` never looks at line numbers. Neither needs a single line changed.

The request decomposes into three capabilities with wildly different costs:

| | Capability | Where it lands |
|---|---|---|
| **(1)** | multi-range *active* step, one file | `highlightSubRange` — cheap |
| **(2)** | compressing the irrelevant lines between them | folding — medium, risky |
| **(3)** | one step spanning several *files* | new surface — expensive |

Treat them separately. (1) is worth doing regardless of what happens to (2) and (3).

---

## 2. Approach A — folding the gaps

**Possible in Rider: yes, with caveats.** The editor is the real platform editor (the plugin already
drives its markup model, inlay model and Java2D painting in the sandbox), so `editor.foldingModel` is
there. Two API shapes: classic `addFoldRegion(start, end, placeholder)` inside
`runBatchFoldingOperation { }`, or `addCustomLinesFolding(startLine, endLine, renderer)` (2021.3+),
which yields a `CustomFoldRegion` occupying its own visual line that **we paint** — i.e. literally the
`⋯ 24 lines ⋯` separator from the sketch, in the plugin's accent. The second is the better fit and
reuses the Java2D competence already in `CalloutRenderer`.

Two places in the current code were **already written fold-aware**: the arrow renderer bails when a fold
collapses both endpoints onto one row (`:1433`), and `inlayRight` treats `inlay.bounds == null` as
"folded away, cannot collide" (`:1509`). That groundwork is done.

Risks, ordered by likelihood of biting:

1. **Fold regions may nest but not partially overlap.** `addFoldRegion` returns `null` when the range
   intersects an existing region. The gaps we want to fold will *routinely* straddle method / brace /
   `#region` boundaries that Rider's own folding builder already owns, so the naive call fails on real
   code more often than it succeeds. Fix: inside the batch op, find intersecting regions, snapshot and
   `removeFoldRegion` them, add ours; on teardown remove ours and let
   `CodeFoldingManager.updateFoldRegions(editor)` rebuild the platform's. Workable, but it is us
   fighting the platform for ownership of a shared model — the #1 thing to spike.
2. **We mutate persistent user state.** Fold state is per-editor and saved in the workspace, so an
   interrupted walkthrough (IDE closed mid-step, exception inside `runOnEdt`, editor closed) leaves the
   user's file collapsed with no explanation. Contrast with everything drawn today, which is 100 %
   ephemeral. Needs snapshot/restore on every transition *plus* restore-on-dispose *plus* realistically
   a "Restore folding" escape hatch.
3. **Caret vs. collapsed region.** `revealCenter` (`:898-906`) moves the caret before scrolling. The
   platform will not leave a caret inside a collapsed region — it either expands the region or relocates
   the caret. Either outcome breaks a fold just made. Ordering becomes load-bearing: fold first, then
   caret into an *active* block, never into a gap.
4. **Rider's folding is backend-driven.** Regions arrive from ReSharper over the protocol; a
   frontend-added region may be dropped when the backend pushes a fresh folding model after a reparse.
   Unverified, and the risk that could kill the approach outright.
5. **Reflow jumpiness.** Re-folding per step changes the visual line count under the user mid-narration —
   scroll jumps, callout and arrow geometry moving while the voice talks.

Mitigation for (5) that also improves UX and cuts cost: **fold once per segment, not per step** — union
every highlight's ranges in the segment, fold the gaps between them on segment entry, leave it stable
while stepping. The story's *shape* holds still and only the spotlight moves. Do it this way regardless.

Architecturally it fits the existing discipline cleanly: fold regions are neither markup-owned nor
`Disposable`, so the unfold goes in a `Disposable` in `trackedDisposables` (`:105`) — the same slot the
inlay and flash timer use, and `clearTracked` already runs on every transition, on `STOPPED`
(`:142-146`) and on project dispose.

**Complexity: Medium**, ~2–3 days including the spike, with a genuine chance the spike says "not robust
in Rider". The consolation is a graceful failure mode: drop folding, keep (1), long gaps stay dimmed.

---

## 3. Approach B — several sub-editors

**Possible: yes.** `EditorFactory.createEditor(document, project, virtualFile, isViewer = true)` over the
*real* `Document` gives real syntax highlighting (important — see the trap below), one per block, stacked
in a container, each scrolled to its block with gutter and scrollbars suppressed.

Arrows between them are, counter-intuitively, **easier** than in-editor: no code to route around, and
`SwingUtilities.convertPoint` plus a transparent overlay above the children is a well-trodden Swing
pattern. §2.9's "inlays always paint over custom renderers, no z-index exists for that pair" simply does
not apply — we own the Z order.

The problems are structural rather than technical:

1. **Where does it live?** Not the tool window — that is the JCEF sidebar, and it has no width to spare.
   Real width means a custom `FileEditor` + `FileEditorProvider` over a `LightVirtualFile` opened as a
   tab. Doable, non-trivial, and a new user-visible surface concept.
2. **It forks the rendering surface.** Callout, token flash, gutter pointers, gap bars, the whole
   `Palette`, and every future decoration then need two homes — against MoreUI.md §2.10's own test ("is
   this the only place this is answered") and against the entire A2/A3/A4 investment.
3. **It costs the user their own context**: their tab, their scroll position, their sense of where in the
   file this is. Dimming preserves all three; a panel of excerpts discards them.
4. N live editors per step: daemon passes, memory, disposal discipline multiplied.

**Complexity: High**, a week-plus, and it permanently doubles the maintenance surface. It *is* the only
one of the two proposals that solves (3) properly.

**The Rider-specific trap worth knowing before anyone tries this:** the obvious cheap implementation —
stitch the excerpts into one synthetic `LightVirtualFile` / `DocumentImpl` with `⋯` separators and show
one editor — will likely render as **plain, uncoloured text in Rider**. C# highlighting is computed by
the backend for files it knows as part of a project; a synthetic in-memory document is not one. In
IntelliJ (lexer-based) the same code would look perfect, which is exactly how this gets built and then
discovered. Use **real editors over the real `Document`/`VirtualFile`** and let each fold or scroll to
its block.

---

## 4. Recommended path

### Stage 1 — `Highlight.ranges` (first, independent of everything else). ~1 day, low risk.

```json
{ "start": 42, "end": 96,
  "ranges": [ {"start": 42, "end": 45}, {"start": 60, "end": 63}, {"start": 94, "end": 96} ],
  "ttsText": "…", "token": "customerId" }
```

Optional back-compat convention (see the addendum — no longer required): keep `start`/`end` as the
*bounding span* and let `ranges` carry the detail; absent `ranges` ⇒ `[start..end]`.

Work list:

- `Protocol.kt`: `LineRange`, `Highlight.ranges`, helper `Highlight.effectiveRanges()`.
- `SpotlightRenderer.spotlightStep` (was `EditorHighlighter.highlightSubRange`): replace `zeroStart/zeroEnd` with a resolved range list; build an
  `activeLines` set beside `highlightedLines`; the `:268-279` walk becomes role-by-line over **three**
  roles — *simpler* than the current special-cased skip of the active block. One
  `addLineRange(c.active)` per range (each then gets its own overview-ruler stripe — a bonus: the error
  stripe becomes a map of the step).
- Callout anchors to the **first** range's first line (`indentPx` is that block's own left edge, matching
  the rationale at `:351-353`); `calloutBelow` anchors to the last range's last line.
- `resolveToken` / `startTokenFlash` / `flashCurrentToken`: try each range in order, first hit wins.
- `inActive` (`:496-498`): "inside any range".
- `revealCenter`: needs an explicit policy. Suggest — if the whole span fits the viewport
  (`visibleArea.height / lineHeight`) centre the span, else centre the first range. Currently it
  unconditionally centres the midpoint, which for a 3-block span lands on a folded-away gap.
- `sidebar.js`: label at `:946` → span or `42–45 +2`; **and add `ranges` to the rebuild fingerprint at
  `:818-821`** — as written, a plan whose ranges changed but whose `start`/`end` did not keeps a stale
  outline row.
- Docs, per §2.1's four-file rule: `plan-schema.md` (authoritative) + `segments.md` + `manifest.txt` +
  `VERSION`, plus the inline fallback in `ClaudeExplainer.buildPrompt`. Ship the renderer cap as an
  authoring rule (§2.4): **max 3–4 ranges per step, ascending, non-overlapping, same file**.
- No `WalkthroughStorage` change — no new `file`-bearing field (§4.4 rule 6 satisfied trivially, which is
  *why* Stage 1 is cheap).

### Stage 2 — segment-scoped fold ("story mode"), gated. ~2 days + spike.

Approach A with per-segment scope, `addCustomLinesFolding` for the `⋯ N lines ⋯` separator, unfold as a
`trackedDisposables` entry, off by default until proven in Rider. Only fold gaps longer than a threshold
(~4 lines) — short gaps stay dimmed, which is precisely the "1 grayed line" in the sketch, already
implemented.

### Stage 3 — cross-file, only if Stage 1+2 prove insufficient.

Two observations first:

- **Cross-file is already supported — sequentially.** Two files in one step = two segments today. What is
  actually missing is *simultaneity*, and narration is linear: only one place can be talked about at a
  time. The real requirement is **context retention** — seeing B while hearing why A calls it.
- That is satisfiable without a new surface: a **peek excerpt** — the other file's 4–6 relevant lines
  drawn as a block inlay under the active block, reusing `CalloutRenderer`'s measure/wrap/paint machinery
  and the existing `relations`→pointer degradation (`:520-546`) as its data model. ~200–300 lines, one
  surface, zero folding, zero user-state mutation. Handles same-file-300-lines-away and cross-file with
  one mechanism. Gives up syntax colour (a plain-coloured peek is acceptable; if not, the backend's own
  `RangeHighlighter`s over the target document can be sampled for `TextAttributes` — possible, but a
  rabbit hole).
- If a *real* editor is required in there, the platform way is `EditorEmbeddedComponentManager` — a real
  component hosted inside the editor as a block inlay, which is how notebook cells and inline watches
  work. Same idea as approach B, hosted in the user's editor instead of a new tab. **Experimental API,
  availability in Rider 261 unverified.**

---

## 5. Summary

| Option | Rider-feasible | Effort | Risk | Verdict |
|---|---|---|---|---|
| **(1) multi-range active step** | yes, no new API | ~1 day | low | **do it now** |
| **(2) folding, per-segment** | probably | ~2–3 d | medium — shared model, persistent state, backend sync | do after (1), gated, spike first |
| **(2′) folding, per-step** | same | same | high — reflow under the voice | don't |
| **(3) N sub-editors in a tool window** | yes | 1 wk+ | forks the rendering surface permanently | don't |
| **(3′) peek excerpt inlay** | yes, reuses A2 | ~2 d | low | the cheap answer to cross-file |
| **(3″) embedded real editor inlay** | unverified | ~4 d | experimental API | only if 3′ isn't enough |

---

## 6. Spikes required before committing to folding

These genuinely need the sandbox — folding is where the frontend meets the ReSharper backend, which no
`javac` probe can reproduce.

1. In a real C# file, `runBatchFoldingOperation { addFoldRegion(gap) }` across a method boundary — does
   it return `null`? Remove-then-add: does it hold?
2. Type in the file afterwards. Does the backend's next folding push drop the region?
3. Does `addCustomLinesFolding` exist and paint in 261?
4. Collapse a region containing the caret — expanded, or caret moved?
5. Close the file with folds applied, reopen: are they still collapsed? (Answers "how bad is an
   interrupted walkthrough".)

If 1 and 2 both pass, Stage 2 is straightforward. If either fails, Stage 1 plus the peek inlay covers the
motivating case — code spread across a long file or across files — without ever taking ownership of the
folding model.
