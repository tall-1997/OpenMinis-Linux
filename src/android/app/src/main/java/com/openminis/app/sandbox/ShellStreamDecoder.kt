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
 *
 * [T-android-stale-stream-gate] The framer also gates on a BEGIN line
 * (`__MINIS_GO_<marker>__`) that the command wrapper echoes BEFORE running
 * anything. Bytes that arrive before it belong to no current command — late
 * bash job-control notifications ("… Killed" from the previous command's
 * watchdog reap), a late offload response, or a replayed previous payload —
 * and field evidence showed such a payload re-delivered under four different
 * later tool-call ids in one evening. Everything before BEGIN is dropped here
 * (a bounded sample is kept for diagnostics); only the current command's own
 * bytes are framed. When the shell dies before BEGIN (proot never reached the
 * command), the buffered bytes are flushed instead of dropped so the death
 * path keeps proot's last words.
 */
internal class MarkerFramer(marker: String) {
    private val prefix = "__MINIS_DONE_${marker}_EXIT_"
    private val full = Regex("__MINIS_DONE_${Regex.escape(marker)}_EXIT_(\\d+)__")
    private val beginPrefix = "__MINIS_GO_${marker}__"
    private val carry = StringBuilder()
    private var armed = false
    private var preBeginDropped = 0
    private val preBeginSample = StringBuilder()
    private val preBeginAll = StringBuilder()

    // [T-p1-3-shell-auth-frames] 首条命令的 bootstrap 在 pre-BEGIN 窗口回显一次
    // 会话秘密（__MINIS_AUTH_<hex>__），framer 从丢弃流里捞出来交还宿主——秘密
    // 因此只在「尚无任何 guest 子进程存在」的时刻走过 stdout，之后所有帧标签
    // 都用它认证，竞争 stdin 的杂散进程伪造不出合法帧。
    private val authRegex = Regex("__MINIS_AUTH_([0-9A-Za-z]{8,80})__")
    private val authHoldPrefix = "__MINIS_AUTH_"

    /** 会话秘密（bootstrap 回显），未捕获时为 null。宿主只读。 */
    var authTag: String? = null
        private set

    data class Step(val output: String, val completed: Boolean, val exitCode: Int)

    /** Bytes dropped before the BEGIN line, for post-mortem diagnostics. */
    fun preBeginDroppedChars(): Int = preBeginDropped

    /** First bytes dropped before BEGIN (bounded) — identifies the emitter. */
    fun preBeginDroppedSample(): String = preBeginSample.toString()

    fun push(text: String, endOfInput: Boolean = false): Step {
        if (text.isNotEmpty()) carry.append(text)
        if (!armed) {
            captureAuth()
            val beginIdx = carry.indexOf(beginPrefix)
            if (beginIdx < 0) {
                if (endOfInput) {
                    // Shell died before the command started: none of this is
                    // this command's output, but it IS proot's dying words —
                    // flush the buffered pre-begin bytes for the death path
                    // (readLoop completes the callback with this output). The
                    // final chunk never went through noteDropped, so fold it
                    // in before flushing.
                    preBeginAll.append(carry)
                    if (preBeginAll.length > PRE_BEGIN_ALL_MAX) {
                        preBeginAll.delete(PRE_BEGIN_ALL_MAX, preBeginAll.length)
                    }
                    val rest = preBeginAll.toString()
                    preBeginAll.setLength(0)
                    carry.setLength(0)
                    return Step(rest, completed = false, exitCode = -1)
                }
                // [T-p1-3-shell-auth-frames] begin 与 AUTH 两个前缀的部分尾巴都要
                // 保住：AUTH 行跨读分割时，若把半个 AUTH 前缀当噪声丢掉，握手就
                // 永远配不齐（降级 legacy）。取两者更靠前的 hold 点。
                val heldFrom = minOf(
                    holdIndex(carry.toString(), beginPrefix),
                    holdIndex(carry.toString(), authHoldPrefix),
                )
                noteDropped(heldFrom)
                carry.delete(0, heldFrom)
                return Step("", completed = false, exitCode = -1)
            }
            noteDropped(beginIdx)
            carry.delete(0, beginIdx + beginPrefix.length)
            if (carry.startsWith("\r\n")) carry.delete(0, 2) else if (carry.startsWith("\n")) carry.deleteCharAt(0)
            armed = true
            preBeginAll.setLength(0)
        }
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

    /**
     * [T-p1-3-shell-auth-frames] 从 pre-BEGIN 缓冲里捕获会话秘密并连同行尾删掉
     * （不进丢弃样本、不进死亡缓冲）。每次 push 至多扫一次、命中一次后短路：
     * pre-BEGIN 窗口的 carry 被持续裁剪，扫描成本有界。
     */
    private fun captureAuth() {
        if (authTag != null) return
        val text = carry.toString()
        if (!text.contains("__MINIS_AUTH_")) return
        val match = authRegex.find(text) ?: return
        authTag = match.groupValues[1]
        val delEnd = run {
            var end = match.range.last + 1
            if (carry.length >= end + 2 && carry.substring(end, end + 2) == "\r\n") end + 2
            else if (carry.length >= end + 1 && carry[end] == '\n') end + 1
            else end
        }
        carry.delete(match.range.first, delEnd)
    }

    private fun noteDropped(upto: Int) {
        if (upto <= 0) return
        preBeginDropped += upto
        if (preBeginSample.length < PRE_BEGIN_SAMPLE_MAX) {
            preBeginSample.append(carry.substring(0, minOf(upto, PRE_BEGIN_SAMPLE_MAX - preBeginSample.length)))
        }
        if (preBeginAll.length < PRE_BEGIN_ALL_MAX) {
            preBeginAll.append(carry.substring(0, minOf(upto, PRE_BEGIN_ALL_MAX - preBeginAll.length)))
        }
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

    private companion object {
        const val PRE_BEGIN_SAMPLE_MAX = 200
        const val PRE_BEGIN_ALL_MAX = 4096
    }
}
