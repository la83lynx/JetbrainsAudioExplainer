# Source check — plan

`Speed.md` #4 claimed the agent's hand-written validator repeats what the server checks. **It does not.**
`PlanLoader` checks the plan against itself and never opens a file, so a `token` missing from its line, a
line past the end of the file, and a `file` that does not exist all come back clean. The agent wrote the
script because `plan-schema.md` told it to — it is the only check there is. It cost ~16 s and 3 turns.

**Fix: the server checks the source, and the docs stop asking for the script.**

## A. `PlanSourceCheck` — pure, beside the loader

`check(resolved, source) → List<PlanIssue>`, where `source(path)` returns the file's lines or null. It
has no VFS in it (the loader's own rule), so `PlanProbe` drives it with a map.

| code | level | when |
|---|---|---|
| `frame.file-unresolved` | error | `file` does not resolve — the code the tree already uses at mount, now also in the reply |
| `frame.keep-past-eof` | warn | a kept line is past the last line |
| `frame.call-token-not-on-line` | warn | `callToken` not on `callLine` — the box falls back to the whole line |
| `step.beat-token-not-on-line` | warn | the pulse never happens (legacy line-0 beats skipped) |
| `relation.token-not-on-line` | warn | that end is never tinted, and its line is probably miscounted |
| `relation.line-past-eof` | warn | an end with no token, on a line the file does not have |
| `relation.file-unresolved` | warn | a cross-file end whose `file` does not exist |

**Matching rule:** the `occurrence`-th plain substring on that one line. The renderers try whole-word first
and then fall back to a plain substring, so "found" means the same thing to both. Beat and relation tokens
are trimmed and the call token is not, just as each renderer does. `tokenOnLine` in `Protocol.kt` is the
one copy.

## B. The server runs it

After `set_plan` and `/api/load`, it appends the result to `issues` and logs it. Paths resolve the way the
tree resolves them (absolute, else against the project root). That logic moves out of `TreePlanController`
into one shared function. Reading prefers the open document, so unsaved edits count, just as the tree
shows them. A failure in the check is logged and never costs the POST.

## C. The docs

- `plan-schema.md` "What it still cannot see is the code": now it can. The "twenty-line script" advice is
  removed.
- `SKILL.md` and `explain-at-cursor.md`: remove the stale "no relation mistake ever appears in `issues`".
- Prompt: the reply checks tokens and lines against the source, so do not write a validator. Then re-freeze
  `prompt-standard.txt`.

## Measured 2026-09-30: no saving at the default effort

There were four serial `claude -p` runs, alternating before and after, on the `Speed.md` target (caret at 300,
`--effort medium`, Standard depth). The mock server ran the real `PlanLoader` + `PlanSourceCheck`.

| run | wall | turns | out tok | Write→POST | calls between |
|---|---|---|---|---|---|
| before 1 | 73.7s | 8 | 6290 | 6.1s | 0 |
| after 1 | 69.9s | 6 | 5881 | 4.2s | 0 |
| before 2 | 81.3s | 8 | 5980 | 5.6s | 0 |
| after 2 | 82.4s | 6 | 6324 | 8.5s | 0 |

**No run wrote a validator, before or after.** The ~16s script was an `xhigh` behaviour. It went away with the
`--effort` fix, so this change saves nothing at the default. All four plans were also clean against the
source (the two "before" plans were replayed through the new check afterwards). The check is a safety net
that hasn't caught anything yet. Untested: whether the prompt line stops the script at `high`/`xhigh`.

## Judged by

`PlanProbe` covers each code with a control. After that, one `claude -p` run against the mock server (see
`verify-headless-claude-without-sandbox`) to confirm no validator script gets written. That run is where
the ~16 s is measured.
