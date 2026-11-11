# `probes/` — fast checks on the pure parts

The repo's test suite: `javac` probes and benches, one `node` probe and two Python scripts. The Java probes
`import com.audioexplainer.*` and call the production code (reflectively where it is private), so they
cannot drift from it; a rename fails them loudly. Each prints a `pass / fail` tally.

| Probe | Covers |
|---|---|
| `PlanProbe.java` | `PlanLoader` degrades (cycles, over-deep frames, stray lines widening `keep`, duplicate ids, caps), `PlanOutline`, the scene model, `WalkthroughStorage` save/load and listing, save identity on `Walkthrough`, owner lookup across frames, and `PlanSourceCheck` |
| `TokenProbe.java` | The token anchor's boundaries: exclusive end, overlapping hits, whole-word occurrence counting, identifier flanks |
| `EffortProbe.java` | The prompt and argv built by `ExplainPrompt` for every scope, depth, effort and model, against the golden `prompt-standard.txt`; plus the diagnostics catalogue |
| `McpProbe.java` | The `set_plan` MCP endpoint of the real `ExplainerServer` over loopback: handshake order, per-run token scoping, plan acceptance and error results |
| `RunEventGolden.java` | Recorded `claude -p` streams in `fixtures/stream/` replayed through `ClaudeStream` → `RunReader` → `RunLoop`, compared to frozen goldens |
| `AcpProbe.java` | The ACP client: permission policy, `fs/read_text_file`, preset launches, JSON-RPC framing against a fake agent, and the recorded sessions in `fixtures/acp/` |
| `LinkBench.java` | The link drawing (`LinkArt`): routing rules, lanes, fans, labels, portals and tints, asserted on drawn pixels; writes PNGs |
| `TreeBench.java` | The Code-tree settings preview: every dropdown value changes the picture; writes PNGs |
| `AppearanceBench.java` | The Appearance settings preview, same contract over its controls; writes PNGs |
| `ClockProbe.js` | The webview's playback clock, read-along cursor and beat scheduler, run on functions extracted by name from `sidebar.js` |
| `gates.py` | Build gates: no `noCompiledSerializer` stubs, `plugin.xml` classes resolve, resource XML parses, no mixed line endings, skill manifest matches disk, skill `VERSION` bumped when the skill changes, markdown fences balanced, one plan destination |
| `encoding_probe.py` | `scripts/explainer.ps1` and `curl` deliver a non-ASCII plan byte-for-byte |

Data files, not probes: `prompt-standard.txt` (the golden prompt for `EffortProbe`; regenerate only
deliberately with `java EffortProbe --freeze`), `beat-normalize.tsv` (the beat-matching table shared by
`PlanProbe` and `ClockProbe`), `fixtures/` (recorded runs; `java RunEventGolden record` rewrites the goldens).

## Running them

From the repo root, in Git Bash. Set `RD` to your Rider install's `lib` folder.

```bash
./gradlew compileKotlin            # the probes read build/classes/kotlin/main

export MSYS_NO_PATHCONV=1          # or Git Bash rewrites the Windows paths below
G=$HOME/.gradle/caches/modules-2/files-2.1
P=$(cygpath -m "$PWD")
KS=$(ls $G/org.jetbrains.kotlin/kotlin-stdlib/2.3.0/*/kotlin-stdlib-2.3.0.jar | sed 's|^/c/|C:/|')
SC=$(ls $G/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.7.3/*/*-core-jvm-1.7.3.jar | sed 's|^/c/|C:/|')
SJ=$(ls $G/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.7.3/*/*-json-jvm-1.7.3.jar | sed 's|^/c/|C:/|')
RD="C:/Program Files/JetBrains/JetBrains Rider 2024.3.2/lib"
CP="$P/build/classes/kotlin/main;$P/build/probe;$KS;$SC;$SJ;$RD/util-8.jar;$RD/app.jar;$RD/intellij.platform.core.jar;$RD/*"

mkdir -p build/probe
printf -- '-cp\n"%s"\n' "$CP" > build/probe/cp.txt

javac -nowarn "@build/probe/cp.txt" -d build/probe probes/*.java
java "@build/probe/cp.txt" PlanProbe
java "@build/probe/cp.txt" TokenProbe
java "@build/probe/cp.txt" EffortProbe          # last section shells out to claude/powershell; ping only
java "@build/probe/cp.txt" McpProbe             # binds loopback; backs up and restores the discovery files
java "@build/probe/cp.txt" RunEventGolden
java "@build/probe/cp.txt" AcpProbe
java -Dide.ui.scale=1.0 -Dsun.java2d.uiScale.enabled=false "@build/probe/cp.txt" LinkBench build/probe/pngs
java -Dide.ui.scale=1.0 -Dsun.java2d.uiScale.enabled=false "@build/probe/cp.txt" TreeBench build/probe/tree
java -Dide.ui.scale=1.0 -Dsun.java2d.uiScale.enabled=false "@build/probe/cp.txt" AppearanceBench build/probe/appearance
node probes/ClockProbe.js                # no build step
python probes/gates.py                   # after compileKotlin
python probes/encoding_probe.py          # not against a live sandbox: it takes over the discovery files
```

Classpath notes:

- **Use forward slashes in the `@argfile`.** A backslash is an escape there, so platform classes silently
  go missing and the probe dies at runtime with `NoClassDefFoundError`.
- **`javac` does not expand a `lib/*` wildcard from an `@argfile`; `java` does.** Any platform jar a probe
  *imports* must be named explicitly — hence `util-8.jar` (`TextRange`, `Logger`), `app.jar` and
  `intellij.platform.core.jar`.
- **`-Dide.ui.scale=1.0` is required for the benches.** Headless `JBUIScale` otherwise throws
  `Must be precomputed`; pinning it also makes the PNGs deterministic.
