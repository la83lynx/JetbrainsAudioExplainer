// @ts-check

/** @type {ReturnType<typeof acquireVsCodeApi>} */
const vscode = acquireVsCodeApi();

// ── State ──

let state = {
	title: "",
	/**
	 * The outline's row model, as `PlanOutline` builds it: `{scenes:[{id,label,groups:[{frame,title,
	 * steps:[{index,label}]}]}], scened, steps}`. Content only — the position is `currentStep`, kept apart
	 * so navigation never rebuilds a row (which would destroy its click handler).
	 */
	outline: { scenes: [], scened: false, steps: 0 },
	/** The plan step showing, or -1. **The whole position model**: one flat index. */
	currentStep: -1,
	stepCount: 0,
	/**
	 * **Which saved walkthrough this is** — the file stem Save writes without asking — or
	 * null when it was never saved, and Save asks for a name.
	 */
	savedAs: null,
	/** The plan holds content no file does: it was never saved. Only the tooltip reads it. */
	unsaved: false,
	/** What Now Playing shows for that step (a `StepView`), or null: title, explanation, ttsText, location. */
	now: null,
	status: "idle",
	language: "en",
	languageName: "English",
	autonym: "English",
};

/** Where walkthroughs are saved (from the plugin's `settings` message), for the Save tooltip; "" until known. */
let walkthroughsDir = "";

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
/** Last generation phase seen — used to auto-expand the banner on a fresh generation. */
let genLastPhase = "idle";
/** True when audio_end arrived but chunks are still pending (AudioContext suspended) */
let deferredPlaybackComplete = false;
/** Guard: true while waitForActiveSourcesToFinish has a pending wrapper or sent playback_complete */
let playbackCompleteWired = false;
/** True once `playback_started` was sent for the current step — one per step, cleared by resetStepAudioModel. */
let playbackStartedSent = false;

/** @type {{base64: string, sampleRate: number}[]} */
let pendingChunks = [];

// ── Read-along transcript progress + seek ──
// Kokoro exposes no per-word timestamps, but the TTS server tags every wire chunk with its sentence index
// (si) and that sentence's word count (sw). Per step we keep: the absolute AudioContext time the first
// buffer started (stepStartClock); every decoded buffer with its cumulative audio offset (stepBuffers, so
// a click can seek); and a per-sentence map (stepSentences: audio start/duration + word range). The
// read-along cursor maps the Web Audio playhead onto words *per sentence*, so it tracks the audio without
// drifting (dividing by the step's total duration would not: that total keeps growing as frames stream in).
// A suspended AudioContext freezes currentTime, so the cursor freezes exactly where playback paused.
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
/**
 * Silence this step's schedule has had inserted into it, in seconds. Two clocks run here and they are not
 * the same clock: `stepAudioDuration`/`tStart` count *audio content*, while `currentTime - stepStartClock`
 * counts *wall time since the first buffer started*. They agree only while playback never starves. When a
 * chunk arrives after the scheduled audio has already drained, playAudioChunk clamps `nextPlayTime` forward
 * and a silent gap goes into the timeline that the content clock never sees — so from that moment the wall
 * clock runs permanently ahead and the cursor leads the voice for the rest of the step. Accumulated here
 * and subtracted by stepElapsed(), which is the one place elapsed is defined.
 */
let stepClockSkew = 0;
/** True once audio_end arrived for the current step (all chunks generated) — a precondition for seeking. */
let stepAudioEnded = false;
/**
 * Each sentence as the server actually spoke it, indexed by si. Empty against a tts_server.py older than
 * the `stext` field, which is a supported state: the installed copy refreshes only on a VERSION difference
 * and is editable on disk.
 */
let sentenceTexts = [];
/** Beats already fired this step, by their index in the step's `beats` array. */
let firedBeats = new Set();
/** Per-beat resolved sentence: >=0 fires there, -2 suppressed. Absent = not resolvable yet, so retried. */
let beatSentence = [];

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
				playAudioChunk(chunk.base64, chunk.sampleRate, chunk.si, chunk.sw, chunk.stext);
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


/**
 * Where the next buffer has to start, and how much silence that inserts. A chunk that arrives after
 * everything scheduled has already drained cannot start in the past, so it starts *now* and the difference
 * becomes a gap the audio-content clock never sees — the drift [stepClockSkew] exists to cancel.
 *
 * [anchored] is false for a step's very first chunk, where `nextAt` is still 0 and this clamp is what
 * *anchors* the step rather than a starvation; counting that as skew would offset every step by its own
 * start time.
 *
 * Split out of [playAudioChunk] so the arithmetic holding the two clocks together can be asserted without
 * a Web Audio context (`probes/ClockProbe.js`).
 */
function scheduleSlot(nextAt, now, anchored) {
	if (nextAt >= now) return { at: nextAt, gap: 0 };
	return { at: now, gap: anchored ? now - nextAt : 0 };
}

function playAudioChunk(base64Data, sampleRate, si, sw, stext) {
	ensureAudioContext();

	// If AudioContext is still suspended (no user gesture yet), queue the chunk
	if (audioCtx.state === "suspended") {
		pendingChunks.push({ base64: base64Data, sampleRate, si, sw, stext });
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

	const slot = scheduleSlot(nextPlayTime, audioCtx.currentTime, stepStartClock !== null);
	stepClockSkew += slot.gap;
	nextPlayTime = slot.at;
	source.start(nextPlayTime);
	// Read-along timeline: the first scheduled buffer marks this step's audio start.
	if (stepStartClock === null) stepStartClock = nextPlayTime;
	nextPlayTime += buffer.duration;
	recordChunk(buffer, si, sw, stext);

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
 * Record a decoded chunk into the step's buffer list + per-sentence map. Buffers are
 * retained (with their cumulative audio offset) so a click can seek playback; the sentence map (audio
 * start + duration + word range, from the wire's si/sw) is what anchors the read-along cursor per sentence.
 * [stext] is the sentence as spoken, kept so a beat can be matched against real output rather than against
 * anyone's count of sentences (see sentenceForBeat).
 */
function recordChunk(buffer, si, sw, stext) {
	const dur = buffer.duration;
	if (stext && !sentenceTexts[si]) sentenceTexts[si] = stext;
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

/**
 * Show the exact narration text (a highlight's ttsText) for the current step — i.e. the transcript
 * of what the audio says. Driven from local state, so it works whether or not audio is playing.
 *
 * The text is rendered as per-word spans so the read-along cursor can highlight one word at
 * a time. A same-text re-render (e.g. a play/pause status update) is a no-op so it never resets the cursor.
 */
function renderTranscript(animate) {
	const box = document.getElementById("transcript-box");
	const el = document.getElementById("transcript-text");
	if (!box || !el) return;
	const text = state.now && state.now.ttsText ? state.now.ttsText : "";
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
 * one continuous progress bar rather than separate word chips. Only leading/orphan
 * whitespace (with no preceding word) stays a plain text node. container white-space:pre-wrap preserves the
 * original spacing either way.
 */
function setTranscriptWords(el, text) {
	el.replaceChildren();
	transcriptWordSpans = [];
	// Japanese/Chinese have no spaces, so whitespace tokenisation would collapse the whole narration into
	// ONE span (the read-along cursor would light the entire block at once). For CJK we tokenise per
	// SENTENCE instead — matching the TTS server, which streams one audio chunk per CJK sentence (sw=1), so
	// the cursor advances a sentence at a time.
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
	stepClockSkew = 0;
	stepAudioEnded = false;
	playbackStartedSent = false;
	sentenceTexts = [];
	firedBeats = new Set();
	beatSentence = [];
}


// ── Beats: a pulse timed to a point in the narration ──

/**
 * The one matching rule — trim, collapse whitespace, lowercase. **`Beat.normalize` in Protocol.kt must do
 * the same thing.** That side warns an author at load that a phrase is unsayable; this side decides whether
 * the beat ever fires, and a phrase accepted there but unmatched here is the one failure with no symptom.
 */
function beatKey(s) {
	return String(s || "").trim().toLowerCase().replace(/\s+/g, " ");
}

/** The transcript word index where [needle] starts, or -1. Used only when the server sends no `stext`. */
function wordIndexOfPhrase(needle) {
	const starts = [];
	let acc = "";
	for (const span of transcriptWordSpans) {
		if (acc) acc += " ";
		starts.push(acc.length);
		acc += beatKey(span.textContent);
	}
	const at = acc.indexOf(needle);
	if (at < 0) return -1;
	let w = 0;
	while (w + 1 < starts.length && starts[w + 1] <= at) w++;
	return w;
}

/**
 * Which sentence a beat belongs to, or -1 while that cannot be answered yet (sentences stream in, so an
 * unresolved beat is retried every tick rather than written off).
 *
 * Matched against the text the server says it spoke, because "sentence 3" is not a shared idea: the author
 * counts prose, the splitter counts ASCII enders and a 320-char cap, and this file tokenises the raw
 * ttsText. A phrase needs no agreement between the three.
 */
function sentenceForBeat(beat) {
	const needle = beatKey(beat.say);
	if (!needle) return -1;
	const haveText = sentenceTexts.some((t) => !!t);
	if (haveText) {
		for (let si = 0; si < sentenceTexts.length; si++) {
			if (sentenceTexts[si] && beatKey(sentenceTexts[si]).includes(needle)) return si;
		}
		// A phrase quoted across a sentence boundary belongs to the sentence it STARTS in: walk it back a
		// word at a time and look for a sentence ending with that head. Only once every sentence is in —
		// mid-stream a short head would happily match some earlier sentence that merely ends the same way.
		const parts = needle.split(" ");
		if (stepAudioEnded && parts.length > 2) {
			for (let take = parts.length - 1; take >= 2; take--) {
				const head = parts.slice(0, take).join(" ");
				for (let si = 0; si < sentenceTexts.length; si++) {
					if (sentenceTexts[si] && beatKey(sentenceTexts[si]).endsWith(head)) return si;
				}
			}
		}
		return -1;
	}
	// No `stext` on the wire: locate the phrase among the transcript's own words and ask which sentence
	// owns that word. Same answer whenever the two tokenisations agree — which is the normal case, and the
	// disagreement is exactly what `stext` was added to remove.
	const wi = wordIndexOfPhrase(needle);
	if (wi < 0) return -1;
	const owner = sentenceForWord(wi);
	return owner ? stepSentences.indexOf(owner) : -1;
}

/**
 * Fire every beat whose sentence has begun, once each. Called from the rAF tick with the same
 * audio-content [elapsed] the cursor uses, so a beat and the highlighted word cannot disagree.
 *
 * **At the sentence's start, not the phrase's.** No engine here reports word timings, so a word's position
 * inside its sentence is interpolated — about a second of error against a 700 ms pulse. A sentence's tStart
 * is a sum of real decoded buffer durations.
 */
function fireDueBeats(elapsed) {
	const beats = (state.now && state.now.beats) || [];
	if (beats.length === 0 || firedBeats.size === beats.length) return;
	for (let i = 0; i < beats.length; i++) {
		if (firedBeats.has(i) || beatSentence[i] === -2) continue;
		// A beat with no `say` fires the instant the audio starts, and the plugin already has a measured
		// signal for that moment (`playback_started`). Not this loop's job — retired so it is
		// not re-resolved every frame for the rest of the step.
		if (!beats[i].say) { beatSentence[i] = -2; continue; }
		let si = beatSentence[i];
		if (si === undefined) {
			si = sentenceForBeat(beats[i]);
			if (si < 0) continue;
			// One pulse per sentence. A second beat on the same sentence would fire in the same tick and
			// cancel the first (startTokenFlash self-cancels), so it is dropped instead of made to
			// flicker — its absence from the plugin's beat log is the signal.
			if (beatSentence.indexOf(si) !== -1) { beatSentence[i] = -2; continue; }
			beatSentence[i] = si;
		}
		const sentence = stepSentences[si];
		if (!sentence || elapsed < sentence.tStart) continue;
		firedBeats.add(i);
		vscode.postMessage({ type: "beat", index: state.currentStep, beat: i });
	}
}

/**
 * Audio-content seconds elapsed in this step: wall clock since the first buffer was scheduled, less the
 * silence [stepClockSkew] records. This is the coordinate system `stepSentences[].tStart` and
 * `stepBuffers[].offset` are already in, so everything that maps a time onto the transcript — the cursor,
 * a seek, a beat — reads the same number.
 */
function stepElapsed() {
	return audioCtx.currentTime - stepStartClock - stepClockSkew;
}

/** Start the rAF loop driving the read-along cursor, if audio is playing and it isn't already running. */
function ensureProgressLoop() {
	if (!progressRaf && audioPlaying) progressRaf = requestAnimationFrame(updateStepProgress);
}

/** rAF tick: map the Web Audio playhead onto the transcript words + step progress bar. */
function updateStepProgress() {
	progressRaf = 0;
	if (audioCtx && stepStartClock !== null) {
		// The playhead has reached this step's first scheduled buffer, so the user is NOW hearing it. Tell the
		// plugin once per step: the editor's token flash keys off this, because "audio requested" is seconds
		// too early. Reported from here rather than when the buffer is scheduled so a
		// non-zero start offset, or a step whose first chunk lands mid-queue, is still honoured.
		if (!playbackStartedSent && audioCtx.currentTime >= stepStartClock) {
			playbackStartedSent = true;
			vscode.postMessage({ type: "playback_started", index: state.currentStep });
		}
		const elapsed = stepElapsed();
		applyReadAlong(elapsed);
		fireDueBeats(elapsed);
	}
	if (audioPlaying) progressRaf = requestAnimationFrame(updateStepProgress);
}

/**
 * Paint the read-along "fill" for [elapsed] audio-seconds since this step's audio started.
 * Words up to the playhead keep the "spoken" wash; the word at the playhead adds "speaking".
 * Monotonic during playback (the currently-streaming sentence's duration can still grow, which would
 * otherwise nudge the cursor back a word), but a seek resets lastSpokenWord so the fill may jump backward.
 * Mutates the DOM only when the word index changes, so a burst of rAF frames collapses into a few
 * mutations/second — which the MutationObserver turns into the native repaints CEF needs.
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

// ── Click-to-seek within the current step ──

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
 * time. If currently paused, also asks the controller to resume so it plays from there.
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
 * Reschedule the current step's buffered audio to start from audio-second [t]. Stops the
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

	// Re-anchor the read-along clock so elapsed == target right now; allow a backward jump. The skew goes
	// with it: the re-queue above is contiguous, and seeking needs the whole step buffered
	// (seekAllowed), so no further chunk can arrive to starve this schedule.
	stepStartClock = audioCtx.currentTime - target;
	stepClockSkew = 0;
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

/** Force a full repaint only when the visible top-level view actually changed (idle ↔ active). */
function maybeRepaintOnViewChange(view) {
	if (view !== lastRenderedView) {
		lastRenderedView = view;
		forceFullRepaint();
	}
}

/**
 * The Save split button's state: a tooltip that says which file Save writes — so the
 * one-click, no-dialog Save of a saved walkthrough is never a surprise. Called from render() and when the save directory arrives.
 */
function renderSaveButton() {
	const save = document.getElementById("btn-save");
	const caret = document.getElementById("btn-save-menu");
	if (!save || !caret) return;
	let tip;
	if (state.savedAs) {
		tip = state.unsaved
			? `Save changes to “${state.savedAs}”`
			: `Saved as “${state.savedAs}” — Save writes it again`;
	} else {
		tip = walkthroughsDir ? `Save walkthrough in ${walkthroughsDir}` : "Save Walkthrough";
	}
	setTip(save, tip);
	setTip(caret, "More save options");
}

function render() {
	const idleView = document.getElementById("idle-view");
	const activeView = document.getElementById("active-view");

	if (state.status === "idle") {
		idleView.style.display = "";
		activeView.style.display = "none";
		vscode.postMessage({ type: "request_saved_list" });
		maybeRepaintOnViewChange("idle");
		return;
	}

	if (state.status === "stopped") {
		idleView.style.display = "";
		activeView.style.display = "none";
		vscode.postMessage({ type: "request_saved_list" });
		maybeRepaintOnViewChange("idle");
		return;
	}

	idleView.style.display = "none";
	activeView.style.display = "";

	// Title + language badge
	document.getElementById("walkthrough-title").textContent = state.title;
	renderLanguageBadge();
	renderSaveButton();

	// Now playing — the frame this step is about, and the one place a file and a line still belong. The
	// outline says *which step*; this says *where the code is*, and it is clickable.
	const now = state.now;

	if (now) {
		document.getElementById("segment-title").textContent = now.title;

		const loc = document.getElementById("segment-location");
		const fileName = (now.file || "").split(/[\\/]/).pop();
		loc.textContent = now.start > 0 ? `${fileName}:${now.start}-${now.end}` : fileName;
		loc.dataset.file = now.file;
		loc.dataset.start = String(now.start);
		loc.dataset.end = String(now.end);
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
	if (now) {
		const explEl = document.getElementById("explanation-text");
		explEl.classList.add("fade-out");
		setTimeout(() => {
			explEl.innerHTML = simpleMarkdown(now.explanation);
			explEl.classList.remove("fade-out");
		}, 150);
	}

	// Outline
	renderOutline();

	// Transcript of the current step (matches the audio narration)
	renderTranscript(false);

	// Switching into the active view is a layout transition — repaint so nothing ghosts (see forceFullRepaint).
	maybeRepaintOnViewChange("active");
}

/**
 * Show the walkthrough's language as a small badge next to the title when it isn't English.
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
 * Rebuild the voice <select> from the language-specific voice list pushed by the plugin,
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
	syncVoiceTitle();
}

/** The closed select clips a long voice name to the panel width; its tooltip shows it whole. */
function syncVoiceTitle() {
	const vs = document.getElementById("voice-select");
	const opt = vs && vs.selectedOptions[0];
	if (vs) vs.title = opt ? opt.textContent : "";
}

/** Global progress — `step+1 / stepCount`. */
function computeGlobalProgress() {
	if (!state.stepCount) return { current: 0, total: 0 };
	return { current: Math.max(0, state.currentStep) + 1, total: state.stepCount };
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
 * Signature of the last full outline build. **Content-sensitive and position-free**, which is the whole
 * rule: a rebuild replaces every row, and replacing a row destroys its click handler — so navigation must
 * never trigger one. It captures each scene's id and label, each group's frame and title, and each step's
 * index and label; it does NOT capture which step is current.
 *
 * `scened` is in the signature because turning the scene row on or off changes the DOM shape, and a plan
 * that gained a scene would otherwise keep the old flat list. Labels are in it because a shape-only
 * signature collides when a new plan has the same shape as the previous one, leaving stale labels.
 *
 * renderOutline (a) rebuilds from scratch whenever the content changed OR the live DOM drifted out of sync
 * (outlineDomInSync - a hard invariant that can never keep a stale row), and (b) forces a full repaint of
 * the scroll region after a rebuild: a shorter outline otherwise leaves "ghost" rows of the previous,
 * longer one, because CEF fails to repaint the region the removed rows vacated.
 */
let outlineSignature = "";

/** Content signature of the current outline (see outlineSignature). */
function computeOutlineSignature() {
	const o = state.outline || { scenes: [] };
	return (o.scened ? "S|" : "F|") + (o.scenes || [])
		.map((sc) => {
			const groups = (sc.groups || [])
				.map((g) => {
					const steps = (g.steps || []).map((st) => `${st.index}~${st.label}`).join(",");
					return `${g.frame}\u241f${g.title}\u241f${steps}`;
				})
				.join("\u2021");
			return `${sc.id}\u241e${sc.label}\u241e${groups}`;
		})
		.join("\u241f");
}

/**
 * Hard invariant: does the live DOM match the outline exactly - one row per scene (when scened), per group
 * and per step? If not, a stale row survived a previous render and we MUST rebuild rather than reconcile
 * marker classes onto a mismatched tree.
 */
function outlineDomInSync(list) {
	const o = state.outline || { scenes: [] };
	const scenes = o.scenes || [];
	if (o.scened) {
		if (list.children.length !== scenes.length) return false;
		for (let i = 0; i < scenes.length; i++) {
			const groups = list.children[i].querySelectorAll(".outline-segment");
			if (groups.length !== (scenes[i].groups || []).length) return false;
		}
	} else {
		const flat = scenes.flatMap((sc) => sc.groups || []);
		if (list.children.length !== flat.length) return false;
	}
	const wantSteps = scenes.reduce(
		(n, sc) => n + (sc.groups || []).reduce((m, g) => m + (g.steps || []).length, 0), 0);
	return list.querySelectorAll(".outline-highlight").length === wantSteps;
}

/**
 * Force CEF to repaint the ENTIRE tool window. JCEF/CEF frequently leaves stale "ghost" pixels after a
 * layout change (a banner appearing/disappearing, a view switch, the outline shrinking) - the DOM/layout
 * is correct but the old pixels linger until some input event (a hover) forces a repaint. That's the
 * "crooked layout / loading strip won't hide until I mouse over it" symptom.
 *
 * Two independent levers, because CEF's OSR invalidation is flaky and render-mode-dependent:
 *  1. Toggle `body`'s display off/on with forced reflows - invalidates the whole surface at the DOM layer.
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
	void body.offsetHeight; // flush the restore -> forces a fresh paint of the whole surface
	if (scroller) scroller.scrollTop = prev;
	// Native belt-and-suspenders: let the plugin force a CEF-level repaint too.
	vscode.postMessage({ type: "repaint" });
}

/**
 * Lightweight, coalesced native repaint. JCEF/CEF also fails to repaint on *non-layout* DOM changes without
 * an input event - e.g. the outline's step markers flipping to a tick on audio step-advance stayed stale
 * until you moused over them (a mouse event is what makes CEF paint). A single MutationObserver (set up
 * below) calls this on any DOM change; it asks the plugin for a native `CefBrowser.invalidate()` - the
 * programmatic equivalent of that hover. No `body` display toggle here (the change needs a *repaint*, not a
 * relayout), so it never disturbs in-flight CSS fades. Debounced, so a burst of mutations (one step advance
 * touches the progress bar, the outline markers and the transcript) collapses into a single repaint.
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

/** One step row. Marker + label, and **nothing else** - see buildOutline. */
function buildStepRow(st) {
	const li = document.createElement("li");
	li.className = "outline-highlight";
	li.dataset.step = String(st.index);

	const marker = document.createElement("span");
	marker.className = "hl-marker";

	const label = document.createElement("span");
	label.className = "hl-label";
	label.textContent = st.label;

	li.appendChild(marker);
	li.appendChild(label);
	li.addEventListener("pointerdown", (e) => {
		e.preventDefault();
		vscode.postMessage({ type: "goto_step", index: st.index });
	});
	return li;
}

/** One frame group: a clickable header over its step rows. */
function buildGroupRow(group, ordinal) {
	const li = document.createElement("li");
	li.className = "outline-segment";

	const header = document.createElement("div");
	header.className = "outline-segment-header";

	const marker = document.createElement("span");
	marker.className = "marker";

	const text = document.createElement("span");
	text.className = "segment-label";
	text.textContent = `${ordinal}. ${group.title}`;

	const progress = document.createElement("span");
	progress.className = "segment-progress";

	header.appendChild(marker);
	header.appendChild(text);
	header.appendChild(progress);
	// A group header is its own first step - with a flat position there is no separate "segment" to go to.
	const first = (group.steps || [])[0];
	if (first) {
		header.addEventListener("pointerdown", (e) => {
			e.preventDefault();
			vscode.postMessage({ type: "goto_step", index: first.index });
		});
	}
	li.appendChild(header);

	const stepList = document.createElement("ul");
	stepList.className = "outline-highlights";
	for (const st of group.steps || []) stepList.appendChild(buildStepRow(st));
	li.appendChild(stepList);
	return li;
}

/**
 * **Build the outline: scene -> frame -> step**, with the scene level drawn only when there is more than
 * one of them.
 *
 * - **The scene row is conditional.** Most plans are single-scene, and a lone "Scene 1" header over the
 *   whole list is an indent that separates nothing. A plan that really has scenes gains the level where it
 *   carries information: a scene boundary is where the tree gets rebuilt.
 * - **A step row is a marker and a label.** No file or line range: those live under Now Playing, where
 *   they are a clickable location rather than decoration on every row.
 */
function buildOutline(list) {
	const o = state.outline || { scenes: [] };
	let ordinal = 0;
	if (!o.scened) {
		for (const scene of o.scenes || []) {
			for (const group of scene.groups || []) list.appendChild(buildGroupRow(group, ++ordinal));
		}
		return;
	}
	for (const scene of o.scenes || []) {
		const li = document.createElement("li");
		li.className = "outline-scene";

		const header = document.createElement("div");
		header.className = "outline-scene-header";

		const marker = document.createElement("span");
		marker.className = "marker";

		const text = document.createElement("span");
		text.className = "scene-label";
		text.textContent = scene.label;

		const progress = document.createElement("span");
		progress.className = "segment-progress";

		header.appendChild(marker);
		header.appendChild(text);
		header.appendChild(progress);
		const first = ((scene.groups || [])[0] || { steps: [] }).steps[0];
		if (first) {
			header.addEventListener("pointerdown", (e) => {
				e.preventDefault();
				vscode.postMessage({ type: "goto_step", index: first.index });
			});
		}
		li.appendChild(header);

		const groupList = document.createElement("ul");
		groupList.className = "outline-groups";
		for (const group of scene.groups || []) groupList.appendChild(buildGroupRow(group, ++ordinal));
		li.appendChild(groupList);
		list.appendChild(li);
	}
}

/** done / current / upcoming, for one row, from a step index range against the position. */
function markerFor(el, markerEl, firstIndex, lastIndex, cls) {
	const cur = state.currentStep;
	const isCurrent = cur >= firstIndex && cur <= lastIndex;
	const isDone = cur > lastIndex;
	el.className = cls + (isCurrent ? " current" : isDone ? " completed" : "");
	if (markerEl) markerEl.textContent = isDone ? "\u2713" : isCurrent ? "\u25b6" : "\u25cb";
}

/**
 * Paint the outline's state onto the existing rows, rebuilding first only when the *content* changed.
 *
 * Position is applied by walking `data-step`, so every row's own index decides its marker - no arithmetic
 * over segment offsets.
 */
function renderOutline() {
	const list = document.getElementById("outline-list");

	const signature = computeOutlineSignature();
	const needRebuild = signature !== outlineSignature || !outlineDomInSync(list);
	if (needRebuild) {
		list.replaceChildren();
		outlineSignature = signature;
		buildOutline(list);
		// A shrunk outline can leave "ghost" pixels of the old, taller list in CEF; a rebuild forces a repaint.
		forceFullRepaint();
	}

	const cur = state.currentStep;
	// Groups: marker from the span of step indices the group holds, plus an n/m of its own.
	for (const groupLi of list.querySelectorAll(".outline-segment")) {
		const steps = groupLi.querySelectorAll(".outline-highlight");
		if (!steps.length) continue;
		const first = Number(steps[0].dataset.step);
		const last = Number(steps[steps.length - 1].dataset.step);
		const header = groupLi.querySelector(".outline-segment-header");
		markerFor(header, header.querySelector(".marker"), first, last, "outline-segment-header");
		const progress = header.querySelector(".segment-progress");
		if (steps.length > 1) {
			const done = cur > last ? steps.length : cur < first ? 0 : cur - first + 1;
			progress.textContent = `${done}/${steps.length}`;
		} else {
			progress.textContent = "";
		}
		for (const row of steps) {
			const i = Number(row.dataset.step);
			markerFor(row, row.querySelector(".hl-marker"), i, i, "outline-highlight");
		}
	}
	// Scenes: the same, over every step the scene holds - so a reader can see which mount they are in and
	// how much of it is left before the tree is replaced.
	for (const sceneLi of list.querySelectorAll(".outline-scene")) {
		const steps = sceneLi.querySelectorAll(".outline-highlight");
		if (!steps.length) continue;
		const first = Number(steps[0].dataset.step);
		const last = Number(steps[steps.length - 1].dataset.step);
		const header = sceneLi.querySelector(".outline-scene-header");
		markerFor(header, header.querySelector(".marker"), first, last, "outline-scene-header");
		const progress = header.querySelector(".segment-progress");
		const done = cur > last ? steps.length : cur < first ? 0 : cur - first + 1;
		progress.textContent = `${done}/${steps.length}`;
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

/**
 * Prev/Next move **one plan step**; holding Shift moves one **group** (the next frame the walk visits).
 *
 * Fine by default, coarse on Shift, and the two-icon button says which. The default never depends on
 * the current group's shape, so Next never skips a whole frame.
 */
function updateNavIcons() {
	const showSegment = shiftHeld;
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

/**
 * The first step of the group before/after the one holding the current step — Shift+Prev/Next.
 *
 * Derived from the outline the sidebar already holds rather than asked for over the wire: the row model is
 * the grouping, so a second definition of "the next frame" could disagree with what the reader can see.
 */
function groupJumpTarget(delta) {
	const groups = (state.outline.scenes || []).flatMap((sc) => sc.groups || [])
		.filter((g) => (g.steps || []).length);
	if (!groups.length) return null;
	const cur = state.currentStep;
	const at = groups.findIndex((g) => cur >= g.steps[0].index && cur <= g.steps[g.steps.length - 1].index);
	if (at === -1) return null;
	// Going back from anywhere but a group's own first step lands on that first step, which is what a
	// reader means by "back to the start of this frame".
	if (delta < 0 && cur > groups[at].steps[0].index) return groups[at].steps[0].index;
	const target = groups[at + delta];
	return target ? target.steps[0].index : null;
}

function navigate(delta) {
	if (shiftHeld) {
		const target = groupJumpTarget(delta);
		if (target !== null) {
			vscode.postMessage({ type: "goto_step", index: target });
			return;
		}
	}
	vscode.postMessage({ type: delta > 0 ? "next" : "prev" });
}

document.getElementById("btn-next").addEventListener("click", () => navigate(+1));

document.getElementById("btn-prev").addEventListener("click", () => navigate(-1));

// The Save split button. The dialogs are the plugin's; this side only says which half was
// pressed.
function setSaveMenuOpen(open) {
	const menu = document.getElementById("save-menu");
	const caret = document.getElementById("btn-save-menu");
	if (!menu || !caret) return;
	menu.style.display = open ? "" : "none";
	caret.setAttribute("aria-expanded", open ? "true" : "false");
}
document.getElementById("btn-save").addEventListener("click", () => {
	setSaveMenuOpen(false);
	vscode.postMessage({ type: "save" });
});
document.getElementById("btn-save-menu").addEventListener("click", (e) => {
	e.stopPropagation(); // or the document handler below closes it again on the same click
	setSaveMenuOpen(document.getElementById("save-menu").style.display === "none");
});
document.getElementById("btn-save-as").addEventListener("click", () => {
	setSaveMenuOpen(false);
	vscode.postMessage({ type: "save_as" });
});
document.addEventListener("click", (e) => {
	const split = document.getElementById("save-split");
	if (split && !split.contains(/** @type {Node} */ (e.target))) setSaveMenuOpen(false);
});
document.addEventListener("keydown", (e) => {
	if (e.key === "Escape") setSaveMenuOpen(false);
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
// Same effect as the header's Close (X) icon, but a discoverable, explicitly-labelled action.
document.getElementById("btn-finish").addEventListener("click", () => {
	vscode.postMessage({ type: "close_walkthrough" });
});

// Loading / replace banners. Each show/hide/collapse changes the docked-strip layout, so force a repaint
// afterwards (CEF otherwise leaves the old strip painted until a hover).
document.getElementById("gen-dismiss").addEventListener("click", () => {
	document.getElementById("generation-banner").style.display = "none";
	forceFullRepaint();
});
document.getElementById("gen-log").addEventListener("click", () => {
	vscode.postMessage({ type: "show_run_log" });
});
document.getElementById("gen-collapse").addEventListener("click", () => {
	const banner = document.getElementById("generation-banner");
	const btn = document.getElementById("gen-collapse");
	const collapsed = banner.classList.toggle("collapsed");
	btn.textContent = collapsed ? "▶" : "▼";
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

// Manual "Refresh UI" — a discoverable escape hatch that forces a full repaint of the tool
// window, for the case where an automatic repaint didn't catch a CEF paint glitch.
document.getElementById("btn-refresh").addEventListener("click", () => {
	forceFullRepaint();
});

// Overview (segment explanation) collapse toggle. The whole header is the click target;
// collapsing changes the layout, so force a repaint afterwards.
document.getElementById("explanation-toggle").addEventListener("click", () => {
	const box = document.getElementById("explanation-box");
	const btn = document.getElementById("btn-overview-collapse");
	const collapsed = box.classList.toggle("collapsed");
	btn.innerHTML = collapsed ? "&#9654;" : "&#9660;"; // ▶ / ▼
	btn.title = collapsed ? "Expand overview" : "Collapse overview";
	forceFullRepaint();
});

// Click a word in the transcript — or anywhere on the thin bar above it — to seek playback to that point
// in the current step. Only takes effect once the step's audio is fully buffered
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
	syncVoiceTitle();
	// Engine is per-voice: show the robotic row iff the newly-selected voice is robotic. The plugin
	// follows up with a `robotic_profile` message to sync the slider positions to that voice's profile.
	const opt = e.target.selectedOptions[0];
	setRoboticRowVisible(!!(opt && opt.dataset.robotic));
});

// Clicking the "file:lines" location under Now Playing jumps the editor to that code.
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
				const pct = typeof msg.fraction === "number" ? Math.floor(msg.fraction * 100) : null;
				title.textContent = (msg.displayName
					? `Generating: ${msg.displayName}`
					: "Generating walkthrough…") + (pct !== null ? ` — ${pct}%` : "");
				activity.textContent = msg.activity || "";
				const warning = document.getElementById("gen-warning");
				const hadWarning = warning.style.display !== "none";
				warning.textContent = msg.warning ? `⚠ ${msg.warning}` : "";
				warning.style.display = msg.warning ? "" : "none";
				if (hadWarning !== !!msg.warning) forceFullRepaint(); // the strip grows a line
				const bar = document.getElementById("gen-bar");
				bar.style.display = pct !== null ? "" : "none";
				if (pct !== null) document.getElementById("gen-bar-fill").style.width = `${msg.fraction * 100}%`;
			} else if (msg.phase === "failed") {
				banner.style.display = "";
				banner.classList.add("failed");
				banner.classList.remove("collapsed"); // always show the failure detail
				spinner.style.display = "none";
				collapse.style.display = "none";
				dismiss.style.display = "";
				title.textContent = "Generation failed";
				activity.textContent = msg.message || "";
				document.getElementById("gen-warning").style.display = "none";
				document.getElementById("gen-bar").style.display = "none";
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
			// Live IDE theme change: replace the injected :root variables so the panel's
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
			// the audio engine actually uses (otherwise the combobox shows the first voice).
			if (msg.voice) {
				const vs = document.getElementById("voice-select");
				if ([...vs.options].some((o) => o.value === msg.voice)) vs.value = msg.voice;
				syncVoiceTitle();
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
			// "Saved Walkthroughs" label. Only override the static titles when the
			// plugin actually sent a directory (i.e. this project has a workspace root).
			if (msg.walkthroughsDir) {
				walkthroughsDir = msg.walkthroughsDir;
				renderSaveButton();
				const savedTitle = document.getElementById("saved-list-title");
				if (savedTitle) setTip(savedTitle, `Walkthroughs are saved in ${msg.walkthroughsDir}`);
			}
			break;
		}

		case "voice_options": {
			// Repopulate the voice combo for the current walkthrough's language.
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

		case "step_advance": {
			// Narration has started this step. The *position* already arrived in an `update` (the walkthrough
			// fires one move event and the sidebar pushes on it), so this carries no cursor to assign — what
			// it carries is the moment: reset the read-along audio model (clock, retained buffers, sentence
			// map) so the next chunks re-populate it.
			if (typeof msg.index === "number" && msg.index !== state.currentStep) break;
			resetStepAudioModel();
			lastSpokenWord = -1;
			// The explanation box keeps the frame-level overview (set in render()); the transcript box
			// tracks the current step's spoken words. This is the text that matches the audio.
			renderTranscript(true);
			break;
		}

		case "update": {
			// **One position, and the backend states it.** Nothing to guess or reset here: the backend pushes
			// the position as one integer that every consumer reads; never infer it from a group change.
			state = {
				title: msg.title,
				outline: msg.outline || { scenes: [], scened: false, steps: 0 },
				currentStep: typeof msg.currentStep === "number" ? msg.currentStep : -1,
				stepCount: msg.stepCount || 0,
				savedAs: typeof msg.savedAs === "string" ? msg.savedAs : null,
				unsaved: msg.unsaved === true,
				now: msg.now || null,
				status: msg.status,
				language: msg.language || "en",
				languageName: msg.languageName || "English",
				autonym: msg.autonym || "English",
			};
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
			playAudioChunk(msg.data, msg.sampleRate, msg.si || 0, msg.sw || 1, msg.stext || "");
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

// Repaint-on-mutation safety net. CEF doesn't reliably repaint DOM changes without an input
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

// ── Custom hover tooltips ────────────────────────────────────────────────────
// JCEF's offscreen renderer never paints the native `title` tooltip, so a title="…" in the markup
// (Save, Refresh UI, Close, Mute, play controls, collapse buttons, the saved-dir hints) is invisible alone.
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
