package com.openminis.app.sandbox

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * UTF-8 decoder that keeps an incomplete sequence across [read] boundaries.
 *
 * `InputStream.read` returns whatever is buffered, not a character. Decoding
 * each chunk with `String(bytes, UTF_8)` turns a CJK character split on that
 * boundary into U+FFFD on both sides.
 */
internal class Utf8ChunkDecoder {
    private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    fun decode(chunk: ByteArray, length: Int = chunk.size, endOfInput: Boolean = false): String {
        decoder.reset()
        val n = length.coerceIn(0, chunk.size)
        val merged = ByteArray(pending.size + n)
        if (pending.isNotEmpty()) System.arraycopy(pending, 0, merged, 0, pending.size)
        if (n > 0) System.arraycopy(chunk, 0, merged, pending.size, n)
        val input = ByteBuffer.wrap(merged)
        val output = CharBuffer.allocate((merged.size + 1).coerceAtLeast(1))
        val sb = StringBuilder()
        while (true) {
            val result = decoder.decode(input, output, endOfInput)
            output.flip()
            sb.append(output)
            output.clear()
            if (result.isOverflow) continue
            if (endOfInput) {
                while (true) {
                    val flush = decoder.flush(output)
                    output.flip()
                    sb.append(output)
                    output.clear()
                    if (!flush.isOverflow) break
                }
                pending = ByteArray(0)
            } else {
                pending = ByteArray(input.remaining())
                if (pending.isNotEmpty()) input.get(pending)
            }
            break
        }
        return sb.toString()
    }

    fun finish(): String = decode(ByteArray(0), 0, endOfInput = true)
}

/**
 * Finds `__MINIS_DONE_<marker>_EXIT_<code>__` even when it is split across reads.
 *
 * A suffix that is only a prefix of the marker is held back so it is not
 * appended to the command output and then missed on the next chunk.
 */
internal class MarkerFramer(marker: String) {
    private val prefix = "__MINIS_DONE_${marker}_EXIT_"
    private val full = Regex("__MINIS_DONE_${Regex.escape(marker)}_EXIT_(\\d+)__")
    private val carry = StringBuilder()

    data class Step(val output: String, val completed: Boolean, val exitCode: Int)

    fun push(text: String, endOfInput: Boolean = false): Step {
        if (text.isNotEmpty()) carry.append(text)
        val match = full.find(carry)
        if (match != null) {
            val output = carry.substring(0, match.range.first)
            val code = match.groupValues[1].toIntOrNull() ?: -1
            carry.clear()
            return Step(output, completed = true, exitCode = code)
        }
        val heldFrom = holdIndex(carry.toString(), prefix)
        val output = carry.substring(0, heldFrom)
        carry.delete(0, heldFrom)
        if (endOfInput && carry.isNotEmpty()) {
            val rest = output + carry.toString()
            carry.clear()
            return Step(rest, completed = false, exitCode = -1)
        }
        return Step(output, completed = false, exitCode = -1)
    }

    private fun holdIndex(text: String, markerPrefix: String): Int {
        val idx = text.indexOf(markerPrefix)
        if (idx >= 0) return idx
        val max = minOf(text.length, markerPrefix.length - 1)
        for (len in max downTo 1) {
            if (markerPrefix.startsWith(text.substring(text.length - len))) {
                return text.length - len
            }
        }
        return text.length
    }
}
