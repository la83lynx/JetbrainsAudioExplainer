package com.audioexplainer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * **How far an explain run has got, estimated from what the run streams** — no cooperation from the agent.
 *
 * The agent cannot report its own progress: almost all of a run is one model turn (think, then emit the plan
 * `Write`) with no tool call inside it, and a model has no idea how long its own turn will take. What the
 * plugin *can* see, with `--include-partial-messages`, is enough to fill a bar honestly:
 *
 * | Band | Fraction | Driven by |
 * |---|---|---|
 * | starting | 0 – 6 % | time, until the model's first message |
 * | reading and planning | 6 – 50 % | time **and** the thinking-token heartbeats, against what this effort usually thinks |
 * | writing the plan | 50 – 88 % | the bytes of the `Write` streamed so far, against what this depth usually writes |
 * | checking and sending | 88 – 96 % | time, from the end of the `Write` to the POST's reply |
 * | finishing | 96 – 99.5 % | time, until the result line |
 *
 * The bands are `Speed.md`'s measured medium/Standard timeline (`bench2`/`bench3`): ~8 s to the first read,
 * ~25 s reading and thinking, ~30 s writing ~9.5 KB, ~5 s to the POST, ~8 s of closing summary. Writing is the
 * one band with a real numerator, which is lucky, because it is also the longest.
 *
 * **Never goes backwards.** A re-POST after an `[error]` reply is a second `Write`, which the bar spends inside
 * the band it is already in rather than refilling.
 *
 * [now] is seconds on any monotonic clock, injected so a recorded run can be replayed at its own timestamps.
 */
class ExplainProgress(effort: String, depth: String, private val now: () -> Double = { System.nanoTime() / 1e9 }) {

    enum class Band(val from: Double, val to: Double) {
        STARTING(0.00, 0.06),
        PREPARING(0.06, 0.50),
        WRITING(0.50, 0.88),
        SENDING(0.88, 0.96),
        FINISHING(0.96, 0.995),
        DONE(1.0, 1.0),
    }

    private val expectedThinking = when (effort.trim()) {
        AudioExplainerSettings.EFFORT_LOW -> 600.0
        AudioExplainerSettings.EFFORT_MEDIUM -> 1_500.0 // bench3: 1 150-2 000 estimated before the plan `Write`
        AudioExplainerSettings.EFFORT_HIGH -> 4_000.0
        AudioExplainerSettings.EFFORT_XHIGH -> 7_000.0
        AudioExplainerSettings.EFFORT_MAX -> 10_000.0
        else -> 4_000.0 // inherited from the user's own settings, which are usually above medium
    }

    /**
     * Characters of `input_json_delta` — the `Write`'s input as JSON, so the plan's own quotes count twice.
     * Standard is bench3's 8 305 and 8 596 (4 frames, 12-13 steps); the other two are scaled from it.
     */
    private val expectedWriteChars = when (depth) {
        AudioExplainerSettings.DEPTH_BRIEF -> 3_500.0
        AudioExplainerSettings.DEPTH_DEEP -> 14_000.0
        else -> 8_500.0
    }

    private val started = now()
    private var bandStarted = started
    private var floor = 0.0

    var band = Band.STARTING
        private set
    private var thinkingTokens = 0
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
            }
            "assistant" -> for (block in obj.blocks()) {
                if (block.str("type") != "tool_use") continue
                val name = block.str("name")
                val input = block["input"] as? JsonObject
                if (band < Band.WRITING) {
                    enter(Band.PREPARING)
                    activity = when (name) {
                        "Read" -> "Reading ${input?.str("file_path")?.substringAfterLast('/')?.substringAfterLast('\\') ?: "the code"}…"
                        "Grep", "Glob" -> "Searching the code…"
                        "Task" -> "Exploring the code around it…"
                        else -> "Planning the walkthrough…"
                    }
                } else if ((name == "Bash" || name == "PowerShell") && input?.str("command")?.let(::isPost) == true) {
                    postPending = true
                    activity = "Sending the walkthrough…"
                }
            }
            "user" -> if (obj.blocks().any { it.str("type") == "tool_result" }) {
                when {
                    postPending -> { postPending = false; enter(Band.FINISHING); activity = "Finishing…" }
                    band < Band.WRITING -> activity = "Planning the walkthrough…"
                }
            }
            "result" -> { enter(Band.DONE); activity = "Done" }
        }
    }

    private fun onStreamEvent(ev: JsonObject) {
        when (ev.str("type")) {
            "message_start" -> { writeBlock = -1; enter(Band.PREPARING) }
            "content_block_start" -> {
                val block = ev["content_block"] as? JsonObject ?: return
                when (block.str("type")) {
                    "thinking" -> if (band < Band.WRITING) activity = "Planning the walkthrough…"
                    "tool_use" -> if (block.str("name") == "Write") {
                        writeBlock = ev["index"]?.jsonPrimitive?.intOrNull ?: -1
                        writes++
                        if (writes == 1) {
                            enter(Band.WRITING)
                            activity = "Writing the walkthrough…"
                        } else {
                            activity = "Fixing the walkthrough…"
                        }
                    }
                }
            }
            "content_block_delta" -> {
                if (ev["index"]?.jsonPrimitive?.intOrNull != writeBlock || writes != 1) return
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
            "content_block_stop" -> if (ev["index"]?.jsonPrimitive?.intOrNull == writeBlock) {
                writeBlock = -1
                if (writes == 1) {
                    enter(Band.SENDING)
                    activity = "Checking the walkthrough…"
                }
            }
        }
    }

    /** The bar's position, 0..1. Never decreases between calls. */
    fun fraction(): Double {
        val t = now()
        val p = when (band) {
            Band.STARTING -> ease(t - bandStarted, 5.0)
            Band.PREPARING -> 1 - exp(-((t - bandStarted) / 30.0 + thinkingTokens / expectedThinking))
            Band.WRITING -> {
                val r = writeChars / expectedWriteChars
                // Linear while the plan is the usual size; a longer one creeps toward the end of the band.
                if (r < 0.9) r else 0.9 + 0.1 * (1 - exp(-(r - 0.9) * 5))
            }
            Band.SENDING, Band.FINISHING -> ease(t - bandStarted, 5.0)
            Band.DONE -> 1.0
        }
        floor = max(floor, band.from + (band.to - band.from) * min(p, 1.0))
        return floor
    }

    fun activity(): String = activity

    private fun enter(next: Band) {
        if (next <= band) return
        band = next
        bandStarted = now()
    }

    private fun ease(seconds: Double, tau: Double) = 1 - exp(-seconds / tau)

    private fun isPost(command: String) = POST.containsMatchIn(command)

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.blocks(): List<JsonObject> =
        runCatching { this["message"]!!.jsonObject["content"]!!.jsonArray.mapNotNull { it as? JsonObject } }.getOrDefault(emptyList())

    companion object {
        private val FRAME_KEY = Regex(Regex.escape("\\\"keep\\\""))
        private val STEP_KEY = Regex(Regex.escape("\\\"ttsText\\\""))
        private const val CARRY = 10 // one less than the longest key above, `\"ttsText\"`
        private val POST = Regex("""explainer\.(ps1|sh)|/api/message""", RegexOption.IGNORE_CASE)
    }
}
