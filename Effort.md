# Effort & depth — plan

Two user-facing knobs over explanation generation, plus the model. Not yet built.

## What the measurements changed

Measured on one 200-line C# method (`bench/` harness, 7 runs, see the round report). The plugin
passes no `--effort`, so it inherits `~/.claude/settings.json` — which on this machine is
`effortLevel: "xhigh"`. That inheritance *is* the slowdown:

| effort | wall | thinking | output tok |
|---|---|---|---|
| xhigh (inherited today) | 269s / 241s | 161s / 150s | 21.5k / 18.5k |
| medium | 118s | 45s | 9.2k |
| low | 74s | 14s | 5.3k |

Two consequences for this plan:

1. **The Effort setting must default to an explicit value, not to blank.** "Blank = inherit" is the
   safe default for `--model`, but for effort it preserves the bug: a user who never opens the
   control keeps paying for whatever they set for interactive coding. Default **medium**, and let
   blank mean inherit for the two who want it.
2. **Depth is worth more than the table suggests.** Two identical baseline runs produced a 5-frame /
   20-step plan and a 2-frame / 12-step one — same prompt, same settings. Output volume is the best
   single predictor of wall time and is currently uncontrolled; depth is the only knob that bounds it.

## Why two knobs and not one

They are different axes and they fail differently:

| | what it changes | when it is wrong |
|---|---|---|
| **Effort** | how hard the agent thinks per turn (`claude --effort`) | too low → wrong line numbers, missed call chain; too high → minutes of thinking on a getter |
| **Depth** | how much walkthrough we *ask for* (prompt text) | too low → a tour that skips the point; too high → 40 steps nobody listens to |

Effort at `max` on a "Brief" depth is a very careful one-frame explanation. Effort at `low` on "Deep"
is a sprawling plan with bad line numbers. Collapsing them into one slider makes both of those
unreachable, and the second is the failure mode users report as "it's slow AND it's wrong".

Output length is also the dominant cost of a run (tokens decode serially), so **depth is the knob
that actually buys back wall-clock time**; effort mostly buys correctness. Say so in the UI.

## What the CLI gives us

`claude --effort <low|medium|high|xhigh|max>` — exists today (CLI 2.1.274), one flag, no prompt change.
`claude --model <alias>` — `fable` / `opus` / `sonnet`, or a full model name.

Neither is passed by `ClaudeExplainer.buildArgs` today, so every run takes the user's CLI default for
both. That is the current, invisible behaviour we are replacing with a visible one.

## Settings to add

`AudioExplainerSettings.State`:

```kotlin
/** claude --effort. "" = inherit the CLI's own default; see below for why that is NOT the default. */
@JvmField var explainEffort: String = EFFORT_MEDIUM   // "", low, medium, high, xhigh, max
/** claude --model alias. Blank = the user's configured default. */
@JvmField var explainModel: String = ""               // "", sonnet, opus, fable
/** How much walkthrough to ask for. See buildPrompt. */
@JvmField var explainDepth: String = DEPTH_STANDARD   // brief | standard | deep
```

Blank-means-inherit stays *reachable* for `explainModel`, where the user's own choice is usually the
right one. `explainEffort` is the exception and ships as `medium`: inheriting is precisely the
present bug, and "a fresh install behaves exactly as it does today" is not a virtue when today means
four minutes. This is the one place the plugin deliberately overrides a setting the user made
elsewhere, so the release note has to say so in one line.

## Depth → prompt

Depth selects clauses in `ClaudeExplainer.buildPrompt`; nothing else in the pipeline changes. The
schema doc stays authoritative — depth never contradicts it, it only asks for less of it.

| | frames | steps | `note` / `example` | `beats` | `relations` | nesting / `scene` |
|---|---|---|---|---|---|---|
| **Brief** | 1 | ≤ 4 | omit | omit | omit | forbid |
| **Standard** (default) | 1–2 | as now | sparingly (as now) | sparingly | ≤ 2–3 per frame | as now |
| **Deep** | up to 4 | as now | encouraged | encouraged | ≤ 5 per frame | encouraged |

Brief is the interesting one: it is not "Standard with a smaller cap", it is *the older, flatter
plan shape* — no relations, no nested frames, no scenes. That shape is worth keeping reachable
because it is both the fastest to generate and the one that renders on the least screen.

`docs/explain-at-cursor.md` needs one paragraph saying a depth line may appear in the prompt and that
it narrows, never widens, what `plan-schema.md` allows.

## Wiring

- `buildArgs(prompt)` → `buildArgs(prompt, state)`: append `--effort <v>` and `--model <v>` when the
  corresponding setting is non-blank. Two `if`s.
- `buildPrompt` takes the depth and emits the matching clause block. The existing paragraph becomes
  the Standard branch verbatim, so Standard is byte-identical to today's prompt — that is what makes
  the change safe to ship without re-judging every existing behaviour.
- The no-skill-dir fallback branch needs the same depth treatment, or Brief silently means Standard
  for anyone whose skill install failed.

## UI

A new collapsible group **"Explanation"**, placed directly under **Audio engines** and above
**Language & narration** — it is about what gets generated, which precedes how it is spoken.

Three rows: Depth (combo), Effort (combo), Model (combo). Each combo carries `Default` as its first
item, mapping to the blank string — except Effort, which ships `medium` for the reason above.

These are the defaults the per-run dialog is seeded from; the diagnostics button lives further down,
in **Executables (advanced)**.

One `rowComment` under Depth carrying the honest trade — depth is what costs time; effort is what
costs correctness. Do not repeat that sentence on the other two rows.

## Per-run overrides — "Explain This with…"

Settings hold the defaults. One run in ten wants something else, and going to Settings and back to
change it is worse than the thing it fixes.

The context menu's `Explain This (Audio)` becomes a group:

```
Explain This (Audio)          <- unchanged, one click, uses the saved defaults
Explain This with…            <- opens the dialog below
```

Keeping the plain entry first and unchanged is the point: the common case must not grow a click.

### The dialog

Modal, shown before anything is spawned, seeded from the saved settings:

| control | |
|---|---|
| **Effort** | slider, 5 stops: low / medium / high / xhigh / max. Tick labels, no numbers. |
| **Additional instructions** | multi-line text area, empty by default, with a drop-down of the last ~8 non-empty entries. |
| *(optional)* **Depth** | the same combo as Settings. Cut it if the dialog feels crowded — depth also reads as an "additional instruction". |

Under the slider, one line that changes with the stop, because the number means nothing on its own:

> `medium` — about half the time of `xhigh`, same plan shape. Measured: 118s vs 255s.

**Per-run values do not write back to Settings.** A one-off must not silently become the new default;
that is how a user ends up wondering why every explanation got terse. The instruction *history* is the
exception and does persist — retyping "use simple terms" every time is the friction this dialog exists
to remove.

### Where the free text goes in the prompt

Appended as its own trailing block, after the numbered steps:

```
The user added these instructions for this run. Follow them where they narrow or restyle the
walkthrough. They do NOT relax the `set_plan` contract: the field names, the line-range rules and
the POST are fixed regardless of what is asked here.
<verbatim user text>
```

That framing is the whole design. "Use simple terms" and "do not use links" must work — both only
narrow. "Just print it as text" must not, because it breaks the one thing the plugin needs back.
Without the second sentence the agent has no way to tell those apart, and the failure is silent: a
run that prints prose to a terminal nobody sees.

The text is the user's own, typed into their own IDE, so this is not a trust boundary — it is a
blast-radius one.

### Plumbing

`ExplainRequest` gains `effort: String?`, `depth: String?`, `extraInstructions: String?`, all null for
the plain action. `ClaudeExplainer` resolves each as `request.x ?: state.x`. Nothing else changes: one
resolution point, and the plain entry keeps taking exactly today's path.

## Diagnostics — "Check permissions and settings"

A button in Settings (bottom of **Executables (advanced)**) that runs read-only checks and shows a
list of *name → status → remedy*. Every check names its fix or it does not earn a row.

It exists because this round's three defects were all invisible from inside the IDE: a POST helper
that corrupted its payload and printed nothing, a tool the agent reaches for that the allow-list
forbids, and an inherited effort setting costing minutes. None would show up in a log the user reads.

### The checks

**Every OS**

| check | fails when |
|---|---|
| `claude` on PATH | not found → the error users already hit, but before they right-click |
| CLI version | below the floor `--effort` needs |
| Effort in force | `~/.claude/settings.json` `effortLevel` / `modelSettings` disagrees with the plugin's setting — the exact bug this round found |
| MCP servers loaded | reports the count and that none are on the allow-list; informational, never a failure |
| Skill installed & current | `~/.claude/skills/audio-explainer` missing or older than the bundled `VERSION` |
| Loopback server | port/token files exist and `GET /api/health` answers |
| **POST round-trip** | the marquee check — see below |

**Windows only**

| check | fails when |
|---|---|
| PowerShell present | neither `pwsh` nor Windows PowerShell 5.1 resolves |
| `PowerShell` tool allowed | `ALLOWED_TOOLS` omits it while the docs tell the agent to use it |
| Execution policy | blocks `-File` even with `-ExecutionPolicy Bypass` |

**Linux / macOS only**

| check | fails when |
|---|---|
| `curl` present | `explainer.sh` has no fallback without it |
| `explainer.sh` executable | the install lost the `+x` bit — a real failure mode of copying from a jar |
| `$HOME` writable | the port/token files and the plan temp file both land there |

`SystemInfo.isWindows / isLinux / isMac` picks the set. A check that does not apply is **omitted, not
shown as passing** — a green "PowerShell: n/a" on Linux teaches the reader nothing.

### The round-trip check

Send a small payload containing an em dash, an arrow and one non-Latin line **through the platform's
real helper** (`explainer.ps1` / `explainer.sh`) to the running server, and have the server echo back
exactly what it received. Compare bytes.

This is the only check that exercises the path end to end, and it is the one that would have caught
the encoding bug on day one instead of during a performance investigation.

It needs a side-effect-free message: add `{"type":"ping","echo":"<text>"}` to `ExplainerServer`,
replying with the echo string verbatim. `set_plan` cannot be used — it would replace whatever
walkthrough the user currently has loaded, which is a rude thing for a diagnostic to do.

## Verification

A probe in the `probes/` style: build the args and the prompt for each of the 3 × 6 × 4 combinations
and assert (a) **Standard depth with no extra instructions reproduces today's prompt byte for byte**,
(b) blank effort/model add no flags and every non-blank one adds exactly one flag pair, (c) Brief's
prompt contains no `relations` / `parent` / `scene` ask and Deep's does, (d) a per-run override beats
the saved setting and absent ones fall back to it, (e) extra instructions appear once, verbatim, after
the numbered steps, and carry the contract sentence. No network, no IDE — it is string building.

The diagnostics need their own probe: each check must return a status AND a remedy, and the OS filter
must omit inapplicable checks rather than pass them. The round-trip check is already covered by
`bench/encoding_probe.py`, which is what proved the fix.

(a) is the one that matters: the prompt is the part an upgrade could silently change for everyone, so
it must be provably identical for a user who never opens the new group. The argv deliberately is not
identical — it gains `--effort medium`, which is the fix.
