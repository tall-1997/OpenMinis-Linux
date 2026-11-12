package com.openminis.app.accessibility

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * [GH#114] 按应用的辅助功能（读屏 / 点击）允许清单。
 *
 * ## 为什么需要
 *
 * `ui_read` / `ui_action` 能通过 AccessibilityService 读取任意前台应用的
 * 界面并注入点击。默认把它交给模型等于把“手机上所有 app 的可见内容”都交给
 * 一次会话。本策略让用户声明一份 per-app 清单，在**每次读屏/点击之前**用
 * 当前前台包名判定，未授权应用直接拦截并给出可见提示。
 *
 * ## 数据模型
 *
 * - [Mode.OFF]：不限制（默认，向后兼容；升级用户行为不变）。
 * - [Mode.ALLOWLIST]：只有清单里的包名可读/可点。
 * - [Mode.BLOCKLIST]：清单里的包名被禁止，其它放行。
 *
 * 持久化在 SharedPreferences（`a11y_app_policy`，JSON：
 * `{"mode":"allowlist","apps":["com.example"]}`）。不写 Room —— 本批不碰
 * 数据库 schema。保存入口已做包名格式校验（字母/数字/点/下划线），重复项
 * 去重，大小写不敏感归一。
 *
 * ## 判定时机 / 失败语义
 *
 * 判定发生在工具派发前（[verdictForForeground]），使用
 * [MinisAccessibilityService.foregroundPackage] 的前台包名：
 *  - 前台包名拿不到（服务未连接 / 查询超时）时，限制模式下**拦截**而不是
 *    放行 —— 否则“服务挂掉”就成了绕过清单的后门；
 *  - Minis 自己的包名永远放行（读屏快照可能是应用内嵌浏览器/终端渲染的
 *    自己的界面，拦截它只会把功能弄坏）；
 *  - [Mode.OFF] 下不做任何前台查询，零额外开销。
 *
 * 这是工具层的“权限门”，与 [com.openminis.app.guard.ActionGuard] 的防循环 /
 * 节奏控制正交：两者都在派发前评估，互不替代。
 */
object A11yAppPolicy {

    private const val PREFS = "a11y_app_policy"
    private const val KEY = "policy_json"
    private const val MAX_APPS = 500

    /**
     * [GH#114] android-a11y-cli subcommands that READ the screen or INJECT
     * input, and therefore must pass the per-app policy gate before dispatch.
     *
     * `wait appear` and `notify watch/once` are gated too: the first literally
     * waits for on-screen content and reports it, and the second observes
     * window/notification events from the foreground app — both expose the
     * same data the policy exists to protect. Diagnostics (`service`,
     * `--version`) stay ungated.
     */
    val POLICY_GATED_SUBS: Set<String> = setOf(
        "ui", "tap", "input", "scroll", "gesture", "dialog", "extract", "event",
        "wait", "notify",
    )

    /** 包名允许的字符集；同时挡住空格、路径分隔符与注入用的特殊字符。 */
    private val PACKAGE_RE = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    enum class Mode {
        OFF,
        ALLOWLIST,
        BLOCKLIST,
        ;

        companion object {
            fun fromName(raw: String?): Mode =
                entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OFF
        }
    }

    sealed interface Verdict {
        data object Allowed : Verdict
        data class Blocked(val packageName: String?, val reason: String) : Verdict
    }

    @Volatile
    private var app: Context? = null

    @Volatile
    private var mode: Mode = Mode.OFF

    @Volatile
    private var apps: Set<String> = emptySet()

    /** Minis 自身包名，构造时缓存；判断“自己的界面”时用。 */
    @Volatile
    private var ownPackage: String = ""

    fun prime(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        ownPackage = ctx.packageName
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (raw.isBlank()) {
            mode = Mode.OFF
            apps = emptySet()
            return
        }
        runCatching {
            val o = JSONObject(raw)
            mode = Mode.fromName(o.optString("mode", "OFF"))
            val arr = o.optJSONArray("apps") ?: JSONArray()
            apps = (0 until arr.length())
                .mapNotNull { i -> arr.optString(i).trim().lowercase().takeIf { it.isNotEmpty() } }
                .toSet()
        }.onFailure {
            mode = Mode.OFF
            apps = emptySet()
        }
    }

    fun mode(): Mode = mode

    fun apps(): List<String> = apps.sorted()

    /** 保存策略；返回错误信息，null = 成功。 */
    fun save(context: Context, newMode: Mode, newApps: Collection<String>): String? {
        val cleaned = LinkedHashSet<String>()
        for (raw in newApps) {
            for (part in raw.split(',', '\n', '\r', ';', ' ')) {
                val pkg = part.trim().lowercase()
                if (pkg.isEmpty()) continue
                if (!PACKAGE_RE.matches(pkg)) return "Invalid package name: $part"
                cleaned.add(pkg)
                if (cleaned.size > MAX_APPS) return "Too many apps (max $MAX_APPS)"
            }
        }
        val ok = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(
                KEY,
                JSONObject()
                    .put("mode", newMode.name)
                    .put("apps", JSONArray(cleaned.toList()))
                    .toString(),
            )
            .commit()
        if (!ok) return "Failed to persist policy"
        mode = newMode
        apps = cleaned
        ownPackage = context.applicationContext.packageName
        return null
    }

    /** 纯函数判定，便于单测。 */
    fun decide(packageName: String?, mode: Mode = this.mode, apps: Set<String> = this.apps): Verdict {
        if (mode == Mode.OFF) return Verdict.Allowed
        val pkg = packageName?.trim()?.lowercase().orEmpty()
        if (pkg.isEmpty()) {
            return Verdict.Blocked(
                packageName = null,
                reason = "无法确认当前前台应用（AccessibilityService 未连接或查询超时），" +
                    "按应用清单已阻止本次操作。",
            )
        }
        return when (mode) {
            Mode.OFF -> Verdict.Allowed
            Mode.ALLOWLIST ->
                if (pkg in apps) Verdict.Allowed
                else Verdict.Blocked(pkg, "应用 $pkg 不在读屏/点击允许清单中，操作已阻止。")
            Mode.BLOCKLIST ->
                if (pkg in apps) Verdict.Blocked(pkg, "应用 $pkg 在读屏/点击阻止清单中，操作已阻止。")
                else Verdict.Allowed
        }
    }

    /**
     * 判定当前前台应用。O(1) 权限检查 + 一次现有的 a11y 前台查询
     * （走 [AccessibilityQueryGuard]，失败返回 null → 限制模式下拦截）。
     *
     * [GH#114] 服务完全没连接时也 FAIL-CLOSED：旧代码为了“让更具体的
     * SERVICE_NOT_RUNNING 文案负责”而直接放行，但限制模式下这等于让
     * “服务挂掉 / 被 force-stop”成为绕过 allowlist 的后门。现在返回
     * [Verdict.Blocked]，reason 明确说明是 AccessibilityService 未连接。
     */
    fun verdictForForeground(): Verdict {
        if (mode == Mode.OFF) return Verdict.Allowed
        val service = runCatching { MinisAccessibilityService.getInstance() }.getOrNull()
            ?: return decideForForeground(
                packageName = null,
                serviceAvailable = false,
            )
        val pkg = runCatching { service.foregroundPackage().first }.getOrNull()
        return decideForForeground(packageName = pkg, serviceAvailable = true)
    }

    /**
     * [GH#114] Foreground decision with an explicit "service connected?" input,
     * split out so the fail-closed branch is unit-testable on the JVM. Pure
     * apart from reading [mode] / [apps] / [ownPackage] defaults.
     */
    internal fun decideForForeground(
        packageName: String?,
        serviceAvailable: Boolean,
        mode: Mode = this.mode,
        apps: Set<String> = this.apps,
    ): Verdict {
        if (mode == Mode.OFF) return Verdict.Allowed
        if (!serviceAvailable) {
            return Verdict.Blocked(
                packageName = null,
                reason = "无法确认当前前台应用：AccessibilityService 未连接（服务未启用或已被系统停止）。" +
                    "按应用清单已阻止本次操作；请先在 Settings → Accessibility 启用并连接服务。",
            )
        }
        val pkg = packageName?.trim()?.lowercase().orEmpty()
        // 自己的界面永远放行（见 KDoc）。
        if (pkg.isNotEmpty() && ownPackage.isNotEmpty() && pkg == ownPackage) {
            return Verdict.Allowed
        }
        return decide(pkg, mode, apps)
    }

    /** 给工具层用的错误文案：把判定结果转换成模型可读的拒绝原因。 */
    fun blockedMessage(verdict: Verdict.Blocked, toolName: String): String =
        "Error: $toolName blocked by the per-app accessibility policy. ${verdict.reason} " +
            "Settings → Permissions → Accessibility app access 可调整清单；" +
            "如需临时放开，设为 Off 或把该应用加入允许清单。"
}
