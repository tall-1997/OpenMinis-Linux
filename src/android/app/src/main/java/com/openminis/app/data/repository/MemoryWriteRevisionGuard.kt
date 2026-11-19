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
     * The refusal message for a rejected [expectedRevision], or null when the
     * caller may proceed. [existingContent] is only consulted when the caller
     * wants the plain hash-mismatch verdict.
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
        // An empty-but-present file is the "day rolled over" case wearing a
        // disguise: nothing else writes a zero-byte day log (every writer goes
        // through writeTextAtomic with frontmatter + an entry), so a caller
        // whose hash cannot match has nothing to lose by appending.
        if (existing.isEmpty()) {
            return "Error: 今天的日志是空的，没有可校验的 revision。" +
                "省略 revision 参数即可新建。"
        }
        if (!hashOf(existingContent).startsWith(MemoryRevision.parse(expectedRevision).hash)) {
            return "Error: MEMORY_CONFLICT — 日志文件已被并发更新。请先 memory_get 重新读取后再写。"
        }
        return null
    }
}
