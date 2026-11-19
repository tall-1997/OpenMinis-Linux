package com.openminis.app.ui.chat

import org.json.JSONObject

/**
 * [T-session-journal] Append-only, per-session record of WHAT THE MODEL WAS
 * SHOWN, independent of the mutable in-memory projections that produced it.
 *
 * WHY this exists: both P0 defects fixed in this branch (unsummarized rounds
 * silently dropped by the tail budget; the compact gate measuring a slice the
 * budget had already shrunk) could only be found by reading code and reasoning
 * across two data paths — `agentHistory` and the request projection. There was
 * no durable answer to "what exactly did request #42 carry?". Every later
 * roadmap item (compaction transactions, a single token authority, post-compact
 * retrieval) needs that answer as a substrate.
 *
 * Scope for this phase is deliberately narrow — **write-only shadow log**:
 *  - nothing reads it back into a request;
 *  - a failed append never affects the turn (every write is guarded);
 *  - the format is one JSON object per line, so an external tool can diff it
 *    against the live projection without linking this code.
 *
 * Once a shadow run proves `deriveMessages(journal) == agentHistory` byte for
 * byte, this becomes the substrate for reading; until then it is evidence.
 *
 * Storage: `<filesDir>/session-journal/<safe-session>.jsonl`.
 */
internal object SessionJournal {

    /** Directory name under `filesDir`. */
    internal const val DIR = "session-journal"

    /** Hard cap on the live file; older lines are dropped by rewrite. */
    internal const val MAX_LINES = 6_000

    /** Line-count cap expressed in bytes (a line is bounded by its fields). */
    private const val MAX_BYTES = 4L * 1024 * 1024

    /** [T-session-journal-byte-bound] Bytes retained after a trim (half). */
    internal const val TRIM_KEEP_BYTES = MAX_BYTES / 2

    /** Event kinds. Keep stable: a diff tool matches on these. */
    internal const val TURN_START = "turn/start"
    internal const val TURN_END = "turn/end"
    internal const val STEP_START = "step/start"
    internal const val REQUEST_ASSEMBLED = "request/assembled"
    internal const val TOOL_CALL = "tool/call"
    internal const val TOOL_RESULT = "tool/result"

    /** [T-orphan-use-report] One request was served from a REPAIRED history. */
    internal const val PAIRING_REPAIRED = "pairing/repaired"
    internal const val COMPACTION_START = "compaction/start"
    internal const val COMPACTION_SUMMARY = "compaction/summary"
    internal const val COMPACTION_END = "compaction/end"
    internal const val COMPACTION_INTERRUPTED = "compaction/interrupted"

    /** Monotonic per-process sequence, so ordering survives equal timestamps. */
    private val seq = java.util.concurrent.atomic.AtomicLong(0)

    private fun safeSession(sessionId: String): String =
        sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "_" }

    internal fun file(filesDir: java.io.File, sessionId: String): java.io.File =
        java.io.File(java.io.File(filesDir, DIR), "${safeSession(sessionId)}.jsonl")

    /**
     * Append one event. Best-effort by contract: callers must never branch on
     * the result and must never let an IO failure escape into the loop.
     *
     * @param fields flat JSON-able properties describing the event.
     */
    fun record(
        filesDir: java.io.File?,
        sessionId: String,
        kind: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        if (filesDir == null || sessionId.isBlank()) return
        runCatching {
            val target = file(filesDir, sessionId)
            target.parentFile?.mkdirs()
            val line = JSONObject().apply {
                put("v", 1)
                put("seq", seq.incrementAndGet())
                put("at", System.currentTimeMillis())
                put("kind", kind)
                for ((k, v) in fields) {
                    if (v == null) continue
                    put(k, v)
                }
            }.toString()
            // Trim by rewrite when the file would grow unbounded. Doing this
            // before the append keeps the post-append size predictable.
            // [T-session-journal-lock] The trim rewrite and the append must be ONE
            // critical section: setLength(0)+write between two appends silently
            // dropped whichever line landed in between.
            synchronized(lockFor(target)) {
                if (target.isFile && target.length() > MAX_BYTES) {
                    trim(target)
                }
                target.appendText(line + "\n", Charsets.UTF_8)
            }
        }
    }

    /** Convenience: request assembly, the load-bearing record of this phase. */
    fun recordRequest(
        filesDir: java.io.File?,
        sessionId: String,
        turn: Int,
        messages: List<com.openminis.app.data.model.LLMMessage>,
        estimatedTokens: Int,
        providerTokens: Int,
        summaryChars: Int,
        markerId: String?,
        droppedRounds: Int,
    ) {
        record(
            filesDir,
            sessionId,
            REQUEST_ASSEMBLED,
            mapOf(
                "turn" to turn,
                "messages" to messages.size,
                "estTokens" to estimatedTokens,
                "providerTokens" to providerTokens,
                "summaryChars" to summaryChars,
                "marker" to markerId,
                "droppedRounds" to droppedRounds,
                // Per-message digest so a diff can locate the first divergence
                // without copying the whole transcript into the journal.
                "digests" to messages.joinToString(",") { digestOf(it) },
            ),
        )
    }

    /**
     * Stable per-message digest: role, part kinds, and a length. Deliberately
     * NOT a content hash — the journal must not become a second copy of the
     * transcript (secrets, size), only enough to prove two projections disagree
     * and where.
     */
    internal fun digestOf(message: com.openminis.app.data.model.LLMMessage): String {
        val kinds = buildString {
            for (p in message.contentParts) {
                append(
                    when (p) {
                        is com.openminis.app.data.model.AgentContentPart.Text -> 't'
                        is com.openminis.app.data.model.AgentContentPart.ToolUse -> 'u'
                        is com.openminis.app.data.model.AgentContentPart.ToolResult -> 'r'
                        is com.openminis.app.data.model.AgentContentPart.ImageData -> 'i'
                    },
                )
            }
        }
        val head = message.role.name.take(1)
        val len = message.content.length + message.contentParts.sumOf {
            when (it) {
                is com.openminis.app.data.model.AgentContentPart.Text -> it.text.length
                is com.openminis.app.data.model.AgentContentPart.ToolUse -> 1
                is com.openminis.app.data.model.AgentContentPart.ToolResult -> it.content.length
                is com.openminis.app.data.model.AgentContentPart.ImageData -> it.data.size
            }
        }
        return "$head:$kinds:$len"
    }

    /**
     * [T-session-journal-byte-bound] Cut the file down to [TRIM_KEEP_BYTES] by
     * seeking, not by line count.
     *
     * WHY: a `request/assembled` line carries one digest per message, so a
     * 500-message request is an ~8 KB line. A line-count guard ("do not trim
     * while fewer than 3 000 lines") would therefore let the file reach tens of
     * megabytes before the first trim ran, which is the opposite of a bound.
     * Seeking to `length - target`, then advancing past the first partial line,
     * always lands on a line boundary and costs one read of the kept tail.
     */
    private val fileLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    
    /** [T-session-journal-lock] One lock per journal file (trim vs append). */
    private fun lockFor(target: java.io.File): Any =
        fileLocks.getOrPut(target.absolutePath) { Any() }
    
    private fun trim(target: java.io.File) {
        runCatching {
            val length = target.length()
            if (length <= TRIM_KEEP_BYTES) return@runCatching
            val start = (length - TRIM_KEEP_BYTES).coerceAtLeast(0L)
            val bytes = java.io.RandomAccessFile(target, "rw")
            bytes.use { raf ->
                raf.seek(start)
                // Skip the partial first line so the kept tail starts clean.
                if (start > 0) raf.readLine()
                val keepFrom = raf.filePointer
                val tail = ByteArray((length - keepFrom).toInt().coerceAtLeast(0))
                raf.seek(keepFrom)
                raf.readFully(tail)
                raf.seek(0)
                raf.setLength(0)
                raf.write(tail)
            }
        }
    }

    /**
     * Read back the newest [limit] events, oldest-last. Used by diagnostics and
     * tests only; the request path never calls this.
     */
    fun tail(filesDir: java.io.File, sessionId: String, limit: Int = 200): List<JSONObject> {
        val target = file(filesDir, sessionId)
        if (!target.isFile) return emptyList()
        val cap = limit.coerceAtLeast(1)
        return runCatching {
            // [T-session-journal-bounded-tail] Read the file tail, not the whole
            // file. `readLines()` + parse-everything + `takeLast(cap)` made every
            // caller pay for the full 4 MB budget, and one caller runs on the
            // session-load path. A byte window of BYTES_PER_LINE x cap covers the
            // requested lines in the worst case (a line is a compact JSON object)
            // and the first partial line is dropped by the parser below.
            val windowBytes = (cap.toLong() * BYTES_PER_LINE).coerceAtMost(target.length())
            val from = (target.length() - windowBytes).coerceAtLeast(0L)
            val lines = java.io.RandomAccessFile(target, "r").use { raf ->
                raf.seek(from)
                val buf = ByteArray((target.length() - from).toInt().coerceAtLeast(0))
                raf.readFully(buf)
                String(buf, Charsets.UTF_8).split('\n')
            }
            var start = 0
            // Drop the partial first line unless the window starts at 0.
            if (from > 0 && lines.isNotEmpty()) start = 1
            lines.asSequence().drop(start)
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .toList()
                .takeLast(cap)
        }.getOrDefault(emptyList())
    }

    /** Conservative per-line byte budget for [tail]'s window computation. */
    private const val BYTES_PER_LINE = 512L

    /**
     * [T-session-journal-interrupted] Compaction transactions that opened and
     * never closed. A crash between `compaction/start` and `compaction/end`
     * leaves a paid-for summary attempt with no durable result; knowing it
     * happened is what lets the next turn report "interrupted" instead of
     * silently retrying.
     */
    fun interruptedCompactions(filesDir: java.io.File, sessionId: String): List<JSONObject> {
        // [T-session-journal-scan-window] The scan must cover every byte the file
        // can hold before a trim runs (MAX_BYTES), expressed in lines because that
        // is what the reader consumes.
        // [T-session-journal-scan-window] Cover the whole file up to the TRIM
        // THRESHOLD, not just the retained size: trimming happens above MAX_BYTES,
        // so a file legitimately sits between TRIM_KEEP_BYTES and MAX_BYTES with a
        // `compaction/start` outside a TRIM_KEEP_BYTES window. Sizing the scan by
        // the retained size silently missed exactly those attempts.
        val lines = (MAX_BYTES / BYTES_PER_LINE)
            .coerceIn(64L, Int.MAX_VALUE.toLong())
            .toInt()
        val events = tail(filesDir, sessionId, limit = lines)
        val open = LinkedHashMap<String, JSONObject>()
        for (e in events) {
            val id = e.optString("compactionId", "")
            if (id.isBlank()) continue
            when (e.optString("kind")) {
                COMPACTION_START -> open[id] = e
                COMPACTION_END, COMPACTION_INTERRUPTED -> open.remove(id)
            }
        }
        return open.values.toList()
    }

    /**
     * [T-session-journal-consistency] The newest assembled request's
     * per-message digests, or null when no request has been recorded.
     *
     * WHY this is the projection `0.1` can actually assert: the journal stores
     * digests, not bodies (it must not become a second transcript - secrets,
     * size), so `deriveMessages(journal)` reconstructs the DIGEST sequence.
     * That is enough to answer the question the phase exists for - was the
     * request the model received the history the ViewModel believed it had?
     * - and a divergence still points at the first differing index.
     */
    fun lastRequestDigests(filesDir: java.io.File?, sessionId: String): List<String>? {
        if (filesDir == null || sessionId.isBlank()) return null
        val events = tail(filesDir, sessionId, limit =200)
        val newest = events.lastOrNull { it.optString("kind") == REQUEST_ASSEMBLED }
            ?: return null
        val joined = newest.optString("digests", "")
        return if (joined.isEmpty()) emptyList() else joined.split(',')
    }
    
    /**
     * [T-session-journal-consistency] First index where the recorded request
     * and [actual] disagree.
     *
     * Return contract: -1 identical, -2 nothing recorded (no verdict is not
     * a mismatch), otherwise the first differing index - or min(size) when one
     * side is a prefix of the other, because that is the first index only one
     * side has.
     */
    fun firstDigestDivergence(
        recorded: List<String>?,
        actual: List<com.openminis.app.data.model.LLMMessage>,
    ): Int {
        if (recorded == null) return -2
        val actualDigests = actual.map { digestOf(it) }
        val common = minOf(recorded.size, actualDigests.size)
        for (i in 0 until common) {
            if (recorded[i] != actualDigests[i]) return i
        }
        return if (recorded.size == actualDigests.size) -1 else common
    }
    
    /** Test seam. */
    internal fun resetSeqForTest() {
        seq.set(0)
    }
}
