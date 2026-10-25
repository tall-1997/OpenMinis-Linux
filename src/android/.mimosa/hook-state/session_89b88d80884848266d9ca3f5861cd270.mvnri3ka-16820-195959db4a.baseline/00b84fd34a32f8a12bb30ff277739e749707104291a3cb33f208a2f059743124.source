package com.openminis.app.ui.chat

import androidx.compose.ui.text.AnnotatedString
import com.openminis.app.text.BoundedText
import java.util.LinkedHashMap

internal object MarkdownParseCaches {
    // [T-android-parse-lru-char-budget] (#759) Per-cache character budget,
    // replacing the previous fixed entry count of 768.
    //
    // The old cap-by-count rule blew up on Larky-class sessions: a 33KB
    // assistant message produces hundreds of cached entries (one per
    // paragraph / table / list item / math snippet), and 768 entries × an
    // average per-entry text length of 100KB+ of inline `AnnotatedString`
    // backing arrays cleared tens of megabytes of resident heap before
    // eviction kicked in. Cap on source characters instead so the cache
    // bytes scale with the source bytes, not with the entry count.
    //
    // Why 2 MB (= 2,000,000 chars) per cache:
    //   - For Larky's worst case (1.9 MB session, single message up to
    //     ~33 KB): the budget holds ~60 distinct 33 KB messages or
    //     ~1500 distinct 1 KB chat blocks — plenty for the tail-window
    //     scroll range (200 messages × ~50 paragraphs avg).
    //   - For typical sessions (1–2 KB messages): ~1000+ entries, ≥ the
    //     previous count-based cap; hit-rate effectively identical to
    //     the 768-entry cap.
    //   - Across the three caches that's 6 MB worst-case (chars only;
    //     AnnotatedString backing arrays are larger but scale with the
    //     same source). On a Pixel-class device with ~96 MB heap budget,
    //     6 MB is acceptable; was previously unbounded by chars on the
    //     33 KB tail of the distribution.
    private const val CHAR_BUDGET_PER_CACHE = 2_000_000

    /**
     * Access-order LinkedHashMap with eviction driven by a running
     * character total rather than entry count. [sizer] returns the number
     * of source characters each (key, value) pair contributes (we count
     * source-side bytes — the canonical input — rather than
     * AnnotatedString output, because output sizes are not easily
     * computable and source length is the dominant correlate anyway).
     *
     * `get` continues to refresh recency via LinkedHashMap's accessOrder;
     * `put` is overridden to maintain [totalChars] and trim trailing
     * eldest entries until the running total fits in [budget], while
     * always keeping at least one entry — see the put loop guard. This
     * preserves the "single oversized message lands in the cache without
     * an infinite eviction loop" invariant required by the spec.
     */
    private class Lru<K, V>(
        private val budget: Int,
        private val sizer: (K, V) -> Int,
    ) : LinkedHashMap<K, V>(64, 0.75f, true) {
        var totalChars: Int = 0
            private set

        override fun put(key: K, value: V): V? {
            val prior = super.put(key, value)
            // The map call above may have replaced an existing entry —
            // adjust the running total by the difference, not the new
            // value alone. Also defends against accidental double-count
            // when the same key is re-put with a different value.
            if (prior != null) totalChars -= sizer(key, prior).coerceAtLeast(0)
            totalChars += sizer(key, value).coerceAtLeast(0)
            // Trim eldest entries until under budget. Always keep at
            // least one entry alive so a single message larger than
            // budget still gets cached (its first parse is the
            // expensive one; we eat the over-budget transient until
            // any other put displaces it).
            while (totalChars > budget && size > 1) {
                val eldest = entries.iterator().next()
                totalChars -= sizer(eldest.key, eldest.value).coerceAtLeast(0)
                entries.remove(eldest)
            }
            return prior
        }

        override fun remove(key: K): V? {
            val v = super.remove(key) ?: return null
            totalChars -= sizer(key, v).coerceAtLeast(0)
            return v
        }

        override fun clear() {
            super.clear()
            totalChars = 0
        }

        // Suppress the count-based eviction path inherited from
        // LinkedHashMap; our own put() does the work and removeEldestEntry
        // would race with our totalChars bookkeeping if both fired.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = false
    }

    private val inlineLru = Lru<Pair<String, MdColors>, AnnotatedString>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.first.length },
    )
    private val mathLru = Lru<String, List<String>>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.length },
    )
    private val blocksLru = Lru<String, List<MdBlock>>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.length },
    )

    // Double-checked get: the lock is held only for map access, never during a
    // parse — a slow parse on Default must not block a main-thread hit on a
    // DIFFERENT key. A concurrent miss on the same key computes twice and the
    // results are equal immutable values; harmless.
    fun inline(text: String, colors: MdColors): AnnotatedString {
        val key = text to colors
        synchronized(inlineLru) { inlineLru[key] }?.let { return it }
        val t0 = System.nanoTime()
        val computed = parseInline(text, colors)
        maybeLogSlowParse("inline", text.length, (System.nanoTime() - t0) / 1_000_000)
        synchronized(inlineLru) { inlineLru[key] = computed }
        return computed
    }

    fun mathLatex(text: String): List<String> {
        synchronized(mathLru) { mathLru[text] }?.let { return it }
        val t0 = System.nanoTime()
        val computed = collectInlineMathLatex(text)
        maybeLogSlowParse("math", text.length, (System.nanoTime() - t0) / 1_000_000)
        synchronized(mathLru) { mathLru[text] = computed }
        return computed
    }

    // ─── [T-android-streaming-incremental-inline] Live-tail incremental parse ──
    //
    // The streaming live fragment is (usually) a single growing paragraph. Its
    // `raw` changes every throttle tick, so `inline()` / `mathLatex()` MISS the
    // cache every tick and re-scan the WHOLE accumulated paragraph char-by-char
    // — the confirmed 81%-of-hang-stacks inline/math regex hotspot + Matcher
    // allocation GC storm (see minis-2026-07-06.log analysis).
    //
    // Incremental fix: find a SAFE closed boundary (a newline where every inline
    // construct — bold/italic/strike/code/math/link — is balanced), parse the
    // frozen prefix ONCE (it only advances in discrete jumps, so it's a cache
    // HIT across ticks), and re-parse only the short unclosed suffix each tick,
    // then splice. Correctness rests on `safeInlineSplitOffset` never splitting
    // inside an open construct, so parse(prefix) ++ parse(suffix) == parse(full).

    /** Below this length the whole-fragment parse is sub-ms; incremental
     *  splitting/splicing overhead isn't worth it. */
    private const val INCREMENTAL_MIN_CHARS = 1_500

    /** Incremental inline parse for the live streaming tail. Returns the exact
     *  same AnnotatedString as `inline(text, colors)` would, but reuses a cached
     *  parse of the closed prefix and only scans the unclosed suffix. Falls back
     *  to the plain cached path for short text or when no safe boundary exists. */
    fun inlineIncremental(text: String, colors: MdColors): AnnotatedString {
        // Exact-match cache hit (e.g. a re-publish of the same content) — free.
        synchronized(inlineLru) { inlineLru[text to colors] }?.let { return it }
        if (text.length < INCREMENTAL_MIN_CHARS) return inline(text, colors)
        val split = safeInlineSplitOffset(text)
        if (split <= 0) return inline(text, colors)
        // Prefix goes through the normal cache: identical across ticks until the
        // boundary advances, so this is a HIT on all but the (rare) advance tick.
        val prefixAnn = inline(text.substring(0, split), colors)
        val t0 = System.nanoTime()
        val suffixAnn = parseInline(text.substring(split), colors)
        maybeLogSlowParse("inline-incr", text.length - split, (System.nanoTime() - t0) / 1_000_000)
        return androidx.compose.ui.text.buildAnnotatedString {
            append(prefixAnn)
            append(suffixAnn)
        }
    }

    /** Incremental math-latex collection for the live streaming tail. Same
     *  contract as `mathLatex(text)`; reuses the closed prefix's list. */
    fun mathLatexIncremental(text: String): List<String> {
        synchronized(mathLru) { mathLru[text] }?.let { return it }
        if (text.length < INCREMENTAL_MIN_CHARS) return mathLatex(text)
        val split = safeInlineSplitOffset(text)
        if (split <= 0) return mathLatex(text)
        val prefix = mathLatex(text.substring(0, split))
        val suffix = collectInlineMathLatex(text.substring(split))
        return if (suffix.isEmpty()) prefix else prefix + suffix
    }

    /**
     * [T-android-jank-diag-logging] Threshold-gated slow-parse visibility:
     * silent in normal operation, one INFO line when a SINGLE parse exceeds
     * [SLOW_PARSE_LOG_MS]. thread=main is exactly the signal a future jank
     * report needs — it means a parse escaped every off-main path.
     */
    private const val SLOW_PARSE_LOG_MS = 80L
    private fun maybeLogSlowParse(layer: String, chars: Int, ms: Long, extra: String = "") {
        if (ms < SLOW_PARSE_LOG_MS) return
        val thread = if (android.os.Looper.getMainLooper().isCurrentThread) {
            "main"
        } else {
            Thread.currentThread().name
        }
        com.openminis.app.logging.AppLogger.info(
            "JankDiag",
            "[JankDiag] slow markdown parse layer=$layer chars=$chars ms=$ms thread=$thread$extra",
        )
    }

    /** [T-android-coldload-offmain-parse] Lock-only cache peek — never
     *  computes. Lets the frozen render path keep cache HITs synchronous
     *  while routing big MISSes off-main. */
    fun cachedBlocks(raw: String): List<MdBlock>? =
        synchronized(blocksLru) { blocksLru[raw] }

    /** [T-android-review-p1-fixes] F2(a): freeze-edge handoff — the live
     *  branch deposits its parse result here so the frozen branch HITs
     *  synchronously when the segment freezes (stream end / live→frozen
     *  migration) instead of flashing the plain-text preview while an
     *  off-main re-parse runs. */
    fun putBlocks(raw: String, blocks: List<MdBlock>) {
        if (raw.length > BoundedText.MAX_MARKDOWN_PARSE_CHARS) return
        synchronized(blocksLru) { blocksLru[raw] = blocks }
    }

    /** Block-level parse for a FROZEN fragment. First parse may run on the
     *  caller's thread (once per distinct fragment text process-wide); scroll
     *  away/return and session re-entry are hits. */
    fun blocks(raw: String): List<MdBlock> {
        val cacheable = raw.length <= BoundedText.MAX_MARKDOWN_PARSE_CHARS
        if (cacheable) {
            synchronized(blocksLru) { blocksLru[raw] }?.let { return it }
        }
        val t0 = System.nanoTime()
        val computed = parseMarkdownBlocksBlocking(raw)
        maybeLogSlowParse(
            "blocks", raw.length, (System.nanoTime() - t0) / 1_000_000,
            extra = " blocks=${computed.size}",
        )
        if (cacheable) {
            synchronized(blocksLru) { blocksLru[raw] = computed }
        }
        return computed
    }

    /** Pre-compute everything RenderBlock will ask for, off-main. Walks the
     *  same texts the RenderBlock branches feed to inline()/mathLatex();
     *  anything missed simply computes lazily on first composition (once). */
    fun prewarm(blocks: List<MdBlock>, colors: MdColors) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Paragraph -> { inline(b.raw, colors); mathLatex(b.raw) }
                is MdBlock.Heading -> { inline(b.text, colors); mathLatex(b.text) }
                is MdBlock.UnorderedList -> b.items.forEach {
                    inline(it.text, colors); mathLatex(it.text); prewarm(it.children, colors)
                }
                is MdBlock.OrderedList -> b.items.forEach {
                    inline(it.text, colors); mathLatex(it.text); prewarm(it.children, colors)
                }
                is MdBlock.TaskList -> b.items.forEach { inline(it.text, colors); mathLatex(it.text) }
                is MdBlock.Table -> {
                    b.headers.forEach { inline(it, colors); mathLatex(it) }
                    b.rows.forEach { row -> row.forEach { inline(it, colors); mathLatex(it) } }
                }
                is MdBlock.BlockQuote -> prewarm(b.innerBlocks, colors)
                else -> Unit // code blocks / media / HR / math-display don't inline-parse
            }
        }
    }

    /**
     * [T-android-streaming-incremental-inline] Off-main prewarm for the LIVE
     * tail block. Warms the closed-prefix inline/math cache so the main-thread
     * `inlineIncremental` / `mathLatexIncremental` (which RenderBlock calls for
     * the live block) resolve to a prefix HIT + a tiny suffix scan. Only the
     * LAST block is the live one; earlier blocks are frozen and handled by the
     * normal [prewarm] above. No-op for non-Paragraph tails (they don't take
     * the incremental path in RenderBlock).
     */
    fun prewarmLiveTail(blocks: List<MdBlock>, colors: MdColors) {
        val last = blocks.lastOrNull() as? MdBlock.Paragraph ?: return
        // inlineIncremental/mathLatexIncremental internally split at the safe
        // boundary and reuse inline(prefix)/mathLatex(prefix). Computing them
        // here (off-main) both warms the prefix AND memoizes the exact full-text
        // result under the plain key, so the main-thread call is a clean HIT.
        val ann = inlineIncremental(last.raw, colors)
        synchronized(inlineLru) { inlineLru[last.raw to colors] = ann }
        val math = mathLatexIncremental(last.raw)
        synchronized(mathLru) { mathLru[last.raw] = math }
    }
}

