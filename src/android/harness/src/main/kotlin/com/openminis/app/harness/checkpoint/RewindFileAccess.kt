package com.openminis.app.harness.checkpoint

import com.openminis.app.harness.result.AppResult

/**
 * Adapted from taixu WorkspaceFileAccess (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * [RewindController] 的文件操作端口。
 *
 * 为什么需要端口：`harness` 的默认实现 [com.openminis.app.harness.WorkspaceFileAccess]
 * 按「工作区相对路径」解析（上游模型），而宿主 OpenMinis 的写工具收到的是
 * **Linux 绝对 guest 路径**（`/var/minis/workspace/...`、`/root/...`、挂载卷），
 * 由 PRootKernel 映射到 host File。宿主注入自己的实现后，同一套
 * rewind 语义（冲突检测、undo、子工作区）无需感知两种路径模型。
 *
 * 契约：`path` 的语义由实现方定义（默认实现=工作区相对路径；宿主=guest 路径），
 * 捕获与恢复必须使用同一种写法（宿主侧在写入捕获时归一化）。
 */
interface RewindFileAccess {
    /** 文件字节数（路径越界/非文件/不存在返回 null）。 */
    suspend fun fileSizeOrNull(path: String): Long?

    /** 读取当前内容；不存在或不可读返回 null（不抛异常）。 */
    suspend fun previewOrNull(path: String): String?

    /** 删除文件（不存在视为成功；目录/越界/失败返回 false）。 */
    suspend fun delete(path: String): Boolean

    /** 写入内容（原子替换；越界/超限/失败返回 Failure）。 */
    suspend fun write(path: String, content: String): AppResult<Unit>

    /** 切换到子工作区；不支持子工作区的实现返回 this。 */
    fun withBase(workspace: String): RewindFileAccess
}
