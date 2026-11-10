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

/**
 * Lightweight memory recall engine (port of XINCODE `MemoryRecall.kt`).
 *
 * Unlike XINCODE we do NOT depend on an embedding / vector service — the
 * sandbox has no LLM backend wired for embeddings. Instead this engine does
 * keyword FTS over the DAILY MEMORY LOGS (every `YYYY-MM-DD.md`) and scores
 * candidates with a compact BM25:
 *
 *   score = 2.0 * bm25(kw tokens)  +  0.5 * recency  +  0.2 * headline
 *
 * [T-android-memory-recall-r2] Two structural fixes over the original raw
 * sum (`kwHits + recency + recallBonus`), which let generic GLOBAL rules
 * outscore on-topic diary entries:
 *
 *  1. **GLOBAL.md is not a recall candidate.** Its full text is injected
 *     verbatim into the system prompt on EVERY turn (MemoryFragmentBuilder),
 *     so recalling it is pure duplication that consumed the whole budget:
 *     the sidecar showed the same three GLOBAL lines injected 20–30 times
 *     while diary entries never surfaced. Recall now serves what is NOT
 *     already standing context — the dated logs from both scopes.
 *  2. **Scoring is IDF-weighted and length-normalised.** The old raw hit
 *     count let high-frequency 2-grams ("什么", "问题") match generic rule
 *     lines for every query, and long lines accumulate more substring hits
 *     than short ones. BM25's idf + length normalisation fixes both; the
 *     recall-count bonus is REMOVED from the score (it was a positive
 *     feedback loop — injected → scored higher → re-injected) and survives
 *     only as displayed metadata (`RecallHit.recallCount`).
 *
 * ## Hygiene
 * HTML comment ranges (`<!-- … -->`), separator-only and punctuation-only
 * lines are skipped, so template scaffolding never becomes a "memory".
 *
 * ## Aging (M3-1 "后半" / MemoryDecay)
 * Entries not recalled for > [ARCHIVE_AFTER_DAYS] days are still returned by
 * keyword match but flagged with `archived=true`; the caller can choose to
 * surface them with a faded style or omit them from auto-injection.
 *
 * ## Limits
 * At most [RECALL_LIMIT] entries are returned per query; a single file
 * contributes at most [CANDIDATE_LIMIT] candidate lines and at most
 * [MAX_HITS_PER_FILE] of the final picks, so one dense file cannot fill the
 * budget with near-duplicates.
 *
 * ## Thread safety
 * The recall-count map is a ConcurrentHashMap; all reads/writes are atomic.
 * The engine itself is stateless across calls except for the recall-count
 * cache (which is in-memory only and resets on process death — a reasonable
 * trade-off vs. adding a Room entity for what is effectively a soft signal).
 */
class MemoryRecallEngine(
    private val memoryDirProvider: () -> File,
    /**
     * [T-memory-shared-recall] Optional second scope: the app-wide
     * `minis-global/memory` directory. When present, recall covers experience
     * recorded in other chats too, with the session's own file winning on a
     * filename collision. Null keeps the engine scoped to one directory, which is
     * what the existing tests and call sites expect.
     */
    private val sharedDirProvider: (() -> File?)? = null,
) {

    companion object {
        private const val TAG = "MemoryRecallEngine"
        private const val RECALL_LIMIT = 4
        /** Per-file cap on the scoring pool, applied after df/idf are computed over the whole corpus. */
        private const val CANDIDATE_LIMIT = 48
        /** Per-file cap on the FINAL picks (diversity across sources). */
        internal const val MAX_HITS_PER_FILE = 2
        private const val RECENCY_WINDOW_DAYS = 30L
        private const val ARCHIVE_AFTER_DAYS = 90L
        private const val RECALL_COUNTS_FILE = ".recall-counts.json"
        // BM25 parameters (standard defaults) for the keyword score.
        private const val BM25_K1 = 1.2
        private const val BM25_B = 0.75
        private const val KW_WEIGHT = 2.0
        private const val RECENCY_WEIGHT = 0.5
        private const val HEADLINE_BONUS = 0.2
        /** [T-memory-recall-hygiene] Upper bound on characters tokenized per query. */
        internal const val QUERY_CHAR_CAP = 1200

        /** [T-memory-recall-caps] One hit line may not exceed this. */
        internal const val MAX_HIT_CHARS = 240

        /** [T-memory-recall-caps] The whole recall fragment may not exceed this (~600 tokens). */
        internal const val MAX_FRAGMENT_CHARS = 2400

        /** [T-memory-recall-anti-nag] A memory announced this many consecutive turns is considered "seen". */
        internal const val RECALL_REPEAT_CAP = 3

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
            return MemoryRecallEngine(memoryDirProvider = { memoryDir })
                .also { it.loadRecallCounts() }
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
            return if (memoryDir != null) {
                MemoryRecallEngine(memoryDirProvider = { memoryDir })
            } else {
                null
            }
        }

        /** "GLOBAL.md" ⇒ "global", "2026-09-18.md" ⇒ "2026-09-18". */
        private fun dayKeyOf(fileName: String): String =
            if (fileName == MemoryRepository.GLOBAL_FILE) "global" else fileName.removeSuffix(".md")
    }

    /** recallCount — persisted to a JSON sidecar so the boost survives restarts. */
    private val recallCounts = ConcurrentHashMap<String, AtomicInteger>()

    /** [T-memory-recall-memo] (query, hits) of the most recent recall, or null. */
    @Volatile
    private var memo: Pair<String, List<RecallHit>>? = null

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
     * [T-memory-recall-caps] Scrub forged authority tokens, then clip.
     *
     * Only the RENDERED line is cleaned: acceptRecall must keep keying on the
     * stored line, or the recall-count boost stops matching the key recall()
     * looks up (the same class of bug the acceptRecall KDoc records).
     */
    private fun clipForPrompt(line: String): String {
        val safe = MemoryAuthority.stripForgedTag(line)
        return if (safe.length <= MAX_HIT_CHARS) safe else safe.take(MAX_HIT_CHARS) + "…"
    }

    /**
     * Run a targeted recall over the DAILY memory logs: hygiene-filter the
     * lines, score candidates with a compact BM25 (IDF + length
     * normalisation), and return the top [RECALL_LIMIT] picks with a
     * per-file diversity cap.
     */
    fun recall(query: String): List<RecallHit> {
        val keywords = extractKeywords(query)
        if (keywords.isEmpty()) return emptyList()
        // [T-memory-recall-memo] Prompt assembly can run more than once for the
        // same user message (retry, model fallback, queue drain). The file scan is
        // the expensive part, so the last (query -> hits) pair is reused when the
        // query is byte-identical. Single-entry on purpose: memory usage stays O(1)
        // and a changed message naturally misses the memo.
        memo?.let { (memoedQuery, memoedHits) -> if (memoedQuery == query) return memoedHits }

        val today = LocalDate.now()

        // ── pass 1: collect candidate lines (hygiene-filtered) ─────────────
        // [T-memory-recall-hygiene] HTML comment ranges, separator-only and
        // punctuation-only lines are template scaffolding, not memories.
        class Raw(val file: String, val line: String, val day: String, val isHeadline: Boolean, val kwTokens: List<String>)
        val raws = mutableListOf<Raw>()

        for (file in scanMemoryFiles()) {
            val relName = file.name
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            val dayStr = dayKeyOf(relName)
            var inHtmlComment = false

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (inHtmlComment) {
                    if (trimmed.contains("-->")) inHtmlComment = false
                    continue
                }
                if (trimmed.startsWith("<!--")) {
                    if (!trimmed.contains("-->")) inHtmlComment = true
                    continue
                }
                // Separator / decoration lines ("---", "===", pure punctuation).
                if (trimmed.none { it.isLetterOrDigit() }) continue

                val kwTokens = keywords.filter { trimmed.contains(it, ignoreCase = true) }
                if (kwTokens.isEmpty()) continue
                // Headline / bullet / section lines are higher signal than body prose.
                val isHeadline = trimmed.startsWith("#") || trimmed.startsWith("-") ||
                    trimmed.startsWith("*") || trimmed.startsWith(">")
                raws.add(Raw(relName, trimmed, dayStr, isHeadline, kwTokens))
            }
        }
        if (raws.isEmpty()) return emptyList()

        // ── pass 2: BM25 over the candidate pool ───────────────────────────
        // [T-memory-recall-idf] Corpus statistics (df / N / avgLen) must see
        // EVERY keyword-hit line: a per-file cap applied before df would
        // under-count files whose overflow was dropped and inflate their idf.
        val df = HashMap<String, Int>()
        for (r in raws) for (t in r.kwTokens.distinct()) df.merge(t, 1, Int::plus)
        val poolSize = raws.size.toDouble()
        val avgLen = raws.sumOf { it.line.length }.toDouble() / poolSize
        // Per-file scoring-pool cap, applied AFTER corpus statistics: a single
        // dense file still cannot own the pool, while idf stays whole-corpus.
        val pool = raws.groupBy { it.file }.values.flatMap { it.take(CANDIDATE_LIMIT) }

        fun idf(token: String): Double {
            val d = df[token]?.coerceAtLeast(1) ?: 1
            return kotlin.math.ln(1.0 + poolSize / d)
        }

        fun occurrences(haystack: String, needle: String): Int {
            var count = 0
            var idx = 0
            while (true) {
                idx = haystack.indexOf(needle, idx, ignoreCase = true)
                if (idx < 0) break
                count++
                idx += needle.length
            }
            return count
        }

        val cutoff = today.minusDays(ARCHIVE_AFTER_DAYS)
        val scored = pool.map { r ->
            val lenNorm = BM25_B * (1.0 - r.line.length / avgLen.coerceAtLeast(1.0))
            var kw = 0.0
            for (t in r.kwTokens.distinct()) {
                val tf = occurrences(r.line, t).toDouble()
                kw += idf(t) * (tf * (BM25_K1 + 1.0)) / (tf + BM25_K1 * (1.0 - lenNorm).coerceAtLeast(0.05))
            }
            val entryDay = runCatching { LocalDate.parse(r.day, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
            val daysAgo = entryDay?.let { ChronoUnit.DAYS.between(it, today).coerceAtLeast(0L) }
            // Recency: recent entries mildly preferred; undated lines neutral.
            val recency = when {
                daysAgo == null -> 0.3
                daysAgo > RECENCY_WINDOW_DAYS -> 0.0
                else -> 1.0 - (daysAgo.toDouble() / RECENCY_WINDOW_DAYS)
            }
            val score = KW_WEIGHT * kw + RECENCY_WEIGHT * recency +
                (if (r.isHeadline) HEADLINE_BONUS else 0.0)
            // [T-memory-recall-explain] Human-readable breakdown for callers
            // that surface *why* an entry matched (Settings → Memory debug).
            val rcKey = "${r.day}:${r.line}"
            val rc = recallCounts.getOrPut(rcKey) { AtomicInteger(0) }
            val detail = "bm25=${"%.2f".format(kw)} recency=${"%.2f".format(recency)}"
            RecallHit(
                source = r.file,
                line = r.line,
                day = r.day,
                score = score,
                recallCount = rc.get(),
                // Aging (M3-1 后半): old entries stay searchable, flagged for
                // the caller to fade. Undated lines are never archived.
                archived = entryDay?.isBefore(cutoff) == true,
                scoreDetail = detail,
            )
        }

        // ── pass 3: diversity-aware top-K ──────────────────────────────────
        val merged = mutableListOf<RecallHit>()
        val perFilePicks = HashMap<String, Int>()
        for (hit in scored.sortedByDescending { it.score }) {
            if (merged.size >= RECALL_LIMIT) break
            // Near-duplicate guard FIRST (a skipped line must not consume a
            // per-file slot): same file, one line's 30-char head is the
            // other's prefix (an annotation appended to an already-picked line).
            val head = hit.line.take(30)
            if (merged.any { m -> m.source == hit.source && (m.line.startsWith(head) || hit.line.startsWith(m.line.take(30))) }) continue
            val used = perFilePicks.merge(hit.source, 1, Int::plus) ?: 1
            if (used > MAX_HITS_PER_FILE) continue
            merged += hit
        }
        memo = query to merged
        return merged
    }

    /**
     * Accept a recalled entry — bumps its persisted recallCount. The count no
     * longer contributes to the ranking score ([T-android-memory-recall-r2]:
     * "injected → scored higher → re-injected" was a positive feedback loop
     * that let three GLOBAL lines own the budget); it survives as displayed
     * metadata and for future strength-style signals. Called when an entry
     * is injected into the prompt.
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
        // [T-android-memory-recall-r2] GLOBAL.md (BOTH scopes) is never a
        // recall candidate: its full text rides in the system prompt every
        // turn, so recalling it is pure duplication that used to consume the
        // whole budget. Recall serves the dated logs — content that is NOT
        // already standing context.
        val isSearchable = { f: File -> f.isFile && f.extension == "md" && f.name != MemoryRepository.GLOBAL_FILE }
        val memoryDir = memoryDirProvider()
        val session = if (memoryDir.isDirectory) {
            memoryDir.listFiles()?.filter(isSearchable).orEmpty()
        } else {
            emptyList()
        }
        // [T-memory-shared-recall] Shared scope: same filename means the session
        // copy wins, because that is the one the verbatim window shows.
        val sharedDir = sharedDirProvider?.invoke()
        val shared = if (sharedDir != null && sharedDir.isDirectory) {
            sharedDir.listFiles()?.filter(isSearchable).orEmpty()
        } else {
            emptyList()
        }
        val sessionNames = session.mapTo(HashSet()) { it.name }
        return (session + shared.filter { it.name !in sessionNames })
            .sortedByDescending { it.lastModified() }
    }

    /** Format the hit list as a system-prompt fragment (mirrors XINCODE format). */
    fun formatAsPromptFragment(hits: List<RecallHit>): String {
        if (hits.isEmpty()) return ""
        val included = ArrayList<RecallHit>(hits.size)
        val body = buildString {
            append("## Recalled relevant memories (keyword FTS, no embeddings)\n")
            for (hit in hits) {
                // [T-memory-recall-caps] Budget the whole fragment, not just one
                // hit: a single enormous diary line used to be able to fill the
                // block on its own. `length` is this StringBuilder's size.
                if (length >= MAX_FRAGMENT_CHARS) break
                if (hit.archived) append("[archived] ")
                append("- **${hit.source}**: ${clipForPrompt(hit.line)}")
                appendLine()
                included += hit
            }
        }
        // [T-memory-recall-io] One sidecar write per prompt build, not one per
        // hit: acceptRecall() rewrites the whole JSON file on each call, so a
        // 4-hit build cost four synchronous rewrites on the prompt path.
        acceptRecallBatch(included.map { it.source to it.line })
        return MemoryAuthority.withEnvelope(
            body,
            MemoryTrust.UNVERIFIED_CANDIDATE,
            MemoryOrigin.AUTOMATIC_RECALL,
            use = InstructionUse.REFERENCE_DATA,
        )
    }

    // ── [T-memory-recall-anti-nag] cross-turn injection dedupe ─────────────

    /**
     * Per-hit consecutive-injection counter, keyed exactly like the
     * recall-count boost (`day:line`). The engine instance is per session
     * ([MemoryRecallEngineCache] keeps it alive across turns), so this state
     * is session-scoped by construction; LRU eviction only resets the
     * counters, which costs at most one repeated announcement.
     */
    private val injectionStreaks = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * Recall for the prompt path: [recall] plus the cross-turn dedupe the
     * query-keyed memo cannot provide.
     *
     * The memo keys on the query string, but the prompt query folds in the
     * assistant tail, which changes every turn — so the same few memories
     * matched every turn and were re-announced every turn (the noise the
     * live-recall block was drowning in). The dedupe here keys on the HIT
     * instead: a memory injected [RECALL_REPEAT_CAP] consecutive turns stops
     * being injected (the model has read it), while dropping out of the hit
     * set — a task pivot — resets its counter, so it is announced once again
     * when it becomes relevant anew.
     *
     * The streak update covers EVERY hit, including the ones filtered out:
     * a capped memory is still matching, and resetting its counter would
     * re-inject it next turn, defeating the cap.
     */
    fun recallForPrompt(query: String): List<RecallHit> {
        val hits = recall(query)
        // The streak update runs on EVERY outcome, including the empty one:
        // an empty hit round IS the task-pivot signal, and returning early
        // would keep the capped counters pinned — a memory that left the hits
        // could then never re-arm (announced again), defeating the whole
        // drop-out semantics.
        val keyOf = { hit: RecallHit -> "${dayKeyOf(hit.source)}:${hit.line.trim()}" }
        val keys = hits.associate { it to keyOf(it) }
        val seenKeys = keys.values.toHashSet()
        val fresh = hits.filter { (injectionStreaks[keys[it]]?.get() ?: 0) < RECALL_REPEAT_CAP }
        for (key in seenKeys) {
            injectionStreaks.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
        }
        // Drop keys that fell out of the hit set: their next reappearance is
        // a fresh signal (task pivot) and must be announced again.
        injectionStreaks.keys.retainAll(seenKeys)
        return fresh
    }
}
