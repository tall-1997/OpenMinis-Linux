package com.openminis.app.data.repository

import android.content.Context
import com.openminis.app.data.authority.InstructionUse
import com.openminis.app.data.authority.MemoryAuthority
import com.openminis.app.data.authority.MemoryTrust
import com.openminis.app.data.authority.MemoryOrigin
import com.openminis.app.data.memory.MemoryEmbeddingIndexer
import com.openminis.app.data.memory.MemoryEmbeddingSupport
import com.openminis.app.util.Digest
import com.openminis.app.util.IsoTime
import android.util.Log
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Lightweight memory recall engine (port of XINCODE `MemoryRecall.kt`).
 *
 * Keyword recall ranks the active diary by BM25, recency and headline, not
 * by delivery count. Optional semantic recall fills remaining slots. Both
 * revalidate the current Markdown visibility and never search GLOBAL or SOUL.
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
    /**
     * [T-memory-embedding-recall] Optional semantic-recall support (#27).
     * Null (the default) keeps this engine keyword-FTS-only — the pre-#27
     * code path byte-identical, which is why every existing test runs against
     * engines built without this parameter. When present AND the provider
     * serves the query, [recallForPromptHybrid] appends cosine top-K hits
     * BELOW the keyword hits (same caps, same dedupe, same anti-nag
     * bookkeeping); the plain [recall] / [recallForPrompt] functions never
     * consult it, so a wired support cannot perturb the keyword path.
     */
    private val embeddingSupport: MemoryEmbeddingSupport? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Resolve at recall time so a cached engine also picks up a new reranker. */
    private val rerankerProvider: () -> MemoryReranker = { MemoryRerankerRegistry.active() },
) {

    companion object {
        private const val TAG = "MemoryRecallEngine"
        private const val RECALL_LIMIT = 4
        /** Per-file cap on the scoring pool, applied after df/idf are computed over the whole corpus. */
        private const val CANDIDATE_LIMIT = 48
        /** Per-file cap on the FINAL picks (diversity across sources). */
        internal const val MAX_HITS_PER_FILE = 2
        private const val RECENCY_WINDOW_DAYS = 30L
        private const val RECALL_COUNTS_FILE = ".recall-counts.json"
        private val opaqueCountKey = Regex("v2:[0-9a-f]{64}")

        internal fun recallCountKey(day: String, line: String): String = "v2:" + Digest.sha256Hex("$day:${line.trim()}")
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

        /**
         * [T-memory-embedding-wiring] Process-lifetime scope for fire-and-forget
         * semantic prefetches. One supervisor shared by every engine instance
         * (the per-session engine cache holds a bounded LRU set), so an
         * in-flight embedding call survives engine eviction without leaking a
         * scope per engine. Jobs are short (one embeddings request + merge);
         * failures inside them are caught and logged, never propagated.
         */
        private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /** recallCount — persisted to a JSON sidecar so the boost survives restarts. */
    private val recallCounts = ConcurrentHashMap<String, AtomicInteger>()

    // ── [T-memory-embedding-wiring] semantic prefetch cache ────────────────

    private data class PrefetchKey(val query: String, val turnId: String?, val version: Long)
    private class PrefetchAttempt(val key: PrefetchKey)
    private data class PrefetchResult(
        val key: PrefetchKey,
        val hits: List<RecallHit>,
        val completedAt: Long,
        val retrySoon: Boolean,
    )

    private val prefetchLock = Any()
    @Volatile private var prefetched: PrefetchResult? = null
    private var prefetchInFlight: PrefetchAttempt? = null

    data class RecallHit(
        val source: String,      // "GLOBAL.md" or "2026-09-18.md"
        val line: String,        // the matched line (snippet)
        val day: String,         // YYYY-MM-DD or "global"
        val score: Double,
        val recallCount: Int,
        val archived: Boolean,
        /** [T-memory-recall-explain] Score breakdown (keywords / recency / recall) for callers that want to surface *why* an entry matched. */
        val scoreDetail: String = "",
        /** Null on externally constructed hits; real scan results always carry their scope. */
        val directory: File? = null,
        val entryId: String? = null,
        val restoredAt: Long? = null,
        val entryDigest: String? = null,
    ) {
        internal val ref: MemoryEntryRef? get() = if (directory != null && entryId != null && entryDigest != null)
            MemoryEntryRef(directory, source, entryId, entryDigest) else null
        internal val announcementKey: String get() = "v2:" + Digest.sha256Hex(
            "$directory\u0000$source\u0000${entryId ?: line}\u0000$restoredAt",
        )
    }

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
        return if (safe.length <= MAX_HIT_CHARS) safe else memoryTextPrefix(safe, MAX_HIT_CHARS) + "…"
    }

    /**
     * Run a targeted recall over the DAILY memory logs: hygiene-filter the
     * lines, score candidates with a compact BM25 (IDF + length
     * normalisation), and return the top [RECALL_LIMIT] picks with a
     * per-file diversity cap.
     */
    fun recall(query: String): List<RecallHit> = recallAt(query, clock())

    private fun recallAt(query: String, now: Long, sources: Map<String, MemorySources.Visible>? = null): List<RecallHit> {
        val keywords = extractKeywords(query)
        if (keywords.isEmpty()) return emptyList()
        // A file or its replacement can expire without changing any of the previous hits.
        // A query-only memo would miss a previously hidden file becoming eligible again.
        val today = LocalDate.parse(IsoTime.formatLocalDate(now))

        // ── pass 1: collect candidate lines (hygiene-filtered) ─────────────
        class Raw(
            val file: String, val directory: File, val line: String, val day: String,
            val isHeadline: Boolean, val kwTokens: List<String>, val entry: MemoryLifecycle.Managed?,
        )
        val raws = mutableListOf<Raw>()
        for ((directory, name, view) in (sources ?: MemorySources.recallSources(fileScopes(), now)).values) {
            val day = dayKeyOf(name)
            for ((line, entry) in MemorySources.recallLines(view)) {
                val kwTokens = keywords.filter { line.contains(it, ignoreCase = true) }
                if (kwTokens.isEmpty()) continue
                val isHeadline = line.startsWith("#") || line.startsWith("-") || line.startsWith("*") || line.startsWith(">")
                raws.add(Raw(name, directory, line, day, isHeadline, kwTokens, entry))
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
            val rc = r.entry?.state?.usage ?: recallCounts[recallCountKey(r.day, r.line)]?.get() ?: 0
            val detail = "bm25=${"%.2f".format(kw)} recency=${"%.2f".format(recency)}"
            RecallHit(
                source = r.file,
                line = r.line,
                day = r.day,
                score = score,
                recallCount = rc,
                archived = false,
                scoreDetail = detail,
                directory = r.directory,
                entryId = r.entry?.ref?.id,
                restoredAt = r.entry?.state?.restoredAt,
                entryDigest = r.entry?.ref?.digest,
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
        // The reranker can reorder, never replace, add or remove picks.
        return runCatching { rerankerProvider().rerank(query, merged.toList()) }.getOrNull()
            ?.takeIf { it.size == merged.size && it.toSet() == merged.toSet() }
            ?: merged
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

    /** Legacy recall counter, never used as a delivery receipt. One write per batch. */
    fun acceptRecallBatch(hits: List<Pair<String, String>>) {
        if (hits.isEmpty()) return
        for ((source, line) in hits) {
            val key = recallCountKey(dayKeyOf(source), line)
            recallCounts.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
        }
        saveRecallCounts()
    }

    // ── recall-count persistence (sidecar JSON, atomic) ───────────────────

    internal fun recallCountsFile(): File = File(memoryDirProvider(), RECALL_COUNTS_FILE)

    fun loadRecallCounts() {
        val file = recallCountsFile()
        if (!file.isFile) return
        val text = readTextResilient(file, TAG) ?: return
        try {
            val obj = org.json.JSONObject(text)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optInt(k, 0)
                if (k.isBlank() || v <= 0) continue
                val key = if (opaqueCountKey.matches(k)) k else recallCountKey(k.substringBefore(':'), k.substringAfter(':', ""))
                recallCounts.merge(key, AtomicInteger(v)) { a, b -> if (a.get() >= b.get()) a else b }
            }
        } catch (_: Exception) {
            Log.w(TAG, "recall-counts sidecar unreadable, starting fresh")
        }
    }

    fun saveRecallCounts() {
        // [T-memory-recall-save-safe] Bookkeeping on the prompt path: a full disk
        // or a failed atomic move must not surface as a provider failure and kill
        // the reply (every sibling ledger already swallows).
        runCatching {
            val file = recallCountsFile()
            val obj = org.json.JSONObject()
            recallCounts.forEach { (k, v) -> if (v.get() >0) obj.put(k, v.get()) }
            writeTextAtomic(file, obj.toString())
        }
        // [T-memory-recall-io] Test seam: proves the prompt path writes once per
        // build instead of once per hit.
        saveCountForTest +=1
    }

    /** [T-memory-recall-io] Number of sidecar writes performed by this instance. */
    internal var saveCountForTest: Int = 0
        private set

    /** Snapshot for tests/debug: (key, count) pairs, sorted by key. */
    internal fun recallCountsSnapshot(): Map<String, Int> =
        recallCounts.entries.associate { it.key to it.value.get() }.toSortedMap()

    private fun fileScopes(): List<File> = listOfNotNull(memoryDirProvider(), sharedDirProvider?.invoke())

    private fun currentRecallHits(hits: List<RecallHit>, now: Long): List<RecallHit> {
        val names = hits.filter { it.directory != null }.mapTo(HashSet()) { it.source }
        if (names.isEmpty()) return hits
        val sources = MemorySources.recallSources(fileScopes(), now, names)
        val lines = sources.mapValues { (_, source) -> MemorySources.recallLines(source.view) }
        return hits.filter { hit ->
            val dir = hit.directory ?: return@filter true // Pure rendering callers can supply fixture hits.
            sources[hit.source]?.directory == dir && lines[hit.source].orEmpty().any { (line, entry) ->
                entry?.ref == hit.ref && entry?.state?.restoredAt == hit.restoredAt && matchesRecallLine(line, hit.line)
            }
        }
    }

    private fun matchesRecallLine(actual: String, hit: String): Boolean =
        actual == hit || memoryTextPrefix(actual, MemoryEmbeddingIndexer.MAX_LINE_CHARS) == hit

    /** [T-recall-delivery-accounting] Rendered fragment plus exactly the hits it carries. */
    data class RenderedFragment(
        val text: String,
        val included: List<RecallHit>,
    )

    /**
     * Format the hit list as a system-prompt fragment (mirrors XINCODE format).
     *
     * [T-recall-delivery-accounting] Callers must mark only [included] hits as
     * delivered: the fragment stops at [MAX_FRAGMENT_CHARS], and marking the
     * whole candidate list permanently suppressed every hit cut by the cap —
     * a memory never shown could never be announced again.
     */
    fun formatAsPromptFragmentWithHits(hits: List<RecallHit>, now: Long = clock()): RenderedFragment =
        renderCurrentHits(currentRecallHits(hits, now))

    /** Request projection has already revalidated this snapshot with [recallCandidatesForDelivery]. */
    internal fun renderCurrentHits(hits: List<RecallHit>): RenderedFragment {
        if (hits.isEmpty()) return RenderedFragment("", emptyList())
        val included = ArrayList<RecallHit>(hits.size)
        val body = buildString {
            append("## Recalled relevant memories\n")
            for (hit in hits) {
                val snippet = clipForPrompt(hit.line)
                if (snippet.isBlank()) continue
                val row = "- **${hit.source}**: $snippet\n"
                if (length + row.length > MAX_FRAGMENT_CHARS) break
                append(row)
                included += hit
            }
        }
        if (included.isEmpty()) return RenderedFragment("", emptyList())
        val text = MemoryAuthority.withEnvelope(
            body,
            MemoryTrust.UNVERIFIED_CANDIDATE,
            MemoryOrigin.AUTOMATIC_RECALL,
            use = InstructionUse.REFERENCE_DATA,
        )
        return RenderedFragment(text, included)
    }

    /** String-only projection for callers that do not track delivery. */
    fun formatAsPromptFragment(hits: List<RecallHit>): String =
        formatAsPromptFragmentWithHits(hits).text

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
     * live-recall block was drowning in). The dedupe keys on the HIT
     * instead — see [applyStreakFilter] for the exact semantics. The keyword
     * path never consults the embedding support (#27); use
     * [recallForPromptHybrid] for that.
     */
    fun recallForPrompt(query: String): List<RecallHit> = applyStreakFilter(recall(query))

    /**
     * [T-memory-recall-anti-nag] The hit-keyed cross-turn dedupe, extracted
     * verbatim from the original `recallForPrompt` body so the hybrid path
     * (#27) applies the SAME bookkeeping over the SAME key identity
     * (`"$day:$trimmed line"`) — no second counter, no double counting.
     *
     * A memory injected [RECALL_REPEAT_CAP] consecutive turns stops being
     * injected (the model has read it), while dropping out of the hit set —
     * a task pivot — resets its counter, so it is announced once again when
     * it becomes relevant anew.
     *
     * The streak update covers EVERY hit, including the ones filtered out:
     * a capped memory is still matching, and resetting its counter would
     * re-inject it next turn, defeating the cap. The update also runs on
     * EVERY outcome, including the empty one: an empty hit round IS the
     * task-pivot signal, and returning early would keep the capped counters
     * pinned — a memory that left the hits could then never re-arm
     * (announced again), defeating the whole drop-out semantics.
     */
    private fun applyStreakFilter(hits: List<RecallHit>): List<RecallHit> {
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

    /**
     * [T-memory-embedding-recall] The #27 hybrid recall: keyword FTS first,
     * then — ONLY when an embedding support is wired AND the provider serves
     * the query — cosine top-K hits appended BELOW the keyword hits. Every
     * keyword path stays byte-identical: [recall] and [recallForPrompt] never
     * touch the support, and a null support (default) short-circuits to the
     * plain keyword result.
     *
     * Weight rule: semantic hits fill only the slots the keyword path left
     * free under [RECALL_LIMIT] and the per-file diversity cap, so they can
     * never displace a keyword hit — "排序权重低于 FTS 命中". Dedupe runs on
     * the same key identity the anti-nag uses (`day:trimmed line`) plus the
     * pass-3 head-prefix guard, so a semantically-found line already surfaced
     * by keywords is not double-injected.
     *
     * Backfill: every hybrid call opportunistically triggers a throttled,
     * fire-and-forget re-index of this engine's memory scopes — a line whose
     * vector write failed earlier is repaired on the next recall
     * ("失败静默，下次检索触发补算").
     */
    suspend fun recallForPromptHybrid(query: String): List<RecallHit> =
        // Single accounting point: the bare body carries no anti-nag
        // bookkeeping; this wrapper applies it exactly once. The inline copy
        // this used to be drifted from [recallHybridUnaccounted] — keep the
        // delegation, the duplication was the double-counting bug's cousin.
        applyStreakFilter(recallHybridUnaccounted(query))

    /**
     * The hybrid body WITHOUT the anti-nag bookkeeping: FTS + semantic fusion
     * only. [recallForPromptHybrid] wraps this with [applyStreakFilter]; the
     * PREFETCH path ([fillPrefetch]) must use this bare form instead — a
     * prefetched result that loses the race against the synchronous render is
     * never injected, and a streak counted for a turn that never saw the hit
     * would silently shorten the REPEAT_CAP window (the double-counting bug).
     * Bookkeeping happens exactly once, at consumption time, inside
     * [recallForPromptCached].
     */
    private suspend fun recallHybridUnaccounted(query: String): List<RecallHit> {
        val semantic = fetchSemantic(query)
        return mergeCurrentRecall(query, clock(), semantic)
    }

    private suspend fun fetchSemantic(query: String): List<MemoryEmbeddingSupport.SemanticHit> {
        val support = embeddingSupport ?: return emptyList()
        val context = currentCoroutineContext()
        context.ensureActive()
        val scopes = fileScopes()
        val hits = try {
            support.semanticHits(query, RECALL_LIMIT, scopes.map { it.absolutePath })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        context.ensureActive()
        try {
            support.backfillAsync(scopes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Backfill is opportunistic and must not suppress a usable lookup.
        }
        context.ensureActive()
        return hits.take(RECALL_LIMIT)
    }

    /** Build one ranked snapshot; consumption only revalidates its shortlisted hits. */
    private fun mergeCurrentRecall(query: String, now: Long, semantic: List<MemoryEmbeddingSupport.SemanticHit>): List<RecallHit> {
        if (semantic.isEmpty()) return recallAt(query, now)
        val sources = MemorySources.recallSources(fileScopes(), now)
        val fts = recallAt(query, now, sources)
        val lines = HashMap<String, List<MemoryLifecycle.OwnedLine>>()
        val resolved = semantic.mapNotNull { hit ->
            val source = sources[hit.source] ?: return@mapNotNull null
            if (hit.scope != null && hit.scope != source.directory.path) return@mapNotNull null
            val line = lines.getOrPut(hit.source) { MemorySources.recallLines(source.view) }
                .firstOrNull { matchesRecallLine(it.text, hit.line) } ?: return@mapNotNull null
            hit to line.entry
        }
        val owners = resolved.associate { (hit, owner) -> (hit.source to hit.line) to owner }
        return SemanticRecallFusion.merge(
            fts = fts, semantic = resolved.map { it.first }, limit = RECALL_LIMIT,
            perFileCap = MAX_HITS_PER_FILE, archiveCutoff = null, dayKeyOf = ::dayKeyOf,
        ).map { hit ->
            if (hit.directory != null) hit else {
                val owner = owners[hit.source to hit.line]
                hit.copy(directory = sources.getValue(hit.source).directory, entryId = owner?.ref?.id,
                    recallCount = owner?.state?.usage ?: 0, restoredAt = owner?.state?.restoredAt,
                    entryDigest = owner?.ref?.digest)
            }
        }
    }

    /** Real requests may prefetch; repeated projections only read the bounded result. */
    fun prefetchSemantic(query: String, turnId: String? = null) {
        startPrefetch(query, turnId)
    }

    private fun startPrefetch(query: String, turnId: String?): kotlinx.coroutines.Job? {
        if (embeddingSupport == null || query.isBlank()) return null
        val attempt = synchronized(prefetchLock) {
            val key = PrefetchKey(query, turnId, MemoryLifecycle.recallGeneration())
            if (prefetchInFlight?.key == key || recentPrefetch(key) != null) return null
            PrefetchAttempt(key).also { prefetchInFlight = it }
        }
        return prefetchScope.launch {
            try {
                fillPrefetch(attempt)
            } finally {
                synchronized(prefetchLock) {
                    if (prefetchInFlight === attempt) prefetchInFlight = null
                }
            }
        }
    }

    private fun recentPrefetch(key: PrefetchKey): PrefetchResult? = prefetched?.takeIf {
        // Failed/empty vector lookups back off briefly even if BM25 returned candidates.
        val lifetime = if (it.retrySoon) 5_000L else 30_000L
        it.key == key && clock() - it.completedAt in 0 until lifetime
    }

    private suspend fun fillPrefetch(attempt: PrefetchAttempt) {
        val context = currentCoroutineContext()
        val query = attempt.key.query
        val (hits, retrySoon) = try {
            val semantic = fetchSemantic(query)
            val hits = mergeCurrentRecall(query, clock(), semantic)
            hits to (semantic.isEmpty() || hits.isEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "semantic prefetch failed: ${e.javaClass.simpleName}")
            emptyList<RecallHit>() to true
        }
        synchronized(prefetchLock) {
            context.ensureActive()
            // An older completion must not clobber a more recent request or keep a stale generation.
            if (prefetchInFlight !== attempt) return
            if (MemoryLifecycle.recallGeneration() == attempt.key.version) {
                prefetched = PrefetchResult(attempt.key, hits, clock(), retrySoon)
            }
        }
    }

    /** Legacy caller that consumes its one-shot prefetch. Production request previews do not use it. */
    fun recallForPromptCached(query: String): List<RecallHit> {
        val key = PrefetchKey(query, null, MemoryLifecycle.recallGeneration())
        val hit = synchronized(prefetchLock) {
            recentPrefetch(key)?.also { prefetched = null }
        }
        return applyStreakFilter(currentPrefetchHits(hit, clock()) ?: recall(query))
    }

    private fun currentPrefetchHits(result: PrefetchResult?, now: Long): List<RecallHit>? {
        if (result == null || result.hits.isEmpty()) return null
        val active = currentRecallHits(result.hits, now)
        return active.takeIf { it.size == result.hits.size && result.key.version == MemoryLifecycle.recallGeneration() }
    }

    /** Dispatch/preview read: do not consume prefetch, start network, or advance anti-nag counters. */
    internal fun recallCandidatesForDelivery(query: String, now: Long, turnId: String? = null): List<RecallHit> {
        val key = PrefetchKey(query, turnId, MemoryLifecycle.recallGeneration())
        // Cache age uses the lookup clock. The earlier projection clock is for visibility only.
        return currentPrefetchHits(recentPrefetch(key), now) ?: recallAt(query, now)
    }

    /** Await the same scheduling/dedup path as a real request. */
    internal suspend fun prefetchSemanticForTest(query: String, turnId: String? = null) {
        startPrefetch(query, turnId)?.join()
    }
}
