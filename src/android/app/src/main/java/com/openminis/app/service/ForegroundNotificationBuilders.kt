package com.openminis.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.openminis.app.R

/**
 * 前台通知各 surface 专属行的构建器（HyperOS 超级岛行、OEM 静默行），以及
 * 它们与普通行/提升行共用的呈现 helper（锁屏替身行、状态栏图标、工具显示标签）。
 *
 * 单独成文件的原因与 [ForegroundNotificationCopy] 相同：AgentForegroundService
 * 已顶到架构门禁的行数 ceiling，这段逻辑必须外置。纯结构性搬移——函数体与
 * AgentForegroundService 中的原实现逐行一致，仅把 private 成员改为同包
 * internal 顶层函数、service 实例以 [Context] 显式传入；通知 id、文案与
 * 行为不变。
 */

/**
 * [T-approval-lockscreen] Benign lock-screen stand-in for the ongoing
 * status row: app name + stable subtitle only. Never carries tool
 * previews or session content, so the channel can stay
 * VISIBILITY_PRIVATE while the row remains visible.
 */
internal fun publicLockscreenRow(
    context: Context,
    title: String,
    text: String,
    tap: PendingIntent,
): Notification =
    NotificationCompat.Builder(context, AgentForegroundService.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_status)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(tap)
        .setOngoing(true)
        .build()

/**
 * T-bg-overlay phase 1: human-readable label per tool. [Wave3 #8] the
 * duplicated English `when` lived here AND in ToolOverlayController ("Mirrors
 * …"); both now delegate to [ForegroundNotificationCopy.toolLabel] so the
 * notification and the overlay capsule read one mapping (resource-backed in
 * strings_service.xml).
 */
internal fun toolDisplayLabel(context: Context, toolName: String): String =
    ForegroundNotificationCopy.toolLabel(context.applicationContext, toolName)

/**
 * Status-bar icons must be an alpha mask from this app. Framework
 * `ic_menu_*` drawables are color bitmaps; OEM SystemUI decodes a new
 * one every time it rebuilds the island. Completed runs use the check.
 */
internal fun statusIconRes(isCompleted: Boolean): Int =
    if (isCompleted) R.drawable.ic_notification_completed else R.drawable.ic_notification_status

/**
 * [T-android-hyperos-island] HyperOS 小米超级岛 notification: a native
 * builder (no compat wrapper — parcelable extras go straight into
 * miui.focus.pics / miui.focus.actions) carrying the miui.focus.param
 * JSON. A static ongoing row for HyperOS, ZUI and every other forked
 * SystemUI — no chronometer, no progress, no promoted style, no changing
 * text; their island can mirror this once because it has nothing left to
 * tick. The shade row keeps OEM_QUIET discipline — static when, no
 * chronometer, no progress — because the forked-SystemUI re-inflation
 * hazard [SystemUiHost] guards against is about the shade row, not the
 * island pipeline, which Xiaomi renders from the JSON on its own.
 *
 * Update path is the official one: same notification id +
 * `updatable=true` in the JSON; the policy's 20s cosmetic gap throttles
 * tool-name churn on the big island.
 */
internal fun buildHyperOsFocusNotification(
    context: Context,
    state: ForegroundNotificationState,
    isCompleted: Boolean,
    sessionLabel: String,
    timeString: String,
    contentIntent: PendingIntent,
    stopPendingIntent: PendingIntent,
): Notification {
    val toolName = state.toolName
    // Same title semantics as the plain row: completed → "Task
    // completed", tool in flight → its display label, else the app name.
    val titleText = when {
        isCompleted -> context.getString(R.string.bg_service_notification_title_completed)
        toolName != null -> toolDisplayLabel(context, toolName)
        else -> context.getString(R.string.bg_service_notification_title)
    }
    val collapsedText = if (isCompleted) {
        context.getString(R.string.bg_service_notification_text_completed, sessionLabel, timeString)
    } else {
        context.getString(R.string.bg_service_notification_text_live, sessionLabel, state.subtitle)
    }

    // 模板库 guidance: big-island text ≤4 CJK chars, longer text
    // degrades to small font then clips. The tool display label and
    // "Task completed" are the short forms; the subtitle ("1 task
    // running") is the trailing small word.
    // [T-live-status-model] Ticker/AOD/big-island text come from the shared
    // model, not from literals and not from the long shade title: the
    // template guide caps the big island at ~4 CJK chars, and handing it
    // "Minis is using Shell" degraded to a smaller font and then clipped.
    val live = liveStatusOf(context, state, sessionLabel)

    val content = HyperOsIsland.IslandContent(
        business = "agent",
        ticker = live.ticker(),
        aodTitle = live.ticker(),
        bigTitle = live.islandHeadline(),
        bigContent = live.islandCaption(),
        baseTitle = titleText,
        baseContent = collapsedText,
        hintTitle = live.islandCaption(),
    )
    val focusJson = HyperOsIsland.buildFocusParamJson(content)

    // [T-interrupt-no-pause-button] The shade row's second button used to
    // read "Pause" while ACTION_INTERRUPT stops and destroys every running
    // turn. No real suspend exists yet, so the lying button is dropped —
    // only Stop remains, matching ACTION_STOP's behaviour. The
    // ACTION_INTERRUPT handler in onStartCommand stays for compat.

    // 模板库 rule: miui.focus.pics icons must be COLOR content icons
    // (square, >=88*88px). The status-bar small icon is a white alpha
    // mask — on the island it renders as an invisible white blob (the
    // "应用图标没有" symptom on HyperOS 4). The launcher icon is the
    // color icon every app ships; it also matches the protocol's
    // picInfo.type=1 fallback ("系统直接展示应用桌面图标").
    val launcherIcon = android.graphics.drawable.Icon.createWithResource(
        context, context.applicationInfo.icon,
    )
    // White alpha-mask small icon for the shade row only — the shade
    // tints it; the island must never see it (see launcherIcon note).
    val statusIcon = android.graphics.drawable.Icon.createWithResource(
        context, statusIconRes(isCompleted),
    )
    val builder = Notification.Builder(context, AgentForegroundService.CHANNEL_ID)
        .setSmallIcon(statusIcon)
        .setContentTitle(titleText)
        .setContentText(collapsedText)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setContentIntent(contentIntent)
        .setCategory(Notification.CATEGORY_SERVICE)
        // [T-approval-lockscreen] PRIVATE + benign publicVersion (see the
        // promoted builder note); the shade-row text is static app strings.
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setPublicVersion(publicLockscreenRow(context, titleText, collapsedText, contentIntent))

    // Shade-row buttons keep the white status icon — the shade tints
    // small icons itself. The island button is separate: hintInfo carries
    // ONE actionInfo, so only Stop goes to the island (方式一: the system
    // renders the miui.focus.actions Action's Icon + Title — color icon,
    // real label). No Stop once there is nothing left to stop.
    // [T-interrupt-no-pause-button] The "Pause" action was removed here
    // too — see the note above buildHyperOsFocusNotification.
    val islandActions = mutableMapOf<String, Notification.Action>()
    if (!isCompleted) {
        val stopAction = Notification.Action.Builder(
            launcherIcon,
            context.getString(R.string.bg_service_stop_action),
            stopPendingIntent,
        ).build()
        builder.addAction(stopAction)
        islandActions[HyperOsIsland.ACTION_STOP] = stopAction
    }

    HyperOsIsland.attachFocusExtras(
        builder = builder,
        json = focusJson,
        icons = mapOf(
            HyperOsIsland.PIC_SMALL to launcherIcon,
            HyperOsIsland.PIC_BIG to launcherIcon,
            HyperOsIsland.PIC_TICKER to launcherIcon,
            HyperOsIsland.PIC_AOD to launcherIcon,
        ),
        islandActions = islandActions,
    )
    return builder.build()
}

internal fun buildOemQuietNotification(
    context: Context,
    isCompleted: Boolean,
    sessionLabel: String,
    timeString: String,
    contentIntent: PendingIntent,
    stopIntent: PendingIntent,
    activeCount: Int,
): Notification {
    val title = if (isCompleted) {
        context.getString(R.string.bg_service_notification_title_completed)
    } else {
        context.getString(R.string.bg_service_notification_title)
    }
    // [T-android-hyperos-island] Fix: the completed row used the TITLE
    // string ("Task completed") as its body text — a copy-paste slip
    // from the title branch above. Use the real completed body.
    // 运行中的行必须报真实会话数：之前硬编码 "1 task running"，
    // 多会话并行时通知栏/灵动岛一直在撒谎。
    val text = if (isCompleted) {
        context.getString(R.string.bg_service_notification_text_completed, sessionLabel, timeString)
    } else {
        ForegroundNotificationCopy.runningSubtitle(context, activeCount)
    }
    val builder = NotificationCompat.Builder(context, AgentForegroundService.CHANNEL_ID)
        .setSmallIcon(statusIconRes(isCompleted))
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setUsesChronometer(false)
        .setContentIntent(contentIntent)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        // [T-approval-lockscreen] Benign private + publicVersion, as for
        // the plain row (this builder's text is static app strings).
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicLockscreenRow(context, title, text, contentIntent))
    if (!isCompleted) {
        builder.addAction(
            R.drawable.ic_notification_status,
            context.getString(R.string.bg_service_stop_action),
            stopIntent,
        )
    }
    return builder.build()
}
