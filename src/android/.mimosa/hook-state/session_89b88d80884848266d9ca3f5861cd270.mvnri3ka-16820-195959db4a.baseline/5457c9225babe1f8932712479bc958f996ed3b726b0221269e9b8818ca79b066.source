package com.openminis.app.sandbox.offload

import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.ProcfsSnapshot
import org.json.JSONObject

/**
 * minis-ps — PIDs this app can read under Android hidepid.
 */
class ProcfsOffloadHandler : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), setOf("json"))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        val sub = args.positional.firstOrNull() ?: if (args.hasFlag("json")) "json" else "table"
        return try {
            when (sub) {
                "json" -> NativeOffloadResult(
                    0,
                    OffloadOutput.formatBody(ProcfsSnapshot.snapshot().toString(2), args) + "\n",
                )
                "table", "ps" -> NativeOffloadResult(0, ProcfsSnapshot.table())
                else -> NativeOffloadResult(2, "minis-ps: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            val body = JSONObject().put("error", "internal").put("message", e.message ?: "unknown").toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    companion object {
        private const val HELP = """minis-ps — readable /proc for this app UID (Android hidepid)

Usage:
  minis-ps [table]
  minis-ps json
  cat /run/minis-proc.json
"""
    }
}
