package com.openminis.app.data.repository

/**
 * [GH#357] Split an admitted memory allocation into the byte-stable block
 * (GLOBAL / project / session standing rules) and the per-turn-volatile block
 * (recent daily logs + their caption + TOC).
 *
 * 中文：每日记忆日志会随 memory_write 变化，必须放在动态尾部；否则每写
 * 一条记忆都会改写缓存前缀，导致整轮 prompt cache miss。
 *
 * Why: the whole memory allocation used to be appended to the STABLE head of
 * the system prompt. Every `memory_write` changes the newest daily log, so the
 * next turn rewrote bytes inside the cached prefix and DeepSeek/Anthropic
 * prefix caching missed the entire prompt (system + tools + history). The
 * daily block is keyword/history-driven and changes far more often than the
 * standing rules; keeping it in the dynamic tail preserves the cached prefix.
 *
 * Pure and total: no Android types, no I/O — pinned by MemoryBlockSplitTest.
 */
internal object MemoryBlockSplit {

    data class Blocks(
        /** Standing rules — safe to place before the cache breakpoint. */
        val stable: String?,
        /** Daily logs / caption / TOC — must live in the dynamic tail. */
        val transient: String?,
    )

    fun split(allocation: MemoryPromptBudget.Allocation?): Blocks {
        val included = allocation?.included.orEmpty()
        val stable = included.filterNot { isTransientKey(it.key) }
            .joinToString("\n\n") { it.text }
            .takeIf { it.isNotBlank() }
        val transient = included.filter { isTransientKey(it.key) }
            .joinToString("\n\n") { it.text }
            .takeIf { it.isNotBlank() }
        return Blocks(stable = stable, transient = transient)
    }

    /** Daily-day fragments, the inserted caption, and the TOC row. */
    fun isTransientKey(key: String): Boolean =
        key.startsWith(MemoryAssembly.DAILY_KEY_PREFIX) ||
            key == MemoryAssembly.INDEX_KEY ||
            key == MemoryAssembly.HEADER_KEY
}
