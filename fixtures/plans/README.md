# `fixtures/plans/` — the hand-written acceptance plans

Phase 2's acceptance test: real plans over `fixtures/TreeSpike`, loaded into a running sandbox. They are
written against `src/main/resources/claude-skill/docs/plan-schema.md` by hand, so a plan that does not load is
either a real defect or a doc that does not say what it means — both worth knowing before an agent writes one.

**Every plan renders as a tree of code.** There is no `view` field to author: round 3 judged a one-frame scene
rendered as a one-node tree as good as the in-place spotlight it replaced, so that surface and the two
`*-editor.json` control plans are deleted.

| Plan | What it is there to prove |
|---|---|
| `scenes-tour.json` | **The scene model.** Five frames in **three scenes**, ten steps, **three rebuilds**. Scene 0 is `Dispatcher` with `ValidationHandler` nested in it; scene 1 is `PricingHandler` with `MidInvoker` nested in it; scene 2 is `SubInvoker.Apply` **alone** — one frame, one node, which is the shape every *"explain this method"* plan now takes. Step 10 deliberately **returns to scene 0**, so a revisit is judged rather than assumed. Note `PricingHandler`: a nested child in `tree-links.json` and a root here, because a frame's place is a property of its scene and not of the file. Each scene is inside the line budget on its own (28 / 26 / 19) where the five frames together are 73 |
| `single-frame.json` | **The commonest shape there is**, and the case that retired the second surface. One frame, three steps, one same-editor link (a `TOKEN` × `LINE` one — round 6 made it the same drawing as a portal, with the same palette and the same hover). It was half a judged pair; round 3's verdict was *"one-node tree looks as good"*, so what is left is the regression case for a one-node tree |
| `link-fan.json` | **The routing plan**, and the one thing `tree-links.json` cannot show. **Six steps, five links each**: five token links off line 18 of `AuditHandler` all **diving**, the same five off line 31 all **rising**, five **line-end** links off line 54 of `Invokers` (no endpoint names a token, so every end runs along the centre of its row — five runs on one row, which is what round 8's centre offsets separate), **three same-editor links and two portals off one line** at line level (68) and again at token level (72) for the mixed lane layout, and round 8's **staircase** (step 6, `SubInvoker` 87–100): five links off five *different* pairs of rows, 49, 59, 78 and 96 columns wide plus a portal, which is the only step where per-lane routing is visible as such. Two of the diving five and two of the rising five land on **one row at the far end**, distinguished only by which token they name — that pair is what round 7's `pairKey` collapsed, costing those steps their fifth link. **Three root nodes and no nesting anywhere in it**, deliberately: a nested child is a block inlay, and a leg spanning one has to be routed clear of it — correct, and exactly what would make a routing fixture unreadable. 79 kept lines against a budget of 60 — over it since K5 measured the viewport, and deliberately so: what this plan is for is routing, and the fold keeps the frames a step does not touch out of the way. Each step's active range covers its own links' **whole span** for the same reason: the callout is docked above the first active line, and a link reaching above it would be routed round the box rather than into its slot |
| `tree-links.json` | **The regression control, and the plan every portal round is judged on.** `Dispatcher.Dispatch` with its three handler siblings, one of which nests `MidInvoker.InvokeOn` a level deeper — a sibling row *and* a grandchild in one plan. Eleven steps, six projected segments, one **multi-frame** step that is also a **revisit** of frame 0. Its reason to exist is `relations` that cross frames: parent → child ×3, sibling → sibling *drawn from both ends* (`→` outgoing in one node, `←` incoming in the other), child → grandchild, and one aimed at a file **no frame holds**. Two steps are there for the drawing rather than the narration: **step 2 carries three portal links at once** in one node (one per handler, lanes 0/1/2, all diving into their children — which is where three links are judged separable by colour alone, and where the fan puts three rails 3 px apart in three portal-wide lanes), and **step 7 carries three links of two different joins pointing opposite ways** — the verdict arriving by portal from the sibling above, the subtotal leaving by portal for the grandchild below, and the 38→39 relation staying in this editor as a drawn line. That step is the only case that can catch a half reading its direction backwards, and since round 6 it is also the *"same editor-line and out-of editor links nearby should have different colors"* case. With one control: a sibling carrying no relations at all. **It mentions no `scene`**, so it must still load as `scenes=1 rebuilds=0` |
| `plan2-occurrence.json` | **`occurrence`, and nothing else.** One frame, seven steps, every one anchored on a token appearing more than once in its own range — so the count is the only thing deciding which glyph is marked. Two pairs make that falsifiable rather than plausible (occurrences 1 and 2 of `running` on one line, 1 and 2 of the dotted `guarded.Amount` on another); step 5 spans two lines, which separates *counted across the range* from *counted per line*; the last step asks for a fifth `running` where four exist, so the drop sits beside the hits |
| `plan3-unresolvable.json` | **The `token anchor dropped` path.** Five ways an anchor fails — absent, wrong case, present but outside the step's ranges, blank after trimming, and one that resolves only on the substring pass — each beside a control where the same mechanism succeeds. It opens and closes on a resolving step, so a run that silently stopped pulsing partway does not read like a run where every drop behaved. What is judged: a dropped anchor costs the **pulse** and nothing else |
| `plan4-beats.json` | **B1b, and the one fixture that must be *played* rather than stepped** — a beat carrying a `say` fires off the webview's playback clock, so Next/Previous shows the code and no pulse. Seven steps: three beats on three lines; three beats on **one** line, which is the verbose case one anchor shape costs; **the `e.g.` step**, where the splitter cuts `"If it's missing, e.g."` into its own piece so a beat counted by sentence *number* would land on the wrong words; two beats inside one spoken sentence, where only the first may fire; **one beat with no `say`**, which is the commonest shape and must behave exactly as a bare `token` did; a **five-line callout** (3-line ellipsised note over a 2-line example, the tallest the caps allow without a pointer block); and the **legacy shape** — a bare `token` over two ranges with the token in the *second*, which resolved to nothing before the whole-span fallback |

## Loading one

`Tools ▸ Audio Explainer ▸ Fixture plans (dev)` — one entry per plan. It reads the file through the same `Plan`
serializer, calls `setPlan` on a **pooled thread** (the thread the HTTP responder uses), and reports
`frames n/m`, `scenes n (m rebuild(s))`, `steps n/m`, projected segments and the issue list in a balloon — the
same content a `set_plan` reply carries. Next/Previous step are there too, so a round can judge what is *drawn*
without a TTS server.

Three entries load a plan **mutated in memory**, so the file on disk stays correct:

- `5 — Bad call line` breaks `tree-links.json`'s frame 2 `callLine` to a line its parent does not keep.
- `6 — Parent in another scene` moves `scenes-tour.json`'s frame 3 into scene 2, where its parent has no node.
- `7 — Over the line budget` widens `tree-links.json`'s **root** frame to keep the rest of its file, 88 lines
  against a cap of 80. Every plan above is deliberately *under* the cap, so this is the only entry that makes
  `enforceLineBudget` run at all.

## Posting one

Still works and still drives the same code; it is simply no longer the way in.

```powershell
powershell -ExecutionPolicy Bypass `
  -File src/main/resources/claude-skill/scripts/explainer.ps1 plan fixtures/plans/tree-links.json
```

## What a scene change actually does

A step whose scene differs from the one on screen calls `TreePlanView.mountScene`, which goes through
`CodeNestView.setTree` — every node released, the new set built. The reader's own state inside the scene
(a collapsed nesting, a dragged node height) does **not** survive, because the node views do not. A revisited
scene is rebuilt, not restored, and pays for both crossings. Nothing is cached; caching a nest per scene would
keep N editors alive per scene, which is the disposal risk Phase 4 owns.

## What a cross-frame link actually does

**Every relation is one link, and a link is two independent choices** (round 6 — `LinkArt`):

| | joined by a **line** (same editor) | joined by a **portal** (different editors) |
|---|---|---|
| anchored on a **token** | tint, rail, lane, rail, tint | tint, rail, **portal** |
| anchored on a **line end** | text end, row middle, lane, row middle, text end | text end, row middle, `∩`/`∪`, rail, **portal** |

Everything after the rail is one drawing, so the finish into a portal looks the same however the link started —
and a line end reaches that rail in the **last 26 px** before its turn, so how long its row is cannot change
what the finish looks like. The **anchor** is decided per *end*, by whether that end's `token` resolves on its
line — which is why nearly every endpoint here names one. The **join** is decided by whether the far end is a
line of the same document near enough to reach with a leg; two ends in one editor more than 40 rows apart get a
portal each too. The **lane** (0–4) is decided once per step across every editor on screen and is the colour and
the column at once; the **rail** is counted separately, per row and per side, because two ends contend for one
only when they leave the same row in the same direction. A lane is as wide as what it carries — 4 px for a
same-editor vertical, a whole portal for a portal — and same-editor lanes go innermost, so five same-editor
links need 52 px of column where five portals need 152. A same-editor link can never come out the same colour
as a portal beside it, and since round 7 it can never be routed through one either.

Since round 8 each lane also carries **its own** clearance rather than the step's widest, and the columns are
filled in ascending order of it — within a join, never across one, so portals stay outside. Column order is
therefore not lane order and two links may cross, which is allowed; what may not happen is two verticals on
one x, and the gap between consecutive *columns* is what rules that out.

Both ends of a link carry one colour and one hover: brightening either brightens both, in both editors. A
click on either lands the caret on the far end's token — **within the mounted scene only**, because a target
in another scene is not on screen. That falls back to opening the file, which is acceptable for a click and is
why `plan-schema.md` tells the agent to keep both ends of a relation in one scene. A target **no frame holds**
opens as a file too, which is why `tree-links.json` deliberately points at one. A relation that leaves the file
also keeps its line of prose in the callout, because that may be the only place its words can live.

What was missing originally was never the behaviour — it was the affordance, because a node sets
`isLineMarkerAreaShown = false` and the old gutter icon was therefore created and never drawn. The numbered
chip that replaced it was rejected on sight, the connector that replaced *that* crossed the code it was
annotating, and five reshapes followed before the portal pair was accepted. The shape it settled on is
in `TreeOfCode.rules.md` under *The link system's shape*.

**Nearly every relation endpoint here names a `token`**, and that is load-bearing rather than tidy: the token
is what gets tinted, and it is what a click lands the caret on. Four of the endpoints leaned on `symbol`
alone and were given one; every endpoint across the four plans is verified as a literal substring of the line it
points at, at the stated `occurrence`, and every one lands on a line its frame `keep`s. `link-fan.json` uses
that deliberately: two of its links land on **one row** distinguished only by which token they name, and one
pair uses `occurrence` alone.

`tree-links.json` is where the *cross-frame* forms are judged and `link-fan.json` where the *routing* is;
`tree-links.json` has no step with more than three links. `link-fan.json` has four relations that leave their
own file — the two portals on each of steps 4 and 5, and one on step 6 — and none anywhere else, so a step's
lane layout is a property the fixture controls rather than stumbles into.

A relation with **both** ends in one file but more than 40 rows apart gets a portal at each end too, both in
the one node. Nothing about a portal needs two editors; what it needs is two ends a drawn leg cannot join.

## Keeping them honest

```bash
java -cp "…" PlanFileProbe fixtures/plans/*.json
```

`probes/PlanFileProbe.java` runs the real loader over every plan and fails on any error or warn, so a
mis-numbered `keep` range costs three seconds instead of a four-minute sandbox launch. It reports the scene
grouping and flags any scene over the line budget, and it asserts that **every step resolves to a scene** —
a step that does not would mount nothing and paint into an empty tree.

It is structure only: whether line 86 really holds that call is the VFS's answer, and a line number that is
wrong but still inside `keep` is structurally fine. The tree view is what shows it.
