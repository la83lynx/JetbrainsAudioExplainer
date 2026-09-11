# `probes/` — three-second checks on the pure parts

Four `javac` probes — two over the schema layer, one over the token anchor, and a **bench** over the link
drawing — one `node` probe over the webview's playback clock, and two Python gates over the repo itself. They exist because `PlanLoader`, `PlanOutline`,
`WalkthroughStorage`, `TreeStyle` and the renderer's own `findNth` are pure arithmetic over line numbers, paths and strings — no VFS, no documents, no EDT — so every question about them is answerable
in seconds, and finding a mis-numbered `keep` range in a four-minute sandbox launch instead is a bad trade.

**These do not drift, and that is the one thing that separates them from the deleted `spikes/`.** Those were
standalone Swing files that *re-derived* the geometry they measured, so they agreed with the production code
only until one of the two was edited — and the edit landed in production, where the work happens. These
probes `import com.audioexplainer.*` and call the real `PlanLoader` / `PlanOutline` / `WalkthroughStorage` / `TreeStyle` / `findNth`. There is one
derivation; the probe cannot disagree with it, only report what it does. `TokenProbe` reaches a **private**
method reflectively for the same reason: what it measures is the production method, not a copy widened to be
measurable — and a rename breaks it loudly instead of leaving it agreeing with something that moved.

They also follow the standing rule that **every "X is absent" assertion needs a case where X is present**:
each control (no cycle reported, no depth issue, budget not exceeded) sits beside a case that does report it,
so a green tick cannot come from an instrument that is simply blind.

| Probe | Asks |
|---|---|
| `PlanProbe.java` | Does each degrade do what the caps say? Cycle broken at the frame that closes it, over-deep frame re-rooted, un-kept call line dropped to top level, focus inferred and reported, step range outside `keep` reported, duplicate id first-wins, soft caps counted. Plus **the outline** (Phase 3): runs and revisits not merged, every step reachable exactly once in plan order, the scene level on only when a plan has more than one scene, a revisited scene as two rows reusing its number, `stepView`'s enclosing span — and that a step row carries **no** `start`/`end`/`file`/`ranges`, with a control asserting it does carry its index and label. **Plus the scene model**: grouping in step order, a revisited scene counted twice, cross-scene parent re-rooted, cross-scene step reference dropped, the budget per scene rather than per plan, and that a plan mentioning no `scene` is exactly one scene. **Plus Phase 4's two**: a saved walkthrough round-trips through `WalkthroughStorage` into a temp workspace and back — `scene`, the nesting link, the `keep` roles, a relation's own endpoint file, and the resolved scene count — with the on-disk text asserted to hold *relative* paths, so the whole section cannot pass on a save/load that did nothing; and the tree's shipped settings defaults are asserted to be the style classes' own defaults, to be §1c's verdicts by name, and to name real enum constants, with an unknown value shown degrading and a real one shown being read |
| `PlanFileProbe.java` | Do the hand-written acceptance plans in `fixtures/plans/` load with **no** error or warn, and does every step resolve to a **scene**? Reports the scene grouping, flags any scene over the line budget, and prints **the outline the sidebar will draw** — scene rows, whether the scene level is on, groups, and step rows — failing if any step has no row to reach it from. Structure only — whether line 86 really holds that call is the VFS's answer, and the tree view gives it |
| `TokenProbe.java` | **The B1a anchor's boundaries**, hand-traced for two phases and asserted here. `to` is exclusive, so a token *ending* on it resolves and one *starting* on it does not — both sides measured, one character apart. The scan steps by `i = idx + 1`, so overlapping hits are each counted **and** an empty needle terminates instead of spinning (it is dropped upstream by `resolveToken` before it can arrive, but the loop is safe on its own — the hand trace had this the other way round). `occurrence` counts *qualifying* matches, so whole-word `#2` of `rate rateTable rate` is the standalone one and the same `#2` without whole-word is the one inside `rateTable`. `_` and `$` flank an identifier, `-` does not. And the pair that justifies `resolveToken`'s two passes: `Parse(` fails whole-word and resolves as a bare substring. **Not reachable here**: `resolveToken` itself needs an `Editor`, so the empty-needle guard and the order of the two passes are read, not asserted |
| `LinkBench.java` | **The link drawing's bench, and the spike eight rounds of reshaping were judged from.** Mock nodes, up to five links at once, both anchors, both joins, both directions, resting beside hovered — **13 PNGs**, of which five carry a round's questions: `link-label.png` (a spread fan with a short link hovered, round 8's label position beside round 11's — the same call, one argument apart), `link-lanes.png` (three same-editor links and two portals off one row, rest beside hover, 3× — round 7's picture), `link-fan.png` (five links off one row, both directions), `link-forms.png` (the `TOKEN`/`LINE_END` × `LINE`/`PORTAL` taxonomy at 4×, where the `∩` and the staple are directly comparable), `link-dim.png` (all five slots, rest beside hover, dark **and** light). It imports `LinkArt` and draws with it, so it cannot drift from what the editor paints. Assertions cover what is *fact* rather than taste. **Round 11's one, and it is the yardstick**: a short link's label must start beside *its own* end and not
beside the furthest lane's — the reader's *"'short' link text feels detached"*. Six assertions on drawn ink at
the reported geometry (own end 540, label ink 536, far lane 822), with the same call told the fan is cheap to
clear as the control (826), the trimming it also fixes measured in glyph ink (364 px against 136), a bunched
fan still stepped past, and the outermost lane unmoved either way. **Round 10's one, and it is the screen**: a step whose every coordinate is negative — the window on a
monitor left of the primary — must route to the *same* axes as the same step on the primary. It did not: 0 was
the "this lane asks for nothing" sentinel, so every real demand was discarded and the fan ended up flush
against the viewport's right edge. Nine assertions, including the live numbers as a control, and the second
site (`laneRightEdge` seeding its max at 0) was caught by the invariance assertion itself rather than by
reading. **Round 9's two**: a fanned token end's **stem** measured for all 4 fans × 2 directions × 2 joins — landing on the fan-0 rail, exactly the fan's own offset long, **8 of 8 rows** of the gap carrying ink, hoverable because it is a vertex of the same polyline — against two controls that draw **0** there, a token end still on its row and a line end fanned just as far; and the band a leg is routed clear of, where a node docked under the lower of two adjacent rows is **not** crossed while round 8's predicate — kept verbatim as the control — counted it, and a node genuinely between the rows still is, to the pixel at both edges. **Round 8's routing**: five same-editor line-end runs leaving one row measured onto **five different y** inside that row, with all five on **one** y as the control and a single run still exactly on the middle; the three routing rules re-asserted over **32 layouts × 8 demand vectors** now that ascending-demand packing can reorder the columns, with a check that the vectors really do reorder them so the sweep is not vacuous; a lane with a short row routing left of one with a long row while no lane is left of its own code and portals stay outside every same-editor lane; and the hovered label measured in **glyph ink** at two viewport widths — 116 px with room past the fan and 116 px again with none, where refusing to leave its own lane would have had 32 px against a 60 px minimum. the degradation per-lane demands introduce — a fan packed **wider than the viewport**, which one demand for the whole step could not produce — shown falling back to round 7's layout rather than to nothing, with the too-wide case as its own control and a viewport too narrow for even the minimum layout still drawing nothing. The hovered tint is held **under half way** to the link's own colour (44 % dark, 45 % light, against 34 % resting), which is the assertion round 7's 0.58 would have failed. **Round 7's routing, still asserted**: the reader's three rules — no two verticals on one x, no two portals overlapping, no line through another's portal — over **all 32 layouts of five**, against the drawn rectangles rather than against a pitch; a lane costing what it carries (5 same-editor lanes **52 px** against 5 portals' **152**); the fan fitting a 550 px node past 380 px of code where round 6's uniform pitch would have needed 549 of 510 (the control); the shared column now pulled **left** by a narrow member so both ends move together, with per-node clamping shown splitting them (the control); a line end placing **271 px** of ink on its row's middle where a token end places **0**; the hop measured at the same 26 px on a 60 px row and a 340 px row, and a token end and a line end finishing into the portal on the same rail with the same head to the same leg; `railSpace` buying **13 px** for a fan that reaches **11**; the polyline axis-aligned in every form with the middle of **every** segment hittable; the tint moving further from the background on hover in both themes while staying behind the connector's own weight; and the label's drawn glyphs centred on the row's **middle** rather than on the rail, with a same-editor link drawing one through the same call. **Still measured from earlier rounds**: five links separable by colour from a gold, a grey and a blue accent, all ten pairs and the consecutive pairs separately; the dimming on rendered pixels in both themes, with the old 0.45 blend failing 10 of 10; the hole measurably transparent and the leg measurably inside it, each the other's control; every column of the drawn portal mirrored about its own axis, with an even-width control that fails; font scaling as a real proportion of the row; and the strikethrough counted — **514 px** for the straight control against **7** shipped, plus the fan's cost *without* the reservation (0, 0, 39, 101, **217** px — the last two grew by the stem's own 2 px in round 9), which is the size of the problem `railSpace` removes. It has caught **thirteen** things a round would have spent itself on — the newest being a round-8 control that claimed the label would be lost at a viewport width where it comfortably fitted, which is an instrument reporting a fix that was not being exercised — including a line-end link on the longest row of a step with no direction indicator at all, and two in **its own instruments** — an indicator sample landing a half pixel outside the shape it was measuring, and a strikethrough pass measuring only the *diving* halves against an empty margin |

| `ClockProbe.js` | **The webview's playback clock**, which had no instrument at all until B1b needed one. `node probes/ClockProbe.js`, no build step. It **extracts** `scheduleSlot` / `recordChunk` / `stepElapsed` / `currentWordFor` / `sentenceForWord` / `beatKey` / `sentenceForBeat` / `fireDueBeats` out of `sidebar.js` by name and runs them in a `vm` context — a copy would pass forever while the shipped file drifted, and a rename raises instead of quietly testing nothing. Two clocks live in that file and they disagree only when playback starves: a 1.5 s underrun is measured leaving the cursor **three words of a six-word sentence** ahead of the voice, with the un-corrected elapsed kept as the control and a healthy stream asserted to bank no correction at all. Then the beats: a phrase resolving to the piece the splitter made out of `e.g.` (where a sentence *number* lands wrong), a straddling phrase left unresolved mid-stream and resolved to the sentence it starts in once every sentence is known, the no-`stext` fallback, firing once and only at its sentence's start, and two beats in one sentence collapsing to one pulse — with the same two phrases in *different* sentences as the control. Shares `beat-normalize.tsv` with `PlanProbe` |
| `beat-normalize.tsv` | Not a probe — **the one copy of the beat-matching rule**, read by `PlanProbe` (Kotlin's `Beat.normalize`) and `ClockProbe` (the webview's `beatKey`). A phrase the loader accepts and the webview never matches is the only failure in B1b with no symptom, so the two implementations are asserted against one table instead of against each other |
| `gates.py` | The build gates, none of which a green compile implies: no `noCompiledSerializer` stub in `build/classes` (dropping `@Serializable` **compiles** and throws at runtime), every `plugin.xml` class reference resolving to a compiled class, every resource XML parsing, no mixed line endings, the skill manifest matching disk, `VERSION` bumped, markdown fences balanced, and **one destination for a plan** — the tree controller built once and registered once, with no code naming the deleted `view` field (comments may, and do: the record of why it went is the thing worth keeping). `python probes/gates.py` from the repo root, after `compileKotlin` |
| `check_fixture.py` | Every relation endpoint in every acceptance plan verified against the **file it points at** — line in range, token present on that line, span under the cap — plus the per-step link count under both the round-7 and round-8 pair keys. It is what says a fixture still poses the case it was written for. `python probes/check_fixture.py` |

## Running them

```bash
./gradlew compileKotlin            # the probes read build/classes/kotlin/main

export MSYS_NO_PATHCONV=1          # or Git Bash rewrites the Windows paths below
G=$HOME/.gradle/caches/modules-2/files-2.1
P=C:/_code/JetbrainsAudioExplainer
KS=$(ls $G/org.jetbrains.kotlin/kotlin-stdlib/2.3.0/*/kotlin-stdlib-2.3.0.jar | sed 's|^/c/|C:/|')
SC=$(ls $G/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.7.3/*/*-core-jvm-1.7.3.jar | sed 's|^/c/|C:/|')
SJ=$(ls $G/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.7.3/*/*-json-jvm-1.7.3.jar | sed 's|^/c/|C:/|')
RD="C:/Program Files/JetBrains/JetBrains Rider 2024.3.2/lib"
CP="$P/build/classes/kotlin/main;$P/build/probe;$KS;$SC;$SJ;$RD/util-8.jar;$RD/*"

mkdir -p build/probe                     # the argfile: Git Bash cannot pass a ;-list straight through
printf -- '-cp\n"%s"\n' "$CP" > build/probe/cp.txt

javac -nowarn "@build/probe/cp.txt" -d build/probe probes/*.java
java "@build/probe/cp.txt" PlanProbe
java "@build/probe/cp.txt" PlanFileProbe fixtures/plans/*.json
java "@build/probe/cp.txt" TokenProbe
node probes/ClockProbe.js                # no classpath, no build — it reads sidebar.js directly
java -Dide.ui.scale=1.0 -Dsun.java2d.uiScale.enabled=false "@build/probe/cp.txt" LinkBench scratchpad/link-round11
#   the last argument is an optional output dir; omit it and the PNGs go to build/probe
```

**Windows paths, twice over.** Java on Windows cannot read Git Bash's `/c/...` form, so the classpath entries
are Windows paths — `cygpath -w` each one if you are assembling it in a script. And the platform half is a
`lib/*` wildcard rather than a list of jars: `LinkBench` scales with `JBUI`, which drags in enough of the
platform that naming them one at a time is a losing game. The JVM expands `*` itself, which is also what
keeps the command under Windows' argument-length limit. `util-8.jar` is named *as well*, for the reason
below.

**`javac` does not expand a classpath wildcard that arrives in an `@argfile`; `java` does.** That is why
`util-8.jar` is named as well as covered by `$RD/*` above — `TextRange` lives there, and `TokenProbe` is the
first probe to name a platform type in its *source* rather than only needing one at runtime. Passed straight
on the command line the wildcard compiles fine; through the argfile the same entry silently contributes
nothing and every `com.intellij.*` import fails to resolve. Name any platform jar a probe *imports*.

**Use forward slashes inside a `@argfile`.** A backslash is an *escape* character there, so a quoted
`C:\Program Files\...\lib\*` arrives as `CProgram FilesJetBrains...lib*` and every platform class silently
goes missing — `javac` still succeeds, because it only needs signatures, and the probe then dies at runtime
with `NoClassDefFoundError: JBUI`. `C:/Program Files/.../lib/*` works and needs no escaping.
**`-Dide.ui.scale=1.0` is not optional for the bench.** `LinkArt` measures everything through `JBUI.scale`,
and headless `JBUIScale` throws `AssertionError: Must be precomputed` when it has to work the system scale out
for itself. Pinning it also makes the PNGs deterministic at 1×, so two runs on two machines are comparable.

Two notes that cost a few minutes each:

- **`Logger` lives in `util-8.jar`**, not `util.jar`, in this Rider install. Without it `PlanLoader`'s
  `<clinit>` throws `NoClassDefFoundError`.
- **Git Bash mangles a `;`-separated `-cp`** when it is passed straight through. Write the whole `-cp "…"`
  line into a file and run `java "@argfile"`, or set `MSYS_NO_PATHCONV=1`.

Expected: `105 pass / 0 fail` from `PlanProbe`, `PASS — no error/warn issues in any plan` from `PlanFileProbe`,
`18 pass / 0 fail` from `TokenProbe`, and `212 pass / 0 fail` from `LinkBench`.

`LinkBench` also prints a count of ends the room check rejected, and it is **0** at the mock node widths it
currently draws. That number used to be quoted here as evidence the room check works, which it was not: it was
evidence about where 550 px happens to fall relative to a threshold, and it went to zero the moment the
threshold moved. The check is asserted directly now, both sides of it, and the tally stays only so a genuinely
missing link cannot look like a design decision.

**Two of its numbers are reported rather than asserted, on purpose.** The fan's glyph overlap per link count
and the shipped route's 7 px of strikethrough are both *costs of the fan*, not defects: slot 0 crosses nothing,
and each further slot puts its rail 3 px deeper into a neighbouring row. Asserting them at zero would be
asserting a design the reader has not judged yet; printing them is what lets the round judge it.

## When to delete them

`PlanProbe` asserts invariants that are meant to hold forever (a cycle must never nest, every step must be
reachable from exactly one outline row, a saved plan must come back with its scenes), so it is closer to a
unit test than to a spike, and it has now grown through two phases without being promoted. **It wants a test
source set**, and the argument gets stronger each time: 105 assertions are run by a `javac` line in a README,
which means they run when somebody remembers. Until then this is the cheapest thing that works.

`TokenProbe` is the same kind of thing and the argument applies to it unchanged — an anchor that resolves
at a line end is an invariant, not a spike. It passed 18 for 18 on its first run, which by this file's own
rule is the suspicious outcome, so two assertions were deliberately inverted to watch them fail before the
run was believed.

`PlanFileProbe` lives as long as `fixtures/plans/` does.
