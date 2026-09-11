package com.audioexplainer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Plays streamed TTS PCM natively via `javax.sound.sampled`, independent of the sidebar webview — used by
 * the voice-editor dialog's "Test" button so a preview works while narration is paused (no round-trip to
 * Web Audio). Receives base64 float32-LE chunks (the server's wire format), converts to signed 16-bit PCM,
 * and writes them to a [SourceDataLine] opened lazily at the first chunk's sample rate.
 *
 * [onChunk] runs on [TtsService]'s pooled thread (its `write` blocks on buffer space, which is fine there);
 * [stop] may be called from the EDT to cancel — the `runCatching` guards the cross-thread close race.
 *
 * [onFinished] (if given) fires exactly once when playback ends on its own — natural end (after the line
 * has drained, i.e. the sound has actually finished) or an error — but **not** when [stop] cancels it
 * (the caller drove that and already knows). Runs on the pooled thread; marshal to the EDT if it touches
 * UI. Lets the dialog flip its Test/Stop button back to "play" when a preview completes.
 */
class NativePcmPlayer(private val onFinished: (() -> Unit)? = null) : TtsService.TtsListener {
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var stopped = false
    private val finished = AtomicBoolean(false)

    override fun onChunk(
        base64: String,
        sampleRate: Int,
        sentenceIndex: Int,
        sentenceWords: Int,
        sentenceText: String,
    ) {
        if (stopped) return
        val src = Base64.getDecoder().decode(base64)
        val n = src.size / 4
        if (n == 0) return
        val fb = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteArray(n * 2)
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        repeat(n) {
            val v = (fb.float * 32767f).coerceIn(-32768f, 32767f)
            ob.putShort(v.toInt().toShort())
        }
        val ln = ensureLine(sampleRate) ?: return
        runCatching { ln.write(out, 0, out.size) }
    }

    override fun onEnd() {
        val ln = line
        runCatching { ln?.drain(); ln?.stop(); ln?.close() }
        line = null
        if (!stopped) fireFinished() // drained naturally → notify (unless we were cancelled)
    }

    override fun onError(message: String) {
        stop()
        fireFinished()
    }

    /** Abort playback immediately (a new Test, or the dialog closed). Safe from any thread. */
    fun stop() {
        stopped = true
        val ln = line ?: return
        runCatching { ln.stop(); ln.flush(); ln.close() }
        line = null
    }

    private fun fireFinished() {
        if (finished.compareAndSet(false, true)) onFinished?.invoke()
    }

    private fun ensureLine(sampleRate: Int): SourceDataLine? {
        line?.let { return it }
        if (stopped) return null
        return try {
            // 16-bit signed mono, little-endian — universally supported (avoids relying on PCM_FLOAT).
            val fmt = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            AudioSystem.getSourceDataLine(fmt).apply { open(fmt); start(); line = this }
        } catch (e: Exception) {
            null
        }
    }
}
