package com.openminis.app.harness.checkpoint

/**
 * Adapted from taixu CheckpointModels (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 */

/**
 * 单次编辑前的文件快照。仅用于写文件类工具（write/edit）捕获。
 *
 * @property path 工作区相对路径（与 write/edit 工具一致）
 * @property content 快照时文件内容；`null` 表示该快照时刻文件不存在（恢复时需删除文件）。
 * @property afterContent 该路径在本轮内**最后一次成功写入后**的内容（改动后状态）；
 *   `null` = 没有可用的改动后凭据（写入失败/超大文件跳过捕获/旧数据）。
 *   restore 时用它检测外部改动：当前文件内容与最后凭据不一致即报冲突、跳过恢复，
 *   避免 rewind 静默覆盖用户或外部工具在会话期间做的修改。
 */
data class FileSnap(
    val path: String,
    val content: String?,
    val afterContent: String? = null,
)

/**
 * 一个用户轮次的文件快照集合。每个用户轮次在写入第一个文件前开启，
 * 记录该轮被写工具触碰过的文件的"轮初内容"。
 */
data class Checkpoint(
    /** 0 起始的用户轮次号，用作恢复 UI 的定位锚点。 */
    val turn: Int,
    val time: Long,
    /** 该轮用户消息，作为选择器标签。 */
    val prompt: String,
    /** 该轮触碰过的文件快照（每个 path 仅保留轮初内容）。 */
    val files: List<FileSnap>,
    /** 该轮用户消息 entry id，供对话 fork 在会话树上定位轮次边界。 */
    val anchorMessageId: String? = null,
)

/** 提供给恢复 UI 的轻量摘要：不含文件内容。 */
data class CheckpointMeta(
    val turn: Int,
    val time: Long,
    val prompt: String,
    val changedFiles: List<String>,
    /** 该轮用户消息 entry id，供对话 fork 在会话树上定位轮次边界。 */
    val anchorMessageId: String? = null,
)

/** 恢复范围：仅代码 / 仅对话 / 两者。 */
enum class RewindScope { CODE, CONVERSATION, BOTH }

/** prepare/commit 两段式——prepare 生成不可变方案，commit 实际落盘。 */
data class RewindPlan(
    val sessionId: String,
    val turn: Int,
    val scope: RewindScope,
    /** 待恢复的文件快照（按 path 去重，取每 path 从目标轮起最早的轮初内容）。 */
    val fileSnaps: List<FileSnap>,
)

data class RewindResult(
    val filesRestored: Int,
    val filesDeleted: Int,
    /** 存在无法满足的部分（如对话 fork 处理器未就绪）时为 true。 */
    val partial: Boolean,
    val note: String? = null,
    /** CONVERSATION/BOTH 恢复派生出的新会话 id；UI 可跳转过去继续对话。 */
    val forkedSessionId: String? = null,
    /**
     * 因外部改动被跳过恢复的路径：当前文件内容与本 store 记录的最后改动后凭据不一致，
     * 恢复会静默覆盖外部修改，故保守跳过并报告。空列表 = 全部按方案恢复。
     */
    val conflicts: List<String> = emptyList(),
)

/**
 * 一次 rewind 的撤销记录（单层级）：
 * - [applied]：rewind 实际写入/删除的状态（冲突跳过的路径不含在内），undo 时的冲突检测基线；
 * - [undoSnaps]：与 [applied] 按下标对齐的「rewind 前磁盘状态」，undo 据此还原。
 * 对话侧无需 undo：CONVERSATION/BOTH 的 fork 不改动原会话，切回原会话即可。
 */
data class RewindUndoRecord(
    val applied: List<FileSnap>,
    val undoSnaps: List<FileSnap>,
)
