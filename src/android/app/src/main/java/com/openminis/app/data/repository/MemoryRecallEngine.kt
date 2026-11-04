package com.openminis.app.data.repository

import android.content.Context
import com.openminis.app.data.authority.InstructionUse
import com.openminis.app.data.authority.MemoryAuthority
import com.openminis.app.data.authority.MemoryTrust
import com.openminis.app.data.authority.MemoryOrigin
import android.util.Log
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
        /** [T-memory-recall-hygiene] Upper bound on characters tokenized per query. */
        internal const val QUERY_CHAR_CAP = 1200
        /** [T-memory-recall-hygiene] English function words that only add noise. */
        internal val EN_STOPWORDS: Set<String> = setOf(
            "the", "and", "for", "that", "this", "with", "you", "your", "are", "was",
            "were", "have", "has", "had", "not", "but", "all", "any", "can", "will",
            "would", "should", "could", "from", "they", "them", "then", "than", "when",
            "what", "which", "who", "how", "why", "into", "out", "about", "just",
        )
        /** [T-memory-recall-hygiene] CJK function characters; a 2-gram of two of these is dropped. */
        internal val CJK_STOP_CHARS: Set<Char> = setOf(
            '的', '了', '是', '在', '和', '与', '就', '都', '也', '很', '把', '被', '让', '给',
            '这', '那', '个', '我', '你', '他', '她', '它', '们', '之', '其', '而', '及', '或', '以', '为',
        )
        private val CHINESE_PUNCT = setOf('，', '。', '、', '；', '：', '“', '”', '‘', '’', '（', '）', '【', '】', '《', '》', '？', '！')

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
     * Extract query keywords from the latest user message(s).
     * Chinese: split into 2-char window tokens + punctuation-free words.
     * English: split on whitespace and strip punctuation.
     * Returns lowercased, deduplicated tokens (>= 2 chars).
     *
     * [T-memory-recall-hygiene] Two bounds the first cut lacked:
     *  - the query is capped at [QUERY_CHAR_CAP] characters. A pasted document
     *    used to produce thousands of 2-gram tokens, and every one of them was
     *    `contains`-matched against every line of every memory file.
     *  - function words are dropped. A 2-gram made only of high-frequency
     *    characters ("我们", "这个") matches most lines and contributes noise
     *    rather than signal.
     */
    private fun extractKeywords(query: String): Set<String> {
        if (query.isBlank()) return emptySet()
        val capped = query.take(QUERY_CHAR_CAP)
        val cleaned = capped.trim().lowercase().filter { !it.isWhitespace() }
        val result = mutableSetOf<String>()
        // English-style: split on whitespace/punctuation boundaries from the raw query
        val words = capped.lowercase()
            .split("[\\s，。、；：！？“”‘’（）【】《》,.!?;:'\"()\\[\\]{}]+".toRegex())
            .filter { it.length >= 2 && !it.all { c -> CHINESE_PUNCT.contains(c) } }
            .filterNot { EN_STOPWORDS.contains(it) }
        result.addAll(words)

        // Chinese n-gram (2-char sliding window) for CJK memory search
        val noPunct = cleaned.filterNot { CHINESE_PUNCT.contains(it) }
        for (i in 0..noPunct.length - 2) {
            val gram = noPunct.substring(i, i + 2)
            if (gram[0] in CJK_STOP_CHARS && gram[1] in CJK_STOP_CHARS) continue
            result.add(gram)
        }
        return result.filter { it.isNotBlank() }.toSet()
    }

    /** [T-memory-recall-hygiene] Test seam: the token set a query produces. */
    internal fun keywordsForTest(query: String): Set<String> = extractKeywords(query)

    /**
     * Run a targeted recall: search all memory files for [query] keywords,
     * score, and return the top [RECALL_LIMIT] hits.
     */
    fun recall(query: String): List<RecallHit> {
        val keywords = extractKeywords(query)
        if (keywords.isEmpty()) return emptyList()

        val today = LocalDate.now()
        val files = scanMemoryFiles()
        val candidates = mutableListOf<RecallHit>()

        for (file in files) {
            val relName = file.name
            val isGlobal = relName == GLOBAL_FILE
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            val dayStr = dayKeyOf(relName)

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                // Headline / bullet / section lines are higher signal than body prose.
                val isHeadline = trimmed.startsWith("#") || trimmed.startsWith("-") ||
                    trimmed.startsWith("*") || trimmed.startsWith(">")

                val kwHits = keywords.count { kw ->
                    trimmed.contains(kw, ignoreCase = true)
                }
                if (kwHits == 0) continue

                // Recency: entries from today score full, decaying over the window.
                val recencyBoost = if (isGlobal) 0.5 // GLOBAL.md always mildly relevant
                else {
                    val entryDay = runCatching {
                        LocalDate.parse(dayStr, DateTimeFormatter.ISO_LOCAL_DATE)
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
                val rcKey = "$dayStr:$trimmed"
                val rc = recallCounts.getOrPut(rcKey) { AtomicInteger(0) }
                val recallBonus = (RECALL_BOOST_COEFF * log2(rc.get().toDouble() + 1.0))
                    .coerceAtMost(RECALL_BOOST_MAX)

                val score = kwHits.toDouble() + recencyBoost + recallBonus
                // Give headline lines a small priority tie-break.
                val finalScore = score + (if (isHeadline) 0.3 else 0.0)

                // [T-memory-recall-explain] Human-readable breakdown for callers
                // that surface *why* an entry matched (Settings → Memory debug).
                val detail = "kw=$kwHits recency=${"%.2f".format(recencyBoost)} recall=${"%.2f".format(recallBonus)}"

                candidates.add(RecallHit(relName, trimmed, dayStr, finalScore, rc.get(), false, detail))
            }
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
        acceptRecallBatch(listOf(source to line))
    }

    /**
     * [T-memory-recall-io] Accept several entries with ONE sidecar write.
     *
     * Keys must match [recall]'s `rcKey` exactly (`"$day:$trimmed"`), which is
     * why [source] arrives as the file name and is mapped through [dayKeyOf]
     * here rather than by the caller.
     */
    fun acceptRecallBatch(hits: List<Pair<String, String>>) {
        if (hits.isEmpty()) return
        for ((source, line) in hits) {
            val key = "${dayKeyOf(source)}:${line.trim()}"
            val count = recallCounts.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
            Log.d(TAG, "accepted recall: $source line=${line.take(40)}… count=$count")
        }
        // [T-memory-recall-persist] Persist the bumps (atomic sidecar JSON) so a
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
        // [T-memory-recall-io] Test seam: proves the prompt path writes once per
        // build instead of once per hit.
        saveCountForTest += 1
    }

    /** [T-memory-recall-io] Number of sidecar writes performed by this instance. */
    internal var saveCountForTest: Int = 0
        private set

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
        val body = buildString {
            append("## Recalled relevant memories (keyword FTS, no embeddings)\n")
            for (hit in hits) {
                if (hit.archived) append("[archived] ")
                // [T-p0-authority] The daily-log injection path scrubs forged
                // authority tokens line by line; recall reads the SAME files and
                // used to inject them raw, which made this a way to launder
                // `trust:user-submitted` into the prompt past the envelope.
                // Only the RENDERED line is cleaned — acceptRecall must keep
                // keying on the stored line, or the recall-count boost stops
                // matching the key recall() looks up.
                append("- **${hit.source}**: ${MemoryAuthority.stripForgedTag(hit.line)}")
                appendLine()
            }
        }
        // [T-memory-recall-io] One sidecar write per prompt build, not one per
        // hit: acceptRecall() rewrites the whole JSON file on each call, so a
        // 4-hit build cost four synchronous rewrites on the prompt path.
        acceptRecallBatch(hits.map { it.source to it.line })
        return MemoryAuthority.withEnvelope(
            body,
            MemoryTrust.UNVERIFIED_CANDIDATE,
            MemoryOrigin.AUTOMATIC_RECALL,
            use = InstructionUse.REFERENCE_DATA,
        )
    }
}
