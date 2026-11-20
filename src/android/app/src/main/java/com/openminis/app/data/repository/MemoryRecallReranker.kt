package com.openminis.app.data.repository

/**
 * [T-memory-reranker-seam] The extension point `4.3` asked for: let a stronger
 * backend reorder (or drop) the BM25 candidates without teaching the engine
 * about embeddings.
 *
 * WHY a seam and not an implementation: the keyword engine is the only scorer
 * that works with zero setup, and it is good enough for the common case. What
 * was missing is the ability to swap in a semantic scorer for operators who
 * want it, WITHOUT a second recall path — two scorers is exactly how the token
 * meter and the tool pairing drifted in this branch. So the engine keeps its
 * BM25 and asks a reranker to reorder the survivors.
 *
 * Contract:
 *  - called at most once per delivered hit list, never per hit;
 *  - receives the candidates in BM25 order and MUST return a permutation of
 *    them (same size, same elements) — dropping a hit silently would defeat
 *    the "use count is visible" guarantee below;
 *  - a null/blank query or an empty candidate list short-circuits before the
 *    seam, so an implementation never has to handle those.
 */
fun interface MemoryReranker {
    /**
     * Reorder [hits] for [query]. Return the same hits, possibly reordered.
     *
     * Must be pure and fast: it runs on the prompt-build thread. An
     * implementation that throws is ignored by the caller (the BM25 order is
     * kept), so a broken optional backend can never break recall.
     */
    fun rerank(query: String, hits: List<MemoryRecallEngine.RecallHit>): List<MemoryRecallEngine.RecallHit>

    companion object {
        /**
         * The shipped behaviour: preserve BM25 order. Kept as a named value so
         * the default is greppable and a test can assert it is the active one
         * without touching private state.
         */
        val IDENTITY: MemoryReranker = MemoryReranker { _, hits -> hits }
    }
}

/**
 * [T-memory-reranker-seam] Where a non-default reranker is installed.
 *
 * WHY a registry and not a constructor argument only: the engine instance is
 * created deep in the prompt path, so a backend that wants to reorder hits
 * cannot reach a constructor. One volatile holder is the whole seam — the
 * default is the identity, and a future semantic backend swaps it after its
 * model is ready. Read once per recall, so a swap takes effect on the next
 * request instead of requiring a restart.
 */
object MemoryRerankerRegistry {

 @Volatile
 private var current: MemoryReranker = MemoryReranker.IDENTITY

 /** The active reranker; the identity until one is installed. */
 fun active(): MemoryReranker = current

 /** Install a backend, or restore the identity with null. */
 fun install(reranker: MemoryReranker?) {
 current = reranker ?: MemoryReranker.IDENTITY
 }

 /** True when a non-default backend is installed (diagnostics / settings UI). */
 fun isCustom(): Boolean = current !== MemoryReranker.IDENTITY

 /** Test seam. */
 internal fun resetForTest() {
 current = MemoryReranker.IDENTITY
 }
}
