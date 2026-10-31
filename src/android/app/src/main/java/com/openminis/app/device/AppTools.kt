package com.openminis.app.device

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.knowledge.AppKnowledgeStore
import org.json.JSONObject

/**
 * Apps group of the device tool set: list / inspect / launch installed apps,
 * open links and deep links, build explicit intents, and share text via the
 * system share sheet. Launches start from this app's process with
 * FLAG_ACTIVITY_NEW_TASK; when Android blocks a background start and Shizuku
 * is ready, the launch is retried through the Shizuku shell (`am start`).
 */
internal object AppTools {

    private const val TOOL_TITLE_PARAM = "tool_title"
    private const val TOOL_TITLE_DESC = "A concise 5-10 word summary shown to the user."

    private fun titleParam() = AgentToolParam("string", TOOL_TITLE_DESC)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AppListTool.definition(),
        AppInfoTool.definition(),
        AppLaunchTool.definition(),
        AppOpenLinkTool.definition(),
        AppSendIntentTool.definition(),
        ShareToAppTool.definition(),
    )

    fun execute(name: String, argsJson: String, context: Context, sessionId: String? = null): String = when (name) {
        AppListTool.NAME -> AppListTool.execute(argsJson, context)
        AppInfoTool.NAME -> AppInfoTool.execute(argsJson, context)
        AppLaunchTool.NAME -> AppLaunchTool.execute(argsJson, context, sessionId)
        AppOpenLinkTool.NAME -> AppOpenLinkTool.execute(argsJson, context)
        AppSendIntentTool.NAME -> AppSendIntentTool.execute(argsJson, context)
        ShareToAppTool.NAME -> ShareToAppTool.execute(argsJson, context)
        else -> DeviceArgs.fail("unknown_tool", "apps group does not handle '$name'")
    }

    // ── shared plumbing ──

    /**
     * Start an activity from the app process. On a background-start
     * SecurityException, retry through the Shizuku shell when available.
     */
    private fun start(context: Context, intent: Intent): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "ok"
        } catch (e: SecurityException) {
            val via = DeviceArgs.shizukuShell(arrayOf("am", "start", "-W", intent.toUri(Intent.URI_INTENT_SCHEME)))
            if (via != null) {
                "ok (via Shizuku shell)"
            } else {
                DeviceArgs.fail(
                    "failed",
                    "Android blocked starting this activity from the background and no Shizuku shell is " +
                        "available (${e.message}). Ask the user to open it, or set up Shizuku.",
                )
            }
        }
    }

    private fun labelOf(pm: PackageManager, ai: ApplicationInfo): String =
        runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName)

    // ── app_list ──

    internal object AppListTool {
        const val NAME = "app_list"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "List installed, user-launchable apps as 'Label (package)'. Filter with query " +
                "(substring of label or package); system apps are excluded unless include_system is true " +
                "(or a query is set). Use app_launch with the returned package. Read-only.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "query" to AgentToolParam("string", "Substring to match against app label or package name."),
                "include_system" to AgentToolParam("boolean", "Include system apps (default false)."),
                "limit" to AgentToolParam("integer", "Max apps to return (default 100)."),
            ),
            required = listOf(TOOL_TITLE_PARAM),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "query", "include_system", "limit"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val query = obj.strArg("query")?.lowercase()
            val includeSystem = obj.boolArg("include_system") == true
            val limit = (obj.intArg("limit") ?: 100).coerceIn(1, 500)
            val pm = context.packageManager
            val launchable = runCatching {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0,
                )
            }.getOrDefault(emptyList())
            val apps = launchable.asSequence()
                .mapNotNull { it.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .filter { includeSystem || query != null || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { labelOf(pm, it) to it.packageName }
                .filter { (label, pkg) -> query == null || label.lowercase().contains(query) || pkg.lowercase().contains(query) }
                .sortedBy { (label, _) -> label.lowercase() }
                .toList()
            if (apps.isEmpty()) return "No matching apps."
            val shown = apps.take(limit)
            return shown.joinToString("\n") { (label, pkg) -> "$label ($pkg)" } +
                if (apps.size > shown.size) "\n…(${apps.size - shown.size} more; narrow with query)" else ""
        }
    }

    // ── app_info ──

    internal object AppInfoTool {
        const val NAME = "app_info"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Details of one installed app: display name, version, install/update time, APK size, " +
                "system app flag, target SDK, and its requested permissions with granted state. Read-only.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "package" to AgentToolParam("string", "Package name, e.g. com.example.app."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "package"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "package"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val pkg = obj.strArg("package") ?: return DeviceArgs.fail("invalid_args", "missing 'package'")
            val pm = context.packageManager
            val info = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull()
                ?: return DeviceArgs.fail("invalid_args", "package '$pkg' is not installed")
            val ai = info.applicationInfo ?: return DeviceArgs.fail("failed", "no application info for '$pkg'")
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
            val apkSize = runCatching { java.io.File(ai.sourceDir).length() }.getOrDefault(-1)
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val perms = info.requestedPermissions.orEmpty().mapIndexedNotNull { i, name ->
                val granted = pm.checkPermission(name, pkg) == PackageManager.PERMISSION_GRANTED
                "$name${if (granted) " (granted)" else ""}"
            }
            return buildString {
                appendLine("${labelOf(pm, ai)} ($pkg) v${info.versionName ?: "?"} (${versionCode})")
                appendLine("installed: ${java.util.Date(info.firstInstallTime)}; updated: ${java.util.Date(info.lastUpdateTime)}")
                appendLine("apk: ${if (apkSize >= 0) "%.1f".format(apkSize / 1e6) + " MB" else "unknown"}; system: $isSystem; targetSdk: ${ai.targetSdkVersion}")
                append("permissions: ${if (perms.isEmpty()) "none requested" else perms.joinToString(", ")}")
            }
        }
    }

    // ── app_launch ──

    internal object AppLaunchTool {
        const val NAME = "app_launch"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Launch an installed app by package name on the user's screen (launcher or " +
                "leanback entry point, or a specific exported activity). Falls back to the Shizuku shell " +
                "when Android blocks the background start. Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "package" to AgentToolParam("string", "Package name, e.g. com.example.app."),
                "activity" to AgentToolParam("string", "Optional fully-qualified activity (e.g. com.example.app/.MainActivity)."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "package"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "package", "activity"),
        )

        fun execute(argsJson: String, context: Context, sessionId: String? = null): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val pkg = obj.strArg("package") ?: return DeviceArgs.fail("invalid_args", "missing 'package'")
            val pm = context.packageManager
            val intent: Intent? = obj.strArg("activity")?.let { act ->
                val cls = if (act.startsWith(".")) "$pkg$act" else act
                runCatching { Intent().setComponent(ComponentName(pkg, cls)) }.getOrNull()
            } ?: runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
                ?: runCatching { // leanback/TV entry point
                    pm.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER").setPackage(pkg),
                        0,
                    ).firstOrNull()?.let {
                        Intent(Intent.ACTION_MAIN).setClassName(pkg, it.activityInfo.name)
                    }
                }.getOrNull()
            if (intent == null) return DeviceArgs.fail("invalid_args", "no launchable activity for '$pkg' (is it installed?)")
            return try {
                start(context, intent)
                val base = "Launched $pkg."
                // [T-app-knowledge] When the target app has a knowledge pack, append it once
                // per session so the model gets the app's UI facts right after the launch.
                val knowledge = AppKnowledgeStore.afterLaunch(context, pkg, sessionId)
                if (knowledge == null) base else "$base\n$knowledge"
            } catch (e: ActivityNotFoundException) {
                DeviceArgs.fail("invalid_args", "no launchable activity for '$pkg'")
            }
        }
    }

    // ── app_open_link ──

    internal object AppOpenLinkTool {
        const val NAME = "app_open_link"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Open a URI with the system resolver: https:// web links, geo:, tel:, mailto:, " +
                "market:// store pages, and app deep-link schemes (weixin://, alipays://, …). Optionally " +
                "restrict to one package. Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "uri" to AgentToolParam("string", "The URI to open, e.g. https://example.com or geo:0,0?q=…"),
                "package" to AgentToolParam("string", "Optional package to handle the URI exclusively."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "uri"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "uri", "package"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val uri = obj.strArg("uri") ?: return DeviceArgs.fail("invalid_args", "missing 'uri'")
            val parsed = runCatching { Uri.parse(uri) }.getOrNull()
                ?: return DeviceArgs.fail("invalid_args", "'uri' is not parseable: $uri")
            val intent = Intent(Intent.ACTION_VIEW, parsed)
            val pkg = obj.strArg("package")
            if (pkg != null) {
                intent.setPackage(pkg)
                if (context.packageManager.resolveActivity(intent, 0) == null) {
                    return DeviceArgs.fail("invalid_args", "'$pkg' cannot handle $uri")
                }
            }
            return try {
                start(context, intent)
                "Opened $uri${pkg?.let { " in $it" } ?: ""}."
            } catch (e: ActivityNotFoundException) {
                DeviceArgs.fail("invalid_args", "no app can open $uri")
            }
        }
    }

    // ── app_send_intent ──

    internal object AppSendIntentTool {
        const val NAME = "app_send_intent"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Construct and send an explicit Android Intent — an escape hatch for actions no " +
                "dedicated tool covers (open a settings screen, trigger a provider action). Requires at " +
                "least 'action'; optional data URI, MIME type, target package, component (pkg/pkg.Activity) " +
                "and extras object of string/number/boolean values. The activity starts on the user's " +
                "screen. Dangerous: sends an Intent on your behalf.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "action" to AgentToolParam("string", "Intent action, e.g. android.settings.WIRELESS_SETTINGS or android.intent.action.VIEW."),
                "data" to AgentToolParam("string", "Optional data URI, e.g. package:com.example.app."),
                "type" to AgentToolParam("string", "Optional MIME type for the data."),
                "package" to AgentToolParam("string", "Optional target package for the Intent."),
                "component" to AgentToolParam("string", "Optional explicit component as 'package' or 'package/.Activity' or fully-qualified class."),
                "extras" to AgentToolParam("object", "Optional extra key/values; values may be strings, numbers or booleans."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "action"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "action", "data", "type", "package", "component", "extras"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val action = obj.strArg("action") ?: return DeviceArgs.fail("invalid_args", "missing 'action'")
            val intent = Intent(action)
            val data = obj.strArg("data")
            val type = obj.strArg("type")
            try {
                if (data != null && type != null) intent.setDataAndType(Uri.parse(data), type)
                else {
                    data?.let { intent.setData(Uri.parse(it)) }
                    type?.let { intent.setType(it) }
                }
                obj.strArg("package")?.let { intent.setPackage(it) }
                obj.strArg("component")?.let { comp ->
                    val cn = if (comp.contains("/")) ComponentName.unflattenFromString(comp)
                    else {
                        val p = intent.`package` ?: action.substringBeforeLast('.')
                        ComponentName(p, comp)
                    }
                        ?: return DeviceArgs.fail("invalid_args", "'component' is not parseable: $comp")
                    intent.component = cn
                }
                obj.optJSONObject("extras")?.let { extras ->
                    for (key in extras.keys()) {
                        when (val v = extras.get(key)) {
                            is Boolean -> intent.putExtra(key, v)
                            is Int -> intent.putExtra(key, v)
                            is Long -> intent.putExtra(key, v)
                            is Double -> intent.putExtra(key, v)
                            is String -> intent.putExtra(key, v)
                            else -> intent.putExtra(key, v.toString())
                        }
                    }
                }
                start(context, intent)
                return "Sent intent '$action'${intent.component?.let { " to ${it.flattenToString()}" } ?: ""}."
            } catch (e: ActivityNotFoundException) {
                return DeviceArgs.fail("invalid_args", "no activity found for '$action': ${e.message}")
            }
        }
    }

    // ── share_to_app ──

    internal object ShareToAppTool {
        const val NAME = "share_to_app"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Share text via the Android share sheet (ACTION_SEND): opens the system chooser " +
                "so the user picks the target app, or targets one package directly. Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "text" to AgentToolParam("string", "Text to share."),
                "subject" to AgentToolParam("string", "Optional message subject."),
                "mime" to AgentToolParam("string", "MIME type (default text/plain)."),
                "package" to AgentToolParam("string", "Optional target app; omit to open the chooser."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "text"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "text", "subject", "mime", "package"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val text = obj.strArg("text") ?: return DeviceArgs.fail("invalid_args", "missing 'text'")
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = obj.strArg("mime") ?: "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                obj.strArg("subject")?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            }
            val pkg = obj.strArg("package")
            return try {
                if (pkg != null) {
                    intent.setPackage(pkg)
                    start(context, intent)
                    "Shared to $pkg."
                } else {
                    start(context, Intent.createChooser(intent, null))
                    "Share sheet opened; the user picks the target app."
                }
            } catch (e: ActivityNotFoundException) {
                DeviceArgs.fail("invalid_args", "no app can share this content${pkg?.let { " into $it" } ?: ""}")
            }
        }
    }
}
