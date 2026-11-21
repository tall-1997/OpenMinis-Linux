package com.openminis.app.data.repository

/**
 * [T-memory-revision-day-scoped] Wording layer for the day-scoped revision
 * check (classification lives in [MemoryRevision]).
 *
 * Split out of `MemoryRepository` for two reasons that both bit during the
 * fix: the file is at the 800-line architecture ceiling, and the CJK ratchet
 * counts user-facing Chinese per FILE — memos written straight into the
 * repository would have to be re-baselined every time the copy changes.
 */
internal object MemoryWriteRevisionGuard {

    /**
     * Single source of the malformed-revision copy — the tool parameter gate
     * ([F3]) and this guard must tell the model the same thing, or the model
     * sees two different diagnoses for one mistake and retries blind.
     */
    fun malformedCopy(): String =
        "Error: revision 格式不正确（应为 YYYY-MM-DD:hash16，即 memory_get 返回的当前日志 revision）。" +
            "请原样传入，或省略 revision 参数即可追加。"

    /**
     * The refusal message for a rejected [expectedRevision], or null when the
     * caller may proceed.
     *
     * Caller contract ([F2] — the empty-file branch below was unreachable for
     * exactly this reason): [dayFileExists] ≡ `existingContent.isNotEmpty()`.
     * An empty existing content therefore always classifies as NoLogToday and
     * never reaches the hash comparison.
     */
    fun refusal(
        expectedRevision: String,
        fileName: String,
        dayFileExists: Boolean,
        hashOf: (String) -> String,
        existingContent: String,
    ): String? {
        val rejection = MemoryRevision.rejection(
            revision = expectedRevision,
            day = fileName.removeSuffix(".md"),
            fileName = fileName,
            dayFileExists = dayFileExists,
        )
        when (rejection) {
            null -> Unit
            is MemoryRevision.Rejection.Malformed -> return malformedCopy()
            is MemoryRevision.Rejection.DayRolledOver -> return if (rejection.dayFileHasContent) {
                "Error: revision ${rejection.revisionDay} 属于前一天的日志，今天是 ${rejection.today}" +
                    " —— 日期已翻页，不是并发冲突。请先 memory_get 取今天的 revision 再写。"
            } else {
                "Error: revision ${rejection.revisionDay} 属于前一天的日志，今天是 ${rejection.today}" +
                    " —— 日期已翻页，不是并发冲突。今天的日志还没有内容，省略 revision 参数即可新建。"
            }
            is MemoryRevision.Rejection.NoLogToday ->
                return "Error: 今天的日志（${rejection.fileName}）还不存在，没有可校验的 revision。" +
                    "省略 revision 参数即可新建。"
        }
        // [T-memory-revision-degenerate-inputs] Full-equality comparison via
        // [MemoryRevision.hashMatches]: the guard's hashOf produces the FULL
        // 64-char sha256, and the minted token carries its 16-char prefix —
        // the old inline `startsWith(hash)` was always true for an empty hash
        // and weakened for any short one (F1, JVM-reproduced). By the time
        // control reaches here, rejection() has already shape-checked the
        // hash to 16 hex, so the equality is safe to trust.
        if (!MemoryRevision.hashMatches(hashOf(existingContent), MemoryRevision.parse(expectedRevision).hash)) {
            return "Error: MEMORY_CONFLICT — 日志文件已被并发更新。请先 memory_get 重新读取后再写。"
        }
        return null
    }
}
