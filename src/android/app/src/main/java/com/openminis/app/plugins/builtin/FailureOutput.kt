package com.openminis.app.plugins.builtin

/**
 * [T-builtin-failure-diagnosis] Turns raw guest output into the one line a
 * settings row can honestly show as a failure reason.
 *
 * Why this exists. PRoot prints its own error when a command dies, and then a
 * multi-KB talloc leak report as it unwinds:
 *
 * ```
 * node gate: ...real cause...
 * full talloc report on 'tail' (total  1374 bytes in 61 blocks)
 * /bin/sh contains      576 bytes in     9 blocks (ref 0) 0x…
 * HandlerEntry contains  32 bytes in     2 blocks (ref 0) 0x…
 * ```
 *
 * The installer used to summarize with `lines.takeLast(8)`, so once a run hit
 * that report the "diagnosis" the user saw was nothing but allocator rows —
 * "node gate: HandlerEntry contains 32 bytes in 2 blocks" for a download that
 * had actually timed out thousands of lines earlier. The real cause sits at
 * the HEAD of the stream, the dump at the tail; a tail-only summary is exactly
 * the wrong end.
 *
 * So: drop allocator bookkeeping and keep the last few remaining lines (a
 * compiler/curl failure prints its cause last). When the stream held nothing
 * but the dump, the summary is EMPTY — the caller then reports the stage alone
 * instead of printing a memory dump as if it were a reason.
 *
 * Pure, no Context — the shape is pinned by FailureOutputTest.
 */
internal object FailureOutput {

    /** One allocator row: `<name> contains N bytes in M blocks (ref K)`. */
    private val TALLOC_ROW = Regex("""\bcontains\s+\d+\s+bytes\s+in\s+\d+\s+blocks?\b""")

    /** The dump's own header line ("full talloc report on '…'"). */
    private val TALLOC_HEADER = Regex("""^\s*(?:full\s+)?talloc report\b""")

    /** True when a line is allocator bookkeeping rather than a cause. */
    fun isNoise(line: String): Boolean =
        TALLOC_ROW.containsMatchIn(line) || TALLOC_HEADER.containsMatchIn(line)

    /**
     * The last [maxLines] meaningful lines joined with " / ", capped at
     * [maxChars]. Empty when every line was allocator bookkeeping: an empty
     * reason is honest, a dump row is not.
     */
    fun summarize(
        lines: List<String>,
        maxLines: Int = 3,
        maxChars: Int = 200,
    ): String {
        val meaningful = lines.filter { it.isNotBlank() && !isNoise(it) }
        return meaningful.takeLast(maxLines).joinToString(" / ").trim().take(maxChars)
    }

    /**
     * Sanitizes a diagnosis that was persisted before this filter existed.
     * A stored reason that is pure allocator noise is worthless (it describes
     * PRoot's bookkeeping, not the install), so it is dropped and the caller
     * falls back to a generic "install was interrupted" label.
     */
    fun sanitizeStoredDiagnosis(raw: String): String =
        if (raw.isBlank() || isNoise(raw)) "" else raw
}
