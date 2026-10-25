package com.openminis.app.sandbox.offload

import com.openminis.app.sandbox.HostEventHooks
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * minis-on-event — register guest scripts against host events.
 */
class HostEventOffloadHandler : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        val sub = args.positional.firstOrNull() ?: "list"
        return when (sub) {
            "list" -> {
                val obj = JSONObject()
                for ((k, v) in HostEventHooks.list()) {
                    obj.put(k, JSONArray(v))
                }
                NativeOffloadResult(0, obj.toString(2) + "\n")
            }
            "register" -> {
                val event = args.positional.getOrNull(1) ?: return NativeOffloadResult(2, "missing event\n$HELP")
                val cmd = args.positional.drop(2).joinToString(" ").ifBlank {
                    args.get("command")
                } ?: return NativeOffloadResult(2, "missing command\n$HELP")
                if (!HostEventHooks.register(event, cmd)) {
                    NativeOffloadResult(2, "rejected: event empty or command failed the path sanitizer\n")
                } else {
                    NativeOffloadResult(0, JSONObject().put("ok", true).put("event", event).toString() + "\n")
                }
            }
            "unregister" -> {
                val event = args.positional.getOrNull(1) ?: return NativeOffloadResult(2, "missing event\n")
                val cmd = args.positional.drop(2).joinToString(" ").ifBlank { null }
                HostEventHooks.unregister(event, cmd)
                NativeOffloadResult(0, "{\"ok\":true}\n")
            }
            else -> NativeOffloadResult(2, "minis-on-event: unknown '$sub'\n$HELP")
        }
    }

    companion object {
        private const val HELP = """minis-on-event — host→sandbox reverse event hooks

Events: battery_low battery_ok doze_enter doze_exit network_available network_lost

Usage:
  minis-on-event list
  minis-on-event register battery_low /var/minis/hooks/pause.sh
  minis-on-event unregister battery_low

Live feed: /run/android-events.jsonl  snapshot: /run/minis-host-status.json
Commands must pass the same sanitizer as minis-notify actions.
"""
    }
}
