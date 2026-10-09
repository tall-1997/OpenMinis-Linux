package com.openminis.app.tools

import android.content.Context
import java.io.File

/**
 * Spill oversized tool results out of the LLM context into the session
 * workspace. Inspired by long-running Android agents that otherwise freeze
 * Compose and blow the context window when `shell_execute` dumps megabytes.
 *
 * The UI still shows a short tail; the model gets a preview plus a guest
 * path it can `file_read` if it actually needs more.
 */
object ToolOutputSpill {
    const val LIMIT = 16_000
    const val HEAD = 6_000
    const val TAIL = 4_000

    fun maybeSpill(
        context: Context,
        sessionId: String,
        toolName: String,
        toolId: String,
        output: String,
    ): String {
        if (output.length <= LIMIT || sessionId.isBlank()) return output
        val safeId = toolId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "tool" }
        val dir = File(
            com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, "workspace"),
            "tool-spill",
        )
        if (!dir.mkdirs() && !dir.isDirectory) {
            return "[tool output omitted: spill directory unavailable, ${output.length} chars]"
        }
        val file = File(dir, "$safeId.txt")
        return try {
            file.writeText(output)
            formatPreview(
                toolName = toolName,
                guestPath = "/var/minis/workspace/tool-spill/${file.name}",
                output = output,
            )
        } catch (_: Exception) {
            "[tool output omitted: spill write failed, ${output.length} chars]"
        }
    }

    /**
     * 宿主工具名 → taixu 保留策略名。shell_execute 的语义即上游 "base"
     * （命令执行，报错在尾部）；execute_code 对应 "build_script"。其余走 HEAD。
     */
    private fun retentionName(toolName: String): String? = when (toolName) {
        "shell_execute", "shell_exec", "env_exec" -> "base"
        "execute_code" -> "build_script"
        else -> null
    }

    fun formatPreview(toolName: String, guestPath: String, output: String): String {
        // [T-recovery-layer] 截断方向随工具切换：命令/构建类的 panic、断言失败、
        // 编译错误永远在末尾，保留尾部才能让模型直击报错核心；read/search 类
        // 关键信息在头部。整行对齐避免把一行切两半；超长单行先折叠。
        val folded = com.openminis.app.harness.effects.foldOverlongLines(output)
        val tailFirst = com.openminis.app.harness.effects.ToolOutputRetention
            .forTool(retentionName(toolName)) == com.openminis.app.harness.effects.OutputRetention.TAIL
        val headBudget = if (tailFirst) TAIL else HEAD
        val tailBudget = if (tailFirst) HEAD else TAIL
        val head = com.openminis.app.harness.effects.keepHeadWholeLines(folded, headBudget)
        val tail = if (folded.length > headBudget + tailBudget) {
            com.openminis.app.harness.effects.keepTailWholeLines(folded, tailBudget)
        } else {
            ""
        }
        return buildString {
            append("[tool-output-spill] $toolName produced ${output.length} chars. ")
            append("Full output saved to $guestPath — use file_read if you need more.\n")
            append("Open in app: minis://workspace/tool-spill/${guestPath.substringAfterLast('/')}\n")
            append(if (tailFirst) "[retention: tail-biased — errors usually land at the end]\n\n" else "\n")
            append(head)
            if (tail.isNotEmpty()) {
                append("\n\n…(${folded.length - head.length - tail.length} chars omitted)…\n\n")
                append(tail)
            }
        }
    }

    private val GUEST_PATH = Regex("/var/minis/workspace/tool-spill/([A-Za-z0-9._-]+)")

    fun parseGuestPath(content: String): String? {
        val m = GUEST_PATH.find(content) ?: return null
        return "/var/minis/workspace/tool-spill/${m.groupValues[1]}"
    }

    fun hostFile(context: Context, sessionId: String, guestPath: String): File? {
        val name = parseGuestPath(guestPath)?.substringAfterLast('/') ?: return null
        if (sessionId.isBlank() || name.isBlank()) return null
        val file = File(
            com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, "workspace"),
            "tool-spill/$name",
        )
        return file.takeIf { it.isFile }
    }
}
