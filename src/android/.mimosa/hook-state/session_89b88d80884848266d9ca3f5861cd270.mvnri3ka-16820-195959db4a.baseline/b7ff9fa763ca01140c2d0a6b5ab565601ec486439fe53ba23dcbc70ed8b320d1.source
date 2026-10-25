package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Guest scripts registered against host events (`battery_low`, `doze_enter`, …).
 * Commands must pass [com.openminis.app.notification.SandboxNotifyActions.isSafeGuestCommand].
 */
object HostEventHooks {

    private const val TAG = "HostEventHooks"
    private const val PREFS = "host_event_hooks"
    private const val KEY = "hooks_json"
    const val SESSION_ID = "__host-events__"

    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    @Synchronized
    fun list(): Map<String, List<String>> {
        val ctx = app ?: return emptyMap()
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}"
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { k ->
                val arr = obj.optJSONArray(k) ?: JSONArray()
                (0 until arr.length()).map { arr.getString(it) }
            }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun register(event: String, command: String): Boolean {
        val ctx = app ?: return false
        val ev = event.trim()
        val cmd = command.trim()
        if (ev.isEmpty() || !com.openminis.app.notification.SandboxNotifyActions.isSafeGuestCommand(cmd)) {
            return false
        }
        val next = list().toMutableMap()
        val cur = next[ev].orEmpty().toMutableList()
        if (cmd !in cur) cur += cmd
        next[ev] = cur
        persist(ctx, next)
        return true
    }

    @Synchronized
    fun unregister(event: String, command: String?): Boolean {
        val ctx = app ?: return false
        val next = list().toMutableMap()
        if (command.isNullOrBlank()) {
            next.remove(event)
        } else {
            next[event] = next[event].orEmpty().filterNot { it == command }
        }
        persist(ctx, next)
        return true
    }

    fun dispatch(event: String) {
        val cmds = list()[event].orEmpty()
        if (cmds.isEmpty()) return
        HostEventBridge.launch {
            withContext(Dispatchers.IO) {
                for (cmd in cmds) {
                    try {
                        Log.i(TAG, "hook $event → $cmd")
                        ExecutionCoordinator.execute(SESSION_ID, cmd)
                    } catch (t: Throwable) {
                        Log.w(TAG, "hook failed: ${t.message}")
                    }
                }
            }
        }
    }

    @Synchronized
    private fun persist(ctx: Context, map: Map<String, List<String>>) {
        val obj = JSONObject()
        for ((k, v) in map) {
            val arr = JSONArray()
            v.forEach { arr.put(it) }
            obj.put(k, arr)
        }
        val ok = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, obj.toString())
            .commit()
        if (!ok) Log.w(TAG, "failed to persist host event hooks")
    }
}
