package com.openminis.app.sandbox

/**
 * Retains the head and a rolling tail of a command's output. The reader
 * still drains the pipe (a full pipe would stall the guest and look like
 * a hang); only the string handed back to the model is bounded.
 *
 * [truncated] is true only after a char has actually been discarded. A
 * buffer that still holds the whole stream reports the concatenation of
 * head and tail with no marker.
 */
internal class BoundedOutputBuffer(
    private val headChars: Int = HEAD_CHARS,
    private val tailChars: Int = TAIL_CHARS,
) {
    private val head = StringBuilder()
    private val tail = StringBuilder()
    var dropped: Int = 0
        private set

    val truncated: Boolean get() = dropped > 0

    fun append(text: String) {
        if (text.isEmpty()) return
        val chars = text.toCharArray()
        append(chars, 0, chars.size)
    }

    fun append(buf: CharArray, off: Int, len: Int) {
        if (len <= 0) return
        var i = off
        val end = off + len
        if (head.length < headChars) {
            val take = minOf(headChars - head.length, end - i)
            head.append(buf, i, take)
            i += take
        }
        if (i >= end) return
        tail.append(buf, i, end - i)
        if (tail.length > tailChars) {
            val extra = tail.length - tailChars
            tail.delete(0, extra)
            dropped += extra
        }
    }

    fun appendLine(text: String) {
        append(text)
        append("\n")
    }

    override fun toString(): String = when {
        tail.isEmpty() -> head.toString()
        !truncated -> head.toString() + tail.toString()
        else -> head.toString() +
            "\n[output truncated, dropped $dropped chars; tail follows]\n" +
            tail.toString()
    }

    companion object {
        const val HEAD_CHARS = 64 * 1024
        const val TAIL_CHARS = 64 * 1024
        const val MAX_LINE_CHARS = 8 * 1024
    }
}
