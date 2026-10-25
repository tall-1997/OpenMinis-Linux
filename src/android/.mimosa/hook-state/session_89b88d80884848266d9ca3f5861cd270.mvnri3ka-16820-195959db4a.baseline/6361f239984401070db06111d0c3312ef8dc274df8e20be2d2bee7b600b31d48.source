package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.power.PowerOptimizationManager
import com.openminis.app.sandbox.DozeSnapshot
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject

/**
 * minis-doze — Doze / battery-optimisation status and request dialog.
 */
class DozeOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        val sub = args.positional.firstOrNull() ?: "status"
        return try {
            when (sub) {
                "status" -> ok(DozeSnapshot.json(context), args)
                "request" -> {
                    val launched = PowerOptimizationManager.requestBatteryOptimizationExemptionFromContext(context)
                    ok(DozeSnapshot.json(context).put("launched", launched), args)
                }
                "oem" -> {
                    val launched = PowerOptimizationManager.openOemAutostartSettingsFromContext(context)
                    ok(DozeSnapshot.json(context).put("launched", launched), args)
                }
                else -> NativeOffloadResult(2, "minis-doze: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            val body = JSONObject().put("error", "internal").put("message", e.message ?: "unknown").toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    private fun ok(json: JSONObject, args: OffloadArgs) =
        NativeOffloadResult(0, OffloadOutput.formatBody(json.toString(2), args) + "\n")

    companion object {
        private const val HELP = """minis-doze — battery optimisation / Doze

Usage:
  minis-doze [status]
  minis-doze request     Open the ignore-battery-optimizations dialog
  minis-doze oem         Open OEM autostart settings when applicable
"""
    }
}
