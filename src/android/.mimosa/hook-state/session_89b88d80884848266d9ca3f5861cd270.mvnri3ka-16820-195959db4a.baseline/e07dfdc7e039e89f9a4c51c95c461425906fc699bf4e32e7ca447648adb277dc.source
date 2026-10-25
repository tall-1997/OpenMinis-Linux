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
}
