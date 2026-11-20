package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger

/**
 * [T-compact-chunk-pool] The rolling per-compaction chunk pool plus the
 * keyword retrieval that decides which chunks ride along with the current
 * instruction. Extracted from the ChatViewModel companion so the compaction
 * read/write paths share one unit without growing the ViewModel file.
 *
 * Legacy markers (no `summary_chunks`) resolve to an empty pool so the read
 * side keeps using the monolithic summary.
 */

private const val TAG = "ChatViewModel"

/** [T-compact-chunk-pool] Newest chunks kept in the rolling pool. */
internal const val SUMMARY_CHUNK_POOL_MAX = 8

/** Per-chunk cap; a runaway summary must not fill the pool. */
internal const val SUMMARY_CHUNK_MAX_CHARS = 4_000

/**
 * [P3-3] JSON budget for the whole pool. A pool over this budget used to
 * be discarded WHOLE on both the read and the write path — silently
 * erasing every earlier compaction snapshot. [fitSummaryChunks] now
 * degrades to the newest records that fit instead.
 */
internal const val SUMMARY_CHUNK_POOL_MAX_CHARS = 65_536

/** Chunks injected per turn after retrieval. */
internal const val SUMMARY_CHUNK_TOP_K = 3

/** (text, epochMs) pairs, oldest first. */
private fun parseSummaryChunkRecords(json: String?): List<Pair<String, Long>> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(json)
        val out = ArrayList<Pair<String, Long>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("t").takeIf { it.isNotBlank() } ?: continue
            out.add(t to o.optLong("a", 0L))
        }
        out
    }.getOrDefault(emptyList())
}

/**
 * [P3-3] Bound a pool to [SUMMARY_CHUNK_POOL_MAX_CHARS] by keeping the
 * NEWEST records that fit, evicting oldest first — the same policy the
 * rolling pool applies per record count, extended to bytes. Returns the
 * input unchanged when it already fits; null when nothing usable fits.
 * Logs the degradation so a shrunken pool is never a silent loss. Pure
 * function, testable.
 */
internal fun fitSummaryChunks(json: String?, maxChars: Int = SUMMARY_CHUNK_POOL_MAX_CHARS): String? {
    if (json.isNullOrBlank()) return null
    // Parse first: this gates the WRITE path, so a corrupt payload must not
    // pass through "fits the budget" and silently replace a valid pool.
    val records = parseSummaryChunkRecords(json)
    if (records.isEmpty()) {
        AppLogger.warning(
            TAG,
            "[Compact] summary chunk pool ${json.length} chars is not a parsable pool — " +
                "falling back to the whole-summary path",
        )
        return null
    }
    if (json.length <= maxChars) return json
    val kept = ArrayList<Pair<String, Long>>()
    var size = 2 // "[]"
    for (record in records.asReversed()) {
        val encoded = runCatching {
            org.json.JSONObject().put("t", record.first).put("a", record.second).toString()
        }.getOrDefault("")
        val growth = encoded.length + if (kept.isEmpty()) 0 else 1
        if (encoded.isEmpty() || size + growth > maxChars) break
        kept.add(0, record)
        size += growth
    }
    if (kept.isEmpty()) {
        // Even one record is over budget: truncate the newest record's
        // text until its encoded form fits, so the newest snapshot —
        // the one the fallback path prefers — still survives.
        val (text, at) = records.last()
        var shrunk = text
        while (shrunk.isNotEmpty()) {
            val encoded = runCatching {
                org.json.JSONObject().put("t", shrunk).put("a", at).toString()
            }.getOrNull()
            if (encoded != null && encoded.length + 2 <= maxChars) {
                AppLogger.warning(
                    TAG,
                    "[Compact] summary chunk pool ${json.length} chars over the $maxChars budget — " +
                        "kept a truncated newest record (${shrunk.length}/${text.length} chars)",
                )
                return org.json.JSONArray().put(
                    org.json.JSONObject().put("t", shrunk).put("a", at),
                ).toString()
            }
            shrunk = shrunk.dropLast(maxOf(1, shrunk.length / 4))
        }
        return null
    }
    AppLogger.warning(
        TAG,
        "[Compact] summary chunk pool ${json.length} chars over the $maxChars budget — " +
            "kept newest ${kept.size}/${records.size} record(s)",
    )
    val arr = org.json.JSONArray()
    for ((t, a) in kept) arr.put(org.json.JSONObject().put("t", t).put("a", a))
    return arr.toString()
}

/**
 * [T-compact-chunk-pool] Rolling pool update on a successful compact:
 * keep the newest [SUMMARY_CHUNK_POOL_MAX] chunks (each capped), each
 * stored as a per-compaction snapshot so earlier material can still be
 * retrieved by keyword later. Pure function, testable.
 */
internal fun appendSummaryChunk(existingJson: String?, newSummary: String): String {
    val capped = ChatViewModel.preserveSummaryEdges(newSummary, SUMMARY_CHUNK_MAX_CHARS)
    val kept = parseSummaryChunkRecords(existingJson)
        .filter { it.first.isNotBlank() }
        .toMutableList()
    kept.add(capped to System.currentTimeMillis())
    val trimmed = if (kept.size > SUMMARY_CHUNK_POOL_MAX) {
        kept.subList(kept.size - SUMMARY_CHUNK_POOL_MAX, kept.size).toList()
    } else {
        kept
    }
    return runCatching {
        val arr = org.json.JSONArray()
        for ((t, a) in trimmed) {
            arr.put(org.json.JSONObject().put("t", t).put("a", a))
        }
        arr.toString()
    }.getOrElse { "[]" }
}

/**
 * [T-compact-chunk-pool] Tokenize a query into comparable units:
 * ASCII word runs plus CJK bigrams (a Chinese sentence has no spaces,
 * and a single-gram match is far too noisy). Pure function.
 */
internal fun summaryQueryTerms(query: String): Set<String> {
    val terms = LinkedHashSet<String>()
    val lower = query.lowercase()
    val ascii = StringBuilder()
    fun flushAscii() {
        if (ascii.length >= 2) terms.add(ascii.toString())
        ascii.setLength(0)
    }
    var cjkRun = StringBuilder()
    fun flushCjk() {
        val run = cjkRun.toString()
        if (run.length >= 2) {
            for (i in 0..run.length - 2) terms.add(run.substring(i, i + 2))
        } else if (run.length == 1) {
            terms.add(run)
        }
        cjkRun.setLength(0)
    }
    for (c in lower) {
        when {
            c.code < 128 && (c.isLetterOrDigit()) -> ascii.append(c)
            c.code in 0x4E00..0x9FFF -> {
                flushAscii()
                cjkRun.append(c)
            }
            else -> {
                flushAscii()
                flushCjk()
            }
        }
    }
    flushAscii()
    flushCjk()
    return terms
}

/**
 * [T-compact-chunk-pool] Retrieve the chunks that match the current
 * instruction, newest-first relevance, then re-sorted chronologically
 * for injection. Falls back to the most recent chunk when the query
 * shares no terms with any of them (the tail is what a conversation
 * most plausibly continues from). Pure function, testable.
 */
internal fun selectSummaryChunks(chunksJson: String?, query: String, topK: Int = SUMMARY_CHUNK_TOP_K): List<String> {
    val chunks = parseSummaryChunkRecords(chunksJson)
    if (chunks.isEmpty()) return emptyList()
    if (chunks.size == 1) return listOf(chunks[0].first)
    val terms = summaryQueryTerms(query)
    if (terms.isEmpty()) return listOf(chunks.last().first)
    val scored = chunks.mapIndexed { idx, (text, at) ->
        val lower = text.lowercase()
        var score = 0
        for (t in terms) {
            if (t in lower) score += if (t.length >= 2) 2 else 1
        }
        Triple(idx, score, text)
    }
    val hits = scored.filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(topK)
    val chosen = if (hits.isNotEmpty()) {
        hits
    } else {
        listOf(Triple(chunks.lastIndex, 0, chunks.last().first))
    }
    return chosen.sortedBy { it.first }.map { it.third }
}
