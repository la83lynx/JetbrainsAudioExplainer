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
item, mapping to the blank string.

One `rowComment` under Depth carrying the honest trade — depth is what costs time; effort is what
costs correctness. Do not repeat that sentence on the other two rows.

## Verification

A probe in the `probes/` style: build the args and the prompt for each of the 3 × 6 × 4 combinations
and assert (a) **Standard depth reproduces today's prompt byte for byte**, (b) blank effort/model add
no flags and every non-blank one adds exactly one flag pair, (c) Brief's prompt contains no
`relations` / `parent` / `scene` ask and Deep's does. No network, no IDE — it is string building.

(a) is the one that matters: the prompt is the part an upgrade could silently change for everyone, so
it must be provably identical for a user who never opens the new group. The argv deliberately is not
identical — it gains `--effort medium`, which is the fix.
