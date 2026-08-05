# Tree of Code Views — implementation plan

A **call-tree code viewer**: a read-only tree whose nodes are outlined, editor-like views of real files — each
showing only the relevant code with the rest collapsed, each independently scrollable — depicting a
caller→callee chain. The existing in-place spotlight is preserved as a second view mode, not replaced.

**Status:** Phase 0 ✅ · 0b ✅ · 1a ✅ · 1a′ ✅ · 1a″ ✅ · 1a‴ ✅ (G1c PASSED — Arch C nesting is the chosen
layout) · 1a⁗ ✅ (built and judged: 6 items accepted, 4 sent back, 2 rejected) · cleanup pass ✅ → next:
**§1a⁵**, then gate **G1c′**.

**§1a⁵ is now a specification, not a punch list.** After the cleanup pass the reader specified the three leader
modes to the pixel, scrapped `NodeChrome.TITLED_BORDER` outright, and decided the header rule — so the remaining
Phase 1 work is transcription plus one bench fix. The governing idea is **constraint 16**: the token box, the
leader and the child's frame are *one outline*, so a leader mode decides which border edges each part draws.

**Architecture: Arch B — one editor per node, over the real document — laid out as Arch C, each child embedded
in its parent's editor at the call site.** Settled by measurement rather than preference: the semantic
highlighting Arch B was supposed to need a blocking spike for is measured working in exactly its configuration
(**182 / 321 / 302** `ReSharper.*` markers, folded *and* decorated, unchanged when nested) and the
stitched-document alternative scored **zero** on the same test; independent per-node scrolling is impossible in
a single document; and the reader's verdict on containment was "nested is absolute winner". The Arch A and
Stage-1 fallbacks are **no longer live options** — see `TreeOfCode.completed.md` for what each measured.

**The one live risk, and the cleanup pass deliberately made it bigger.**
`EditorEmbeddedComponentManager` is an `impl`-package API (constraint 15). A flat-tree fallback needing no impl
API *was* built and measured — the parent split into before-call / after-call nodes, the child between them —
and the 1a⁗ round scrapped it to reduce scope: *"Do we actually need those additional layouts for anything? I
would just scrap them to decrease scope — the nested layout is all we need."* So `CodeTreeView` and everything
under it is gone, and **there is no built fallback**. If that API is removed, the answer is to rebuild that
layout, which is a week rather than an afternoon. The reader has the measurement (6/6 embeddings, no exceptions,
clean disposal across 25 rebuilds) and took the scope reduction knowingly. **And rebuilding it will not be a
`git revert`:** the whole `tree/` and `spike/` package was still untracked when the cleanup pass ran, so the
deleted code was never committed — see the cleanup record below.

**Where the rest of the record lives:**

| For | Read |
|---|---|
| What each completed phase set out to test, what it measured, and the criteria that were wrong | `TreeOfCode.completed.md` |
| Measured spike numbers and corrections for Phases 0 / 0b | `MultilineTreeFindings1.md` §6, §7 |
| Platform API signatures verified in RD-261.26222.60 | `MultilineTreeFindings1.md` §1 |
| Schema starting point | `MultilineTreeFindings1.md` §3 |

**Nothing is deleted, it is moved.** When a phase closes, its probe specifications and per-probe verdicts go to
`TreeOfCode.completed.md` and this file keeps only what still constrains the work ahead — so if a rule below
looks arbitrary, its evidence is in the ledger, not lost. Keep doing this: a plan that also carries its own
history stops being read. The cleanup pass is where the moving actually happens, and it is a scheduled work
item rather than tidying.

Working branch; no backward compatibility required.

---

## Constraints that bind the remaining work

Every item is measured, not assumed. These are the rules Phases 1–4 have to build inside. **The numbers are
referenced from code comments, so they do not get renumbered** — a constraint that stops applying says so and
keeps its number.

**Rendering**

1. **De-emphasis must act on `backgroundColor`, never `foregroundColor`.** The dim roles sit above the syntax
   layer, so a foreground dim flattens every dimmed row to one grey — which is what made the Phase 0
   prototype read as plain text. Acceptable for context lines *around* a segment in the in-place editor;
   **not** acceptable for whole non-active nodes, which are the tree's structure. Hard constraint on Phase 3.
2. **Decorations are per-editor, not per-document.** Two editors over one document share none of our
   highlighters, so the whole decoration set is applied per editor. One `SpotlightRenderer` per node editor.
3. **Semantic (ReSharper) colour follows the file being real on disk.** Identical content scored 84 markers
   for a real document in a viewer with no `FileEditor` vs **0** for a `LightVirtualFile`, even with a tab
   open. Settled at N: 78–321 markers/doc, 23–30 categories, at 12 editors folded *and* decorated;
   re-confirmed on the built component and again when nested. ReSharper type-hint inlays render too
   (`var normalised :Money`). Rider must load the **solution**, not the folder — the backend is what produces
   the colour, and solution-loaded ≠ backend-ready, so sample late.
4. **The active-node tint takes its accent from `AudioExplainerSettings`**, as the shipping decoration code
   already does. `JBColor.namedColor("Focus.color", …)` rendered dark red and read as an error band.

**Folding and layout**

5. **A node does not own its folding model.** The daemon pushes its own folding into a viewer-only editor over
   a real document (19–59 foreign regions arrived after our `clearFoldRegions()`; 13–25 per node in practice).
   It **adds** rather than replaces, so no re-assert loop is needed — but **height resync must be driven by a
   `FoldingListener`**. Measured 3/3 of ours still collapsed in every node, in every run, at every depth.

   Two things not to misread. Rider logs `FrontendEditorFoldingModelAdapter — Unable to insert (…) in the
   folding tree. Maybe the duplicate range already/still exist` **twice per node on every open**: our regions
   are nonetheless installed and stay collapsed, so this is the adapter declining to mirror a range the backend
   already has, not a lost elision. And **a reader who opens an elision looks exactly like a lost one** — so
   the count is split: `invalidFolds()` is a real loss, `expandedFolds()` is a reader. Any check that fails on
   `ours < requested` fails on a reader clicking `⋯ 30 lines ⋯`.
6. **Custom fold regions refuse to expand** — `shouldNeverExpand=true`, `setExpanded(true)` is a no-op. A
   reader-openable elision needs plain `addFoldRegion` with a placeholder instead, which is why the elision
   style is a real choice and not a cosmetic one.
7. **A drawing that must survive a child editor's own repaint has to be a *sibling above it*, never
   container-side painting.** Measured 1444 → **3** connector pixels when a child editor repainted itself on
   scroll, because `paintChildren` only runs when the *container* paints and nothing then repaints the drawing.
   A sibling survives exactly, because Swing's `RepaintManager` includes overlapping components above the dirty
   child.

   Arch C's `LeaderStrip` **is** this rule one level down, and that is now the only form of it that ships: the
   `JLayeredPane` overlay that the flat tree used is deleted. Two properties are load-bearing and both fail
   *silently*: the sibling must be `isOpaque = false` where it overlaps, and **`contains()` must claim only its
   own band**. The last one is the trap — Swing routes an event to the topmost component containing the point,
   so an overlapping sibling with the default `contains` takes **every click and wheel notch in the nested
   editor's top rows**, which reads as the editor being dead at the top rather than as something being in the
   way. Measured both ways, twice: on the flat overlay and again on the leader strip.
8. **`maxHeight` = 600 px is a *ceiling*, and it is a boundary rather than a limit.** Measured on the chain:
   folded **484 / 550 / 585–612 px** against unfolded 2464–3740, so exactly one of three nodes crosses it. A
   220 px cap was so aggressive that all 12 nodes exceeded it and grow-vs-scroll never got compared; 600 lets
   both behaviours appear in one screen. Capped beat grow by eye. §1a⁵ 4 adds a second number beside it: a
   nested child also wants a **target** height — its excerpt's — and ceiling and target can disagree.
9. **A node's height depends on its own width, and on what is drawn in it.** The docked callout is a block
   inlay that *wraps*, so the same node at the same step measured 612 px at 1050 px wide and 585 px at 1268 px
   wide. **A measured number is meaningless without the thing it was measured against** — see constraint 16 for
   the same rule arriving as a font rather than a width.

   The stronger consequence: **rendering a step changes a node's height**, because the callout inlay arrives,
   leaves and is re-measured. Nothing else can see that — not a `FoldingListener`, not a font listener, not a
   resize — so `CodeNodeView` listens to its own `InlayModel` (82 events / 19 real height changes on one node
   in a single session). Two earlier benches hid this by revalidating their own container after every render,
   which is why it took a container that deliberately does not to surface it.
10. **Tool-window width is not a stable quantity** — 413/716/784 px across runs. Not a host to design against;
    the host is an editor tab via the `FileEditorProvider` probe B2 proved out (~90 lines, 15–19 ms/node, no
    leaks), which every bench since has run in.

**Platform discipline these components get wrong quietly** — all three cost a `SEVERE` in `idea.log` while the
UI looked perfect and the spike report said `0 fail`.

11. **A colour-scheme change must re-render, not merely re-measure.** Both `SpotlightRenderer` and the node
    chrome derive every colour *per render*, so after a theme switch what is on screen is the old scheme's
    colours: dark tints and a white callout font in a light theme, until something forces a repaint. Resyncing
    the height is not enough — the owner has to re-render the current step. `CodeNodeView.onRerenderNeeded`
    and `EditorHighlighter`'s own scheme subscription are the two halves of this, and the N requests are
    coalesced onto one dispatch (`requests=4 dispatches=1` at N=4).
12. **Reading the document model needs a read action even on the EDT.** `FileDocumentManager.getDocument` and
    `document.charsSequence` both trip `softAssertReadAccess` — which *logs* and continues, so the omission is
    invisible unless someone greps the log. Wrap them in `ReadAction.compute`.
13. **An application-level subscription must be parented to a `Disposable` its owner actually disposes.** A
    `JPanel` that implements `Disposable` is only disposed by the Disposer *tree*; an owner calling
    `dispose()` directly leaves the subscription live, and the next theme switch drives it into `offsetToXY`
    on a released editor. Own an explicit child disposable and dispose it first.
14. **An unclosed `/*` smears across concatenated excerpts and a separator row cannot stop it** — only emitting
    `*/` can. Dormant: it applied to the Arch A fallback, which is no longer live. Kept because it binds any
    future surface that concatenates excerpts into one document.

**Nesting — measured in 1a‴/1a⁗, and binding on everything after**

15. **An editor can hold an editor, and the platform accounts for it.**
    `EditorEmbeddedComponentManager.getInstance().addComponent(viewerEditor, component, Properties)` accepts a
    **viewer** editor, returns a valid `Inlay`, and — the part that makes the whole layout legal —
    **`offsetToXY` counts the block inlay**, so the host's own text reflows below the nested frame instead of
    being drawn over. Measured: `MainLoop`'s content height went **528 → 1741 px** around a 1138 px child, and
    the line after the call site landed at y=1727, below the frame's bottom. 6 of 6 embeddings across three
    cases, no exceptions, `getAllEditors()` **6 → 5 across 25 rebuilds**. It is an `impl` package all the same.
16. **The token box, the leader and the child's frame are ONE outline, not three drawings that meet.** Settled by
    the reader after the 1a⁗ round, and it is the rule the whole leader redesign (§1a⁵ 1) follows from: in the
    joining modes the leader does not *land on* a border, it **is** the border for the length of its run. So the
    box gives up its bottom edge and the frame gives up its top edge, and the three parts form a single
    continuous path from the frame's bottom-left, up and around the token, and back down to the frame's bottom-
    right.

    **Two consequences that are not stylistic.**

    - **A border segment's owner depends on the leader mode.** The same `CodeNodeView` needs a 3-sided border
      under `CORNERS`/`BRACE` and a 4-sided one under `ARROW`, and the same token box needs 3 sides and then 4.
      Neither is a property of the node or of the token alone. Whatever draws them has to be told the mode.
    - **The leader is structural, so "no leader" is not a coherent option** — it would leave a bottomless box
      above a topless frame with nothing joining them. Same for the box: a leader that starts at "the box's
      bottom corners" needs a box. This is what removes `NestLeader.NONE` and the whole `TokenMark` choice
      (§1a⁵ 4); a genuine minimal mode would have to be a *fourth* leader mode that restores both borders and
      draws nothing between them, not an independent off-switch on either half.

    **What this dissolved.** `NodeChrome.TITLED_BORDER` — the chrome whose line is drawn at the caption's
    mid-height rather than at its bounds edge — is **scrapped**, and with it the entire drawn-line-offset
    problem: the font-dependent offset (measured 7 px in a mock and 9 px in the real component), the
    `CaptionBorder` written to make that offset a queryable value, `chromeDrawnTopOffset()`, and the
    rounded-corner defect the fix introduced. **All of it is history, in the ledger.** Every surviving chrome
    draws its line *at* its bounds edge, so the offset is 0 by construction rather than by agreement. The
    lesson keeps its number because the *shape* of it still binds: two drawings that must meet need one
    definition of where, which is what `LeaderArt` exists to enforce — and sharing one number was not enough
    when a second (the corner radius) was not shared.

    **What survives unchanged: leader mode and chrome are one design, not two independent knobs.** The coupling
    just moved — it used to be "does the leader reach the drawn line", it is now "which border segments does the
    leader own". Phase 4 still must not offer them as two free choices.

    **The strip needs no container-side paint**, because the strip and the child are *siblings*: a strip at
    z-order 0 is constraint 7's own mechanism. Measured with its control — strip above, tips drawn; strip sent
    to back, the child's opaque background erases them entirely. What changes is *how far* it reaches: it no
    longer has to cover a caption inset, only to leave no seam where the leader meets the frame's side walls.
    **Whether that seam needs a 1 px overlap or exact adjacency is a probe question** (the pre-flight probe
    already measured that a stroke drawn to `y == height` is clipped away, so the leader ends at `height − 1`
    and the frame's walls start at its own row 0 — a 1 px stagger unless something absorbs it). If the answer is
    zero overlap, the strip stops covering any of the editor's rows and constraint 7's `contains()` **cost**
    disappears; the rule itself stays, because it has bitten twice.
17. **Height propagation up a nesting chain costs one deferred pass per level.** A nested child's re-measure
    reaches its *host's* inlay only through the host node's geometry callback, which coalesces onto an
    `invokeLater` — so at depth 2 the outer inlay is one EDT turn behind. It was a real failure first
    (collapse-then-expand restored 1741 → 650 → **1186** px, short by exactly the inner frame's 602 − 47) and
    is now closed with a number: a synchronous innermost-first walk up the chain measures
    **1677 → 1080 → 1677 px, exact at two levels**. Either walk the chain synchronously or state the settle
    time — do not assume one pass. **And this recurs in every new check**, not once: it caught the mouse-reach
    check next (see §1a⁵ 5).
18. **A boxed token needs padding, and `EffectType.BOXED` has none.** The box is drawn on the glyph bounds, so
    it touches the first and last character and reads as clipping the text. Padding needs a
    `CustomHighlighterRenderer` inflating the rectangle. **The defect and the risk are measured against
    different ink**, which is the trap: the defect is clearance to the token's **own** glyphs (`BOXED` measures
    **0 px**), the risk is collision with the **neighbours** (at pad 0 there is none, so measuring the defect
    against the neighbours reports it as absent).

    The answer is a cheap choice with a bounded cost, **not a clean window**: pad **2 px** takes clearance
    0 → 2, costing 2 pixels of stroke on a character that follows the token with no space (`…verdict);`). The
    cost is **non-monotonic** — 2 → 4 → 3 → 0 as the stroke walks across the adjacent glyph and out the other
    side — so a wider box buys only width, and the box is painted behind the text so the glyph wins the pixel
    anyway. Vertical inflate is clamped to the row. **Closed by eye: "padding ok now."**

    Also: **the box and the leader must be the same colour.** They are the same object to the reader's eye, and
    they were the raw accent and the accent blended 0.80 toward the border until `LeaderArt` made one
    derivation serve both. Constraint 16 makes this stronger than a colour rule — they are now literally one
    path, so a divergence would show as a seam in a continuous stroke rather than as two similar lines.

    **And the box loses its bottom edge in `CORNERS`/`BRACE`** (constraint 16), which the padded-box renderer
    has to express: 2 px of padding on three sides, and the fourth side is where the leader takes over. The
    vertical padding still matters on the surviving bottom corners, since that is where the leader's origin sits
    — the box's *padded* bottom corners, not the glyphs'.
19. **A nested node does not scroll at its natural size, so nothing can be revealed by scrolling it.** Measured
    by the reader: "nested subeditor does not have scrollbar, innermost subeditor only gets one if I expand some
    collapsed block" — and when one does appear, the wheel reaches it and "scrolling feels natural and ok". Not
    a defect; a consequence of sizing. A host grows to content (`growHosts`) and a leaf's folded content
    measures 484–612 px against a 600 px cap, so there is nothing to scroll in either.

    It binds everything that wants to *move* a nested node's viewport — the landing (§1a⁵ 4), Phase 3's per-node
    scroll policy, and "playback resuming re-asserts the authored viewport". **A reveal is only expressible if
    the node is capped below its content**, so a cap is not merely a size limit: it is what creates the viewport
    that attention can be moved inside. Choosing "grow" for a node silently switches its half of Phase 3's
    scroll policy off.

---

## Correction carried forward: the attention point does NOT go away

`MultilineTreeFindings1.md` §3 said `NarrationController` "gets simpler". That referred **only to index
bookkeeping** — two-level `segIndex` + `hlIndex` collapsing into one `stepIndex`. It did **not** mean a step
advance stops moving the visual attention point.

**Every per-step decoration stays, and stays per-step.** Active-range glow, docked callout, token flash on
audio start, scroll-to-reveal, overview-ruler mark — all of it continues to move on every step, exactly as it
does today. A narrated walkthrough with a static picture would be strictly worse than what already ships.

What the tree *adds*, for Phase 3 to answer:

- **A fifth line role.** Today: `dim` / `segmentDim` / `gap` / `active`. A tree needs **non-active frame** as
  well, so the eye can tell "this whole frame is not what is being talked about" from "this line inside the
  active frame is not the focus".
- **Multiple callouts — decided, and deferred.** The shape is **one Primary callout plus Helper callouts**: the
  Primary brightest and most prominent, Helpers visibly secondary. Judged at N nodes and wanted, but
  **explicitly not now** — Phase 1 and Phase 2 keep the single callout on a designated primary frame. Two
  things follow. A Primary is not inherently mandatory: a step whose narration needs no box can have none. It
  *becomes* mandatory as soon as the callout carries anything interactive — putting playback controls in it is
  a plausible later direction, and that is the change that would force it. Do not build the renderer in a way
  that forecloses either.
- **Scroll policy.** A step's active ranges may live in two frames far apart. Reveal all of them when they fit
  the viewport; otherwise reveal the primary frame's. Today's unconditional `revealCenter` on a midpoint would
  land on an elided gap.

---

## The C# fixture solution — `fixtures/TreeSpike/`

Built in P0b.0, reused by Phase 1b. Three projects (`Fixture.App` → `Fixture.Processing` → `Fixture.Core`),
net8.0, builds clean and runs. Full anchor table in its own `README.md`.

| Case | Where | Anchors |
|---|---|---|
| **3-deep chain, 3 files** | `MainInvoker.MainLoop` → `MidInvoker.InvokeOn` → `SubInvoker.Apply` | class headers 18 / 17 / 15, methods 68 / 36 / 68, `int someParam = SomeSysCall();` at 85 |
| **Same file, two nodes** | `Invokers.cs` | `InvokeOn` 36 calls `Normalize` 123 — 87 lines apart, one document |
| **Wide, one parent → three children** | `Dispatcher.Dispatch` 45 | three handlers called from lines 50 / 51 / 52, in three files |

Three properties are deliberate and load-bearing:

- **The gaps.** ~49 lines between `MainInvoker`'s header and `MainLoop`, ~16 more before the `someParam` line,
  ~52 in `SubInvoker`. A node keeping header + method must elide them.
- **No diagnostics.** `GenerateDocumentationFile` is off; with it on, 82 CS1591 warnings covered the fixture
  in squiggles — precisely the noise B5 had to see past.
- **Handler files are short (38–47 lines) where chain files are long (111–169)**, so 1c can judge
  grow-to-content against a `maxHeight` on nodes that actually differ in size.

---

## Phase 1 — UI prototype: a tree of node views · *in progress*

**Goal:** build the node-view tree well enough to judge it, and settle the layout questions the schema then
has to satisfy. Driven by a hand-written fixture; no schema commitment, no skill changes.

### What 1a–1a⁗ already settled · **DONE**

Five sub-phases, each with its own sandbox round driven by the reader. **The full record — what each set out to
test, every measured number, every by-eye verdict and every criterion that turned out to be wrong — is in
`TreeOfCode.completed.md`.** What survives into the work ahead is the constraint list above, the 1c verdicts
below, and this:

| Phase | Built | Outcome |
|---|---|---|
| **1a** | `SpotlightRenderer` (the drawing stack, one per editor) split out of `EditorHighlighter` (the listener) | in-place walkthrough unchanged; 3 defects found later became constraints 11–13 |
| **1a′** | `CodeNodeView` — one viewer editor over one real document, chrome around it, everything per-node | folds survive the daemon, semantic colour holds on the built thing, resync fires per trigger, `6 → 6` editors |
| **1a″** | `CodeTreeView` — depth layout, `JLayeredPane` overlay, five connector shapes | **38 pass / 0 fail, 0 `SEVERE`.** Deleted in the cleanup pass; its overlay lesson is constraint 7 |
| **1a‴** | `CodeNestView` — Arch C nesting, three layouts one click apart | **G1c PASSED** — "nested is absolute winner". 65 pass / 1 fail; the FAIL became constraint 17 |
| **1a⁗** | Nesting polish: chrome × leader, padded box, one affordance, landing, multi-level collapse fix | **6 accepted, 4 sent back, 2 rejected.** 111 pass / 1 fail (the check's own) / 0 `SEVERE`. Sent back → §1a⁵ |

Three carry-forwards are worth naming here because they are still open:

- **The renderer API is still `Segment`/`Highlight`-shaped** — `spotlightSegment(start, end)`,
  `spotlightStep(segment, index)`, `flashToken(hl)` — *not* the "(line ranges, roles, `Palette`)" signature the
  tree ultimately wants. Deferred since 1a, and correctly: the signature follows the schema, so writing it now
  would mean inventing the schema twice. **Phase 2 work item.**
- **`width=` fires ~95–425 times per session and publishes nothing.** Harmless (the height guard absorbs it)
  but a resize storm costs one `preferredSize` computation per node per pass. Revisit if N grows.
- **Collapsing an opened `⋯ N lines ⋯` back needs the small hover-only gutter arrow.** The standing annoyance
  from 1a′ onward; acceptable, since opening one should be rare.

### 1a⁵. What the 1a⁗ round sent back · **NEXT**

Ordered by whether the answer is already known. **Nothing here is exploratory** — the reader specified the leader
geometry, the chrome rule and the landing mechanism directly, so this section is a transcription of decisions
rather than a list of options. Three items of the original list were deletions and are done; see the cleanup
record.

#### 1. The three leader modes — specified, and they are the user setting

**One outline, three shapes** (constraint 16). The token box's bottom edge and the frame's top edge are not
decoration that the leader avoids: in the two joining modes they are *absent*, and the leader is that stretch of
the outline. In the third the leader leaves an intact box and points at an intact frame.

**One origin rule for all three modes, and it is the thing the last round got wrong:** the leader leaves the box
**at the box's bottom-edge level** — the row where the suppressed bottom border would have been — from a bottom
corner. Not from the middle of the token's row, which is what the split fallback did and the reader caught
immediately; and not from a row below the box, which would leave a gap in a continuous outline. Sharing the
*path* between two surfaces was not enough last time; the **origin** is the part that has to be shared.

**`CORNERS` — the diagonals.** Box: left, top, right (**no bottom**). Frame: left, right, bottom (**no top**).
Two straight runs, box's bottom-left corner → frame's top-left corner and box's bottom-right → frame's top-right.
One continuous path.

```
           ╭-------------╮
   var x = | y.Invoke(); |
          ╱              ╲ (spans to corners)
  ╱                                      ╲
  |    path/to/y.cs    y.Invoke       ▼   |
  |  SubEditor code                       |
  |  SubEditor code                       |
  ╰---------------------------------------╯
```

**`BRACE` — two L-shaped halves, orthogonal, not curved.** Same border suppression as `CORNERS`. Each half is
two segments: **outward horizontally** from the box's bottom corner, at that corner's level, to the frame's side
wall — then **down** into the frame's top corner. **One right angle per half, at the outer end.** No central stem
and no spine across the middle, which is what was wrong with the built version; and no S-curve, which is what the
previous pass of this plan wrongly wrote down. Read the drawing as the specification: the horizontal run sits
exactly where the box's suppressed bottom border would be, so the box's bottom edge is not missing so much as
*redirected outwards*.

```
           ╭-------------╮
   var x = | y.Invoke(); |
  ╭--------╯             ╰----------------╮
  |    path/to/y.cs    y.Invoke       ▼   |
  |  SubEditor code                       |
  |  SubEditor code                       |
  ╰---------------------------------------╯
```

Its advantage over `CORNERS` is the reason to keep both: *"unlike diagonals braces do not overlap with header
text"* — the horizontal run sits in the strip, above the caption row a diagonal cuts across.

**`ARROW` — a reference, not a join.** Box: **all four sides**. Frame: **all four sides**. The box's bottom
border **continues past its own bottom-right corner** (collinear — it reads as the bottom edge extended, not as a
new line leaving a corner), runs right to an x **between the box's right edge and the frame's right edge**, turns
down, and ends in an **arrowhead** pointing at the frame's top border without touching it.

```
           ╭-------------╮
   var x = | y.Invoke(); | // comment
           ╰-------------┴--------╮
                                  ↓
  ╭---------------------------------------╮
  |    path/to/y.cs    y.Invoke       ▼   |
  |  SubEditor code                       |
  |  SubEditor code                       |
  ╰---------------------------------------╯
```

Four things to get right, in rough order of how easily they go wrong:

- **Top corners are square in `CORNERS`/`BRACE`, and that is what dissolves the rounding defect.** With no top
  edge there is no top corner to round: the "corner" is just where a side wall begins, and the leader arrives
  into it. Bottom corners stay rounded as they are today. `ARROW`'s frame keeps four rounded corners, and the
  arrowhead lands mid-span where rounding cannot reach it.
- **`stripHeight` stops being cosmetic.** All three leaders originate at the box's bottom-edge level and have to
  arrive at the frame's top, so the strip's height *is* that vertical distance — which sets a diagonal's slope,
  a brace's drop, and how far `ARROW` descends before its head. Every one of the three shapes changes character
  if it changes, so it is a number to judge by eye at a stated width (constraint 9), not a spacing default.
- **The seam.** See constraint 16: a stroke drawn to `y == height` is clipped, so the leader ends at
  `height − 1` while the frame's walls start at their own row 0. Measure whether that leaves a visible notch
  before choosing an overlap.
- **`ARROW`'s turn-down x is the only free parameter in any of the three.** Pick a fraction, look at it, write
  the number down with the width it was judged at (constraint 9).

**These three are the user setting** — one choice, three values, exposed in Phase 4. There is no fourth value:
"no leader" is incoherent once the leader carries border segments (constraint 16).

#### 2. Chrome: `TITLED_BORDER` is scrapped, and the header rule is decided

**Scrap `NodeChrome.TITLED_BORDER` entirely** — the chrome that draws its top line through the middle of the
caption. It was the only chrome whose drawn line was not its bounds edge, and it is the sole reason the
drawn-line offset, `CaptionBorder` and `chromeDrawnTopOffset()` ever existed. All of that goes with it
(constraint 16).

**The header rule, decided rather than open:** show the header when the node is **top-level** *or* **in a
different file from its parent**; hide it when a nested child is in the **same file** as its caller. So the
question the previous pass left open — same-file only, or every nested child — is answered: a cross-file child
keeps its caption, because that caption is the only place its file name appears. This is a **rule, not a
preference**, so it does not reach the settings dialog.

**Header styling:** distinguished by **background** only — the current `HEADER_STRIP` treatment, which is
already right — and **no border line below it**. (Already true: the strip is an opaque label with padding, and
the only line is the frame's own. Recorded so it is not "improved" later.) The header band also holds the single
collapse affordance at its trailing edge, which is unchanged.

So `NodeChrome` reduces to two values, and they are selected by the rule above, not by a knob: a
background-tinted caption band, or none.

#### 3. What the outline rule deletes

Consequences of constraint 16, not separate decisions:

- **`NestLeader.NONE`** — a leaderless mode leaves a bottomless box above a topless frame.
- **`TokenMark` as a choice, and probably the enum.** The box is where every leader mode starts, so it is
  structural in all three. It was already down to `BOX` and `NONE` after the last round; `NONE` now goes too.
- **`NodeChrome.TITLED_BORDER`, `CaptionBorder`, `chromeDrawnTopOffset()`, `leaderOverlap()`'s offset role**, and
  the bench's `reportLeaderLanding` check in its current form — it asserts that the chrome's drawn offset and the
  strip's overlap agree, a question that stops existing. Its replacement asserts the *new* rule: the leader's
  endpoints coincide with the frame's top corners, and the frame draws **no** top edge in `CORNERS`/`BRACE` and
  **does** in `ARROW`.

A note on cost, because this is the second pass over the same geometry: `CaptionBorder` and the offset machinery
were built to make a chrome the reader has now scrapped work correctly. That work is not wasted — it is what
produced constraint 16's measurements and the "share the geometry, not one number" lesson — but the cheaper route
existed and was visible at the time. Two of the four chromes measured **0** offset from the start, and the plan
recorded that as a convenience rather than as a reason to prefer them.

**Landing, re-mechanised (verdict 12) — the item to think about before coding:**

#### 4. Landing: cap a nested node to its excerpt, then scroll it to the member

*"Landing 'member' is better on subeditors,
   but worse on top editor. I think we should do it other way — using default scroll. Ie. leave the class line as
   before, but inside subeditor SCROLL by default to a member … This requires subeditor with initial height capped
   exactly to height of exerpt of interest."*

Three consequences, and they are why this is a redesign rather than a revert:

- **The elision-based landing is reverted** — done in the cleanup pass. `keep` is honoured as authored, so the
  class header is a visible row again.
- **A nested child's height stops being `min(content, 600)`.** It becomes the height of the excerpt of interest
  — the focus range's line count — which is what creates the scroll room constraint 19 says does not otherwise
  exist.
- **The root node is exempt.** Landing applies to nested children only, which is also the answer to why the
  elision version read worse at the top: the top node is the reader's entry point and its class header is the
  one place the file's identity is not already implied by a caller.

And it hands Phase 3 something: once a nested node has a viewport smaller than its content, "reveal the active
range in *its* node" becomes expressible for the first time.

**Open, and it needs a decision before coding:** constraint 8's 600 px was a *ceiling* and this is a *target*,
and the two can disagree — a 200-line member wants the ceiling, a 6-line member wants six lines. Which wins,
and is there a floor below which a subeditor is too short to read?

#### 5. One defect in the bench's own checks (found by reading the reports, not the UI)

**`reportMouseReach` measures before a depth-2 frame is placed.** It guards on the *child's* `isShowing` and
size, which pass while the ancestor chain is still settling, so the first report of a session fails on the
doubly-nested node and a later one passes. Fix: sample the centre of the child's frame **intersected with the
host's bounds** (`frameBoundsIn`), and treat "point outside the host" as *not measured* rather than as a
failure — a stolen event returns the host's component, a `null` means the check aimed into space. Keep the real
assertion: a point genuinely inside the child that resolves to the host is still a FAIL.

**Gate G1c′, restated for the specified geometry:**

- Each of the three leader modes draws the outline constraint 16 describes — **the box's bottom edge and the
  frame's top edge absent in `CORNERS`/`BRACE`, both present in `ARROW`** — with no seam where the leader meets
  the frame's walls, and `ARROW` ending in an arrowhead short of the frame.
- `BRACE` is two stepped halves from the box's bottom corners, and its horizontal run clears the caption row.
- The header appears exactly when the node is top-level or in a different file from its parent, and nowhere else.
- A nested child opens scrolled to its member inside a viewport sized to it; the root opens on its first kept line.
- All three modes reachable from one setting, and switching between them leaves no stale border segment behind —
  the mode changes which edges are drawn, so a restyle that only repaints the leader would leave a visible ghost.

The log grep and the open-close-repeat editor count are **already banked** from the 1a⁗ artifacts and clean
(0 `SEVERE`, 6 → 5 editors across 25 rebuilds), so the next round inherits them rather than owing them. None of
it is a gate on Arch C itself — that gate is passed.

### 1b. Fixture + prototype types

A throwaway fixture read from a file, so layout can be iterated without the agent, the skill or the HTTP
server: `scratchpad/tree-fixtures/callchain.json` → proto types in a `proto/` package, deleted in Phase 2.

Fixtures must name **real files in a real solution**, since nodes are views of actual documents — that is
`fixtures/TreeSpike`, and its three cases are exactly the three the prototype needs to show.

### 1c. Alternatives — the verdicts

The per-node alternatives were **built as `CodeNodeStyle` / `NestStyle` knobs and judged**, on real code in the
bench, by the person the surface is for. These are decisions, not comparisons to run again; each is the shipped
default and the survivors become user settings in Phase 4. **Rejected options are recorded as rejected so they do
not reach the settings dialog** — a knob the reader has already turned down is a comparison the *user* would have
to redo, and the cleanup pass deleted the code behind each one.

| Row | Verdict | Note |
|---|---|---|
| **Chrome** | **a rule, not a knob**: header iff the node is top-level or in a different file from its parent | one value per tree until constraint 16 re-opened it; the reader then specified the rule outright (§1a⁵ 2). `TITLED_BORDER` **scrapped** — it was the only chrome drawing its line off its bounds edge — and a borderless variant **rejected** earlier as "strictly worse than plain". What is left is background-tinted caption band or nothing, chosen by role. **Not a user setting** |
| **Path** | `PROJECT_RELATIVE` | full is unusable at depth, basename is ambiguous in exactly the same-file case the fixture exists for |
| **De-emphasis** | `TINT` alone | tint+border was the old default and read as too much; border-only too little |
| **Sizing** | **capped**, 600 px as a *ceiling* | capped "looks more convenient" than grow. A nested child also wants a **target** height — its excerpt's (§1a⁵ 4) — and ceiling and target can disagree |
| **Elision** | `OPENABLE` | and it earns it: hovering a collapsed run shows the elided code in a tooltip, for free. Collapsing it back needs the gutter arrow |
| **Layout** | **nested (Arch C), and now the only one** | "nested is absolute winner", then 1a⁗ scrapped the flat and split layouts rather than carry them |
| **Leader mode** | three modes, **user-selectable**: `CORNERS` / `BRACE` / `ARROW` | `FLARE` dismissed unbuilt; `NONE` removed as incoherent. All three specified to the pixel by the reader (§1a⁵ 1) and all three **still to build** — the shipped versions were sent back. The mode decides which border edges the box and the frame draw, so it is one setting with three values and no independent chrome knob beside it |
| **Token mark** | **always a box** — not a choice at all | the background tint was "still bad" at every padding; then constraint 16 made the box *structural* (every leader mode starts at its corners), so the off-switch went too. Padded 2 px, sharing the leader's colour, and **three-sided** in `CORNERS`/`BRACE` (constraint 18) |
| **Node landing** | **scroll inside a capped nested node** | the elision version was judged better in a nested node and *worse* in the root; the mechanism the reader wants is a default scroll in a subeditor sized to its excerpt. §1a⁵ 4 |
| **Nesting indent** | **0 px** | the nested node's own gutter already reads as nesting, so no width is spent on an indent — and past ~24 px the frame's left edge passes the invoking token and the left diagonal runs backwards, so the non-default values were a defect rather than a choice. The knob is deleted |

**Retired by containment**, and recorded as answered so nobody re-opens them: **depth expression** (indentation
was a number to tune only while depth had to be *expressed*; containment **is** the cue and the nested gutter is
the boundary) and **connectors** (`ELBOW` / `RAIL` / `BRACKET` / `LEADER` / `NONE` all drew correctly and all
resolved their anchors — and the whole routing problem is gone, since the child is physically at the call site.
This also closes "should a connector point at the child's first *kept* line or at its member": **there is no
child-side anchor to choose any more**).

One parting finding from the connector work, because it outlives the code and is the reason `LeaderArt` exists:
the flat tree anchored its leader to a line's **centre** while the nested surface anchors to the token box's
**bottom**, and the round caught it immediately ("leader top corner height calculation is flawed in this mode").
Two surfaces with two notions of "where the call is" is what one shared vocabulary is for — and sharing the
*path* was not enough, the **origin** has to be shared too.

**Attention across nodes** — *decided in part.* The node half is built (`setActive`) and the pair read well at
N nodes. On multi-callout the verdict is: **allow several, with one Primary** (brightest, most prominent) and
the rest **Helper** callouts — but **not now**. Phase 1 keeps the single callout. Two consequences worth
carrying: the Primary is *not necessarily mandatory* on its own terms, and it becomes mandatory the moment it
carries anything interactive — playback controls in the callout is a plausible later direction, and that is
what would force the distinction.

**Host** — an editor tab via the `FileEditorProvider` from B2, which the bench has been running in. Tool window
only if a node tree happens to read well narrow (constraint 10 says its width is not designable-against).

Also still open, and cheap: **whether one line of leading context should stay visible above each elision.**

### Deliverables

- A working node-view tree from a fixture, in an editor tab, over a real solution
- A screenshot set, one per alternative, and a short written verdict per row above
- Confirmation that semantic colour survives the **built** thing — nodes shown, folded, decorated, N at once
- A list of layout constraints the schema must satisfy (feeds Phase 2): max depth, max nodes, max ranges per
  node, whether context lines must be explicit

**Gate G1:** the node tree reads clearly on real code and the N-editor machinery is stable. **Both halves are
now met**: G1c passed on the layout question and the stability half is measured at every level of containment —
6/6 embeddings, elision intact, semantic colour unchanged, no editor leak across 25 rebuilds. After the cleanup
pass the `impl`-package dependency has no built fallback — see the top of this file.

**Estimate: 1–1.5 days remaining** (1a through 1a⁗ and the cleanup pass spent; **0.5–1 for §1a⁵ and G1c′** —
the leader redesign touches three border owners rather than one drawing — plus 0.5 fixture).

### Cleanup pass · **DONE**

Asked for by the reader alongside the §1a⁵ redesigns, and a real work item rather than tidying: the round's
scope decision left a large amount of the tree package dead, and **dead code that still compiles is
indistinguishable from a supported option to whoever reads it next.** `compileKotlin` is clean and every
`plugin.xml` registration was re-checked against a real class, which is the half of this that fails silently.

**Deleted.**

| What | Why |
|---|---|
| `CodeTreeView.kt` in full — depth layout, `JLayeredPane` overlay, `TreeConnector`, `CodeTreeStyle`, `ConnectorGeometry`, `AnchorState`, the `LEADER` token plumbing | the flat and split layouts are scrapped |
| The flat/split branches of the Arch C bench, and `splitAtCallSites` | nothing left to switch to |
| `NodeChrome.NONE` | rejected: "strictly worse than plain" |
| `TokenMark.BACKGROUND` and `BOX_AND_BACKGROUND` | rejected: "background still bad … leave the box as only one" |
| `CodeNodeView.effectiveKeep` + `landingLine` + `landOn` + `landOnMember` | the mechanism was rejected; §1a⁵ 4 replaces it |
| `CodeNodeSpec.linkFrom`, and the bench's `linkAnchor` beside it | superseded by `CodeTreeEdge.parentLine` — the call site belongs to the **edge**, since a parent can have three children |
| `NestStyle.indentPx` | settled at 0, and its other values reproduce a rejected defect |
| `SpikeStoryEditorAction` (Arch A), `SpikeNodeTreeAction` + `SpikeNodeTree` + the autorun (Phase 0b), `SpikeTreeContainerAction` (1a″), `SpikeRenderers`, `SpikeFixture` | benches for surfaces that no longer exist |
| `spikes/b3a/`, `spikes/tree1a2/`, `spikes/tree1a3/`, and every compiled `out/` | findings are in the ledger and the constraint list |
| The `-PspikeNodeTree` autorun half of the `runIde` block | the bench it armed is gone; the useful half (open the fixture **solution**) survives as `-PspikeFixture` |

**Kept, and two of them corrected the list this pass was working from:**

- **`SpikeNodeTreeEditorProvider.kt` — the list said delete it, and that was wrong.** It is the B2
  `FileEditorProvider` and its `SpikeTreeVirtualFile`, which is the tab *both* surviving benches open
  themselves in. It outlived the bench it was named for.
- **`lineOfAnchor` was declared in `SpikeNodeTree.kt`** — deleted — **and both survivors call it.** Recovered
  from the previous build's bytecode (`javap -c`) rather than rewritten from guesswork, and moved to
  `SpikeSupport.kt` where the shared plumbing lives. Same class of mistake as the item above, caught by the
  compiler instead of by review.
- `SpikeNodeViewAction` (the single-node bench is still the cheapest way to judge a node in isolation) and
  `SpikeNestedNodeAction` minus its dead branches.
- `spikes/tree1a4/NestPolishProbe.java` — **§1a⁵'s brace and arrow geometry is exactly what it measures**, in
  two seconds rather than a 4–7 minute sandbox launch.
- `CodeTreeEdge`, **moved** into `CodeNestView.kt` rather than deleted: it was declared in `CodeTreeView.kt` and
  it is how the nested surface learns where a call site is. Kept under its old name — Phase 2 rewrites the whole
  vocabulary, so a rename now would churn every call site for a name with one release to live.

**One thing the round assumed and this pass disproved: none of it was in git.** `tree/` and `spike/` were
still **untracked** when the deletions ran, so "rebuild it from git history" — written into this plan as the
fallback for the `impl`-package risk — was not true of any of it. The deletions themselves are sound (the
verdicts are explicit and the ledger holds the findings), but **commit before the next destructive pass**, and
read the top of this file as the statement of a risk with no undo rather than one with a slow undo.

**Also retired here:** the original §1a⁵ item 10 (`reportLanding`'s SKIP conflating "one range each" with
"landing is off"). The branch went with the mechanism, so there is nothing to fix. The check that replaced it
**asserts nothing** — it prints each node's first visible line, content height, cap and `scrolls()` and says
what §1a⁵ 4 will assert — because a PASS computed off a spec field while no mechanism is running is exactly the
kind of green report this project has learned not to trust.

---

## Phase 2 — Schema

**Goal:** one schema, shaped by what Phase 1 proved renderable, serving **both** surfaces. Starting point is
`MultilineTreeFindings1.md` §3 — `frames` (structure) separate from `steps` (timeline) — amended by Phase 1's
constraint list. Node views make the `frames` half *more* natural: a frame is literally one file plus the
ranges kept visible in it, which is exactly a node's constructor arguments.

### Decisions to settle here

1. **Frame identity and linkage**: `id` / `parent` / the call site. Does a frame need more than one parent link
   (a callee called from two places)? Phase 1's wide fixture answers it.

   **Two findings from Arch C that change the shape of the answer.** First, **the call site needs a column
   extent, not only a row**: Arch C boxes the invoking *token*, and `_pricing.Handle(order, verdict)` and
   `_audit.Handle(order, priced)` can share a line. `CodeTreeEdge.callToken` carries the text today (matched
   against the line, which is enough for a text-anchored fixture); the schema should carry whatever the agent can
   author — the token text is a §4.4-rule-2-compatible locator, a column pair is not. Second, **a nested child
   has exactly one parent by construction** — a frame is in one place. `CodeNestView` keeps the first edge for a
   child and drops the rest with a warning, so a callee called from two sites is either two frames or one frame
   shown at top level. The schema must not promise what containment cannot render.
2. **Two frames on one file.** Caller and callee in the same file are two nodes over one document — legal per
   constraint 2, and the same-file fixture exists to confirm it reads. The schema must therefore key frames by
   `id`, **never** by `file`.
3. **`keep` ranges and roles**: `context` vs `focus`, and whether roles belong on the frame's ranges, on the
   step, or both. **§1a⁵ 4 depends on this** — "the excerpt of interest" is the focus range, and Phase 1 has to
   guess it from range order until the schema says it.
4. **Steps referencing frames**: one frame per step, or a list. Phase 1 decides whether multi-frame is
   legible; the schema should permit it either way, since the renderer cost is small and the authoring cost is
   a doc sentence.
5. **View mode**: `"view": "editor" | "tree"`, so one plan can drive either surface and the existing in-place
   spotlight is preserved rather than replaced.
6. **Agent-supplied context lines.** Class/method header lines must be listed by the agent as `context`
   ranges. Deriving enclosing declarations needs PSI or a backend structure query, and §4.4 rule 2 committed
   this project to text-search-only locators for Rider parity. This is the one thing in the sketch that looks
   free and is not — it belongs in the authoring guidance, not the code.
7. **Caps become authoring rules** (MoreUI.md §2.4): max depth, max frames, max ranges — from Phase 1's
   constraint list, stated in `plan-schema.md` in the same commit as the renderer that enforces them.
8. **A soft budget on lines visible per step.** The real quantity a reader can take is not "lines per node" but
   **total lines on screen for one step**, across every node. That is an authoring rule (the agent chooses
   `keep` ranges) enforced by a renderer cap, and it is what makes the 600 px node cap a starting point rather
   than the answer — three capped nodes still add up. Phase 3 owns the enforcement, this phase owns stating the
   budget.

### Work items

- `Protocol.kt` rewritten (`Frame`, `Step`, `LineRange`, `Plan`); `proto/` fixture types deleted
- **`SpotlightRenderer`'s API generalised** from `Segment`/`Highlight` to (line ranges, roles, `Palette`) —
  the 1a deferral, now unblocked because the schema exists
- `WalkthroughStorage` — `frames[].file` joins the relative-path rewrite (§4.4 rule 6)
- `plan-schema.md` rewritten as the single source of truth; `segments.md`, `explain-at-cursor.md`,
  `walkthrough.md`, `SKILL.md` updated to *point*, not restate; `manifest.txt` + `VERSION` bumped (MoreUI.md
  §2.1's four-file rule — a skill change that misses either does not reach users)
- `ClaudeExplainer.buildPrompt` inline fallback updated
- One hand-written plan per view mode, POSTed to the running server, as the acceptance test

**Gate G2:** an agent-authored plan for a real call chain in a real solution renders correctly in both view
modes.

**Estimate: 2–3 days.**

---

## Phase 3 — Narration and attention *(approximate)*

Where the correction above gets implemented.

- Flatten `Walkthrough` position state to a single `stepIndex`; `NarrationController`'s two-level `startAt` /
  `advanceAfterPlayback` / `userAdvanceInternal` / `gotoHighlightInternal` collapse accordingly. Bookkeeping
  only.
- **Two levels of attention**, which is new: the active **node** among N, and the active **range inside** it.
  The in-place editor only ever had the second.
- **Node-level de-emphasis must not touch `foregroundColor`** (constraint 1). Background tint, border contrast
  or header cue only.
- **Range-level attention inside the active node**: active glow, primary callout, token flash on
  `playback_started` (with the existing staleness check — audio and decoration are in different processes).
- **Scroll policy, now per node.** Each node may hold its own scroll offset; a step must reveal its active
  ranges in *its* node, and the container must reveal that node. A reader may scroll a node freely, and
  **playback resuming re-asserts the authored viewport** — the default to implement.

  **Constraint 19 is a precondition for the whole bullet, and §1a⁵ 4 is what supplies it.** A node that grows to
  content has no viewport smaller than its content, so it cannot be scrolled and "reveal the active range in its
  node" is not expressible — measured in the 1a⁗ round, where no nested editor had a scrollbar at all. Sizing a
  nested child to its excerpt is therefore not only a landing fix: it is what makes per-node attention possible.
  Where a node genuinely does grow, this phase must say what reveal means there (probably: nothing, and the
  container's reveal of the node carries it).
- **Multi-callout — wanted, and deliberately deferred past Phase 3 unless it is cheap here.** Phase 1 judged
  the shape: one **Primary** (brightest, keeps the scroll and the flash) plus visibly secondary **Helper**
  callouts, one per other active node, capped. A Primary is not inherently mandatory — a step may have none —
  but becomes mandatory once the callout carries anything interactive, which is the reason to keep the
  distinction in the renderer's shape even while shipping one box.
- **Enforce the per-step line budget** (Phase 2 decision 8). Three 600 px nodes are still 1800 px of code for
  one sentence of narration; the cap that matters is the total, and it is a step-level decision because which
  nodes are visible changes per step.
- Sidebar outline becomes a **tree** rather than a two-level list; the rebuild fingerprint
  (`sidebar.js:818-821`) must cover the new fields or stale rows survive a plan change.

**Estimate: 3–4 days.**

## Phase 4 — Integration *(approximate)*

- Saved walkthroughs (`.walkthroughs/*.json`) round-trip the new schema in both view modes
- **Parts of `CodeNodeStyle` and `NestStyle` become user settings** — asked for explicitly twice ("leader type and
  header type should be configurable"), and the fields already exist. **The headline setting is the leader mode:
  `CORNERS` / `BRACE` / `ARROW`, one choice with three values** (§1a⁵ 1), because it is the one the reader asked
  to be able to pick. Beside it: path display, de-emphasis, elision style, `maxHeight`/grow, strip height,
  `growHosts`. Ship the 1c verdicts as the defaults (`CORNERS` / `PROJECT_RELATIVE` / `TINT` / `OPENABLE` /
  capped 600) so Reset-to-defaults restores what was actually judged.

  **The list of knobs has shrunk twice, and both times because a decision replaced a preference.** Worth stating,
  because the instinct on this item has been to expose everything that happened to be a field:

  - **Chrome is not a setting at all.** It is the rule from §1a⁵ 2 — header iff top-level or a different file
    from the parent. There is nothing left to choose: `TITLED_BORDER` is scrapped and the borderless variant was
    rejected, so the two remaining values are selected by node role.
  - **The token mark is not a setting.** Constraint 16 makes the box structural — every leader mode starts at its
    corners — so it is always drawn and there is no off-switch.
  - **The nesting indent is gone**, settled at 0 with its other values reproducing a rejected defect.
  - **The leader mode is not free of the rest of the drawing** (constraint 16): it decides which border edges the
    box and the frame draw. A settings UI can present it as one clean choice precisely *because* the chrome knob
    beside it no longer exists — which is the thing that used to make the pair dangerous.

  Each decoration still gets a toggle, and the toggle goes in Reset
- Entry points: `ExplainThisAction`, the Solution-Explorer action and the saved-list all reach the tree view;
  `view` chosen by the agent or overridden by the user
- **Disposal audit, the main integration risk.** N editors per tree × trees opened and closed:
  `EditorFactory.releaseEditor` for every node, fold regions, inlays, timers, and the per-editor renderer state
  from 1a. A leaked editor holds a document and its markup. Worth an explicit open-close-repeat check against
  `EditorFactory.getAllEditors().size` rather than trusting inspection — B6's 4 → 16 → 4 is the shape of the
  answer wanted, and the nest bench's 6 → 5 across 25 rebuilds is the shape it has had so far.
- **A file deleted or edited under an open tree**: nodes point at live documents, so this is reachable in a
  way Arch A's snapshot was not. Validate-and-drop the node, keep the tree.

**Estimate: 3–4 days.**

## Phase 5 — Deferred, explicitly not in scope *(approximate)*

**Four items left this list when the architecture flipped**, and "we get this for free now" is worth knowing as
much as "we deferred this": semantic colour, live sync, goto-definition / hover, and mixed-language frames are
all simply properties of an editor over the real document. Two more were shipped rather than deferred: opening
an elision (`NodeElision.OPENABLE`) and collapsing a subtree (a nesting collapses to a stub and re-expands —
and unlike a `CustomFoldRegion` it actually re-expands, constraint 6).

Still deferred:

- **B5 / B1b choreography** — per-sentence beats over the playback clock (MoreUI.md §4.3, §5 tier (b));
  unchanged in relevance, and the tree gives it more to choreograph
- **Re-rooting the tree on a node**
- **A draggable bottom border on a nested node**, so the reader can give a subeditor more or less height. Raised
  by the reader as a consequence of §1a⁵ 4: once a nested node is capped to its excerpt and scrolls inside that,
  "how tall should this one be" becomes a question the reader has an opinion about. Cheap to reach —
  `EditorEmbeddedComponentManager.Properties` takes a `ResizePolicy` and today we pass `none()` — but it needs the
  height to survive a re-render, a theme flip and a step advance, which is state the node does not currently keep
- **Editing in a node.** Nodes are real documents, so making one writable is *possible* in a way Arch A's
  synthetic file never was. Deliberately not now: a narration surface that can silently modify the user's code
  is a different product with different risks
- **Very large trees** — N editors has a cost ceiling; if a plan asks for 20 nodes, the answer is a cap and an
  authoring rule, not lazy editor construction, until measurement says otherwise

---

## How to verify with the user — the spike-bench loop

Half of what this plan asks cannot be measured: *does a node read as a node, is a scrolled-away active range
worse than a tall node, does the callout fit.* The other half can be measured but is invisible in the UI. The
loop that has worked for both, and that later phases should keep using:

1. **Build the alternative as a knob, not a constant.** Every 1c row is a `CodeNodeStyle` field with a button
   on the bench, so switching is a click. A constant to edit costs a rebuild *and* a 4–7 minute sandbox launch
   per comparison, and nobody compares seven things at that price.

   **And put the *settled* knobs on the bench too.** 1a‴ left `chrome` off, because 1a′ had settled it — but
   nesting changed what the question meant, and the round came back with "it's a pity the chrome switch was not
   included and I could not test it". A knob is cheap; a knob missing from a round costs the whole round for that
   question. When a new container changes the geometry, every visual knob is open again whether or not its old
   verdict was sound.
2. **Put both kinds of answer in one vehicle.** The benches are interactive for the eye and write numbered
   `report*.txt` + PNGs for the record. **Number the reports** (`report.txt`, `report-1.txt`, …) so the script
   can ask for one per case and switching case after a report loses nothing — which is exactly what went wrong
   in 1a′ round one.

   **And make the competing alternatives one click apart in the same vehicle.** The Arch C bench rebuilt nested /
   flat / split in place on the same resolved fixture, which is what turned "which reads better" from an
   impression into a verdict — two tabs, or two launches, would have compared two moments as much as two
   layouts. It is also what made scrapping two of them a decision the reader could actually take.
3. **Probe the parts that are not about the IDE, before building the plugin at all.** A ~250–700 line `javac`
   Swing probe answers questions like "does a transparent overlay swallow mouse events", "does a *banded* repaint
   restore the stroke", "is z-order the mechanism" in about two seconds each. Every such probe needs a
   **control** that fails: the mouse-through check also runs the un-overridden version and asserts it *is*
   swallowed, or the PASS means nothing.

   **And read the probe's first run as a report on the probe.** The 1a⁗ probe opened 18 pass / 5 fail and all
   five failures were the probe's own — including a control that passed when it should have failed, because the
   check was matching the *chrome's* border and calling it the leader. A probe that passes first time on a
   question you did not already know the answer to is the thing to be suspicious of.

   **And do not let a green probe shorten the eye's list.** The same probe asserted that `BRACE` reached the
   frame's corners, and it did — on a shape that was not the one the reader had asked for. A geometric probe
   cannot review intent.
4. **Hand the user an ordered script and wait**, rather than launching and narrating. State per step what to
   click and what should happen, mark which steps need their judgement, and put **`write report` last** — the
   first 1a′ round produced a report taken before the font/theme steps, so its resync counters were empty.
   Tabulating it (`# | do | expect/judge`, with `[JUDGE]` and `[WATCH]` tags) is what made rounds come back with
   a verdict per row instead of an overall impression.
5. **Then read the log, not only the report** — and slice it to the current launch first, or you will re-read
   the last round's fixed defects. 1a′: `report.txt` said `5 pass, 0 fail` while two `SEVERE`s from our own code
   sat in `idea.log`; those became constraints 11–13. **A green report is not a clean run.**
6. **Prefer one round that tests two paths.** POSTing a `set_plan` before the user starts means a single theme
   flip exercises the node view *and* the shipping in-place spotlight — which is how `refresh()` finally got
   verified, and how two rounds confirmed both surfaces recolour together.
7. **Read the artifacts even when the round skipped the steps that produce them.** The 1a⁗ round stopped before
   its log grep and disposal check; both were recoverable afterwards from the six numbered reports and an
   untruncated `idea.log`, and one of them (multi-level collapse, 1677 → 1080 → 1677 px) was the round's most
   important result. A skipped step is not necessarily an unmeasured one.

A defect the user reports in prose ("changing the theme leaves dark tints; clicking a button fixes it") is
usually a precise bug report about a mechanism. That one was constraint 11.

## Operational notes

- Close any running sandbox IDE **before** `runIde`/`buildPlugin` — a live sandbox holds a Windows jar lock
  and `prepareSandbox` fails. `compileKotlin` is unaffected.
- **`gradlew runIde -PspikeFixture` opens `fixtures/TreeSpike/TreeSpike.sln` instead of this project.** Without
  it the sandbox reopens whichever solution was last used, and with the wrong project open every probe skips
  with a notification rather than lying — check `recentSolutions.xml`, or just pass the flag.
- The sandbox stops at the **Welcome screen** unless `config/options/ide.general.xml` has
  `reopenLastProject=true`, and with no project open the plugin's server never starts at all
  (`ServerStartupActivity` is project-level), so the port file never appears. Check before launching; a cold
  start is 4–7 minutes to waste. Restore the file **after** the sandbox is dead — a graceful exit rewrites
  config from memory.
- `MainWindowTitle` is not a readiness signal: it read `Welcome to JetBrains Rider` while a project frame was
  already opening behind it. Poll for `~/.claude-explainer-port`.
- A `SEVERE` slow-op from the bundled CSV plugin on file open is benign and not ours.
- `log.debug` for `#com.audioexplainer` is already enabled for `runIde`; output lands at level **FINE** in
  `.intellijPlatform/sandbox/JetbrainsAudioExplainer/RD-2026.1.4/log/idea.log`, **not** stdout.
- Verify rendering by POSTing a hand-written `set_plan` to the running server and screenshotting — but note
  that **a narrow editor legitimately suppresses decorations** (`MIN_ARROW_ROOM`, callout wrap), so a frame
  with no arrow proves nothing either way. Craft the plan so the decoration under test has room.
- **After every round, grep the sandbox log for our own failures** — one command, and in 1a′ it found two
  defects nothing else would have:
  `Select-String -Path <sandbox>/log/idea.log -Pattern "SEVERE|ERROR -"` then filter for `com.audioexplainer`
  in the following ~45 lines (the stack frames, not the header, carry the package name).
- **`idea.log` is not truncated between launches — slice it to the current one first.** Done naively after
  1a″ the grep returned **24** blocks naming our package, every one of them the two `SEVERE`s that were fixed
  in 1a′ hours earlier. Find the first line matching this launch's start time (the JVM logs `JVM options:`
  early) and read only from there. Reading a stale defect as a live one is a cheap way to spend an afternoon.
- **…but it *is* truncated in place when it grows, with no backup kept — so read it while the round is fresh.**
  During 1a‴ the file went from 45 360 lines to 444, **in place**, keeping only the last few minutes; there is no
  `idea.log.1` in this sandbox. Grep the log **immediately after each report**, not once at the end of a long
  session — and if the window is already lost, say so rather than reporting the surviving tail as if it covered
  the round.
- **A `SEVERE` naming our plugin is not necessarily ours.** 1a‴ startup logged
  `JBCefApp$Holder <clinit> requests ProxyMigrationService … Plugin to blame: Explain Code Audio`, with a stack
  containing **no frame of ours** — the platform's own class-initialisation order, attributed to whichever plugin
  first touched JCEF. It appears only when a `set_plan` is POSTed *during* startup (~8 s in); POST after the IDE
  settles and it does not fire. Read the stack before believing the blame line.
- **`com.audioexplainer.tree` and `spike/` were still untracked as of the cleanup pass.** Commit them before the
  next destructive change: this pass deleted files that turned out to have no git copy, and one of them
  (`lineOfAnchor`) had to be recovered from `javap -c` on the previous build's bytecode.
- The spike package (`com.audioexplainer.spike`, `spikes/`) stays as the fixture harness through Phase 1, then
  is deleted with `proto/`. `com.audioexplainer.tree` is **production** and stays.

---

## Summary

| Phase | Content | Gate | Days |
|---|---|---|---|
| **0** ✅ | Arch A spikes S1–S6 | **PASSED**; also decided Arch B | done |
| **0b** ✅ | C# fixture solution; Arch B spikes B1–B6 | **PASSED**; B3b retracted, B4 half | done |
| **1a** ✅ | Per-editor renderer split (`SpotlightRenderer` / `EditorHighlighter`) | in-place walkthrough unchanged | done |
| **1a′** ✅ | `CodeNodeView` + interactive bench; 5 of 7 alternatives judged; 3 defects fixed | folds survive, colour holds, resync fires, 6 → 6 editors | done |
| **1a″** ✅ | `CodeTreeView`: depth layout, overlay, connectors, coalesced re-render | 38 pass / 0 fail, **0 SEVERE** — then deleted, its lesson kept as constraint 7 | done |
| **1a‴** ✅ | `CodeNestView`: Arch C nesting, 3 layouts on one fixture | **G1c PASSED** — 65 pass / 1 fail; 1c layout rows retired | done |
| **1a⁗** ✅ | Polish: chrome×leader, padded box, one affordance, landing, multi-level collapse fix; probe 23/0 | **6 accepted, 4 back, 2 rejected**; 111 pass / 1 fail (the check's own) / 0 SEVERE | done |
| **cleanup** ✅ | Flat+split layouts, rejected knobs, 3 retired benches and 3 probe spikes deleted; docs compressed | `compileKotlin` clean, every `plugin.xml` registration re-checked | done |
| **1** | **§1a⁵** — three specified leader modes as one outline (`CORNERS`/`BRACE`/`ARROW`), `TITLED_BORDER` scrapped, header-by-rule, scroll-in-capped-node landing, 1 check fix + fixture | **G1c′** restated per mode; the log grep and editor count already banked | 1–1.5 |
| **2** | `frames` + `steps` schema; renderer API generalised; docs, skill four-file update | agent plan renders in both modes | 2–3 |
| **3** | Flatten position state; two-level attention; per-node scroll; multi-callout; sidebar tree | — | 3–4 |
| **4** | Storage, settings, entry points, N-editor disposal audit, live-document invalidation | — | 3–4 |
| **5** | Deferred list (four items retired by the flip) | — | — |
| | | **Remaining** | **≈ 10–14** |

The extra days over the original ≈11–15 estimate buy real semantic highlighting, live documents,
independently scrollable nodes, native goto/hover, and mixed-language trees — four of which were previously on
the deferred list.
