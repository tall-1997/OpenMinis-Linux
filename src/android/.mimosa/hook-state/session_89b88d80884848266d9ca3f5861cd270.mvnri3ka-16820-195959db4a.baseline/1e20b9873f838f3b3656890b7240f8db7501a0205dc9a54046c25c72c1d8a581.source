package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.SandboxFirewall
import com.openminis.app.sandbox.SandboxHttpProxy
import org.json.JSONObject

/**
 * minis-firewall — inspect / set sandbox network policy.
 *
 *   minis-firewall
 *   minis-firewall status
 *   minis-firewall set allow|wifi-only|log|deny [--strict]
 *   minis-firewall log | cut | netlog
 */
class FirewallOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), setOf("strict"))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        val sub = args.positional.firstOrNull() ?: "status"
        return try {
            when (sub) {
                "status" -> ok(SandboxFirewall.snapshot(context), args)
                "set" -> {
                    val raw = args.positional.getOrNull(1)
                        ?: return NativeOffloadResult(2, "minis-firewall: missing policy\n$HELP")
                    val policy = SandboxFirewall.Policy.parse(raw)
                    SandboxFirewall.setPolicy(context, policy)
                    val json = SandboxFirewall.snapshot(context)
                    if (args.hasFlag("strict")) {
                        json.put("strict", SandboxFirewall.applyStrict(context, policy))
                    }
                    ok(json, args)
                }
                "log" -> {
                    SandboxFirewall.setPolicy(context, SandboxFirewall.Policy.LOG)
                    ok(SandboxFirewall.snapshot(context), args)
                }
                "cut" -> {
                    SandboxFirewall.setPolicy(context, SandboxFirewall.Policy.DENY)
                    ok(SandboxFirewall.snapshot(context), args)
                }
                "netlog" -> ok(JSONObject().put("recent", SandboxHttpProxy.recentJson()), args)
                else -> NativeOffloadResult(2, "minis-firewall: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            val body = JSONObject().put("error", "internal").put("message", e.message ?: "unknown").toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    private fun ok(json: JSONObject, args: OffloadArgs) =
        NativeOffloadResult(0, OffloadOutput.formatBody(json.toString(2), args) + "\n")

    companion object {
        private const val HELP = """minis-firewall — sandbox network policy (no LSPosed, no VpnService)

Usage:
  minis-firewall [status]
  minis-firewall set allow|wifi-only|log|deny [--strict]
  minis-firewall log
  minis-firewall cut
  minis-firewall netlog

log/cut inject http_proxy=http://127.0.0.1:17890 into the sandbox.
Host OkHttp (LLM) ignores http_proxy. uid iptables DROP is never auto-applied.
--strict wifi-only binds the whole app process to Wi-Fi (including the LLM).
"""
    }
}
