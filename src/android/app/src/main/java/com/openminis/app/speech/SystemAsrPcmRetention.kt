package com.openminis.app.speech

/**
 * [T-voice-asr-failure-retry-prompt] Keeps a copy of what the recognizer is
 * fed, so a failed take can be retried instead of re-dictated. Extracted
 * from [SystemSpeechRecognitionEngine] so the engine file stays under the
 * architecture ceiling. No behaviour change.
 *
 * @param maxBytes hard cap on retained audio; writes past the cap are
 * dropped (a take longer than the cap simply is not retryable).
 */
internal class RetainedPcmBuffer(private val maxBytes: Int) {
    private val chunks = ArrayList<ByteArray>()
    private var totalBytes = 0
    private val lock = Any()

    fun reset() = synchronized(lock) {
        chunks.clear()
        totalBytes = 0
    }

    fun retain(bytes: ByteArray, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            if (totalBytes + len > maxBytes) return
            chunks.add(bytes.copyOf(len))
            totalBytes += len
        }
    }

    fun sizeBytes(): Int = synchronized(lock) { totalBytes }

    /** Concatenated copy of everything retained so far. */
    fun snapshot(): ByteArray = synchronized(lock) {
        val out = ByteArray(totalBytes)
        var pos = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, pos, c.size)
            pos += c.size
        }
        out
    }
}
