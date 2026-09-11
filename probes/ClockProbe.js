/*
 * ClockProbe — the webview's playback clock, asserted without a browser.
 *
 *   node probes/ClockProbe.js
 *
 * The read-along cursor, click-to-seek and (B1b) the beat scheduler all map a time onto the transcript,
 * and they all read one number: audio-content seconds since the step's audio began. Two clocks produce
 * that number and they disagree exactly when playback starves — which is the defect this file exists to
 * pin down, and the reason the fix needs an instrument at all: a cursor three words ahead of the voice
 * reads as "roughly right" to a human watching it, so nobody reports it.
 *
 * The functions under test are EXTRACTED FROM sidebar.js, not copied here. A copy would pass this probe
 * forever while the shipped file drifted underneath it. Extraction fails loudly if a function is renamed.
 */

"use strict";

const fs = require("fs");
const path = require("path");
const vm = require("vm");

const SRC = path.join(__dirname, "..", "src", "main", "resources", "sidebar", "sidebar.js");
const source = fs.readFileSync(SRC, "utf8");

let pass = 0;
let fail = 0;

function header(title) {
    console.log("\n" + title);
    console.log("-".repeat(title.length));
}

function check(what, cond, detail) {
    if (cond) {
        pass++;
        console.log("  ok    " + what + (detail ? "   [" + detail + "]" : ""));
    } else {
        fail++;
        console.log("  FAIL  " + what + (detail ? "   [" + detail + "]" : ""));
    }
}

const near = (a, b) => Math.abs(a - b) < 1e-9;

/**
 * Pull one top-level `function NAME(...) { ... }` out of sidebar.js by brace matching. Every function this
 * probe wants is top-level and brace-balanced with no braces inside string literals; the assertions below
 * would catch a bad slice, and a rename raises rather than silently testing nothing.
 */
function extract(name) {
    const sig = "\nfunction " + name + "(";
    const i = source.indexOf(sig);
    if (i === -1) throw new Error("PROBE IS STALE: sidebar.js has no top-level function '" + name + "'");
    if (source.indexOf(sig, i + 1) !== -1) throw new Error("two definitions of '" + name + "' in sidebar.js");
    let depth = 0;
    let started = false;
    for (let k = source.indexOf("{", i); k < source.length; k++) {
        const c = source[k];
        if (c === "{") { depth++; started = true; } else if (c === "}") depth--;
        if (started && depth === 0) {
            const body = source.slice(i + 1, k + 1);
            if (!body.startsWith("function " + name + "(") || !body.endsWith("}")) {
                throw new Error("extracted a malformed '" + name + "'");
            }
            return body;
        }
    }
    throw new Error("unbalanced braces around '" + name + "'");
}

// The module-level state those functions read and write, as a sandbox. Declared here rather than in the
// extracted text because sidebar.js declares them with `let` far from the functions that use them.
const posted = [];
const sandbox = {
    audioCtx: { currentTime: 0 },
    stepStartClock: null,
    stepClockSkew: 0,
    stepAudioDuration: 0,
    stepBuffers: [],
    stepSentences: [],
    transcriptWordSpans: [],
    // Beats (B1b)
    sentenceTexts: [],
    firedBeats: new Set(),
    beatSentence: [],
    stepAudioEnded: false,
    state: { currentStep: 0, now: { beats: [] } },
    vscode: { postMessage: (m) => posted.push(m) },
    console,
};
vm.createContext(sandbox);

const NEEDED = [
    "scheduleSlot", "recordChunk", "stepElapsed", "currentWordFor", "sentenceForWord",
    "beatKey", "wordIndexOfPhrase", "sentenceForBeat", "fireDueBeats",
];
for (const name of NEEDED) vm.runInContext(extract(name), sandbox, { filename: "sidebar.js:" + name });

const { scheduleSlot, recordChunk, stepElapsed, currentWordFor, sentenceForWord } = sandbox;
const { beatKey, sentenceForBeat, fireDueBeats } = sandbox;

function resetStep() {
    sandbox.stepStartClock = null;
    sandbox.stepClockSkew = 0;
    sandbox.stepAudioDuration = 0;
    sandbox.stepBuffers = [];
    sandbox.stepSentences = [];
}

/** Schedule one chunk the way playAudioChunk does: slot it, bank the gap, record it, advance the queue. */
function feed(queueAt, wallNow, durationSec, si, sw) {
    const slot = scheduleSlot(queueAt, wallNow, sandbox.stepStartClock !== null);
    sandbox.stepClockSkew += slot.gap;
    if (sandbox.stepStartClock === null) sandbox.stepStartClock = slot.at;
    recordChunk({ duration: durationSec }, si, sw);
    return slot.at + durationSec; // the next queueAt
}

console.log("ClockProbe — " + NEEDED.length + " functions extracted from sidebar.js");

// ---------------------------------------------------------------------------------------------------
header("scheduleSlot — a chunk cannot start in the past, and the shortfall is silence");

check("audio still queued ahead: the slot is where it already was, no gap",
    (() => { const s = scheduleSlot(10, 5, true); return near(s.at, 10) && near(s.gap, 0); })(),
    "queued to 10, now 5");
check("the boundary is >=, so arriving exactly on time is not a starvation",
    (() => { const s = scheduleSlot(5, 5, true); return near(s.at, 5) && near(s.gap, 0); })(),
    "queued to 5, now 5");
check("drained already: the slot moves to now and the shortfall is the gap",
    (() => { const s = scheduleSlot(4, 6.5, true); return near(s.at, 6.5) && near(s.gap, 2.5); })(),
    "queued to 4, now 6.5 -> gap 2.5");
// The un-anchored case is the one an off-by-one would get wrong invisibly: every step would be skewed by
// its own absolute start time, which is large, and the cursor would sit at word 0 forever.
check("a step's FIRST chunk anchors rather than starves, so it banks no gap",
    (() => { const s = scheduleSlot(0, 12.34, false); return near(s.at, 12.34) && near(s.gap, 0); })(),
    "queued to 0, now 12.34, not yet anchored");
check("CONTROL: the same numbers once anchored DO bank a gap, so the flag is load-bearing",
    near(scheduleSlot(0, 12.34, true).gap, 12.34),
    "anchored -> gap 12.34, un-anchored -> 0");

// ---------------------------------------------------------------------------------------------------
header("recordChunk — the sentence map the arithmetic reads");

// Two sentences: 4 words over 2.0s (arriving as two frames), then 6 words over 3.0s.
resetStep();
let q = 0;
q = feed(q, 100.0, 1.0, 0, 4);
q = feed(q, 100.5, 1.0, 0, 4);
q = feed(q, 101.5, 3.0, 1, 6);

const s0 = sandbox.stepSentences[0];
const s1 = sandbox.stepSentences[1];
check("sentence 0 spans words 0..3 starting at 0s, its two frames summed",
    s0.wordStart === 0 && s0.wordCount === 4 && near(s0.tStart, 0) && near(s0.dur, 2.0),
    JSON.stringify(s0));
check("sentence 1 starts where sentence 0's words end, and at its accumulated audio offset",
    s1.wordStart === 4 && s1.wordCount === 6 && near(s1.tStart, 2.0) && near(s1.dur, 3.0),
    JSON.stringify(s1));
check("the step's total is the sum of the frames, gaps excluded",
    near(sandbox.stepAudioDuration, 5.0), "5.0s of audio content");
check("no starvation in this run, so nothing was banked",
    near(sandbox.stepClockSkew, 0), "skew 0");

// ---------------------------------------------------------------------------------------------------
header("THE DRIFT — a starved schedule, and the cursor that used to run ahead of the voice");

// Same two sentences, but sentence 1's chunk arrives 1.5s after the queue drained. Sentence 1 is therefore
// HEARD starting at wall 103.5 while its tStart — audio content — is still 2.0.
resetStep();
sandbox.transcriptWordSpans = new Array(10).fill(null);
q = 0;
q = feed(q, 100.0, 1.0, 0, 4);
q = feed(q, 100.5, 1.0, 0, 4);
q = feed(q, 103.5, 3.0, 1, 6); // queue had drained at 102.0 -> 1.5s of silence

check("the starvation was measured, not guessed", near(sandbox.stepClockSkew, 1.5), "skew 1.5s");

// The instant sentence 1 begins to sound.
sandbox.audioCtx.currentTime = 103.5;
const corrected = stepElapsed();
check("elapsed is audio-content seconds, so it equals the sentence's own tStart",
    near(corrected, s1AtStart()), "elapsed " + corrected.toFixed(3) + "s = tStart 2.000s");
function s1AtStart() { return sandbox.stepSentences[1].tStart; }

const wordNow = currentWordFor(corrected);
check("the cursor sits on the first word of the sentence now being spoken",
    wordNow === 4, "word " + wordNow + " of 0..9");

// The control IS the old code: elapsed without the skew subtracted. If this ever stops disagreeing, the
// probe above has stopped proving anything.
const uncorrected = sandbox.audioCtx.currentTime - sandbox.stepStartClock;
const wordDrifted = currentWordFor(uncorrected);
check("CONTROL: without the correction the same instant reads 1.5s late",
    near(uncorrected, 3.5) && wordDrifted !== wordNow,
    "uncorrected -> word " + wordDrifted + ", corrected -> word " + wordNow);
check("CONTROL: and that error is three words of a six-word sentence, not a rounding wobble",
    wordDrifted - wordNow === 3, "drifted " + (wordDrifted - wordNow) + " words ahead");

// ---------------------------------------------------------------------------------------------------
header("the correction is inert when nothing starves");

resetStep();
sandbox.transcriptWordSpans = new Array(10).fill(null);
q = 0;
q = feed(q, 100.0, 1.0, 0, 4);
q = feed(q, 100.5, 1.0, 0, 4);
q = feed(q, 101.5, 3.0, 1, 6); // arrives while 2.0s is still queued: no gap

sandbox.audioCtx.currentTime = 102.0; // sentence 1 begins
check("a healthy stream banks no skew, so corrected and raw elapsed agree",
    near(sandbox.stepClockSkew, 0)
        && near(stepElapsed(), sandbox.audioCtx.currentTime - sandbox.stepStartClock),
    "both 2.000s");
check("and the cursor lands on the same word either way",
    currentWordFor(stepElapsed()) === 4, "word 4");

// ---------------------------------------------------------------------------------------------------
header("sentenceForWord — the inverse a seek and a beat both use");

check("a word inside sentence 1 resolves to sentence 1", sentenceForWord(6) === sandbox.stepSentences[1]);
check("the first word of sentence 0 resolves to sentence 0", sentenceForWord(0) === sandbox.stepSentences[0]);
check("the boundary word belongs to the sentence that starts there, not the one that ended",
    sentenceForWord(4) === sandbox.stepSentences[1], "word 4 = s1.wordStart");
check("a word past the end resolves to nothing rather than the last sentence",
    sentenceForWord(10) === null, "10 words, indices 0..9");

// ---------------------------------------------------------------------------------------------------
header("beatKey — the matching rule, against the table Protocol.kt is also held to");

// probes/beat-normalize.tsv is read by BOTH this file and PlanProbe.java. A phrase the loader accepts and
// the webview never matches is the one failure in B1b with no symptom, so the two sides are asserted
// against one table rather than against each other.
let rows = 0;
for (const line of fs.readFileSync(path.join(__dirname, "beat-normalize.tsv"), "utf8").split(/\r?\n/)) {
    if (!line.trim() || line.startsWith("#")) continue;
    const tab = line.indexOf("\t");
    if (tab < 0) { check("malformed row in beat-normalize.tsv: " + line, false); continue; }
    const input = line.slice(0, tab).replace(/\\n/g, "\n").replace(/\\t/g, "\t");
    const want = line.slice(tab + 1).replace(/\\n/g, "\n").replace(/\\t/g, "\t");
    const got = beatKey(input);
    if (got !== want) check('beatKey("' + input + '") = "' + got + '", table says "' + want + '"', false);
    rows++;
}
check("beatKey matches the shared table (" + rows + " rows)", rows >= 6, "same file PlanProbe reads");
check("whitespace-only normalises to empty, which is what makes a blank phrase inert",
    beatKey(" \t\n ") === "");

// ---------------------------------------------------------------------------------------------------
header("sentenceForBeat — matched against what was spoken, not against a count");

/** Lay out a step whose sentences are [texts], each of the given duration. */
function spokenStep(texts, durations, withText) {
    resetStep();
    sandbox.sentenceTexts = [];
    sandbox.firedBeats = new Set();
    sandbox.beatSentence = [];
    sandbox.stepAudioEnded = false;
    posted.length = 0;
    let at = 0;
    const spans = [];
    texts.forEach((t, si) => {
        for (const w of t.split(/\s+/).filter(Boolean)) spans.push({ textContent: w });
    });
    sandbox.transcriptWordSpans = spans;
    texts.forEach((t, si) => {
        at = feed(at, 1000 + at, durations[si], si, t.split(/\s+/).filter(Boolean).length);
        if (withText) sandbox.sentenceTexts[si] = t;
    });
}

// The example from the design note: the server splits "e.g." off as its own piece, so counting sentences
// would put a beat one piece out. Matching text cannot.
const SPOKEN = [
    "The dispatcher looks up the handler.",
    "If it's missing, e.g.",
    "for an unknown type, it throws.",
];
spokenStep(SPOKEN, [2.0, 1.0, 2.0], true);

check("a phrase resolves to the sentence that actually contains it",
    sentenceForBeat({ say: "looks up" }) === 0);
check("a phrase in the piece the splitter made out of 'e.g.' resolves to that piece",
    sentenceForBeat({ say: "for an unknown type" }) === 2,
    "the author would have called this sentence 2, the server calls it 3");
check("matching ignores case and collapses whitespace",
    sentenceForBeat({ say: "LOOKS    UP" }) === 0);
check("a phrase nobody spoke resolves to nothing rather than to sentence 0",
    sentenceForBeat({ say: "consults the registry" }) === -1);
check("a blank phrase is not a wildcard", sentenceForBeat({ say: "  " }) === -1);

// A phrase quoted across a boundary belongs to the sentence it STARTS in — but only once every sentence
// is known, or a short head matches whichever earlier sentence happens to end the same way.
spokenStep(["alpha beta the handler", "and if it is missing"], [2.0, 2.0], true);
check("mid-stream, a straddling phrase is left unresolved rather than guessed",
    sandbox.stepAudioEnded === false && sentenceForBeat({ say: "the handler and if" }) === -1);
sandbox.stepAudioEnded = true;
check("once every sentence is in, it resolves to the one it starts in",
    sentenceForBeat({ say: "the handler and if" }) === 0);

// ---------------------------------------------------------------------------------------------------
header("sentenceForBeat — the fallback for a tts_server.py older than `stext`");

spokenStep(SPOKEN, [2.0, 1.0, 2.0], false); // no sentence text on the wire
check("with no spoken text, the phrase is found among the transcript's own words",
    sandbox.sentenceTexts.filter(Boolean).length === 0
        && sentenceForBeat({ say: "for an unknown type" }) === 2);
check("and a phrase the transcript does not hold still resolves to nothing",
    sentenceForBeat({ say: "consults the registry" }) === -1);

// ---------------------------------------------------------------------------------------------------
header("fireDueBeats — at the sentence's start, once each");

spokenStep(SPOKEN, [2.0, 1.0, 2.0], true);
sandbox.state = {
    currentStep: 4,
    now: { beats: [{ say: "looks up" }, { say: "for an unknown type" }] },
};

fireDueBeats(0);
check("the beat on the first sentence fires the moment audio starts",
    posted.length === 1 && posted[0].beat === 0 && posted[0].type === "beat");
check("...carrying the step it belongs to, so a stale one can be dropped plugin-side",
    posted[0].index === 4);

fireDueBeats(1.5);
check("a beat whose sentence has not begun does not fire early",
    posted.length === 1, "elapsed 1.5s, sentence 2 starts at 3.0s");

fireDueBeats(3.0);
check("it fires when the playhead reaches that sentence's start",
    posted.length === 2 && posted[1].beat === 1, "elapsed 3.0s = tStart of sentence 2");

fireDueBeats(4.0);
fireDueBeats(5.0);
check("and once only, however many ticks follow", posted.length === 2);

// ---------------------------------------------------------------------------------------------------
header("fireDueBeats — two beats in one sentence is one pulse, not a flicker");

spokenStep(SPOKEN, [2.0, 1.0, 2.0], true);
sandbox.state = {
    currentStep: 0,
    now: { beats: [{ say: "the dispatcher" }, { say: "the handler" }] }, // both in sentence 0
};
fireDueBeats(0);
fireDueBeats(1.0);
check("the second beat on a sentence is suppressed, not fired a millisecond after the first",
    posted.length === 1 && posted[0].beat === 0,
    "startTokenFlash self-cancels, so both firing would show only the second");
check("...and it is suppressed permanently rather than retried every frame",
    sandbox.beatSentence[1] === -2);

// The control: the same two phrases in DIFFERENT sentences both fire, so the rule above is about
// sharing a sentence and not about the second beat being ignored.
spokenStep(SPOKEN, [2.0, 1.0, 2.0], true);
sandbox.state = {
    currentStep: 0,
    now: { beats: [{ say: "the dispatcher" }, { say: "for an unknown type" }] },
};
fireDueBeats(0);
fireDueBeats(3.0);
check("CONTROL: two beats in two sentences both fire",
    posted.length === 2 && posted[0].beat === 0 && posted[1].beat === 1);

// ---------------------------------------------------------------------------------------------------
header("a beat with no `say` belongs to the plugin, not to this loop");

// An at-start beat fires on `playback_started`, which is a measured signal the plugin already has
// (MoreUI §2.8). If this loop also fired it, the pulse would happen twice — and the second would cancel
// the first mid-fade. Retired on sight rather than left unresolved, so it is not re-scanned every frame.
spokenStep(SPOKEN, [2.0, 1.0, 2.0], true);
sandbox.state = {
    currentStep: 0,
    now: { beats: [{ say: "", token: "Apply", line: 68 }, { say: "for an unknown type", token: "X" }] },
};
fireDueBeats(0);
check("the at-start beat is not fired here", posted.length === 0, "nothing posted at elapsed 0");
check("...and it is retired, not retried every frame", sandbox.beatSentence[0] === -2);
fireDueBeats(3.0);
check("CONTROL: the timed beat beside it still fires normally",
    posted.length === 1 && posted[0].beat === 1);

// ---------------------------------------------------------------------------------------------------
header("a beat and the cursor read the same clock");

// The whole reason fireDueBeats takes `elapsed` rather than reading the wall clock itself: after a
// starvation the two would disagree, and a pulse landing on a word the cursor has not reached is exactly
// the "roughly right" failure the drift fix removes.
spokenStep(SPOKEN, [2.0, 1.0, 2.0], true);
sandbox.stepClockSkew = 1.5; // as if the schedule had starved by 1.5s
sandbox.audioCtx.currentTime = sandbox.stepStartClock + 3.0 + 1.5;
sandbox.state = { currentStep: 0, now: { beats: [{ say: "for an unknown type" }] } };
const e = stepElapsed();
fireDueBeats(e);
check("with a starved schedule the beat still fires exactly at its sentence",
    near(e, 3.0) && posted.length === 1, "elapsed " + e.toFixed(3) + "s");
check("and the cursor is on that sentence's first word at the same instant",
    currentWordFor(e) === sandbox.stepSentences[2].wordStart,
    "word " + currentWordFor(e));

// ---------------------------------------------------------------------------------------------------
console.log("\n" + pass + " passed, " + fail + " failed");
process.exit(fail === 0 ? 0 : 1);
