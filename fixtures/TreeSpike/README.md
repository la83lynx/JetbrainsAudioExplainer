# TreeSpike — C# fixture solution

Built for **P0b.0** of `TreeOfCode.md`, and reused unchanged by **Phase 1b**.

Arch B's nodes are editors over **real documents in a loaded solution**, so every Arch-B question needs real
C# to ask it of. This repo had none — no `.sln` or `.csproj` anywhere — which is exactly why Phase 0's S5
probe reported SKIPPED. This is that missing piece.

It builds clean (0 warnings, 0 errors) and **runs**, so "is this real code?" has an answer that does not
depend on reading it.

```
dotnet build TreeSpike.sln
dotnet run --project src/Fixture.App
```

## Opening it in the sandbox

Rider must load the **solution**, not the folder: the ReSharper backend is what produces the semantic colour
B1 and B5 are about, and it only runs for a loaded solution. Either pass the `.sln` path as a `runIde`
argument, or use File → Open in the already-running sandbox.

**Solution loaded ≠ backend ready.** §6.2's caveat was a 4-second settle; do not reproduce it. Sample late,
and record how long you waited.

## Layout

| Project | Depends on | Holds |
|---|---|---|
| `Fixture.Core` | — | `Money`, `PricingOptions`, `Telemetry`, and **`SubInvoker`** (chain depth 2) |
| `Fixture.Processing` | Core | **`MidInvoker`** (chain depth 1) + its same-file callee, `Dispatcher`, three handlers |
| `Fixture.App` | Processing | **`MainInvoker`** (chain depth 0), `Program` |

Three projects rather than one, because the brief's sketch crosses project boundaries (`/Project1/…` →
`/Project2/…`) and a node's title is meant to carry that path.

## The three cases, with anchors

Line numbers are **load-bearing** — the spike and the Phase 1 fixtures point at them. If you edit a file,
regenerate this table.

### 1. Three-deep chain across three files

| Depth | File | Class header | Method | The line that matters |
|---|---|---|---|---|
| 0 | `src/Fixture.App/MainInvoker.cs` | 18 | `MainLoop` 68 | `int someParam = SomeSysCall();` **85**, call out at **86** |
| 1 | `src/Fixture.Processing/Invokers.cs` | 17 | `InvokeOn` 36 | calls `SubInvoker.Apply` at **51** |
| 2 | `src/Fixture.Core/SubInvoker.cs` | 15 | `Apply` 68 | leaf |

The gaps are deliberate and match the sketch: **~49 lines** between `MainInvoker`'s header and `MainLoop`,
then **~16 more** inside it before the `someParam` line; **~52** between `SubInvoker`'s header and `Apply`.
A node that keeps the header and the method has to elide those. Do not tidy them away.

### 2. Same file, two nodes

`src/Fixture.Processing/Invokers.cs` — `InvokeOn` (**36**) calls `Normalize` (**123**), 87 lines below it.
Caller and callee become two node views over **one document**, which Phase 0 finding 4 says is legal but
which nobody has looked at yet.

### 3. Wide — one parent, three children

`src/Fixture.Processing/Dispatcher.cs` — `Dispatch` (**45**) calls three handlers from three consecutive
lines **50 / 51 / 52**:

| Child | File | Class | Method |
|---|---|---|---|
| `ValidationHandler.Handle` | `Handlers/ValidationHandler.cs` | 9 | 18 |
| `PricingHandler.Handle` | `Handlers/PricingHandler.cs` | 11 | 29 |
| `AuditHandler.Handle` | `Handlers/AuditHandler.cs` | 9 | 18 |

The three handler files are **short** (38–47 lines) where the chain files are long (111–169). That contrast
is wanted: B4 and Phase 1c both have to judge grow-to-content against a `maxHeight`, and a fixture where
every node is the same size cannot settle it.

`PricingHandler.Handle` re-enters the chain at **39** (`MidInvoker.InvokeOn`), so a tree rooted at `Dispatch`
is wide *and* deep — and `BatchInvoker` (`Invokers.cs` **145**) is a second caller of `MidInvoker.InvokeOn`,
which is the two-parents question Phase 2 decision 1 has to answer.

## Deliberate properties

- **No diagnostics.** `GenerateDocumentationFile` is off; with it on, 82 CS1591 warnings covered the fixture
  in squiggles and error stripes — precisely the noise B5 must see past when it classifies markers as colour
  versus diagnostics. Phase 0 §6.2 got a clean 0-diagnostic reading and this keeps it that way.
- **Deterministic.** `MainInvoker.Entropy` is seeded, so a narrated walkthrough describes the same numbers
  every replay.
- **Plenty to colour.** Records, a readonly record struct, an interface, an enum, generics, LINQ, extension
  methods, `required` members, pattern matching — so B5 has many ReSharper identifier categories to find,
  not just keywords a lexer would have coloured anyway.
- `net8.0`, so the fixture does not depend on the newest SDK on the machine.
