# Tree of Code Views — completed-phase ledger

Historical companion to `TreeOfCode.md`, which now carries only what still shapes the work ahead. This file
answers one question: **what was actually measured, and what was not.** It is a ledger, not a second copy of
the results — those live in `MultilineTreeFindings1.md` §6 (Phase 0) and §7 (Phase 0b), which record the
numbers, the corrections and the caveats.

Probe *specifications* are deliberately not preserved. A passed gate's spec has no future use, and the ones
worth remembering are the criteria that turned out to be **wrong** — kept below, because that keeps happening.

**Most of the code named below no longer exists.** The cleanup pass deleted the Phase 0 / 0b / 1a″ benches, the
flat-tree layout they judged, and the `spikes/b3a`, `spikes/tree1a2` and `spikes/tree1a3` probes — the surfaces
were retired by later verdicts and the findings are here and in `TreeOfCode.md`'s constraint list. **None of it
was ever committed to git** (see the cleanup section), so these pages are the record. File paths below are where
things *were*, not where to look.

---

## Phase 0 — Arch A spikes · PASSED (gate G0)

Vehicle: one throwaway action (`SpikeStoryEditorAction`) running every probe in sequence inside a
`DialogWrapper`, rendering the editor to a PNG and writing a pass/fail report — pixels rather than reasoning.
Fixture embedded in the spike source (excerpts + the real source line each starts at), so it needed no `.cs`
on disk. Artefacts: `scratchpad/spike-story/*.png` + `report.txt`. Four sandbox runs.

| Probe | What it asked | Verdict |
|---|---|---|
| **S1** (gate) | Does a `LightVirtualFile` + `createViewer` + `createEditorHighlighter` get C# colour? | **PASS** — 35 token types, 5 distinct foregrounds |
| **S1v** | Is a `VirtualFile` needed at all? | **PASS, and a simplification** — a bare `createDocument` colours identically |
| **S1b** | Does an unterminated string / unclosed `/*` smear across stitched excerpts? | **Smears.** A separator row cannot stop it; only emitting `*/` can |
| **S2** | Do custom fold regions paint, collapse, and re-add cleanly? | **PASS to paint/collapse — but they refuse to expand** |
| **S3** | Does `setLineNumberConverter` show real source line numbers? | **PASS** |
| **S4** | Does the existing decoration stack drive a story editor (incl. block inlay in a *viewer*)? | **PASS** |
| **S5** | Arch B fork gate: semantic markers on a real document with no `FileEditor`? | **SKIPPED** — no `.sln` in the repo; re-run as B5 in Phase 0b |
| **S6** | Host survey: dialog / tool window / ordinary editor tab | **PASS**, and S6c was cheaper than assumed — the platform's own text editor accepts a `LightVirtualFile` named `story.cs` |

**What Phase 0 did not settle:** which architecture. Reviewing the built prototype did — one stitched
document with drawn rails reads as *one file with arrows in it*, not as a tree. That, plus S5-as-B5 measuring
semantic colour at **84 markers for a real on-disk document vs 0 for a `LightVirtualFile`**, and the fact that
sub-regions of one editor cannot scroll independently, flipped the plan to **Arch B**.

---

## Phase 0b — Arch B spikes · PASSED (gate G0b)

Prerequisite **P0b.0**: `fixtures/TreeSpike/` — the three-project C# solution the spikes and Phase 1 both
need, built because S5 was unmeasurable without one. Still live; described in `TreeOfCode.md`.

Vehicle: `SpikeNodeTreeAction` beside the Phase 0 action, reusing its report/capture/timer/release plumbing.
One sandbox launch, 12 node editors over 4 trees, **18 pass / 0 fail** — one of which is retracted below.
Artefacts: `scratchpad/spike-nodetree/*.png` + `report.txt`. B3a needed no IDE: a plain `javac` Swing probe
(`spikes/b3a/B3aOverlayProbe.java`).

| Probe | What it asked | Verdict |
|---|---|---|
| **B1** | Does our folding survive on a real document the backend owns? | **PASS** — ours survived every sample incl. a live edit. **But the daemon pushes its own folding in too** (19–59 foreign regions after our `clearFoldRegions()`), additively |
| **B2** | Does a throwaway `FileEditorProvider` host a `JPanel` of node editors? | **PASS** — ~90 lines; opens, colours, disposes |
| **B3a** | Overlay mechanism, no IDE: `JLayeredPane` vs container-side `paintChildren` | **DECISIVE** — 1444 → 1444 px vs 1444 → **3** px after a child-only repaint |
| **B3b** | Same, with real editor components | **RETRACTED, not passed** — see below |
| **B4** | Fold-aware height; nested scroll; wheel routing | **HALF** — heights solid, routing unmeasured |
| **B5** | Semantic colour at N, properly settled | **PASS, decisive** — 78–321 `ReSharper.*` markers/doc, 23–30 categories, at 12 editors folded *and* decorated. Bonus: ReSharper type-hint inlays render too |
| **B6** | Per-node cost and disposal | **PASS** — 15–19 ms/node; `getAllEditors()` 4 → 16 → **4** |

---

## Phase 1a — renderer/listener split · DONE

`EditorHighlighter` (1868 lines) became `SpotlightRenderer.kt` (1712, the drawing stack, one instance per
editor) + `EditorHighlighter.kt` (242, the `WalkthroughListener`). Verified by a declared-name diff and a
comment-stripped code-line diff against the pre-split commit — only the intended renames and dropped `editor`
parameters — then by five synthetic `set_plan` POSTs at a live sandbox over `fixtures/TreeSpike`:

| Checked | Result |
|---|---|
| four line roles: `dim` / `segmentDim`+tint / `gap`+gutter bar / `active` glow | all four distinguishable in one frame, syntax colour intact underneath |
| callout inlay, all three block styles | PROSE (italic, ellipsised at its cap) + rule + CODE example + POINTER line |
| overview-ruler mark; gutter pointer icon | both present |
| token flash | `'converted' occurrence 1 -> line 26 offsets 727-736`, visibly mid-fade |
| relation split | arrow **and** pointer both resolved and logged with the expected text |
| relation arrow *painted* | only on a second probe — see the trap below |
| editor switch clears the outgoing editor | a bare segment back in file A showed the outer dim and nothing left over |
| `stop` | every decoration gone |
| nothing threw | zero `Walkthrough decoration render failed` warnings across all POSTs |

Fixed on the way rather than preserved: `onHighlight` used to set `trackedEditor` to the **new** editor before
the render's `clearTracked()`, so a step arriving for a different file left the previous file decorated for
good.

---

## Phase 1a′ — `CodeNodeView` + the interactive bench · DONE

Built: `com.audioexplainer.tree.CodeNodeView` (production) and `SpikeNodeViewAction` (throwaway bench, every
1c alternative a button). Two sandbox rounds over `fixtures/TreeSpike`, driven by the user; artefacts
`fixtures/TreeSpike/scratchpad/spike-nodeview/report.txt` + 4 PNGs per round.

Vehicle change worth keeping: the Phase 0/0b probes were **unattended** (one action, a timer chain, a report).
This one is **interactive**, because five of its seven questions were "does this read well" and no report can
answer that. Both halves in one component: buttons for the eye, `write report` for the record.

| Checked | Result |
|---|---|
| **elision survives the daemon** | 3/3 of ours still collapsed per node, every run; 13–25 foreign regions alongside |
| **semantic colour on the built thing** | 182 / 321 / 302 `ReSharper.*` colour markers across the three documents, folded *and* decorated (constraint 3 re-confirmed off a probe) |
| **height resync** | `fold=2/2 elision=1/0 font=8/8 scheme=2/0 restyle=4/0` — every trigger fires; scheme correctly changes no height |
| **the 600 px cap** | folded 484 / 550 / 585–612 px — the tallest crosses 600 at 1050 px node width and drops under it at 1268 px, so the cap is a boundary that moves with width, and both behaviours are visible at once |
| **focus / caret at N editors** | a caret appears only in the clicked node; the bench reports the focus owner. The 1a-deferred question, closed by switching a non-focused node's caret off entirely |
| **callout at node width** | 1 block inlay on the active node, 0 on the rest; wraps and reads at 1000–1270 px |
| **connector anchors (for 1a″)** | both `linkFrom` lines resolved *and* visible rather than inside an elision |
| **N-editor disposal** | `getAllEditors()` **6 → 6** across five open/close cycles |
| **`EditorHighlighter.refresh()`** | verified at last — a theme flip drove it, logged as `render step 1/2 of segment 0 (lines 80-82)` |

**By eye** (the user's verdicts, which are the deliverable for these rows): chrome `TITLED_BORDER`, path
`PROJECT_RELATIVE`, de-emphasis `TINT` alone, sizing **capped** over grow, elision `OPENABLE` — which also
turned out to give a free tooltip of the elided code on hover. All five are now the shipped defaults and a
Phase 4 settings item.

**Three defects, none visible in the UI and none in the report** — found by reading `idea.log` after a
`5 pass, 0 fail`:

- `FileDocumentManager.getDocument` on the EDT without a read action → `SEVERE ThreadingAssertions`. *Soft*
  assert: logs and continues. → constraint 12.
- The application-level scheme subscription was parented to the view itself, which only the Disposer tree
  disposes; an owner calling `dispose()` directly left it live, and the next `ShowSettings` drove `resync` into
  `offsetToXY` on a released editor → `SEVERE Editor is already disposed`, once per node ever built. →
  constraint 13.
- A scheme change resynced the height but re-rendered nothing, so a dark→light flip kept dark tints and a white
  callout font until a button forced a repaint — **reported by the user in prose**, and true of the shipping
  in-place walkthrough too. → constraint 11, fixed in both paths.

Found after the last round and therefore **compile-verified only**: `resync` coalesced on the *content* height,
which a grow⇄cap toggle does not change, so `restyle` alone published no new size. The bench masked it by
revalidating its own container. **Confirmed fixed in 1a″**, whose container deliberately does not revalidate
itself so the counter is the only evidence.

---

## Phase 1a″ — `CodeTreeView` (tree container) · DONE

Built: `com.audioexplainer.tree.CodeTreeView` (production) and `SpikeTreeContainerAction` (bench), plus
`spikes/tree1a2/TreeOverlayProbe.java` — a plain-`javac` pre-flight probe. One sandbox round, four cases,
four reports in `fixtures/TreeSpike/scratchpad/spike-treeview/`.

**A new step in the loop: probe the Swing structure before spending a launch on it.** Four of 1a″'s questions
were about Swing and not about the IDE, so they ran outside it in about two seconds — mouse-transparency,
whether a *banded* overlay repaint restores the stroke, viewport-width tracking, and depth positioning. All four
passed before the plugin was ever built, which is why the sandbox round had nothing structural left to find.
This is the `verify-swing-ui-without-sandbox` discipline applied to a container rather than to a defect.

| Checked | Result |
|---|---|
| overlay structure (constraint 7) | above content, non-opaque, equal bounds, `contains()==false` — PASS in all four cases |
| layout by depth | every node at `inset + depth × indent`; every slot exactly its published height; held at 933, 905 and 751 px tab widths |
| connector anchors | **2 / 1 / 3 / 3 edges, all resolved, all SOLID** — both ends real, visible lines |
| the wide case | `Dispatch` → three handlers from lines **50 / 51 / 52** |
| geometry live, not cached | an 88 px scroll inside a node moved its parent anchor 579 → 491 px |
| `restyle` publishes (1a′ carry-forward) | `crossesCap` and `publishedOnToggle` agreed on every node of every case — **closed** |
| re-render coalescing | **`requests=8 dispatches=2`** across two theme switches at N=4 |
| semantic colour | 182 / 321 / 302 (chain), 185 / 93 / 96 / 78 (wide) |
| reveal into viewport | PASS in all four cases |
| totals | **38 pass / 0 fail / 2 skipped**, and **zero `SEVERE`/`ERROR` in the launch** |

The two SKIPs are the honest kind: in both `wide` runs no node crossed the 600 px cap, so there was nothing to
scroll and the live-geometry check said so instead of passing on a stationary input.

**Two interpretation traps, both of which a naive check would have got wrong:**

- `folds: 2/3 ours` and `1/3 ours` are **not lost elisions** — they are the reader having clicked one open,
  which is what `OPENABLE` is for. `survivingFolds()` counts valid *and collapsed*, so a reader is
  indistinguishable from a loss unless the count is split. The 1a′ bench would have written a FAIL here.
- `width=420/0` looks like a broken listener and is the opposite: 420 resize events, none of which changed a
  height, i.e. the coalescing guard doing its job through a layout storm.

**No defects.** The three 1a′ constraints (read actions, disposable parenting, scheme re-render) held, and the
one thing 1a″ was told to confirm first — that a grow⇄cap toggle publishes a new size — did.

---

## Phase 1a‴ — Arch C nested nodes (`CodeNestView`) · G1c PASSED

**What it set out to test.** Whether a child node can be drawn *inside* its parent at the call site — the
reader's proposal — and whether that reads better than 1a″'s flat tree. Three layouts on one resolved fixture,
one click apart: **nested** (`CodeNestView` + `EditorEmbeddedComponentManager`), **flat** (`CodeTreeView`
unchanged), **split** (the no-impl-API fallback: the parent cut into before-call / after-call nodes with the
child between them).

**Pre-flight probe first** (`spikes/tree1a3/NestedLeaderProbe.java`, ~330 lines, plain `javac`, **7 pass / 0
fail** in ~2 s). Four of the questions were pure Swing and none of them needed the IDE:

| Probe | Result |
|---|---|
| the token's x converts from the editor content component's space into the leader strip's | exact; **control**: unconverted it is wrong by exactly the nesting indent, every time |
| a diagonal drawn to `y == height` is clipped away | endpoints at `height - 1` reach both frame corners, both shapes |
| the token box has four sides | passed only after the check stopped re-deriving the rectangle — see the lesson below |
| collapsing shrinks the preferred height **and** the slot | 137 → 37 px; **control**: a height-caching frame reports 137 → 137, the 1a″ defect kept as a control |
| how shallow is "straight to the corners" at full width? | **8.0°** — and the PNGs said it reads as an expansion funnel anyway, which is why `CORNERS` shipped as the default and `FLARE` was dismissed before the sandbox ever opened |

**Measured in the sandbox** — one round, five numbered reports, four cases, **65 pass / 1 fail / 10 skipped**
(the last report re-measures `wide` after a theme flip, so 14 passes are a repeat, not new coverage):

| Checked | Result |
|---|---|
| embedding into a **viewer** editor | 6/6 across three cases; every `Inlay` valid, every frame inside the host's content component, every leader on a located token |
| **the host reflows** (the load-bearing claim) | `offsetToXY` counts the block inlay: `MainLoop` 528 → **1741 px** around a 1138 px child, next line at y=1727 below the frame |
| slot around the host | every top-level node exactly its published height |
| mouse reach | `EditorComponentImpl` **inside the child** at the child's centre; `LeaderStrip` at the leader. The wheel reaches the child and the leader takes its own click |
| collapse / re-expand | **exact at one level** — same-file 1029 → 606 → 1029, wide 1617 → 524 → 1617 with three siblings; host scroll offset unchanged everywhere. **FAIL at two levels** (below) |
| elision when nested | 3/3 collapsed, **0 lost**, 13–25 foreign, at every depth |
| semantic colour when nested | 182 / 321 / 302 — identical to the un-nested numbers |
| re-render coalescing | `requests=4 dispatches=1` across a theme flip |
| disposal | `getAllEditors()` 9 → 9, 8 → 8, 10 → 10 across two full rebuilds each, 19 rebuilds in the session |

**The one defect: multi-level collapse.** Collapse-then-expand on the chain restored the outer host to 1186 px
instead of 1741 — short by exactly 555 px = the inner frame's 602 − 47. The inner nesting's re-measure reaches
the *outer* inlay only through the host node's geometry callback, which coalesces onto an `invokeLater`: **one
deferred pass per level of nesting.** Invisible to the reader (the pass does run), visible to a synchronous
check. Now constraint 17, with a ~10-line fix in §1a⁗.

**The eye found what the numbers could not, and it was geometry.** With `TITLED_BORDER` the frame's *drawn* line
sits at the caption's mid-height, ~8 px below the component's top edge — so the diagonals, which end at the
component's corner, visibly stop short. Now constraint 16, along with its corollary that leader shape and node
chrome are one design rather than two knobs. The other two eye findings: the boxed token needs padding
(`EffectType.BOXED` draws on the glyph bounds) and must share the leader's colour; and the collapse affordance
was **duplicated**, a triangle on the leader and another on the stub.

**Verdicts taken:** nested beats flat outright · `FLARE` dismissed, `BRACE` and `ARROW` to build · `BOX` over
`BACKGROUND` · **indent 0** (the nested node's own gutter already reads as nesting) · the split fallback is
acceptable as a fallback but should use the token box and the same leader.

---

## Phase 1a⁗ — nesting polish · BUILT AND JUDGED, G1c′ not passed

**What it set out to do.** Work the twelve-item punch list 1a‴'s round produced: the multi-level-collapse defect,
the chrome × leader coupling, a padded token box, one collapse affordance, the member landing, and the split
fallback. Not a re-architecture — Arch C's gate was already passed.

**Pre-flight probe** (`spikes/tree1a4/NestPolishProbe.java`, ~700 lines, plain `javac`, **23 pass / 0 fail** in
~2 s). Three punch-list items said "probe before building"; this is that probe, and each check carries a control
that must fail.

| Probe | Result |
|---|---|
| where each chrome's line is actually **drawn** | `TITLED_BORDER` **7 px** below its bounds edge; `HEADER_STRIP` **0**; `PLAIN` **0**; `NONE` no border at all |
| can a leader reach the drawn line? | yes, at **all 3 shapes × 4 chromes**, via a strip extended by that offset and z-ordered above the child |
| **is z-order the mechanism?** | **decisive** — strip at index 0: tips drawn; strip sent to back: erased by the child's opaque background. So constraint 7's rule applies to siblings one level down, and no container-side paint is needed |
| does the overlap swallow the child's mouse? | not with `contains()` narrowed to the strip's band; **control**: un-narrowed, the strip takes the point and the nested editor reads as dead at the top |
| token-box padding | pad 0 = **0 px** clearance to the token's own glyphs (the defect); 2 px is the cheap fix; the neighbour cost is non-monotonic (2/4/3/0) |
| a collapse control in the header band | hit-testable three components deep; **control**: the same control behind the editor component is drawn and unreachable |
| the collapsed stub | bordered on four sides, dashed — reads as a frame |

**Built** (all twelve, compiling, `buildPlugin` clean): the synchronous innermost-first inlay walk up the nesting
chain (constraint 17) · `chrome` on the bench as its own toolbar row · `NodeChrome.NONE` · our own `CaptionBorder`,
so the drawn-line position is a value rather than an inference · `NestLeader.BRACE` and `ARROW`, `FLARE` removed ·
`indentPx` 18 → 0 · `LeaderArt` as one shared vocabulary (leader path, colour, padded box renderer, call-site
locator) for both surfaces · `LeaderArt.TokenBoxRenderer` replacing `EffectType.BOXED` · one collapse affordance in
the node's header band, strip triangle and stub `▸` removed, stub dashed-bordered · elision-based member landing ·
`TreeConnector.LEADER` for the split fallback.

**The round: 6 accepted, 4 sent back, 2 rejected.** The redesign list it produced is `TreeOfCode.md` §1a⁵; the
verdicts themselves, numbered as the script asked them:

| # | Item | Verdict |
|---|---|---|
| 1 | multi-level collapse (the 1a‴ FAIL) | **Accepted** — measured exact, 1677 → 1080 → 1677 px |
| 2 | leader lands on the drawn line | **Redesign.** Lands at the corners, but the corner *rounding* the fix introduced means it does not meet the border there — constraint 16 |
| 3 | nested editor takes the mouse in its top rows | **Accepted**, and a fact recorded: a nested node has no scrollbar at all at its natural size — constraint 19 |
| 4 | padded token box | **Accepted** — "padding ok now" |
| 6 | `HEADER_STRIP` | **Accepted** |
| 7 | `PLAIN` | **Accepted, scoped**: caller identity was better in general, so `PLAIN` is the *same-file* chrome, not the default |
| 8 / 14 | `NodeChrome.NONE` | **Rejected — "strictly worse than plain".** Verdict 16 asked for no *header*, not no *border*. And the **topmost** node must keep its header |
| 9 | `NestLeader.BRACE` | **Redesign.** Rounding (as #2), and the shape is wrong: a brace is **two halves**, each starting at a token-box corner |
| 10 | `NestLeader.ARROW` | **Redesign.** Must not run to the frame's far corner, and needs an **arrowhead** |
| 11 | token mark alternatives | **Rejected**: background "still bad" — scrap them, `BOX` is the only mark |
| 12 | landing on the member | **Redesign.** Better in a nested node, *worse* in the top one — and the mechanism should be a scroll inside a capped node, not elision |
| 13 | one collapse affordance + bordered stub | **Accepted** |
| 15 | wide case | **Accepted** — no new notes |
| 16 | split fallback with the leader | **Rejected, and the layout with it**: its leader starts at the invoker box's *middle*, and the answer to that was to scrap the flat and split layouts entirely |
| 17 | theme flip recolours both surfaces | **Accepted** |

The two rejections were both "this option is worse than the one it was added beside" (`NodeChrome.NONE` vs
`PLAIN`; the background token mark vs the box), and the largest outcome was a **scope decision** rather than a
defect: the flat and split layouts are scrapped, taking `CodeTreeView` with them. Everything the round rejected
or retired was deleted in the cleanup pass, including four of the things this section records as built —
`NodeChrome.NONE`, `TreeConnector.LEADER`, the elision landing, and `indentPx`.

**What the round did *not* re-open:** the 1a‴ measurements 1a⁗ did not touch — embedding, pushdown, elision at
depth, semantic colour, coalescing. All six reports carry them and all six pass, but that is a repeat rather
than new coverage.

**The one FAIL is a defect in the check, not in the subject.** `mouse reach: 1 of 2 nested editors do not
receive events at their own centre`, on the *doubly* nested node, in the report written seconds after the bench
opened. The deepest component came back **`null`** — meaning the sampled point was outside the host entirely
rather than being stolen by it — and the same case and chrome passes in a later report from the same session. It
measured a frame the deferred inlay pass had not yet placed. Its `isShowing` guard checks the *child*, and at
depth 2 it is the ancestor chain that is still settling. Now `TreeOfCode.md` §1a⁵ 6, and a lesson below.

**Steps 19–20 were skipped during the round and recovered afterwards from the artifacts** — six numbered reports,
and an `idea.log` that this time was *not* truncated, so the launch window (11:40–13:56) survived. Sliced to it:
**0 `SEVERE`/`ERROR` from anyone, 0 `WARN` from our package**, `getAllEditors()` **6 → 5 across 25 layout builds**,
and **111 pass / 1 fail / 8 skipped** over the six reports. The headline recovery: **constraint 17's multi-level
collapse is exact — 1677 → 1080 → 1677 px**, so the 1a‴ defect is closed with a measurement rather than an
assumption. The single FAIL was the check's own (below).

---

## The titled-border chrome and its drawn-line offset · SCRAPPED, and the measurements retired with it

Kept because two sub-phases of work and one standing lesson came out of it, and because it is the clearest case
on these pages of **solving a problem that could have been deleted instead.**

`NodeChrome.TITLED_BORDER` drew its top border line through the **middle of the caption row**, so the line the
reader sees was not the component's bounds edge. Everything below follows from that one property:

| Measured | Result |
|---|---|
| the offset, in the pre-flight probe's mock | **7 px** below the bounds edge (11 pt sans caption) |
| the offset, in the real component | **9 px** — the caption font comes from the editor scheme |
| the other three chromes | **0 px** — their drawn edge *is* their bounds edge |
| can a leader reach the drawn line? | yes, at 3 shapes × 4 chromes, via a strip extended by that offset and z-ordered above the child |
| is z-order the mechanism? | **decisive** — strip at index 0: tips drawn; strip sent to back: erased by the child's opaque background |
| the round, at `TITLED_BORDER` | `drawnTopOffset=9px leaderOverlap=9px` on both nestings — they agreed, which is what the check was for |

Built to serve it: a hand-written `CaptionBorder` replacing Swing's `TitledBorder`, so the drawn line's position
was a queryable **value** (`chromeDrawnTopOffset()`) rather than caption metrics re-derived in a second place; an
overlapping leader strip with `contains()` narrowed to its own band; and a `reportLeaderLanding` check asserting
that the chrome's offset and the strip's overlap agree.

**Then the reader scrapped the chrome.** Every surviving chrome draws its line at its bounds edge, so the offset
is 0 by construction rather than by agreement, and the whole apparatus above has nothing left to compute. What
carries forward into `TreeOfCode.md` is only the *shape* of the lesson — two drawings that must meet need one
definition of where — plus the two lessons below ("a number measured in a mock is the mock's number", "a fix's own
geometry can reintroduce the defect it fixed"), both of which were learned here.

**The cost, stated plainly.** Two of the four chromes measured **0** offset from the very first probe run, and the
plan recorded that as a convenience ("makes the leader exact for free") rather than as an argument for preferring
them. Had it been read as the latter, `CaptionBorder`, the offset plumbing, the extended strip and the
rounded-corner defect that the fix itself introduced would all have been unnecessary. **A measurement that says
one option needs machinery and another does not is a design signal, not a footnote.**

---

## Cleanup pass · DONE

**What it set out to do.** Execute the deletions the 1a⁗ round's scope decision implied, and compress the two
plan documents back to what still binds. Asked for by the reader alongside the §1a⁵ redesigns. The full deleted /
kept list is in `TreeOfCode.md`; what belongs here is what the pass *found*, because two of its findings were
about the pass itself.

**Verified rather than assumed:** `compileKotlin` clean, no import left orphaned, and **every
`com.audioexplainer.*` class named in `plugin.xml` re-checked against a real file** — a stale registration is a
runtime failure, so the compiler cannot catch it and it is exactly the kind of thing a deletion pass breaks.

**Two things the pass got wrong before it got them right, both the same shape:** a file's name says which bench
it was written for, not who depends on it now.

- The deletion list said to remove `SpikeNodeTreeEditorProvider` with the Phase 0b bench it was named for. It is
  the B2 `FileEditorProvider` and `SpikeTreeVirtualFile` — **the tab both surviving benches open themselves in.**
  Caught by reading the callers before deleting.
- `lineOfAnchor` was declared in `SpikeNodeTree.kt`, deleted, and **called by both survivors.** Caught by the
  compiler. Recovered from the previous build's bytecode with `javap -c` rather than rewritten from guesswork —
  the semantics (first occurrence, 1-based line, null when absent) were unambiguous in ~20 instructions — and
  moved to `SpikeSupport.kt`, where the shared plumbing lives.

**And one thing nobody had checked in six sub-phases: none of this code was in git.** `com.audioexplainer.tree`
and `com.audioexplainer.spike` were still **untracked** when the deletions ran. The plan had been carrying
"rebuild the flat layout from git history" as the recovery path for the `impl`-package risk, and that path did
not exist. The deletions were still the right call — the verdicts are explicit, the findings are on these pages —
but the risk at the top of `TreeOfCode.md` is a risk with **no** undo rather than a slow one, and the recovery
that was actually needed came from a build artefact.

---

## Criteria that were wrong — the standing lesson

Several spikes reported a verdict their test could not actually support. This is the most reusable thing on
this page.

- **B3a v1 was unfalsifiable and its PASS was worthless.** It drew the elbow *outside* the child bounds and
  counted pixels over the whole frame, so a child-only repaint could not have erased the stroke under either
  mechanism — and both "passed". Anchoring the elbow deep inside the child and counting only within that
  child's rectangle produced the real, decisive result.
- **B3b is retracted** (§7.5). `captureComponent` paints the whole hierarchy into the image, and a stale
  overlay is precisely what a full repaint hides. Both mechanisms reported identical counts — impossible given
  B3a — and the before/after PNGs were byte-identical because `scrollVertically` applies asynchronously. The
  mechanism question stands answered **by B3a**, corroborated by hand.
- **B4's 220 px cap was too aggressive** — all 12 nodes exceeded it, so grow-vs-scroll never got compared.
- **A suppressed decoration looks exactly like a broken one.** In 1a the relation arrow did not appear,
  because with the tool window open the editor had ~445 px of code area and `MIN_ARROW_ROOM` correctly
  declined to route an elbow. A second plan (short span, no callout competing for width) showed it drawn. The
  same shape of mistake as B3a v1, one layer up: *a frame with no arrow is evidence of neither pass nor fail.*
- **A green report is not a clean run.** 1a′'s first report said `5 pass, 0 fail` and the UI looked perfect,
  while two `SEVERE`s from our own code sat in `idea.log` — a missing read action and a subscription outliving
  its editor. Neither could ever have failed a check the report contained, because the report only checked what
  it was asked to. **After every sandbox round, grep the log for `SEVERE`/`ERROR` blocks naming
  `com.audioexplainer`** — it is one command and it found two defects the pixels could not.
- **The report measures what you did, not what you meant to do.** 1a′ round one wrote its report before the
  font and theme steps, so the `font=` / `scheme=` resync counters were simply absent — and absent reads exactly
  like "the listener never fired". Order the user's script so `write report` is last. 1a″ fixed this
  structurally: `write report` numbers its output (`report.txt`, `report-1.txt`, …), so switching case after a
  report costs nothing and the script can ask for one per case.
- **A count over rendered text has a floor, and it is not zero.** The 1a″ pre-flight probe asserted that
  erasing the overlay left *no* connector-coloured pixels, and got 53 every time. They were real: LCD subpixel
  antialiasing puts blue fringes (`#317DC1`) on black-on-white glyph edges, a hair from the connector's own
  `#2F7FD0`. Tightening the tolerance from 40 to 16 changed the count by one pixel. The assertion had to become
  "the band restores the *delta*", not "the baseline is empty" — B3a's lesson one layer down: a colour count
  measures the palette, not the drawing.
- **`idea.log` outlives the launch.** Grepped whole after 1a″ it produced 24 `SEVERE` blocks naming our
  package — all of them the two defects fixed hours earlier in 1a′. Slice from this launch's first line before
  reading anything. Sliced, the same round had zero.
- **A user-reachable feature makes its own measurement ambiguous.** `OPENABLE` elisions exist so the reader can
  open them; a fold-survival count therefore cannot tell a reader from a bug. Whenever a check counts something
  the user is invited to change, it has to distinguish *invalid* from *changed*. **Fixed structurally in 1a‴**:
  `CodeNodeView` now reports `invalidFolds()` (a loss) separately from `expandedFolds()` (a reader).
- **Geometry duplicated in an assertion is a false defect waiting to be believed.** 1a‴'s pre-flight probe
  reported "the token box is missing its top and right sides" — because the *check* re-derived the box rectangle
  from the line height while the *painter* used its own arithmetic, and they disagreed by 1–2 px. It looked
  exactly like a half-drawn box. The fix was one definition (`tokenBox()`) used by both. An assertion that
  recomputes what it is checking is testing two implementations against each other, and it will report the
  disagreement as a defect in the subject.
- **A synchronous read of an asynchronous settle reports the intermediate state as a failure.** 1a‴'s collapse
  check was written synchronously on the (correct) grounds that `Nesting.setCollapsed` calls `Inlay.update()`
  itself — true at one level, and wrong at two, because the *outer* inlay learns about it through a coalesced
  `invokeLater`. The FAIL was real information about the propagation, not about collapse. When a check spans more
  than one layer of a deferred chain, either make the chain synchronous or measure after it settles — and say
  which one you did.
- **A `SEVERE` naming your plugin may contain no frame of your code.** 1a‴ startup logged
  `JBCefApp$Holder <clinit> requests ProxyMigrationService … Plugin to blame: Explain Code Audio` — a platform
  class-initialisation ordering complaint, attributed to whichever plugin first touched JCEF. Read the stack
  before believing the blame line, and check whether the entry predates your change (this one appeared in two
  earlier launches the same day).
- **`idea.log` is truncated in place when it grows, and no backup is kept.** During 1a‴ it went from 45 360 lines
  to 444 while the IDE kept running, retaining only the last few minutes — the round's own window was
  unrecoverable by the time it was read. This is the exact complement of the 1a″ lesson above: the log outlives
  the *launch* but not the *session*. Grep it right after each report, and if the window is gone, report that
  rather than presenting the surviving tail as coverage.
- **A knob you left off the bench is a question the round cannot answer.** 1a‴ omitted `chrome`, because 1a′ had
  settled it — but nesting changed what the question meant, and the round came back with "it's a pity the chrome
  switch was not included". A new container re-opens every visual knob, whatever its old verdict.
- **A check cannot measure a line it cannot tell from its neighbour.** The 1a⁗ pre-flight probe reported that
  extended leader tips survived with the strip z-ordered *behind* the child — impossible, and the opposite of what
  the check existed to prove. It was matching the **child's own chrome border**, drawn in the same row and within
  the tolerance ball of the leader's colour, because production deliberately gives both "the accent, muted" (which
  is constraint 18). Two more checks in the same run matched **gutter grey** the same way. Tolerance around a mid
  blue admits grey; the fix was to separate the mock's colours by *hue* and test the hue (`b - r ≥ 40`) instead of
  a distance. This is 1a″'s "a colour count measures the palette, not the drawing" arriving as a **false PASS**
  rather than a false count — and a false PASS is the worse of the two, because nothing prompts you to look.
- **A control that derives its expectation from the value under test tests nothing.** The same probe's control for
  "an unextended leader stops short" computed the expected line position from the frame's *current* overlap — which
  in the unextended case is zero, so the expected line and the bounds edge were the same row and both assertions
  passed. It had to read the chrome's own offset instead. The 1a‴ lesson about an assertion recomputing its
  subject's geometry, one layer over: a **control** can do it too, and there it is invisible, because a control
  that cannot fail looks exactly like a mechanism that works.
- **A mock's own layout can hide the thing being measured.** The probe measured each chrome's drawn border line and
  found `PLAIN` at y=8 (it is y=0): the mock positioned its fake editor at the header band's height, which for
  `PLAIN` is zero, so an opaque child painted over the very border under measurement. Production could not have
  this bug — a `BorderLayout` keeps children inside the insets for free — which is exactly why the mock had it. A
  mock that reimplements what the subject gets from the framework must reimplement it correctly, or it is a
  different subject.
- **Measure the defect against the thing the defect is about.** Constraint 18 names one defect (the box touches the
  token's *own* first and last character) and one risk (an inflated box hits the *neighbouring* glyphs). The probe
  measured both against the neighbours and concluded `EffectType.BOXED` had no defect — 0 collisions at pad 0,
  which is true and irrelevant. Two masks, two questions. And the answer turned out to be non-monotonic, so the
  first phrasing ("pads 2–5 are safe") was also wrong in a way the numbers were already showing.
- **A fix's own geometry can reintroduce the defect it fixed.** 1a⁗ replaced Swing's `TitledBorder` so the leader
  could land on a known line, and gave the replacement **rounded corners** to match the other chromes. The reader's
  verdict: the diagonals "do not meet border lines of subeditor because of the new rounded corners" — at x=0 and
  x=w−1 the border is an arc, not a line, so the tip lands in the gap the rounding left. The *vertical* geometry was
  shared through one value and the *horizontal extent of the straight run* was not. Sharing one number is not
  sharing the geometry: ask what else the two drawings have to agree about.
- **A probe proves a shape lands; only the eye says it is the right shape.** `spikes/tree1a4/` asserted that `BRACE`
  reached the frame's corners, and it did — but the brace was a single stem with a spine across the middle, and the
  reader's brace is *two* halves, each starting at a corner of the invoking token box. Every pixel assertion passed
  on a shape that was not the one being asked for. A geometric probe cannot review intent, so do not let a green
  probe shorten the eye's list.
- **When a mechanism "cannot work", check whether the thing blocking it is itself a choice.** 1a⁗ was told to make a
  node open scrolled to its member, reasoned that a scroll clamps to `content − viewport` and that a node fitting
  its cap therefore has no scroll room, and re-implemented the feature as elision. The reasoning was correct and the
  conclusion was wrong: the reader's answer was to **change the cap** — size a nested node to the excerpt of
  interest, and the scroll room exists. The blocking constraint was a default (`maxHeight`/`grow`), not a law. Worth
  the habit: when a requirement looks impossible, list what would have to be true for it to work before replacing it.
- **A number measured in a mock is the mock's number.** The 1a⁗ probe measured a titled border's drawn line at
  **7 px** below its bounds edge and the plan wrote 7 px into a constraint; the round measured **9 px** in the real
  component, because the caption font there comes from the editor scheme rather than from the probe's 11 pt sans.
  The *fact* was right (non-zero, `captionHeight / 2`, must be asked for) and the constant was wrong — and a
  hard-coded 7 would have been short at every font size the user picks. Constraint 9's rule generalises: a measured
  number is meaningless without the thing it was measured against, whether that is a width or a font.
- **Measure-before-settle recurs in every new check, not once per project.** 1a‴ learned it on collapse propagation
  and 1a⁗ learned it again on mouse reach: the first report of a session failed on the *doubly* nested node with the
  deepest component `null`, and the identical case passed later in the same session. The check guarded on the
  child's own `isShowing`, which is true while the *ancestor* chain is still being placed by the deferred inlay
  pass. Two habits fall out: a guard must cover the whole chain a deferred layout touches, and **the shape of a
  wrong answer identifies its cause** — a stolen event returns the thief's component, so `null` meant the check had
  aimed outside the container, not that anything was broken.
- **An option added beside another can be strictly worse than it, and a verdict can be read one notch too far.**
  Verdict 16 asked for a *no-header* chrome for the same-file case; 1a⁗ delivered `NodeChrome.NONE`, no header **and
  no border** — "strictly worse than plain … Verdict 16 only asked for absence of header, not absence of border".
  `PLAIN` already was the answer. Implement the verdict's literal scope; an extra step past it costs a round.
- **A measurement that says one option needs machinery and another does not is a design signal.** The 1a⁗ probe
  reported that `TITLED_BORDER` draws its line 7 px below its bounds edge and that the other chromes draw at 0. The
  plan filed that as a convenience — the zero-offset chromes "make the leader exact for free" — and then built an
  offset-aware border, an extended strip and a check that the two numbers agree, so the non-zero case could work
  too. The reader later scrapped that chrome, and the whole apparatus went with it. **When a probe finds that one
  alternative costs machinery and another costs nothing, that is the comparison, not a caveat on the result.**
- **A deletion list written from a plan describes surfaces; the compiler describes dependencies.** The cleanup
  pass's list named files by the bench each was *written for*, and two of them had outlived it — the throwaway
  `FileEditorProvider` that every later bench opens its tab in, and a text-anchor helper both survivors call. The
  first was caught by reading callers, the second by the compiler. **Grep for callers of every declaration in a
  file before deleting the file**, and treat "it belongs to phase N" as a hypothesis rather than a fact.
- **Check the code is in version control before deleting it.** Six sub-phases of work in `tree/` and `spike/` was
  untracked, and the plan had been quoting git history as the recovery path for its single largest risk. Deleting
  untracked code is not reversible; the one thing that did need recovering came back from `javap -c` on the
  previous build's bytecode, which worked only because a build happened to be lying around.
- Phase 0's own three bad criteria are recorded in `MultilineTreeFindings1.md` §6.3.

A spike that cannot fail measures nothing — a spike whose output nobody cross-checks measures only itself — a
spike that counts something the user is allowed to move must say which of them moved it — and a spike that
recomputes the geometry it is checking is comparing two implementations, not testing one.
