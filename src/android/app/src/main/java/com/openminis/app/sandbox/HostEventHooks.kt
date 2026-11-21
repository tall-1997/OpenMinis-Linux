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

    /**
     * [T-prefs-typed] 文件名与键都登记在 PrefsRegistry（host_event_hooks / hooks_json）。
     * 写入语义不变：PrefsBackend 内部仍走 `editor.commit()`（同步），只是不再把
     * 布尔返回值往上传 —— commit 仅在磁盘故障时为 false，调用方原本也只是打一条 warn。
     */
    private fun prefs() = com.openminis.app.config.Prefs.file(PREFS)

    fun init(context: Context) {
        app = context.applicationContext
    }

    @Synchronized
    fun list(): Map<String, List<String>> {
        if (app == null) return emptyMap()
        val raw = prefs().string(KEY, "{}")
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
        if (app == null) return false
        val ev = event.trim()
        val cmd = command.trim()
        if (ev.isEmpty() || !com.openminis.app.notification.SandboxNotifyActions.isSafeGuestCommand(cmd)) {
            return false
        }
        val next = list().toMutableMap()
        val cur = next[ev].orEmpty().toMutableList()
        if (cmd !in cur) cur += cmd
        next[ev] = cur
        persist(next)
        return true
    }

    @Synchronized
    fun unregister(event: String, command: String?): Boolean {
        if (app == null) return false
        val next = list().toMutableMap()
        if (command.isNullOrBlank()) {
            next.remove(event)
        } else {
            next[event] = next[event].orEmpty().filterNot { it == command }
        }
        persist(next)
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
    private fun persist(map: Map<String, List<String>>) {
        val obj = JSONObject()
        for ((k, v) in map) {
            val arr = JSONArray()
            v.forEach { arr.put(it) }
            obj.put(k, arr)
        }
        prefs().set(KEY, obj.toString())
        Log.d(TAG, "persisted host event hooks")
    }
}
