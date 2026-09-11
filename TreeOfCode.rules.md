# Tree of Code — the rules that outlive the plan

**What this is.** The tree-of-code viewer is built and judged; `TreeOfCode.md` (the phase plan) and
`TreeOfCode.completed.md` (the per-phase ledger) were **deleted in the pre-commit cleanup**, and this file is
what was load-bearing in them. It is not a history: it is the set of rules, numbers and verdicts that
something else still depends on.

**Read this before changing the tree.** Production KDoc cites **`constraint 1`–`23`** and **`decision 1`–`11`**
by number — 132 constraint citations and 17 decision citations across 18 files. The numbers here are those
numbers. **They are never renumbered** — a constraint that stops applying says so and keeps its number.

| If you are | Read |
|---|---|
| changing how a node, a leader or a link is drawn | *Constraints*, then *The link system's shape* |
| changing the schema or the loader | *Decisions*, then *The caps* — and `src/main/resources/claude-skill/docs/plan-schema.md`, which is the agent-facing copy |
| deciding whether a cap can move | *The caps* — every row says whether its number was measured or judged |
| reaching for a platform API | *Platform facts* — what the compiler cannot tell you |
| about to run a judged round | *How to verify with the user*, then *Operational notes* |
| about to cut a release | *What ships, and what must come out before a release* |
| wondering why something is not built | *Deferred, explicitly not in scope* and *Retired* |

**The labels in the prose below name design passes and judged rounds, not sections of a document.** They are
kept because the rules were argued in those terms and renaming them would make this text disagree with the
KDoc that cites the same rules:

| Label | What it was |
|---|---|
| **§1a′ … §1a⁵** | Phase 1's five design passes — the node view, the flat tree (deleted), Arch C nesting, the nesting polish, and the leader specification the reader wrote to the pixel |
| **§1b** | The file-driven fixture: `frames` + `steps` loaded from JSON, judged against the hard-coded case, then deleted. Its two surviving requirements are *nothing resolves silently* and *structure separate from timeline* |
| **§1c** | The per-alternative verdicts — the table whose surviving rows became the eight user settings |
| **G1c, G1c′ … G1c⁷, G2** | Named gates and the judged rounds that met them |
| **Arch A / B / C** | One stitched story document / one editor per node / one editor per node **nested in its parent** — C is what is built, and A and B are recorded under *Retired* |

---

## Constraints

Every item is measured, not assumed. These are the rules Phases 3–4 still have to build inside. **The numbers are
referenced from code comments, so they do not get renumbered** — a constraint that stops applying says so and
keeps its number.

**Rendering**

1. **De-emphasis must act on `backgroundColor`, never `foregroundColor`.** The dim roles sit above the syntax
   layer, so a foreground dim flattens every dimmed row to one grey — which is what made the Phase 0
   prototype read as plain text. Acceptable for context lines *around* a segment in the in-place editor;
   **not** acceptable for whole non-active nodes, which are the tree's structure. Hard constraint on Phase 3.

   **And every tier is a background, including the one that says "not this frame at all".** A step advance
   always moves the visual attention point — glow, docked callout, token flash, scroll-to-reveal and
   overview-ruler mark all move on every step, and flattening the two-level position state into one `stepIndex`
   was only ever index bookkeeping. The distinction *"this whole frame is not what is being talked about"* vs
   *"this line is not the focus"* is carried by three background **tiers** rather than by a fifth line role:
   primary glow, `HELPER_GLOW_MIX` Helper glow with its own softened callout, and a whole-node wash for a frame
   the step does not touch. `SpotlightRenderer` has the detail at the code.
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

   Arch C's `LeaderStrip` is this rule one level down and the only form of it that ships; the `JLayeredPane`
   overlay the flat tree used is deleted. **An overlapping sibling must be `isOpaque = false` and must override
   `contains()` to claim only its own band** — Swing routes an event to the topmost component containing the
   point, so the default `contains` silently eats every click and wheel notch in the rows it covers, which reads
   as a dead editor rather than as something in the way. Measured both ways, twice. §1a⁵ took the overlap to zero
   so the override is gone and the rule is not; `LeaderStrip`'s KDoc carries the standing instruction to
   re-introduce it the moment the strip covers anything again.
8. **`maxHeight` = 600 px is a *ceiling*, and it is a boundary rather than a limit.** Measured on the chain:
   folded **484 / 550 / 585–612 px** against unfolded 2464–3740, so exactly one of three nodes crosses it. A
   220 px cap was so aggressive that all 12 nodes exceeded it and grow-vs-scroll never got compared; 600 lets
   both behaviours appear in one screen. Capped beat grow by eye.

   **Three numbers now, and the order is settled** (§1a⁵ 4): a nested child's height is
   **`min(excerpt, content, cap)`, with no floor.** The *target* is the excerpt of interest, the *ceiling* is this
   600, and the *content* is in the min because a target taller than the folded document would leave blank rows
   below the last line. No floor is the reader's decision: a 6-line member gets six lines. `content` is what makes
   this safe on a short file; the ceiling is what makes it safe on a 200-line member. The **root is exempt** and
   keeps `min(content, cap)`.

   **And a fourth number, from the G1c′ round: an embedded child is not the node's own code.** *"Subhosts with
   subhosts should be by default sized to their excerpt size + full sizes of subnodes."* So a host's target is its
   member **plus the frames nested inside that member at full height**, and its ceiling is **600 + everything
   embedded in it**. The 600 was measured on folded *code*; counting a 500 px child against it is what produced the
   two-lines-of-caller-behind-a-scrollbar picture the `growHosts` knob existed to escape — and with the ceiling
   corrected, a **nested** host lands on its excerpt like any other nested node.

   **The root is exempt from that correction too, and the distinction is the one thing the round did not say.**
   Capping a top-level host below its content gives it an *inner* scrollbar inside the outer pane — two scrollbars
   for one node — so `growHosts` still grows the root and reaches nothing else.

   **A fifth number (G1c″): the step's callout is not code either**, so target and ceiling both add
   `calloutAllowance()` while a callout is docked. The ceiling still means "as much code as a reader takes in at
   once"; what keeps changing is what stops counting as code.

   **And one number that is not a rule: the reader's own.** A dragged height (`CodeNodeView.heightOverride`)
   replaces the whole `min`, including `grow`, clamped only by the content. It sits outside the ordering rather
   than inside it — a rule that could overrule a drag would make the splitter read as broken.
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
    is the mechanism, and the N requests are coalesced onto one dispatch (`requests=4 dispatches=1` at N=4).
    There used to be a second half — the in-place surface's own scheme subscription — and deleting it exposed
    a third path nobody had counted: **Settings ▸ Apply** re-styled a live walkthrough by finding
    `EditorHighlighter`s in the listener list, so with that class gone the filter would have matched nothing
    and Apply would have compiled and done nothing. `TreePlanController.refresh()` is where that lands now.
    A per-render derivation means re-rendering *is* re-styling — but only if something still asks.
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
    - **A frame's top EDGE and its top CORNERS are two questions.** They were one boolean for four rounds, on
      the reasoning that a frame drawing no top edge has no top corner to round. That held only while `BRACE`
      kept its own turn inside the leader strip — and keeping it there is what forced the horizontal run one
      radius **above** the header, which is the gap K3 saw a relation link cross. The run is the leader's; the
      turn into the side wall is the frame's; `CORNERS` wants neither, because its diagonals *land on* those
      corners and a radius there would open a gap where one arrives.
    - **The leader is structural, so "no leader" is not a coherent option** — it would leave a bottomless box
      above a topless frame with nothing joining them. Same for the box: a leader that starts at "the box's
      bottom corners" needs a box. This is what removes `NestLeader.NONE` and the whole `TokenMark` choice
      (§1a⁵ 4); a genuine minimal mode would have to be a *fourth* leader mode that restores both borders and
      draws nothing between them, not an independent off-switch on either half.

    **What this dissolved**, recorded because the lesson keeps its number: scrapping the one chrome that drew its
    line at a caption's mid-height took the whole drawn-line-offset apparatus with it, and every surviving chrome
    draws at its bounds edge, so the offset is 0 by construction rather than by agreement. **Two drawings that must
    meet need one definition of where** — which is what `LeaderArt` exists to enforce, and sharing *one* number was
    not enough when a second (the corner radius) was not shared.

    **What survives unchanged: leader mode and chrome are one design, not two independent knobs.** The coupling
    just moved — it used to be "does the leader reach the drawn line", it is now "which border segments does the
    leader own". Phase 4 still must not offer them as two free choices.

    Sending the strip to the back is the measured control: the child's opaque background erases the leader's
    tips entirely.

    **The seam is closed at zero overlap, and it was a probe question that had a measurable answer.** The §1a⁵
    pre-flight probe (`spikes/tree1a5/OutlineProbe.java`, **deleted in the post-G1c⁗ cleanup — the measurement is
    banked as a number rather than a file**) labelled the outline's ink into 8-connected components: the box, the leader and
    the frame are **one** component in every mode. So the leader's last row at `height − 1` and the frame's wall at
    its own row 0 are *adjacent*, and adjacency is continuity — the 1 px stagger needed nothing to absorb it. Two
    consequences: the strip covers none of the child's rows, so constraint 7's `contains()` cost is gone (the rule is
    not, see there); and **the whole outline needs one row constant**,
    `boxBottomRow == callRowY + lineHeight == the strip's row 0`, which is the origin every one of the three shapes
    measures from. That is the *only* place a 1 px disagreement is visible — on a vertical wall it reads as
    adjacency, in `ARROW`'s collinear horizontal extension it reads as a kink.

    **`ARROW` used to be two components and the G1c′ round made it one**, which is worth recording because the
    earlier reading was that the split *was* the distinction: "a reference, not a join" was measured as the frame
    being its own component, and the round measured the same 4 px as *"arrow does not reach header top."* An
    arrowhead floating short of the line it points at reads as misdrawn. What actually distinguishes `ARROW` is the
    thing above — **both outlines stay closed** — so connectivity no longer discriminates it and the suppressed-edge
    count does. A probe assertion can be a correct measurement of the wrong claim.

    **The origin is two coordinates, and only one of them was shared.** The row constant above held; the *column*
    did not. The box is inflated by constraint 18's 2 px of padding, and the leader was started from the token's
    **glyph** x — so it left the outline 2 px inside its own wall, which the round saw immediately: *"diagonals top
    don't meet invoker box ends."* Both coordinates now come from one call (`LeaderArt.boxEdges`), and the probe
    checks it with the glyph-origin control that must fail. Note where this had already been written down: constraint
    18 says "the box's *padded* bottom corners, not the glyphs'", and the pre-flight probe padded its endpoints
    correctly. **The specification was right and the transcription lost it** — which is the argument for the probe
    and the code sharing a definition rather than agreeing on one.

    **The row below the box belongs to the nested frame**, so `ARROW`'s box is four-sided only if a
    `CustomHighlighterRenderer` may paint there. It may: `LeaderArt.closesTokenBox` and `drawsFrameTop` are both
    `leader == ARROW`, and that is what ships.
17. **Height propagation up a nesting chain costs one deferred pass per level.** A nested child's re-measure
    reaches its *host's* inlay only through the host node's geometry callback, which coalesces onto an
    `invokeLater` — so at depth 2 the outer inlay is one EDT turn behind. It was a real failure first
    (collapse-then-expand restored 1741 → 650 → **1186** px, short by exactly the inner frame's 602 − 47) and
    is now closed with a number: a synchronous innermost-first walk up the chain measures
    **1677 → 1080 → 1677 px, exact at two levels**. Either walk the chain synchronously or state the settle
    time — do not assume one pass. **And this recurs in every new check**, not once: it caught the mouse-reach
    check next.
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
    expresses: 2 px of padding on three sides, and the fourth side is where the leader takes over. The
    vertical padding still matters on the surviving bottom corners, since that is where the leader's origin sits
    — the box's *padded* bottom corners, not the glyphs'.

    **One number moved when the outline became one path**: the box's height is now `lineHeight`, not
    `lineHeight − 1`, so its bottom-edge row *is* the strip's row 0 (constraint 16's row constant). That grew the
    box **away** from the text — the extra row belongs to the nested frame, not to a glyph row — so the vertical
    clearance measured here is unaffected, and the horizontal cost above is unchanged.
19. **A nested node does not scroll at its natural size, so nothing can be revealed by scrolling it.** Measured
    by the reader: "nested subeditor does not have scrollbar, innermost subeditor only gets one if I expand some
    collapsed block" — and when one does appear, the wheel reaches it and "scrolling feels natural and ok". Not
    a defect; a consequence of sizing. A host grew to content (`growHosts`, on by default then) and a leaf's folded
    content measures 484–612 px against a 600 px cap, so there was nothing to scroll in either. **Both halves are
    closed now**: §1a⁵ 4's excerpt target caps the leaf, and the G1c′ round's host sizing (constraint 8's fourth
    number) caps the host — so every nested node has a viewport, including the ones that host other nodes.

    It binds everything that wants to *move* a nested node's viewport — the landing (§1a⁵ 4), Phase 3's per-node
    scroll policy, and "playback resuming re-asserts the authored viewport". **A reveal is only expressible if
    the node is capped below its content**, so a cap is not merely a size limit: it is what creates the viewport
    that attention can be moved inside. Choosing "grow" for a node silently switches its half of Phase 3's
    scroll policy off.
20. **A scroll in this surface is never a one-shot, because every scroll target is computed from a height that
    other rules are still changing.** `scrollVertically` clamps to `content − viewport`, and in this surface both
    terms keep moving *after* the scroll is issued: embedded heights refresh post-order (constraint 8's fourth
    number), the callout's allowance arrives and leaves with the step (its fifth), a drag rewrites the extent
    outright, and each level of nesting is a deferred pass behind (constraint 17). So a scroll that was correct when
    it was computed is wrong one turn later, and **nothing re-arms it**.

    Measured twice, in two different mechanisms, which is what makes it a constraint rather than a bug:

    - **The landing.** A retry bounded by *passes* when the thing that changed was the *target* reports itself as
      settled — `asked=66px offset=0px` at depth 1 of the three-deep `chain`. The **goal** has to outlive the
      passes: latch only on success, re-arm from every extent-changing trigger, and keep an attempt ceiling for the
      one genuinely unreachable case. A report must also keep **"the landing failed"** apart from **"the landing was
      superseded"** — a step's reveal moves a viewport on purpose, and `wide` step 5 was a check asserting the former
      about the latter.
    - **The reveal.** The band is measured and scrolled to, and then the callout's own wrapped height and the
      allowance resize move it again — one deferred pass, running only when something was *expanded*.
    - **The collapse (G1c⁗), the sharpest form.** A collapse does not *move* the extent, it **destroys** it: the
      frame goes to 0 px and the offset clamps to 0, and expanding returns the extent but not the offset. The latch
      that fixed the landing is what made this permanent, since a node that had already landed was never re-armed.
      Signature: `satisfied=true byReveal=false asked=66px offset=0px` — the one combination neither explanation
      covers, which is the only reason it was attributable. So the trigger set needs an event meaning *the extent
      you measured against no longer exists*, and it belongs on the way **up** only: invalidating downward leaves
      the landing owed at a 0 px viewport, burning the ceiling against an unreachable target — the same bug through
      its own fix.

    Two rules follow. **A scroll must be re-armed by the events that move its extent, exactly as a height resync is** —
    landing belongs in the same trigger set as `fold`/`inlay`/`embedded`/`drag`, not in a one-shot schedule. And **no
    scroll target may sit flush against a viewport edge**: a target placed exactly at the top edge crosses it on any
    late change, which is precisely the reader's symptom, so a reveal leaves the callout's own margin as slack.
21. **A node shrinking at the bottom of the outer pane cannot keep its dragged edge under the pointer, and no
    scroll offset can fix it.** With the pane scrolled to its maximum, shrinking the content by `d` *forces* the
    offset down by `d` and the dragged edge's absolute position falls by `d` too, so its screen position is
    unchanged by construction; keeping it under a pointer that moved needs an offset the clamp no longer allows.
    `NestStyle.bottomSlack`'s KDoc has the arithmetic and the reader's report.

    **The remedy is slack, not anchoring**: scrollable room below the last node, which is exactly what the reader
    created by hand when the symptom disappeared. It is worth having on its own terms — the last node's bottom is
    otherwise unreachable from a comfortable reading position.

    **Judged with the strut in, and the reader's statement of the limit is sharper than the arithmetic above.** From
    G1c⁗'s notes: *"there is limit on how far from the window bottom a bottom-most edge of editor can go. The scroll line
    follows the mouse, as long as you don't hit that limit … I think it's still usable for now."* Which locates the limit
    exactly — it is a property of the *bottom-most* edge, so **slack moves the limit and can never remove it**: any finite
    strut is exhausted by a long enough drag, and an infinite one would give the pane a scrollbar with nothing under it.
    So this constraint's status is **accepted residual, not open defect**: the strut stays at 240 px with `0` as its
    control, and the behaviour is a documented property of the surface. Reopen only if a reader raises it again.

22. **A border drawn on a text row has no room of its own, and the row below it belongs to the next statement.**
    G1c⁵, and it is constraint 18 one axis over — that one bought the box horizontal clearance from its own glyphs and
    explicitly *"clamped vertical inflate to the row"*, which is precisely the decision this reverses.

    A row's descenders reach the bottom of it, so an edge drawn inside the row is drawn on the text — and moving it
    down lands it on the row *after* the call row, which constraint 16 already spends: the leader strip's row 0 while
    expanded, and the next statement's own row while collapsed (in `case: wide`, the next call site). **One row,
    three claimants**, so the room has to be *made* — the frame's top inset while expanded, `2 ×` the pad as
    collapsed-frame height while folded. A collapsed frame is therefore no longer 0 px, which does not reverse
    *"hide it totally"*: that verdict is about the **child**, and the box's own margin was never the child's to give
    up. `NestStyle.boxPadBelow` has the mechanics.

    **The generalisable half:** the number is one knob (`boxPadBelow`), because a border, its clickable band and the
    separation between two of them are three requirements on one dimension, and satisfying them separately is how they
    end up disagreeing. `0` stays reachable as the control — a fix whose before-state cannot be shown is an assertion
    about itself.

23. **A re-armed scroll still needs somewhere to go, and "re-issue it" and "make it reachable" are two fixes.**
    G1c⁶, and it is the first time in this phase that an accepted fix was *correct and insufficient* rather than wrong.

    Constraint 20's re-arm shipped and works — the report shows the landing re-armed, re-issued and `attempts`
    counting up — and the node still dropped to line 1, because `scrollVertically` **clamps to `content −
    viewport`**: a member `y` px down the document can only reach the top of a viewport when `y` px of content sit
    *below* it, and collapsing a nesting inside the node removes exactly that. The landing was asking, correctly,
    for an offset that did not exist. It is the **third** surface on which the same clamp has been met
    (`NestStyle.bottomSlack` is the outer pane, 240 px of strut).

    The room is supplied per landing node **as a setting rather than a number** —
    `EditorSettings.isAdditionalPageAtBottom` — because a pixel count would have to be re-derived on every collapse
    against a height that keeps moving, which is constraint 20's own warning. `CodeNodeView.setLandingSlack` has the
    extent arithmetic and why it is emphatically not `additionalLinesCount`.

    **The generalisable half:** when a fix restores an *action*, ask separately whether the action's *precondition*
    holds — and a knob, because the room costs a page of blank space a node can be wheeled into. `nodeSlack: false`
    is the control and reproduces the defect on demand.

---

## The schema's decisions

Settled in Phase 2, and cited by number from `PlanLoader`, `Protocol`, `PlanOutline`, `Walkthrough`,
`ClaudeExplainer`, `ExplainerServer`, `ExplainRequest` and three of the tree views. The agent-facing statement
of what an author may write is `src/main/resources/claude-skill/docs/plan-schema.md`; this is why each rule
is what it is.

1. **Frame identity and linkage: `id` on the frame, `parent` + `callLine` + `callToken` on the *child*.**
   Phase 1 answered the open half of this from two directions and both answers narrow the schema rather than
   widen it.

   **The call site needs a column extent, not only a row**, so the link carries `callToken` (plus
   `callOccurrence`): Arch C boxes the invoking *token*, and `_pricing.Handle(order, verdict)` and
   `_audit.Handle(order, priced)` can share a line. The token *text* is a §4.4-rule-2-compatible locator; a
   column pair is not authorable. `callLine: 0` means "not stated" and the child renders at top level with
   `EmbedState.NO_CALL_LINE`, which is the existing degrade.

   **A nested child has exactly one parent by construction**, so the link lives on the child and there is no
   edge list at all — which is what makes a second parent *unexpressable* rather than rejected. The first sketch
   put `linkFrom` on the parent; that is superseded for the reason the wide fixture demonstrates (one
   `linkFrom` per parent cannot say which of three consecutive call lines goes with which child), and putting
   it on the child is the same information with the multiplicity the renderer actually has.

   **And the call line must itself be kept.** New, and it is the same defect as a step lighting an elided line
   one level up: a `callLine` outside the parent's `keep` puts the whole nested frame *inside a collapsed fold
   region*, where it is invisible — a silent loss, not a degraded picture. The loader degrades the child to top
   level and says so (`frame.call-line-not-kept`).
2. **Two frames on one file: frames are keyed by `id`, never by `file`.** Caller and callee in the same file
   are two nodes over one document — legal per constraint 2, and `case: same-file` exists to confirm it reads.
   Consequence carried into the projection below: a *step* names a frame id, and a frame may host many steps.
3. **`keep` ranges carry the roles; a step's ranges are active by definition.** `role: "context" | "focus"`
   on each `keep` range, default `context`. **`focus` is §1a⁵ 4's "excerpt of interest"** — the range a nested
   node is sized to and lands on — so `CodeNodeSpec.focus` stops being a guess from range order. At most one
   `focus` per frame: the first wins, the rest are reported. A frame with none keeps the Phase 1 guess (the
   second kept range) and the loader records that it guessed (`frame.focus-inferred`), because a landing the
   plan never asked for is exactly the kind of thing that reads as a renderer defect.

   A `role` on a *step's* ranges is ignored, and the doc says so: the step's ranges are what is active this
   step, and that is not a property an author can vary.
4. **Steps reference frames as a list, with sugar for the one-frame case.** Canonical is
   `frames: [{frame, ranges}]`; `frame` + `ranges` is normalised into a one-element list at load. **`frames[0]`
   is the primary frame** — no separate `primary` field, because ordering already expresses it and a second
   spelling of the same fact is a second thing to get wrong. The Primary/Helper callout split is Phase 3's, and
   this is the field it will read.
5. **`view` — deleted.** It said one plan drives either surface, keeping a second renderer alive; decision 11
   removed the reason to, round 3 judged the single frame both ways one menu click apart, and the tree won. The
   field and everything that selected it are listed under *Retired*. **Two things it cost that a compile could not
   have told us**, both found by sweeping for references before cutting rather than after: Settings ▸ Apply's live
   re-style was reached only through the deleted class and would have compiled into silence (constraint 11), and
   one agent-facing doc still *instructed* the agent to send `"view": "editor"` — **a field can be deleted from
   the schema and still be instructed somewhere else.** Saved walkthroughs written while it existed still load:
   every decoder sets `ignoreUnknownKeys`.

6. **Context lines stay the agent's job.** Class/method header rows are `context` ranges the agent lists.
   Deriving the enclosing declaration needs PSI or a backend structure query and §4.4 rule 2 committed this
   project to text-search-only locators for Rider parity; measured `firstKept` ran **9–18** across the fixture,
   so a frame whose header was inferred would land the reader on a line the plan never named. Authoring
   guidance, no code.
7. **The caps live in `plan-schema.md`**, written agent-facing and moved there in this phase's commits.
   *The caps* below is the other half — which number was measured, on which case, and which are judgements.
8. **The line budget is ~60 lines of code visible across every frame of one *scene*.** `PlanLoader.LINE_BUDGET`,
   reported as an INFO (`scene.line-budget-exceeded`). **60 → 80 → 40 → 60, and only the last two were
   measurements.** 60 came from arithmetic (an editor viewport is ~45–55 lines); 80 from the first probe run
   putting the fixture's own judged-good `chain` — 66 lines — over it. Then the tool window was actually
   looked at: **25–40 lines on one monitor (K4), 55 on another (K5)**. The tree is in a tool window beside the
   sidebar, not an editor filling a window, and it moves between screens — so the measurement is a *range*,
   and the cap belongs at the top of it. **The number came back to where the arithmetic first put it, and
   it now means something different**: not "half of two viewports" but "the largest real one". **The 66-line
   chain is still over budget, and that is the finding rather than a regression**: it always scrolled, and the
   round that liked it was judging how it rendered.

   The fold is no longer this cap's enforcement — decision 12 made it unconditional — so what the cap counts is
   the part a fold cannot reclaim: **the frames one step needs open together**. Siblings fold away one at a
   time; a nested chain does not, because keeping a child open keeps every ancestor open with it. *Depth spends
   the budget, breadth borrows it.*

#### Two decisions this phase had to take that the list above did not name

9. **`Segment` / `Highlight` stopped being the wire format**, and the `PlanProjection` bridge that carried them
   was deleted by Phase 3's flattening exactly as planned. One rule outlived all three, and it now lives on
   `PlanOutline`'s `OutlineGroup`: **a frame revisited by non-adjacent steps produces two runs, never one merged
   group.** The revisit surviving at all is why `frames` is split from `steps` (§1b's first requirement), so
   nothing downstream may be the thing that flattens it.
10. **`insert_after` / `replace_segment` / `remove_segments` are retired.** They edit a segment list that is no
    longer the source of truth — mutating the projection would leave the stored plan and the tree disagreeing —
    and nothing reaches them: no internal caller, no line in any skill doc's flow, and `buildPrompt` tells the
    agent to send `set_plan` and nothing else. A live plan is changed by POSTing a new `set_plan`, which the
    agent can do because it is holding the whole plan already. `goto` stays and takes a frame id.

11. **A plan is a sequence of *scenes*, and the tree is the only surface.** The decision round 2 asked for and
    the one that changed what a frame *is*. The reader stated the product half — *"more appropriate to create a
    separate Tree-of-Code viewer even for a single file … this way we will have a unified workflow and it will be
    simpler to maintain"* — and asked the mechanism question that decides it: *"can we actually change the set of
    loaded nodes for different steps? Eg. three steps operate on 3 files, then next 5 steps operate on different
    files … That was actually the vision."*

    **The mechanism question is answered from the platform, and it eliminates one of the three candidate models.**
    `Editor`, `EditorEx` and `EditorImpl` expose `getDocument()` and **no setter**; every `EditorFactory` factory
    method takes the document as a *creation* parameter, and `releaseEditor` is the only teardown verb (verified
    with `javap` against the RD install on the compile classpath, not recalled). So *"just switch the file in a
    single internal editor node"* **is** *"release that editor and create another"* — which is exactly what
    `CodeNestView.setTree` already does for every node. The attractive-sounding third model is the second model
    with worse bookkeeping, and there is no cheaper path behind it. Which makes the answer to the vision question
    **yes, and it is the mechanism that already exists.**

    So: `Frame.scene` (default `0`). Frames sharing a scene are one tree; a step whose scene differs from the
    mounted one **replaces the whole nest**. Four consequences, each of which pays for the change:

    - **The simple case stops needing a second renderer.** One frame is a one-node tree, so *"explain this
      method"* is expressible in the tree, which is decision 5's retirement.
    - **The line budget becomes actionable.** It was ~80 lines *per plan*, which for a twelve-file walkthrough
      had no remedy except cutting content. Per scene, the remedy is *split the scene* — and that is why raising
      the sibling cap to 6 did not need the budget raised with it.
    - **A frame's place is a property of its scene, not of the file.** `PricingHandler` is a nested child in
      `tree-links.json` and a root in `scenes-tour.json`. The fixture exercises this deliberately.
    - **A step is now the only position that matters.** `frames` stopped being "everything on screen" the moment
      a scene did, which is what makes item 2's flattening the natural next move rather than a patch.

    **What it costs, stated before the round rather than after it.** A crossing is N editors released and M
    created — the operation Phase 1 measured 25 times with clean disposal, which is the evidence it is
    affordable. The reader's own state inside a scene does **not** survive: a collapsed nesting, a dragged node
    height. A revisited scene is **rebuilt, not restored**, and pays both crossings. Nothing is cached, and that
    is deliberate — caching one nest per scene keeps N editors alive per scene, which is Phase 4's named risk
    taken on purpose to avoid a cost already measured as cheap. **Whether the rebuild reads as correct or as a
    loss is the one thing a probe cannot answer, and it is round 3's first row.**

    Two rules the loader enforces because a render-time recovery does not exist: a **`parent` must be in the same
    scene** (the parent's node is not there to embed into — `frame.parent-other-scene`, degraded to top level),
    and a **multi-frame step must stay in one scene** (`frames[0]`'s scene wins — `step.frames-cross-scene`).
    Both are the family *The caps* names below: two fields authored in separate passes with nothing connecting
    them.

12. **No step says which frames are open.** Asked outright in round K3 — *"do we actually write in steps
    whether subeditor is kept open or closed for that step?"* — and the answer is no, deliberately. A
    frame's openness is a property of the reader's screen, not of the walkthrough: it depends on the height
    of the window, on what the reader has folded by hand, and on nestings the author never mentioned. What
    the author states is which frames a step **touches**; which of the *others* are worth their height is
    the surface's judgement, and it is one setting (`NestStyle.stepFold`) rather than a field on every step
    of every plan.

    The mechanism was already there, gated on the wrong thing: the fold ran only when a scene exceeded the
    line budget (decision 8), so a walkthrough comfortably under it kept every nesting open through every
    step. **The budget was never the reason a nesting the step ignores is worth its height** — it only
    decided whether anything was done about it. Ships as `ALWAYS`, with `OVER_BUDGET` kept as the control
    that says whether the budget was doing anything on its own.

---

## The caps

The agent-facing copy lives in `plan-schema.md`, moved there by Phase 2 decision 7 in the same commit as the
loader that reports them. **This is the other half: which number was measured, on which case, and which are
judgements.** When a cap changes, both change together, and this is what says whether the new number was
measured or decided. Numbers are transcribed from the G1c⁷ round's `chain` and `wide` runs at
`leader=CORNERS`, `pad: 3`; those artifacts were never committed, so **this table is the only citable copy**.

**A cap is only a rule if there is a defined behaviour on the other side of it**, which is the column that is
easy to leave unwritten.

| Rule | Number | Enforced by | Past the cap |
|---|---|---|---|
| **Nesting depth** | **3** (a root plus two levels) — *a judgement* | **The loader** re-roots it and warns (`frame.depth-exceeded`); the renderer then **degrades, not refuses** | The over-deep frame renders **at top level instead of nested**, and says so — the same answer already given to a child whose edge names no call-site line (`EmbedState.NO_CALL_LINE`), on the same grounds: *degrading to the flat shape is legible and dropping the node is not* |
| **Siblings in one host** | **6** *(was 3; a judgement)* | **The loader**, as an INFO (`plan.siblings-exceeded`) | A taller host, nothing worse — siblings are **additive, not nested**, at 24–26 px per collapsed box. 3 was measured and judged on screen in G2 round 2 (*"all siblings read as siblings cleanly … we can safely increase the cap to 5–7"*) and 6 is the middle of the range the reader named. **Nothing has been rendered above 3**, so the row is an extrapolation from a judged case |
| **`keep` ranges per frame** | **3** | **The loader**, as an INFO (`frame.keep-exceeded`) | A taller node. Elision is per-gap (`3/3 collapsed, 0 lost` on every node of every case), so the mechanism does not care. **A tested limit, not a believed one — nothing has run above 3** |
| **Parents per frame** | **exactly 1** | **The schema** | `parent` is one nullable field on the child (`Protocol.kt`), so a second parent is **unexpressable rather than rejected** — there is no edge list and nothing to drop. This is where Phase 2 decision 1 arrived from the other direction. What the loader does police is a parent that is missing, self-referential, cyclic or in another scene |
| **A step's ranges inside its frame's `keep`** | **required** | **The loader** | The step lights a **fold placeholder** — it renders, pointing at code the node elided, and reads as the renderer having lost the highlight. There is no render-time recovery (growing `keep` overrides the author's own elision; dropping the step loses narration), so **the plan is wrong** and is checked. The two are **authored in different passes with nothing connecting them**, which is why a schema doc has to warn about it |
| **Cycles** | **forbidden** | **The loader** | `addComponent` would refuse whichever embedding arrived second, landing it as `EmbedState.FAILED` **by edge order**. The re-measure walks are cycle-guarded so the EDT will not hang, but nothing in the renderer rejects one: **a schema that cannot express a cycle is worth more than a renderer that survives one** |
| **Relations per step** | **5** — `LinkArt.SLOTS`, one per routing lane | **The loader** | The ones past the fifth are **not drawn at all**, `[warn] relation.step-exceeded`. Links leaving *one* line cost that line room; links leaving different lines cost nothing, so the remedy is spreading them over more steps |
| **Frames per scene** | the rows above | as those rows | A scene *is* one tree, so it inherits the tree's caps exactly. Inventing a separate number would be a cap with no measurement behind it |
| **Scenes per plan** | **no cap** | — | Each crossing is one nest rebuild, and **25 consecutive rebuilds measured clean disposal** in the 1a‴ bench. Nothing suggests a ceiling below that, so none is stated |
| **`parent` in another scene** | **forbidden** | **The loader** | The parent's node does not exist while the child shows — no editor to embed in, no call line to sit under. Cut the link, render top-level, `[warn] frame.parent-other-scene` |
| **A step spanning scenes** | **forbidden** | **The loader** | Only one scene is mounted, so off-scene references ask for glow inside nodes that do not exist — silently unpainted rather than visibly degraded. `frames[0]`'s scene wins, strays dropped, `[warn] step.frames-cross-scene` |

**Depth is the binding cap, and it is the one number that is a judgement.** Depth compounds where nothing else
does: a node's height is its own excerpt **plus its children at full height**. The measured `chain` case is
`MainLoop` **1584 px** → `MidInvoker.InvokeOn` 954 → `SubInvoker.Apply` 510 — so at depth 3 the innermost
510 px of readable code already sits inside 1584 px of container. A fourth level puts a few hundred px of code
inside roughly two thousand and the reader is scrolling a container to find a container. **Depth 3 is proven and
depth 4 has never been run**; the argument is the compounding, not a failed run, and the thing to measure first
if a fourth level is ever wanted is not whether it renders — it will — but whether the outermost frame is
still navigable.

**What is *not* an authoring rule, and stating it is half the deliverable.**

- **Node height is the renderer's, entirely.** A nested child is `min(excerpt, content, 600 + embedded +
  callout)` with no floor; a top-level host still grows; a height the reader drags beats all of it. The
  measurement that matters: **the 600 px cap does not bind once a node hosts a child** — `content` ran
  550–1557 px against it and `published` 487–1584 px, the excerpt target dominating at every level. So an
  agent must not budget lines against a pixel ceiling it is not subject to. **Depth and ranges are the caps a
  plan controls.**
- **Fold state is never in the schema.** Each node carries **13–25 fold regions the daemon added and the
  plugin did not**. The plan says which lines to *keep*; what else is folded is not ours to state, which is why
  elision has to stay additive-tolerant.
- **Lines on screen is a real budget: ~80 per scene.** Phase 1 could only establish that the quantity binds —
  the tallest tree measured is one 1584 px root, already more than a screenful before a second exists. **How 80
  was set is the part worth keeping:** it started at 60 from arithmetic, and the first probe run put the
  fixture's own judged-good three-deep `chain` (26 + 26 + 14 = **66**) over budget. A cap that fires on the one
  configuration a human has already approved is mis-calibrated whatever the arithmetic says. So 80 — above
  what has been read and liked, below two viewports. **Per *scene*, not per plan**, which is what makes it
  actionable: before scenes, *"you are over budget"* had no remedy but keeping less code; now it has one that
  costs the reader nothing, which is split the scene.

**The scene model earns its cost as a cap, not a feature** (what a crossing costs is decision 11): without scenes a walkthrough over twelve files is twelve nodes and ~150
kept lines at once — over every budget here with no remedy that does not cut content. With scenes it is four
scenes of three, each inside all of them.

**Two requirements that are not caps** are stated with their reasons above: context lines must be explicit
(decision 6) and a call site needs a token rather than only a row (decision 1).

---

## Platform facts

Established before Phase 0 by indexing all 1067 jars in the Rider install, extracting the registration XML and
running `javap` for exact signatures — the evidence that made the whole tree worth attempting. RD-261.26222.60.

**Most of that table has since been deleted from this file, and the reason is the point**: every signature the
tree actually uses is now called from production code, so `compileKotlin` re-verifies it on every build and a
prose copy can only drift. What is left is the three facts a compiler cannot give you.

| Question | Answer |
|---|---|
| **Frontend C# colour with no ReSharper backend?** | **Yes.** `product.jar :: intellij.rider.languages.xml` registers `CSharpFileType` and a `lang.syntaxHighlighterFactory` for `C#`; the lexer classes sit in the same jar. Lexer colour is guaranteed by registration — *semantic* colour is the backend's, and constraint 3 is where that landed. Nothing in our code asserts this; it is a property of the install |
| Making an editor read-only | `EditorFactory.createViewer(...)`. **Never `Document.setReadOnly`** — the document is the user's own file, and that would block them editing it. The only prohibition in this section, and the compiler is happy to let you get it wrong |
| Prior art worth reading | `com.intellij.diff.tools.util.FoldingModelSupport` (the diff viewer's collapse-unchanged-lines), `ImplementationViewComponent` (Quick Definition — a real editor over a real file in a popup), `UsageInfo2UsageAdapter` (coloured one-line excerpts in a tree) |

The embedded-component API Arch C is built on is **constraint 15**, which additionally carries the runtime
measurement no signature could give (`addComponent` accepts a *viewer* editor; `offsetToXY` counts the block
inlay, 528 → 1741 px).

---

## The link system's shape

Eleven judged rounds settled this and every one was accepted; what stays is the shape, because everything
that draws a link depends on it. A link is two independent choices and all four combinations are one drawing:

| | `LinkJoin.LINE` — same editor | `LinkJoin.PORTAL` — different editors |
|---|---|---|
| `LinkAnchor.TOKEN` | tint, **stem**, rail, lane, rail, **stem**, tint | tint, **stem**, rail, **portal** |
| `LinkAnchor.LINE_END` | text end, row middle, lane, row middle, text end | text end, row middle, `∩`/`∪`, rail, **portal** |

Three rules make it derivable rather than authored: the **anchor** per *end*, by whether that end's `token`
resolves; the **join** by the reporting renderer (`LinkRequest.local`), the only thing holding the document;
the **lane** once per step by the tree (`TreePlanView.paintLinkPairs`), across every editor on screen.
`Relation.kind` is the one thing an author chooses: `backforth` arrives at **both** ends, `flow` at one.

A click on either end navigates to the *other* end (`LinkHalf.target`) via `TreePlanController.followRelation`.
**`mousePressed`, not `mouseClicked`** — the caret has already moved by the time a click arrives. Hit-testing is
recomputed from `LinkShape.points` on every mouse move, so it cannot go stale against a scroll or drift from
the ink.

**Two questions no bench can reach and no round has asked.** *Does a link survive a fold* — fold a range under
a link on `tree-links.json` step 7; the log's `D of A end(s) drawn` says whether an end was dropped, and a
dropped end must degrade to a lone approach rather than a leg to nowhere. And *does a click reach the link at
all* — the handler consumes `mousePressed`, so whether the editor still dispatches a click is unknown.

---

## The C# fixture — `fixtures/TreeSpike/`

Built in P0b.0, and every Phase 1 round ran on it: three projects (`Fixture.App` → `Fixture.Processing` →
`Fixture.Core`), net8.0, builds clean and runs. Three cases — a 3-deep chain across 3 files, two nodes in one
file, and one parent calling three children from consecutive lines.

**`fixtures/TreeSpike/README.md` is the copy to read and the copy to maintain**: it holds the anchor table, the
deliberate properties (the gaps a node must elide, `GenerateDocumentationFile` off so 82 CS1591 warnings do not
cover the fixture in squiggles, short handler files against long chain files) and — the reason it is the
authoritative one — the warning that **line numbers are load-bearing and the table must be regenerated when a
file is edited.** A second copy here could only drift out of that.

---

## What ships, and what must come out before a release

Not a Phase 2 item — a standing list, because the answer is not obvious from the file tree and the reader has
asked for the split.

| | Verdict |
|---|---|
| `tree/` entire (`TreePlanView`, `TreePlanEditor`, `CodeNodeView`, `CodeNestView`, `LeaderArt`, `TreeStyle`), plus `PlanLoader`, `PlanOutline`, `Protocol`, `LinkArt`, `SpotlightRenderer`, `TreePlanController`, `Walkthrough` | **production.** `PlanProjection` was here and is gone — Phase 3 deleted the bridge, as decision 9 said it would |
| `fixtures/plans/*.json`, `fixtures/TreeSpike/` | **repo, not the jar.** Acceptance plans and the C# fixture they read; Phases 3 and 4 both need rounds, and a plan that has never been loaded is not an acceptance plan |
| `probes/` — four `javac` probes, two Python gates | **repo, not the jar.** There is no test source set; these *are* the tests, and they import the production classes rather than transcribing them, which is what the deleted `spikes/` could not do |
| **`spike/FixturePlanActions.kt` + the `Fixture plans (dev)` menu in `plugin.xml`** | **must come out before a release.** Ten dev actions — seven fixture plans, next/previous and clear; `plugin.xml` carries the note at the group. Kept because a judged round has no other vehicle — delete the package and the group together when the tree needs no more rounds |
| `spikes/`, the Phase 1 bench cluster, `EditorHighlighter` | **gone**, and the deletions are part of the pending commit |

---

## Retired, and recorded so nobody re-opens them

- **The "switch the file in one node" model.** Worth recording as *removed* rather than never considered: it was
  the reader's own suggestion and the platform does not offer it. See decision 11 — there is no document setter,
  so it **is** the rebuild it was proposed as an alternative to.
- **The whole-plan line budget** (`plan.line-budget-exceeded`), replaced by `scene.line-budget-exceeded`. It was
  measuring "lines in the plan" while claiming to measure "lines on screen at once"; those were the same number
  only while a plan was one tree.
- **`RelationArrowRenderer` and the whole second link drawing** — `ArrowSpec`, `addArrow`, `arrowReserve`'s
  label allowance, `anchor`/`Anchor`, and five constants. Not rejected on how it read: it read fine. Deleted
  because *two* drawings for one concept is what made a shared palette, a shared hover and a shared routing
  impossible, and the reader asked for all three. Its rules survive inside `LinkArt` — the channel clear of
  every spanned row, the leg routed round a block inlay, the head pointing back into the text.
- **The relation-arrow *colour* as a thing separate from the link palette.** `arrowUseAccent` / `arrowColor`
  now seed the palette for **every** link rather than colouring half of them, so the setting does more than it
  did while meaning the same thing.
- **The gutter pointer form of a cross-frame link** — `addPointerGutter`, `RelationPointerRenderer`,
  `PointerIcon`. Rejected in round 2 on how it read, and it turned out never to have been *drawn* in a node at
  all: `isLineMarkerAreaShown = false` there, so the affordance was absent rather than unclear. The callout's
  prose line for the relation survives and is unchanged; only the icon is gone.
- **The portal chip entirely** — a numbered rounded box at the start of the line, and the name on it before
  that. Rejected on sight: *"rather than current rectangular mini-callout on the left of the line"*. The
  reason generalises and is worth keeping — a chip marks a **row**, and a relation connects **tokens**.
- **The in-place spotlight and everything that selected it** — `EditorHighlighter`, `Plan.view`, `wantsTree()`,
  `VIEW_EDITOR`/`VIEW_TREE`, `WalkthroughSnapshot.view`, the `/api/state` `view` key, both guards, both
  `*-editor.json` fixtures, their menu entries. Deleted on round 3's verdict; decision 5 has what it cost.
- **The Phase 1 bench cluster** — `SpikeNestedNodeAction`, `SpikeNodeViewAction`, `SpikeSupport`,
  `SpikeNodeTreeEditorProvider`, 3408 lines, and their `plugin.xml` registrations. They judged Phase 1's layout
  alternatives on a hard-coded fixture; the production host plus the fixture plans do the same job on the real
  schema, which is what the plan always said would replace them. **The round vehicle
  (`spike/FixturePlanActions.kt`) stays** — the menu is now seven plans, next/prev and clear. **Note what deleting them cost**: the uncommitted bench work included the round instrumentation
  (`reportOutlinePixels`, `reportChevronAffordance`, `reportFolding`, `reportSidebar`), so any future visual
  round rebuilds its checks rather than inheriting them.
- **Nothing else.** The issue codes, the `keep`-role split and the depth cap all survived their rounds and stay
  as they are.

---

## Deferred, explicitly not in scope

- **B1b beats** — the token flash landing on the phrase being spoken (MoreUI.md §4.3, §5 tier (b)). B5's
  choreography half — links and callouts drawing themselves in time — is **dropped, not deferred**: the tree's
  link allocator is per-step and tree-global, so revealing pairs one at a time would re-lane the ones already
  on screen
- **Re-rooting the tree on a node**
- **Editing in a node.** Nodes are real documents, so making one writable is *possible* in a way Arch A's
  synthetic file never was. Deliberately not now: a narration surface that can silently modify the user's code
  is a different product with different risks
- **Very large trees** — N editors has a cost ceiling; if a plan asks for 20 nodes, the answer is a cap and an
  authoring rule, not lazy editor construction, until measurement says otherwise

---

## How to verify with the user — the spike-bench loop

Half of what this plan asks cannot be measured — *does a node read as a node, is a scrolled-away active range
worse than a tall node, does the callout fit* — and the other half can be measured but is invisible in the UI.
The loop that has worked for both:

1. **Build the alternative as a knob, not a constant.** A constant to edit costs a rebuild *and* a 4–7 minute
   sandbox launch per comparison, and nobody compares seven things at that price. **Put the *settled* knobs on
   the bench too**: 1a‴ left `chrome` off because 1a′ had settled it, nesting changed what the question meant,
   and the round came back with *"it's a pity the chrome switch was not included"*. When a new container
   changes the geometry, every visual knob is open again whether or not its old verdict was sound.
2. **Make the competing alternatives one click apart in the same vehicle.** Rebuilding nested / flat / split in
   place on the same fixture is what turned *"which reads better"* from an impression into a verdict; two
   launches would have compared two moments as much as two layouts.
3. **Probe the parts that are not about the IDE before building the plugin at all** — a `javac` probe answers
   *"does a transparent overlay swallow mouse events"* in about two seconds rather than a sandbox launch.
   `probes/README.md` holds the standing rules and the commands; two are worth repeating here because they are
   about *writing* the probe rather than running it:
   - **Read the probe's first run as a report on the probe.** The 1a⁗ probe opened 18 pass / 5 fail and all
     five failures were its own — including a control that passed when it should have failed. A probe that
     passes first time on a question you did not already know the answer to is the thing to be suspicious of.
   - **A probe is for a question that is still open.** A standalone file re-derives the geometry, so it is a
     *second* transcription that agrees only until one copy is edited — and the edit will be in the production
     code. Both §1a⁵ probes were deleted for exactly that. Once the answer is in the product, the assertion
     belongs in a bench that can **import** the real thing, which is what `probes/` now is.
4. **Hand the user an ordered script and wait**, rather than launching and narrating: what to click, what
   should happen, and which rows need their judgement. Tabulating it (`# | do | expect/judge`, with `[JUDGE]`
   and `[WATCH]` tags) is what made rounds come back with a verdict per row instead of an overall impression.
5. **Then read the log, not only the round's own output**, and slice it to the current launch first. 1a′
   reported `5 pass, 0 fail` while two `SEVERE`s from our own code sat in `idea.log`; those became constraints
   11–13. **A green report is not a clean run.**
6. **When a surface is deleted, the checks that covered two things now cover one; go and find what they
   stopped covering.** Losing the theme-flip two-for-one is what hid the Settings ▸ Apply regression until a
   reference sweep found it (constraint 11). **And go and find the *settings* that named it.** Round 6
   replaced the elbow arrow with `LinkArt`; three dialog rows kept reading into a style object nothing
   consumed, and stayed that way for five rounds. Nothing catches this: it compiles, the value persists to
   `audioExplainer.xml`, Apply re-renders, and the picture is correct — the knob is simply not in it. Only a
   reader turning it and seeing nothing found it. **A field that is written and never read is invisible to
   every check in this repo, so grep the settings class for consumers whenever a renderer is replaced.**
7. **Read the artifacts even when the round skipped the steps that produce them** — 1a⁗ stopped before its log
   grep, which was recoverable afterwards and held the round's most important result (multi-level collapse,
   1677 → 1080 → 1677 px). Two rules about reading them:
   - **The verdict is the set, not the newest artifact.** G1c″ produced five and left a landing FAIL in three;
     the two that passed were the same case at the same step, so the newest was the least informative and the
     round was recorded clean on a check it was failing.
   - **When a check *skips*, ask what changed under it.** Both of G1c⁗'s check defects were introduced by the
     previous round's accepted work — including a section keyed off a knob whose default that round had
     flipped, so it skipped in every case and read as clean. **When a knob's default moves, grep the checks for
     that knob's name.**
8. **When only a live window shows the defect, drive the live window — do not reason at it.** Round 10's *"all
   links are routed to the far right side"* was unreproducible in the bench and cost two wrong hypotheses. What
   measured it: a **deduped trace** on the decision (one line per *change*, because it runs inside a paint
   pass), then the reader's already-open sandbox driven over the same `set_plan` / `goto` transport the fixture
   menu uses — `~/.claude-explainer-port` + `-token`, `POST /api/message`, numbers read out of `idea.log`. It
   cost no round and no launch. **Then reproduce it offline before fixing it**: a ten-line probe over `LinkArt`
   returned the same five numbers, which is what turned a plausible reading of the code into a measurement, and
   the reproduction became a bench assertion while the trace came out. **And a defect that appears on one
   machine and not another is a coordinate-basis defect until proved otherwise** — that window was on a monitor
   *left* of the primary, so every screen x was negative and every `0`-as-sentinel silently meant "no demand".
   The lasting instrument is not the fix but the invariance assertion: the same input, translated, must produce
   the same output.

---

## Operational notes

**Launching**

- Close any running sandbox IDE **before** `runIde`/`buildPlugin` — a live sandbox holds a Windows jar lock and
  `prepareSandbox` fails. `compileKotlin` is unaffected.
- **`gradlew runIde -PspikeFixture` opens `fixtures/TreeSpike/TreeSpike.sln`** instead of this project. Without
  it the sandbox reopens whichever solution was last used, and with the wrong project open every probe skips
  with a notification rather than lying.
- The sandbox stops at the **Welcome screen** unless `config/options/ide.general.xml` has
  `reopenLastProject=true`, and with no project open the plugin's server never starts (`ServerStartupActivity`
  is project-level), so the port file never appears. Check before launching; a cold start is 4–7 minutes to
  waste, and restore the file **after** the sandbox is dead — a graceful exit rewrites config from memory.
- `MainWindowTitle` is not a readiness signal — it read `Welcome to JetBrains Rider` while a project frame was
  already opening behind it. Poll for `~/.claude-explainer-port`.
- `log.debug` for `#com.audioexplainer` is already enabled for `runIde`; output lands at level **FINE** in
  `.intellijPlatform/sandbox/JetbrainsAudioExplainer/RD-2026.1.4/log/idea.log`, **not** stdout. The console
  carries `WARN` and above only.
- Verify rendering by POSTing a hand-written `set_plan` to the running server and screenshotting — but **a
  narrow editor legitimately suppresses decorations** (the right-hand reserve, callout wrap), so a frame with
  no decoration proves nothing either way. Craft the plan so the thing under test has room.

**The log gate, as one block.** Run it immediately after each report, and *before* step 1 so the baseline is
established rather than assumed. **Delete `log/` before launching** — that makes the baseline genuinely zero.

```
L=.intellijPlatform/sandbox/JetbrainsAudioExplainer/RD-2026.1.4/log/idea.log
S=$(awk '/IDE STARTED/{n=NR} END{print n}' $L); tail -n +$S $L > /tmp/session.log
for p in SEVERE ThreadingAssertions "Plugin to blame" java.lang.OutOfMemoryError; do echo "$p $(grep -c "$p" /tmp/session.log)"; done
grep " WARN - " /tmp/session.log | sed 's/.* WARN - //' | sed 's/[0-9][0-9]*/N/g' | cut -c1-90 | sort | uniq -c | sort -rn | head
```

Four corrections that each cost a round to learn:

1. **Scope to one session before counting anything.** The verification round's first pass read **195** folding
   warnings and **6 `SEVERE`** — sums across *three* of the day's sessions in one un-rotated `idea.log`. Scoped
   to the launch: **92** and **1**. A count that spans a session boundary is not wrong by a little.
2. **`java.lang.`-qualify `OutOfMemoryError`.** Bare, it matches `-XX:+HeapDumpOnOutOfMemoryError` in every
   startup's `JVM options:` line, so a gate that expects zero can never return zero and hides the first real
   hit behind the standing one.
3. **Tally the `WARN`s by kind; do not grep for known ones.** Four terms cannot report a fifth thing — the
   103–159 `FrontendEditorFoldingModelAdapter` warnings per round were found by re-reading a log for an
   unrelated reason, not by the gate.
4. **Normalise any count before comparing it, and a rate is still not a mechanism.** 86 → 159 → 103 looked like
   noise until divided by reports written (~29 / ~32 / ~26) — flat, so a rebuild rather than a leak. *Which*
   rebuild was then got wrong twice; the third attempt got a counter before it got a sentence.
   Attributed at the end: the rejected inserts are the daemon's class-body and member-body folds crossing our
   last gap, 2 per node per pass, **none of them ours** — benign and structural, so **stop counting it.** A
   count that stops matching that shape is a new question.

**Reading a log**

- **A gate reports only on what the run exercised — open the sidebar once per gated run.** Six consecutive
  rounds logged zero `SEVERE` and the verification round logged one, and it had not regressed: **the sidebar
  had never been constructed in any of those six rounds**, so the streak was a statement about the tree surface
  and silence about the other one. **Any surface a gate is silent about is silent for the same reason** — open
  the sidebar once per gated run.
- **`idea.log` is neither stable nor a length measure.** It is *not* truncated between launches (a naive grep
  after 1a″ returned 24 blocks naming our package, every one a `SEVERE` fixed hours earlier), it *is* truncated
  in place when it grows with no backup kept (45 360 → 444 lines mid-round), **and** it rotates. So slice to
  the launch before counting, grep immediately after each report, and never quote a line count as a measure of
  a round's length.
- **A `SEVERE` naming our plugin is not necessarily ours.** `JBCefApp$Holder <clinit> requests
  ProxyMigrationService … Plugin to blame: Explain Code Audio` carries **no frame of ours** in its stack — it is
  the platform's class-initialisation order, attributed to whichever plugin first touched JCEF. Read the stack
  before believing the blame line. Likewise a `SEVERE` slow-op from the bundled CSV plugin on file open, and
  the write-intent assertion Ctrl+scroll font zoom trips inside Rider's own `HighlighterColorUpdater`.

**Committing**

- **Commit before each destructive pass, not after it.** A cleanup pass deleted files with no git copy and one
  of them had to be recovered from `javap -c` on the previous build's bytecode. **Check rather than assume in
  either direction** — the post-G1c⁗ round started from a stale "untracked" reading and confirmed it against
  `git ls-tree` before relying on it.
- **KDoc in `tree/` cites `spikes/tree1a3…5/` for where a number was measured.** The directories are deleted;
  those citations are facts about the past and resolve at commit `4f7cc91`, the last one that carried them. The
  dev-only/production split is in *What ships*.

---

## Standing lessons — the checks that were wrong, and why

Kept because every one of them is a rule about how to measure, and each cost a round to learn.

The most reusable page in this file: several spikes and checks reported a verdict their test could not support, and a mis-specified criterion is a trap that recurs. One line each.

- **A check placed outside the thing it measures cannot fail** — the elbow was drawn *outside* the child and pixels counted over the whole frame, so a child-only repaint could not have erased it under either mechanism, and both "passed".
- **A screenshot of the whole hierarchy hides what a stale overlay does**, and a before/after pair can be byte-identical because the scroll it was meant to capture applies asynchronously.
- **A suppressed decoration looks exactly like a broken one** — the right-hand reserve correctly declines the arrow at ~445 px of code area: *a frame with no arrow is evidence of neither pass nor fail.*
- **A count over rendered text has a floor, and it is not zero** — "no connector-coloured pixels after erasing the overlay" got **53** every time, LCD subpixel fringes (`#317DC1`) a hair from the connector's `#2F7FD0`. Assert the *delta*, not an empty baseline.
- **A user-reachable feature makes its own measurement ambiguous** — `OPENABLE` elisions exist to be opened, so `invalidFolds()` (a loss) had to be split from `expandedFolds()` (a reader).
- **Geometry duplicated in an assertion is a false defect waiting to be believed** — "the token box is missing its top and right sides" was the *check* re-deriving the rectangle and disagreeing with the painter by 1–2 px.
- **A synchronous read of an asynchronous settle reports the intermediate state as a failure** — true at one level of nesting and wrong at two. Make the chain synchronous or measure after it settles, and say which.
- **Tolerance around a mid blue admits grey, and that produces a false PASS** — the probe matched the child's own chrome border and called it the leader, so a control that should have failed passed. Test the *hue*.
- **A control that derives its expectation from the value under test tests nothing**, and it is invisible there: a control that cannot fail looks exactly like a mechanism that works.
- **A mock's own layout can hide the thing being measured** — `PLAIN`'s border read y=8 when it is y=0, because the mock painted its fake editor over the border under measurement. Production cannot have that bug, which is why the mock had it.
- **Measure the defect against the thing the defect is about** — constraint 18's defect is clearance to the token's *own* glyphs; measured against the neighbours it reports 0 collisions at pad 0, which is true and irrelevant.
- **A fix's own geometry can reintroduce the defect it fixed** — the replacement border's rounded corners left the diagonals landing in the gap: the vertical geometry was shared through one value and the horizontal extent was not. **Sharing one number is not sharing the geometry.**
- **A column scan cannot follow a path that moves** — "is the outline continuous" as an ink-free-row walk down a fixed column fails on a diagonal that leaves the column by design; **connectivity is what "one outline" means.**
- **A probe proves a shape lands; only the eye says it is the right shape** — every pixel assertion passed on a brace that was one stem with a spine across it. A geometric probe cannot review intent.
- **When a mechanism "cannot work", check whether the blocker is itself a choice** — "a node fitting its cap has no scroll room" was correct reasoning to a wrong conclusion: the blocker was a default, and the answer was to **change the cap**.
- **A number measured in a mock is the mock's number** — 7 px in the probe, 9 px in the component, because the caption font comes from the editor scheme. The fact was right and the constant was wrong.
- **An option added beside another can be strictly worse, and a verdict can be read one notch too far** — verdict 16 asked for no *header* and `NodeChrome.NONE` delivered no header and no border. Implement the verdict's literal scope.
- **A measurement that says one option needs machinery and another does not is a design signal, not a footnote** — two chromes measured 0 offset from the first probe run, it was filed as a convenience, and the offset-aware border built for the third died with the chrome.
- **A check that hard-codes a number the product derives votes against every future change to it** — `pref == 0` was true for three rounds and then went red on the *working* code, and the response to a red check is to revert the fix. **Assert the derivation, not the value it currently produces.**
- **"Not yet" and "not ever" are different claims, and one boolean cannot carry both** — `!landingSatisfied` was reported as *"never reached the offset they asked for"* about nodes sitting on their member, while the check raced its own setup. The product names the state now and the check drives it to settle first.
- **The defect is never "two derivations of one fact" — it is "a change applied to one of them"** — and the grep was for `boxRect(` while the next instance derived the child's top from a **frame rectangle**, so it could never have found it. **When a container gains an inset, every coordinate measured from its origin is wrong, and the ones wrong by less than a strip's height pass anyway.**
- **A check that asserts *absence* passes on a sample of nothing** — the box's bottom edge read 8 % inked in every mode, and two modes passed by expecting no edge there, so six rounds of their PASSes came off an image with no box in it. **Every "X is absent" assertion needs a case where X is present.**
- **A check's own guess about why it failed is a hypothesis, not a finding** — the message blamed depth, the reports said containment. A message is written before the defect exists, when it is least informed.
- **A check placed where the defect is not cannot report it, however many numbers it prints** — the landing section measured with every nesting **expanded**, and the clamp exists only while one is **collapsed**.
- **A fix that restores an action has not established that the action is possible** — `asked=66px offset=0px` reads identically whether nothing asked or nothing could comply, so the check has to report the room available as well as the room requested (constraint 23).
- **A control that is easy to leave engaged will be left engaged** — all four G1c⁷ rounds ran with `nodeSlack` off, so the defect was recorded cleanly and the fix not at all. **Label the control in the artifact, not only where it is set.**
- **A comment that quotes the verdict is not evidence the verdict was implemented** — `leaderPath` carried
  G1c⁗'s *"exactly on top of the header, not above it"* verbatim, next to code that put the run a radius
  above it, and three rounds read past both. The quote made the geometry look settled; the reader saw the
  gap only when a link crossed it. **Re-read the arithmetic, not the paragraph in front of it.**
- **A skip is the one outcome that reads as success**, and it arrives from a default moving under a check that was written right, or a count meeting a fixture shape it had never seen (`wide`'s three siblings).
- **A hazard placed in the last frame has nothing downstream to damage.** Phase 0's S1b read the row that
  *opens* a `/*` (whitespace either way) and put it in the final frame; rebuilt in the middle it showed that an
  unclosed block comment taints the next frame's first content row and that a `//` separator **cannot** stop it,
  because a `//` inside `/* … */` is still comment. Constraint 14 is the rule; this is why it took two attempts
  to see.
- **Counting markers cannot tell colour from squiggles**, and a run that reads a previous run's own output
  scores itself. Phase 0's S5 called `0 → 84` a success against a control of 736.

---
