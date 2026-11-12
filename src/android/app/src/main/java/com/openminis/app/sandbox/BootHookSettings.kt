package com.openminis.app.sandbox

import android.content.Context

/**
 * [GH#511] 启动脚本 `/var/minis/boot.sh` 的显式开关与来源审计。
 *
 * ## 为什么需要单独的开关
 *
 * rootfs 内的 `/var/minis/boot.sh` 位于 agent 可写目录：任何能执行 shell 的
 * 会话都能写它，而 [BootHook] 会在每次进程启动时静默执行。这等于给 agent
 * 一条“写文件 → 下次启动自动执行”的提权链，不需要用户批准。开关默认关闭，
 * 只有用户在 Settings → Agent Runtime → Lifecycle Hooks 里显式打开后，脚本
 * 才会执行；关闭时脚本内容原样保留，不会删除用户文件。
 *
 * ## 来源审计
 *
 * 脚本没有可区分的“创建者”（同一 app uid 写入），所以这里记录**首次观察
 * 时间**和**内容指纹**（size + lastModified）。每次启动执行前写入 AppLogger：
 * 首次出现的脚本记 info，指纹变化记 warning。这样“谁在什么时候改了启动
 * 脚本”至少在日志里可追溯，而不是完全静默。
 *
 * 持久化用 SharedPreferences（与项目内 LifecycleHooks / A11yAppPolicy 同
 * 一模式）；DataStore 依赖已在 build.gradle 中移除，不为此重新引入。
 */
object BootHookSettings {

    private const val PREFS = "boot_hook"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_FINGERPRINT = "last_script_fingerprint"
    private const val KEY_FIRST_SEEN_AT = "first_seen_at"

    /** Master switch. Default false: an upgrade must not start executing scripts. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Last observed `size|lastModified` fingerprint, null on first run. */
    internal fun lastScriptFingerprint(context: Context): String? =
        prefs(context).getString(KEY_LAST_FINGERPRINT, null)

    internal fun setLastScriptFingerprint(context: Context, fingerprint: String) {
        prefs(context).edit().putString(KEY_LAST_FINGERPRINT, fingerprint).apply()
    }

    /** Wall-clock time the current boot script was first observed by this install. */
    internal fun firstSeenAt(context: Context): Long? =
        prefs(context).getLong(KEY_FIRST_SEEN_AT, 0L).takeIf { it > 0L }

    internal fun setFirstSeenAt(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_FIRST_SEEN_AT, at).apply()
    }

    /** Test-only reset so a JVM/Robolectric test starts from the shipped default. */
    internal fun resetForTest(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
