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

    /**
     * [T-turn-card-diff-detail] Line-level diff for the card's detail view:
     * Myers BACKWARD trace from the terminal state of [editDistance] — same
     * bounds (MAX_BYTES / MAX_LINES / MAX_EDIT_DISTANCE), same text/binary
     * guards as [compute]; a null result (binary / oversized / distance over
     * cap) means "no inline diff, show the file in the previewer instead".
     * The trace is O(D) with the same v-array walk, so it adds no asymptotic
     * cost over the distance computation.
     */
    fun diffLines(
        before: ByteArray?,
        existedBefore: Boolean,
        after: ByteArray?,
    ): List<DiffLine>? {
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
        val n = old.size
        val m = new.size
        val max = n + m
        if (max == 0) return emptyList()
        val offset = max + 1
        // [T-turn-card-diff-detail] Parent-pointer Myers: ONE forward pass
        // records, for every (d, k) endpoint, the full node (endpoint + parent
        // + move kind); the backward walk then replays the recorded path. The
        // reused v-array trick alone cannot trace a path (later rounds
        // overwrite earlier endpoints), so nodes[] keeps one row per round —
        // D ≤ MAX_EDIT_DISTANCE = 2000 bounds the table.
        data class Node(val x: Int, val y: Int, val px: Int, val py: Int, val added: Boolean?)
        val nodes = Array(minOf(MAX_EDIT_DISTANCE, max) + 1) { d -> arrayOfNulls<Node>(2 * d + 1) }
        val v = IntArray(max * 2 + 3)
        val vNode = arrayOfNulls<Node>(max * 2 + 3)
        var foundD = -1
        for (d in 0..minOf(MAX_EDIT_DISTANCE, max)) {
            var k = -d
            while (k <= d) {
                val index = offset + k
                val node: Node = when {
                    k == -d || (k != d && v[index - 1] < v[index + 1]) -> {
                        // Down move from k+1: new[y] ADDED.
                        val px = v[index + 1]
                        Node(px, px - (k + 1) + 1, px, px - (k + 1), true)
                    }
                    else -> {
                        // Right move from k-1: old[x] REMOVED.
                        val px = v[index - 1]
                        Node(px + 1, px - (k - 1), px, px - (k - 1), false)
                    }
                }
                var x = node.x
                var y = node.y
                while (x < n && y >= 0 && y < m && old[x] == new[y]) {
                    x++
                    y++
                }
                val finalNode = Node(x, y, node.px, node.py, node.added)
                v[index] = x
                vNode[index] = finalNode
                nodes[d][k + d] = finalNode
                if (x >= n && y >= m) { foundD = d; break }
                k += 2
            }
            if (foundD >= 0) break
        }
        if (foundD < 0) return null
        // Backward walk from the terminal node of round foundD at k = n - m.
        // [T-turn-card-diff-detail] Snake bounds are GEOMETRIC, not
        // match-based: the edit-after position is exactly (px, py+1) for an
        // added line / (px+1, py) for a removed one, and the forward snake
        // ran from there to (x, y). A match-based backward loop would run
        // THROUGH the edit position whenever old[px-1] == new[py] (the line
        // before the parent equals the added line), swallowing the edit and
        // corrupting the script — the oracle test caught exactly that.
        val out = ArrayList<DiffLine>(foundD * 2 + 8)
        var node = nodes[foundD][n - m + foundD]!!
        var d = foundD
        while (d > 0) {
            val px = node.px
            val py = node.py
            val ex: Int
            val ey: Int
            when (node.added) {
                true -> { ex = px; ey = py + 1 }
                else -> { ex = px + 1; ey = py }
            }
            var sx = node.x
            var sy = node.y
            while (sx > ex || sy > ey) {
                if (sx > ex && sy > ey) {
                    out.add(DiffLine(DiffLine.Kind.CONTEXT, old[sx - 1]))
                    sx--; sy--
                } else if (sx > ex) {
                    sx--
                    out.add(DiffLine(DiffLine.Kind.CONTEXT, old[sx]))
                } else {
                    sy--
                    out.add(DiffLine(DiffLine.Kind.CONTEXT, new[sy]))
                }
            }
            when (node.added) {
                true -> out.add(DiffLine(DiffLine.Kind.ADDED, new[ey - 1]))
                false -> out.add(DiffLine(DiffLine.Kind.REMOVED, old[ex - 1]))
                null -> {}
            }
            node = nodes[d - 1][px - py + (d - 1)]
                ?: break // parent beyond a broken round — fall through to the tails
            d--
        }
        // Head snake (round 0): the run from (0, 0) to the round-0 endpoint.
        var sx = node.x
        var sy = node.y
        while (sx > 0 && sy > 0 && old[sx - 1] == new[sy - 1]) {
            sx--; sy--
            out.add(DiffLine(DiffLine.Kind.CONTEXT, old[sx]))
        }
        while (sx > 0) { sx--; out.add(DiffLine(DiffLine.Kind.REMOVED, old[sx])) }
        while (sy > 0) { sy--; out.add(DiffLine(DiffLine.Kind.ADDED, new[sy])) }
        out.reverse()
        return out
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

/**
 * One rendered line of a file's turn diff: [REMOVED]/[ADDED]/[CONTEXT] with
 * its text. Produced by [TurnDiffStats.diffLines] for the card's detail view.
 */
data class DiffLine(val kind: Kind, val text: String) {
    enum class Kind { REMOVED, ADDED, CONTEXT }
}

