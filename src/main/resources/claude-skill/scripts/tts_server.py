#!/usr/bin/env python3
"""
Audio Explainer TTS server — Kokoro-82M via kokoro-onnx, over a TCP loopback socket.

Cross-platform rewrite of code-explainer's Apple-Silicon-only ``tts_server.py``:

  | code-explainer (macOS)          | this server (cross-platform)                    |
  |---------------------------------|-------------------------------------------------|
  | mlx-audio (Apple MLX)           | kokoro-onnx (ONNX Runtime) — same Kokoro weights|
  | Unix domain socket              | TCP 127.0.0.1:<port>                            |
  | macOS ``say`` fallback          | (dropped; Windows SAPI is handled in the plugin)|

The model is portable; only the *engine* changed. The JSON request shape is preserved:
``{"text": ..., "voice": ..., "speed": ...}`` and ``{"ping": true}``. An optional ``"lang"`` may
override the phonemizer language; normally it is derived from the voice-name prefix (see PREFIX_LANG).

Wire protocol — newline-delimited JSON, ONE request per TCP connection:

  client -> server:   {"text": "...", "voice": "af_heart", "speed": 1.0}\\n
                      {"text": "...", "voice": "ef_dora", "lang": "es"}\\n   (explicit lang override)
                      {"text": "...", "voice": "de+f3", "engine": "espeak"}\\n  (robotic espeak tier)
                      {"text": "...", "voice": "Microsoft Aria Online", "engine": "sapi"}\\n  (Windows tier)
                      {"text": "...", "voice": "de_DE-eva_k-x_low", "engine": "piper"}\\n  (offline-neural tier)
                      {"ping": true}\\n

  An optional ``"engine": "espeak"`` routes the request to the bundled espeak-ng synthesizer (the
  ~100-language robotic fallback tier) instead of Kokoro; the ``voice`` is then an espeak
  voice spec (a language code plus an optional ``+f3``/``+m3`` variant). ``"engine": "sapi"`` routes to
  the Windows System.Speech tier (Windows only) — ``voice`` is then an installed SAPI5 voice
  NAME (e.g. "Microsoft Aria Online"); this reaches whatever voices the machine has, including the
  neural Edge "Online" voices surfaced by NaturalVoiceSAPIAdapter. ``"engine": "piper"`` routes to the
  Piper offline-neural tier (cross-platform, requires ``pip install piper-tts`` + a per-voice
  model download) — ``voice`` is then a Piper voice key (e.g. "de_DE-eva_k-x_low"). Absent, the request
  uses the primary engine (Kokoro, or synthetic in --synthetic mode). The ``meta`` frame reports whichever
  engine actually served the request, at that engine's own sample rate (Kokoro 24 kHz, espeak/SAPI 22.05
  kHz, Piper 16/22.05 kHz — the webview honours the per-stream ``sampleRate``).

  server -> client (one JSON object per line):
      {"type":"meta","sampleRate":24000,"engine":"kokoro"}
      {"type":"chunk","audio":"<base64 float32 little-endian PCM>","si":<sentenceIndex>,"sw":<wordCount>}
      {"type":"end"}

  Each chunk carries its sentence index (``si``) and that sentence's word count (``sw``) so the webview
  can anchor its read-along cursor per sentence (Kokoro exposes no per-word timestamps).
      {"type":"error","message":"..."}
      {"type":"pong","engine":"kokoro","ready":true,"sampleRate":24000}   (ping only)

The base64 ``audio`` payload is raw float32 little-endian samples — exactly what the sidebar
webview decodes (``atob`` -> ``Uint8Array`` -> ``Float32Array``) and plays through Web Audio at
``sampleRate``. So the Kotlin client forwards the base64 straight through, no re-encoding.

Startup handshake: once the socket is listening AND the engine is ready, the server prints a
single JSON line to stdout so the launching process (the IDE plugin) can read the chosen port:

      {"ready":true,"port":<n>,"engine":"kokoro"|"synthetic","sampleRate":24000}

On a fatal startup error it instead prints ``{"ready":false,"error":"..."}`` and exits non-zero.

Modes:
  (default)        load kokoro-onnx and serve. If the model can't load, exit non-zero with a JSON
                   error line.
  --synthetic      never load a model; synthesize a short sine tone per sentence. Lets the whole
                   TCP -> plugin -> webview audio path be exercised with NO model / heavy deps.
  --download       download the Kokoro model + voices into --model-dir (progress on stdout), exit.
  --download-piper download one Piper voice (``.onnx`` + ``.onnx.json``) into --model-dir/piper (md5-
                   verified against the catalog), exit.
  --delete-piper   delete one installed Piper voice from --model-dir/piper, exit.
  --piper-catalog  print the normalized Piper voice catalog (~170 voices) as one JSON line, cached under
                   --model-dir/piper for offline use; add --refresh to force a re-fetch. Exit.
  --check          print a JSON status of prerequisites (python / kokoro-onnx / model files) plus
                   which walkthrough languages are ready (``languagesReady``, ``misakiJa``/``misakiZh``),
                   the espeak robotic-tier language codes (``espeakLanguages``), and the Piper tier
                   (``piperImportable`` + installed ``piperVoices``), exit.

This file is bundled in the plugin and installed to ~/.claude/skills/audio-explainer/scripts/, so
the interactive skill can use it too.
"""

import argparse
import array
import base64
import ctypes
import hashlib
import json
import math
import os
import re
import socket
import struct
import subprocess
import sys
import tempfile
import urllib.request
import wave

SAMPLE_RATE = 24000
DEFAULT_VOICE = "af_heart"

# kokoro-onnx v1.0 model + combined voices (see github.com/thewh1teagle/kokoro-onnx releases).
MODEL_NAME = "kokoro-v1.0.onnx"
VOICES_NAME = "voices-v1.0.bin"
MODEL_URL = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx"
VOICES_URL = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin"

# Piper offline-neural tier. Each voice is a standalone VITS ONNX model downloaded on demand
# from the rhasspy/piper-voices HF repo into <model-dir>/piper/. The catalog lists ~170 voices / ~50
# languages; each entry's `files` map gives the repo-relative path (+ size + md5) appended to PIPER_BASE_URL.
PIPER_SUBDIR = "piper"
PIPER_CATALOG_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/voices.json"
PIPER_BASE_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/"
# The normalized catalog is cached here (under <model-dir>/piper/) so the voice-manager UI opens offline
# after the first fetch; a manual refresh re-fetches (see do_piper_catalog / --piper-catalog).
PIPER_CATALOG_NAME = "catalog.json"

# Longest text handed to the engine in one call; longer sentences are split further so playback
# starts quickly and no single synth call stalls.
MAX_CHUNK_CHARS = 320

# Each wire chunk carries at most this many float32 samples (~0.5 s @ 24 kHz). Sentences are re-framed
# to this size so playback streams smoothly and no single base64 payload gets large (the IDE forwards
# each chunk to the webview via executeJavaScript, so bounded payloads matter).
MAX_FRAME_SAMPLES = 12000
BYTES_PER_SAMPLE = 4  # float32


def default_model_dir():
    """~/.cache/audio-explainer — simple, per-user, writable on Windows/macOS/Linux alike."""
    return os.path.join(os.path.expanduser("~"), ".cache", "audio-explainer")


def piper_dir(model_dir):
    """Where Piper per-voice models live: <model-dir>/piper/<voiceKey>.onnx (+ .onnx.json sidecar)."""
    return os.path.join(model_dir, PIPER_SUBDIR)


# ── Text segmentation ────────────────────────────────────────────────────────

# CJK (Japanese/Chinese) detection + sentence/clause punctuation. CJK writes without spaces, and its
# sentence enders (。．！？…) and commas (、，；) differ from ASCII — so the splitters below must handle
# both alphabets, or a whole ja/zh narration stays one un-split blob (no per-sentence streaming, and the
# webview read-along has nothing to advance through).
_CJK_RE = re.compile(r"[぀-ヿ㐀-䶿一-鿿豈-﫿ｦ-ﾟ]")
# Sentence boundary: an ASCII ender followed by whitespace, OR (zero-width) right after a CJK ender.
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?])\s+|(?<=[。．！？…])")
# Long-sentence fallback: an ASCII comma + whitespace, OR right after a CJK comma/semicolon.
_CLAUSE_SPLIT_RE = re.compile(r"(?<=,)\s+|(?<=[、，；])")


def _is_cjk(text):
    """True if the text contains Japanese/Chinese characters (hiragana, katakana, or Han)."""
    return bool(_CJK_RE.search(text))


def split_sentences(text):
    """Split narration into synth-sized pieces: sentence boundaries first, then long-sentence
    fallback on commas / hard length, so the first audio arrives fast and playback streams."""
    text = " ".join(text.split())  # collapse whitespace/newlines
    if not text:
        return []
    pieces = _SENTENCE_SPLIT_RE.split(text)
    out = []
    for piece in pieces:
        piece = piece.strip()
        if not piece:
            continue
        if len(piece) <= MAX_CHUNK_CHARS:
            out.append(piece)
            continue
        # Long sentence: break on commas, coalescing back up to the length cap.
        buf = ""
        for part in _CLAUSE_SPLIT_RE.split(piece):
            if buf and len(buf) + len(part) + 1 > MAX_CHUNK_CHARS:
                out.append(buf.strip())
                buf = part
            else:
                buf = (buf + " " + part).strip() if buf else part
        if buf.strip():
            out.append(buf.strip())
    return out


# Kokoro voice-name first letter → espeak/misaki language code. Mirrors the Kotlin
# `Languages.languageOfVoice` prefix map (Languages.kt) — keep the two in sync. 'a'/'b' are the two
# English accents; the rest are Kokoro's other trained languages. ja→"ja", zh→"cmn" go through misaki.
PREFIX_LANG = {
    "a": "en-us", "b": "en-gb", "e": "es", "f": "fr-fr",
    "h": "hi", "i": "it", "p": "pt-br", "j": "ja", "z": "cmn",
}


def lang_for_voice(voice):
    """Derive the phonemizer language from the voice-name prefix (e.g. 'ef_dora' → 'es')."""
    return PREFIX_LANG.get((voice or "a")[:1], "en-us")


def _can_import(module):
    try:
        __import__(module)
        return True
    except Exception:  # noqa: BLE001
        return False


# A run of code-ish Latin text inside otherwise-CJK narration: an identifier, a type, an API name.
# Dotted paths are one run so `Volatile.Read` stays a phrase; the dot becomes a space before English
# phonemization, which is what makes it "volatile read" rather than "volatile dot read".
_LATIN_RUN = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*")


def _misaki_lang_ready(code):
    """Whether the misaki backend for a CJK language is importable. `code` is 'ja' or 'zh'.
    Base kokoro-onnx does NOT install misaki; ja/zh need the extra `misaki[ja]` / `misaki[zh]` packs."""
    if not _can_import("misaki"):
        return False
    try:
        if code == "ja":
            from misaki import ja  # noqa: F401
        else:
            from misaki import zh  # noqa: F401
        return True
    except Exception:  # noqa: BLE001
        return False


# ── Engines ──────────────────────────────────────────────────────────────────


class SyntheticEngine:
    """No model: emit a short sine tone per sentence. For pipeline testing without heavy deps."""

    name = "synthetic"
    sample_rate = SAMPLE_RATE

    def synth(self, text, voice, speed, lang=None, tune=None):
        speed = max(0.5, min(3.0, speed))
        for i, sentence in enumerate(split_sentences(text)):
            words = max(1, len(sentence.split()))
            duration = min(1.5, 0.18 + 0.05 * words) / speed
            freq = 200.0 + (i % 6) * 45.0
            yield _sine_pcm(freq, duration, self.sample_rate), self.sample_rate, sentence


class KokoroEngine:
    """Kokoro-82M via kokoro-onnx. Loads the ONNX model + voices once; synthesizes per sentence."""

    name = "kokoro"
    sample_rate = SAMPLE_RATE

    def __init__(self, model_path, voices_path):
        if not os.path.isfile(model_path):
            raise FileNotFoundError("Kokoro model not found: %s" % model_path)
        if not os.path.isfile(voices_path):
            raise FileNotFoundError("Kokoro voices not found: %s" % voices_path)
        from kokoro_onnx import Kokoro  # noqa: import here so --synthetic needs no deps

        self.kokoro = Kokoro(model_path, voices_path)
        self._jag2p = None

    def _ja_g2p(self):
        """misaki's Japanese grapheme-to-phoneme, built once. Kanji need a dictionary, not rules."""
        if self._jag2p is None:
            from misaki import ja  # noqa: import here so non-ja runs never pay for fugashi/unidic

            self._jag2p = ja.JAG2P()
        return self._jag2p

    def _phonemize_ja(self, sentence):
        """Japanese phonemes for [sentence], with embedded Latin identifiers read as English.

        **Why this exists, and why it is not kokoro-onnx's job.** kokoro-onnx phonemizes *every*
        language through espeak-ng — it never imports misaki. espeak's `ja` voice has kana rules but no
        kanji dictionary, so on a real Japanese sentence it switches language per character run and
        *announces the switch*: 109 characters of narration came back as 943 phonemes reading
        "Chinese letter … Japanese letter …" aloud, in the Kokoro voice, for the length of the sentence.
        misaki is Kokoro-82M's own G2P and its inventory is inside the model's 114-symbol vocabulary
        (verified against `DEFAULT_VOCAB`: every symbol it emits for this text is in there).

        What misaki does *not* do is pronounce `Volatile.Read` — it passes Latin through untouched, and
        those letters would be spoken as if they were phonemes. So the sentence is cut into runs: code
        goes to espeak as English, everything else to misaki, and the two phoneme strings are joined.
        Non-vocabulary leftovers (`<`, `>`, `_` from generics and field names) are dropped at the end
        rather than fed to the model.
        """
        g2p, pieces, pos = self._ja_g2p(), [], 0
        for m in _LATIN_RUN.finditer(sentence):
            if m.start() > pos:
                pieces.append(self._misaki_ja(g2p, sentence[pos:m.start()]))
            pieces.append(self.kokoro.tokenizer.phonemize(m.group().replace(".", " "), "en-us"))
            pos = m.end()
        if pos < len(sentence):
            pieces.append(self._misaki_ja(g2p, sentence[pos:]))
        vocab = self.kokoro.tokenizer.vocab
        joined = " ".join(p for p in (piece.strip() for piece in pieces) if p)
        return "".join(ch for ch in joined if ch in vocab)

    @staticmethod
    def _misaki_ja(g2p, chunk):
        """misaki returns (phonemes, tokens) in some releases and a bare string in others."""
        if not chunk.strip():
            return ""
        out = g2p(chunk)
        return out[0] if isinstance(out, tuple) else out

    def synth(self, text, voice, speed, lang=None, tune=None):
        import numpy as np

        # Explicit request `lang` wins; otherwise derive from the voice prefix (normally authoritative).
        lang = lang or lang_for_voice(voice)
        # Japanese is the one language kokoro-onnx cannot phonemize (see `_phonemize_ja`), so we do it
        # and hand the model finished phonemes. Chinese is *not* in the same boat: espeak's `cmn` voice
        # has a Han dictionary and produces good Chinese narration, so it keeps the ordinary path and
        # misaki[zh] is not required for it.
        use_misaki_ja = lang == "ja"
        if use_misaki_ja and not _misaki_lang_ready("ja"):
            raise RuntimeError("Japanese needs 'misaki[ja]' — install it from Settings → Audio engines → Kokoro")
        for sentence in split_sentences(text):
            if use_misaki_ja:
                phonemes = self._phonemize_ja(sentence)
                samples, sr = self.kokoro.create(
                    phonemes, voice=voice, speed=float(speed), is_phonemes=True
                )
            else:
                samples, sr = self.kokoro.create(sentence, voice=voice, speed=float(speed), lang=lang)
            pcm = np.asarray(samples, dtype="<f4").tobytes()
            yield pcm, int(sr), sentence


def _sine_pcm(freq, duration, sample_rate):
    """Float32 little-endian PCM of a sine tone with a short fade in/out to avoid clicks."""
    n = max(1, int(duration * sample_rate))
    fade = min(n // 8, int(0.01 * sample_rate)) or 1
    out = bytearray()
    for i in range(n):
        amp = 0.28
        if i < fade:
            amp *= i / fade
        elif i > n - fade:
            amp *= (n - i) / fade
        out += struct.pack("<f", amp * math.sin(2.0 * math.pi * freq * (i / sample_rate)))
    return bytes(out)


# ── espeak-ng robotic fallback tier ────────────────────────────────────────────
#
# The ~100 languages Kokoro can't voice, at lower ("robotic but intelligible") quality, with ZERO extra
# download: `kokoro-onnx` already pulls `espeakng-loader`, which ships the espeak-ng shared library + voice
# data (we use it for Kokoro's phonemization). The *same* library is a full synthesizer, driven here with
# pure ctypes (no `py-espeak-ng` dependency). espeak init returns 16-bit mono @ 22 050 Hz; we convert to
# float32-LE and stream through the identical wire protocol (the webview honours the per-stream sample rate).
# espeak and Kokoro coexist in one process, so no separate server is needed.

_ESPEAK_AUDIO_RETRIEVAL = 1  # espeak_AUDIO_OUTPUT: deliver PCM to our callback (no audio device)
_ESPEAK_CHARS_UTF8 = 1       # espeakCHARS_UTF8 flag for espeak_Synth
# espeak_PARAMETER enum values we drive per request (see espeak-ng speak_lib.h):
_ESPEAK_RATE = 1             # words-per-minute
_ESPEAK_PITCH = 3            # base pitch 0..100 (espeak default 50)
_ESPEAK_RANGE = 4            # pitch range / "liveliness" 0..100 (0 = monotone, espeak default 50)
_ESPEAK_WORDGAP = 7          # extra gap between words, units of 10 ms (espeak default 0)
# Tuning defaults. WPM lowered from espeak's 175 default — slower is markedly
# clearer for the robotic tier and recovers syllables that ran together — plus a tiny word gap for the
# same reason. Pitch/range default to espeak's neutral 50 but are user-tunable from the sidebar.
_ESPEAK_DEFAULT_WPM = 160
_ESPEAK_DEFAULT_PITCH = 50
_ESPEAK_DEFAULT_RANGE = 50
_ESPEAK_DEFAULT_WORDGAP = 1


class _EspeakVoice(ctypes.Structure):
    """Mirror of espeak-ng's ``espeak_VOICE`` struct (for ``espeak_ListVoices``)."""

    _fields_ = [
        ("name", ctypes.c_char_p),
        # A sequence of (priority-byte, NUL-terminated code) pairs; c_char_p reads only the first pair,
        # so ``languages[1:]`` (past the priority byte) is the primary language code, e.g. b"de".
        ("languages", ctypes.c_char_p),
        ("identifier", ctypes.c_char_p),
        ("gender", ctypes.c_ubyte),
        ("age", ctypes.c_ubyte),
        ("variant", ctypes.c_ubyte),
        ("xx1", ctypes.c_ubyte),
        ("score", ctypes.c_int),
        ("spare", ctypes.c_void_p),
    ]


# int callback(short *wav, int numsamples, espeak_EVENT *events); return 0 to keep synthesizing.
_ESPEAK_SYNTH_CB = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.POINTER(ctypes.c_short), ctypes.c_int, ctypes.c_void_p)


def _load_espeak():
    """Load espeakng-loader's DLL and initialize espeak-ng in retrieval mode. Returns (lib, sampleRate).
    Raises if the loader isn't importable or init fails."""
    import espeakng_loader  # noqa: import here so --synthetic / non-espeak paths need no deps

    lib = ctypes.CDLL(espeakng_loader.get_library_path())
    lib.espeak_Initialize.restype = ctypes.c_int
    lib.espeak_Initialize.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
    # espeak_Initialize wants the PARENT directory of the "espeak-ng-data" folder.
    data_parent = os.path.dirname(espeakng_loader.get_data_path())
    sample_rate = lib.espeak_Initialize(_ESPEAK_AUDIO_RETRIEVAL, 0, data_parent.encode("utf-8"), 0)
    if sample_rate <= 0:
        raise RuntimeError("espeak-ng initialization failed (rc=%s)" % sample_rate)
    return lib, sample_rate


def _espeak_language_codes():
    """Primary language codes the local espeak build supports (for --check). [] if unavailable."""
    try:
        lib, _ = _load_espeak()
        lib.espeak_ListVoices.restype = ctypes.POINTER(ctypes.POINTER(_EspeakVoice))
        lib.espeak_ListVoices.argtypes = [ctypes.c_void_p]
        ptr = lib.espeak_ListVoices(None)
        out, seen = [], set()
        i = 0
        while ptr[i]:
            raw = ptr[i].contents.languages or b""
            code = raw[1:].decode("ascii", "replace") if len(raw) > 1 else ""
            if code and code not in seen:
                seen.add(code)
                out.append(code)
            i += 1
        return out
    except Exception:  # noqa: BLE001
        return []


# Custom voice variants we install into espeak's data dir so every robotic-tier language gets a clean,
# consistent voice. The stock numbered variants are unusable for us: f2/f3/f4/f5 and m2 all carry an `echo`
# directive (a 120–140 ms echo that sounds like reverb), most f-variants add `roughness`, and NO stock
# female variant is free of both. We build ours from a fixed base (pitch + 9 formants + stress) plus four
# *tunable* Layer-2 directives — consonants (clarity), roughness, flutter, breath — that the voice-editor
# dialog drives: the file is regenerated and reloaded (on SetVoiceByName) whenever those change. espeak
# reads variants from ``<data>/voices/!v/<name>``; writing there is a tiny file op, and an unknown
# ``+ae_*`` variant simply falls back to the base language voice (no error) on read-only installs.
_VARIANT_BASE = {
    "ae_f": {
        "dispname": "ae_female", "gender": "female", "pitch": "140 220",
        "formants": [
            "formant 0 105  80 150", "formant 1 110  80 160", "formant 2 110  70 150",
            "formant 3 110  70 150", "formant 4 115  80 150", "formant 5 115  80 150",
            "formant 6 110  70 150", "formant 7 110  70 150", "formant 8 110  70 150",
        ],
        "stressadd": "stressAdd 0 0 -10 -10 0 0 10 40",
    },
    "ae_m": {
        "dispname": "ae_male", "gender": "male", "pitch": "82 118",
        "formants": [
            "formant 0 100 100 100", "formant 1  96  97 100", "formant 2  96  97 100",
            "formant 3  96 103 100", "formant 4  95 103 100", "formant 5  95 103 100",
            "formant 6 100 100 100", "formant 7 100 100 100", "formant 8 100 100 100",
        ],
        "stressadd": "stressAdd 10 10 0 0 0 0 -30 -30",
    },
}
# Layer-2 defaults (match the clean voices we shipped): clear consonants, no roughness/breath, light flutter.
_VARIANT_L2_DEFAULTS = {"consonants": 120, "roughness": 0, "flutter": 2, "breath": 0}


def _clamp_int(value, default, lo, hi):
    """Coerce a wire value to an int in [lo, hi]; fall back to default on None / bad input."""
    try:
        return max(lo, min(hi, int(value)))
    except (TypeError, ValueError):
        return default


def _build_variant(name, consonants, roughness, flutter, breath):
    """Compose an espeak variant file for [name] from its fixed base + tunable Layer-2 directives."""
    base = _VARIANT_BASE[name]
    lines = ["language variant", "name %s" % base["dispname"], "gender %s" % base["gender"], "",
             "pitch %s" % base["pitch"]]
    lines += base["formants"]
    lines.append("")
    lines.append("consonants %d %d" % (consonants, consonants))
    if flutter > 0:
        lines.append("flutter %d" % flutter)
    if roughness > 0:
        lines.append("roughness %d" % roughness)
    if breath > 0:
        lines.append("breath 0 " + " ".join(["%d" % breath] * 7))
    lines.append(base["stressadd"])
    return "\n".join(lines) + "\n"


def _l2_from_tune(tune):
    """Clamped Layer-2 params (consonants, roughness, flutter, breath) from a tune dict, with defaults."""
    t = tune if isinstance(tune, dict) else {}
    d = _VARIANT_L2_DEFAULTS
    return (
        _clamp_int(t.get("consonants"), d["consonants"], 50, 200),
        _clamp_int(t.get("roughness"), d["roughness"], 0, 20),
        _clamp_int(t.get("flutter"), d["flutter"], 0, 30),
        _clamp_int(t.get("breath"), d["breath"], 0, 15),
    )


def _variant_path(name):
    import espeakng_loader  # here so non-espeak paths need no loader

    return os.path.join(espeakng_loader.get_data_path(), "voices", "!v", name)


def _write_variant(name, content):
    """Write variant [name]'s file (best-effort). Returns True on success."""
    try:
        with open(_variant_path(name), "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        return True
    except Exception:  # noqa: BLE001 — read-only / loader missing → caller degrades gracefully
        return False


def _install_espeak_variants():
    """Write our custom variants (at Layer-2 defaults) into espeak's ``voices/!v`` dir (idempotent)."""
    for name in _VARIANT_BASE:
        content = _build_variant(name, **_VARIANT_L2_DEFAULTS)
        try:
            path = _variant_path(name)
            if os.path.isfile(path):
                with open(path, "r", encoding="utf-8") as f:
                    if f.read() == content:
                        continue  # already current
        except Exception:  # noqa: BLE001 — loader missing / unreadable → try the write, else give up
            pass
        _write_variant(name, content)


class EspeakEngine:
    """espeak-ng via ctypes — the robotic ~100-language fallback tier. Synthesizes 16-bit mono
    PCM at ~22 050 Hz per sentence and converts it to float32-LE for the wire. Reuses [split_sentences].
    Installs our custom clean voice variants on init and honours per-request tuning: runtime params
    (pitch / range / word-gap via SetParameter) plus Layer-2 params (consonants / roughness / flutter /
    breath) that regenerate the variant file on the fly — both driven by the sidebar + voice-editor dialog."""

    name = "espeak"

    def __init__(self):
        self.lib, self.sample_rate = _load_espeak()
        _install_espeak_variants()
        # Last Layer-2 content written per variant, so we only rewrite the file when it actually changes.
        self._variant_content = {}
        self.lib.espeak_SetVoiceByName.restype = ctypes.c_int
        self.lib.espeak_SetVoiceByName.argtypes = [ctypes.c_char_p]
        self.lib.espeak_SetParameter.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_int]
        self.lib.espeak_Synth.restype = ctypes.c_int
        self.lib.espeak_Synth.argtypes = [
            ctypes.c_char_p, ctypes.c_size_t, ctypes.c_uint, ctypes.c_int,
            ctypes.c_uint, ctypes.c_uint, ctypes.c_void_p, ctypes.c_void_p,
        ]
        self.lib.espeak_SetSynthCallback.argtypes = [_ESPEAK_SYNTH_CB]
        self._buf = bytearray()
        # Keep a strong ref to the callback for the engine's lifetime — if it's GC'd while espeak holds
        # the pointer, the next synth crashes the process.
        self._callback = _ESPEAK_SYNTH_CB(self._on_samples)
        self.lib.espeak_SetSynthCallback(self._callback)

    def _on_samples(self, wav, numsamples, events):
        if numsamples > 0 and wav:
            self._buf.extend(ctypes.string_at(wav, numsamples * 2))  # int16 mono
        return 0

    def synth(self, text, voice, speed, lang=None, tune=None):
        import numpy as np

        # The espeak "voice" is a language code plus an optional +variant (e.g. "de", "de+ae_f"). Fall back
        # to an explicit lang, then English, so a malformed request still speaks *something* audible.
        spec = (voice or lang or "en").strip()
        # If the voice targets one of our tunable variants, (re)generate its file from the request's Layer-2
        # params BEFORE SetVoiceByName (which reads the file), so consonants/roughness/flutter/breath apply.
        variant = spec.split("+", 1)[1] if "+" in spec else ""
        if variant in _VARIANT_BASE:
            content = _build_variant(variant, *_l2_from_tune(tune))
            if self._variant_content.get(variant) != content and _write_variant(variant, content):
                self._variant_content[variant] = content
        self.lib.espeak_SetVoiceByName(spec.encode("utf-8"))
        wpm = int(round(_ESPEAK_DEFAULT_WPM * max(0.5, min(3.0, float(speed)))))
        self.lib.espeak_SetParameter(_ESPEAK_RATE, max(80, min(450, wpm)), 0)
        # Per-request voice tuning from the sidebar sliders. Set EVERY parameter each call — espeak's
        # parameters are global to the library instance, so an unset one would leak the prior request's.
        t = tune if isinstance(tune, dict) else {}
        self.lib.espeak_SetParameter(_ESPEAK_PITCH, _clamp_int(t.get("pitch"), _ESPEAK_DEFAULT_PITCH, 0, 100), 0)
        self.lib.espeak_SetParameter(_ESPEAK_RANGE, _clamp_int(t.get("range"), _ESPEAK_DEFAULT_RANGE, 0, 100), 0)
        self.lib.espeak_SetParameter(_ESPEAK_WORDGAP, _clamp_int(t.get("wordgap"), _ESPEAK_DEFAULT_WORDGAP, 0, 50), 0)
        for sentence in split_sentences(text):
            self._buf = bytearray()
            data = sentence.encode("utf-8")
            self.lib.espeak_Synth(data, len(data) + 1, 0, 0, 0, _ESPEAK_CHARS_UTF8, None, None)
            if hasattr(self.lib, "espeak_Synchronize"):
                self.lib.espeak_Synchronize()  # block until all callbacks for this sentence have fired
            i16 = np.frombuffer(bytes(self._buf), dtype="<i2")
            pcm = (i16.astype(np.float32) / 32768.0).astype("<f4").tobytes()
            yield pcm, self.sample_rate, sentence


# ── Windows System.Speech tier ─────────────────────────────────────────────────
#
# A Windows-only tier that reaches whatever SAPI5 voices the machine has installed — the classic
# offline voices AND, once the user installs NaturalVoiceSAPIAdapter (a third-party SAPI5 provider) and
# enables the Edge "Online" voices, the neural Azure voices for ~140 locales. We do NOT bundle or depend
# on the adapter; we synthesize with whatever voice NAME the plugin hands us. .NET's System.Speech isn't
# reliably loadable from pwsh 7, so we drive Windows PowerShell 5.1 via a persistent worker: it loads the
# synthesizer once, then per sentence writes a WAV that we read back (stdlib `wave`) and convert to the
# wire's float32-LE. Zero extra pip deps (PowerShell + .NET are in-box). Online voices are network calls
# (~0.3 s warm / ~1 s cold per sentence) so text is sent to Microsoft; offline voices are instant.

# Persistent worker: read one JSON request per line from stdin, synthesize to a WAV, print a JSON ack.
# UTF-8 console encoding is mandatory — narration text is multi-language (umlauts, CJK, RTL). SetOutputTo-
# Null() after Speak flushes + closes the WAV so the parent can read it; the voice is cached across calls.
_SAPI_WORKER_PS = r'''
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$lastVoice = ''
while ($true) {
    $line = [Console]::In.ReadLine()
    if ($null -eq $line) { break }
    if ($line.Trim() -eq '') { continue }
    try {
        $req = $line | ConvertFrom-Json
        if ($req.voice -and $req.voice -ne $lastVoice) {
            $synth.SelectVoice([string]$req.voice)
            $lastVoice = [string]$req.voice
        }
        $synth.Rate = [int]$req.rate
        $synth.SetOutputToWaveFile([string]$req.out)
        $synth.Speak([string]$req.text)
        $synth.SetOutputToNull()
        [Console]::Out.WriteLine('{"ok":true}')
    } catch {
        try { $synth.SetOutputToNull() } catch {}
        $err = ($_.Exception.Message -replace '[\r\n]+', ' ')
        [Console]::Out.WriteLine((@{ ok = $false; error = $err } | ConvertTo-Json -Compress))
        $lastVoice = ''
    }
    [Console]::Out.Flush()
}
'''


def _powershell_exe():
    """Windows PowerShell 5.1 (System.Speech is .NET Framework — pwsh 7 can't always load it)."""
    root = os.environ.get("SystemRoot", r"C:\Windows")
    cand = os.path.join(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
    return cand if os.path.isfile(cand) else "powershell.exe"


def _wav_to_float32le(path):
    """Read a PCM WAV → (float32-LE bytes, sampleRate). 16-bit; stereo is downmixed to mono."""
    with wave.open(path, "rb") as w:
        sr, width, ch = w.getframerate(), w.getsampwidth(), w.getnchannels()
        frames = w.readframes(w.getnframes())
    if width != 2:
        raise RuntimeError("unexpected SAPI WAV sample width: %d bytes" % width)
    i16 = array.array("h")
    i16.frombytes(frames)
    if sys.byteorder == "big":
        i16.byteswap()  # WAV samples are little-endian on disk
    if ch == 2:
        i16 = array.array("h", [(i16[j] + i16[j + 1]) // 2 for j in range(0, len(i16) - 1, 2)])
    floats = array.array("f", (s / 32768.0 for s in i16))
    if sys.byteorder == "big":
        floats.byteswap()  # emit little-endian float32 for the wire
    return floats.tobytes(), sr


class SapiEngine:
    """Windows System.Speech tier. See the section header. Synthesizes each sentence to a WAV
    via a persistent PowerShell worker and converts it to the wire's float32-LE. Reuses [split_sentences]
    so read-along + streaming work as for the other engines. The server is serial (one request at a time),
    so at every yield the worker is idle and its pipe drained — a mid-stream cancel leaves it reusable."""

    name = "sapi"
    sample_rate = 22050  # advertised in `meta`; each sentence also carries its WAV's actual rate

    def __init__(self):
        if sys.platform != "win32":
            raise RuntimeError("the SAPI tier is Windows-only")
        self._dir = tempfile.mkdtemp(prefix="ae_sapi_")
        self._wav = os.path.join(self._dir, "chunk.wav")
        script = os.path.join(self._dir, "worker.ps1")
        with open(script, "w", encoding="utf-8") as f:
            f.write(_SAPI_WORKER_PS)
        self._proc = subprocess.Popen(
            [_powershell_exe(), "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            encoding="utf-8", bufsize=1,
        )

    def _request(self, voice, text, rate):
        """Send one synth request and block until the worker acks; raise on worker error/exit."""
        if self._proc.poll() is not None:
            raise RuntimeError("SAPI worker exited")
        req = json.dumps({"voice": voice, "text": text, "rate": rate, "out": self._wav})
        self._proc.stdin.write(req + "\n")
        self._proc.stdin.flush()
        ack_line = self._proc.stdout.readline()
        if not ack_line:
            raise RuntimeError(
                "Windows voice '%s': SAPI worker closed (PowerShell/System.Speech unavailable, or the "
                "voice is gone)" % voice)
        ack = json.loads(ack_line)
        if not ack.get("ok"):
            detail = ack.get("error") or "synthesis failed"
            # Online (Edge/NaturalVoiceSAPIAdapter) voices reach Microsoft's endpoint per sentence, so a
            # failure here is usually connectivity/endpoint, not a bad voice — flag that in the message.
            hint = " (online voice — needs internet; endpoint may be down or rate-limiting)" \
                if "online" in voice.lower() else ""
            raise RuntimeError("Windows voice '%s'%s: %s" % (voice, hint, detail))

    def synth(self, text, voice, speed, lang=None, tune=None):
        voice = (voice or "").strip()
        # SAPI Rate is -10..10 (0 = normal); map our 0.5..3.0 speed multiplier onto it.
        rate = max(-10, min(10, int(round((max(0.5, min(3.0, float(speed))) - 1.0) * 10))))
        for sentence in split_sentences(text):
            self._request(voice, sentence, rate)
            pcm, sr = _wav_to_float32le(self._wav)
            yield pcm, sr, sentence


# ── Piper offline-neural tier ──────────────────────────────────────────────────


class PiperEngine:
    """Piper VITS ONNX offline-neural tier. The middle tier — neural quality, fully
    offline, cross-platform (unlike SAPI), covering the ~40 languages Kokoro can't voice. Each voice is a
    standalone ONNX model under <model-dir>/piper/, downloaded on demand (see --download-piper). Loads each
    voice lazily and caches it (like [KokoroEngine]); drives synthesis one sentence at a time via
    [split_sentences] so read-along + streaming match the other engines. ``AudioChunk.audio_float_array`` is
    already float32 in [-1, 1] → emit float32-LE directly (the Kokoro path, no ``_wav_to_float32le`` round-
    trip). Piper ships its OWN bundled espeak-ng-data, isolated from the [EspeakEngine] tier, and coexists
    with Kokoro + espeak in one process. ``voice`` is a bare Piper voice key (e.g. ``de_DE-eva_k-
    x_low``); ``speed`` maps to ``length_scale = 1/speed``. Sample rate is per-voice (16 kHz for x_low,
    22.05 kHz otherwise) and carried per chunk, so mixed rates need no wire change."""

    name = "piper"
    sample_rate = 22050  # advertised in `meta`; each sentence also carries its model's actual rate

    def __init__(self, model_dir):
        self._dir = piper_dir(model_dir)
        self._voices = {}  # voiceKey -> loaded PiperVoice (cached across requests)

    @staticmethod
    def _split_speaker(voice):
        """A Piper voice spec is ``<key>`` or ``<key>#<speakerIndex>`` for multi-speaker models.
        Returns ``(key, speaker_id_or_None)``; a trailing ``#N`` selects speaker N (0-based), else default."""
        key = (voice or "").strip()
        base, sep, spk = key.rpartition("#")
        if sep and spk.isdigit():
            return base, int(spk)
        return key, None

    def _voice(self, key):
        v = self._voices.get(key)
        if v is None:
            from piper import PiperVoice  # import here so the base install needs no piper

            onnx = os.path.join(self._dir, key + ".onnx")
            cfg = onnx + ".json"
            if not os.path.isfile(onnx):
                raise RuntimeError(
                    "Piper voice '%s' is not installed — add it under Settings → Audio engines → Piper" % key)
            v = PiperVoice.load(onnx, cfg if os.path.isfile(cfg) else None)
            self._voices[key] = v
        return v

    def synth(self, text, voice, speed, lang=None, tune=None):
        import numpy as np
        from piper import SynthesisConfig

        key, speaker = self._split_speaker(voice)
        v = self._voice(key)
        speed = max(0.5, min(3.0, float(speed)))
        # speaker_id selects a voice within a multi-speaker model; None → the model's default.
        syn = SynthesisConfig(length_scale=1.0 / speed, speaker_id=speaker)  # slower speed → longer audio
        for sentence in split_sentences(text):
            # Drive one sentence at a time: Piper splits internally but its AudioChunk carries no source
            # text, so feeding whole sentences preserves the read-along mapping (one yield per sentence).
            sr = None
            parts = []
            for c in v.synthesize(sentence, syn_config=syn):
                arr = np.asarray(c.audio_float_array, dtype="<f4").ravel()
                if arr.size:
                    parts.append(arr)
                    if sr is None:
                        sr = int(c.sample_rate)  # per-voice: 16 kHz for x_low, else 22.05 kHz
            if not parts:
                continue
            pcm = np.concatenate(parts).astype("<f4").tobytes()
            yield pcm, sr, sentence


class EngineHost:
    """Routes each request to the primary engine (Kokoro/synthetic) or, by ``"engine"``, to a lazily-created
    [EspeakEngine] (``"espeak"``), [SapiEngine] (``"sapi"``, Windows), or [PiperEngine] (``"piper"``). One
    process hosts all of them (they coexist cleanly — verified). The handshake advertises the primary engine;
    each stream's ``meta`` frame reports whichever engine actually served it."""

    def __init__(self, primary, model_dir=None, allow_espeak=True, allow_sapi=True, allow_piper=True):
        self.primary = primary
        self.model_dir = model_dir
        self.allow_espeak = allow_espeak
        self.allow_sapi = allow_sapi
        self.allow_piper = allow_piper
        self._espeak = None
        self._sapi = None
        self._piper = None

    def resolve(self, req):
        engine = req.get("engine")
        if self.allow_espeak and engine == "espeak":
            if self._espeak is None:
                self._espeak = EspeakEngine()  # first espeak request pays the (small) init cost
            return self._espeak
        if self.allow_sapi and engine == "sapi":
            if self._sapi is None:
                self._sapi = SapiEngine()  # first SAPI request spawns the PowerShell worker
            return self._sapi
        if self.allow_piper and engine == "piper":
            if self._piper is None:
                self._piper = PiperEngine(self.model_dir)  # per-voice models loaded lazily inside
            return self._piper
        return self.primary


# ── Socket framing ─────────────────────────────────────────────────────────────


def read_line(conn):
    """Read one newline-terminated request line (requests are tiny)."""
    buf = bytearray()
    while True:
        chunk = conn.recv(4096)
        if not chunk:
            break
        buf.extend(chunk)
        nl = buf.find(b"\n")
        if nl >= 0:
            return bytes(buf[:nl])
        if len(buf) > 1 << 20:  # 1 MiB guard against a runaway request
            break
    return bytes(buf)


def send_json(conn, obj):
    conn.sendall((json.dumps(obj) + "\n").encode("utf-8"))


def handle(host, conn):
    line = read_line(conn)
    if not line:
        return
    try:
        req = json.loads(line.decode("utf-8"))
    except Exception as e:  # noqa: BLE001
        send_json(conn, {"type": "error", "message": "bad request: %s" % e})
        return

    if req.get("ping"):
        primary = host.primary
        send_json(conn, {"type": "pong", "engine": primary.name, "ready": True, "sampleRate": primary.sample_rate})
        return

    text = (req.get("text") or "").strip()
    voice = req.get("voice") or DEFAULT_VOICE
    lang = req.get("lang") or None  # optional override; else derived from the voice prefix
    tune = req.get("tune") if isinstance(req.get("tune"), dict) else None  # espeak pitch/range/word-gap
    try:
        speed = float(req.get("speed") or 1.0)
    except (TypeError, ValueError):
        speed = 1.0

    if not text:
        send_json(conn, {"type": "end"})
        return

    # Pick the engine for THIS request (primary, or espeak on request). Instantiating espeak can fail if
    # its loader isn't importable — surface that as a clean error, not a crashed connection.
    try:
        engine = host.resolve(req)
    except Exception as e:  # noqa: BLE001
        send_json(conn, {"type": "error", "message": "engine unavailable: %s" % e})
        return

    send_json(conn, {"type": "meta", "sampleRate": engine.sample_rate, "engine": engine.name})
    try:
        for si, (pcm, sr, sentence) in enumerate(engine.synth(text, voice, speed, lang, tune)):
            # Tag every frame with its sentence index (si) + that sentence's word count (sw). The webview
            # uses these to anchor its read-along cursor per sentence (audio span + word range) rather than
            # against a total duration that keeps growing as frames stream in — Kokoro gives no per-word
            # timestamps, but per-sentence anchoring is enough for a cursor that tracks without drifting.
            # Word count for the webview read-along cursor. CJK has no spaces to tokenise, so each CJK
            # sentence counts as one "word" — the cursor then advances one *sentence* at a time (the webview
            # tokenises CJK transcripts per sentence to match). Space-delimited languages use the real count.
            words = 1 if _is_cjk(sentence) else max(1, len(sentence.split()))
            frame_bytes = MAX_FRAME_SAMPLES * BYTES_PER_SAMPLE
            for off in range(0, len(pcm), frame_bytes):
                frame = pcm[off:off + frame_bytes]
                if not frame:
                    continue
                # "stext" is the sentence as actually spoken. It repeats on every frame of the
                # sentence (~0.5% of the frame's own base64 payload) so that losing a frame cannot lose
                # the text. The receiver needs it because "sentence 3" is not a shared idea: the author
                # counts prose, split_sentences() counts ASCII enders and a 320-char cap, and the webview
                # tokenises the raw ttsText. Matching a phrase against what was spoken needs no agreement.
                send_json(conn, {
                    "type": "chunk", "sampleRate": int(sr), "si": si, "sw": words,
                    "stext": sentence,
                    "audio": base64.b64encode(frame).decode("ascii"),
                })
    except (BrokenPipeError, ConnectionError, OSError):
        return  # the client (plugin) cancelled by closing the socket — abort quietly
    except Exception as e:  # noqa: BLE001
        try:
            send_json(conn, {"type": "error", "message": str(e)})
        except OSError:
            pass
        return
    send_json(conn, {"type": "end"})


def serve(engine_host, host, port):
    primary = engine_host.primary
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((host, port))
    srv.listen(8)
    actual_port = srv.getsockname()[1]
    # Ready handshake — the launching plugin reads this line to learn the port. The advertised engine is
    # the primary (Kokoro/synthetic); an espeak stream reports itself in its own `meta` frame.
    print(
        json.dumps({"ready": True, "port": actual_port, "engine": primary.name, "sampleRate": primary.sample_rate}),
        flush=True,
    )
    while True:
        try:
            conn, _ = srv.accept()
        except KeyboardInterrupt:
            break
        try:
            handle(engine_host, conn)
        except Exception:  # noqa: BLE001 — one bad connection must not kill the server
            pass
        finally:
            try:
                conn.close()
            except OSError:
                pass


# ── Subcommands: --check / --download ───────────────────────────────────────────


def _fetch_piper_catalog():
    """Fetch + parse the rhasspy/piper-voices ``voices.json`` catalog (~170 voices / ~50 languages)."""
    req = urllib.request.Request(PIPER_CATALOG_URL, headers={"User-Agent": "audio-explainer"})
    with urllib.request.urlopen(req, timeout=60) as r:  # noqa: S310 (trusted HF URL)
        return json.loads(r.read().decode("utf-8"))


def _speaker_names(speaker_id_map, count):
    """Turn a Piper ``speaker_id_map`` (``{name: id}``) into an id-indexed name list of length ``count``
    Many multi-speaker models use opaque numeric ids ("00737") — the plugin falls back to
    "Speaker N" for those — so a missing/degenerate map just yields an empty list."""
    if not isinstance(speaker_id_map, dict) or count <= 1:
        return []
    names = [None] * count
    for nm, idx in speaker_id_map.items():
        if isinstance(idx, int) and 0 <= idx < count:
            names[idx] = nm
    return names


def _normalize_piper_catalog(raw):
    """Reduce the raw ``voices.json`` (keyed by voice key, each a big nested entry) to the compact list the
    voice-manager UI needs: one row per voice with language names, quality, speaker count/names,
    total download size, and the .onnx md5. Sorted by language then quality then key for a stable menu."""
    quality_rank = {"x_low": 0, "low": 1, "medium": 2, "high": 3}
    rows = []
    for key, e in (raw or {}).items():
        lang = e.get("language") or {}
        files = e.get("files") or {}
        size = sum(int(f.get("size_bytes", 0)) for f in files.values() if isinstance(f, dict))
        onnx_md5 = next(
            (f.get("md5_digest") for p, f in files.items()
             if p.endswith(".onnx") and isinstance(f, dict)),
            None,
        )
        speakers = int(e.get("num_speakers", 1) or 1)
        rows.append({
            "key": key,
            "family": lang.get("family"),          # walkthrough code, e.g. "de"
            "locale": lang.get("code"),             # full locale, e.g. "de_DE"
            "region": lang.get("region"),
            "nameEnglish": lang.get("name_english"),
            "nameNative": lang.get("name_native"),
            "quality": e.get("quality"),
            "speakers": speakers,
            "speakerNames": _speaker_names(e.get("speaker_id_map"), speakers),
            "sizeBytes": size,
            "md5": onnx_md5,
        })
    rows.sort(key=lambda r: (
        (r["nameEnglish"] or "").lower(), quality_rank.get(r["quality"], 9), r["key"]))
    return rows


def _piper_catalog(model_dir, refresh=False):
    """Return the normalized Piper catalog (see [_normalize_piper_catalog]) plus its source, caching it to
    ``<model-dir>/piper/catalog.json`` so the manager opens offline after the first fetch.
    ``refresh`` forces a re-fetch. Returns ``(rows, source, error)`` where source ∈ network/cache/none."""
    cache = os.path.join(piper_dir(model_dir), PIPER_CATALOG_NAME)
    if not refresh and os.path.isfile(cache):
        try:
            with open(cache, "r", encoding="utf-8") as f:
                return json.load(f), "cache", None
        except Exception:  # noqa: BLE001 — a corrupt cache just triggers a fetch
            pass
    try:
        rows = _normalize_piper_catalog(_fetch_piper_catalog())
    except Exception as e:  # noqa: BLE001 — offline / HF down: fall back to a stale cache if we have one
        if os.path.isfile(cache):
            try:
                with open(cache, "r", encoding="utf-8") as f:
                    return json.load(f), "cache", str(e)
            except Exception:  # noqa: BLE001
                pass
        return [], "none", str(e)
    try:
        os.makedirs(os.path.dirname(cache), exist_ok=True)
        tmp = cache + ".part"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(rows, f)
        os.replace(tmp, cache)
    except OSError:
        pass  # a read-only cache dir shouldn't stop us returning the fresh catalog
    return rows, "network", None


def _piper_installed_voices(model_dir):
    """Scan <model-dir>/piper for installed voices, reading each ``.onnx.json`` sidecar for metadata
    WITHOUT loading the ONNX. Returns [{key, lang, quality, sampleRate, speakers, sizeBytes}] (mirrors the
    espeakLanguages discovery so the plugin can populate the tier + register dynamic languages)."""
    out = []
    d = piper_dir(model_dir)
    if not os.path.isdir(d):
        return out
    for name in sorted(os.listdir(d)):
        if not name.endswith(".onnx"):
            continue
        key = name[:-len(".onnx")]
        onnx = os.path.join(d, name)
        # Include the .onnx.json sidecar size too, so the plugin's disk-usage total matches what a delete frees.
        cfg_path = onnx + ".json"
        size = os.path.getsize(onnx) + (os.path.getsize(cfg_path) if os.path.isfile(cfg_path) else 0)
        info = {
            "key": key,
            "sizeBytes": size,
            "quality": key.rsplit("-", 1)[-1] if "-" in key else None,
            "sampleRate": 22050,
            "speakers": 1,
            "lang": None,      # full locale, e.g. "de_DE"
            "family": None,    # walkthrough language code, e.g. "de"
            "nameEnglish": None,
            "nameNative": None,
            "speakerNames": [],  # index = speaker_id; name from the model's speaker_id_map
        }
        try:
            with open(cfg_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
            info["sampleRate"] = int(cfg.get("audio", {}).get("sample_rate", 22050))
            info["speakers"] = int(cfg.get("num_speakers", 1))
            lang = cfg.get("language") or {}
            info["lang"] = lang.get("code")
            info["family"] = lang.get("family")
            info["nameEnglish"] = lang.get("name_english")
            info["nameNative"] = lang.get("name_native")
            info["speakerNames"] = _speaker_names(cfg.get("speaker_id_map"), info["speakers"])
        except Exception:  # noqa: BLE001 — a missing/corrupt sidecar shouldn't hide the voice
            pass
        out.append(info)
    return out


def do_check(model_path, voices_path, model_dir):
    status = {
        "python": sys.version.split()[0],
        "kokoroImportable": False,
        "onnxruntime": None,
        "modelPresent": os.path.isfile(model_path),
        "voicesPresent": os.path.isfile(voices_path),
        "modelPath": model_path,
        "voicesPath": voices_path,
    }
    try:
        import kokoro_onnx  # noqa: F401

        status["kokoroImportable"] = True
    except Exception as e:  # noqa: BLE001
        status["kokoroError"] = str(e)
    try:
        import onnxruntime  # noqa: F401

        status["onnxruntime"] = onnxruntime.__version__
    except Exception:  # noqa: BLE001
        pass
    base_ready = bool(
        status["kokoroImportable"] and status["modelPresent"] and status["voicesPresent"]
    )
    status["ready"] = base_ready
    # Which walkthrough languages can actually narrate right now (see Languages.kt). The no-extra-deps
    # set is ready whenever Kokoro is; ja/zh additionally need their misaki backend installed.
    status["misakiJa"] = _misaki_lang_ready("ja")
    status["misakiZh"] = _misaki_lang_ready("zh")
    ready_langs = []
    if base_ready:
        ready_langs = ["en", "es", "fr", "hi", "it", "pt"]
        if status["misakiJa"]:
            ready_langs.append("ja")
        if status["misakiZh"]:
            ready_langs.append("zh")
    status["languagesReady"] = ready_langs
    # Robotic-tier (espeak) language codes the local build supports — the plugin uses these to populate /
    # validate the tier-2 menu. Zero extra deps: espeakng-loader ships with kokoro-onnx.
    status["espeakLanguages"] = _espeak_language_codes()
    # Piper offline-neural tier. `piperImportable` = the pip package is installed;
    # `piperVoices` = the models already downloaded into <model-dir>/piper (each with its metadata). Unlike
    # espeak, Piper needs a separate `pip install piper-tts` + per-voice downloads, so both are reported.
    status["piperImportable"] = _can_import("piper")
    status["piperVoices"] = _piper_installed_voices(model_dir)
    print(json.dumps(status), flush=True)


def _download(url, dest):
    tmp = dest + ".part"
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    name = os.path.basename(dest)
    last = [-1]

    def hook(blocks, block_size, total):
        if total <= 0:
            return
        pct = min(100, int(blocks * block_size * 100 / total))
        if pct != last[0]:
            last[0] = pct
            print(json.dumps({"type": "progress", "file": name, "pct": pct}), flush=True)

    urllib.request.urlretrieve(url, tmp, reporthook=hook)  # noqa: S310 (trusted GitHub release URL)
    os.replace(tmp, dest)
    print(json.dumps({"type": "downloaded", "file": name, "path": dest}), flush=True)


def do_download(model_dir):
    try:
        _download(MODEL_URL, os.path.join(model_dir, MODEL_NAME))
        _download(VOICES_URL, os.path.join(model_dir, VOICES_NAME))
        print(json.dumps({"type": "done", "modelDir": model_dir}), flush=True)
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"type": "error", "message": str(e)}), flush=True)
        sys.exit(1)


def _md5(path):
    """Hex md5 of a file, to match HF's published ``md5_digest`` (integrity check, not security)."""
    h = hashlib.md5()  # noqa: S324 — verifying against HF's published digest, not a security boundary
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def do_download_piper(model_dir, voice_key):
    """Download one Piper voice (its ``.onnx`` + ``.onnx.json``) from HF into <model-dir>/piper, resolving
    the repo paths from the catalog and md5-verifying each file against the catalog digest. Emits
    the same progress/downloaded/done frames as [do_download]; a corrupt download is removed, not kept."""
    try:
        entry = _fetch_piper_catalog().get(voice_key)
        if entry is None:
            raise RuntimeError("unknown Piper voice key: %s" % voice_key)
        dest_dir = piper_dir(model_dir)
        for path, meta in entry.get("files", {}).items():
            if not (path.endswith(".onnx") or path.endswith(".onnx.json")):
                continue
            dest = os.path.join(dest_dir, os.path.basename(path))
            _download(PIPER_BASE_URL + path, dest)
            want = meta.get("md5_digest") if isinstance(meta, dict) else None
            if want and _md5(dest).lower() != want.lower():
                os.remove(dest)
                raise RuntimeError(
                    "checksum mismatch for %s — download corrupt, removed" % os.path.basename(path))
        print(json.dumps({"type": "done", "voice": voice_key, "modelDir": dest_dir}), flush=True)
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"type": "error", "message": str(e)}), flush=True)
        sys.exit(1)


def do_delete_piper(model_dir, voice_key):
    """Delete one installed Piper voice (its ``.onnx`` + ``.onnx.json``) from <model-dir>/piper."""
    try:
        dest_dir = piper_dir(model_dir)
        removed = []
        for ext in (".onnx", ".onnx.json"):
            p = os.path.join(dest_dir, voice_key + ext)
            if os.path.isfile(p):
                os.remove(p)
                removed.append(os.path.basename(p))
        print(json.dumps({"type": "done", "voice": voice_key, "removed": removed}), flush=True)
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"type": "error", "message": str(e)}), flush=True)
        sys.exit(1)


def do_piper_catalog(model_dir, refresh=False):
    """Print the normalized Piper voice catalog as one JSON object for the voice-manager UI:
    ``{"type":"catalog","source":"network|cache|none","voices":[...],"error":?}``."""
    rows, source, error = _piper_catalog(model_dir, refresh=refresh)
    out = {"type": "catalog", "source": source, "voices": rows}
    if error:
        out["error"] = error
    print(json.dumps(out), flush=True)


def main():
    ap = argparse.ArgumentParser(description="Audio Explainer Kokoro TTS server (TCP loopback).")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=0, help="0 = pick a free port (reported in the handshake).")
    ap.add_argument("--model-dir", default=default_model_dir())
    ap.add_argument("--model", help="explicit model path (default: <model-dir>/%s)" % MODEL_NAME)
    ap.add_argument("--voices", help="explicit voices path (default: <model-dir>/%s)" % VOICES_NAME)
    ap.add_argument("--synthetic", action="store_true", help="sine-tone engine, no model (testing).")
    ap.add_argument("--download", action="store_true", help="download model + voices, then exit.")
    ap.add_argument("--download-piper", metavar="VOICE_KEY",
                    help="download one Piper voice (e.g. de_DE-eva_k-x_low) into <model-dir>/piper, then exit.")
    ap.add_argument("--delete-piper", metavar="VOICE_KEY",
                    help="delete one installed Piper voice from <model-dir>/piper, then exit.")
    ap.add_argument("--piper-catalog", action="store_true",
                    help="print the normalized Piper voice catalog JSON (cached under <model-dir>/piper), exit.")
    ap.add_argument("--refresh", action="store_true",
                    help="with --piper-catalog: force a re-fetch instead of using the on-disk cache.")
    ap.add_argument("--check", action="store_true", help="print prerequisite status JSON, then exit.")
    args = ap.parse_args()

    model_path = args.model or os.path.join(args.model_dir, MODEL_NAME)
    voices_path = args.voices or os.path.join(args.model_dir, VOICES_NAME)

    if args.check:
        do_check(model_path, voices_path, args.model_dir)
        return
    if args.download:
        do_download(args.model_dir)
        return
    if args.download_piper:
        do_download_piper(args.model_dir, args.download_piper)
        return
    if args.delete_piper:
        do_delete_piper(args.model_dir, args.delete_piper)
        return
    if args.piper_catalog:
        do_piper_catalog(args.model_dir, refresh=args.refresh)
        return

    if args.synthetic:
        # Synthetic mode is for exercising the audio path with no deps — don't let it reach espeak/SAPI/piper.
        engine_host = EngineHost(SyntheticEngine(), allow_espeak=False, allow_sapi=False, allow_piper=False)
    else:
        try:
            engine = KokoroEngine(model_path, voices_path)
        except Exception as e:  # noqa: BLE001
            print(json.dumps({"ready": False, "error": str(e)}), flush=True)
            sys.exit(2)
        # Kokoro is primary; espeak (robotic ~100-language tier), SAPI (System.Speech, Windows) and
        # Piper (offline-neural, if `piper-tts` is installed) are available on demand — all coexist with
        # Kokoro in this one process.
        engine_host = EngineHost(
            engine, model_dir=args.model_dir, allow_espeak=True,
            allow_sapi=(sys.platform == "win32"), allow_piper=_can_import("piper"))

    try:
        serve(engine_host, args.host, args.port)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
