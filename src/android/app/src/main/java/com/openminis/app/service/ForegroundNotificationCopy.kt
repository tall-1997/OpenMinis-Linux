package com.openminis.app.service

import android.content.Context
import com.openminis.app.R

/**
 * 前台通知的副标题文案。
 *
 * 单独成文件的两个原因：
 *  1. AgentForegroundService 已顶到架构门禁的行数 ceiling，新增逻辑必须外置；
 *  2. 这段"运行中到底几个任务"的分支是 [T-notif-count-lie] 的修复点，
 *     单独放一处便于测试和复用（OEM 通知与普通通知走同一个真值来源）。
 *
 * [Wave3 #8] 工具标签的唯一映射也收敛到本文件：通知（长档）与悬浮工具胶囊
 * （短档）各自硬编码过一份英文 when，注释自认 "Mirrors …"。两者现在都委托
 * [toolLabel] / [toolLabelShort]，文案资源化在 res/values/strings_service.xml。
 */
object ForegroundNotificationCopy {

    /**
     * @param activeCount 当前真实在跑的会话数，来自
     *   [SessionActivityTracker.activeSessions]。0 表示服务还活着但没有任务。
     *
     * [T-notif-count-lie] 之前 OEM 静默通知硬编码 `notif_one_task_running`，
     * 多会话并行时通知栏和灵动岛一直显示 "1 task running"，与实际数量不符。
     */
    fun runningSubtitle(context: Context, activeCount: Int): String = when {
        activeCount <= 0 -> context.getString(R.string.notif_in_session)
        activeCount == 1 -> context.getString(R.string.notif_one_task_running)
        else -> context.getString(R.string.notif_n_tasks_running, activeCount)
    }

    /**
     * [Wave3 #8] 长档工具标签（通知标题）："Minis is using Shell"。
     * 未知工具回退到原始 tool name（而非泛化文案），保持原契约：用户仍能
     * 看出在跑什么。统一用 applicationContext 解析资源，调用方传 Service 或
     * overlay 的 WindowManager context 都不会悬挂在短命的 Activity 上。
     */
    fun toolLabel(context: Context, toolName: String): String {
        val app = context.applicationContext
        return when (toolName) {
            "shell_execute" -> app.getString(R.string.bg_service_tool_shell)
            "file_read" -> app.getString(R.string.bg_service_tool_file_read)
            "file_write" -> app.getString(R.string.bg_service_tool_editor)
            "file_edit" -> app.getString(R.string.bg_service_tool_file_edit)
            "browser_use" -> app.getString(R.string.bg_service_tool_browser)
            "read_image" -> app.getString(R.string.bg_service_tool_read_image)
            "memory_write", "memory_get" -> app.getString(R.string.bg_service_tool_memory)
            "web_search" -> app.getString(R.string.bg_service_tool_search)
            else -> app.getString(R.string.bg_service_tool_generic, toolName)
        }
    }

    /**
     * [Wave3 #8] 短档工具标签（悬浮胶囊）："Shell"；toolName 为 null 时回退
     * 到应用名。与 [toolLabel] 共用 strings_service.xml 中的一套映射。
     */
    fun toolLabelShort(context: Context, toolName: String?): String {
        val app = context.applicationContext
        return when (toolName) {
            null -> app.getString(R.string.tool_label_short_app_name)
            "shell_execute" -> app.getString(R.string.tool_label_short_shell)
            "file_read" -> app.getString(R.string.tool_label_short_file)
            "file_write" -> app.getString(R.string.tool_label_short_editor)
            "file_edit" -> app.getString(R.string.tool_label_short_edit)
            "browser_use" -> app.getString(R.string.tool_label_short_browser)
            "read_image" -> app.getString(R.string.tool_label_short_image)
            "memory_write", "memory_get" -> app.getString(R.string.tool_label_short_memory)
            "web_search" -> app.getString(R.string.tool_label_short_search)
            else -> toolName
        }
    }
}
