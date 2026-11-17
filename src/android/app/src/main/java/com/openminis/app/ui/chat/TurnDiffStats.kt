package com.openminis.app.ui.chat

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Line-level additions and removals for one changed file. */
data class FileDiffStat(
    val added: Int,
    val removed: Int,
) {
    val total: Int get() = added + removed
}

/** Pure line-diff helpers used by the turn changes card. */
object TurnDiffStats {
    const val MAX_BYTES = 1_000_000
    private const val MAX_LINES = 20_000
    private const val MAX_EDIT_DISTANCE = 2_000

    /**
     * Computes a text diff. A null [before] means the file was created in the
     * turn; a null [after] means it was deleted. A missing pre-image for an
     * existing file is treated as unknown and returns null.
     */
    fun compute(
        before: ByteArray?,
        existedBefore: Boolean,
        after: ByteArray?,
    ): FileDiffStat? {
        if (existedBefore && before == null) return null
        if (before != null && !isText(before)) return null
        if (after != null && !isText(after)) return null
        if (before != null && before.size > MAX_BYTES) return null
        if (after != null && after.size > MAX_BYTES) return null

        val beforeLines = before?.let(::decodeLines)
        val afterLines = after?.let(::decodeLines)
        if (before != null && beforeLines == null) return null
        if (after != null && afterLines == null) return null
        val old = beforeLines.orEmpty()
        val new = afterLines.orEmpty()
        val distance = if (old.size + new.size <= MAX_LINES) {
            editDistance(old, new, MAX_EDIT_DISTANCE)
        } else {
            null
        }
        if (distance == null) return null
        val delta = new.size - old.size
        return FileDiffStat(
            added = ((distance + delta) / 2).coerceAtLeast(0),
            removed = ((distance - delta) / 2).coerceAtLeast(0),
        )
    }

    /** Myers forward search. Returns the minimum insert/delete edit distance. */
    internal fun editDistance(old: List<String>, new: List<String>, cap: Int): Int? {
        val n = old.size
        val m = new.size
        val max = n + m
        if (max == 0) return 0
        val offset = max + 1
        val v = IntArray(max * 2 + 3)
        for (d in 0..minOf(cap, max)) {
            var k = -d
            while (k <= d) {
                val index = offset + k
                var x = if (k == -d || (k != d && v[index - 1] < v[index + 1])) {
                    v[index + 1]
                } else {
                    v[index - 1] + 1
                }
                var y = x - k
                while (x < n && y >= 0 && y < m && old[x] == new[y]) {
                    x++
                    y++
                }
                v[index] = x
                if (x >= n && y >= m) return d
                k += 2
            }
        }
        return null
    }

    private fun isText(bytes: ByteArray): Boolean = bytes.none { it == 0.toByte() }

    private fun decodeLines(bytes: ByteArray): List<String>? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .let { lines ->
                if (lines.size == 1 && lines[0].isEmpty()) {
                    emptyList()
                } else if (lines.lastOrNull().isNullOrEmpty()) {
                    lines.dropLast(1)
                } else {
                    lines
                }
            }
    }.getOrNull()
}
