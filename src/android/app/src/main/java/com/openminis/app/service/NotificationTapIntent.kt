package com.openminis.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 前台通知的点击意图。
 *
 * 单独成文件：AgentForegroundService 已到架构门禁的行数 ceiling。
 */
object NotificationTapIntent {

    /**
     * 构建"点通知回到正确会话"的 PendingIntent。
     *
     * [T-notif-tap-wrong-session] 之前只带 SINGLE_TOP|CLEAR_TOP —— 点通知仅把
     * App 拉到前台，显示它上次停留的会话。多会话并行时，任务 A 完成的通知点开
     * 却进了会话 B。完成态通知尤其致命：那正是用户最想知道"我刚问的结果在哪"
     * 的时刻。
     *
     * 修法：把 [SessionActivityTracker.currentSessionId] 作为 minis://session/<id>
     * 深链接塞进 Intent.data，由 MainActivity 的 DeepLinkHandler 导航过去。
     */
    fun build(context: Context): PendingIntent {
        val intent = Intent(context, Class.forName("com.openminis.app.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val sid = SessionActivityTracker.currentSessionId.value
            if (!sid.isNullOrBlank()) {
                data = android.net.Uri.parse("minis://session/$sid")
            }
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
