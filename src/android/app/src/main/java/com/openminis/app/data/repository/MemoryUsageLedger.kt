package com.openminis.app.data.repository

import org.json.JSONObject
import java.io.File

/**
 * [T-memory-usage-ledger] `mark_memory_used`: the counts that make "was this
 * memory ever actually delivered?" answerable.
 *
 * WHY separate from [MemoryRecallEngine.acceptRecallBatch]: that one counts
 * *recall* (the engine proposed it), and its counter feeds the anti-nag streak.
 * This one counts *delivery to the model* — a hit that was cut by the fragment
 * cap, or filtered by `recallFreshHits`, was never shown and must not look
 * used. Conflating the two is how a memory earns credit for an answer it never
 * appeared in.
 *
 * Storage mirrors the recall-count sidecar: one small JSON file, atomic write,
 * corrupt file → empty (never an exception into the prompt path).
 */
internal object MemoryUsageLedger {

    internal const val FILE = "memory-usage.json"

    private val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @Volatile
    private var primedDir: File? = null

    internal fun file(memoryDir: File): File = File(memoryDir, FILE)

    /** Key identity matches the recall/render path: `day:line`. */
    internal fun keyOf(day: String, line: String): String = "$day:${line.trim()}"

    /** Prime the cache from disk; safe to call more than once. */
    fun load(memoryDir: File?) {
        if (memoryDir == null) return
        primedDir = memoryDir
        val text = runCatching { file(memoryDir).readText(Charsets.UTF_8) }.getOrNull() ?: return
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optInt(k, 0)
            if (k.isNotBlank() && v > 0) counts[k] = v
        }
    }

    /**
     * [T-memory-usage-ledger] Record that these hits reached the model.
     *
     * One disk write per delivery, not per hit — the prompt path already pays
     * one save for the recall counts and a second per-hit write would show up
     * as jank in a long turn.
     */
    fun markUsed(memoryDir: File?, hits: List<Pair<String, String>>) {
        if (memoryDir == null || hits.isEmpty()) return
        for ((day, line) in hits) {
            val key = keyOf(day, line)
            counts[key] = (counts[key] ?: 0) + 1
        }
        save(memoryDir)
    }

    /** Delivery count for one line, 0 when it was never shown. */
    fun countOf(day: String, line: String): Int = counts[keyOf(day, line)] ?: 0

    /** Snapshot for diagnostics / a future ranking signal. */
    fun snapshot(): Map<String, Int> = HashMap(counts)

    private fun save(memoryDir: File) {
        runCatching {
            val obj = JSONObject()
            counts.forEach { (k, v) -> if (v > 0) obj.put(k, v) }
            val target = file(memoryDir)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(obj.toString(), Charsets.UTF_8)
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    /** Test seam. */
    internal fun resetForTest() {
        counts.clear()
        primedDir = null
    }
}
