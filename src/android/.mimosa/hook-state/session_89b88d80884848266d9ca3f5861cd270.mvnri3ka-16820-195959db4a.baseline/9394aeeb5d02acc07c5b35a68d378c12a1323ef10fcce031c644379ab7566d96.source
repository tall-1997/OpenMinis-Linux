package com.openminis.app.backup

/**
 * 备份进度日志的压缩规则。
 *
 * 拷贝到本地文件夹（以及上传到远程）时，每个百分比都回调一次进度，
 * 原样追加进记录后一个大文件产生上万行，历史页加载时卡死。
 * 连续的进度行表达的是同一件事的中间状态，只保留最后一条；
 * 问题行和普通叙述行全部保留。最后再按上限截断，保留尾部（最新的）。
 */
object BackupLogCompaction {

    private val PROGRESS = Regex("""^(Sending to .+…|Copying to .+…)""")

    fun compact(
        entries: List<BackupHistory.LogEntry>,
        maxLines: Int,
    ): List<BackupHistory.LogEntry> {
        val collapsed = mutableListOf<BackupHistory.LogEntry>()
        for (entry in entries) {
            val previous = collapsed.lastOrNull()
            // 同一目的地的进度只留最新一条。问题行（拷贝失败）也算该目的地的
            // 最终状态，替换掉前面的进度行，避免「50%」和「失败」并列。
            val supersedes = previous != null && isProgress(previous) &&
                destinationOf(previous) == destinationOf(entry) &&
                (isProgress(entry) || entry.isProblem)
            if (supersedes) {
                collapsed[collapsed.lastIndex] = entry
            } else {
                collapsed.add(entry)
            }
        }
        return if (collapsed.size > maxLines) collapsed.takeLast(maxLines) else collapsed
    }

    /** 进度行：拷贝/上传的中间状态，且不是问题。问题行即使措辞相似也要保留。 */
    private fun isProgress(entry: BackupHistory.LogEntry): Boolean =
        !entry.isProblem && PROGRESS.containsMatchIn(entry.message)

    /** 进度行按目的地区分，不同目的地的进度各自成组。 */
    private fun destinationOf(entry: BackupHistory.LogEntry): String =
        entry.message.substringBefore('…')
}
