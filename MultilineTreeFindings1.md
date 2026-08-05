# Call-tree code viewer ("peek excerpt tree") — technical evaluation #1

Evaluation of a custom **read-only tree viewer** whose nodes are code excerpts from different files and
methods, depicting a caller→callee chain:

```
| /Project1/Path1/MainInvoker.cs
| public static class MainInvoker {
|    ...30 lines...
|    public void MainLoop() {
|        ...15 lines...
|        int someParam = SomeSysCall();
|        MidInvoker.InvokeOn(..., someParam,...);
|
╰->| /Project2/Processing/Invokers.cs
   | public static class MidInvoker {
   |     ...20 lines...
   |     public void InvokeOn(..., theParam,...) {
   ...
```

Requirements taken from the brief: tree of code-block nodes, excerpts only (the relevant parts), spanning
files and methods, **read-only** (preferably non-editable), **syntax highlighting present**. No
backward compatibility required — schema / highlighting / rendering may be rewritten.

> ### ⚠ Read §6 and §7 before acting on §0, §2, §4 or §5
>
> This is an evaluation written *before* any of it was measured, and its own later sections overturn its
> recommendation. **§0 and §2 recommend Arch A; the project builds Arch B** — decided by §6.2's semantic-colour
> measurement and confirmed by §7. §4's estimates and §5's spike list are historical: the spikes all ran
> (Phase 0 and 0b), and §7 supersedes them.
>
> **§1 (verified platform APIs), §3 (schema), §6 and §7 (measured results) are current and load-bearing.**
> The live plan is `TreeOfCode.md`; completed-phase history is `TreeOfCode.completed.md`.

---

## 0. TL;DR

- **Syntax highlighting is not the hard problem.** Rider ships a *frontend* C# lexer + syntax highlighter
  registered as a normal `lang.syntaxHighlighterFactory`, so keyword/string/comment/number colouring works
  in any editor over any document — including a synthetic one — with **no ReSharper backend involvement**.
  Verified in the install, see §1.
- Every other API this design needs **exists in 261** and is public: custom-drawn elision lines, fold
  conflict detection/cleanup, caret-safe folding, per-editor read-only, and remapped gutter line numbers.
- The **hard problem is the schema and the narration timeline**, not the rendering. Today structure and
  narration are fused (`Segment.highlights`), which is exactly why a step cannot span files.
- ~~Recommended: **Arch A** — one synthetic "story document" in one read-only editor~~ — **superseded, see
  §6.2.** The reasoning was that Arch A reuses the entire existing decoration stack verbatim, since all of it
  is already `Editor`-parameterised and would operate in one coordinate space. That held; what killed it is
  that a synthetic document gets **zero** semantic colour and its sub-regions cannot scroll independently.
  **The project builds Arch B.**
- Keep the current in-place spotlight as a second view mode. Tree view for call chains, in-place for
  "explain the method I'm looking at".

---

## 1. Verified platform facts (Rider RD-261.26222.60)

Method: indexed all 1067 jars in the install for the relevant classes, extracted the registration XML, and
ran `javap` over the platform jars for exact signatures.

| Question | Answer | Evidence |
|---|---|---|
| **Frontend C# highlighting without the backend?** | **YES** | `product.jar :: intellij.rider.languages.xml` →<br>`<fileType name="C#" extensions="cs" language="C#" implementationClass="…csharp.CSharpFileType"/>`<br>`<lang.syntaxHighlighterFactory language="C#" implementationClass="…csharp.CSharpHighlighterProvider"/>`<br>plus `product.jar :: …/csharp/lexer/CSharpLexer.class`, `…/csharp/CSharpSyntaxHighlighter.class` |
| Attach that highlighter to any editor | `EditorEx.setHighlighter(EditorHighlighter)`; build one with `EditorHighlighterFactory.createEditorHighlighter(project, "story.cs")` or `(project, fileType)` / `(project, virtualFile)` | javap `EditorHighlighterFactory`, `EditorEx` |
| Custom-drawn elision line (`⋯ 30 lines ⋯`) | **YES** `FoldingModel.addCustomLinesFolding(int, int, CustomFoldRegionRenderer)`; renderer is `calcWidthInPixels` / `calcHeightInPixels` / `paint(…, Graphics2D, Rectangle2D, TextAttributes)` — **the same shape as the existing `CalloutRenderer`**, plus `getContextMenuGroup` and `calcGutterIconRenderer` | javap `FoldingModel`, `CustomFoldRegionRenderer`, `CustomFoldRegion` |
| Fold-conflict pre-check and cleanup | **YES** `FoldingModelEx.intersectsRegion(int,int)`, `getRegionsOverlappingWith(int,int)`, `clearFoldRegions()`, `setFoldingEnabled(boolean)`, `rebuild()`, `createFoldRegion(start, end, placeholder, FoldingGroup, neverExpands)` | javap `FoldingModelEx` |
| Caret-versus-collapsed-region hazard | **solved by API** `FoldingModel.runBatchFoldingOperationDoNotCollapseCaret(Runnable)` | javap `FoldingModel` |
| Read-only editor | `EditorFactory.createViewer(Document, Project[, EditorKind])`, or `EditorEx.setViewer(true)`. **Never** `Document.setReadOnly` — that would block the user editing their own file | javap `EditorFactory`, `EditorEx` |
| Real line numbers on a synthetic document | **YES, public API** `EditorGutter.setLineNumberConverter(LineNumberConverter)` (and a 2-arg primary/additional overload). `LineNumberConverter` = `convert(Editor, int)` + `getMaxLineNumber(Editor)` | javap `EditorGutter`, `LineNumberConverter` |
| Arbitrary per-line gutter text (file labels, depth) | `EditorGutter.registerTextAnnotation(TextAnnotationGutterProvider[, EditorGutterAction])` — the VCS-annotate mechanism | javap `EditorGutter` |
| Live Swing component inside an editor | `EditorEmbeddedComponentManager.getInstance().addComponent(EditorEx, JComponent, Properties)` | javap, `intellij.platform.ide.impl.jar` |
| `EditorKind` values | `UNTYPED, MAIN_EDITOR, CONSOLE, PREVIEW, DIFF` | javap |
| Reference implementation for elision | `com.intellij.diff.tools.util.FoldingModelSupport` (+ `$Data$Block`, `$Data$Group`, `$DescriptionComputer`) — the diff viewer's "collapse unchanged lines", battle-tested in Rider | `intellij.platform.diff.impl.jar` |
| Other precedents in-product | `ImplementationViewComponent` (Quick Definition popup — real editor over a real file in a popup), `UsageInfo2UsageAdapter` (coloured one-line code excerpts in a tree) | `intellij.platform.lang.impl.jar`, `app.jar` |

**Still unverified (needs a runtime spike, not a jar):** whether Rider's ReSharper backend pushes
**semantic** highlighting (types vs. methods vs. fields vs. locals) to an editor that is not a
`FileEditor`. Lexer colouring is guaranteed by the registration above; semantic colouring is a bonus.

### Correction to `MultilineFindings1.md`

Two risks that document rated as the main folding hazards have direct API answers I did not know when
writing it:

- *"Fold regions may nest but not partially overlap → `addFoldRegion` returns null"* — real, but
  `FoldingModelEx.intersectsRegion` tests it up front, `getRegionsOverlappingWith` enumerates the
  conflicts, and `clearFoldRegions()` removes them wholesale. In an editor **we** created, the conflict
  problem disappears entirely: clear, then add.
- *"Caret vs. collapsed region"* — `runBatchFoldingOperationDoNotCollapseCaret` exists precisely for this.

Folding is therefore materially less risky than that document concluded. The one unverified folding
question that remains is whether Rider's backend re-pushes a folding model that drops ours — and that
question **does not arise at all** in an editor we own and that no `FileEditor` is attached to.

---

## 2. The three viable architectures

### Arch A — one synthetic "story document", one read-only editor · ~~recommended~~ **now the fallback (§6.2)**

Build a single in-memory document that is the concatenation of every frame's kept lines:

```
                                     ← LightVirtualFile("walkthrough.cs") ⇒ FileType C#
public static class MainInvoker {          ← frame 0, context range
    public void MainLoop() {               ← frame 0, context range
        int someParam = SomeSysCall();     ← frame 0, focus range
        MidInvoker.InvokeOn(…, someParam); ←
    public static class MidInvoker {       ← frame 1, indented by depth × N spaces
        public void InvokeOn(…, theParam)  ←
```

- **Highlighting**: `EditorEx.setHighlighter(EditorHighlighterFactory.getInstance()
  .createEditorHighlighter(project, "story.cs"))`. Do **not** rely on the plain
  `createViewer(document, project)` overload — only the `VirtualFile`/`FileType` overloads of
  `createEditor` install a highlighter automatically; for a synthetic document set it explicitly.
- **Line numbers**: `editor.gutter.setLineNumberConverter { _, visualLine -> lineMap[visualLine] }` — every
  row shows its **real** line number in its **real** file.
- **Elisions**: `addCustomLinesFolding` for `⋯ 30 lines ⋯`, drawn in the plugin's accent, with
  `neverExpands = false` so a curious reader can open one.
- **Tree indentation**: pad with **plain spaces**, never with `|` / `╰->` glyphs — spaces are lexer-safe,
  box-drawing characters are not valid C# and would derail the lexer. Draw the connectors as a
  `CustomHighlighterRenderer` over the left margin instead, which is what the existing
  `RelationArrowRenderer` already does for arrows.
- **Frame headers** (the `/Project2/Processing/Invokers.cs` lines): either a `TextAnnotationGutterProvider`
  column, or a block inlay per frame — the existing `CalloutRenderer` already draws exactly this kind of
  boxed label.

**Why this is the recommendation:** the entire existing decoration stack keeps working *unchanged*.
`highlightSubRange(editor, …)`, `addCallout`, `startTokenFlash`, `resolveToken`, `addArrow`,
`addPointerGutter`, `Palette`, all of `HighlightSettingsConfigurable` — every one of them takes an
`Editor` and offsets in that editor. One coordinate space means arrows between frames are just arrows,
and §2.9's inlay-versus-renderer routing problem is already solved for it.

**Limitations, stated honestly:**

- **Lexer-level colour only** — no semantic (ReSharper) colouring. Keywords, strings, comments, numbers,
  operators are coloured; type/method/field/local distinctions are not.
- **One file type per document.** A tree mixing C#, `.csproj` XML and JS cannot be highlighted correctly
  in one document. If mixed-language trees matter, that is the argument for Arch B.
- **Lexer state can smear across frame boundaries** (an unterminated string or an open `/*` in one
  excerpt bleeds into the next). Mitigate with a `\n// ─── frame ───\n` separator line; an open block
  comment is the one case that still smears.
- **A snapshot, not a live view.** Edits to the real files do not update the story. For a narrated
  walkthrough that is arguably correct, but it must be said out loud.
- **No goto-definition / hover** inside it. Recoverable cheaply: a gutter icon or click handler per frame
  that opens the real file at the real line — the pattern already exists as
  `EditorHighlighter.navigateTo(Endpoint)`, which since Phase 1a is also the `navigate` callback every
  `SpotlightRenderer` is constructed with.

**Effort: rendering ~4–6 days.** (Total including the schema rework — see §4.)

### Arch B — N real viewer editors over the real documents, stacked · **CHOSEN (§6.2, §7)**

One `createViewer(realDocument, project, EditorKind.PREVIEW)` per frame; in **our** editor call
`foldingModel.clearFoldRegions()` and then add ours (no conflict, because nothing else owns that editor's
folding model); size each to its content height; suppress scrollbars; lay them out in an indented `JPanel`
inside a `JBScrollPane`; draw connectors on a transparent overlay above the children.

- **Wins:** real *semantic* highlighting **if the backend cooperates** (the one open spike); correct line
  numbers for free; live sync with the user's edits; goto/hover likely functional; mixed languages
  trivially — each editor has its own file type; folding stays entirely inside editors we own, so none of
  §MultilineFindings1 §2's persistence or backend-sync risks apply.
- **Costs:** N editor lifecycles (`EditorFactory.releaseEditor` discipline); height/layout resync on every
  fold, font or theme change; focus and caret management across N editors; and the decoration stack needs a
  per-editor multiplexer — `tracked` / `trackedEditor` become per-editor maps. Moderate, not deep, since
  every renderer is already `Editor`-parameterised.
- **Effort: ~8–12 days**, plus the semantic-highlighting spike.

### Arch C — HTML/CSS/SVG tree in the existing JCEF sidebar · **not recommended**

Cheapest layout by far (the sidebar is already a web view with read-along word highlighting, and
click→`postMessage`→open-file is already wired). Colour would come from a JS tokenizer with CSS variables
derived from `EditorColorsScheme` so it tracks the theme.

Rejected because it ships **a second implementation of syntax highlighting**, which is precisely the trap
MoreUI.md §2.10 records about the settings preview ("a preview that computes its own answer is a second
implementation of the thing being previewed, and the day they disagree the preview is what gets
believed"). Fonts and metrics would differ from the editor, and a deep tree needs width the side tool
window does not have. ~4–5 days for something that will always look *almost* like the IDE.

*(Also considered and dropped: repurposing `UsageViewImpl` / `UsageInfo2UsageAdapter`. It gives a free
Rider-native tree with coloured code lines and click-to-navigate, but one line per node, Find-Usages
chrome we cannot remove, and no per-block active-glow rendering.)*

---

## 3. Schema — the part that is a genuine rewrite

The current shape fuses structure and narration: `Segment{file,start,end,highlights[]}`. A step therefore
cannot span files, and one file cannot host two steps in unrelated places. **Split them.**

```json
{
  "type": "set_plan",
  "title": "How someParam reaches SubInvoker",
  "language": "en",
  "view": "tree",
  "frames": [
    { "id": 0, "parent": null, "file": "/Project1/Path1/MainInvoker.cs",
      "keep": [ { "start": 3,  "end": 3,  "role": "context" },
                { "start": 34, "end": 34, "role": "context" },
                { "start": 50, "end": 51, "role": "focus"   } ],
      "linkFrom": 51 },
    { "id": 1, "parent": 0, "file": "/Project2/Processing/Invokers.cs",
      "keep": [ … ], "linkFrom": 88 }
  ],
  "steps": [
    { "frame": 0, "ranges": [ {"start":50,"end":51} ], "ttsText": "…", "note": "…", "token": "someParam" },
    { "frame": 1, "ranges": [ {"start":86,"end":88} ], "ttsText": "…" },
    { "frames": [ {"frame":0,"ranges":[…]}, {"frame":1,"ranges":[…]} ], "ttsText": "…" }
  ]
}
```

Design decisions and why:

1. **`frames` = structure, `steps` = timeline.** The tree stays still while narration walks it. A step can
   light up ranges in *several* frames at once — the original multi-file ask — and a frame can be revisited
   by non-adjacent steps.
2. **`keep`, not `elide`.** The agent lists what to show; the plugin elides the rest and picks the
   placeholder. Same division of labour as relations today: *the agent states semantics, the plugin picks
   the picture* (MoreUI §4.3 B3).
3. **`role: context | focus`** maps straight onto the existing `segmentDim` / `active` palette entries.
   The class and method header lines in the sketch are `context`.
4. **The agent must list the header lines itself — no PSI.** Deriving "the enclosing class and method
   declaration" automatically requires PSI or a backend structure query, and §4.4 rule 2 has already
   committed this project to *"locators are always text-search, never PSI"* for Rider parity. This is the
   one thing in the sketch that looks free and is not.
5. ~~**`parent` + `linkFrom`** (the line in the parent where the call appears) is all a connector elbow
   needs: parent anchor row → child frame's first row.~~ **Superseded — wrong in two ways, both measured in
   Phase 1** (`TreeOfCode.md` Phase 2 decision 1). There is no elbow: the chosen layout puts the child
   *inside* its parent at the call site, so nothing is routed. And **the call site cannot live on the parent
   frame** — the fixture's wide case is one `Dispatch` calling three handlers from three consecutive lines,
   and one `linkFrom` per frame cannot say which line goes with which child. It belongs to the **edge**, and
   it needs a **column extent, not only a row**, because two calls can share a line
   (`_pricing.Handle(order, verdict)` / `_audit.Handle(order, priced)`) and the layout boxes one of them. The
   token *text* is the locator to carry, since a column pair is not authorable under rule 2.
6. `frames[].file` joins the `WalkthroughStorage` relative-path rewrite (§4.4 rule 6).

**Knock-on effects:**

- `Protocol.kt` — rewritten.
- `Walkthrough` — `currentIndex` + `currentHighlightIndex` collapse to one `stepIndex`.
- `NarrationController` — gets **simpler**: `advanceAfterPlayback` becomes `stepIndex++`, and the whole
  two-level segment/highlight dance (`startAt`, `userAdvanceInternal`, `gotoHighlightInternal`) flattens.
  **Read "simpler" narrowly — it means *index bookkeeping only*.** It does not mean a step advance stops
  moving the visual attention point; every per-step decoration stays, and stays per-step. See
  `TreeOfCode.md` § "Correction carried forward", which exists because this sentence was read too broadly.
- `sidebar.js` — the flat two-level outline becomes a tree; the rebuild fingerprint at `:818-821` must
  cover the new fields or stale rows survive.
- `EditorHighlighter` — splits into "resolve ranges + palette" (reusable as-is) and "which editor / which
  document" (new). **Done in `TreeOfCode.md` Phase 1a**: `SpotlightRenderer` + `EditorHighlighter`.
- Skill docs, per §2.1's four-file rule: `plan-schema.md`, `segments.md`, `manifest.txt`, `VERSION`, plus
  the inline fallback in `ClaudeExplainer.buildPrompt`.

---

## 4. Effort and staging · *historical — superseded by `TreeOfCode.md`'s summary table*

*Kept because the Arch B row below is the baseline that plan's estimate is checked against. The "build the
story viewer against the existing schema first" staging was not taken: the architecture flipped instead.*

| Piece | Days |
|---|---|
| Schema + `Protocol.kt` + storage rewrite | 1–1.5 |
| `Walkthrough` / `NarrationController` flattening | 1–1.5 |
| Story-document builder (stitching, indentation, line map, elisions) | 1.5–2 |
| Story editor surface (viewer, highlighter, line-number converter, disposal, tab) | 1.5–2 |
| Decoration reuse + tree connector renderer | 1–1.5 |
| Sidebar tree outline | 1–1.5 |
| Settings + reset-to-defaults + skill docs | 1 |
| **Total (Arch A)** | **≈ 9–12** |

Arch B is the same list with the middle two rows replaced by per-editor layout and lifecycle: **≈ 12–16**.

**Staging that de-risks it.** Build the story viewer against the **existing** schema first — treat each
`Segment` as a frame, its `highlights` as steps, and `relations` with a cross-file endpoint as the parent
links. That yields a working, syntax-highlighted tree in **~5 days with zero schema churn**, and proves
the surface before the protocol is rewritten around it. Then do §3 once the viewer's real requirements are
known. This mirrors the project's own §6 build order: prove the prerequisite, then build the tier that
consumes it.

Keep the in-place spotlight. `"view": "editor" | "tree"` selects the surface from one schema — the tree is
better for a call chain, the in-place spotlight is better for "explain the method I am looking at", and the
in-place one is already built and good.

---

## 5. Spikes · *historical — all of these ran; results in §6 and §7*

*The "one blocking spike" for Arch B below is the one that decided the architecture. It ran as S5 (SKIPPED, no
solution) then as B5 (PASS, decisive). Its stated fallback — "accept lexer colour, at which point Arch A is
simply cheaper" — is what did **not** happen.*

**Arch A — one spike, ~30 minutes:** create a `LightVirtualFile("story.cs", stitchedText)`, a
`createViewer` editor, `setHighlighter(createEditorHighlighter(project, "story.cs"))`, show it, screenshot.
The registration in §1 proves the extension point exists; this proves the wiring produces colour on a
synthetic document. Everything else Arch A needs is already verified above.

**Arch B — one blocking spike:** does the ReSharper backend push **semantic** highlighting to a
`createViewer` editor over a real `.cs` document with no `FileEditor` attached? Probe by comparing the
document markup model (`DocumentMarkupModel.forDocument(doc, project, false)`) for a file open in a tab
versus one shown only in our viewer. If it does not, Arch B's main advantage over Arch A evaporates and the
fallback is either "briefly open the file in a preview tab to warm the markup, then copy it" or "accept
lexer colour", at which point Arch A is simply cheaper.

Per `verify-rendering-via-synthetic-set-plan` and `verify-swing-ui-without-sandbox`, do both by POSTing a
hand-written plan into a running sandbox and reading pixels — neither needs a full narration run.

---

## 6. Phase 0 spike results — measured, RD-261.26222.60

> **Outcome for the architecture choice:** §2 recommended Arch A. Phase 0's measurements plus a review of
> the built prototype **reversed that — Arch B is primary.** §6.2 is the reason: semantic highlighting,
> Arch B's one blocking spike per §5, is measured working in exactly Arch B's configuration and measured
> *absent* in Arch A's. Independent per-node scrolling, which the brief asks for, is impossible in Arch A at
> all. Arch A remains the proven first fallback. See `TreeOfCode.md` for the revised plan.

Ran `SpikeStoryEditorAction` (throwaway, `com.audioexplainer.spike`) over four sandbox launches.
Artefacts: `scratchpad/spike-story/run{1..3}/` + the final run — `report.txt` plus PNGs, all read as pixels.
**Verdict: G0 passes. Arch A works.** But three of the spike's own pass criteria were wrong on the first
run and are corrected below, because a plan built on a lenient criterion is worse than no plan.

### 6.1 What passed as predicted

| Probe | Result |
|---|---|
| **S1** (the gate) | **PASS.** `CSharpFileType` + `CSharpSyntaxHighlighter` resolve; `LexerEditorHighlighter` attaches to a synthetic document; **35 token types, 5 distinct foreground colours**. Rider's frontend C# lexer colours a document we invented, exactly as §1 predicted |
| **S1v** | **PASS, and a simplification.** A bare `EditorFactory.createDocument` + `createEditorHighlighter(project, "story.cs")` colours *identically* — 35 types, 5 colours. **Lexer colour needs no `VirtualFile` at all** |
| **S2** | **PASS.** Both `addCustomLinesFolding` regions installed and painted `⋯ N lines ⋯`; `isOffsetCollapsed` true |
| **S3** | **PASS.** `setLineNumberConverter` accepted; gutter shows `12,13,–,47…55, 8,9,–,61…66, 14,15,–,88…92`. A synthetic row returning `null` draws **blank, no crash** |
| **S4** | **PASS.** All five line roles (incl. the tree's new non-active-frame dim), 3/3 frame-header block inlays, 3 rail + `╰->` custom renderers, callout inlay, token flash — **all in a `createViewer` editor** |
| **S6a / S6c** | **PASS.** Dialog hosts it. And an **ordinary editor tab over a `LightVirtualFile` needs no `FileEditorProvider`** — `PsiAwareTextEditorImpl`, read-only via `LightVirtualFile.setWritable(false)`, fully coloured |

### 6.2 The semantic-colour result, which changes the architecture

§2 listed *"lexer-level colour only — no semantic (ReSharper) colouring"* as Arch A's first limitation, and
§1 left it as the one open runtime question. Measured, with the **same stitched story content** in three
configurations:

| Configuration | ReSharper semantic markers |
|---|---|
| Real file **on disk**, viewer only, **no `FileEditor`** | **84** — incl. `CSHARP_PARAMETER_IDENTIFIER`×14, `CSHARP_LOCAL_VARIABLE_IDENTIFIER`×4, `CSHARP_KEYWORD`×3, `CSHARP_METHOD_DECLARATION_IDENTIFIER`, `CSHARP_STATIC_METHOD_DECLARATION_IDENTIFIER`. **0 diagnostic keys, 0 error-stripe marks** |
| `LightVirtualFile`, viewer only | **0** |
| `LightVirtualFile`, **with** a real editor tab open | **0** |

Two conclusions, and the second is the actionable one:

1. **Semantic colour follows the file being real on disk — not the editor having a `FileEditor`.** The
   84-marker case had *no* `FileEditor`; the 0-marker cases include one *with* a tab. This is the opposite
   of what §5's spike design assumed it was testing.
2. **So Arch A should stitch the story into a real on-disk file, not a `LightVirtualFile`** — and then it
   plausibly gets ReSharper semantic colour for free. That deletes Arch A's headline limitation and, with
   it, Phase 5's "import `DocumentMarkupModel` highlighters from each source file and translate offsets"
   item, which was the expensive way to buy the same thing.

Also notable: **zero diagnostics on deliberately invalid stitched C# outside any .NET project.** No red
squiggles, no error stripes. The risk that a story file would be covered in analysis noise does not
materialise.

*Caveat, stated rather than buried:* the on-disk file was probed after ~4 s and the tab case after only
~1.4 s, which is thin for a backend round-trip. The on-disk/in-memory contrast is solid; "a tab does not
help a `LightVirtualFile`" needs a longer settle before it is load-bearing. The on-disk route itself is
proven only for a *viewer over a file that already existed* — Phase 1 must confirm it for a file the
plugin writes and immediately opens.

### 6.3 Corrections — three spike criteria that were wrong

- **S1b tested the wrong line, and the smear is real.** The first run read the token at the line *opening*
  the `/*` — whitespace there regardless — and put the hazard in the *last* frame, where nothing is
  downstream. Rebuilt with both hazards in the middle frame: an unterminated string is **harmless** (C#
  ends a regular string at EOL → `WHITE_SPACE`, clean), but an unclosed `/*` **taints the next frame's
  first content row**, `first token = C_STYLE_COMMENT`, **with and without separator rows**.
  So §2's prediction was right and its proposed mitigation is wrong: a `// ─── ───` separator cannot stop
  it, because a `//` line inside `/* … */` is still comment. **The stitcher must emit `*/` when an excerpt
  leaves a block comment open.** New Phase 2 builder rule.
- **S5 reported PASS on a count, on the wrong file.** `0 → 84` markers was scored as "semantic markup
  populated" while the control had 736 — and the file it found was `run1/story.cs`, *the spike's own
  output from a previous run*. Counting markers cannot distinguish colour from squiggles anyway. Now:
  prefers real user code, labels a synthetic fallback explicitly, classifies every marker by
  `getTextAttributesKey()` / `getErrorStripeTooltip()`, and **can no longer PASS on counts at all**.
  (The accident was productive — it is what produced 6.2.)
- **S6b's width answer was never stable.** Measured `0px` (before layout), then `784px` ("fits"), then
  `413px`, then `716px` against ~774px needed. Tool-window width is a property of where the user last
  dragged the splitter, not of the tree. **"It fits" was never a claim worth making; the editor tab is the
  host**, and the tool window is viable only with horizontal scrolling.
- **"Syntax highlighting present" was reported off the wrong screenshot.** S1's token dump (35 types, 5
  colours) and the two *undecorated* PNGs (`s6b-tool-window.png`, `s6c-real-tab.png`) do show fully coloured
  C#. But the S4 screenshot — the one the prototype window actually looked like — shows almost none, and
  that was cited as evidence of colour. Cause, and it is a design finding rather than a spike artefact:

  > **A dim role implemented as `foregroundColor` sits above the syntax layer and erases syntax colour.**

  `frameDim` covered both non-active frames and `contextDim` the active frame's non-focus rows, so **27 of
  29 rows were flattened to one grey**; only the 2 focus rows kept colour, because `active` sets *background*
  only. The comments that survived were the elision rows, painted by our own renderer, not the lexer.
  This is already documented behaviour of the shipping in-place spotlight ("*this flattens syntax colours on
  dimmed lines, which is the intended de-emphasise effect*", now the `SpotlightRenderer` KDoc) — where it is correct,
  because dimmed lines there are context nobody is reading. **In a tree it is wrong**: non-active nodes are
  the structure, and flattening them all makes the tree read as plain text. Node-level de-emphasis must act
  on **background**, border contrast, or a header cue — never foreground.

### 6.4 Smaller findings that constrain Phase 1

- **A `CustomFoldRegion` refuses to expand.** `shouldNeverExpand=true`, and `setExpanded(true)` leaves
  `expanded=false`. §2's "`neverExpands = false` so a curious reader can open one" is **false as built** —
  reader-openable elisions need a different mechanism (a plain `addFoldRegion` with placeholder text, or
  rebuilding the document).
- **A stitched document has nothing to fold**, so each elision needs a **placeholder row** for the region
  to cover. Written as `//  ⋯ 33 lines ⋯`, so a failed fold degrades to a readable comment rather than a
  blank line. Confirmed accidentally: the tool-window editor shares the *document* but not the dialog
  editor's folding model, so all three elisions rendered in the literal-comment form and read fine.
- **Decorations are per-editor, not per-document.** Two editors over one document showed our highlighters
  in neither the tool window nor the tab. Any multi-surface design applies the full decoration set per
  editor — which is exactly the argument for Phase 1a's `SpotlightRenderer` split.
- **Indentation at 4 spaces/depth is too weak to read as structure.** The frame header bands and the rails
  carry essentially all the tree signal. Phase 1 should test a larger indent unit or a pixel offset.
- **Rider stamps a "Reader Mode" badge and button** on our viewer, and the root frame gets no rail (depth 0
  opens no left margin). Both cosmetic, both Phase 1.

---

## 7. Phase 0b spike results — Arch B, measured, RD-261.26222.60

> **Outcome: G0b passes.** Ran `SpikeNodeTreeAction` over the `fixtures/TreeSpike` solution — 12 node
> editors across 4 trees (3-deep chain ×2 overlay modes, same-file pair, one-parent-three-children).
> Report: `scratchpad/spike-nodetree/report.txt`, 18 pass / 0 fail. **One of those passes was worthless and
> is retracted below** (B3b), in the same spirit as §6.3.

### 7.1 B1 — folding on a real document: ours survive, but we do not own the model

§1 claimed the backend-folding question "does not arise at all in an editor we own and that no `FileEditor`
is attached to." **That is wrong, and the correction matters more than the pass does.**

| Sample | Ours still collapsed | Foreign regions present |
|---|---|---|
| t≈1.5 s | 9/9, 9/9, 6/6, 12/12 | 59, 59, 50, 19 |
| t≈6 s | unchanged | unchanged |
| t≈21 s (settled) | unchanged | unchanged |
| after a document edit (daemon re-runs) | unchanged | unchanged |

So: **the daemon does push its own folding model into a viewer-only editor over a real on-disk document**
— tens of regions arrive *after* our `clearFoldRegions()`. But it **adds rather than replaces**: not one of
our regions was dropped or re-expanded, across four samples including a live document edit.

**And the relationship is contended, not peaceful.** The sandbox log carries **52** warnings during the run,
none of them ours:

```
WARN - #c.j.r.d.h.f.FrontendEditorFoldingModelAdapter -
       Unable to insert (659, 3575) in the folding tree. Maybe the duplicate range already/still exist.
```

That is Rider's frontend folding adapter **trying to install its own regions into our editors and being
refused**, because our gap-folds already occupy overlapping ranges. So the true picture is not "it adds
alongside ours" — it is "both push, ours are already there, and the backend's lose the collision." Which is
why only 19–59 foreign regions landed rather than a full set. Benign here, and our regions are the ones that
survive, but Phase 1 should expect this log noise and not mistake it for a bug of ours.

Consequences for Phase 1, none of which are "add a re-assert loop":

- A node's folding model is **shared with the platform**, so `clearFoldRegions()` at build time is not a
  guarantee of anything later. Never enumerate `allFoldRegions` expecting only ours — hold identities.
- **Node height is not ours alone to compute.** Foreign folds change visual line count, so height resync
  must be driven by a `FoldingListener`, not assumed static after construction.
- A foreign fold *inside* a kept range could hide content the plan meant to show. Not observed in the
  fixture, but it is reachable and Phase 1 should decide deliberately.

### 7.2 B5 — semantic colour, settled: decisive, and §6.2's caveat is closed

Per node, at ~21 s, **folded and decorated, twelve editors at once**:

| Document | ReSharper.* colour markers | Distinct keys | Diagnostic-looking keys |
|---|---|---|---|
| `MainInvoker.cs` | 182 | 29 | 1 |
| `Invokers.cs` | 321 | 29 | 2 |
| `SubInvoker.cs` | 302 | 30 | 1 |
| `Dispatcher.cs` | 185 | 28 | **0** |
| three handler files | 78–96 | 23–27 | 1 |

Categories are richly distinguished — `CSHARP_LOCAL_VARIABLE_IDENTIFIER`, `CSHARP_PARAMETER_IDENTIFIER`,
`CSHARP_STATIC_METHOD_CALL_IDENTIFIER`, `CSHARP_RECORD_STRUCT_IDENTIFIER`, `CSHARP_ENUM_IDENTIFIER`,
`CSHARP_EXTENSION_METHOD_CALL_IDENTIFIER`, `CSHARP_FIELD_IDENTIFIER`, `CSHARP_NAMESPACE_IDENTIFIER` — and
574–867 distinct colours in each node's own content. §6.2's "thin settle" caveat no longer applies.

**Bonus nobody costed: ReSharper's type-hint inlays render too.** `var normalised :Money` is visible in the
capture. A node view is not merely coloured, it carries the backend's inlay hints.

### 7.3 B2 / B6 — the host works and nothing leaks

- A `JPanel` of N editors opens in a **first-class editor tab** via a `FileEditorProvider` +
  `FileEditorPolicy.HIDE_DEFAULT_EDITOR`, over a `LightVirtualFile` subclass carrying the payload. S6c's
  free tab does not transfer to Arch B, but the paid version is ~90 lines.
- **15–19 ms per node**; 12 nodes built in 57–71 ms per tree. `getAllEditors()` went 4 → 16 → **4**.
  Closing the tab fires `FileEditor.dispose()`, which released its editors. Phase 5's "very large trees"
  worry now has a number: 20 nodes is ≈350 ms, not a reason for lazy construction.

### 7.4 B4 — sizing measured, routing not

Folding earns its keep: folded node heights **286–550 px** against unfolded **858–3740 px**, a 5–8× saving.

**The 220 px cap chosen for the probe was too aggressive — all 12 nodes exceeded it**, so grow-to-content
never got compared against cap-and-scroll. A cap nearer **600 px** would let most fixture nodes show whole;
that is Phase 1c's number to settle, now with real heights to settle it from.

The synthetic wheel dispatch moved nothing and proves nothing — `Component.dispatchEvent` does not
reproduce AWT's real delivery. **Per-node scrolling was confirmed by hand instead**, in the live sandbox:
nodes scroll independently and the connectors track the scroll.

### 7.5 B3b — retracted. The automated overlay test could not fail

**Reported PASS; the pass is worthless.** Two independent defects:

1. `captureComponent` calls `comp.paint(g)`, which repaints the **whole hierarchy** into the image. A stale
   overlay is by definition something a *full* repaint fixes, so the capture can never show one. This is the
   exact flaw caught and fixed in B3a's first version — and not carried across to the in-IDE probe.
2. `ScrollingModel.scrollVertically` applies **asynchronously**. The before/after PNGs are byte-identical
   (md5), so the scroll had not even happened when the second capture was taken.

The giveaway was in the numbers and should have been read as a failure of the test: `chain-layered` and
`chain-paintchildren` both reported **2176 → 2176** connector pixels, when B3a had already proven
`paintChildren` loses its overlay. Two mechanisms with opposite behaviour cannot produce identical numbers.

What *is* still known, and it is enough:

- **B3a settled the mechanism** decisively and falsifiably (1444 → 1444 layered, 1444 → 3 `paintChildren`).
- The `settled` capture, taken 15 s later, does show node 0 scrolled **with its connector intact** in the
  layered tree — corroboration, not measurement.
- Live, by hand: connectors render over real editor content and follow scrolling.

A correct in-IDE version would use `Robot` screen capture as B3a does, and wait for the scroll to apply.
Not worth a sandbox cycle: the mechanism question is answered.

### 7.6 Smaller findings

- **The active-node tint rendered dark red**, reading as an error band rather than a highlight:
  `JBColor.namedColor("Focus.color", …)` resolves to something reddish in this theme. Phase 1 must take the
  accent from `AudioExplainerSettings` as the shipping decoration code already does, not from named theme
  keys. Finding 2's "background, never foreground" rule held up; the *choice of colour* did not.
- **Reader-openable elisions work.** Plain `addFoldRegion` with a `⋯ N lines ⋯` placeholder renders with a
  gutter chevron and expands — the alternative finding 3 called for after `CustomFoldRegion` refused.
- **Real gutter line numbers for free**, no converter, as predicted for Arch B.
- Text-anchor locators (never line numbers, never PSI) resolved every node across 7 files.
