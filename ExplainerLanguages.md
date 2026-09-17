# Audio Explainer — Multi-Language Support (Phase L) — what is left

Walkthroughs generate and narrate in languages other than English. **Scope, as locked at the start: the
explanation text and the voice only** — the IDE UI, settings labels, notifications and skill docs stay in
English.

> **L1–L9 are built, compiled and gated against the real engines.** This file used to carry the design and a
> phase-by-phase ledger; both are in git history (`git show 4fead42:ExplainerLanguages.md`) for anyone
> reopening a decision. The living reference for the tiers, the voice catalogue and the wire protocol is
> **`claude-skill/docs/tts.md`**, which ships to users — this file is only what has not been done.

---

## The one fact the whole design turns on

espeak-ng's 127 languages is **not** the ceiling, and neither is it the floor. In this stack espeak-ng is
the **phonemizer** (text → phonemes); the thing that *speaks* is the acoustic model, and Kokoro-82M was
trained on **8 languages**. Feeding Polish phonemes to Kokoro produces English-coloured noise — the model
has never heard the language.

That is why there are four **per-voice** engine tiers rather than one engine and a long language list:
Kokoro (neural, the 8), Piper (neural, offline, the ~40 Kokoro can't voice), Windows SAPI (whatever is
installed; neural Edge voices if the user has NaturalVoiceSAPIAdapter), espeak-ng (robotic, ~140, already
on disk). **Engine is a property of the voice, not of the language**, so one language can offer several
tiers side by side and the dropdown says which is which.

Text was never the bottleneck — Claude writes prose in any language it knows. Voice is.

---

## The `runIde` session: done, 2026-09-16

**L1–L9 are judged.** A human drove all seven remaining checks in one sitting and every one passed: L4's
full Claude → prompt → wire → voice path in Spanish, L3's saved-list reload, L5's misaki packs, L6.2's
espeak editor, L7's SAPI re-push, L8's Piper install and L9's voice-manager dialog — plus the unjudged UX
batch (localized menu label, Solution Explorer entry, saved-list filter/delete/delete-all) and the two
surfaces built the round before (the install console, the C++ build-tools row). The three defects that the
previous session found and the last round fixed were re-checked and hold: **36 link renders in that
session, every one `portal=0` with all ends drawn**, a downloaded Piper voice updating its row without
"Re-check engines", and the misaki packs installing one language at a time.

What the session *did* surface is below, under the Japanese entry — the one bug no unit could have caught,
because it is only audible.

<details><summary>The seven checks, for the record</summary>

| # | Phase | Drove this | Passed when |
|---|---|---|---|
| 1 | **L4** | Set the explanation language, then right-click → **Explain This (Audio)** | Claude POSTs a `set_plan` whose prose is in that language and whose top level carries `"language":"<code>"`, with **code identifiers, paths and API names untranslated** — then it narrates in that language's voice. **This has never been run end to end**, and it is the one pass that exercises Claude, the prompt, the wire and the voice reconciliation in a single click |
| 2 | **L3** | Load a **saved** non-English walkthrough from the saved list | The voice combo repopulates with *that* language's voices and the language badge appears. L6's pass already proved the settings side (the combo persisted `explanationLanguage=lt`, and voice reconciliation handled an espeak spec); the load path is the half it did not touch |
| 3 | **L5** | **Audio engines… → Add Japanese & Chinese voices (misaki)**, then narrate a `zh` or `ja` walkthrough | It speaks. Only the *clean-error* path is gated today ("needs `misaki[ja]`" instead of a stack trace); the success path has never run. **`ja` needs a C++ compiler on CPython 3.13** — see below — so on a machine without one expect `zh` to install and `ja` to fail *with a balloon that says why*. Expect read-along to fill **per sentence** for CJK — that is the recorded limitation, not a defect |
| 4 | **L6.2** | The espeak voice editor: **Advanced… (⚙)** beside a robotic voice | The 7-parameter dialog opens, **Test** plays natively while narration is paused (it goes through `NativePcmPlayer`, not the webview), **Save voice** persists per variant, **Reset to default** restores. The three inline sliders (Pitch / Life / Word gap) are already judged; the dialog is not |
| 5 | **L7** | Windows SAPI voices in the dropdown | The async probe lands and the sidebar **re-pushes** its voice options, so SAPI voices appear without a reload; picking one narrates. With NaturalVoiceSAPIAdapter + its Edge voices the dev machine saw **322 voices across 135 locales**; without it, whatever SAPI5 voices are installed |
| 6 | **L8** | **Settings → Audio engines → Piper**: Install Piper (pip) | The status label refreshes off-EDT without freezing the dialog, and a downloaded voice narrates offline |
| 7 | **L9** | **Manage voices…** → `PiperVoiceManagerDialog` | The 170-voice / 50-locale table loads, the filter narrows it, **Download** md5-verifies, **Delete** frees the disk shown in the status line, and a multi-speaker voice expands into one dropdown entry per speaker. **A whole dialog nobody has seen**, which makes it the highest-risk item after L4 |

</details>

**Test outside the sandbox wherever the question allows it.** `tts_server.py` runs standalone and every
engine gate was won that way; `runIde` takes 4–7 minutes to reach a port file and jar-locks if a previous
sandbox is still running. Reserve it for questions only a human at the UI can answer — and note that the
Japanese defect below was *found* by ear in the sandbox but diagnosed and fixed entirely outside it, by
putting the saved plan through the real phonemizer.

---

## Known limitations — recorded, not defects

- **Quality is bounded per tier, and the dropdown says so.** Kokoro's 8 languages are neural; Piper is
  neural and offline for ~40 more; SAPI's Edge voices are neural but **online** (internet required, an
  unofficial endpoint, text sent to Microsoft — all documented in `docs/tts.md`; classic SAPI voices have
  none of that); espeak is robotic but intelligible everywhere else.
- **Sparse voice sets are not a bug.** French has one Kokoro voice (`ff_siwis`); Italian, Spanish and
  Portuguese have 1–3. The combo is legitimately short for those.
- **Japanese does not go through kokoro-onnx's phonemizer, and could not.** `kokoro-onnx` phonemizes
  *every* language with espeak-ng — it never imports misaki, whatever is installed. espeak's `ja` voice has
  kana rules but **no kanji dictionary**, so on real Japanese it switches language per character run and
  *announces every switch*: 109 characters of narration came back as 943 phonemes that read
  "Chinese letter … Japanese letter …" out loud, in the Kokoro voice, and one sentence blew past the model's
  510-phoneme ceiling and died with an `IndexError` — narration silently short a sentence. Reported from the
  sandbox as "the second sentence sounded very long and said *in Chinese*, *in Japanese*", and reproduced
  exactly by pushing the saved plan through `Tokenizer.phonemize(text, "ja")`.
  So `KokoroEngine` now phonemizes Japanese itself: **misaki** (Kokoro-82M's own G2P — every symbol it emits
  for that plan is inside the model's 114-entry `DEFAULT_VOCAB`) for the Japanese, **espeak `en-us`** for the
  Latin identifier runs misaki passes through untouched, joined and handed to `create(..., is_phonemes=True)`.
  Measured on the same text: step 2 fell from **48.8 s to 13.6 s**, and `Interlocked.Exchange` went from
  spelled-out letters to `ˌɪntɚlˈɑːkt ɛkstʃˈeɪndʒ`. **Chinese deliberately keeps the espeak path** — espeak's
  `cmn` voice *does* have a Han dictionary, and that is what produced the Chinese narration judged good in
  the same session. `misaki[zh]` is therefore not required for Chinese; only `misaki[ja]` is load-bearing.
- **CJK read-along degrades to per-sentence.** The transcript cursor counts words by whitespace, which is
  meaningless for Japanese and Chinese; `split_sentences` and the JS tokeniser were matched so sentences
  line up, and the per-word cursor simply does not apply.
- **misaki is heavy and stays opt-in** — `misaki[ja]`/`misaki[zh]` pull `fugashi`/`unidic` and
  `jieba`, so they install from **Audio engines…** on demand, cancellably, and never as part of the base set.
- **`misaki[ja]` needs a C++ compiler on CPython 3.13, and that is upstream's problem, not ours.** It pulls
  **mojimoji**, a Cython extension with no 3.13 wheel on PyPI, so pip compiles it and fails with *"Microsoft
  Visual C++ 14.0 or greater is required"* on a machine without the Build Tools. Reproduced here: every other
  dependency of both packs resolves and every `zh` dependency is a pure-Python wheel. Two things came out of
  it — the packs now install **one language at a time**, so `zh` survives `ja`'s failure instead of being
  rolled back with it, and that exact pip line is recognised and turned into a sentence naming the Build
  Tools. Bundling a compiler is not on the table; a Python with a mojimoji wheel is the other way past.
  The prerequisite is now **detected before the attempt**, with the *same vswhere query setuptools itself
  runs* — `-latest -prerelease -requires …VC.Tools.x86.x64 -products *`, and notably **without `-all`** —
  because the question is not what is on the disk but what the build will find. Both flags were measured on
  the dev machine and both change the answer: a bare query answers "Community 2022" while the `-requires`
  query answers *nothing* (a Visual Studio without the C++ workload), and `-all` turns up a **Build Tools
  2019** whose MSVC 14.29 toolset is complete enough to compile a C file by hand through `vcvars64.bat` but
  is flagged `isComplete: false` — so vswhere hides it, setuptools' `_find_vc2017()` returns `(None, None)`,
  and pip demands a compiler that is physically present. That third state is reported as itself: the status
  line says the install is **incomplete** and asks for a *Repair*, not a download. Surfaced two ways — the
  line plus **Get Microsoft C++ Build Tools…** under Kokoro in Settings, and a warning written into the
  install console before `misaki[ja]` runs. Neither blocks the attempt: only the index knows whether *your*
  Python has a wheel.
- **SAPI rides the running Kokoro server** (as espeak does): it needs the Python process up. A
  "SAPI with zero Kokoro/Python" launch mode is possible and is not built.
- **Windows' neural Narrator voices are invisible to both SAPI5 and WinRT.** The only route to them is the
  user-installed NaturalVoiceSAPIAdapter, which re-exposes them to `System.Speech`. The plugin
  **discovers whatever SAPI5 voices exist** and neither bundles nor depends on the adapter.
- **A language discovered by SAPI or Piper can be one the static registry never had.** Both fold through
  one `Discovered` layer, so a code found by both becomes a single merged language rather than two
  parallel copies — that merge is the reason the augmentation is shared instead of per-engine.
