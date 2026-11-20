package com.openminis.app.data.repository

import com.openminis.app.util.Digest
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Per-chat recall-line statistics, separate from the authoritative entry lifecycle. */
internal object MemoryUsageLedger {
    private const val LEGACY_FILE = "memory-usage.json"
    private const val VERIFIED_FILE = ".memory-usage-v2.json"
    private const val RETENTION_MS = 30 * MemoryLifecycle.DAY_MS
    private const val MAX_KEYS = 5_000
    private const val TURN_WINDOW = 64
    private const val MAX_LINES_PER_TURN = 128
    private val opaqueKey = Regex("[0-9a-f]{64}")

    private data class Count(val shown: Int, val lastShownAt: Long)
    private data class Receipt(val turn: String, val keys: Set<String>)
    private data class Snapshot(val lines: Map<String, Count>, val receipts: List<Receipt>)

    internal fun lineKey(shared: Boolean, source: String, line: String): String =
        Digest.sha256Hex("$shared\u0000$source\u0000${line.trim()}")

    internal fun verifiedFile(sessionDirectory: File): File = File(sessionDirectory, VERIFIED_FILE)

    /** Called only by successful request settlement. Read/modify/write belongs to the consuming chat, not the diary's owner. */
    fun recordVerified(sessionDirectory: File, turnKey: String, lines: Set<String>, deliveredAt: Long) {
        if (turnKey.isBlank() || lines.isEmpty() || lines.any { !opaqueKey.matches(it) }) return
        val turn = Digest.sha256Hex(turnKey)
        runCatching {
            MemoryLifecycle.locked(sessionDirectory) { dir ->
                val file = MemoryLifecycle.child(dir, VERIFIED_FILE) ?: return@locked
                val previous = readVerified(file)
                val seen = previous.receipts.firstOrNull { it.turn == turn }?.keys.orEmpty()
                val added = lines - seen
                if (added.isEmpty()) return@locked
                val counts = previous.lines.toMutableMap()
                for (key in added) {
                    val old = counts[key]
                    counts[key] = Count(increment(old?.shown ?: 0), maxOf(old?.lastShownAt ?: deliveredAt, deliveredAt))
                }
                counts.entries.removeIf { (_, count) -> count.lastShownAt < deliveredAt - RETENTION_MS }
                val kept = counts.entries.sortedByDescending { it.value.lastShownAt }.take(MAX_KEYS).associate { it.toPair() }
                val rows = JSONObject()
                for ((key, count) in kept) rows.put(key, JSONObject().put("shown", count.shown).put("at", count.lastShownAt))
                val receipts = (previous.receipts.filterNot { it.turn == turn } + Receipt(turn, seen + added)).takeLast(TURN_WINDOW)
                val turns = JSONArray()
                for (receipt in receipts) {
                    val keys = receipt.keys.filter { it in kept }.takeLast(MAX_LINES_PER_TURN)
                    if (keys.isNotEmpty()) turns.put(JSONObject().put("turn", receipt.turn).put("lines", JSONArray(keys)))
                }
                writeLifecycleAtomic(file, JSONObject().put("version", 2).put("lines", rows).put("turns", turns).toString())
            }
        }
    }

    /** Model-facing labels report only the new, verified counters. No disk writes from summaries. */
    fun verifiedSummaryFor(sessionDirectory: File): Pair<Int, Int> = runCatching {
        MemoryLifecycle.locked(sessionDirectory) { dir ->
            summarize(readVerified(MemoryLifecycle.child(dir, VERIFIED_FILE) ?: return@locked 0 to 0).lines.values.map { it.shown })
        }
    }.getOrDefault(0 to 0)

    /** Read-only compatibility for #401's flat historical counters. These are not delivery evidence. */
    fun summaryFor(memoryDir: File?): Pair<Int, Int> = runCatching {
        if (memoryDir == null) return 0 to 0
        MemoryLifecycle.locked(memoryDir) { dir ->
            val file = MemoryLifecycle.child(dir, LEGACY_FILE)?.takeIf { it.isFile } ?: return@locked 0 to 0
            val old = JSONObject(file.readText())
            summarize(old.keys().asSequence().map { old.optInt(it, 0) }.toList())
        }
    }.getOrDefault(0 to 0)

    private fun readVerified(file: File): Snapshot = runCatching {
        val json = JSONObject(file.readText())
        require(json.getInt("version") == 2)
        val rows = json.getJSONObject("lines")
        val counts = buildMap {
            for (key in rows.keys()) {
                if (!opaqueKey.matches(key)) continue
                val row = rows.optJSONObject(key) ?: continue
                val shown = row.optInt("shown", 0)
                if (shown > 0) put(key, Count(shown, row.getLong("at")))
            }
        }
        val turns = json.getJSONArray("turns")
        val receipts = (maxOf(0, turns.length() - TURN_WINDOW) until turns.length()).mapNotNull { i ->
            val row = turns.optJSONObject(i) ?: return@mapNotNull null
            val turn = row.optString("turn")
            if (!opaqueKey.matches(turn)) return@mapNotNull null
            val keys = row.getJSONArray("lines")
            Receipt(turn, (0 until minOf(keys.length(), MAX_LINES_PER_TURN)).mapNotNullTo(HashSet()) { j ->
                keys.optString(j).takeIf { it in counts }
            })
        }
        Snapshot(counts, receipts)
    }.getOrElse { Snapshot(emptyMap(), emptyList()) }

    private fun increment(count: Int): Int = if (count < Int.MAX_VALUE) count + 1 else count

    private fun summarize(values: Collection<Int>): Pair<Int, Int> {
        val positive = values.filter { it > 0 }
        return positive.size to positive.sumOf { it.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
