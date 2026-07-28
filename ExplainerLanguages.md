# Audio Explainer — Multi-Language Support (Phase L)

A plan to make the plugin generate and narrate walkthroughs in languages other than English.
Scope (locked by the request): translate the **explanation text and the audio/voice only**. The IDE
UI, settings labels, notifications, and skill docs stay in English.

This is a feature layer on top of the completed Phase 1–7.5 plugin (see `JetbrainsAudioExplainer.md`).

---

## 0. Critical correction — how many languages are actually possible

> The docs for espeak-ng show support for 127 languages, so it seems it's possible…

espeak-ng's 127 languages is **not** the ceiling. In this stack espeak-ng is only the **G2P
phonemizer** (text → phonemes). The thing that actually *speaks* is the neural acoustic model,
**Kokoro-82M**, and Kokoro was trained on only **8 languages (9 accents)**. Feeding espeak phonemes
for, say, Polish into Kokoro produces garbage/English-coloured noise — the model has never heard the
language. So the realistic, good-quality language set is exactly Kokoro's trained set, and **each
language ships its own fixed voice set** (from 1 voice for French to ~20 for American English).

**The authoritative Kokoro-82M v1.0 voice catalogue** (source: Kokoro `VOICES.md`, kokoro-onnx docs):

| Walkthrough `language` | Language | Voice-name prefix | espeak/misaki `lang` code | Voices | Extra deps |
|---|---|---|---|---|---|
| `en` (default) | English (US) | `af_` / `am_` | `en-us` | af_heart, af_bella, af_sarah, af_alloy, af_aoede, af_jessica, af_kore, af_nicole, af_nova, af_river, af_sky, am_adam, am_michael, am_echo, am_eric, am_fenrir, am_liam, am_onyx, am_puck, am_santa | none |
| `en` | English (UK) | `bf_` / `bm_` | `en-gb` | bf_emma, bf_alice, bf_isabella, bf_lily, bm_george, bm_daniel, bm_fable, bm_lewis | none |
| `es` | Spanish | `ef_` / `em_` | `es` | ef_dora, em_alex, em_santa | none |
| `fr` | French | `ff_` | `fr-fr` | ff_siwis | none |
| `hi` | Hindi | `hf_` / `hm_` | `hi` | hf_alpha, hf_beta, hm_omega, hm_psi | none |
| `it` | Italian | `if_` / `im_` | `it` | if_sara, im_nicola | none |
| `pt` | Portuguese (BR) | `pf_` / `pm_` | `pt-br` | pf_dora, pm_alex, pm_santa | none |
| `ja` | Japanese | `jf_` / `jm_` | `ja` | jf_alpha, jf_gongitsune, jf_nezumi, jf_tebukuro, jm_kumo | **`misaki[ja]`** |
| `zh` | Chinese (Mandarin) | `zf_` / `zm_` | `cmn` | zf_xiaobei, zf_xiaoni, zf_xiaoxiao, zf_xiaoyi, zm_yunjian, zm_yunxi, zm_yunxia, zm_yunyang | **`misaki[zh]`** |

Naming convention: **1st letter = language/accent, 2nd letter = gender (`f`/`m`)**. US vs UK English
is one *text* language (`en`) with two voice groups — the accent is a voice choice, not a separate
language.

**Consequence for the plan:** the language menu is these **8 languages**, not 127. Japanese and
Chinese need extra pip packages (`misaki[ja]`, `misaki[zh]`, which pull heavy transitive deps like
`fugashi`/`unidic` and `jieba`/`ordered-set`), so they are split into a **later opt-in phase**. If we
ever want the long tail of languages, that is a *different engine* problem (espeak-ng's own robotic
synthesis, or the deferred Windows SAPI path in Phase 7b, which can reach any OS-installed voice) —
out of scope here.

---

## 1. Key design decisions

1. **One language per walkthrough** (not a multi-language map inside one file). The walkthrough file
   gains a single `language` field; every human-facing string in it (title, segment titles &
   explanations, highlight `ttsText` & labels) is written in that language. This matches the request's
   "its language" phrasing and keeps the schema, Claude's work, and the UI simple. (A future
   "re-translate this walkthrough into X" action can produce a *new* file; we don't store parallel
   texts.)
2. **`language` drives BOTH the text and the eligible voices.** A Spanish walkthrough must be spoken
   by a Spanish voice with `lang=es`, or espeak mispronounces it. So loading a walkthrough sets the
   voice combo to *that language's* voices and the TTS `lang` code follows the voice.
3. **Default is English, fully back-compatible.** Missing `language` ⇒ `en`. Existing saved
   walkthroughs and the current English flow are unchanged (English is literally the no-op path).
4. **Settings holds the *default* language for new "Explain This" runs.** Claude is told to generate
   in that language. The setting does not retro-translate loaded walkthroughs.
5. **Scope is phased:** Phase L1–L4 ship the 6 no-extra-deps languages (en, es, fr, hi, it, pt).
   Phase L5 adds Japanese + Chinese behind the extra-deps install. UI translation stays out of scope.
6. **A single canonical language/voice registry** in Kotlin is the source of truth; the Python server
   and the webview derive from it (pushed at runtime) rather than each hardcoding its own copy.

---

## 2. Canonical registry (new)

New file **`src/main/kotlin/com/audioexplainer/Languages.kt`** — the one place the language/voice
facts live. Everything else reads from it.

```kotlin
data class Voice(val id: String, val label: String)   // id="ef_dora", label="Dora (F)"
data class Language(
    val code: String,        // "en","es","fr","hi","it","pt","ja","zh"  (walkthrough.language)
    val displayName: String, // "Spanish"  (English display name — UI stays English)
    val autonym: String,     // "Español"  (shown as the language badge, optional)
    val voices: List<Voice>, // eligible Kokoro voices, US+UK grouped under "en"
    val defaultVoice: String,
    val requiresExtraDeps: Boolean, // true for ja/zh (misaki)
)
object Languages {
    val ALL: List<Language> = listOf(/* en, es, fr, hi, it, pt, ja, zh from the table above */)
    fun byCode(code: String?): Language        // null/unknown → English
    fun voicesFor(code: String?): List<Voice>
    fun defaultVoiceFor(code: String?): String
    fun languageOfVoice(voiceId: String): String   // "ef_dora" → "es"
}
```

The Python-side voice→espeak-lang map is the natural companion (see §4); keep the two in sync (a
one-line comment cross-referencing this table in both files).

---

## 3. Data-model & server changes (Kotlin)

### 3.1 `Protocol.kt`
- Add to `Plan`: `val language: String = "en"`. Because `Plan` is *both* the `set_plan` body and the
  on-disk saved shape, this one field flows through save/load and the wire automatically
  (`ignoreUnknownKeys` + default keeps old files/messages valid). No change to `Segment`/`Highlight`.

### 3.2 `Walkthrough.kt`
- Add `private var language: String = "en"` and include it in `WalkthroughSnapshot`.
- `setPlan(title, segments, language = "en")` — new trailing param; store it, fire on `onPlan`.
- `restorePrevious()` / the `previous` snapshot must capture & restore `language` (it's already a
  full `WalkthroughSnapshot`, so once the field is on the snapshot this is free — just assign it back).
- No navigation/mutation logic changes.

### 3.3 `ExplainerServer.kt`
- `dispatchAgentMessage` → `set_plan`: `walkthrough.setPlan(plan.title, plan.segments, plan.language)`.
- `handleLoad` and `loadSavedWalkthrough`: pass `data.language` into `setPlan`.
- `saveCurrentWalkthrough` / `handleSave`: read `language` from the walkthrough state and hand it to
  `storage.save(...)`.
- `handleGetState`: add `"language"` to the JSON (so the skill's Q&A-on-active-walkthrough knows it).
- `validateAgentMessage`: no change — `language` is optional with a default.

### 3.4 `WalkthroughStorage.kt`
- `save(title, segments, name)` → `save(title, segments, language, name)`; build
  `Plan(title, toRelativePaths(segments), language)`.
- `load`/`list`: `Plan` already carries `language` via serialization — nothing to parse by hand.
  `encodeDefaults = true` means the field is always written; old files without it decode to `"en"`.

---

## 4. TTS server changes (`scripts/tts_server.py`) — **VERSION bump**

The server currently only distinguishes US/UK English:

```python
def lang_for_voice(voice):
    return "en-gb" if voice.startswith("b") else "en-us"
```

Replace with a full prefix→lang map so every language phonemizes correctly:

```python
PREFIX_LANG = {
    "a": "en-us", "b": "en-gb", "e": "es", "f": "fr-fr",
    "h": "hi", "i": "it", "p": "pt-br", "j": "ja", "z": "cmn",
}
def lang_for_voice(voice):
    return PREFIX_LANG.get((voice or "a")[:1], "en-us")
```

Also:
- Accept an optional explicit `"lang"` in the request as an override (falls back to the voice-derived
  code). Belt-and-suspenders; the voice prefix is normally authoritative.
- `--check`: report whether `misaki` (and `misaki[ja]`/`misaki[zh]`) import, so the plugin can tell
  the user which languages are actually ready. Add `"languagesReady": ["en","es",...]` to the status
  JSON.
- **Chinese/Japanese** (`ja`,`cmn`) route through misaki inside kokoro-onnx; guard the import and
  return a clean `{"type":"error","message":"Japanese needs 'misaki[ja]' — run Set up TTS again"}`
  instead of a stack trace when the pack is missing.
- Because this file is installed to `~/.claude/skills/audio-explainer/scripts/` only when the skill
  `VERSION` differs, **bump `VERSION`** (e.g. `0.1.0-4` → `0.2.0`) so `ClaudeSkillInstaller` re-copies
  it. (Same mechanism the Phase 7.5 read-along sync used.)

### `TtsService.kt`
- `installDependencies`: for the base set the current `pip install kokoro-onnx onnxruntime numpy` is
  enough. Add a variant (or extra arg) that also installs `misaki[ja]` / `misaki[zh]` when the user
  opts into Japanese/Chinese (Phase L5). Surface the heavier download in the progress text.
- `speak(text, voice, speed, listener)` is already voice-parameterised — no signature change; the
  server derives `lang` from `voice`. (Optionally pass an explicit `lang` for robustness.)
- `restart()` on a language/voice change is already wired via `onRateChanged()`.

---

## 5. Settings changes

### `AudioExplainerSettings.kt`
- Add `@JvmField var explanationLanguage: String = "en"` — the default language for **new** Explain-This
  generations.
- Voice-per-language memory. `ttsVoice` today is a single global default (`af_heart`). Two options:
  - **Baseline (recommended, low-risk):** keep the single `ttsVoice` as "last selected voice"; when a
    walkthrough loads (or the language changes) and `ttsVoice` doesn't belong to that language, fall
    back to `Languages.defaultVoiceFor(lang)`. Simple, serializer-safe.
  - **Nice-to-have:** a per-language map so each language remembers its own chosen voice. The platform
    XML serializer needs `@JvmField var voiceByLanguage: MutableMap<String,String> = LinkedHashMap()`
    plus care (maps round-trip but are fiddlier). Add only if the baseline feels lossy.
- `speed`/`ttsEngine`/`kokoroModelDir` unchanged (language-agnostic).

### `HighlightSettingsConfigurable.kt` (the settings page, "Narration (audio)" section)
- Add an **"Explanation language"** combo bound to `explanationLanguage`, listing
  `Languages.ALL` by `displayName`.
- Make the existing **voice** combo language-aware: group voices with optgroup-style headers by
  language, or filter to the selected explanation language. Reset the voice to the language default
  when the language selection changes.
- For `ja`/`zh`, show a hint that those need the extra TTS packs (and disable/annotate until installed,
  driven by `--check`'s `languagesReady`).

---

## 6. Sidebar / webview changes

### `AudioExplainerSidebar.kt`
- `pushUpdate`: include `"language"` in the `update` message.
- New `pushVoiceOptions()` (or extend the existing `settings` message): send
  `{ language, languageName, voices:[{id,label}], selectedVoice }` for the **current walkthrough's**
  language. Call it from `onLoadEnd`, and from `onPlan` (a newly loaded walkthrough may be a different
  language), and after a `voice_change`.
- `onPlan`: reconcile the selected voice to a valid one for the new language (via `Languages`), persist
  it, and re-push the voice options.
- `voice_change` handler: persist per the §5 policy (validate the voice belongs to the current
  language before saving).

### `sidebar.html`
- Replace the 7 hardcoded English `<option>`s in `#voice-select` with an empty `<select>` populated
  dynamically from the pushed voice options. (Keep one English fallback option for the pre-load frame.)
- Add a small **language badge** near the walkthrough title (e.g. `<span id="wt-language">`), shown
  when language ≠ en.

### `sidebar.js`
- Handle the new `voice_options` message: rebuild `#voice-select` from `voices`, then select
  `selectedVoice`. Keep the existing `settings`-message voice restore working with the dynamic list.
- On `update`, render the language badge from `msg.language`.
- The `speed_change`/`voice_change` postMessage flow is unchanged.
- **Known limitation for `ja`/`zh` read-along:** the transcript cursor counts words via
  `text.split(/\s+/)`, which is meaningless for space-less Japanese/Chinese. Read-along will fill
  per-sentence rather than per-word for those. Acceptable for L5; note it and consider a CJK
  character-based fallback later.

---

## 7. Claude generation changes (prompt + skill docs)

### `ClaudeExplainer.kt` `buildPrompt`
- Read `AudioExplainerSettings.explanationLanguage`; when it isn't `en`, add an explicit instruction:

  > Generate the walkthrough **in {LanguageName}**. Write the `title`, every segment `title` and
  > `explanation`, and every highlight `ttsText` and `explanation` in {LanguageName}. Keep code
  > identifiers, file paths, and API names verbatim (do not translate code). Put `"language":"{code}"`
  > at the top level of the `set_plan` JSON.

- Update the inline schema example in the prompt to include `"language":"…"` at the top level.

### `docs/explain-at-cursor.md`
- Add `language` to the schema example and a rule: all prose in the target language, code left as-is,
  the top-level `language` field is required when non-English.

### `SKILL.md` / `docs/plan.md` / `docs/segments.md`
- Document the top-level `language` field in the plan schema (`docs/plan.md`).
- For interactive sessions: if the user asks for another language (or one is configured), set
  `language` and write the prose accordingly. Keep it light — no new mandatory question (the request
  said UI/interactive parity is not the focus; "just ask the relevant agent to create it translated"
  is fine).
- These doc edits ride the same **VERSION bump** as the TTS server.

---

## 8. Phased delivery & gates

> **Status (2026-07-24):** L1 ✅, L2 ✅ (both gated standalone), L3 ✅, L4 ✅ (code complete + compiles;
> their visual/integration gate wants one `runIde` sandbox pass — see notes). L5 ✅ (code complete +
> compiles; misaki guard + Spanish regression gated on the real engine). L6 ✅ (code complete + compiles;
> espeak de/pl gated end-to-end through the wire protocol on the real engine, + runIde UI pass done —
> Lithuanian selected in-sandbox, settings persisted, real TTS server launched clean). L7 ✅ (code complete +
> gated end-to-end on the real engine; runIde UI pass pending). L8 ✅ (code-complete + gated end-to-end on
> the real engine — piper+kokoro+espeak coexist in one server, correct per-voice sample rate, no regression;
> **runIde UI pass DONE 2026-07-24** — install/download/pick/narrate all confirmed). **L9 ✅ CODE-COMPLETE +
> gated (2026-07-24, VERSION 0.7.5, compileKotlin exit 0): full Piper catalog + voice-manager dialog +
> multi-speaker + delete/disk. Catalog subcommands verified live (170 voices, network→cache); single-speaker
> synth + `#N` speaker parsing verified. runIde UI pass pending — task list below.**

**Phase L1 — Data model & round-trip (no UI). ✅ DONE.**
Registry (`Languages.kt`), `Plan.language`, `Walkthrough`/snapshot, server set_plan/load/save/state,
storage. *Gate PASSED (standalone JavaExec, 18/18):* a `set_plan`/saved file with `"language":"es"`
round-trips through save→load unchanged; legacy files (no field) → `en`; `Walkthrough.setPlan`/snapshot/
`restorePrevious` thread + restore language; registry helpers correct. English flow untouched.

**Phase L2 — TTS multi-language. ✅ DONE.** `tts_server.py` prefix→lang map (`PREFIX_LANG`), optional
`lang` override, misaki guards for ja/zh, `--check` language readiness (`languagesReady`/`misakiJa`/
`misakiZh`); VERSION `0.1.0-4` → `0.2.0`. *Gate PASSED (standalone, dev machine):* Spanish (`ef_dora`,
RMS 0.070) and French (`ff_siwis`, RMS 0.109) synth real neural speech with the correct per-voice lang;
ja/zh raise a clean "needs misaki[ja]/[zh]" error (no stack trace); `--check` →
`languagesReady:["en","es","fr","hi","it","pt"]`.

**Phase L3 — Settings + voice combo. ✅ CODE COMPLETE (compiles; visual gate pending sandbox).**
`explanationLanguage` setting; settings page gains an "Explanation language" combo + a full
language-grouped voice combo; sidebar pushes `voice_options` (per-walkthrough language voices), reconciles
the persisted voice to the loaded language (baseline policy §5), validates `voice_change`, and renders a
language badge. Webview: dynamic `#voice-select`, `.wt-language` badge. *Gate (needs runIde):* choosing
Spanish + `ef_dora` persists; loading a saved Spanish walkthrough repopulates the combo with Spanish
voices and shows the badge.

**Phase L4 — Claude generation. ✅ CODE COMPLETE (compiles; integration gate pending sandbox).**
`ClaudeExplainer.buildPrompt` reads `explanationLanguage` and, when non-English, injects a LANGUAGE
instruction + `"language":"<code>"` into the schema example. Docs updated: `explain-at-cursor.md`,
`walkthrough.md`, `segments.md` (jq `--arg lang`), `SKILL.md`, `tts.md` (full voice catalogue). *Gate
(needs runIde + claude):* right-click Explain This with language=Spanish → Claude POSTs a `set_plan` whose
prose is Spanish, `language:"es"`, code identifiers intact; it narrates in a Spanish voice.

**Phase L5 — Japanese & Chinese (opt-in). ✅ CODE COMPLETE (compiles; engine-side gate passed).**
`TtsService.installLanguagePacks` + a **Set up TTS → "Add Japanese & Chinese voices (misaki)"** option
(`TtsSetup`); `--check` readiness (`languagesReady`/`misakiJa`/`misakiZh`) now flows into `TtsStatus`,
gating the settings combos (ja/zh show "— needs TTS pack" until installed). CJK read-along: `tts_server.py`
`split_sentences` now splits ja/zh on `。．！？…` / `、，；` (per-sentence streaming) and tags each CJK
sentence `sw=1`; `sidebar.js` tokenises CJK transcripts per sentence to match. VERSION `0.2.0` → `0.3.0`.
*Gate PASSED (standalone, real Kokoro):* a Japanese (`jf_alpha`) and Chinese (`zf_xiaoxiao`) request raise a
clean "needs misaki[ja]/[zh]" `RuntimeError` (→ the plugin's "Narration unavailable" notice, no crash);
Spanish (`ef_dora`) still synthesizes real audio (no regression); CJK sentence counts match between the
Python splitter and the JS tokeniser (ja→2, zh→3 on the samples). *Still pending (needs misaki + runIde):*
after `Add Japanese & Chinese voices`, a `zh` walkthrough actually narrating with `zf_xiaoxiao`.

**Phase L6 — espeak-ng robotic tier (the ~100 other languages). ✅ CODE COMPLETE (compiles; engine-side
gate passed).** See §12. `tts_server.py` gained an `EspeakEngine` (pure ctypes against espeakng-loader's
DLL — no new deps) + an `EngineHost` router: an optional `"engine":"espeak"` request field routes to it
while Kokoro stays primary (both coexist in one process). `--check` now reports `espeakLanguages` (≈140).
`Languages.kt` grew an `Engine` enum + a curated 28-language espeak set (`engine = ESPEAK`, voices =
code + `+f3`/`+m3` variants) with registry-aware `engineOfVoice`/`languageOfVoice`; `TtsService.speak`
sends `engine=espeak` for those voices; settings label them "espeak (robotic)"; `TtsSetup` reports the
espeak language count. VERSION `0.3.0` → `0.4.0`. *Gate PASSED (standalone, real engine, end-to-end over
the TCP wire):* German (`de+f3`, 3.90 s, RMS 0.090, 2 sentences @ 22 050 Hz) and Polish (`pl`, 3.52 s,
RMS 0.077) synthesize intelligible robotic audio through the existing protocol; Kokoro Spanish (`ef_dora`)
and English (`af_heart`) still synthesize real neural audio on the *same* server process (coexistence, no
regression); per-sentence word counts stream correctly. No extra download beyond what Kokoro installed.
*runIde UI pass DONE (2026-07-23):* in the sandbox the user set the explanation language to **Lithuanian**
(an espeak-tier language) — settings persisted (`explanationLanguage=lt`, `ttsVoice=lt`, proving the combo
+ voice reconciliation handle espeak specs); narration then launched the real TTS server (`synthetic=false`)
with **no errors** through the post-L6 routing code; `lt`/`lt+f3` synth independently reconfirmed (RMS 0.096 /
0.078). The skill also redeployed to v0.4.0 in-IDE (VERSION diff). L6 is complete.

**Phase L6.1 — espeak quality pass. ✅ CODE COMPLETE (compiles; engine-side gate passed).** User feedback
after the L6 runIde pass: robotic voices had echo/reverb + artifacts + occasionally dropped syllables.
Root cause was self-inflicted — our "Female"/"Male" voices used espeak's stock `+f3`/`+m3` variants, and
`f3` (like `f2`/`f4`/`f5`/`m2`) bakes in an `echo 120 10` directive (the "reverb") plus `roughness`; no
stock female variant is free of both. Fixes: (1) `tts_server.py` now installs two **custom clean variants**
`ae_f`/`ae_m` (echo/roughness stripped, `consonants` raised for clarity) into espeak's `voices/!v` dir on
`EspeakEngine` init — idempotent, best-effort; an unknown `+ae_*` variant falls back to the base voice, so
read-only installs degrade with no error. `Languages.kt` espeak voices now use `+ae_f`/`+ae_m`. (2) Default
speaking rate lowered 175 → **160 wpm** + a small default word-gap (clearer, recovers run-together
syllables). (3) New per-request `"tune":{pitch,range,wordgap}` wire field → `espeak_SetParameter`
(clamped, set every call so no leak); Kokoro ignores it. (4) **Sidebar UI**: a "Robotic" row with Pitch +
Life (range) sliders, shown only for espeak-tier languages (`voice_options.robotic`), persisted as
`AudioExplainerSettings.ttsEspeakPitchPercent`/`ttsEspeakRangePercent`, re-streams on change like Speed.
`languageOfVoice` hardened to resolve any `code+variant` spec (incl. legacy `+f3`) to its base code.
VERSION `0.4.0` → `0.5.0`. *Gate PASSED (standalone + end-to-end over the actual `handle()` wire):* de+ae_f
with `tune{pitch:75,range:85}` → 22 050 Hz audio, and `{pitch:20,range:0}` gives a measurably different
(monotone) render — proving the knobs bite; pl+ae_m defaults + a non-espeak synthetic request coexist with
no regression; a malformed `tune` is ignored without crashing; `ae_f`/`ae_m` confirmed installed.
*runIde UI pass DONE:* user confirmed the inline Pitch + Liveliness sliders show only for robotic voices
and work as intended.

**Phase L6.2 — voice editor + robotic-anywhere + full Layer-2. ✅ CODE COMPLETE (compiles; engine-side
gate passed).** Follow-up to L6.1 (user request). Three parts:
1. **Word Gap** is now a third inline slider (runtime `espeak_SetParameter WORDGAP`), alongside Pitch/Life.
2. **Robotic voices in Kokoro languages.** Engine is now **per-VOICE** (`Voice.engine`, `voiceToEngine`,
   `engineOfVoice` map-based), not per-language. The 6 non-CJK Kokoro langs (en/es/fr/hi/it/pt) append
   "Robotic (F)"/"Robotic (M)" (`<code>+ae_f`/`+ae_m`); espeak-tier langs dropped the bare "Default" so
   every robotic voice is an editable variant. The sidebar shows the robotic controls whenever the
   *selected voice* is robotic (per-voice flag, toggled client-side on dropdown change). ja/zh skipped
   (CJK + espeak `cmn` mismatch).
3. **Full Layer-2 via a native voice-editor dialog** ([EspeakVoiceDialog], non-modal `DialogWrapper`,
   remembered size). "Advanced…" (⚙) button → stops playback → dialog with all 7 params (Pitch, Liveliness,
   Word gap, Clarity=consonants, Smoothness=10−roughness, Flutter, Breath) + buttons Test / Reset to
   default / Cancel / Save voice. **Test** plays natively via `javax.sound.sampled` ([NativePcmPlayer]) so
   it works while narration is paused — no webview round-trip. Layer-2 params can't be set at runtime, so
   `tts_server.py` **regenerates the variant file** (`_build_variant` from a fixed base + tunable
   directives) and reloads it on `SetVoiceByName` (verified: rewriting the file mid-process changes the
   audio). Params persist **per variant** (`EspeakProfile` × `roboticF`/`roboticM` in settings, shared
   across languages); `TtsService.speak` now takes an `EspeakTune` (7 fields) → `tune` wire object.
   VERSION `0.5.0` → `0.6.0`. *Gate PASSED* (py_compile + compileKotlin BUILD SUCCESSFUL + end-to-end wire:
   full 7-param tune, `en+ae_f` robotic-in-Kokoro routes to espeak, roughness/consonants sweeps change the
   render measurably). *Pending:* runIde UI pass to exercise the dialog (Test/Save/Reset) in-IDE.

**Phase L7 — Windows SAPI (System.Speech) tier. ✅ CODE COMPLETE (compiles; engine gated end-to-end).**
A third **per-VOICE** engine (`Engine.SAPI`), Windows-only (absent on macOS/Linux — they stay on
Kokoro + espeak). Empirical probing (dev machine) reshaped the original "global engine" framing: SAPI5 and
WinRT both only expose *installed* voices, and Windows' neural "natural" voices are Narrator-isolated
(invisible to both APIs). The unlock is the user-installed **NaturalVoiceSAPIAdapter** — a SAPI5 provider
that re-exposes the natural + **Edge online neural** voices to `System.Speech` (NOT WinRT), so with its Edge
voices enabled the dev machine went from 2 → **322 voices across 135 locales** (Azure neural). We do NOT
bundle or depend on the adapter; the plugin discovers and uses whatever SAPI5 voices exist.

- `tts_server.py`: a `SapiEngine` drives a persistent Windows-PowerShell worker (System.Speech loads once;
  per-sentence `SetOutputToWaveFile` → stdlib `wave` → int16→float32-LE → the existing wire). Routed via
  `engine=sapi` in `EngineHost`; `allow_sapi=(win32)`. 22.05 kHz/16-bit/mono (same conversion as espeak).
  Zero extra pip deps. Latency: ~0.3 s warm / ~1 s cold per sentence (fine for per-sentence streaming).
- `Languages.kt`: `Engine.SAPI`; a `@Volatile` runtime augmentation (`setSapiVoices`) so voice lookups are
  SAPI-aware; ids `sapi:<code>:<VoiceName>`; culture→code reduction (`en-US`→`en`, `zh-CN`→`zh`, `ta-IN`→
  `ta`), which lets SAPI introduce **new walkthrough languages** the static registry never had (names from
  .NET `CultureInfo`). `TtsService`: Windows-only `sapiVoices()` discovery (a `powershell -EncodedCommand`
  enum) cached + pushed into `Languages`; `speak()` routes `engine=sapi` with the bare voice name.
- UI: SAPI voices ride the existing per-voice dropdown (robotic controls stay hidden); the sidebar re-pushes
  voice_options once the async probe returns; settings combos + language menu built from `allLanguages()`
  ("Windows (SAPI)" tier note); the dead global `ENGINE_SAPI` engine option retired (per-voice now).
- **MVP boundary:** SAPI rides the *running* Kokoro server (needs Kokoro ready), exactly like espeak. A
  "SAPI with zero Kokoro/Python" launch mode is future. VERSION `0.6.0` → `0.7.0`.
- **Online caveats surfaced:** internet required, unofficial Edge endpoint, text sent to Microsoft — all
  documented in `docs/tts.md`; classic/offline SAPI voices have none. *Pending:* runIde UI pass.

**Phase L8 — Piper offline-neural tier (the ~40 languages Kokoro can't voice, with no online calls). ✅ CODE-COMPLETE + gated end-to-end (2026-07-24); runIde UI pass pending.**
A fourth **per-VOICE** engine (`Engine.PIPER`), **cross-platform** (unlike SAPI). Piper (VITS ONNX voices via
the `piper-tts` pip package) is the missing middle tier: **neural quality, fully offline**, for the ~40 locales
Kokoro doesn't cover — strictly better than espeak's robotic tier wherever a Piper voice exists, and unlike the
SAPI Edge tier it needs no internet and runs on macOS/Linux too. Architecturally it's the **Kokoro shape** (a
pip dependency + per-voice model downloads, run **in-process**), not the SAPI shape (no PowerShell worker).
Positioned per voice, so one language can offer kokoro + espeak + WinSAPI + **piper** side by side.

*L8.0 — De-risk spike first (mirrors §12 and the L7 probe — pin the moving parts before wiring).*
- [x] Pin the package + current API on the dev machine: `pip install piper-tts`, then `from piper import
      PiperVoice`; `PiperVoice.load(onnx, config)` and iterate `voice.synthesize(text)` chunks
      (`chunk.audio_int16_bytes`, `chunk.sample_rate`). The API moved across versions — record the exact
      import + iteration that actually works, and the wheel's platform coverage (Win/mac/Linux).
- [x] Confirm onnxruntime reuse (already pulled by kokoro-onnx) and that Piper's bundled espeak-ng phonemizer
      data does NOT collide with our espeak `voices/!v` variant hackery (separate data dir → expected fine).
- [x] Download one non-Kokoro voice (e.g. `de_DE-thorsten-medium`, `ru_RU-*`, `uk_UA-*`) from HF
      `rhasspy/piper-voices`; synth a sentence → measure sample rate (22 050 vs 16 000 for x_low), RMS,
      latency; confirm int16→float32-LE matches the existing wire.
- [x] Lock the model-storage layout (`<model-dir>/piper/<voiceKey>.onnx` + `.onnx.json`) and confirm the
      catalog URL (`voices.json`) is stable.

#### ✅ De-risk result — PROVEN (standalone spike, dev machine, 2026-07-24)

`piper-tts 1.6.0` (Python 3.13) validated every L8 assumption before any wiring. Refinements the spike
forced into the plan below are flagged **[spike]**.

- **No dependency conflict.** `pip install --user piper-tts` reused the existing `onnxruntime 1.27.0` +
  `numpy 2.5.1` (its bound is `onnxruntime<2,>=1`, `numpy>=1.21.6`) and added only `pathvalidate`. Nothing
  Kokoro depends on was downgraded. **[spike]** so `TtsSetup.installPiper()` is a plain additive `pip
  install piper-tts` — no version pins, no risk to the Kokoro stack.
- **Cross-platform wheels exist for 1.6.0** — `cp39-abi3` (Python 3.9+) for win_amd64, macosx x86_64 +
  arm64, and manylinux_2_28 x86_64 + aarch64 (34 MB each). Confirms the "unlike SAPI, runs on mac/Linux"
  positioning is real.
- **API pinned (it did move).** `from piper import PiperVoice, SynthesisConfig, AudioChunk`.
  `PiperVoice.load(onnx_path, config_path)` (1.75 s). Synthesis is
  `voice.synthesize(text, syn_config=SynthesisConfig(...)) -> Iterable[AudioChunk]`. **[spike]** speed and
  speaker are **not** kwargs on `synthesize` — they live on `SynthesisConfig(length_scale=1/speed,
  speaker_id=...)`. `length_scale=1/speed` verified monotonic (speed 0.7/1.0/1.5 → 1.90/1.36/1.12 s).
- **Audio format is simpler than assumed.** **[spike]** `AudioChunk.audio_float_array` is already
  `float32` numpy in [-1, 1] → `.astype('<f4').tobytes()` is float32-LE *directly*, so `PiperEngine`
  needs **no** `_wav_to_float32le` round-trip (unlike espeak/SAPI); it's the Kokoro path exactly.
  (`audio_int16_bytes` also exists and matched to 6e-5 — not needed.)
- **Sample rate confirmed** = `config["audio"]["sample_rate"]`: **16 000 for x_low** (`de_DE-eva_k-x_low`),
  22 050 for medium. Read straight from the tiny `.onnx.json` sidecar — **[spike]** so `do_check` reports
  each installed voice's rate/`num_speakers`/`language` *without loading the ONNX*. The per-stream `meta`
  frame already carries `sampleRate`, so mixed rates need zero wire changes.
- **Sentence handling.** **[spike]** `synthesize()` splits internally (3 chunks for 3 sentences) but the
  `AudioChunk` carries no source text — so drive it **one sentence at a time** via our own
  `split_sentences` (concatenate that sentence's chunks → one `(pcm, sr, sentence)` tuple), preserving the
  read-along text mapping and matching every other engine. Verified per-sentence driving works.
- **Coexistence proven empirically (the big one).** Imported `kokoro_onnx` and then loaded + synthesized a
  Piper voice **in one process** — no crash, no espeak-data collision. Piper ships its **own** bundled
  `site-packages/piper/espeak-ng-data` (a `load()` default arg), fully isolated from `espeakng_loader`'s
  data dir and our `!v` variant files. So one persistent server hosts kokoro + espeak + piper together.
- **Download + storage locked.** Catalog `https://huggingface.co/rhasspy/piper-voices/resolve/main/
  voices.json` = 170 voices across **50 language families**, fetched in 0.4 s; per-voice `files` map gives
  the repo path + `size_bytes` + `md5_digest`. Files download from `.../resolve/main/<file-path-key>`
  (20.6 MB voice in 2.0 s, md5 verified). Storage layout `~/.cache/audio-explainer/piper/<voiceKey>.onnx`
  + `.onnx.json` works as planned.
- **Performance.** RTF ≈ 0.043 (~23× realtime, faster than Kokoro); RMS ≈ 0.14 (healthy, not silence).

*Server (`tts_server.py`).* ✅ DONE + gated (2026-07-24, VERSION bump deferred to end of L8).
- [x] `PiperEngine` (in-process, like `KokoroEngine`): lazily `PiperVoice.load(onnx, cfg)` each voice and
      **cache loaded voices** in a dict. Drive **one sentence at a time** via `split_sentences` (its CJK
      split covers `zh_CN`); for each, `voice.synthesize(sentence, syn_config=SynthesisConfig(length_scale=
      1/speed, speaker_id=...))`, concatenate the chunks' `audio_float_array` and emit
      `.astype('<f4').tobytes()` — already float32-LE, **no `_wav_to_float32le` round-trip needed** (it's the
      Kokoro path). Sample rate from `chunk.sample_rate` / config `audio.sample_rate`. **[L8.0 spike]**
      (sr captured from first chunk; `PIPER_SUBDIR`/`piper_dir()` helpers added.)
- [x] `EngineHost`: add an `allow_piper` flag + a `resolve()` route for `engine == "piper"` (lazily
      construct, like `_espeak`/`_sapi`). Also threads `model_dir` into the host for `PiperEngine`.
- [x] `main()`: build `EngineHost(..., model_dir=…, allow_piper=_can_import("piper"))`; off `--synthetic`.
- [x] `do_check`: add `piperImportable` + `piperVoices` list from scanning the piper models dir
      (`[{key, lang, quality, sampleRate, speakers, sizeBytes}]`, read from each `.onnx.json` sidecar without
      loading the ONNX). Mirrors `espeakLanguages`/`languagesReady`.
- [x] Voice-download subcommand: `--download-piper <voiceKey>` fetches `<key>.onnx` + `.onnx.json` from HF into
      the models dir via `_fetch_piper_catalog()` + `_download`'s progress frames. (Full catalog fetch → L9.)

  **Gate result (PASSED):** `py_compile` clean. One server process (Kokoro primary) served **piper +
  kokoro + espeak** with no regression — piper `de_DE-eva_k-x_low` streamed float32-LE at the correct
  per-chunk 16 kHz (meta advertises 22.05 k, chunk carries 16 k), RMS ≈ 0.14; speed 1.3 shortened the
  audio (`length_scale` working); the cached voice was reused on a second request; an unknown voice
  returned a clean "not installed — add it under Settings → Audio engines → Piper" error. `--check`
  reports `piperImportable:true` + both installed voices; `--download-piper` downloaded `uk_UA-lada-x_low`
  (progress + done frames, both files landed). Kokoro (24 k, RMS 0.078) + espeak (22.05 k, RMS 0.096)
  unchanged.

*Kotlin registry (`Languages.kt`).* ✅ DONE (2026-07-24, compileKotlin exit 0).
- [x] `enum class Engine { KOKORO, ESPEAK, SAPI, PIPER }` — broke the exhaustive `when(Engine)`s as expected;
      fixed `engineTag` (→ `"piper"`) and `voiceTiers` (→ `"piper"`).
- [x] Id scheme `piper:<code>:<voiceKey>` + `PIPER_PREFIX` const; `engineOfVoice`/`languageOfVoice` parse the
      prefix (like `sapi:`); added `piperVoiceKey(id)` helper (mirrors `sapiVoiceName`).
- [x] Dynamic-language augmentation **generalized** (per the plan): replaced the SAPI-only `SapiData` with a
      shared `Discovered(byCode, names)` layer + a `discover()` builder; `@Volatile sapi` **and** `@Volatile
      piper`, a `sources()` list, and `merged`/`dynamicLanguage`/`allLanguages` now fold **both** sources —
      so a code discovered by both SAPI and Piper becomes **one merged language** (impossible with parallel
      copies). `setSapiVoices`/`setPiperVoices` are thin `discover()` calls.
- [x] Locale→code reduction handled in `TtsService.parsePiperVoices` (`family` from the sidecar, else locale
      prefix). Curated set (de, ru, uk, …) are all already static espeak languages → Piper voices **merge in**
      (upgrade robotic→neural), no new dynamic langs; the dynamic-language path is exercised only by non-registry
      Piper locales (names come from the sidecar's `nameEnglish`/`nameNative`, added to `--check`).

*Kotlin service + wiring.* ✅ DONE.
- [x] `TtsService.speak`: `Engine.PIPER` branch → `engine=piper`, bare `piperVoiceKey`, `lang` hint, no `tune`.
- [x] `TtsService`: `--check` piper fields (`piperImportable`, `piperVoices`) parsed into `TtsStatus`
      (+ `InstalledPiperVoice`, `cachedPiperImportable()`/`cachedPiperVoices()`). Piper rides `--check`;
      `syncPiperVoices` pushes into `Languages.setPiperVoices` after each fresh probe; the sidebar re-push is
      triggered from `scheduleDiscoveredVoiceRefresh`.
- [x] `TtsSetup`: `installPiper()` + `downloadPiperVoice(key)` (`Task.Backgroundable` + `withDone`; the latter
      installs `piper-tts` first if missing). `TtsService.installPiper`/`downloadPiperVoice` stream the pip /
      `--download-piper` progress.
- [x] `NarrationController.diagnoseTtsError`: `Engine.PIPER` branch (missing/corrupt model → re-download).

*Settings UI (`HighlightSettingsConfigurable.kt`).* ✅ DONE.
- [x] Replaced the "Piper — planned" placeholder with a real group (**not** Windows-gated): `piperStatusLabel`,
      an "Install Piper (pip)" button, and an "Add a language voice…" button → a `JBPopupFactory` chooser over
      the curated set (installed ones filtered out). No separate model-dir field — Piper reuses the Kokoro model
      dir (`…/piper`), noted in the row comment. *(L9: the picker button → "Manage voices…" opening
      `PiperVoiceManagerDialog`; the `JBPopupFactory` chooser + `showPiperVoicePicker` were removed.)*
- [x] Folded into `recheckAll()`/`refreshEngineStatusLabels()` + `piperStatusText()`; seeded from cached status;
      refreshed via `invokeLater(…, ModalityState.any())`.

*Sidebar / docs.* ✅ DONE.
- [x] Piper voices carry engineTag `"piper"`; robotic controls stay hidden (`v.engine == Engine.ESPEAK`). The
      old `scheduleSapiVoiceRefresh` was generalized to `scheduleDiscoveredVoiceRefresh` (SAPI **and** Piper
      discovery → single re-push if the current language's voice list grew).
- [x] `docs/tts.md`: Piper tier row + a Piper section (install, per-voice download, offline, cross-platform);
      wire-protocol + `--check`/`--download-piper` notes. **VERSION 0.7.3 → 0.7.4.**

*Curated MVP set.* ✅ DONE (L8) — `TtsSetup.CURATED_PIPER_VOICES`: one **medium** voice each for de, ru, uk, nl,
pl, tr, cs, ar, vi, sv (real catalog keys, verified 2026-07-24). *(L9: REMOVED — superseded by the full
`--piper-catalog` / `PiperVoiceManagerDialog`; the constant + `CuratedPiperVoice` no longer exist.)*

*Gate.* ✅ PASSED — py_compile + compileKotlin clean. Standalone engine gate: `piper-tts` installed + voices
downloaded (`de_DE-eva_k-x_low`, `uk_UA-lada-x_low`), synth a non-Kokoro language end-to-end over the wire
(float32-LE, correct per-voice 16 kHz), coexisting with Kokoro + espeak on the same server process (no
regression); cached-voice reuse + clean unknown-voice error verified. **runIde UI pass still pending**
(install Piper, download a voice via the picker, pick it in the sidebar, narrate; settings group renders +
status refreshes) — GUI test, user-launched.

**Phase L9 — Piper voice catalog & manager. ✅ CODE-COMPLETE + gated (2026-07-24, VERSION 0.7.5); runIde UI
pass pending.** The full download/management experience on top of L8's engine. Two design forks were settled
with the user up front: **install-first** language reach + **settings-only** manager UI.
- [x] Fetch + parse the HF `rhasspy/piper-voices` **`voices.json`** catalog (170 voices / 50 locales): family/
      code/native+english names, region, quality, num_speakers, speaker_id_map → speaker names, per-file sizes
      + md5. *How it landed:* server `--piper-catalog` (`_normalize_piper_catalog` → compact one-row-per-voice
      JSON, sorted by language/quality), cached to `…/piper/catalog.json` (`_piper_catalog`); `--refresh` re-
      fetches; offline-tolerant (falls back to the stale cache, then empty with an `error`). Verified live: 170
      voices, `source` network→cache on re-run. Kotlin `TtsService.piperCatalog(refresh)` + `PiperCatalogVoice`.
- [x] A **voice-manager UI** — a dedicated dialog. *How it landed:* `PiperVoiceManagerDialog` (`DialogWrapper`,
      `TableView`/`ListTableModel`): columns Language/Voice/Quality/Speakers/Size/Installed, a `SearchTextField`
      filtering language+voice+quality, Download (reuses `TtsSetup.downloadPiperVoice` — installs piper if
      missing, md5-verifies), Delete (`TtsSetup.deletePiperVoice` → `--delete-piper`), Refresh, and a disk-usage
      + offline status line. Catalog load + post-op re-discovery run off-EDT (`ModalityState.any()`); action
      buttons disable while an op is in flight. Opened from **Settings → Audio engines → Piper → "Manage voices…"**.
- [x] Multi-speaker voices: `speaker_id` on the wire. *How it landed:* voice key gains an optional `#<n>` suffix
      (`piper:<code>:<key>#<n>`); server `PiperEngine._split_speaker` → `SynthesisConfig(speaker_id=n)`.
      Discovery **expands** a multi-speaker installed model into one dropdown voice per speaker (`syncPiperVoices`,
      label "… — Speaker n" / model speaker name when meaningful), capped at `MAX_EXPANDED_SPEAKERS = 24` (huge
      research sets like VCTK/libriTTS get default-speaker only). `#N` parsing + single-speaker no-regression verified.
- [x] Full dynamic language expansion from the catalog — **install-first** (user's choice): the manager reaches
      every catalogue locale to download; a downloaded voice registers its language via the existing L7/L8
      discovery path (`Languages.setPiperVoices`), so it appears in the language menu. No download-on-select.
- [x] Sidebar vs settings — **settings-only** (user's choice): the manager lives in settings; the sidebar keeps
      auto-discovering installed voices (`scheduleDiscoveredVoiceRefresh`) with no new download UI.

---

## 9. Decisions (confirmed)

1. **Single language per file.** No parallel translations inside one file; a re-translate produces a
   new file. ✅
2. **First-ship scope = {en, es, fr, hi, it, pt}** (Kokoro, no extra deps); **Japanese + Chinese
   deferred to L5** (misaki). ✅
3. **Voice memory = single "last voice" + per-language default fallback** (baseline; the per-language
   map is a later nicety if it feels lossy). ✅
4. **Interactive language selection = inferred/ad hoc** by the skill; no new mandatory question in
   `docs/assess.md`. ✅

Plus (from the follow-up): **a robotic espeak-ng fallback tier for the other ~100 espeak languages** —
see §12, phased as L6.

---

## 10. Risks & limitations

- **Not 127 languages** — quality is bounded to Kokoro's 8. Setting expectations in the settings hint
  matters. (An "espeak-only robotic mode" or the SAPI Phase 7b path could reach more languages at much
  lower quality; explicitly out of scope.)
- **Sparse voice sets** — French has a single voice (`ff_siwis`); Italian/Spanish/Portuguese have 1–3.
  The combo will legitimately look short for those. Not a bug.
- **misaki weight** — `misaki[zh]`/`misaki[ja]` add large deps and download time; keep them opt-in and
  cancellable (reuse the existing background-task setup flow).
- **CJK read-along** — per-word cursor degrades to per-sentence for ja/zh (no whitespace tokenisation).
- **Voice/language mismatch** — the reconciliation step (§6) must run on every load, or a Spanish
  walkthrough could try to speak with an English voice. Covered by the `Languages.languageOfVoice`
  check.
- **Existing walkthroughs** — safe: no `language` ⇒ `en`, verified by the default + `ignoreUnknownKeys`.
- **Sandbox testing** — prefer the standalone `tts_server.py`/mock-server verification (see the memory
  notes on `[[verify-headless-claude-without-sandbox]]` and `[[tts-stack-installed-dev-machine]]`); the
  `runIde` sandbox is slow and jar-locks if a prior instance is running.

---

## 11. File-by-file change checklist

| File | Change |
|---|---|
| `Languages.kt` *(new)* | Canonical language/voice registry + helpers |
| `Protocol.kt` | `Plan.language = "en"` |
| `Walkthrough.kt` | `language` in state/snapshot; `setPlan(...language)`; restore in `restorePrevious` |
| `ExplainerServer.kt` | thread `language` through set_plan/load/save/state |
| `WalkthroughStorage.kt` | `save(...language...)`; `Plan(...,language)` |
| `scripts/tts_server.py` | prefix→lang map; optional `lang` override; `--check` language readiness; misaki guards |
| `VERSION` | bump (re-installs skill + tts_server) |
| `AudioExplainerSettings.kt` | `explanationLanguage`; voice-per-language policy |
| `HighlightSettingsConfigurable.kt` | language combo; language-aware voice combo + ja/zh hint |
| `AudioExplainerSidebar.kt` | `language` in `update`; `pushVoiceOptions`; reconcile voice on `onPlan`; validate `voice_change` |
| `sidebar.html` | dynamic `#voice-select`; language badge element |
| `sidebar.js` | handle `voice_options`; render language badge; CJK read-along caveat |
| `ClaudeExplainer.kt` | language instruction in `buildPrompt`; `language` in schema example |
| `docs/explain-at-cursor.md`, `docs/plan.md`, `SKILL.md`, `docs/segments.md` | document `language`, target-language prose rule |
| `docs/tts.md` | expand the Voices section to the full multi-language catalogue |

---

## 12. Broader language coverage via a lower-quality fallback engine (Phase L6)

**Yes — we can cover ~100 more languages at lower quality, and the best route is espeak-ng itself as a
*synthesis* engine (not just its phonemizer), not Windows SAPI.**

### ✅ De-risk result — PROVEN (standalone spike, dev machine, 2026-07-23)

A throwaway ctypes spike against the shipped library validated every L6 assumption before any UI work:

- **Synthesis-capable, zero download.** `espeakng_loader`'s `espeak-ng.dll` exposes the full synth API
  (`espeak_Initialize/Synth/SetSynthCallback/SetVoiceByName/Synchronize/ListVoices`); init returns
  **22 050 Hz**. No extra install beyond what Kokoro already pulled in.
- **140 languages present** in the bundled data (141 voice entries) — all 14 spot-checked (de, pl, ru,
  ar, nl, tr, sv, cs, el, ko, vi, uk, fa, th) available.
- **Real audio in Latin, Cyrillic and RTL scripts** — German/Russian/Arabic each produced 3–4 s of
  speech at RMS ≈ 0.09 (comparable to Kokoro's ≈ 0.074; not silence). WAVs saved and sane-sized.
- **Coexistence is clean (the big one).** `espeak → Kokoro → espeak` in **one process** all worked —
  Kokoro synth after espeak PASS, espeak synth after Kokoro PASS. So **one persistent server can host
  both engines**; no separate espeak process, no re-init-per-call needed.
- **Wire format drops in.** int16 → float32-LE → ≤12 000-sample reframe → base64 yields the exact
  `tts_server` chunk shape (≤64 KB base64 frames). A numpy-only linear resample 22 050→24 000 Hz works
  if we ever want to unify rates (not required — the webview already honours a per-stream `sampleRate`).
- **Pure ctypes** against the loader's DLL — **no `py-espeak-ng` dependency** after all.

Net: §12 stands as written; the sketch below is updated to the confirmed facts.

### Why espeak-ng is the right fallback (and beats SAPI here)

- **It's already on disk.** `kokoro-onnx` pulls in **`espeakng-loader`**, which ships the espeak-ng
  **shared library + voice data** and exposes `get_library_path()` / `get_data_path()`. We already use
  it for Kokoro's phonemization. The same library is the full synthesizer (`espeak_Synth`), so a
  robotic-TTS tier costs **≈zero extra download**.
- **107 languages/accents**, and **cross-platform** (Windows/macOS/Linux) — unlike SAPI, which is
  Windows-only and would leave macOS/Linux users of this cross-platform plugin with nothing.
- **It reuses the entire existing pipeline.** `tts_server.py` already has an engine abstraction
  (`KokoroEngine`, `SyntheticEngine`) behind one TCP wire protocol, and `NarrationController` /
  the webview are engine-agnostic. Adding an `EspeakEngine` is a drop-in third engine — no new Kotlin
  audio plumbing, just an engine selector.
- **Text isn't the bottleneck — voice is.** Claude can already write the prose in any language it
  knows (German, Polish, Russian, Arabic, …). So the language *menu* can be large; only the *voice
  quality* tier varies. espeak fills the gap for everything Kokoro can't voice.

### Engine tiers (auto-selected per language, overridable)

| Tier | Engine | Languages | Quality | Platform | Cost |
|---|---|---|---|---|---|
| 1 | **Kokoro-82M** | the 8 (en, es, fr, hi, it, pt [+ ja, zh]) | natural / neural | all | model already downloaded |
| 2 | **espeak-ng** | ~130 more (de, pl, ru, ar, ko, tr, …; **140 total** in the bundled build) | robotic but intelligible | all | **none** (lib+data already present — verified) |
| 3 *(opt., Win)* | **SAPI** | whatever OS voices the user installed | decent | Windows only | OS voices |
| — *(future)* | Piper | ~30 langs | neural, mid | all | per-voice model downloads |

The settings language selector labels each entry with its tier, e.g. **"German — espeak (robotic)"**
vs **"Spanish — Kokoro (natural)"**, so expectations are set at selection time. Engine is chosen
automatically from the language (Kokoro if a voice exists, else espeak; SAPI only if the user opts in
on Windows), with a manual override in settings.

### Implementation sketch (Phase L6)

- **`tts_server.py` → new `EspeakEngine`:** locate the library via `espeakng_loader.get_library_path()`
  / `get_data_path()` and synthesize PCM **directly via ctypes** against `espeak_Synth` with a sample
  callback (zero extra deps — confirmed; no `py-espeak-ng` needed). `espeak_Initialize` on the parent of
  the data dir returns **22 050 Hz, 16-bit mono**; convert to float32-LE and report `sampleRate: 22050`
  in the `meta` frame (the webview honours per-stream rate — verified). Keep the synth callback ref alive
  (GC hazard) and reuse the existing `split_sentences` + ≤0.5 s framing loop. Coexists with Kokoro's
  phonemizer in the same process — **no separate server process required**.
- **Voice/variant:** for an espeak language the "voice" is the language code (`de`, `pl`, …) plus an
  optional male/female **variant** (`+f3` / `+m3`). The voice combo offers those variants instead of
  named voices.
- **`--check`:** already reports espeak availability implicitly (it's required for Kokoro); add
  `"espeakLanguages"` so the plugin can populate the tier-2 menu from what the local espeak build
  actually supports (`espeak-ng --voices`).
- **`Languages.kt`:** grow the registry with the espeak set, each entry tagged `engine = ESPEAK` and
  `quality = ROBOTIC`. `AudioExplainerSettings.ttsEngine` already has a slot; add `ENGINE_ESPEAK`
  and/or an `auto` mode that picks per language.
- **No wire/protocol/VERSION-contract break** beyond the L2 bump — it's another engine behind the same
  socket.

### Caveats

- **Quality is genuinely robotic** — fine for "I just need to follow along in my language," not for
  polished narration. The tier label manages that.
- **Read-along** degrades to per-sentence for non-space-delimited scripts (same note as ja/zh).
- **Voice count per espeak language is ~1–2 variants**, not a rich set.
- If robotic isn't good enough for a language you care about, **Piper** (neural, ~30 languages,
  cross-platform, ONNX like Kokoro) is the natural "Tier 1.5" — but it's a real integration (per-voice
  model downloads + a loader), so it's noted as future, not L6.

**Sources:** [espeak-ng (107 languages, cross-platform synthesizer)](https://github.com/espeak-ng/espeak-ng),
[espeakng-loader (ships the lib + data)](https://github.com/thewh1teagle/espeakng-loader),
[py-espeak-ng `synth_wav()`](https://github.com/gooofy/py-espeak-ng).
