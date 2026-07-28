// @ts-check

/** @type {ReturnType<typeof acquireVsCodeApi>} */
const vscode = acquireVsCodeApi();

// ── State ──

let state = {
	title: "",
	segments: [],
	currentSegment: -1,
	status: "idle",
	language: "en",
	languageName: "English",
	autonym: "English",
};

/** Cached saved-walkthrough list (from the plugin's `saved_list` message); rendered/filtered client-side. */
/** @type {{name: string, title: string, langCode?: string, langTag?: string, langName?: string}[]} */
let savedWalkthroughs = [];

// ── Audio player ──

/** @type {AudioContext | null} */
let audioCtx = null;
/** @type {GainNode | null} */
let gainNode = null;
let nextPlayTime = 0;
/** @type {AudioBufferSourceNode[]} */
let activeSources = [];
// Speed is handled by TTS server; Web Audio plays at 1x
let volume = 0.8;
let muted = false;
let audioPlaying = false;
/** True when audio was intentionally suspended via suspendAudio() (user pause). */
let intentionallySuspended = false;
let currentHighlightIndex = 0;
let totalHighlights = 0;
/** Last generation phase seen (Phase 7.5 #5) — used to auto-expand the banner on a fresh generation. */
let genLastPhase = "idle";
/** True when audio_end arrived but chunks are still pending (AudioContext suspended) */
let deferredPlaybackComplete = false;
/** Guard: true while waitForActiveSourcesToFinish has a pending wrapper or sent playback_complete */
let playbackCompleteWired = false;

/** @type {{base64: string, sampleRate: number}[]} */
let pendingChunks = [];

// ── Read-along transcript progress + seek (Phase 7.5 #12/#13) ──
// Kokoro exposes no per-word timestamps, but the TTS server tags every wire chunk with its sentence index
// (si) and that sentence's word count (sw). Per step we keep: the absolute AudioContext time the first
// buffer started (stepStartClock); every decoded buffer with its cumulative audio offset (stepBuffers, so
// a click can seek); and a per-sentence map (stepSentences: audio start/duration + word range). The
// read-along cursor maps the Web Audio playhead onto words *per sentence*, so it tracks the audio without
// drifting — the old scheme divided by a total duration that kept growing as ~0.5s frames streamed in,
// which made the cursor rush to the end then stall. A suspended AudioContext freezes currentTime, so the
// cursor freezes exactly where playback paused.
let stepStartClock = null;
let progressRaf = 0;
/** @type {HTMLSpanElement[]} */
let transcriptWordSpans = [];
let lastSpokenWord = -1;
/** The transcript text currently rendered as word spans — so a same-text re-render doesn't reset the cursor. */
let transcriptText = "";
/** @type {{buffer: AudioBuffer, offset: number}[]} Decoded buffers for the step + cumulative audio-seconds before each (for seeking). */
let stepBuffers = [];
/** @type {{wordStart: number, wordCount: number, tStart: number, dur: number}[]} Per-sentence audio span + word range. */
let stepSentences = [];
/** Total buffered audio-seconds for the current step so far. */
let stepAudioDuration = 0;
/** True once audio_end arrived for the current step (all chunks generated) — a precondition for seeking. */
let stepAudioEnded = false;

function ensureAudioContext() {
	if (!audioCtx) {
		audioCtx = new AudioContext({ sampleRate: 24000 });
		gainNode = audioCtx.createGain();
		gainNode.gain.value = muted ? 0 : volume;
		gainNode.connect(audioCtx.destination);
	}
	if (audioCtx.state === "suspended" && !intentionallySuspended) {
		audioCtx.resume().then(() => {
			// Flush any chunks that arrived while suspended
			const chunks = pendingChunks.slice();
			pendingChunks = [];
			for (const chunk of chunks) {
				playAudioChunk(chunk.base64, chunk.sampleRate, chunk.si, chunk.sw);
			}
			// If audio_end arrived while suspended, now handle deferred playback completion
			if (deferredPlaybackComplete) {
				deferredPlaybackComplete = false;
				waitForActiveSourcesToFinish();
			}
		});
	}
}

// Pre-warm AudioContext on first user interaction so it's ready when audio arrives
document.addEventListener("click", () => ensureAudioContext(), { once: true });


function playAudioChunk(base64Data, sampleRate, si, sw) {
	ensureAudioContext();

	// If AudioContext is still suspended (no user gesture yet), queue the chunk
	if (audioCtx.state === "suspended") {
		pendingChunks.push({ base64: base64Data, sampleRate, si, sw });
		return;
	}

	const binary = atob(base64Data);
	const bytes = new Uint8Array(binary.length);
	for (let i = 0; i < binary.length; i++) {
		bytes[i] = binary.charCodeAt(i);
	}
	const float32 = new Float32Array(bytes.buffer);

	const buffer = audioCtx.createBuffer(1, float32.length, sampleRate);
	buffer.getChannelData(0).set(float32);

	const source = audioCtx.createBufferSource();
	source.buffer = buffer;
	source.playbackRate.value = 1;
	source.connect(gainNode);

	const now = audioCtx.currentTime;
	if (nextPlayTime < now) nextPlayTime = now;
	source.start(nextPlayTime);
	// Read-along timeline (Phase 7.5 #12/#13): the first scheduled buffer marks this step's audio start.
	if (stepStartClock === null) stepStartClock = nextPlayTime;
	nextPlayTime += buffer.duration;
	recordChunk(buffer, si, sw);

	activeSources.push(source);
	source.onended = () => {
		const idx = activeSources.indexOf(source);
		if (idx !== -1) activeSources.splice(idx, 1);
		vscode.postMessage({ type: "chunk_played" });
	};

	audioPlaying = true;
	ensureProgressLoop();
}

/**
 * Record a decoded chunk into the step's buffer list + per-sentence map (Phase 7.5 #12/#13). Buffers are
 * retained (with their cumulative audio offset) so a click can seek playback; the sentence map (audio
 * start + duration + word range, from the wire's si/sw) is what anchors the read-along cursor per sentence.
 */
function recordChunk(buffer, si, sw) {
	const dur = buffer.duration;
	stepBuffers.push({ buffer, offset: stepAudioDuration });
	let sent = stepSentences[si];
	if (!sent) {
		let wordStart = 0;
		for (let k = 0; k < si; k++) wordStart += stepSentences[k] ? stepSentences[k].wordCount : 0;
		sent = stepSentences[si] = { wordStart, wordCount: Math.max(1, sw || 1), tStart: stepAudioDuration, dur: 0 };
	}
	sent.dur += dur;
	stepAudioDuration += dur;
}

function stopAudio() {
	intentionallySuspended = false;
	for (const source of activeSources) {
		// Clear onended BEFORE stopping to prevent stale playback_complete messages.
		// Without this, wrapped onended callbacks (from waitForActiveSourcesToFinish)
		// fire and send playback_complete which can resolve the NEXT chunk's promise,
		// causing the highlight loop to skip subsegments and leak orphaned TTS streams.
		source.onended = null;
		try { source.stop(); } catch {}
	}
	activeSources = [];
	pendingChunks = [];
	nextPlayTime = 0;
	audioPlaying = false;
	deferredPlaybackComplete = false;
	playbackCompleteWired = false;
	resetStepProgress();
}

/** Suspend AudioContext to freeze audio in place (pause mid-highlight). */
function suspendAudio() {
	if (audioCtx && audioCtx.state === "running") {
		intentionallySuspended = true;
		audioCtx.suspend();
	}
	// Don't clear sources or nextPlayTime — we want to resume from here
}

/** Resume AudioContext and wait for remaining buffered audio to finish. */
function resumeAudio() {
	intentionallySuspended = false;
	if (audioCtx && audioCtx.state === "suspended") {
		// Always resume the AudioContext so subsequent playAudioChunk() calls
		// don't silently push to pendingChunks instead of playing.
		audioCtx.resume().then(() => {
			ensureProgressLoop(); // restart the read-along cursor from where it froze
			waitForActiveSourcesToFinish();
		});
	} else {
		// Nothing to resume — signal immediately so extension can re-stream
		vscode.postMessage({ type: "playback_complete" });
	}
}

/**
 * Wait for all active audio sources to finish, then send playback_complete.
 * If no sources are active (already drained), sends immediately.
 * Idempotent: safe to call multiple times (onAudioEnd + resumeAudio) —
 * only the first call wires the wrapper; subsequent calls are no-ops.
 */
function waitForActiveSourcesToFinish() {
	if (playbackCompleteWired || intentionallySuspended) return;
	playbackCompleteWired = true;
	if (activeSources.length === 0) {
		audioPlaying = false;
		playbackCompleteWired = false;
		vscode.postMessage({ type: "playback_complete" });
		return;
	}
	const lastSource = activeSources[activeSources.length - 1];
	const originalOnEnded = lastSource.onended;
	lastSource.onended = (e) => {
		if (originalOnEnded) originalOnEnded.call(lastSource, e);
		audioPlaying = false;
		playbackCompleteWired = false;
		vscode.postMessage({ type: "playback_complete" });
	};
}

function onAudioEnd() {
	stepAudioEnded = true; // all chunks for this step have been generated → click-to-seek is now allowed
	// Wait for actual Web Audio playback to finish,
	// then signal the extension so it can advance to the next sub-highlight.
	// If chunks are still pending (AudioContext suspended), defer until they're flushed.
	if (pendingChunks.length > 0) {
		deferredPlaybackComplete = true;
		return;
	}
	waitForActiveSourcesToFinish();
}

function updateVolume() {
	if (gainNode) {
		gainNode.gain.value = muted ? 0 : volume;
	}
}

// ── UI rendering ──

function showDoneView() {
	document.getElementById("idle-view").style.display = "none";
	document.getElementById("active-view").style.display = "none";
	document.getElementById("done-view").style.display = "";
	document.getElementById("done-summary").textContent =
		`${state.segments.length} segments covered`;
}

/** The highlight object for the current step (bounded), or null if the segment has no highlights. */
function currentHighlight() {
	const seg = state.segments.find((s) => s.id === state.currentSegment);
	if (!seg || !seg.highlights || seg.highlights.length === 0) return null;
	const i = Math.min(Math.max(currentHighlightIndex, 0), seg.highlights.length - 1);
	return seg.highlights[i];
}

/**
 * Show the exact narration text (a highlight's ttsText) for the current step — i.e. the transcript
 * of what the audio says. Driven from local state, so it works whether or not audio is playing.
 *
 * The text is rendered as per-word spans so the read-along cursor (Phase 7.5 #12) can highlight one word at
 * a time. A same-text re-render (e.g. a play/pause status update) is a no-op so it never resets the cursor.
 */
function renderTranscript(animate) {
	const box = document.getElementById("transcript-box");
	const el = document.getElementById("transcript-text");
	if (!box || !el) return;
	const hl = currentHighlight();
	const text = hl && hl.ttsText ? hl.ttsText : "";
	const bar = document.getElementById("step-progress-fill");
	if (!text) {
		box.style.display = "none";
		el.replaceChildren();
		transcriptWordSpans = [];
		transcriptText = "";
		lastSpokenWord = -1;
		if (bar) bar.style.width = "0";
		return;
	}
	box.style.display = "";
	// Only rebuild the word spans when the narration text actually changed — otherwise keep the live cursor.
	if (text === transcriptText && transcriptWordSpans.length) return;
	transcriptText = text;
	setTranscriptWords(el, text);
	lastSpokenWord = -1;
	if (bar) bar.style.width = "0";
	if (animate) {
		el.classList.add("fade-out");
		requestAnimationFrame(() => el.classList.remove("fade-out"));
	}
}

/**
 * Rebuild #transcript-text as per-word spans. Each word span ALSO absorbs the whitespace run that follows
 * it, so when consecutive words are marked "spoken" their backgrounds abut with no gap — the fill reads as
 * one continuous progress bar rather than separate word chips (Phase 7.5 #14 task 4). Only leading/orphan
 * whitespace (with no preceding word) stays a plain text node. container white-space:pre-wrap preserves the
 * original spacing either way.
 */
function setTranscriptWords(el, text) {
	el.replaceChildren();
	transcriptWordSpans = [];
	// Japanese/Chinese have no spaces, so whitespace tokenisation would collapse the whole narration into
	// ONE span (the read-along cursor would light the entire block at once). For CJK we tokenise per
	// SENTENCE instead — matching the TTS server, which streams one audio chunk per CJK sentence (sw=1), so
	// the cursor advances a sentence at a time. A finer per-character cursor is deferred (ExplainerLanguages.md §6).
	if (isCJKText(text)) {
		setTranscriptSentences(el, text);
		return;
	}
	const toks = text.split(/(\s+)/);
	for (let t = 0; t < toks.length; t++) {
		const tok = toks[t];
		if (!tok) continue;
		if (/^\s+$/.test(tok)) {
			// Whitespace with no word before it (leading, or a run we didn't absorb) → plain text node.
			el.appendChild(document.createTextNode(tok));
		} else {
			const s = document.createElement("span");
			s.className = "tw";
			// Absorb the immediately-following whitespace run into this word's span (so the spoken fill is
			// gapless). The word index we push is still just the word, so click-to-seek is unaffected.
			if (t + 1 < toks.length && /^\s+$/.test(toks[t + 1])) {
				s.textContent = tok + toks[t + 1];
				t++;
			} else {
				s.textContent = tok;
			}
			el.appendChild(s);
			transcriptWordSpans.push(s);
		}
	}
}

/** True if the text contains Japanese/Chinese characters (hiragana, katakana, or Han). */
function isCJKText(text) {
	return /[぀-ヿ㐀-䶿一-鿿豈-﫿ｦ-ﾟ]/.test(text);
}

/**
 * Tokenise a CJK transcript into one span per sentence, splitting AFTER each sentence ender (。．！？…)
 * and keeping the ender with its sentence. Pairs with the TTS server's per-CJK-sentence chunks (sw=1) so
 * the read-along cursor advances one sentence at a time. Latin languages use the per-word path above.
 */
function setTranscriptSentences(el, text) {
	let buf = "";
	const flush = () => {
		if (!buf) return;
		const s = document.createElement("span");
		s.className = "tw";
		s.textContent = buf;
		el.appendChild(s);
		transcriptWordSpans.push(s);
		buf = "";
	};
	for (const ch of text) {
		buf += ch;
		if ("。．！？…".indexOf(ch) >= 0) flush();
	}
	flush();
}

/** Clear the read-along visual + audio-clock/buffer bookkeeping for the current step (called on stop/restart). */
function resetStepProgress() {
	if (progressRaf) { cancelAnimationFrame(progressRaf); progressRaf = 0; }
	resetStepAudioModel();
	lastSpokenWord = -1;
	const bar = document.getElementById("step-progress-fill");
	if (bar) bar.style.width = "0";
	for (const s of transcriptWordSpans) if (s.className !== "tw") s.className = "tw";
}

/** Clear the per-step audio bookkeeping (clock, retained buffers, sentence map). New step + on stop. */
function resetStepAudioModel() {
	stepStartClock = null;
	stepBuffers = [];
	stepSentences = [];
	stepAudioDuration = 0;
	stepAudioEnded = false;
}

/** Start the rAF loop driving the read-along cursor, if audio is playing and it isn't already running. */
function ensureProgressLoop() {
	if (!progressRaf && audioPlaying) progressRaf = requestAnimationFrame(updateStepProgress);
}

/** rAF tick: map the Web Audio playhead onto the transcript words + step progress bar. */
function updateStepProgress() {
	progressRaf = 0;
	if (audioCtx && stepStartClock !== null) {
		applyReadAlong(audioCtx.currentTime - stepStartClock);
	}
	if (audioPlaying) progressRaf = requestAnimationFrame(updateStepProgress);
}

/**
 * Paint the read-along "fill" for [elapsed] audio-seconds since this step's audio started (Phase 7.5
 * #12/#13). Words up to the playhead keep the "spoken" wash; the word at the playhead adds "speaking".
 * Monotonic during playback (the currently-streaming sentence's duration can still grow, which would
 * otherwise nudge the cursor back a word), but a seek resets lastSpokenWord so the fill may jump backward.
 * Mutates the DOM only when the word index changes, so a burst of rAF frames collapses into a few
 * mutations/second — which the MutationObserver turns into the native repaints CEF needs (Phase 7.5 #6).
 */
function applyReadAlong(elapsed) {
	const n = transcriptWordSpans.length;
	const bar = document.getElementById("step-progress-fill");
	if (n === 0) { if (bar) bar.style.width = "0"; return; }
	let cur = currentWordFor(elapsed);
	if (cur < lastSpokenWord) cur = lastSpokenWord; // monotonic during play; seek resets lastSpokenWord to -1
	if (cur === lastSpokenWord) return;
	lastSpokenWord = cur;
	for (let i = 0; i < n; i++) {
		const cls = i < cur ? "tw spoken" : i === cur ? "tw speaking" : "tw";
		if (transcriptWordSpans[i].className !== cls) transcriptWordSpans[i].className = cls;
	}
	if (bar) bar.style.width = (((cur + 1) / n) * 100).toFixed(1) + "%";
}

/** The current word index (0-based) for [elapsed] audio-seconds, from the per-sentence audio→word map. */
function currentWordFor(elapsed) {
	const n = transcriptWordSpans.length;
	const e = Math.max(0, elapsed);
	let word = 0;
	for (const s of stepSentences) {
		if (!s) continue;
		if (e >= s.tStart + s.dur - 0.0001) {
			word = s.wordStart + s.wordCount; // playhead is past this whole sentence
		} else {
			const local = s.dur > 0.0001 ? (e - s.tStart) / s.dur : 0;
			return Math.min(n - 1, s.wordStart + Math.floor(Math.max(0, local) * s.wordCount));
		}
	}
	return Math.min(n - 1, Math.max(0, word));
}

// ── Click-to-seek within the current step (Phase 7.5 #13) ──

/** Whether a click-to-seek is currently possible: the step's audio must be fully generated + buffered. */
function seekAllowed() {
	return stepAudioEnded && pendingChunks.length === 0 && stepBuffers.length > 0 && !!audioCtx;
}

/** The sentence whose word range contains [w], or null. */
function sentenceForWord(w) {
	for (const s of stepSentences) {
		if (!s) continue;
		if (w >= s.wordStart && w < s.wordStart + s.wordCount) return s;
	}
	return null;
}

/**
 * Seek playback to the given word: reschedule the step's buffered audio from that word's sentence-anchored
 * time. If currently paused, also asks the controller to resume so it plays from there (Phase 7.5 #13).
 */
function seekToWord(wordIndex) {
	if (!seekAllowed()) return;
	const s = sentenceForWord(wordIndex);
	const t = s
		? s.tStart + ((wordIndex - s.wordStart) / s.wordCount) * s.dur
		: (wordIndex / Math.max(1, transcriptWordSpans.length)) * stepAudioDuration;
	if (seekToAudioTime(t) && state.status !== "playing") {
		vscode.postMessage({ type: "play_pause" }); // start playing from the seeked position
	}
}

/** Seek to a fraction [0,1] of the step (used by the thin bar), snapped to the nearest word. */
function seekToFraction(f) {
	const n = transcriptWordSpans.length;
	if (n === 0) return;
	seekToWord(Math.max(0, Math.min(n - 1, Math.floor(f * n))));
}

/**
 * Reschedule the current step's buffered audio to start from audio-second [t] (Phase 7.5 #13). Stops the
 * currently-scheduled sources and re-queues the retained buffers from the one containing [t], re-anchors
 * the read-along clock, and re-wires playback completion. Returns false if it couldn't seek.
 */
function seekToAudioTime(t) {
	if (!audioCtx || stepBuffers.length === 0) return false;
	const total = stepAudioDuration;
	const target = Math.max(0, Math.min(t, Math.max(0, total - 0.02)));
	// Find the retained buffer that contains the target time.
	let bi = 0;
	while (bi < stepBuffers.length - 1 && stepBuffers[bi + 1].offset <= target) bi++;
	const intra = Math.max(0, target - stepBuffers[bi].offset);

	// Stop everything currently scheduled (clear onended first, so no stale playback_complete fires).
	for (const src of activeSources) { src.onended = null; try { src.stop(); } catch {} }
	activeSources = [];

	// Re-queue the retained buffers from the target, contiguously from now.
	let when = audioCtx.currentTime;
	for (let i = bi; i < stepBuffers.length; i++) {
		const buf = stepBuffers[i].buffer;
		const off = i === bi ? intra : 0;
		if (off >= buf.duration) continue;
		const src = audioCtx.createBufferSource();
		src.buffer = buf;
		src.playbackRate.value = 1;
		src.connect(gainNode);
		try { src.start(when, off); } catch { continue; }
		when += buf.duration - off;
		activeSources.push(src);
		src.onended = () => {
			const k = activeSources.indexOf(src);
			if (k !== -1) activeSources.splice(k, 1);
			vscode.postMessage({ type: "chunk_played" });
		};
	}
	nextPlayTime = when;

	// Re-anchor the read-along clock so elapsed == target right now; allow a backward jump.
	stepStartClock = audioCtx.currentTime - target;
	lastSpokenWord = -1;
	applyReadAlong(target);

	// Re-wire completion: the previously-wired last source was just stopped. If we're suspended (seeked
	// while paused), resumeAudio() will wire it when the controller resumes us.
	playbackCompleteWired = false;
	if (!intentionallySuspended) waitForActiveSourcesToFinish();
	ensureProgressLoop();
	return true;
}

/** Which top-level view was last shown; a change of view is a layout transition that needs a repaint. */
let lastRenderedView = "";

/** Force a full repaint only when the visible top-level view actually changed (idle ↔ active ↔ done). */
function maybeRepaintOnViewChange(view) {
	if (view !== lastRenderedView) {
		lastRenderedView = view;
		forceFullRepaint();
	}
}

function render() {
	const idleView = document.getElementById("idle-view");
	const activeView = document.getElementById("active-view");
	const doneView = document.getElementById("done-view");

	if (state.status === "idle") {
		idleView.style.display = "";
		activeView.style.display = "none";
		doneView.style.display = "none";
		vscode.postMessage({ type: "request_saved_list" });
		maybeRepaintOnViewChange("idle");
		return;
	}

	if (state.status === "stopped") {
		idleView.style.display = "";
		activeView.style.display = "none";
		doneView.style.display = "none";
		vscode.postMessage({ type: "request_saved_list" });
		maybeRepaintOnViewChange("idle");
		return;
	}

	idleView.style.display = "none";
	activeView.style.display = "";
	doneView.style.display = "none";

	// Title + language badge
	document.getElementById("walkthrough-title").textContent = state.title;
	renderLanguageBadge();

	// Now playing
	const seg = state.segments.find((s) => s.id === state.currentSegment);
	const idx = state.segments.findIndex((s) => s.id === state.currentSegment);

	if (seg) {
		document.getElementById("segment-title").textContent = seg.title;

		const loc = document.getElementById("segment-location");
		const fileName = seg.file.split("/").pop();
		loc.textContent = `${fileName}:${seg.start}-${seg.end}`;
		loc.dataset.file = seg.file;
		loc.dataset.start = String(seg.start);
		loc.dataset.end = String(seg.end);
	}

	// Play/pause button (SVG icon toggle)
	const playBtn = document.getElementById("btn-play-pause");
	const iconPlay = playBtn.querySelector(".icon-play");
	const iconPause = playBtn.querySelector(".icon-pause");
	if (state.status === "playing") {
		iconPlay.style.display = "none";
		iconPause.style.display = "";
	} else {
		iconPlay.style.display = "";
		iconPause.style.display = "none";
	}
	playBtn.title = state.status === "playing" ? "Pause" : "Play";

	// Pulse animation when paused (ready to play)
	if (state.status === "paused") {
		playBtn.classList.add("pulse");
	} else {
		playBtn.classList.remove("pulse");
	}

	// Explanation with fade transition
	if (seg) {
		const explEl = document.getElementById("explanation-text");
		explEl.classList.add("fade-out");
		setTimeout(() => {
			explEl.innerHTML = simpleMarkdown(seg.explanation);
			explEl.classList.remove("fade-out");
		}, 150);
	}

	// Outline
	renderOutline(idx);

	// Transcript of the current step (matches the audio narration)
	renderTranscript(false);

	// Switching into the active view is a layout transition — repaint so nothing ghosts (see forceFullRepaint).
	maybeRepaintOnViewChange("active");
}

/**
 * Show the walkthrough's language as a small badge next to the title when it isn't English (Phase L3).
 * The badge text is the native name (autonym); its tooltip is the English name.
 */
function renderLanguageBadge() {
	const badge = document.getElementById("wt-language");
	if (!badge) return;
	const code = state.language || "en";
	if (code && code !== "en") {
		badge.textContent = state.autonym || state.languageName || code;
		badge.style.display = "";
		setTip(badge, (state.languageName || code) + " narration");
	} else {
		badge.style.display = "none";
		badge.removeAttribute("data-ae-tip");
	}
}

/**
 * Render the idle-view "Saved Walkthroughs" list from the cached `savedWalkthroughs`, applying the
 * current filter text (case-insensitive "contains" over the [LANG] tag + title). Each row shows the
 * language tag, the title, and a Delete (✕) button revealed on hover; clicking the row loads the
 * walkthrough, clicking ✕ deletes it (confirmed plugin-side). The whole section hides when nothing is
 * saved; a "no matches" note shows when the filter excludes everything.
 */
function renderSavedList() {
	const section = document.getElementById("saved-list-section");
	const list = document.getElementById("saved-list");
	const empty = document.getElementById("saved-empty");
	const filterEl = document.getElementById("saved-filter");
	if (!section || !list) return;

	if (!savedWalkthroughs.length) {
		section.style.display = "none";
		list.replaceChildren();
		if (empty) empty.style.display = "none";
		return;
	}
	section.style.display = "";

	const q = (filterEl && filterEl.value ? filterEl.value : "").trim().toLowerCase();
	const matches = savedWalkthroughs.filter((wt) => {
		if (!q) return true;
		const hay = `[${wt.langTag || ""}] ${wt.title || ""}`.toLowerCase();
		return hay.indexOf(q) >= 0;
	});

	list.replaceChildren();
	for (const wt of matches) {
		const li = document.createElement("li");
		li.className = "saved-item";

		const tag = document.createElement("span");
		tag.className = "saved-tag";
		tag.textContent = `[${wt.langTag || "?"}]`;
		if (wt.langName) setTip(tag, wt.langName);

		const title = document.createElement("span");
		title.className = "saved-title";
		title.textContent = wt.title;

		const del = document.createElement("button");
		del.className = "saved-del";
		del.title = "Delete this walkthrough";
		del.innerHTML = "&#10005;"; // ✕
		del.addEventListener("click", (e) => {
			e.stopPropagation(); // don't also trigger the row's load handler
			vscode.postMessage({ type: "delete_walkthrough", name: wt.name, title: wt.title });
		});

		li.appendChild(tag);
		li.appendChild(title);
		li.appendChild(del);
		li.addEventListener("click", () => {
			vscode.postMessage({ type: "load", name: wt.name });
		});
		list.appendChild(li);
	}

	if (empty) empty.style.display = matches.length ? "none" : "";
}

/**
 * Rebuild the voice <select> from the language-specific voice list pushed by the plugin (Phase L3),
 * then select `selected` (falling back to the first voice). Options are {id,label}.
 */
function rebuildVoiceOptions(voices, selected) {
	const vs = document.getElementById("voice-select");
	if (!vs || !Array.isArray(voices)) return;
	vs.replaceChildren();
	for (const v of voices) {
		const opt = document.createElement("option");
		opt.value = v.id;
		// Append the engine badge for consistency across tiers ("Heart (US, F) — kokoro",
		// "Robotic (F) — espeak", "Aria (F) — WinSAPI, online"). Falls back to the bare label.
		opt.textContent = v.engineTag ? `${v.label} — ${v.engineTag}` : v.label;
		if (v.robotic) opt.dataset.robotic = "1"; // drives the robotic-row toggle on selection change
		vs.appendChild(opt);
	}
	if (selected && voices.some((v) => v.id === selected)) vs.value = selected;
	else if (voices.length) vs.value = voices[0].id;
}

function computeGlobalProgress() {
	if (state.segments.length === 0) {
		return { current: 0, total: 0 };
	}
	let totalGlobalHighlights = 0;
	let completedHighlights = 0;
	const currentIdx = state.segments.findIndex(s => s.id === state.currentSegment);

	for (let i = 0; i < state.segments.length; i++) {
		const seg = state.segments[i];
		const segHighlights = (seg.highlights && seg.highlights.length > 0) ? seg.highlights.length : 1;
		totalGlobalHighlights += segHighlights;

		if (i < currentIdx) {
			completedHighlights += segHighlights;
		} else if (i === currentIdx) {
			completedHighlights += currentHighlightIndex;
		}
	}

	return { current: completedHighlights + 1, total: totalGlobalHighlights };
}

function renderHighlightProgress() {
	const counter = document.getElementById("segment-counter");
	if (!counter) return;

	const { current, total } = computeGlobalProgress();
	if (total > 0) {
		counter.textContent = `${current}/${total}`;
	} else {
		counter.textContent = "";
	}

	// Update progress bar
	const progressFill = document.getElementById("progress-fill");
	if (progressFill && total > 0) {
		const pct = (current / total) * 100;
		progressFill.style.width = `${pct}%`;
	}

	// Update nav button icons based on current highlight count
	updateNavIcons();
}

/**
 * Signature of the last full outline build. Content-sensitive: it captures each segment's id, title,
 * file/line range AND every substep's line range + label — not just the highlight COUNT. A rebuild is
 * triggered whenever any of those change, but NOT on mere navigation (which changes currentSegment, not
 * the segments' content), so per-step click handlers survive normal Prev/Next/outline use.
 *
 * The old count-only signature collided when a new plan happened to have the same shape as the previous
 * one (e.g. 6 substeps → 6 substeps): the DOM was not rebuilt, leaving stale labels from the old plan.
 * On top of that, a shorter new plan (or an Undo) could leave "ghost" rows of the previous, longer outline
 * on screen even though the DOM was already correct — CEF failed to repaint the region the removed rows
 * vacated (correct new text in the overlapping area, stale old text only below it). renderOutline now
 * (a) rebuilds from scratch whenever the content changed OR the live DOM drifted out of sync
 * (outlineDomInSync — a hard invariant that can never keep a stale row), and (b) forces a full repaint of
 * the scroll region after a rebuild, clearing any ghost pixels (Phase 7.5 #6).
 */
let outlineSignature = "";

/** Content signature of the current segments (see outlineSignature). */
function computeOutlineSignature() {
	return state.segments
		.map((s) => {
			const hls = (s.highlights || [])
				.map((h) => `${h.start}-${h.end}~${(h.explanation || "").trim()}`)
				.join(",");
			return `${s.id}␞${s.title}␞${s.file}:${s.start}-${s.end}␞${hls}`;
		})
		.join("␟");
}

/**
 * Hard invariant: does the live DOM match state.segments exactly — one <li> per segment, and each
 * segment's rendered substep count equal to its highlights? If not, a stale row survived a previous
 * render and we MUST rebuild rather than reconcile marker classes onto a mismatched tree.
 */
function outlineDomInSync(list) {
	const lis = list.children;
	if (lis.length !== state.segments.length) return false;
	for (let i = 0; i < state.segments.length; i++) {
		const want = (state.segments[i].highlights || []).length;
		if (lis[i].querySelectorAll(".outline-highlight").length !== want) return false;
	}
	return true;
}

/**
 * Force CEF to repaint the ENTIRE tool window. JCEF/CEF frequently leaves stale "ghost" pixels after a
 * layout change (a banner appearing/disappearing, a view switch, the outline shrinking) — the DOM/layout
 * is correct but the old pixels linger until some input event (a hover) forces a repaint. That's the
 * "crooked layout / loading strip won't hide until I mouse over it" symptom (Phase 7.5 #6).
 *
 * Two independent levers, because CEF's OSR invalidation is flaky and render-mode-dependent:
 *  1. Toggle `body`'s display off/on with forced reflows — invalidates the whole surface at the DOM layer.
 *  2. Ask the plugin to call the native `CefBrowser.invalidate()` (via the `repaint` message).
 * Both are synchronous/idempotent and only fire at layout transitions (not on every update or audio tick),
 * so there's no per-frame cost; the display toggle paints only the final state, so there's no flicker.
 * Scroll position is preserved.
 */
function forceFullRepaint() {
	const scroller = document.getElementById("view-scroll");
	const prev = scroller ? scroller.scrollTop : 0;
	const body = document.body;
	body.style.display = "none";
	void body.offsetHeight; // flush the 'none' so the toggle isn't coalesced away
	body.style.display = ""; // revert to the stylesheet's display:flex
	void body.offsetHeight; // flush the restore → forces a fresh paint of the whole surface
	if (scroller) scroller.scrollTop = prev;
	// Native belt-and-suspenders: let the plugin force a CEF-level repaint too.
	vscode.postMessage({ type: "repaint" });
}

/**
 * Lightweight, coalesced native repaint. JCEF/CEF also fails to repaint on *non-layout* DOM changes without
 * an input event — e.g. the outline's step markers flipping to ✓/▶ on audio step-advance stayed stale until
 * you moused over them (a mouse event is what makes CEF paint). A single MutationObserver (set up below)
 * calls this on any DOM change; it asks the plugin for a native `CefBrowser.invalidate()` — the programmatic
 * equivalent of that hover. No `body` display toggle here (the change needs a *repaint*, not a relayout), so
 * it never disturbs in-flight CSS fades. Debounced, so a burst of mutations (one step advance touches the
 * progress bar, the outline markers and the transcript) collapses into a single repaint (Phase 7.5 #6).
 */
let nativeRepaintScheduled = false;
function scheduleNativeRepaint() {
	if (nativeRepaintScheduled) return;
	nativeRepaintScheduled = true;
	setTimeout(() => {
		nativeRepaintScheduled = false;
		vscode.postMessage({ type: "repaint" });
	}, 50);
}

function renderOutline(currentIdx) {
	const list = document.getElementById("outline-list");

	const signature = computeOutlineSignature();

	// Rebuild the whole list from scratch when the content changed OR when the live DOM has drifted out of
	// sync with state.segments (outlineDomInSync — a hard invariant). We never reconcile marker classes onto
	// a mismatched tree, so a stale row from a previous (longer) plan can never linger (Phase 7.5 #6).
	const needRebuild = signature !== outlineSignature || !outlineDomInSync(list);
	if (needRebuild) {
		list.replaceChildren();
		outlineSignature = signature;

		for (let i = 0; i < state.segments.length; i++) {
			const seg = state.segments[i];
			const li = document.createElement("li");
			li.className = "outline-segment";

			// Segment header (clickable \u2192 jump to & play the segment)
			const header = document.createElement("div");
			header.className = "outline-segment-header";

			const marker = document.createElement("span");
			marker.className = "marker";

			const text = document.createElement("span");
			text.className = "segment-label";
			text.textContent = `${i + 1}. ${seg.title}`;

			const progress = document.createElement("span");
			progress.className = "segment-progress";

			header.appendChild(marker);
			header.appendChild(text);
			header.appendChild(progress);
			header.addEventListener("pointerdown", (e) => {
				e.preventDefault();
				vscode.postMessage({ type: "goto_segment", segmentId: seg.id });
			});
			li.appendChild(header);

			// Highlight steps \u2014 each is an individually navigable/clickable step of the walkthrough.
			const highlights = seg.highlights || [];
			if (highlights.length > 0) {
				const hlList = document.createElement("ul");
				hlList.className = "outline-highlights";
				for (let j = 0; j < highlights.length; j++) {
					const hl = highlights[j];
					const hli = document.createElement("li");
					hli.className = "outline-highlight";

					const hlMarker = document.createElement("span");
					hlMarker.className = "hl-marker";

					const hlLabel = document.createElement("span");
					hlLabel.className = "hl-label";
					hlLabel.textContent = (hl.explanation && hl.explanation.trim()) || `Step ${j + 1}`;

					const hlLines = document.createElement("span");
					hlLines.className = "hl-lines";
					hlLines.textContent = hl.start === hl.end ? `${hl.start}` : `${hl.start}\u2013${hl.end}`;

					hli.appendChild(hlMarker);
					hli.appendChild(hlLabel);
					hli.appendChild(hlLines);
					hli.addEventListener("pointerdown", (e) => {
						e.preventDefault();
						vscode.postMessage({ type: "goto_highlight", segmentId: seg.id, highlightIndex: j });
					});
					hlList.appendChild(hli);
				}
				li.appendChild(hlList);
			}

			list.appendChild(li);
		}
	}

	// A shrunk outline can leave "ghost" pixels of the old, taller list in CEF; a rebuild forces a repaint.
	if (needRebuild) forceFullRepaint();

	// Update state classes on segment headers AND their highlight steps.
	const items = list.children;
	for (let i = 0; i < items.length; i++) {
		const li = items[i];
		const seg = state.segments[i];
		const header = li.querySelector(".outline-segment-header");

		if (i === currentIdx) header.className = "outline-segment-header current";
		else if (i < currentIdx) header.className = "outline-segment-header completed";
		else header.className = "outline-segment-header";

		const marker = header.querySelector(".marker");
		if (i < currentIdx) marker.textContent = "\u2713";
		else if (i === currentIdx) marker.textContent = "\u25B6";
		else marker.textContent = "\u25CB";

		// Segment progress fraction (prefer live totalHighlights for the current segment).
		const segTotal = (seg.highlights && seg.highlights.length) || 0;
		const total = (i === currentIdx && totalHighlights > 0) ? totalHighlights : (segTotal || 1);
		const progress = header.querySelector(".segment-progress");
		if (total > 1) {
			let completed;
			if (i < currentIdx) completed = total;
			else if (i === currentIdx) completed = currentHighlightIndex + 1;
			else completed = 0;
			progress.textContent = `${completed}/${total}`;
		} else {
			progress.textContent = "";
		}

		// Per-step markers/classes.
		const hlItems = li.querySelectorAll(".outline-highlight");
		for (let j = 0; j < hlItems.length; j++) {
			const hli = hlItems[j];
			const isCurrent = i === currentIdx && j === currentHighlightIndex;
			const isDone = i < currentIdx || (i === currentIdx && j < currentHighlightIndex);
			hli.className = "outline-highlight" + (isCurrent ? " current" : isDone ? " completed" : "");
			const m = hli.querySelector(".hl-marker");
			if (isDone) m.textContent = "\u2713";
			else if (isCurrent) m.textContent = "\u25B6";
			else m.textContent = "\u25CB";
		}
	}
}

function simpleMarkdown(text) {
	if (!text) return "";
	return text
		.replace(/&/g, "&amp;")
		.replace(/</g, "&lt;")
		.replace(/>/g, "&gt;")
		.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
		.replace(/`(.+?)`/g, "<code>$1</code>")
		.replace(/\n\n/g, "<br><br>")
		.replace(/\n/g, "<br>");
}

// ── Hold-to-pause (spacebar) ──

let holdPaused = false;

// ── Shift modifier tracking ──

let shiftHeld = false;

function updateNavIcons() {
	const showSegment = shiftHeld || totalHighlights <= 1;
	document.querySelectorAll("#btn-prev, #btn-next").forEach((btn) => {
		const highlightIcon = btn.querySelector(".icon-highlight");
		const segmentIcon = btn.querySelector(".icon-segment");
		if (highlightIcon && segmentIcon) {
			highlightIcon.style.display = showSegment ? "none" : "";
			segmentIcon.style.display = showSegment ? "" : "none";
		}
	});
}

document.addEventListener("keydown", (e) => {
	if (e.key === "Shift" && !shiftHeld) {
		shiftHeld = true;
		updateNavIcons();
	}
	if (e.code === "Space" && !e.repeat) {
		// Don't intercept space on interactive elements
		if (e.target.tagName === "BUTTON" || e.target.tagName === "INPUT" || e.target.tagName === "SELECT") {
			return;
		}
		e.preventDefault();
		// Only hold-pause if currently playing (not if user-paused)
		if (state.status === "playing" && !holdPaused) {
			holdPaused = true;
			ensureAudioContext();
			vscode.postMessage({ type: "play_pause" });
		}
	}
});

document.addEventListener("keyup", (e) => {
	if (e.key === "Shift") {
		shiftHeld = false;
		updateNavIcons();
	}
	if (e.code === "Space") {
		// Don't intercept space on interactive elements
		if (e.target.tagName === "BUTTON" || e.target.tagName === "INPUT" || e.target.tagName === "SELECT") {
			return;
		}
		e.preventDefault();
		if (holdPaused) {
			holdPaused = false;
			vscode.postMessage({ type: "play_pause" });
		}
	}
});

window.addEventListener("blur", () => {
	holdPaused = false;
	shiftHeld = false;
	updateNavIcons();
});

// ── Event handlers ──

document.getElementById("btn-play-pause").addEventListener("click", () => {
	ensureAudioContext(); // Unlock AudioContext synchronously during user gesture
	vscode.postMessage({ type: "play_pause" });
});

document.getElementById("btn-next").addEventListener("click", () => {
	if (shiftHeld || totalHighlights <= 1) {
		vscode.postMessage({ type: "next" });
	} else {
		vscode.postMessage({ type: "next_highlight" });
	}
});

document.getElementById("btn-prev").addEventListener("click", () => {
	if (shiftHeld || totalHighlights <= 1) {
		vscode.postMessage({ type: "prev" });
	} else {
		vscode.postMessage({ type: "prev_highlight" });
	}
});

document.getElementById("btn-restart").addEventListener("click", () => {
	vscode.postMessage({ type: "restart" });
});

document.getElementById("btn-save").addEventListener("click", () => {
	vscode.postMessage({ type: "save" });
});

// Saved-list filter (client-side "contains") + "Delete all" (confirmed plugin-side, then re-pushed).
document.getElementById("saved-filter").addEventListener("input", () => renderSavedList());
document.getElementById("btn-delete-all").addEventListener("click", () => {
	vscode.postMessage({ type: "delete_all_walkthroughs" });
});

document.getElementById("btn-close").addEventListener("click", () => {
	vscode.postMessage({ type: "close_walkthrough" });
});

// "Finish" ends the walkthrough: clears the tool window (back to idle) and the editor highlights.
// Same effect as the header's Close (X) icon, but a discoverable, explicitly-labelled action (Phase 7.5).
document.getElementById("btn-finish").addEventListener("click", () => {
	vscode.postMessage({ type: "close_walkthrough" });
});

// Loading / replace banners (Phase 7.5 #5). Each show/hide/collapse changes the docked-strip layout, so
// force a repaint afterwards (CEF otherwise leaves the old strip painted until a hover — Phase 7.5 #6).
document.getElementById("gen-dismiss").addEventListener("click", () => {
	document.getElementById("generation-banner").style.display = "none";
	forceFullRepaint();
});
document.getElementById("gen-collapse").addEventListener("click", () => {
	const banner = document.getElementById("generation-banner");
	const btn = document.getElementById("gen-collapse");
	const collapsed = banner.classList.toggle("collapsed");
	btn.textContent = collapsed ? "▶" : "▼"; // ▶ / ▼ (bigger glyphs — Phase 7.5 #15 fix 4)
	btn.title = collapsed ? "Expand" : "Collapse";
	forceFullRepaint();
});
document.getElementById("btn-undo").addEventListener("click", () => {
	document.getElementById("replace-banner").style.display = "none";
	forceFullRepaint();
	vscode.postMessage({ type: "restore_previous" });
});
document.getElementById("btn-keep").addEventListener("click", () => {
	document.getElementById("replace-banner").style.display = "none";
	forceFullRepaint();
	vscode.postMessage({ type: "dismiss_replace" });
});

// Manual "Refresh UI" (Phase 7.5 #6) — a discoverable escape hatch that forces a full repaint of the tool
// window, for the case where an automatic repaint didn't catch a CEF paint glitch.
document.getElementById("btn-refresh").addEventListener("click", () => {
	forceFullRepaint();
});

// Overview (segment explanation) collapse toggle (Phase 7.5 #11). The whole header is the click target;
// collapsing changes the layout, so force a repaint afterwards.
document.getElementById("explanation-toggle").addEventListener("click", () => {
	const box = document.getElementById("explanation-box");
	const btn = document.getElementById("btn-overview-collapse");
	const collapsed = box.classList.toggle("collapsed");
	btn.innerHTML = collapsed ? "&#9654;" : "&#9660;"; // ▶ / ▼ (bigger glyphs — Phase 7.5 #15 fix 4)
	btn.title = collapsed ? "Expand overview" : "Collapse overview";
	forceFullRepaint();
});

// Click a word in the transcript — or anywhere on the thin bar above it — to seek playback to that point
// in the current step (Phase 7.5 #13). Only takes effect once the step's audio is fully buffered
// (seekAllowed()); a click while it's still streaming is a harmless no-op.
document.getElementById("transcript-text").addEventListener("click", (e) => {
	const span = e.target && e.target.closest ? e.target.closest(".tw") : null;
	if (!span) return;
	const idx = transcriptWordSpans.indexOf(span);
	if (idx >= 0) seekToWord(idx);
});
document.querySelector(".step-progress-bar").addEventListener("click", (e) => {
	const rect = e.currentTarget.getBoundingClientRect();
	const f = rect.width > 0 ? (e.clientX - rect.left) / rect.width : 0;
	seekToFraction(Math.max(0, Math.min(1, f)));
});

document.getElementById("volume-slider").addEventListener("input", (e) => {
	volume = parseInt(e.target.value, 10) / 100;
	updateVolume();
	vscode.postMessage({ type: "volume_change", volume });
});

document.getElementById("btn-mute").addEventListener("click", () => {
	muted = !muted;
	document.getElementById("btn-mute").textContent = muted ? "\uD83D\uDD07" : "\uD83D\uDD0A";
	updateVolume();
	vscode.postMessage({ type: "mute_toggle" });
});

document.getElementById("voice-select").addEventListener("change", (e) => {
	vscode.postMessage({ type: "voice_change", voice: e.target.value });
	// Engine is per-voice: show the robotic row iff the newly-selected voice is robotic. The plugin
	// follows up with a `robotic_profile` message to sync the slider positions to that voice's profile.
	const opt = e.target.selectedOptions[0];
	setRoboticRowVisible(!!(opt && opt.dataset.robotic));
});

// Clicking the "file:lines" location under Now Playing jumps the editor to that code (Phase 7.5).
document.getElementById("segment-location").addEventListener("click", (e) => {
	e.preventDefault();
	const el = e.currentTarget;
	if (!el.dataset.file) return;
	vscode.postMessage({
		type: "reveal_location",
		file: el.dataset.file,
		start: parseInt(el.dataset.start || "1", 10),
		end: parseInt(el.dataset.end || "1", 10),
	});
});

// Speed buttons
document.querySelectorAll("#speed-buttons button").forEach((btn) => {
	btn.addEventListener("click", () => {
		const speed = parseFloat(btn.dataset.speed);
		document.querySelectorAll("#speed-buttons button").forEach((b) =>
			b.classList.remove("active"),
		);
		btn.classList.add("active");
		vscode.postMessage({ type: "speed_change", speed });
	});
});

// Robotic-voice (espeak) inline tuning sliders. Fire on `change` (pointer release), not `input`, so a
// drag doesn't restart narration on every pixel — mirrors the speed buttons' one-shot behaviour.
["espeak-pitch", "espeak-range", "espeak-wordgap"].forEach((id) => {
	const el = document.getElementById(id);
	if (!el) return;
	el.addEventListener("change", () => {
		vscode.postMessage({
			type: "espeak_tune",
			pitch: parseInt(document.getElementById("espeak-pitch").value, 10),
			range: parseInt(document.getElementById("espeak-range").value, 10),
			wordGap: parseInt(document.getElementById("espeak-wordgap").value, 10),
		});
	});
});

// "Advanced…" opens the native voice-editor dialog for the current robotic voice (plugin side).
const advBtn = document.getElementById("btn-advanced");
if (advBtn) {
	advBtn.addEventListener("click", () => {
		const vs = document.getElementById("voice-select");
		vscode.postMessage({ type: "advanced_open", voice: vs ? vs.value : "" });
	});
}

function setRoboticRowVisible(show) {
	const row = document.getElementById("robotic-controls");
	if (row) row.style.display = show ? "" : "none";
}

// Sync the three inline slider positions to a variant profile (from voice_options / robotic_profile).
function setRoboticSliders(pitch, range, wordGap) {
	const p = document.getElementById("espeak-pitch");
	const r = document.getElementById("espeak-range");
	const g = document.getElementById("espeak-wordgap");
	if (p && typeof pitch === "number") p.value = pitch;
	if (r && typeof range === "number") r.value = range;
	if (g && typeof wordGap === "number") g.value = wordGap;
}

// Show/hide the robotic tuning row and sync slider positions with the persisted profile.
function updateRoboticControls(robotic, pitch, range, wordGap) {
	setRoboticRowVisible(!!robotic);
	setRoboticSliders(pitch, range, wordGap);
}

// ── Message handler from extension ──

window.addEventListener("message", (event) => {
	const msg = event.data;

	switch (msg.type) {
		case "generation_status": {
			const banner = document.getElementById("generation-banner");
			const spinner = banner.querySelector(".gen-spinner");
			const title = document.getElementById("gen-title");
			const activity = document.getElementById("gen-activity");
			const dismiss = document.getElementById("gen-dismiss");
			const collapse = document.getElementById("gen-collapse");
			// The banner shows/hides only on a phase CHANGE (not on every progress tick, where the height is
			// unchanged); repaint just then so the docked strip doesn't ghost, without flickering mid-generation.
			const phaseChanged = msg.phase !== genLastPhase;
			if (msg.phase === "generating") {
				// Auto-expand when a fresh generation starts (not on every progress tick).
				if (genLastPhase !== "generating") {
					banner.classList.remove("collapsed");
					collapse.textContent = "▼";
					collapse.title = "Collapse";
				}
				banner.style.display = "";
				banner.classList.remove("failed");
				spinner.style.display = "";
				collapse.style.display = "";
				dismiss.style.display = "none";
				title.textContent = msg.displayName
					? `Generating: ${msg.displayName}`
					: "Generating walkthrough…";
				activity.textContent = msg.activity || "";
			} else if (msg.phase === "failed") {
				banner.style.display = "";
				banner.classList.add("failed");
				banner.classList.remove("collapsed"); // always show the failure detail
				spinner.style.display = "none";
				collapse.style.display = "none";
				dismiss.style.display = "";
				title.textContent = "Generation failed";
				activity.textContent = msg.message || "";
			} else {
				// idle
				banner.style.display = "none";
			}
			genLastPhase = msg.phase;
			if (phaseChanged) forceFullRepaint();
			break;
		}

		case "plan_replaced": {
			const rb = document.getElementById("replace-banner");
			const prev = document.getElementById("replace-prev");
			prev.textContent = msg.previousTitle ? `Previous: ${msg.previousTitle}` : "";
			rb.style.display = "";
			forceFullRepaint();
			break;
		}

		case "plan_replaced_clear":
			document.getElementById("replace-banner").style.display = "none";
			forceFullRepaint();
			break;

		case "theme": {
			// Live IDE theme change (Phase 7.5 #10): replace the injected :root variables so the panel's
			// colours track the new light/dark scheme without a reload. This <style> is the last in <head>,
			// so its values win the cascade over sidebar.css' fallback :root.
			let styleEl = document.getElementById("ae-theme-vars");
			if (!styleEl) {
				styleEl = document.createElement("style");
				styleEl.id = "ae-theme-vars";
				document.head.appendChild(styleEl);
			}
			styleEl.textContent = msg.css;
			forceFullRepaint();
			break;
		}

		case "settings": {
			// Restore the persisted voice + speed into the controls on (re)load, so the UI matches what
			// the audio engine actually uses (Phase 7.5 — the combobox reset to the first voice before).
			if (msg.voice) {
				const vs = document.getElementById("voice-select");
				if ([...vs.options].some((o) => o.value === msg.voice)) vs.value = msg.voice;
			}
			if (msg.speedPercent) {
				const speed = msg.speedPercent / 100;
				const btns = document.querySelectorAll("#speed-buttons button");
				let matched = false;
				btns.forEach((b) => { if (parseFloat(b.dataset.speed) === speed) matched = true; });
				if (matched) {
					btns.forEach((b) => b.classList.toggle("active", parseFloat(b.dataset.speed) === speed));
				}
			}
			// Show WHERE walkthroughs are saved on hover — on the header Save button and the idle view's
			// "Saved Walkthroughs" label (Phase 7.5 #14 task 5). Only override the static titles when the
			// plugin actually sent a directory (i.e. this project has a workspace root).
			if (msg.walkthroughsDir) {
				const save = document.getElementById("btn-save");
				if (save) setTip(save, `Save walkthrough in ${msg.walkthroughsDir}`);
				const savedTitle = document.getElementById("saved-list-title");
				if (savedTitle) setTip(savedTitle, `Walkthroughs are saved in ${msg.walkthroughsDir}`);
			}
			break;
		}

		case "voice_options": {
			// Repopulate the voice combo for the current walkthrough's language (Phase L3).
			rebuildVoiceOptions(msg.voices, msg.selectedVoice);
			// Show the robotic (espeak) sliders only when the selected voice is robotic, seeded from its profile.
			updateRoboticControls(msg.robotic, msg.espeakPitch, msg.espeakRange, msg.espeakWordGap);
			break;
		}

		case "robotic_profile": {
			// Reseed the inline sliders after a voice switch or a dialog Save.
			setRoboticSliders(msg.espeakPitch, msg.espeakRange, msg.espeakWordGap);
			break;
		}

		case "server_loading": {
			const btn = document.getElementById("btn-play-pause");
			if (msg.loading) {
				btn.classList.add("loading");
				btn.setAttribute("aria-busy", "true");
				btn.setAttribute("aria-disabled", "true");
			} else {
				btn.classList.remove("loading");
				btn.removeAttribute("aria-busy");
				btn.removeAttribute("aria-disabled");
			}
			break;
		}

		case "highlight_advance": {
			currentHighlightIndex = msg.highlightIndex;
			totalHighlights = msg.totalHighlights;
			renderHighlightProgress();
			const hlIdx = state.segments.findIndex((s) => s.id === state.currentSegment);
			if (hlIdx !== -1) renderOutline(hlIdx);
			// New step → reset the read-along audio model (clock, retained buffers, sentence map); the next
			// chunks re-populate it, and renderTranscript rebuilds the word spans (Phase 7.5 #12/#13).
			resetStepAudioModel();
			lastSpokenWord = -1;
			// The explanation box keeps the segment-level overview (set in render()); the transcript box
			// tracks the current step's spoken words. This is the text that matches the audio.
			renderTranscript(true);
			break;
		}

		case "update": {
			const prevSegment = state.currentSegment;
			state = {
				title: msg.title,
				segments: msg.segments,
				currentSegment: msg.currentSegment,
				status: msg.status,
				language: msg.language || "en",
				languageName: msg.languageName || "English",
				autonym: msg.autonym || "English",
			};
			// Only reset highlight state when the segment actually changed
			if (prevSegment !== msg.currentSegment) {
				currentHighlightIndex = 0;
				totalHighlights = 0;
			}
			// If something else resumed playback while spacebar was held, clear the flag
			if (state.status !== "paused") {
				holdPaused = false;
			}
			render();
			renderHighlightProgress();
			break;
		}

		case "audio_chunk": {
			const playBtn = document.getElementById("btn-play-pause");
			playBtn.classList.remove("loading");
			playBtn.removeAttribute("aria-busy");
			playBtn.removeAttribute("aria-disabled");
			playAudioChunk(msg.data, msg.sampleRate, msg.si || 0, msg.sw || 1);
			break;
		}

		case "audio_end":
			onAudioEnd();
			break;

		case "audio_stop":
			stopAudio();
			break;

		case "audio_suspend":
			suspendAudio();
			break;

		case "audio_resume":
			resumeAudio();
			break;

		case "saved_list": {
			savedWalkthroughs = Array.isArray(msg.walkthroughs) ? msg.walkthroughs : [];
			renderSavedList();
			break;
		}
	}
});

// Repaint-on-mutation safety net (Phase 7.5 #6). CEF doesn't reliably repaint DOM changes without an input
// event, so any mutation (outline markers flipping on step-advance, progress bar, transcript, banners, …)
// schedules a coalesced native repaint. Mutations here are bursty/event-driven — a step advance, a status
// change — not per-audio-frame (playing chunks mutate nothing after the first), so the overhead is minimal.
// This is a superset safety net; the explicit forceFullRepaint() calls remain for the heavier layout ghosts.
const domRepaintObserver = new MutationObserver(() => scheduleNativeRepaint());
domRepaintObserver.observe(document.body, {
	subtree: true,
	childList: true,
	characterData: true,
	attributes: true,
});

// ── Custom hover tooltips (Phase 7.5 #15 fix 3) ────────────────────────────────────────────────────
// JCEF's offscreen renderer never paints the native `title` tooltip, so every title="…" in the markup
// (Save, Refresh UI, Close, Mute, play controls, collapse buttons, the saved-dir hints) was invisible.
// This delegated handler reads the hovered element's title and shows a positioned box instead. It reads
// `title` FIRST so dynamically-updated titles (play↔pause, Expand↔Collapse, the walkthroughsDir save
// tip) always win, then migrates the value into data-ae-tip and strips `title` — that neutralises the
// native tooltip if a platform ever does render it, while a later `el.title = …` simply re-arms a fresh
// read. Element mutations here are picked up by domRepaintObserver, which also gets CEF to paint the box.
let aeTooltipEl = null;
let aeTipOwner = null;
function ensureTooltipEl() {
	if (!aeTooltipEl) {
		aeTooltipEl = document.createElement("div");
		aeTooltipEl.id = "ae-tooltip";
		aeTooltipEl.setAttribute("role", "tooltip");
		document.body.appendChild(aeTooltipEl);
	}
	return aeTooltipEl;
}
// Set a tooltip programmatically (used for any tip we generate in JS rather than the markup).
function setTip(el, text) {
	if (!el) return;
	el.setAttribute("data-ae-tip", text);
	el.removeAttribute("title");
	if (aeTipOwner === el && aeTooltipEl) aeTooltipEl.textContent = text; // refresh if it's showing now
}
function showTooltip(target) {
	let text = target.getAttribute("title");
	if (text != null) {
		target.setAttribute("data-ae-tip", text); // cache + neutralise the (non-rendering) native tip
		target.removeAttribute("title");
	} else {
		text = target.getAttribute("data-ae-tip");
	}
	if (!text) return;
	const tip = ensureTooltipEl();
	tip.textContent = text;
	if (aeTipOwner === target && tip.classList.contains("visible")) return; // same control, text refreshed
	aeTipOwner = target;
	tip.style.display = "block";
	const r = target.getBoundingClientRect();
	const tr = tip.getBoundingClientRect();
	let left = r.left + r.width / 2 - tr.width / 2;
	left = Math.max(6, Math.min(left, window.innerWidth - tr.width - 6));
	let top = r.bottom + 6;
	if (top + tr.height > window.innerHeight - 6) top = r.top - tr.height - 6; // flip above if no room below
	tip.style.left = Math.round(left) + "px";
	tip.style.top = Math.round(Math.max(6, top)) + "px";
	tip.classList.add("visible");
}
function hideTooltip() {
	aeTipOwner = null;
	if (aeTooltipEl) {
		aeTooltipEl.classList.remove("visible");
		aeTooltipEl.style.display = "none";
	}
}
document.addEventListener("mouseover", (e) => {
	const t = e.target && e.target.closest ? e.target.closest("[title],[data-ae-tip]") : null;
	if (t) showTooltip(t);
});
document.addEventListener("mouseout", (e) => {
	const from = e.target && e.target.closest ? e.target.closest("[title],[data-ae-tip]") : null;
	if (!from) return;
	const rel = e.relatedTarget;
	// Don't hide when the pointer just moved onto a child of the same control (e.g. button → its <svg>).
	if (rel && rel.closest && rel.closest("[title],[data-ae-tip]") === from) return;
	hideTooltip();
});

// Initial render
render();
