package com.audioexplainer.acp

import com.audioexplainer.McpEndpoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * **What an ACP agent may do in an explain run: read, search, and send the plan** — the answers
 * to `session/request_permission`, as a pure function so `probes/AcpProbe.java` can table-test it.
 *
 * Allowed, once each:
 * - a read or a search (`kind` `read` / `search`) whose locations, if it names any, are all inside [roots];
 * - the run's own `set_plan` — by name (`audio-explainer_set_plan`, `mcp__audio-explainer__set_plan`,
 *   `set_plan (audio-explainer MCP Server)`), or, for an agent that asks before it names the call (Qwen Code asks
 *   with the plan as the title and no name at all), by an input shaped like a plan on a call of no other kind;
 * - opencode's `external_directory` — its ask before it reads outside `cwd`, `rawInput {filepath, parentDir}` —
 *   when every path in it is inside [roots]. **opencode ends its turn on a rejection**, so the skill folder must be one.
 *
 * Everything else is rejected: edits, deletes, moves, shell commands, fetches, and anything unrecognised. **Never
 * `allow_always`**: an always would outlive this run, in the agent's own config.
 */
object AcpPolicy {
    data class Decision(val allowed: Boolean, val optionId: String?, val why: String)

    private val DENIED_KINDS = UnaskedWatch.RISKY
    private val EXTERNAL_DIRECTORY_KEYS = setOf("filepath", "parentDir")

    fun decide(toolCall: JsonObject?, options: JsonArray?, roots: List<String>): Decision {
        val (allowed, why) = judge(toolCall, roots)
        val opts = options?.mapNotNull { it as? JsonObject }.orEmpty()
        fun pick(vararg kinds: String) = kinds.firstNotNullOfOrNull { k -> opts.firstOrNull { it.str("kind") == k }?.str("optionId") }
        val option = if (allowed) pick("allow_once") else pick("reject_once", "reject_always")
        return Decision(allowed, option, why)
    }

    /** The `session/request_permission` result for [d]: the chosen option, or cancelled when the agent offered none that fits. */
    fun result(d: Decision): JsonObject = buildJsonObject {
        put("outcome", buildJsonObject {
            if (d.optionId != null) { put("outcome", "selected"); put("optionId", d.optionId) }
            else put("outcome", "cancelled")
        })
    }

    private fun judge(call: JsonObject?, roots: List<String>): Pair<Boolean, String> {
        if (call == null) return false to "no tool call in the request"
        val kind = call.str("kind")
        val input = call["rawInput"] as? JsonObject
        val locations = (call["locations"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("path") }.orEmpty()
        if (kind in DENIED_KINDS) return false to "a ${kind} is not part of an explanation"
        if (isSetPlan(call)) return true to "the run's set_plan"
        if (kind == "read" || kind == "search") {
            val outside = locations.firstOrNull { !inRoots(it, roots) }
            return if (outside == null) true to "a $kind" else false to "a $kind outside the project and the skill: $outside"
        }
        if (input != null && input.isNotEmpty() && input.keys.all { it in EXTERNAL_DIRECTORY_KEYS }) {
            val paths = locations + input.values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val outside = paths.firstOrNull { !inRoots(it, roots) }
            return if (outside == null) true to "a read of the project or the skill"
            else false to "a directory outside the project and the skill: $outside"
        }
        return false to "not a read, a search or the run's set_plan"
    }

    /** The run's own tool, by any name an agent gives it, or by the plan it carries when it gives none. */
    fun isSetPlan(call: JsonObject): Boolean {
        if (names(call).any(::namesSetPlan)) return true
        val kind = call.str("kind")
        val input = call["rawInput"] as? JsonObject ?: return false
        return (kind == null || kind == "other") && input["title"] is JsonPrimitive && input["frames"] is JsonArray
    }

    /** Every name an agent may put on a tool call: the title, and `_meta.toolName` at either depth Qwen Code uses. */
    fun names(call: JsonObject): List<String> {
        val meta = call["_meta"] as? JsonObject
        return listOfNotNull(
            call.str("title"),
            meta?.str("toolName"),
            (meta?.get("toolLifecycle") as? JsonObject)?.str("toolName"),
        )
    }

    fun namesSetPlan(name: String): Boolean {
        val head = name.substringBefore(':').substringBefore('{')
        return McpEndpoint.TOOL_NAME in head && McpEndpoint.SERVER_NAME in head
    }

    /**
     * Whether [path] is inside one of [roots]: separators either way, `.` and `..` resolved, and — on Windows, where
     * the file system is — case ignored.
     */
    fun inRoots(path: String, roots: List<String>, ignoreCase: Boolean = isWindows): Boolean {
        val p = normalize(path, ignoreCase) ?: return false
        return roots.mapNotNull { normalize(it, ignoreCase) }.any { r -> p == r || p.startsWith(r.trimEnd('/') + "/") }
    }

    internal fun normalize(path: String, ignoreCase: Boolean): String? {
        val s = path.trim().replace('\\', '/')
        if (s.isEmpty()) return null
        val out = ArrayList<String>()
        val absolute = s.startsWith("/")
        for (part in s.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (out.isEmpty() || out.last().endsWith(":")) return null else out.removeAt(out.size - 1)
                else -> out += part
            }
        }
        val joined = (if (absolute) "/" else "") + out.joinToString("/")
        return if (ignoreCase) joined.lowercase() else joined
    }

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * **What [AcpPolicy] cannot answer: a call the agent never asked about.** The policy is the answer to
 * `session/request_permission`, and an agent whose own config allows a tool runs it without sending one. opencode
 * launched as a Custom agent can write a file this way: its `tool_call` for `write` goes from pending to
 * completed, and no request comes. The client cannot prevent that; it can see it.
 *
 * A call of a kind the policy never allows ([RISKY]) that reports `completed`, never having been asked about, is the
 * agent acting on its own. [update] says so once per call, with what it touched, and the run stops the agent.
 * A refused call that completes anyway is not reported: an agent may well mark its refused call so.
 */
class UnaskedWatch {
    private val kinds = HashMap<String, String>()
    private val targets = HashMap<String, String>()
    private val titles = HashMap<String, String>()
    private val asked = HashSet<String>()
    private val reported = HashSet<String>()

    /** A `session/request_permission`'s `toolCall`: the call was asked about, whatever the answer. */
    @Synchronized
    fun asked(toolCall: JsonObject?) {
        toolCall?.str("toolCallId")?.let(asked::add)
    }

    /** A `session/update`'s `update`: what the call did, when it is a risky one that completed unasked; else null. */
    @Synchronized
    fun update(u: JsonObject?): String? {
        val type = u?.str("sessionUpdate")
        if (type != "tool_call" && type != "tool_call_update") return null
        val id = u.str("toolCallId") ?: return null
        u.str("kind")?.let { kinds[id] = it }
        target(u)?.let { targets[id] = it }
        u.str("title")?.takeIf { !it.startsWith("{") }?.let { titles[id] = it }
        if (u.str("status") != "completed" || id in asked || id in reported) return null
        val kind = kinds[id]?.takeIf { it in RISKY } ?: return null
        reported += id
        return "${VERB[kind]} ${targets[id] ?: titles[id] ?: "a tool call"}".take(200)
    }

    private fun target(u: JsonObject): String? {
        val input = u["rawInput"] as? JsonObject
        input?.let { i -> TARGET_KEYS.firstNotNullOfOrNull { i.str(it) } }?.let { return it }
        val first = (u["locations"] as? JsonArray)?.firstOrNull() as? JsonObject
        return first?.str("path")
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** ACP's tool kinds the policy refuses; `read`, `search`, `think` and `other` (the plan) are not here. */
        val RISKY = setOf("edit", "delete", "move", "execute", "fetch")
        private val VERB = mapOf("edit" to "wrote", "delete" to "deleted", "move" to "moved", "execute" to "ran", "fetch" to "fetched")
        private val TARGET_KEYS = listOf("command", "file_path", "filePath", "filepath", "path", "url")
    }
}
