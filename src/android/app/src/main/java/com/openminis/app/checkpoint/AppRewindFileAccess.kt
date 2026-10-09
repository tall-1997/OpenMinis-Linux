package com.openminis.app.checkpoint

import android.content.Context
import com.openminis.app.harness.checkpoint.RewindFileAccess
import com.openminis.app.harness.result.AppError
import com.openminis.app.harness.result.AppResult
import com.openminis.app.harness.result.ErrorCode
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.tools.AtomicFileWrite
import java.io.File

/**
 * [T-checkpoint-rewind] [RewindFileAccess] 的宿主实现。
 *
 * harness 的默认实现 `com.openminis.app.harness.WorkspaceFileAccess` 按
 * 「工作区相对路径」解析；OpenMinis 的写工具收到的是 Linux 绝对 guest 路径
 * （`/var/minis/workspace/...`、`/root/...`），由 [PRootKernel] 映射到 host File。
 * 本实现让 rewind 与写工具走**同一条**解析路径（`resolveSessionHostPath`），
 * 因此捕获与恢复的 path 语义完全一致——这正是 [RewindFileAccess] 契约要求的。
 *
 * [withBase] 在本宿主中承载 **sessionId**（而非上游语义的子工作区）：
 * [com.openminis.app.harness.checkpoint.RewindController.commit] 通过 `workspace`
 * 参数把目标会话传进来，恢复阶段才拿得到 session 作用域。
 */
internal class AppRewindFileAccess(
    private val context: Context,
    private val sessionId: String,
) : RewindFileAccess {

    private fun hostFile(path: String): File? =
        PRootKernel.resolveSessionHostPath(sessionId, path, context)

    override suspend fun fileSizeOrNull(path: String): Long? =
        hostFile(path)?.takeIf { it.isFile }?.length()

    override suspend fun previewOrNull(path: String): String? {
        val file = hostFile(path) ?: return null
        // 超过快照上限的文件不读入堆：RewindController 把它当作「不可判定」，
        // 冲突检测按保守跳过处理（与 harness 默认实现的语义一致）。
        if (!file.isFile || file.length() > MAX_BYTES) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    override suspend fun delete(path: String): Boolean {
        val file = hostFile(path) ?: return false
        if (!file.exists()) return true
        if (!file.isFile) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    override suspend fun write(path: String, content: String): AppResult<Unit> {
        val file = hostFile(path)
            ?: return AppResult.Failure(AppError(ErrorCode.SECURITY, "路径不可解析：$path"))
        val bytes = content.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_BYTES) {
            return AppResult.Failure(AppError(ErrorCode.IO, "内容超过 $MAX_BYTES 字节：$path"))
        }
        file.parentFile?.mkdirs()
        // 与 file_write 同一个原子写实现：临时文件 + rename + 落盘校验。
        val written = AtomicFileWrite.write(file, content, append = false)
            ?: return AppResult.Failure(AppError(ErrorCode.IO, "写入未落盘：$path"))
        return AppResult.Success(Unit)
    }

    override fun withBase(workspace: String): RewindFileAccess =
        if (workspace.isBlank() || workspace == sessionId) this
        else AppRewindFileAccess(context, workspace)

    private companion object {
        /** 与 CheckpointStore.SNAPSHOT_MAX_BYTES 对齐（1 MiB）。 */
        const val MAX_BYTES = 1L * 1024L * 1024L
    }
}
