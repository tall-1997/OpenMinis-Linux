package com.openminis.app.ui.chat

import java.text.BreakIterator

/**
 * [#467] Chunking for the "Show full thinking" viewer ([ThinkingFullDialog]).
 *
 * The inline thinking block shows only its last 8000 characters, so the
 * viewer is the one place the whole text is drawn - up to hundreds of
 * thousands of characters. Compose `Text` measures its whole string, so the
 * text is cut into chunks, each its own lazy item, and only a few are put on
 * screen at first; more follow as the user scrolls near the end. The tuning
 * matches iOS ToolLiveSheet ([T-ios-tool-result-lazy-render] /
 * [T-ios-tool-sheet-watchdog]): 40 lines a chunk, 5 chunks to start (at most
 * 10 KB), 5 more per batch.
 *
 * A chunk is cut after its 40th line, or earlier at [CHUNK_MAX_CHARS]: a
 * single line of 50,000 characters (model output without newlines is
 * common) must not become one huge item. Such a cut falls on the last
 * newline inside the limit, else on a grapheme boundary, so an emoji, a
 * surrogate pair or a CJK character is never split.
 *
 * Pure Kotlin (java.text only) so it runs in JVM unit tests.
 */
object ThinkingChunking {
    /** Lines per chunk (iOS LazyRenderTuning.chunkLines). */
    const val CHUNK_LINES = 40

    /** A chunk is never longer than this, whatever its line count. */
    const val CHUNK_MAX_CHARS = 4000

    /** Chunks revealed when the viewer opens (iOS initialChunks). */
    const val INITIAL_CHUNKS = 5

    /** ...unless they reach this many characters first (iOS initialByteCap). */
    const val INITIAL_CHAR_CAP = 10 * 1024

    /** Chunks added per batch while the viewer keeps up (iOS batchChunks). */
    const val BATCH_CHUNKS = 5

    /**
     * A batch whose frame took longer than this switches the viewer to one
     * chunk at a time, on request (the watchdog, see [paceAfter]).
     */
    const val SLOW_BATCH_MS = 500L

    /** Character range `[start, end)` of one chunk. */
    data class Chunk(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    /** How the viewer reveals more: [batch] chunks at once, [auto] = as the end scrolls into view. */
    data class Pacing(val batch: Int = BATCH_CHUNKS, val auto: Boolean = true)

    /** The chunks of [text], in order, covering it exactly. Empty text has none. */
    fun chunks(text: String, lines: Int = CHUNK_LINES, maxChars: Int = CHUNK_MAX_CHARS): List<Chunk> {
        require(lines > 0 && maxChars > 0)
        if (text.isEmpty()) return emptyList()
        val graphemes = BreakIterator.getCharacterInstance().apply { setText(text) }
        val out = ArrayList<Chunk>()
        var pos = 0
        while (pos < text.length) {
            val limit = minOf(text.length, pos + maxChars)
            // After the `lines`-th newline, if it comes before the limit.
            var end = -1
            var seen = 0
            var i = pos
            while (i < limit) {
                if (text[i] == '\n' && ++seen == lines) {
                    end = i + 1
                    break
                }
                i++
            }
            if (end < 0) {
                end = if (limit == text.length) {
                    limit
                } else {
                    val nl = text.lastIndexOf('\n', limit - 1)
                    if (nl >= pos) nl + 1 else graphemeCut(text, graphemes, pos, limit)
                }
            }
            out += Chunk(pos, end)
            pos = end
        }
        return out
    }

    /**
     * The last grapheme boundary in `(pos, limit]`; the end of the first
     * grapheme when it alone is longer. Boundaries inside an emoji sequence
     * (a ZWJ family, a skin tone, a variation selector) are skipped too: the
     * JDK's character iterator does not know them (Android's ICU one does).
     */
    private fun graphemeCut(text: String, graphemes: BreakIterator, pos: Int, limit: Int): Int {
        var b = if (graphemes.isBoundary(limit)) limit else graphemes.preceding(limit)
        while (b > pos && joined(text, b)) b = graphemes.preceding(b)
        if (b > pos) return b
        b = graphemes.following(pos)
        while (b < text.length && joined(text, b)) b = graphemes.following(b)
        return b
    }

    /** Whether a cut at [at] would split an emoji sequence. */
    private fun joined(text: String, at: Int): Boolean {
        if (at <= 0 || at >= text.length) return false
        val next = text.codePointAt(at)
        return text[at - 1] == '\u200D' ||
            next == 0x200D ||
            next in 0xFE00..0xFE0F ||
            next in 0x1F3FB..0x1F3FF ||
            next in 0xE0020..0xE007F
    }

    /** Text of chunk [index]. */
    fun chunkText(text: String, chunks: List<Chunk>, index: Int): String =
        chunks[index].let { text.substring(it.start, it.end) }

    /** Chunks shown when the viewer opens: up to [INITIAL_CHUNKS], stopping once [INITIAL_CHAR_CAP] is reached. */
    fun initialCount(chunks: List<Chunk>): Int {
        if (chunks.isEmpty()) return 0
        var count = 0
        var chars = 0
        for (c in chunks.take(INITIAL_CHUNKS)) {
            chars += c.length
            count++
            if (chars >= INITIAL_CHAR_CAP) break
        }
        return count
    }

    /** Chunks shown after one more batch. */
    fun nextCount(revealed: Int, total: Int, pacing: Pacing = Pacing()): Int =
        minOf(total, revealed + pacing.batch)

    fun hasMore(revealed: Int, total: Int): Boolean = revealed < total

    /** Characters on screen with [revealed] chunks shown: `[0, end)`. */
    fun renderedRange(chunks: List<Chunk>, revealed: Int): IntRange =
        if (revealed <= 0 || chunks.isEmpty()) IntRange.EMPTY else 0 until chunks[minOf(revealed, chunks.size) - 1].end

    /**
     * The watchdog: a batch whose first frame took [frameMs] or more slows
     * the viewer to one chunk per tap, so a device that cannot keep up is
     * never frozen by automatic loading. It does not speed up again.
     */
    fun paceAfter(pacing: Pacing, frameMs: Long): Pacing =
        if (frameMs >= SLOW_BATCH_MS) Pacing(batch = 1, auto = false) else pacing

    /** What "Copy All" copies: the whole thinking text, not what is on screen. */
    fun copyText(full: String): String = full
}
