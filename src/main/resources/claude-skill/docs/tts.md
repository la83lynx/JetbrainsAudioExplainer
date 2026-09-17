# TTS Reference

## Engine

**Kokoro-82M** via **`kokoro-onnx`** (ONNX Runtime) — top-ranked open-source TTS, runs locally and
cross-platform (Windows/macOS/Linux). This is the cross-platform rewrite of code-explainer's
Apple-only `mlx-audio` engine; the Kokoro *weights* are identical, only the runtime changed.

Four synthesizer tiers behind one socket, chosen **per voice** (not per language — any language can offer
voices from more than one tier):

| Tier | Engine | Languages | Quality | Cost |
|---|---|---|---|---|
| 1 | **Kokoro-82M** | the 8 trained: en, es, fr, hi, it, pt (+ ja, zh with misaki packs) | natural / neural | model download (~340 MB) |
| 2 | **espeak-ng** | ~100 more (de, ru, pl, nl, tr, ar, ko, …) | robotic but intelligible | **none** — bundled with `kokoro-onnx` |
| 3 | **Windows SAPI** (System.Speech) | whatever SAPI5 voices are installed; with NaturalVoiceSAPIAdapter, neural Edge voices for ~140 locales | classic → neural (online) | **none**; Windows only; online voices need internet |
| 4 | **Piper** (VITS ONNX, `piper-tts`) | ~50 in the catalogue (the ~40 Kokoro can't voice: de, ru, uk, nl, pl, tr, cs, ar, vi, sv, …) | neural, **offline** | `pip install piper-tts` + per-voice model (~20–75 MB); cross-platform |

The espeak tier (Phase L6) is a *fallback* for the languages Kokoro can't voice. `kokoro-onnx` already
pulls **`espeakng-loader`** (we use it for Kokoro's phonemization); the same shared library is a full
synthesizer, driven directly via `ctypes` (no extra dependency, no download). Quality is genuinely
robotic — fine for "follow along in my language," and the settings label each such language
"espeak (robotic)" so expectations are set at selection time. Engine choice is automatic from the
walkthrough's language (Kokoro if it has a voice, else espeak).

Persistent server (`scripts/tts_server.py`) loads the model once. First call pays the model-load cost
(~seconds); subsequent calls stream in ~hundreds of ms. In the IDE plugin the server is started and
owned by the plugin (`TtsService`) — it is **not** the agent's job to start it during a plugin-driven
walkthrough. For interactive/standalone use you can run it yourself (see below).

## Transport

**TCP loopback**, `127.0.0.1:<port>` (code-explainer used a Unix domain socket — dropped for
portability). On startup the server prints one JSON handshake line to stdout so the launcher learns the
port: `{"ready":true,"port":<n>,"engine":"kokoro","sampleRate":24000}`.

Wire protocol — newline-delimited JSON, **one request per connection**:

- client → server: `{"text": "...", "voice": "af_heart", "speed": 1.0}` or `{"ping": true}`.
  An optional `"lang"` overrides the phonemizer language; normally the server derives it from the voice
  prefix (`ef_dora` → Spanish, `ff_siwis` → French, …), so just picking a voice is enough.
  An optional `"engine": "espeak"` routes to the robotic tier; the `voice` is then an espeak spec
  (a language code plus an optional `+ae_f`/`+ae_m` variant, e.g. `"de+ae_f"`). An optional `"tune"`
  object adjusts the robotic voice — two groups:
  - **runtime** (`espeak_SetParameter`): `"pitch": 0-100`, `"range": 0-100` (liveliness/intonation),
    `"wordgap": 0-50` (extra inter-word gap, ×10 ms).
  - **Layer-2** (regenerate the variant file, then reload it): `"consonants"` (clarity, ~80-160),
    `"roughness"` (0 = smoothest), `"flutter"` (pitch wobble), `"breath"` (breathiness).

  An optional `"engine": "sapi"` routes to the Windows System.Speech tier (Phase L7, Windows only); the
  `voice` is then an installed SAPI5 voice **name** (e.g. `"Microsoft Aria Online"`), `tune` is ignored,
  and `speed` maps onto SAPI's rate. An optional `"engine": "piper"` routes to the Piper offline-neural tier
  (Phase L8); the `voice` is then a Piper voice **key** (e.g. `"de_DE-eva_k-x_low"`), `tune` is ignored, and
  `speed` maps to `length_scale = 1/speed`. For a **multi-speaker** Piper model, append `#<n>` to the key to
  pick a speaker (0-based, e.g. `"bn_BD-google-medium#3"`); no suffix uses the model default (Phase L9).

  Every field is clamped and defaults applied when omitted. Absent `engine`, the primary (Kokoro) engine
  serves the request and ignores `tune`.
- server → client: `{"type":"meta","sampleRate":24000,"engine":"kokoro"}` then repeated
  `{"type":"chunk","sampleRate":24000,"audio":"<base64 float32 LE PCM>"}`, then `{"type":"end"}`
  (or `{"type":"error","message":...}`; ping replies `{"type":"pong",...}`). The `meta` frame names the
  engine that served the request and its sample rate — Kokoro 24 kHz, espeak/SAPI **22.05 kHz**, Piper
  **16 kHz (x_low) or 22.05 kHz**; the webview honours the per-stream rate (each chunk carries its own
  `sampleRate`), so tiers — and even Piper voices of different quality — can differ.

The `audio` payload is raw float32 little-endian PCM (what Web Audio consumes directly).

## Behavior

- **Streaming** — one chunk per sentence, so playback starts on the first sentence.
- **Cancelable** — closing the socket aborts generation mid-stream.
- **Auto-canceling** — the plugin cancels the previous highlight's stream when advancing.

## Running it directly

```
python scripts/tts_server.py --port 0            # real Kokoro (needs the model; see below)
python scripts/tts_server.py --synthetic         # sine-tone engine, NO model/deps (for testing)
python scripts/tts_server.py --check             # prerequisite status JSON (+ languagesReady, espeakLanguages, piperVoices)
python scripts/tts_server.py --download          # fetch the model + voices into ~/.cache/audio-explainer
python scripts/tts_server.py --piper-catalog     # normalized Piper catalog JSON (~170 voices), cached; add --refresh to re-fetch
python scripts/tts_server.py --download-piper de_DE-eva_k-x_low   # fetch one Piper voice into …/audio-explainer/piper (md5-verified)
python scripts/tts_server.py --delete-piper  de_DE-eva_k-x_low   # remove one installed Piper voice
```

Setup: `pip install kokoro-onnx onnxruntime numpy` (pulls `espeakng-loader`, so no separate espeak-ng
install is needed), then `--download`. The IDE plugin automates all of this via its settings —
**Settings → Tools → Audio Explainer → Audio engines → Kokoro** (also reachable from
**Tools → Audio Explainer → Audio engines…**).

## Voices

Default: `af_heart`. Convention: **first letter = language/accent, second letter = gender**
(`f`=female, `m`=male). A walkthrough's `language` code determines which voices are eligible; the
plugin loads them into the sidebar's voice combo automatically. The server picks the phonemizer
language from the voice prefix, so a voice from the wrong language mispronounces the text — always use
a voice whose prefix matches the walkthrough language.

Kokoro-82M's full trained set (8 languages / 9 accents):

| `language` | Language | Prefix | Voices |
|---|---|---|---|
| `en` | English (US) | `af_` / `am_` | af_heart (default), af_bella, af_sarah, af_alloy, af_aoede, af_jessica, af_kore, af_nicole, af_nova, af_river, af_sky, am_adam, am_michael, am_echo, am_eric, am_fenrir, am_liam, am_onyx, am_puck, am_santa |
| `en` | English (UK) | `bf_` / `bm_` | bf_emma, bf_alice, bf_isabella, bf_lily, bm_george, bm_daniel, bm_fable, bm_lewis |
| `es` | Spanish | `ef_` / `em_` | ef_dora, em_alex, em_santa |
| `fr` | French | `ff_` | ff_siwis |
| `hi` | Hindi | `hf_` / `hm_` | hf_alpha, hf_beta, hm_omega, hm_psi |
| `it` | Italian | `if_` / `im_` | if_sara, im_nicola |
| `pt` | Portuguese (BR) | `pf_` / `pm_` | pf_dora, pm_alex, pm_santa |
| `ja` | Japanese | `jf_` / `jm_` | jf_alpha, jf_gongitsune, jf_nezumi, jf_tebukuro, jm_kumo |
| `zh` | Chinese (Mandarin) | `zf_` / `zm_` | zf_xiaobei, zf_xiaoni, zf_xiaoxiao, zf_xiaoyi, zm_yunjian, zm_yunxi, zm_yunxia, zm_yunyang |

US vs UK English is one *text* language (`en`) with two voice groups — the accent is a voice choice.
**Japanese needs an extra pip pack** (`misaki[ja]`), installed from **Settings → Tools → Audio Explainer →
Audio engines → Kokoro → "Add / reinstall Japanese & Chinese voices"**; until it is there the server returns
a clean "needs misaki" error and `--check` omits `ja` from `languagesReady`. It is a hard requirement
because the server phonemizes Japanese **itself**: `kokoro-onnx` hands every language to espeak-ng, whose
`ja` voice has no kanji dictionary and reads kanji aloud as *"Chinese letter"*, so `ja` goes through misaki
(Kokoro's own G2P) with Latin identifiers phonemized as English and the result passed as phonemes.
**Chinese does not need `misaki[zh]`** — espeak's `cmn` voice has a Han dictionary and handles it — though
the button installs both. No other language needs anything beyond the base install.

**Robotic tier (espeak) voices.** The robotic "voice" is a language code plus a variant — `de+ae_f`
(female) or `de+ae_m` (male). `ae_f`/`ae_m` are **custom variants the server installs** into espeak's
`voices/!v` dir: the stock numbered variants (`f2`–`f5`, `m2`) bake in an `echo` directive that sounds
like reverb, and most female variants add `roughness`, so ours strip both and raise consonant amplitude
for clarity. The server **regenerates** a variant file from the request's Layer-2 `tune` params
(consonants/roughness/flutter/breath) and reloads it via `SetVoiceByName`; runtime `tune` params
(pitch/range/word-gap) go through `espeak_SetParameter`. If the install/regeneration can't write
(read-only environment), an unknown `+ae_*` variant falls back to the base language voice — no error.
Default speaking rate is 160 wpm (lowered from espeak's 175 — clearer).

Robotic voices are offered **both** as an espeak-tier language's whole list *and* appended to each
Kokoro-tier language (labelled "Robotic (F)"/"Robotic (M)") — so any language can be narrated robotically.
Per-variant parameters are edited in the sidebar (inline Pitch/Liveliness/Word-gap) and the Advanced
voice-editor dialog (adds Clarity/Smoothness/Flutter/Breath), saved per variant and shared across
languages. `--check` reports the local build's supported codes in `espeakLanguages` (≈140). No download —
the espeak library + voice data ship with `kokoro-onnx`.

**Windows SAPI tier (Phase L7, Windows only).** A third tier that reaches whatever SAPI5 voices the
machine has installed. The plugin discovers them at runtime (via System.Speech), maps each voice's culture
to a walkthrough language code (`en-US`→`en`, `zh-CN`→`zh`, `ta-IN`→`ta`, …), and offers them in the voice
combo as `sapi:<code>:<VoiceName>` ids. Every voice entry carries an engine badge for consistency across
tiers (`Heart (US, F) — kokoro`, `Robotic (F) — espeak`, `Aria (F) — WinSAPI, online`), and the settings
language menu lists the tiers each language offers (e.g. `English (English) — kokoro, espeak, WinSAPI`).
Because the set is per-machine, SAPI voices
can introduce **new walkthrough languages** the fixed Kokoro/espeak registry never listed. Synthesis goes
through a persistent Windows-PowerShell worker (System.Speech → WAV → the same float32-LE wire); zero extra
pip deps (PowerShell + .NET are in-box).

Out of the box a machine only has its display-language voices (classic, offline). The big win is optional
and user-installed: **[NaturalVoiceSAPIAdapter](https://github.com/gexgd0419/NaturalVoiceSAPIAdapter)** (a
third-party SAPI5 provider — the plugin does **not** bundle or require it) re-exposes the Windows "natural"
voices and, with its **Edge online voices** enabled, ~140 locales of neural Azure voices as ordinary SAPI5
voices. Caveats for those: they are **online** (each sentence is a network call to Microsoft's endpoint —
~0.3 s warm / ~1 s cold — so narration text is sent to Microsoft and needs internet), and the endpoint is
unofficial (could change/rate-limit). Classic/offline SAPI voices have none of those caveats. The tier is
Windows-only and absent on macOS/Linux (those stay on Kokoro + espeak).

**Piper tier (Phase L8, cross-platform).** The missing middle tier: **neural quality, fully offline**, for
the ~40 languages Kokoro can't voice — strictly better than the robotic espeak tier where a Piper voice
exists, and unlike the SAPI Edge tier it needs no internet and runs on macOS/Linux too. Voices are
[Piper](https://github.com/OHF-Voice/piper1-gpl) VITS ONNX models from the
[`rhasspy/piper-voices`](https://huggingface.co/rhasspy/piper-voices) catalogue (~170 voices / ~50
languages). It's the **Kokoro shape**, not the SAPI shape: a pip dependency (`piper-tts`, additive — it
reuses the onnxruntime/numpy Kokoro already pulled) plus per-voice model downloads, run **in-process** (no
PowerShell worker). Each voice is `<key>.onnx` + `.onnx.json` under `~/.cache/audio-explainer/piper/`; the
plugin discovers installed voices via `--check`'s `piperVoices` and offers them as `piper:<code>:<voiceKey>`
ids with the engine badge `— piper`. Speed maps to `length_scale = 1/speed`; sample rate is per voice
(16 kHz for `x_low`, 22.05 kHz otherwise). Install + add voices from **Settings → Tools → Audio Explainer →
Audio engines → Piper**: an "Install Piper (pip)" button plus a **"Manage voices…"** dialog (Phase L9) that
browses the whole catalogue (searchable, download size + md5-verified, delete with disk-usage). A failed
synth is usually a missing/corrupt model file → re-download the voice.

**Catalog & install-first (Phase L9).** The manager loads the normalized catalogue via `--piper-catalog`
(cached under `…/piper/catalog.json`, so it opens offline after the first fetch; `--refresh` re-fetches).
Design is *install-first*: a language becomes selectable for narration only once you've downloaded a voice
for it — the manager is how you reach the full catalogue to do that. Downloads md5-verify against the
catalogue digest and delete via `--delete-piper`. **Multi-speaker** models (a `num_speakers > 1` VITS model,
e.g. `bn_BD-google-medium`) are expanded into one dropdown voice per speaker with the `#<n>` key suffix; a
model with more than ~24 speakers (rare research sets like VCTK/libriTTS) offers only its default speaker to
keep the dropdown usable.

**CJK read-along caveat:** Japanese/Chinese write without spaces, so the sidebar's read-along cursor
tracks per *sentence* (not per word) for those — the server splits ja/zh on `。．！？…` and tags each
sentence, and the webview highlights a sentence at a time. Everything else (streaming, seek, narration)
works the same. The espeak tier is curated to space-delimited languages, so it keeps the normal
per-word read-along.

## Speed

Default: `1.0`. Always pass the user's speed setting (the plugin sends the sidebar's speed).

## Spoken text rules

- **Plain text only** — no backticks, bold, line numbers, file paths
- **Say identifiers in English, at least the first time.** `camelCase`, `PascalCase` and `snake_case` are read
  badly by every engine — run together, spelled out, or stressed on the wrong syllable. Write *"the readiness
  gate"* or *"the resolve-enabled flag"* rather than `isReadyToResolve`, and if the exact name matters, give it
  once in prose and then use the plain-English form. This decides what the user actually hears, so it is worth
  more care than the written `explanation`, which they can read at their own pace
- **1-2 sentences per step** — shorter than the written explanation. This is the fourth doc to have carried a number for it; `docs/plan-schema.md` binds, and 3-6 belongs to a podcast segment, not to a walkthrough step
- **Conversational** — as if explaining to a colleague
