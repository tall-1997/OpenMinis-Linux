package com.openminis.app.device

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared argument / result plumbing for the device tool group files.
 * Every tool returns a model-readable English String; failures always use the
 * `Error: <code> — <message>` shape so the caller can pattern-match them.
 */
internal object DeviceArgs {

    fun fail(code: String, message: String): String = "Error: $code — $message"

    /** True when [argsJson] parses; on failure the tool should return [fail]("invalid_args", …). */
    fun parseOrNull(argsJson: String): JSONObject? = runCatching { JSONObject(argsJson) }.getOrNull()

    /** Shizuku path marker written into results of tools that can fall back to a system panel. */
    const val VIA_SHIZUKU = "via Shizuku shell"

    /**
     * The shell command for a privileged toggle, run through [com.openminis.app.offload.ShizukuManager].
     * Returns null when Shizuku is not READY (the caller then takes its fallback path); a non-null
     * result is the trimmed combined output (empty string on clean success).
     */
    fun shizukuShell(argv: Array<String>): String? {
        val mgr = com.openminis.app.offload.ShizukuManager
        if (!mgr.isReady()) return null
        val r = mgr.runProcess(argv)
        if (r.exitCode != 0) return null
        return r.combined.trim()
    }

    /** True when Shizuku is READY (privileged shell available). */
    fun shizukuReady(): Boolean = com.openminis.app.offload.ShizukuManager.isReady()
}

/** String arg: present, non-null and non-blank (trimmed). */
internal fun JSONObject.strArg(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() }
}

/** Int arg: accepts a JSON number or a numeric string ("12"); anything else is absent. */
internal fun JSONObject.intArg(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return when (val v = get(key)) {
        is Number -> v.toInt()
        is String -> v.trim().toDoubleOrNull()?.toInt()
        else -> null
    }
}

internal fun JSONObject.longArg(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val v = get(key)) {
        is Number -> v.toLong()
        is String -> v.trim().toDoubleOrNull()?.toLong()
        else -> null
    }
}

internal fun JSONObject.boolArg(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return when (val v = get(key)) {
        is Boolean -> v
        is String -> v.trim().lowercase().let { when (it) { "true", "1", "yes" -> true; "false", "0", "no" -> false; else -> null } }
        is Number -> v.toInt() != 0
        else -> null
    }
}

/** String list arg: a JSON array of strings, or a single string treated as one element. */
internal fun JSONObject.strListArg(key: String): List<String>? {
    if (!has(key) || isNull(key)) return null
    return when (val v = get(key)) {
        is JSONArray -> (0 until v.length()).mapNotNull { i -> v.optString(i).trim().takeIf { s -> s.isNotEmpty() } }
            .takeIf { it.isNotEmpty() }
        is String -> v.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) }
        else -> null
    }
}

/**
 * Android 10+ lets only the app in focus read the clipboard. Process-importance is the one
 * reliable check that needs no Activity handle.
 */
internal fun isAppForeground(context: Context): Boolean = runCatching {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@runCatching false
    am.runningAppProcesses?.any {
        it.pid == Process.myPid() && it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    } ?: false
}.getOrDefault(false)

/** "12.3" style GB with one decimal. */
internal fun gb(bytes: Long): String = "%.1f".format(bytes / 1e9)
