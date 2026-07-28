# MoreUI — richer explanation-view details (draft memo)

> Status: **exploratory draft, NOT source of truth.** Scratch design notes for going beyond
> whole-line highlighting. Nothing here is committed; field names, percentages, and shapes are
> illustrative. Two axes throughout:
> - **Axis A — rendering** (what the IntelliJ editor can draw). See Part A.
> - **Axis B — authoring data** (what the explainer agent must emit to drive it). See Part B.
>
> Most of the work for anything richer than today is Axis B, not Axis A.

---

## Part A — the four UI features (rendering)

Baseline today (`EditorHighlighter.kt`): whole-line `RangeHighlighter`s on `editor.markupModel`
(`LINES_IN_RANGE`), four layers (dim / segmentDim / gap / active-gold), gap gutter bar via
`LineMarkerRenderer`. Driven by `WalkthroughListener.onSegment/onHighlight` marshalled to the EDT;
all decorations tracked in `tracked` and torn down in `clearTracked()`.

Everything below operates on the **frontend `Editor`** (the same object today's highlighting drives),
so it should work in Rider too. The one Rider-fragile technique is PSI — avoid it (see Part B).

| # | Feature | API | Effort | Hooks into |
|---|---------|-----|--------|-----------|
| A1 | **Gutter step badge** (numbered / speaker icon on the active highlight; click = replay) | `RangeHighlighter.setGutterIconRenderer(GutterIconRenderer)` | Low | add in `onHighlight`; dispose in `clearTracked` |
| A2 | **Docked callout / comment box between code lines** | `editor.inlayModel.addBlockElement(offset, relatesToPrecedingText=true, showAbove=true, priority, renderer)` + `EditorCustomElementRenderer` | Medium | create on active highlight; track like `tracked`; dispose the `Inlay` |
| A3 | **Token flash / pulse + exact-range highlight** | `HighlighterTargetArea.EXACT_RANGE` highlighter animated by `com.intellij.util.Alarm` | Medium (rendering trivial; **locating the token is Axis B**) | `onHighlight`; needs a token anchor |
| A4 | **Arrow pointer** | cheap: `JBPopupFactory` balloon (built-in pointer). Real drawn arrow: `RangeHighlighter.setCustomRenderer(CustomHighlighterRenderer)` painting in editor coords | Low / Medium | custom renderer scrolls + clears with the highlighter |

**Disposal discipline** is the one new rule: inlays, custom renderers, gutter icons, and animation
timers all need to be tracked next to `tracked` and cleared in `clearHighlights()` /
`clearTracked()`, plus cancelled on `onStatus(STOPPED)` and editor dispose.

**Recommended A-sequence:** A1 (no data change) → A2 (reuses existing `explanation`) → A3+A4
(gated on Axis B anchors).

---

## Part B — enriching the explanation-creation workflow (Axis B)

### B.0 How a walkthrough is authored today

```
ExplainThisAction
  └─ ClaudeExplainer.explain
       ├─ buildPrompt(): embeds an INLINE schema string (lines ~187-192) + context JSON,
       │                 and points the agent at docs/explain-at-cursor.md
       └─ runClaude(): headless `claude -p`; agent reads code, writes set_plan JSON to a
                       temp file, POSTs to the loopback server
ExplainerServer.dispatchAgentMessage("set_plan")
  └─ json.decodeFromJsonElement<Plan>(obj)   // ignoreUnknownKeys = true  ← forgiving
       └─ walkthrough.setPlan(...) → fires WalkthroughListener
            ├─ EditorHighlighter (spotlight)
            └─ NarrationController (audio + per-highlight advance on `playback_complete`)
```

Data model (`Protocol.kt`): `Plan { title, language, segments[] }` → `Segment { id, file, start,
end, title, explanation, highlights[] }` → `Highlight { start, end, ttsText, explanation? }`.
Everything is **1-based, inclusive, line-only** — no columns, no token identity, no relationships.

### B.1 Enabling facts (why this is tractable)

1. **Additive & forward-compatible.** New optional fields with defaults decode cleanly on old IDEs
   (`ignoreUnknownKeys`) and old saved files stay valid (defaults). Every idea below is *opt-in*.
2. **The plugin must render a bare plan exactly as today** when none of the new fields are present.
   Richness is something the agent *adds where it helps*, never a requirement.
3. **Locator reliability is the whole game.** Anything token/column-precise depends on the agent
   emitting a correct anchor. Standardize on a **text-search locator**, resolved on the highlight's
   own line range — Rider-safe, no PSI. The plugin validates and *silently drops* bad anchors, the
   same way `EditorHighlighter` already coerces out-of-range lines.
4. **Timing has three fidelity tiers** — pick the lowest that works for a feature:
   - (a) **per-highlight** — what exists today (`advanceAfterPlayback` on `playback_complete`).
   - (b) **per-sentence** — *already available for free*: `TtsService` chunks carry `sentenceIndex` /
     `sentenceWords` (see `NarrationController.narrateCurrent` → `onChunk`). A beat can fire when a
     given sentence starts.
   - (c) **per-percent** — needs a NEW webview→plugin progress channel (periodic playback ticks).
     Only reach for this for true fine-grained animation.
5. **Two schema mirrors** (`buildPrompt` inline string + skill docs) must move together. As the
   schema grows, consider extracting ONE canonical schema doc that the prompt references, to stop
   drift. Field-name mistakes are already the #1 failure mode (see `explain-at-cursor.md` reminders).

### B.2 The enrichment spectrum (simple → complex)

Ordered by authoring risk/complexity. Each tier names the schema shape (illustrative), what the
agent must produce, which Part-A feature it feeds, and the main risk.

---

#### Tier B1 — Token anchor  ·  feeds A3 (flash) / A2 (precise callout)  ·  **low**

Add an optional anchor to a highlight so the plugin can find a *specific identifier*, not just lines.

```jsonc
// Highlight, extended:
{ "start": 42, "end": 45, "ttsText": "…", "explanation": "…",
  "token": "customerId", "occurrence": 1 }   // occurrence optional; default = first match on the range
```

- Agent produces just a **string that appears in the highlighted lines**. Cheap and reliable.
- Plugin resolves offset by text search over `[start,end]` (never PSI). Not found → no flash, no error.
- The user's `{ token, start:"25%", duration:"35%" }` shape splits in two:
  - **B1a (do first):** ignore timing — flash the token for a fixed ~700 ms pulse when its highlight
    goes active. Agent supplies only `token`. No new infrastructure.
  - **B1b (defer):** honor `start`/`duration` as a fraction of the highlight's audio. Needs timing
    tier (b) or (c). Map to **sentence index** first (tier b) before percentages (tier c).

Risk: repeated token on a line (mitigate with `occurrence`); low overall.

---

#### Tier B2 — In-code notes  ·  feeds A2 (block-inlay callout)  ·  **low**

The terse `explanation` (2-5 word sidebar label) is too short for an in-editor callout. Either reuse
it, or add a distinct sentence-length note:

```jsonc
{ "start": 42, "end": 45, "ttsText": "…", "explanation": "Retry budget",
  "note": "Capped at 3 so a broken transaction can't loop forever." }
```

- Anchor = the highlight's line (already known), or a `token` (B1) for inline placement.
- Mostly a skill instruction: "write a `note` when a one-line aside in the code margin would help."
- Cheapest high-value pairing with A2.

---

#### Tier B3 — Relationships / arrows  ·  feeds A4  ·  **medium–high**

The "from-to / back-and-forth / outside-inside" idea. Model a relation as a **kind + two endpoints**,
where an endpoint is a locator, and let the *plugin* choose the rendering based on what's on screen.

```jsonc
// Segment, extended with a relations[] list (per-segment is the natural home for v1):
"relations": [
  { "kind": "flow",      "from": { "line": 44, "token": "raw" },
                          "to":   { "line": 51, "token": "parsed" } },
  { "kind": "backforth", "from": { "line": 60 }, "to": { "line": 72 } },
  { "kind": "outside",   "from": { "line": 45, "token": "rate" },
                          "to":   { "file": "Pricing/RateTable.cs", "symbol": "RateTable.Lookup" },
                          "note": "value comes from RateTable.Lookup()" }
]
```

Endpoint locator = `{ file?, line, token?, symbol? }` — `file` defaults to the segment's file;
`token` makes it precise, else the line; `symbol` is a human label for off-screen targets.

Rendering rule (**agent describes semantics, plugin picks the picture**):
- **inside** (both endpoints visible in the same file/segment) → draw a real arrow via a
  `CustomHighlighterRenderer` (A4). `flow` = single arrowhead A→B; `backforth` = double / animated
  pulse both ways.
- **outside** (target is another file / off-screen / a `symbol`) → you *can't* point an on-screen
  arrow at it, so render a **gutter arrow + callout** ("← from RateTable.Lookup()") that navigates on
  click (reuse `EditorHighlighter.openEditor`). This is exactly the inside/outside distinction.

Risks (why this is the jump in difficulty):
- Agent must emit **accurate line/token pairs** for *both* ends — more hallucination surface than
  prose. Plugin must validate each endpoint (line in doc range, token found) and drop invalid
  relations silently.
- Arrows only read well when endpoints are close and both visible; far-apart or cross-file relations
  should degrade to the callout form rather than drawing arrows across a scrolled viewport.
- Save/load: endpoints carrying a `file` need the same relative-path rewriting that `Segment.file`
  already gets in `WalkthroughStorage`.

---

#### Tier B4 — Example blocks  ·  feeds A2 / sidebar  ·  **low (data) / medium (render)**

Optional illustrative snippet or input→output pair on a highlight or segment:

```jsonc
{ "start": 42, "end": 45, "ttsText": "…",
  "example": "Parse(\"2024-01\") → DateOnly(2024, 1, 1)" }   // markdown string; may use ``` fences
```

- Agents are *good* at examples, so authoring cost is low. Keep it a **markdown string** for v1
  (not a structured `{input,output}`) — simplest to author and to render.
- Render in the A2 block-inlay callout (monospace mini-box under the line) or in the sidebar panel.
- Good "second thing to build" once the A2 callout renderer exists.

---

#### Tier B5 — Choreographed / animated designs  ·  needs a timeline  ·  **high (spike)**

The "possibly animated" end: an arrow that draws itself, a token that pulses on the word being
spoken, stepping through an example's evaluation in time with the narration.

- Requires a per-highlight **beat timeline** + a scheduler synced to audio.
- Enabling primitive already exists: **sentence-index chunks** (`onChunk` → `sentenceIndex`). Tag a
  beat to a sentence within the `ttsText` and fire it when that sentence starts (timing tier b) — far
  cheaper and more robust than percentages (tier c, which needs a new progress channel).
- Illustrative shape (do NOT build yet): `"beats": [ { "at": {"sentence": 1}, "do": "flash",
  "token": "rate" }, { "at": {"sentence": 2}, "do": "arrow", "rel": 0 } ]`.
- Treat as a research spike after B1–B4 land. High complexity; least certain payoff.

### B.3 Cross-cutting decisions to make before building any tier

1. **One canonical schema doc.** Before adding fields, de-duplicate the schema (`buildPrompt` inline
   string vs. skill docs) so growth doesn't drift. Field-name errors are already the top failure.
2. **Locators are always text-search, never PSI** — for Rider parity. Standard locator shape
   `{ line, token?, occurrence? }` reused by B1/B2/B3.
3. **Validate-and-drop** every new anchor/endpoint in the plugin, mirroring the existing range
   coercion. A wrong line number must degrade gracefully, never break the render.
4. **Keep everything optional and sparse.** More required structure = more the agent gets wrong +
   more tokens/latency per Explain. Let richness be additive where it earns its place.
5. **Timing: climb tiers (a)→(b)→(c) only as needed.** Prefer per-sentence (free) over per-percent
   (new channel).
6. **Save/load parity.** New `file`-bearing fields (B3 endpoints) need the relative-path rewrite that
   `WalkthroughStorage` already applies to `Segment.file`.

### B.4 Suggested build order (pairs Axis A with Axis B)

1. **A1** gutter step badge — no schema change; proves the disposal/tracking discipline.
2. **B1a + A3** — `token` field + fixed-pulse flash. First Axis-B field; establishes the text-search
   locator + validate-and-drop pattern.
3. **B2 + A2** — `note` field + block-inlay callout. The biggest change to the "explanation view" feel.
4. **B4** example blocks — reuses the A2 renderer; cheap win.
5. **B3 + A4** relations/arrows — the ambitious one; inside=arrow, outside=navigational callout.
6. **B5 / B1b** timing + choreography — spike only, on the sentence-index primitive.

---

### Touchpoints (where each change lands)

- Schema types: `src/main/kotlin/com/audioexplainer/Protocol.kt` (`Highlight` / `Segment` / `Plan`).
- Prompt schema mirror: `ClaudeExplainer.buildPrompt` (inline schema string, ~L187-192).
- Skill docs: `claude-skill/docs/explain-at-cursor.md` (step 3) + `claude-skill/docs/segments.md`.
- Rendering: `EditorHighlighter.kt` (add renderers; extend `tracked` + `clearTracked`).
- Timing/beats: `NarrationController.narrateCurrent` (`onChunk` `sentenceIndex` is the hook).
- Save/load: `WalkthroughStorage` (relative-path rewrite for any new `file` fields).
- Server decode is already tolerant: `ExplainerServer` `Json { ignoreUnknownKeys = true }` (L54).
