package com.audioexplainer.acp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * **`fs/read_text_file`, answered from the IDE** (`Models.B.md` B2b): an agent that hands its reads to the client
 * (Qwen Code does) reads the editor's text, so a walkthrough of a file with unsaved edits narrates the edits.
 *
 * Only inside the roots the permission policy allows ([AcpPolicy.inRoots]) — the project, the skill, the
 * request's own paths. `line` is 1-based and `limit` a number of lines, as the protocol has them; each line keeps
 * its own terminator, so a slice is exactly the text it covers. The IDE half — which document, or the disk — is
 * [text], so a probe can hand it a map.
 */
object AcpFs {
    fun read(params: JsonObject?, roots: List<String>, text: (String) -> String?): JsonObject {
        val path = (params?.get("path") as? JsonPrimitive)?.contentOrNull
            ?: throw AcpException(AcpConnection.INVALID_PARAMS, "fs/read_text_file needs a path")
        if (!AcpPolicy.inRoots(path, roots)) {
            throw AcpException(AcpConnection.INVALID_PARAMS, "$path is outside the project and the skill; this client reads only those")
        }
        val all = text(path) ?: throw AcpException(AcpConnection.INTERNAL_ERROR, "cannot read $path")
        val line = (params["line"] as? JsonPrimitive)?.intOrNull
        val limit = (params["limit"] as? JsonPrimitive)?.intOrNull
        return buildJsonObject { put("content", slice(all, line, limit)) }
    }

    /** Lines [line] (1-based; null or below 1 = the first) onward, at most [limit] of them (null = to the end). */
    fun slice(text: String, line: Int?, limit: Int?): String {
        val from = ((line ?: 1).coerceAtLeast(1)) - 1
        if (from == 0 && limit == null) return text
        var start = 0
        repeat(from) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) return ""
            start = nl + 1
        }
        if (limit == null) return text.substring(start)
        var end = start
        repeat(limit.coerceAtLeast(0)) {
            if (end >= text.length) return text.substring(start)
            val nl = text.indexOf('\n', end)
            end = if (nl < 0) text.length else nl + 1
        }
        return text.substring(start, end)
    }
}
