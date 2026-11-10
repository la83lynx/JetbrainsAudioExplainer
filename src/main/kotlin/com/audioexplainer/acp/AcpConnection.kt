package com.audioexplainer.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** A JSON-RPC error: from the agent's reply, or the connection's own (closed, timed out). */
class AcpException(val code: Int, message: String) : RuntimeException(message)

/**
 * **The Agent Client Protocol's wire** (`Models.B.md` B2b): JSON-RPC 2.0 over an agent's stdio, one message per line.
 *
 * Three kinds of traffic: our requests and their responses, matched by id ([request]); the agent's notifications
 * ([onNotification], `session/update` mostly); and the agent's requests to us ([onRequest] —
 * `session/request_permission`, `fs/read_text_file`). Both callbacks run **on the reader thread, in the order the
 * lines arrived**, which is what keeps a tool's `tool_call_update` ahead of the permission it then asks for. They
 * must not block on this connection. A callback that throws [AcpException] answers with that error; anything else
 * is an internal error. An unknown method should throw `-32601` (method not found), which agents treat as "the
 * client does not do that".
 *
 * Every message is passed to [log] with [scrub] applied first — the key travels in the child's environment, never on
 * this wire, but a log is no place to find out otherwise.
 */
class AcpConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val onRequest: (method: String, params: JsonObject?) -> JsonElement,
    private val onNotification: (method: String, params: JsonObject?) -> Unit,
    private val log: (String) -> Unit = {},
    private val scrub: (String) -> String = { it },
) {
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonElement>>()
    private val writeLock = Any()

    @Volatile
    var closed = false
        private set

    /** Why the agent's output ended, once it has. */
    @Volatile
    var closeReason: String? = null
        private set

    fun start(name: String = "Audio Explainer ACP reader") {
        Thread({ readLoop() }, name).apply { isDaemon = true; start() }
    }

    /** Send a request and wait for its result; an error reply, a closed connection or [timeoutMs] throws [AcpException]. */
    fun request(method: String, params: JsonObject, timeoutMs: Long = Long.MAX_VALUE): JsonElement {
        val future = requestAsync(method, params)
        return try {
            if (timeoutMs == Long.MAX_VALUE) future.get() else future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw AcpException(TIMED_OUT, "$method: no answer within ${timeoutMs / 1000}s")
        } catch (e: ExecutionException) {
            throw (e.cause as? AcpException) ?: AcpException(INTERNAL_ERROR, e.cause?.message ?: "$method failed")
        }
    }

    /** Send a request; the future completes with its result, or exceptionally with an [AcpException]. */
    fun requestAsync(method: String, params: JsonObject): CompletableFuture<JsonElement> {
        val id = ids.incrementAndGet()
        val future = CompletableFuture<JsonElement>()
        pending[id] = future
        if (closed) {
            pending.remove(id)
            future.completeExceptionally(AcpException(CLOSED, closeReason ?: "the agent has exited"))
            return future
        }
        write(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
        return future
    }

    fun notify(method: String, params: JsonObject) {
        write(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) })
    }

    /** Close our side: the agent sees end of input. Pending requests fail. */
    fun close() {
        runCatching { output.close() }
        failPending("the connection was closed")
    }

    private fun write(msg: JsonObject) {
        val line = msg.toString()
        log("→ " + scrub(line))
        synchronized(writeLock) {
            try {
                output.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
                output.flush()
            } catch (e: Exception) {
                // The agent has gone; its reader will see the end and fail what is pending.
                log("write failed: ${e.message}")
            }
        }
    }

    private fun readLoop() {
        try {
            input.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    log("← " + scrub(line))
                    val msg = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                    if (msg == null) {
                        log("not a JSON-RPC message: " + scrub(line.take(200)))
                        continue
                    }
                    dispatch(msg)
                }
            }
            closeReason = "the agent closed its output"
        } catch (e: Exception) {
            closeReason = "reading from the agent failed: ${e.message}"
        } finally {
            closed = true
            failPending(closeReason ?: "the agent has exited")
        }
    }

    private fun dispatch(msg: JsonObject) {
        val method = (msg["method"] as? JsonPrimitive)?.contentOrNull
        val id = msg["id"]
        val params = msg["params"] as? JsonObject
        when {
            method == null && id != null -> {
                val future = (id as? JsonPrimitive)?.intOrNull?.let(pending::remove) ?: return
                val error = msg["error"] as? JsonObject
                if (error != null) {
                    val code = (error["code"] as? JsonPrimitive)?.intOrNull ?: INTERNAL_ERROR
                    future.completeExceptionally(AcpException(code, (error["message"] as? JsonPrimitive)?.contentOrNull ?: "error $code"))
                } else {
                    future.complete(msg["result"] ?: JsonNull)
                }
            }
            method != null && id != null && id !is JsonNull -> {
                val reply = try {
                    val result = onRequest(method, params)
                    buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
                } catch (e: AcpException) {
                    errorReply(id, e.code, e.message ?: "error")
                } catch (e: Exception) {
                    errorReply(id, INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
                }
                write(reply)
            }
            method != null -> runCatching { onNotification(method, params) }
                .onFailure { log("notification $method failed: ${it.message}") }
        }
    }

    private fun errorReply(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }

    private fun failPending(why: String) {
        for (id in pending.keys.toList()) pending.remove(id)?.completeExceptionally(AcpException(CLOSED, why))
    }

    companion object {
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
        /** Ours, not JSON-RPC's: the agent exited, or closed its output. */
        const val CLOSED = -32000
        const val TIMED_OUT = -32001

        internal val json = Json { ignoreUnknownKeys = true }
    }
}
