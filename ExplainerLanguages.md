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

## What is left: one `runIde` session

**No code is outstanding.** Every phase compiles, and each was gated end-to-end against its real engine
outside the sandbox. What none of the following has had is a **human driving the UI**, which is the only
instrument that catches a control that is wired but unreachable.

L6, L6.1 and L8's engine paths already had their passes. These are the seven that have not:

| # | Phase | Drive this | Passes when |
|---|---|---|---|
| 1 | **L4** | Set the explanation language, then right-click → **Explain This (Audio)** | Claude POSTs a `set_plan` whose prose is in that language and whose top level carries `"language":"<code>"`, with **code identifiers, paths and API names untranslated** — then it narrates in that language's voice. **This has never been run end to end**, and it is the one pass that exercises Claude, the prompt, the wire and the voice reconciliation in a single click |
| 2 | **L3** | Load a **saved** non-English walkthrough from the saved list | The voice combo repopulates with *that* language's voices and the language badge appears. L6's pass already proved the settings side (the combo persisted `explanationLanguage=lt`, and voice reconciliation handled an espeak spec); the load path is the half it did not touch |
| 3 | **L5** | **Audio engines… → Add Japanese & Chinese voices (misaki)**, then narrate a `zh` or `ja` walkthrough | It speaks. Only the *clean-error* path is gated today ("needs `misaki[ja]`" instead of a stack trace); the success path never ran because the packs were not installed. Expect read-along to fill **per sentence** for CJK — that is the recorded limitation, not a defect |
| 4 | **L6.2** | The espeak voice editor: **Advanced… (⚙)** beside a robotic voice | The 7-parameter dialog opens, **Test** plays natively while narration is paused (it goes through `NativePcmPlayer`, not the webview), **Save voice** persists per variant, **Reset to default** restores. The three inline sliders (Pitch / Life / Word gap) are already judged; the dialog is not |
| 5 | **L7** | Windows SAPI voices in the dropdown | The async probe lands and the sidebar **re-pushes** its voice options, so SAPI voices appear without a reload; picking one narrates. With NaturalVoiceSAPIAdapter + its Edge voices the dev machine saw **322 voices across 135 locales**; without it, whatever SAPI5 voices are installed |
| 6 | **L8** | **Settings → Audio engines → Piper**: Install Piper (pip) | The status label refreshes off-EDT without freezing the dialog, and a downloaded voice narrates offline |
| 7 | **L9** | **Manage voices…** → `PiperVoiceManagerDialog` | The 170-voice / 50-locale table loads, the filter narrows it, **Download** md5-verifies, **Delete** frees the disk shown in the status line, and a multi-speaker voice expands into one dropdown entry per speaker. **A whole dialog nobody has seen**, which makes it the highest-risk item after L4 |

Not from this document, but the same session and the same kind of risk — the post-L9 UX batch is also
code-complete and unjudged: the **localized `Explain This` menu label** (`Languages.explainActionText`,
refreshed per `update`), the **Solution Explorer** entry (Rider populates `SolutionExplorerPopupMenu`, not
`ProjectViewPopupMenu`, so `rider.xml` re-targets the action), and the saved list's **filter / delete /
delete-all**.

**Test outside the sandbox wherever the question allows it.** `tts_server.py` runs standalone and every
engine gate above was won that way; `runIde` takes 4–7 minutes to reach a port file and jar-locks if a
previous sandbox is still running. Reserve it for questions only a human at the UI can answer — which is
exactly what the seven rows above are.

---

## Known limitations — recorded, not defects

- **Quality is bounded per tier, and the dropdown says so.** Kokoro's 8 languages are neural; Piper is
  neural and offline for ~40 more; SAPI's Edge voices are neural but **online** (internet required, an
  unofficial endpoint, text sent to Microsoft — all documented in `docs/tts.md`; classic SAPI voices have
  none of that); espeak is robotic but intelligible everywhere else.
- **Sparse voice sets are not a bug.** French has one Kokoro voice (`ff_siwis`); Italian, Spanish and
  Portuguese have 1–3. The combo is legitimately short for those.
- **CJK read-along degrades to per-sentence.** The transcript cursor counts words by whitespace, which is
  meaningless for Japanese and Chinese; `split_sentences` and the JS tokeniser were matched so sentences
  line up, and the per-word cursor simply does not apply.
- **misaki is heavy and stays opt-in** — `misaki[ja]`/`misaki[zh]` pull `fugashi`/`unidic` and
  `jieba`, so they install from **Audio engines…** on demand, cancellably, and never as part of the base set.
- **SAPI rides the running Kokoro server** (as espeak does): it needs the Python process up. A
  "SAPI with zero Kokoro/Python" launch mode is possible and is not built.
- **Windows' neural Narrator voices are invisible to both SAPI5 and WinRT.** The only route to them is the
  user-installed NaturalVoiceSAPIAdapter, which re-exposes them to `System.Speech`. The plugin
  **discovers whatever SAPI5 voices exist** and neither bundles nor depends on the adapter.
- **A language discovered by SAPI or Piper can be one the static registry never had.** Both fold through
  one `Discovered` layer, so a code found by both becomes a single merged language rather than two
  parallel copies — that merge is the reason the augmentation is shared instead of per-engine.
