package com.openminis.app.data.repository

import android.content.Context
import android.util.Log
import com.openminis.app.harness.prompt.MemoryRecallScorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log2

/**
 * Lightweight memory recall engine (port of XINCODE `MemoryRecall.kt`).
 *
 * Unlike XINCODE we do NOT depend on an embedding / vector service — the
 * sandbox has no LLM backend wired for embeddings. Instead this engine
 * does keyword-based FTS over ALL historical memory entries (GLOBAL.md +
 * every daily log) and scores them by:
 *
 *   score = keywordHits  +  recencyBoost  +  recallCountBoost
 *
 * where:
 *   - keywordHits    = count of extracted query keywords found in the entry text
 *   - recencyBoost   = decays linearly over [RECENCY_WINDOW_DAYS]; recent entries score higher
 *   - recallCountBoost = min(0.10 * log2(recallCount+1), 0.15) — frequently recalled mems get a slight permanent bump (mirrors XINCODE's M3-1)
 *
 * ## Aging (M3-1 "后半" / MemoryDecay)
 * Entries not recalled for > [ARCHIVE_AFTER_DAYS] days are still returned by
 * keyword match but flagged with `archived=true`; the caller can choose to
 * surface them with a faded style or omit them from auto-injection.
 *
 * ## Entry count limit
 * At most [RECALL_LIMIT] entries are returned per query, matching XINCODE.
 *
 * ## Thread safety
 * The recall-count map is a ConcurrentHashMap; all reads/writes are atomic.
 * The engine itself is stateless across calls except for the recall-count
 * cache (which is in-memory only and resets on process death — a reasonable
 * trade-off vs. adding a Room entity for what is effectively a soft signal).
 */
class MemoryRecallEngine(
    private val memoryDirProvider: () -> File,
) {

    companion object {
        private const val TAG = "MemoryRecallEngine"
        private const val GLOBAL_FILE = "GLOBAL.md"
        private const val RECALL_LIMIT = 4
        private const val CANDIDATE_LIMIT = 48
        private const val RECENCY_WINDOW_DAYS = 30L
        private const val ARCHIVE_AFTER_DAYS = 90L
        private const val RECALL_BOOST_COEFF = 0.10
        private const val RECALL_BOOST_MAX = 0.15
        private const val RECALL_COUNTS_FILE = ".recall-counts.json"

        /**
         * Build an engine bound to the app's /var/minis/memory directory.
         * Returns null if the directory doesn't exist (memory feature disabled / not set up).
         * Persisted recall counts are loaded from the `.recall-counts.json` sidecar
         * so the M3-1 boost survives process restarts ([T-memory-recall-persist]).
         */
        fun fromDir(memoryDir: File): MemoryRecallEngine? {
            if (!memoryDir.exists()) memoryDir.mkdirs()
            if (!memoryDir.isDirectory) return null
            return MemoryRecallEngine { memoryDir }.also { it.loadRecallCounts() }
        }

        fun fromContext(context: Context): MemoryRecallEngine? {
            // Canonical host directory backing the sandbox's /var/minis/memory.
            // MinisApp wires MemoryRepository(File(filesDir, "minis-global/memory")),
            // so that — not <filesDir>/../memory — is where the memory tools
            // actually write GLOBAL.md and the daily logs.
            val candidates = buildList {
                add(File(context.filesDir, "minis-global/memory"))
                System.getProperty("minis.memory.dir")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add(File(it)) }
                add(File("/var/minis/memory"))
            }
            val memoryDir = candidates.firstOrNull { it.isDirectory }
            return if (memoryDir != null) MemoryRecallEngine { memoryDir } else null
        }

        /** "GLOBAL.md" ⇒ "global", "2026-09-18.md" ⇒ "2026-09-18". */
        private fun dayKeyOf(fileName: String): String =
            if (fileName == GLOBAL_FILE) "global" else fileName.removeSuffix(".md")
    }

    /** recallCount — persisted to a JSON sidecar so the boost survives restarts. */
    private val recallCounts = ConcurrentHashMap<String, AtomicInteger>()

    data class RecallHit(
        val source: String,      // "GLOBAL.md" or "2026-09-18.md"
        val line: String,        // the matched line (snippet)
        val day: String,         // YYYY-MM-DD or "global"
        val score: Double,
        val recallCount: Int,
        val archived: Boolean,
        /** [T-memory-recall-explain] Score breakdown (keywords / recency / recall) for callers that want to surface *why* an entry matched. */
        val scoreDetail: String = "",
    )

    /**
     * Run a targeted recall: search all memory files for [query] terms,
     * score, and return the top [RECALL_LIMIT] hits.
     *
     * [T-taixu-2.5] 打分换 BM25（harness/prompt/MemoryRecallScorer，Adapted
     * from taixu GPL-3.0）：线性关键词计数让常见 bigram 与判别词同权，一行
     * 塞满常见词就能霸榜；IDF 加权让稀有查询词更值钱。泛化轮次（"继续"/
     * "好的"）不再触发召回。我方的新近度窗口 / 召回计数加成 / 标题加权 /
     * 条数上限语义全保留——只用打分器的低层件（effectiveQueryTerms + bm25）。
     */
    fun recall(query: String): List<RecallHit> {
        val queryTerms = MemoryRecallScorer.effectiveQueryTerms(query)
        if (queryTerms.isEmpty()) return emptyList()

        val today = LocalDate.now()
        val files = scanMemoryFiles()

        // Pass 1: collect all non-empty lines as raw candidates (BM25 scores
        // everything; zero-score lines drop in pass 2 — same net effect as the
        // old kwHits==0 prefilter, but IDF-weighted).
        data class Raw(
            val relName: String,
            val line: String,
            val dayStr: String,
            val isHeadline: Boolean,
            val rcKey: String,
        )
        val raws = mutableListOf<Raw>()
        for (file in files) {
            val relName = file.name
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            val dayStr = dayKeyOf(relName)
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                // Headline / bullet / section lines are higher signal than body prose.
                val isHeadline = trimmed.startsWith("#") || trimmed.startsWith("-") ||
                    trimmed.startsWith("*") || trimmed.startsWith(">")
                raws += Raw(relName, trimmed, dayStr, isHeadline, "$dayStr:$trimmed")
            }
        }
        if (raws.isEmpty()) return emptyList()

        // Pass 2: BM25 over the line corpus, then our own boosts on top.
        val documents = raws.map { MemoryRecallScorer.tokenize(it.line) }
        val bm25Scores = MemoryRecallScorer.bm25(queryTerms, documents)

        val candidates = mutableListOf<RecallHit>()
        for (index in raws.indices) {
            val raw = raws[index]
            val kwScore = bm25Scores[index]
            if (kwScore <= 0.0) continue

            val isGlobal = raw.relName == GLOBAL_FILE
            // Recency: entries from today score full, decaying over the window.
            val recencyBoost = if (isGlobal) 0.5 // GLOBAL.md always mildly relevant
            else {
                val entryDay = runCatching {
                    LocalDate.parse(raw.dayStr, DateTimeFormatter.ISO_LOCAL_DATE)
                }.getOrNull() ?: continue
                // ChronoUnit gives a signed difference: negative for future
                // dates, positive for the past. The old `today.unaryMinus()
                // .until(entryDay)` was inverted (and negative for every
                // real, i.e. past, entry) so recencyBoost always clamped to
                // 0.0 and recency scoring was effectively dead.
                val daysAgo = ChronoUnit.DAYS.between(entryDay, today).coerceAtLeast(0L)
                if (daysAgo > RECENCY_WINDOW_DAYS) 0.0
                else 1.0 - (daysAgo.toDouble() / RECENCY_WINDOW_DAYS)
            }

            // Recall-count boost (M3-1): log2 growth, capped. The key must
            // match [acceptRecall] exactly or the boost never accumulates.
            val rc = recallCounts.getOrPut(raw.rcKey) { AtomicInteger(0) }
            val recallBonus = (RECALL_BOOST_COEFF * log2(rc.get().toDouble() + 1.0))
                .coerceAtMost(RECALL_BOOST_MAX)

            val score = kwScore + recencyBoost + recallBonus
            // Give headline lines a small priority tie-break.
            val finalScore = score + (if (raw.isHeadline) 0.3 else 0.0)

            // [T-memory-recall-explain] Human-readable breakdown for callers
            // that surface *why* an entry matched (Settings → Memory debug).
            val detail = "bm25=${"%.2f".format(kwScore)} recency=${"%.2f".format(recencyBoost)} recall=${"%.2f".format(recallBonus)}"

            candidates.add(RecallHit(raw.relName, raw.line, raw.dayStr, finalScore, rc.get(), false, detail))
        }

        // Aging (M3-1 后半): entries older than ARCHIVE_AFTER_DAYS are still
        // returned by keyword match but flagged `archived=true` so the caller
        // can fade / drop them. They must NOT be filtered out here — the old
        // pipeline dropped them BEFORE the map() that set the flag, so
        // `archived` was always false and old memories silently vanished.
        val cutoff = today.minusDays(ARCHIVE_AFTER_DAYS)
        return candidates
            .map { hit ->
                val entryDay = runCatching {
                    LocalDate.parse(hit.day, DateTimeFormatter.ISO_LOCAL_DATE)
                }.getOrNull()
                // "global" (unparseable) entries are never archived.
                hit.copy(archived = entryDay?.isBefore(cutoff) == true)
            }
            .sortedByDescending { it.score }
            .take(RECALL_LIMIT)
    }

    /**
     * Accept a recalled entry — bumps its recallCount so frequently-accessed
     * memories score higher next time (M3-1 boost). Should be called when an
     * entry is actually injected into the prompt.
     */
    fun acceptRecall(source: String, line: String) {
        // Must key on the SAME day token [recall] reads (see rcKey there):
        // source arrives as "2026-09-18.md" while recall reads day "2026-09-18",
        // so keying on the raw source meant every boost landed on a key nobody
        // ever looked up.
        val key = "${dayKeyOf(source)}:${line.trim()}"
        val count = recallCounts.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
        Log.d(TAG, "accepted recall: $source line=${line.take(40)}… count=$count")
        // [T-memory-recall-persist] Persist the bump (atomic sidecar JSON) so a
        // process restart does not wipe the accumulated boost.
        saveRecallCounts()
    }

    // ── recall-count persistence (sidecar JSON, atomic) ───────────────────

    internal fun recallCountsFile(): File = File(memoryDirProvider(), RECALL_COUNTS_FILE)

    fun loadRecallCounts() {
        val file = recallCountsFile()
        val text = readTextResilient(file, TAG) ?: return
        try {
            val obj = org.json.JSONObject(text)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optInt(k, 0)
                if (k.isNotBlank() && v > 0) recallCounts.put(k, AtomicInteger(v))
            }
        } catch (_: Exception) {
            Log.w(TAG, "recall-counts sidecar unreadable, starting fresh")
        }
    }

    fun saveRecallCounts() {
        val file = recallCountsFile()
        val obj = org.json.JSONObject()
        recallCounts.forEach { (k, v) -> if (v.get() > 0) obj.put(k, v.get()) }
        writeTextAtomic(file, obj.toString())
    }

    /** Snapshot for tests/debug: (key, count) pairs, sorted by key. */
    internal fun recallCountsSnapshot(): Map<String, Int> =
        recallCounts.entries.associate { it.key to it.value.get() }.toSortedMap()

    private fun scanMemoryFiles(): List<File> {
        val memoryDir = memoryDirProvider()
        if (!memoryDir.isDirectory) return emptyList()
        return memoryDir.listFiles()
            ?.filter { it.isFile && (it.extension == "md" || it.name == "GLOBAL.md") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Format the hit list as a system-prompt fragment (mirrors XINCODE format). */
    fun formatAsPromptFragment(hits: List<RecallHit>): String {
        if (hits.isEmpty()) return ""
        return buildString {
            append("## Recalled relevant memories (keyword FTS, no embeddings)\n")
            for (hit in hits) {
                if (hit.archived) append("[archived] ")
                append("- **${hit.source}**: ${hit.line}")
                appendLine()
                acceptRecall(hit.source, hit.line)
            }
        }
    }
}
