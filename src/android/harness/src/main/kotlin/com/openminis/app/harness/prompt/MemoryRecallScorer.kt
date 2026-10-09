package com.openminis.app.harness.prompt

import kotlin.math.ln

/**
 * [T-taixu-2.5] 记忆召回打分器（MemoryRecallSelector.selectRecall 的纯半边，
 * Adapted from taixu GPL-3.0）。
 *
 * 线性关键词计数的缺陷：常见 bigram（"我们"/"一个"）与判别词（"BM25"）同权，
 * 一行塞满常见词就能霸榜。BM25 的 IDF 加权让稀有查询词更值钱——这是本移植
 * 的价值主张，不是搬运装饰。
 *
 * 决策链逐条保留：泛化轮次抑制（"继续"/"好的"不触发召回）→ 分词（拉丁整词 +
 * CJK 2-gram——bigram 让「调用链」命中「调用链路分析」，避免散字误命中）→
 * BM25（k1=1.2/b=0.75 标准参数）→ scope 加权（project 1.2 / global 1.0 /
 * session 0.9）× 新鲜度降权（<30 天 1.0 / <180 天 0.85 / 更旧 0.7）→ top-5。
 *
 * 纯函数、无 Android 依赖；候选形状是普通数据类，宿主把自己的存储（我方是
 * 文件行级扫描）适配进来。
 */
object MemoryRecallScorer {

    /** 注入条数预算（对齐 Reasonix 的 ≤4 条口径，略放宽为 5）。 */
    const val MAX_RECALL_FACTS = 5

    /** BM25 参数（标准取值）。 */
    private const val K1 = 1.2
    private const val B = 0.75

    /** 泛化短语：短消息整体命中即不触发召回（"继续帮我改代码"不受影响）。 */
    private val GENERIC_PHRASES = listOf(
        "继续", "接着来", "然后呢", "好的", "收到", "可以", "开始吧", "行", "嗯", "哦",
        "continue", "go on", "keep going", "ok", "okay", "next", "yes",
    )

    /** 泛化 token：分词后逐个剔除，剩下的信息词才是有效查询。 */
    private val GENERIC_TERMS = setOf(
        "继续", "好的", "收到", "可以", "开始", "然后", "一下", "这个", "那个", "怎么", "什么",
        "帮忙", "请", "我", "你", "的", "了", "吗", "吧", "呢", "看", "讲", "说",
        "continue", "please", "the", "and", "what", "how",
    )

    /** 召回候选的接缝形状：key/value/scope/updatedAt，宿主存储适配进来。 */
    data class Candidate(
        val key: String,
        val value: String,
        val scope: String = "global",
        val updatedAt: Long = 0L,
    )

    data class Scored(
        val candidate: Candidate,
        val score: Double,
    )

    /**
     * 泛化抑制 + 分词 + 泛化 token 剔除后的有效查询词；空 = 本轮不该召回。
     * 宿主引擎可只用低层件（[effectiveQueryTerms] + [bm25]）保留自己的
     * 加权语义，也可以走 [selectRecall] 的完整决策链。
     */
    fun effectiveQueryTerms(query: String): List<String> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return emptyList()
        val shortGeneric = normalized.length <= 4 &&
            GENERIC_PHRASES.any { normalized == it || normalized.contains(it) }
        if (shortGeneric || normalized in GENERIC_PHRASES) return emptyList()
        return tokenize(query).filter { it !in GENERIC_TERMS }
    }

    /**
     * BM25 召回选择（纯函数）。返回按分降序的 top-[MAX_RECALL_FACTS]；
     * 泛化轮次或无有效查询词时返回空。
     */
    fun selectRecall(
        query: String,
        candidates: List<Candidate>,
        now: Long = System.currentTimeMillis(),
    ): List<Scored> {
        val queryTerms = effectiveQueryTerms(query)
        if (queryTerms.isEmpty()) return emptyList()

        // [T-cjk-unigram-recall] 单字 CJK 查询词的 unigram 回退：分词是
        // bigram-only，单字查询词（如「问」）只作为 bigram 组成部分出现在文档侧，
        // 永远命不中任何文档词——单字召回从「有」降为「无」。给文档补上被
        // bigram 切词吞掉的该字出现次数（unigram tf），BM25/IDF 数学不变；
        // 多字查询词仍走纯 bigram 路径（避免散字误命中）。
        val singleCharTerms = queryTerms.filter { it.length == 1 && CJK_SEGMENT.matches(it) }
        val documents = if (singleCharTerms.isEmpty()) {
            candidates.map { tokenize(it.key + "\n" + it.value) }
        } else {
            candidates.map { candidate ->
                val text = candidate.key + "\n" + candidate.value
                val doc = tokenize(text).toMutableList()
                singleCharTerms.forEach { term ->
                    val ch = term[0]
                    repeat(unigramTfInBigramSegments(text, ch)) { doc += term }
                }
                doc
            }
        }
        if (documents.all { it.isEmpty() }) return emptyList()
        val scores = bm25(queryTerms, documents)

        val ranked = candidates.indices.mapNotNull { index ->
            val score = scores[index]
            if (score <= 0.0) return@mapNotNull null
            val candidate = candidates[index]
            val scopeBoost = when (candidate.scope) {
                "project" -> 1.2
                "session" -> 0.9
                else -> 1.0
            }
            val ageDays = ((now - candidate.updatedAt).coerceAtLeast(0)) / 86_400_000.0
            val freshness = when {
                ageDays < 30 -> 1.0
                ageDays < 180 -> 0.85
                else -> 0.7
            }
            Scored(candidate, score * scopeBoost * freshness)
        }.sortedByDescending { it.score }

        return ranked.take(MAX_RECALL_FACTS)
    }

    /**
     * 分词：拉丁/数字按整词小写；CJK 连续段切 2-gram（单字段保留单字）。
     */
    fun tokenize(text: String): List<String> {
        val terms = mutableListOf<String>()
        LATIN_WORD.findAll(text).forEach { terms += it.value.lowercase() }
        CJK_SEGMENT.findAll(text).forEach { segment ->
            val value = segment.value
            if (value.length == 1) {
                terms += value
            } else {
                for (index in 0 until value.length - 1) {
                    terms += value.substring(index, index + 2)
                }
            }
        }
        return terms
    }

    /** 标准 BM25（k1=1.2/b=0.75）；公开给宿主引擎做 IDF 加权组件。 */
    fun bm25(queryTerms: List<String>, documents: List<List<String>>): List<Double> {
        val documentFrequency = HashMap<String, Int>()
        documents.forEach { doc ->
            doc.distinct().forEach { term -> documentFrequency.merge(term, 1, Int::plus) }
        }
        val total = documents.size.coerceAtLeast(1)
        val avgLength = documents.sumOf { it.size }.toDouble().div(total).coerceAtLeast(1.0)
        return documents.map { doc ->
            val termFrequency = HashMap<String, Int>()
            doc.forEach { term -> termFrequency.merge(term, 1, Int::plus) }
            val length = doc.size.coerceAtLeast(1)
            queryTerms.distinct().sumOf { term ->
                val df = documentFrequency.getOrDefault(term, 0)
                val frequency = termFrequency.getOrDefault(term, 0).toDouble()
                if (frequency == 0.0 || df == 0) {
                    0.0
                } else {
                    val idf = ln(1 + (total - df + 0.5) / (df + 0.5))
                    idf * (frequency * (K1 + 1)) /
                        (frequency + K1 * (1 - B + B * length / avgLength))
                }
            }
        }
    }

    private val LATIN_WORD = Regex("[A-Za-z0-9_]+")
    private val CJK_SEGMENT = Regex("[\\u3400-\\u9fff\\uf900-\\ufaff]+")

    /** 被 bigram 切词吞掉的 `ch` 出现次数（多字 CJK 段内；单字段已在 tokenize 成为词）。 */
    private fun unigramTfInBigramSegments(text: String, ch: Char): Int =
        CJK_SEGMENT.findAll(text)
            .filter { it.value.length > 1 }
            .sumOf { segment -> segment.value.count { it == ch } }
}
