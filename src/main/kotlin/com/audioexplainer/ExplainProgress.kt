package com.audioexplainer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * **What one kind of run usually takes** — the expectation [ExplainProgress] divides by, one per
 * effort/depth/model ([ExplainTimings] keeps them).
 *
 * Two numbers carry a run: how long until the plan `Write` starts (reading, searching, thinking — almost all
 * of it the model thinking, which effort sets), and how big that `Write` is (which depth sets) at a speed that
 * barely varies (bench3: 299-333 chars/s across six runs).
 */
data class RunShape(
    /** Seconds from launch until the plan `Write` starts streaming. */
    val preSeconds: Double,
    /** Characters of the `Write`'s streamed input — the plan as a JSON string, so its own quotes count twice. */
    val writeChars: Double,
    /** How fast that input streams, characters per second. */
    val writeRate: Double,
    /** How many finished runs taught it; 0 for a [seed]. */
    val runs: Int = 0,
) {
    val writeSeconds: Double get() = writeChars / writeRate

    /**
     * This expectation moved toward one more run's [observed] shape. **A seed is a guess and a run is a
     * measurement**, so the first run moves it three quarters of the way; after that each run moves it 40 %,
     * which follows a change of model or habit within two or three runs without one slow run owning it.
     */
    fun learn(observed: RunShape): RunShape {
        val a = if (runs == 0) 0.75 else 0.4
        fun mix(old: Double, new: Double) = old + a * (new - old)
        return RunShape(
            mix(preSeconds, observed.preSeconds), mix(writeChars, observed.writeChars),
            mix(writeRate, observed.writeRate), runs + 1,
        )
    }

    fun encode(): String =
        String.format(Locale.ROOT, "pre=%.1f;chars=%.0f;rate=%.0f;runs=%d", preSeconds, writeChars, writeRate, runs)

    companion object {
        fun parse(text: String): RunShape? = runCatching {
            val m = text.split(';').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            RunShape(m.getValue("pre").toDouble(), m.getValue("chars").toDouble(), m.getValue("rate").toDouble(), m["runs"]?.toInt() ?: 1)
        }.getOrNull()?.takeIf { it.preSeconds > 0 && it.writeChars > 0 && it.writeRate > 0 }

        /**
         * The guess before a combination has run here. Medium/Standard is bench3 (six runs: the `Write` starts
         * at 27-48 s, ~8 000 chars at ~320/s); the rest is the 2026-10-13 sandbox round (medium/Brief ~15 s,
         * high/Standard ~28 s, xhigh/Deep ~206 s) and `Speed.md`'s xhigh/Standard (~165 s).
         */
        fun seed(effort: String, depth: String): RunShape {
            val pre = when (effort.trim()) {
                AudioExplainerSettings.EFFORT_LOW -> 22.0
                AudioExplainerSettings.EFFORT_MEDIUM -> 32.0
                AudioExplainerSettings.EFFORT_HIGH -> 40.0
                AudioExplainerSettings.EFFORT_XHIGH -> 150.0
                AudioExplainerSettings.EFFORT_MAX -> 200.0
                else -> 40.0 // inherited from the user's own settings
            }
            return when (depth) {
                AudioExplainerSettings.DEPTH_BRIEF -> RunShape(pre * 0.5, 3_000.0, 320.0)
                AudioExplainerSettings.DEPTH_DEEP -> RunShape(pre * 1.35, 11_000.0, 320.0)
                else -> RunShape(pre, 8_000.0, 320.0)
            }
        }
    }
}

/**
 * **How far an explain run has got, estimated from what the run streams** — no cooperation from the agent.
 *
 * The agent cannot report its own progress: most of a run is one model turn (think, then emit the plan
 * `Write`) with no tool call inside it, and the thinking itself streams blank — `--include-partial-messages`
 * gives a token count every ~1.2 s, never the text. So the bar is **time spent over time spent plus time
 * left**, with time left from [expected]:
 *
 * | Phase | Time left |
 * |---|---|
 * | preparing — reading, searching, thinking | the rest of [RunShape.preSeconds], then the whole `Write`, then [TAIL] |
 * | writing the plan | the `Write`'s unstreamed characters at the speed it is streaming now, then [TAIL] |
 * | checking — `Write` done | [TAIL]; the bar eases toward the end from wherever it is |
 *
 * A run that goes as expected fills the bar at an even pace, and **a phase change is never a jump to a fixed
 * mark**: it only changes the estimate of what is left. A phase that overruns its expectation slows the bar
 * without ever pinning it ([overrun]). The end is small on purpose: the plan loads from its `Write`, so what
 * follows is usually never on screen.
 *
 * The bar glides toward its target over [GLIDE] seconds and **never goes backwards**. [now] is seconds on any
 * monotonic clock, injected so a recorded run can be replayed at its own timestamps.
 */
class ExplainProgress(private val expected: RunShape, private val now: () -> Double = { System.nanoTime() / 1e9 }) {

    enum class Phase { PREPARING, WRITING, CHECKING, DONE }

    private val started = now()
    var phase = Phase.PREPARING
        private set
    private var writeStarted = Double.NaN
    private var writeEnded = Double.NaN
    private var finished = Double.NaN
    private var shown = 0.0
    private var lastSample = started
    private var barAtWriteStart = Double.NaN
    private var barAtWriteEnd = Double.NaN
    private var writeFullAt = Double.NaN
    private var checkFrom = 0.0

    private var thinkingTokens = 0
    private var thinking = false
    private var tools = 0
    private var writeChars = 0
    private var writeFrames = 0
    private var writeCarry = ""
    private var writeSteps = 0
    private var writes = 0
    private var writeBlock = -1
    private var postPending = false
    private var activity = "Starting Claude…"

    /** Feed one parsed `stream-json` line. */
    fun onEvent(obj: JsonObject) {
        when (obj.str("type")) {
            "stream_event" -> onStreamEvent(obj["event"] as? JsonObject ?: return)
            "system" -> if (obj.str("subtype") == "thinking_tokens") {
                thinkingTokens += obj["estimated_tokens_delta"]?.jsonPrimitive?.intOrNull ?: 0
                if (thinking) activity = thinkingLabel()
            }
            "assistant" -> for (block in obj.blocks()) {
                if (block.str("type") != "tool_use") continue
                val name = block.str("name")
                val input = block["input"] as? JsonObject
                when {
                    // A stream without partial messages never showed the `Write` start: it begins and ends here.
                    name == "Write" && writes == 0 -> {
                        writes = 1
                        writeStarted = now(); writeEnded = writeStarted
                        barAtWriteStart = shown; barAtWriteEnd = shown
                        checkFrom = max(shown, target(writeEnded))
                        phase = Phase.CHECKING
                        thinking = false
                        activity = "Checking the walkthrough…"
                    }
                    phase == Phase.PREPARING -> {
                        tools++
                        thinking = false
                        activity = toolLabel(name, input)
                    }
                    (name == "Bash" || name == "PowerShell") && input?.str("command")?.let(POST::containsMatchIn) == true -> {
                        postPending = true
                        activity = "Sending the walkthrough…"
                    }
                }
            }
            "user" -> if (obj.blocks().any { it.str("type") == "tool_result" }) {
                when {
                    postPending -> { postPending = false; activity = "Finishing…" }
                    phase == Phase.PREPARING -> { thinking = true; activity = thinkingLabel() }
                }
            }
            "result" -> {
                phase = Phase.DONE
                finished = now()
                activity = "Done"
            }
        }
    }

    private fun onStreamEvent(ev: JsonObject) {
        when (ev.str("type")) {
            "message_start" -> if (phase != Phase.WRITING) writeBlock = -1
            "content_block_start" -> {
                val block = ev["content_block"] as? JsonObject ?: return
                when (block.str("type")) {
                    "thinking" -> if (phase == Phase.PREPARING) { thinking = true; activity = thinkingLabel() }
                    "tool_use" -> if (block.str("name") == "Write") {
                        writes++
                        if (writes == 1 && phase == Phase.PREPARING) {
                            writeBlock = ev["index"]?.jsonPrimitive?.intOrNull ?: -1
                            writeStarted = now()
                            barAtWriteStart = shown
                            phase = Phase.WRITING
                            thinking = false
                            activity = "Writing the walkthrough…"
                        } else {
                            activity = "Fixing the walkthrough…"
                        }
                    }
                }
            }
            "content_block_delta" -> {
                if (phase != Phase.WRITING || ev["index"]?.jsonPrimitive?.intOrNull != writeBlock) return
                val delta = ev["delta"] as? JsonObject ?: return
                val part = delta.str("partial_json") ?: return
                writeChars += part.length
                // A key once per frame or step. Inside the `Write`'s input the plan is a JSON *string*, so its keys
                // arrive with escaped quotes — which is what keeps a "keep" in the prose from counting. **Nearly
                // every key is split across two deltas** (bench3 b1: 1 of 13 whole in one), so the scan carries the
                // previous delta's tail, and counts only a match that ends in this delta — one lying wholly inside
                // the carry was counted last time.
                val scan = writeCarry + part
                val fresh = writeCarry.length
                writeFrames += FRAME_KEY.findAll(scan).count { it.range.last >= fresh }
                writeSteps += STEP_KEY.findAll(scan).count { it.range.last >= fresh }
                writeCarry = scan.takeLast(CARRY)
                activity = when {
                    writeSteps > 0 -> "Writing the walkthrough — step $writeSteps…"
                    writeFrames > 0 -> "Writing the walkthrough — frame $writeFrames…"
                    else -> activity
                }
            }
            "content_block_stop" -> if (phase == Phase.WRITING && ev["index"]?.jsonPrimitive?.intOrNull == writeBlock) {
                writeEnded = now()
                barAtWriteEnd = fraction()
                checkFrom = max(shown, target(writeEnded))
                phase = Phase.CHECKING
                activity = "Checking the walkthrough…"
            }
        }
    }

    /** The bar's position, 0..1. Never decreases between calls; 1 only once the run has ended. */
    fun fraction(): Double {
        val t = now()
        if (phase == Phase.DONE) {
            shown = 1.0
        } else {
            val target = target(t)
            if (target > shown) shown += (target - shown) * min(1.0, (t - lastSample) / GLIDE)
            shown = min(shown, CAP)
        }
        lastSample = t
        return shown
    }

    private fun target(t: Double): Double {
        val elapsed = t - started
        if (elapsed <= 0) return 0.0
        return when (phase) {
            Phase.PREPARING -> {
                val whole = expected.preSeconds + expected.writeSeconds + TAIL
                if (elapsed <= expected.preSeconds) elapsed / whole
                else overrun(expected.preSeconds / whole, (elapsed - expected.preSeconds) / expected.preSeconds)
            }
            Phase.WRITING -> {
                val left = expected.writeChars - writeChars
                if (left >= 0) {
                    // The speed it is actually streaming at, once there is enough of it to measure.
                    val spent = t - writeStarted
                    val rate = if (spent >= 2 && writeChars >= 400) writeChars / spent else expected.writeRate
                    elapsed / (elapsed + left / rate + TAIL)
                } else {
                    if (writeFullAt.isNaN()) writeFullAt = elapsed
                    overrun(writeFullAt / (writeFullAt + TAIL), -left / expected.writeChars)
                }
            }
            Phase.CHECKING -> overrun(checkFrom, (t - writeEnded) / CHECK_SECONDS)
            Phase.DONE -> 1.0
        }
    }

    /**
     * **Past the expectation**: from [from], two thirds of the way to the end, approached as `x / (1 + x)` —
     * half of it once the phase has run twice its expected length, three quarters at four times. That keeps
     * moving long after an exponential would have pinned, and the phase that follows starts close to where
     * this one has got to (bench3 b2's 48 s think against 32 s expected: 63 % here, 63 % as its `Write` starts).
     */
    private fun overrun(from: Double, x: Double) = from + (1 - from) * (2.0 / 3) * (x / (1 + x))

    fun activity(): String = activity

    /** This run's own shape, to learn from — null unless a plan `Write` streamed start to end. */
    fun observed(): RunShape? {
        if (writeStarted.isNaN() || writeEnded.isNaN() || writeEnded <= writeStarted || writeChars == 0) return null
        return RunShape(writeStarted - started, writeChars.toDouble(), writeChars / (writeEnded - writeStarted), 1)
    }

    /**
     * One log line on how the run went against [expected], and **how honest the bar was**: where it stood at the
     * `Write`'s start and end, beside where a perfectly even bar would have stood for this run's real length.
     */
    fun report(): String {
        val total = (if (finished.isNaN()) now() else finished) - started
        if (writeStarted.isNaN()) return "no plan Write; ${thinkingTokens} thinking token(s), $tools tool call(s)"
        fun pct(v: Double) = if (v.isNaN()) "?" else "%.0f%%".format(Locale.ROOT, v * 100)
        val pre = writeStarted - started
        val write = if (writeEnded.isNaN()) Double.NaN else writeEnded - writeStarted
        return String.format(
            Locale.ROOT,
            "plan Write at %.1fs (expected %.1fs), %,d chars in %.1fs (expected %,.0f at %.0f/s), %,d thinking token(s), " +
                "%d tool call(s) before it; bar at the Write %s (even %s), at its end %s (even %s)",
            pre, expected.preSeconds, writeChars, write, expected.writeChars, expected.writeRate, thinkingTokens, tools,
            pct(barAtWriteStart), pct(pre / total), pct(barAtWriteEnd), pct((pre + write) / total),
        )
    }

    /** With its running token count — the one live sign of a long think, which streams no text. */
    private fun thinkingLabel() = when {
        thinkingTokens <= 0 -> "Thinking it through…"
        thinkingTokens < 1_000 -> "Thinking it through — $thinkingTokens tokens…"
        else -> String.format(Locale.ROOT, "Thinking it through — %.1fk tokens…", thinkingTokens / 1000.0)
    }

    private fun toolLabel(name: String?, input: JsonObject?): String {
        val path = input?.str("file_path")?.replace('\\', '/')
        return when (name) {
            "Read" -> when {
                path == null -> "Reading the code…"
                SKILL_DOCS in path -> "Reading the explainer's instructions…"
                else -> "Reading ${path.substringAfterLast('/')}…"
            }
            "Grep", "Glob" -> "Searching the code…"
            "Task" -> "Exploring the code around it…"
            else -> "Planning the walkthrough…"
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.blocks(): List<JsonObject> =
        runCatching { this["message"]!!.jsonObject["content"]!!.jsonArray.mapNotNull { it as? JsonObject } }.getOrDefault(emptyList())

    companion object {
        /** Seconds from the `Write`'s end until the plan is on screen — loaded from the `Write` itself, usually. */
        const val TAIL = 3.0
        /** After the `Write`: the bar is halfway from there to the end this long after it, if anyone still sees it. */
        private const val CHECK_SECONDS = 10.0
        /** Seconds the bar takes to close most of the way to a new target. */
        const val GLIDE = 1.0
        private const val CAP = 0.995
        private const val SKILL_DOCS = "/skills/audio-explainer/"
        private val FRAME_KEY = Regex(Regex.escape("\\\"keep\\\""))
        private val STEP_KEY = Regex(Regex.escape("\\\"ttsText\\\""))
        private const val CARRY = 10 // one less than the longest key above, `\"ttsText\"`
        private val POST = Regex("""explainer\.(ps1|sh)|/api/message""", RegexOption.IGNORE_CASE)
    }
}
