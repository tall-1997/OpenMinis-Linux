package com.openminis.app.provider

import java.util.Locale

/**
 * Relay stations publish ids like `GPT-6免费` / `免费GPT-6 Astra`. After
 * exact and normalized catalog lookup fail, pick the candidate whose id/name
 * shares the most distinctive characters (命中最多字) with the stripped query.
 *
 * Generic brand-only overlap (`GPT`, `Claude`) is rejected so a local `GPT聊天`
 * cannot inherit gpt-4o limits.
 */
internal object ModelAliasMatcher {

    private val NOISE = listOf(
        "非官方", "中转站", "中转", "免费", "付费", "高速", "官方", "最新",
        "特价", "稳定", "镜像", "逆向", "公益", "测试", "试用", "畅享",
        "无限", "企业", "国内", "海外", "直连", "原生", "限时", "优惠",
        "折扣", "便宜", "满血", "渠道", "线路", "特供",
        // [T-modelsdev-cjk-noise] Chinese dirty-field markers a relay appends to
        // a bare model id. Without these, `grok4.6破甲` tokenizes with the
        // marker glued to the version (`grok4-6破甲`), matches nothing at any
        // resolution stage, and silently lands on the hardcoded fallback even
        // though the catalog has an exact `grok-4.6` entry. The CJK run is not
        // removed by the punctuation rules that handle Latin noise — it needs
        // its own entries here.
        "破甲", "白嫖", "公益站", "公益", "开挂", "秒开", "满配", "内部",
        "群友", "自用", "共享", "号池", "至尊", "豪华", "旗舰", "顶配",
        "unofficial", "unlimited", "official", "premium", "discount",
        "reverse", "mirror", "latest", "trial", "promo", "cheap", "free",
        "fast", "test",
    ).sortedByDescending { it.length }

    private val GENERIC = setOf(
        "gpt", "openai", "claude", "anthropic", "gemini", "google", "grok",
        "xai", "qwen", "llama", "mistral", "deepseek", "glm", "kimi",
        "moonshot", "model", "chat", "llm", "ai",
    )

    private val VARIANTS = listOf("pro", "instant", "mini", "lite", "nano", "flash")

    /**
     * [T-modelsdev-relay-noise-catalog-driven] Catalog-side tokenisation. It
     * normalises punctuation and letter/digit runs and DROPS non-ASCII letters
     * (relay decoration — the catalog itself contains no CJK ids), but it never
     * strips a noise WORD: `free`, `fast`, `latest`, `max`, `plus` and
     * `thinking` are all real models.dev ids, and removing them here collapses
     * `kimi-k3-free` onto `kimi-k3` before the variant penalty can tell them
     * apart. Human-facing search-query scrubbing stays in [stripNoise].
     */
    fun catalogTokens(text: String): List<String> = tokensInternal(text, stripNoiseWords = false)

    /**
     * [T-modelsdev-relay-noise-catalog-driven] Version agreement. A query whose
     * id carries digits may only fuzzy-match a candidate whose id carries the
     * SAME digit set. Without it `deepseek-v4` matched `deepseek-flash` — whose
     * display name is "DeepSeek V4.1 Flash" — and inherited a 393216 output cap.
     */
    fun versionCompatible(queryId: String, candidateId: String): Boolean {
        val q = versionDigits(queryId)
        if (q.isEmpty()) return true
        val c = versionDigits(candidateId)
        return c.isNotEmpty() && c == q
    }

    

    private fun versionDigits(id: String): Set<String> =
        ModelsDevApi.normalizedModelKey(id)
            .split('-')
            .filter { it.isNotEmpty() && it.all { ch -> ch.isDigit() } }
            .toSet()

    fun stripNoise(text: String): String {
        var s = text
        for (n in NOISE) {
            s = s.replace(n, " ", ignoreCase = true)
        }
        return s.replace(Regex("\\s+"), " ").trim().trim('-', '_', '/')
    }

    fun tokens(text: String): List<String> = tokensInternal(text, stripNoiseWords = true)

    private val WHITESPACE_RUN = Regex("""[\s]+""")

    private fun tokensInternal(text: String, stripNoiseWords: Boolean): List<String> {
        val source = if (stripNoiseWords) stripNoise(text) else text
        // Reuse the id normalizer so scoring speaks exactly the same language as
        // the index lookup: lowercase, unified separators, letter/digit splits,
        // non-ASCII relay decoration dropped.
        val parts = source.split(WHITESPACE_RUN)
            .map { ModelsDevApi.normalizedModelKey(it) }
            .filter { it.isNotEmpty() }
            .flatMap { it.split('-') }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out.addAll(parts)
        for (i in 0 until parts.size - 1) {
            out.add(parts[i] + "-" + parts[i + 1])
        }
        if (parts.size >= 3) {
            out.add(parts.take(3).joinToString("-"))
            out.add(parts.takeLast(3).joinToString("-"))
        }
        return out.toList()
    }

    fun <T> resolveBest(
        query: String?,
        candidates: List<T>,
        idOf: (T) -> String,
        nameOf: (T) -> String,
    ): T? {
        val normalized = query?.trim()?.takeIf(String::isNotEmpty) ?: return null
        candidates.firstOrNull {
            idOf(it).equals(normalized, ignoreCase = true) || nameOf(it).equals(normalized, ignoreCase = true)
        }?.let { return it }
        return pickBest(
            queryId = normalized,
            queryName = normalized,
            candidates = candidates,
            tokensOf = { catalogTokens("${idOf(it)} ${nameOf(it)}").toSet() },
            idOf = idOf,
        )
    }

    fun <T> pickBest(
        queryId: String,
        queryName: String,
        candidates: List<T>,
        tokensOf: (T) -> Set<String>,
        idOf: (T) -> String,
        versionCompatible: (T) -> Boolean = { true },
    ): T? {
        if (candidates.isEmpty()) return null
        val queryTokens = catalogTokens("$queryId $queryName")
        if (queryTokens.isEmpty()) return null
        var best: T? = null
        var bestScore = Int.MIN_VALUE
        var bestExtra = Int.MAX_VALUE
        var bestLen = Int.MAX_VALUE
        for (c in candidates) {
            if (!versionCompatible(c)) continue
            val ct = tokensOf(c)
            val matched = matchedTokens(queryTokens, ct)
            val score = matched.sumOf { it.length }
            if (!isAcceptable(matched, score)) continue
            val extra = extraAtomic(queryTokens, ct)
            val len = idOf(c).length
            val better = when {
                score > bestScore -> true
                score < bestScore -> false
                extra < bestExtra -> true
                extra > bestExtra -> false
                len < bestLen -> true
                else -> false
            }
            if (better) {
                best = c
                bestScore = score
                bestExtra = extra
                bestLen = len
            }
        }
        return best
    }

    internal fun matchedTokens(queryTokens: List<String>, candidateTokens: Set<String>): List<String> {
        val sorted = queryTokens.distinct().sortedByDescending { it.length }
        val matched = mutableListOf<String>()
        for (t in sorted) {
            if (t !in candidateTokens) continue
            if (matched.any { it.contains(t) }) continue
            matched.add(t)
        }
        return matched
    }

    internal fun isAcceptable(matched: List<String>, score: Int): Boolean {
        if (score < 5) return false
        // [T-modelsdev-relay-noise-catalog-driven] A generic brand word may
        // never carry a match on its own. `my-own-model-x` used to inherit
        // llmgateway/custom ("Custom Model") purely on the token `model`,
        // because the compound test accepted any 5+ character token.
        val distinctive = matched.filter { it !in GENERIC }
        if (distinctive.isEmpty()) return false
        val hasVersion = distinctive.any { tok -> tok.any { it.isDigit() } }
        val hasName = distinctive.any { '-' !in it && it.length >= 4 }
        val hasCompound = distinctive.any { '-' in it && it.length >= 5 }
        return hasVersion || hasName || hasCompound
    }

    private fun extraAtomic(queryTokens: List<String>, candidateTokens: Set<String>): Int {
        val q = queryTokens.toSet()
        var extra = candidateTokens.count { t ->
            '-' !in t && t.length >= 3 && t !in q && t !in GENERIC
        }
        for (v in VARIANTS) {
            val qHas = v in q
            val cHas = v in candidateTokens
            if (qHas != cHas) extra += 5
        }
        return extra
    }
}
