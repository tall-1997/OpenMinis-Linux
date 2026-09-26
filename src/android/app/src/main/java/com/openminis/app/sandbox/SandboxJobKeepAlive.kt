package com.openminis.app.sandbox

import android.content.Context
import com.openminis.app.service.AgentForegroundService
import com.openminis.app.service.SessionActivityTracker
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Pins a foreground service + overlay status while a sandbox command runs,
 * and remembers session ids so the next process start can recreate shells
 * on the next command (PersistentShell already rebuilds on demand).
 */
object SandboxJobKeepAlive {

    private const val PREFS = "sandbox_job_keepalive"
    private const val KEY_SESSIONS = "sessions"
    private val inflight = ConcurrentHashMap.newKeySet<String>()

    fun onStart(context: Context, sessionId: String, preview: String) {
        if (sessionId.startsWith("__")) return
        inflight.add(sessionId)
        remember(context, sessionId)
        val key = "sandbox:$sessionId"
        SessionActivityTracker.setActive(key)
        SessionActivityTracker.updateToolStatus(preview.take(80).ifBlank { "sandbox job" })
        if (!AgentForegroundService.isRunning) {
            AgentForegroundService.startService(context)
        }
    }

    fun onEnd(context: Context, sessionId: String) {
        inflight.remove(sessionId)
        SessionActivityTracker.setInactive("sandbox:$sessionId")
        if (inflight.isEmpty()) {
            SessionActivityTracker.updateToolStatus("Idle")
        } else {
            SessionActivityTracker.updateToolStatus("sandbox jobs ${inflight.size}")
            if (!AgentForegroundService.isRunning) {
                AgentForegroundService.startService(context)
            }
        }
    }

    fun rememberedSessions(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SESSIONS, "[]")
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun writeResumeHint(context: Context, rootfsDir: java.io.File?) {
        val run = rootfsDir?.let { java.io.File(it, "run").also { d -> d.mkdirs() } } ?: return
        val obj = JSONObject()
            .put("sessions", JSONArray(rememberedSessions(context)))
            .put("note", "shells recreate on next command; overlay Stop still cancels the current job")
        java.io.File(run, "minis-last-sessions.json").writeText(obj.toString())
    }

    private fun remember(context: Context, sessionId: String) {
        val cur = rememberedSessions(context).toMutableList()
        if (sessionId !in cur) cur += sessionId
        val arr = JSONArray()
        cur.takeLast(16).forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SESSIONS, arr.toString()).apply()
    }
}
