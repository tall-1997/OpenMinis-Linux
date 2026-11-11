// [OpenMinis#546] Tailscale stop-time policy-routing cleanup.
package com.openminis.app.network

import com.openminis.app.logging.AppLogger
import java.util.concurrent.TimeUnit

/**
 * [OpenMinis#546] Remove the policy-routing entries a Tailscale run leaves
 * behind when it stops.
 *
 * Symptom from the field: after stopping Tailscale, `ip rule` still pointed at
 * route table 1076 and table 1076 still held `default dev tun0`; IPv6
 * connectivity broke intermittently afterwards because the stale rule kept
 * steering traffic into a dead interface. The embedded node itself is
 * userspace tsnet and normally installs nothing, but the module ALSO coexists
 * with kernel-TUN tailscaled builds, previous installs and ROM-level VPN
 * helpers — so the stop path cannot assume "nothing was added".
 *
 * The cleanup is DIFF-based on purpose, never "delete table 1076":
 *  - [captureBaseline] records the tun-related `ip rule` / `ip route` entries
 *    BEFORE the node starts;
 *  - [cleanupAfterStop] records them AGAIN after the node stops and removes
 *    only entries that appeared in between. Anything that existed before the
 *    run (the official Tailscale app, another VPN, a static ROM route) is in
 *    the baseline and is therefore never touched.
 *
 * Deleting requires CAP_NET_ADMIN, so the commands run through `su` when the
 * device has root. When that fails the reason is surfaced via [lastError] and
 * the log — a cleanup failure must never be silent, but it must also never
 * throw into the network apply path.
 *
 * The object is deliberately dependency-free and synchronous: it is called
 * from NetworkHub's serial IO coroutine around the blocking Go start/stop
 * calls, where a process spawn with a timeout is acceptable.
 */
object TailscaleRouteCleanup {

    private const val TAG = "Network"
    private const val MAX_DELETIONS = 32
    private const val TIMEOUT_MS = 2_000L

    /** One `ip` output line plus the command that would delete it. */
    private data class Entry(val line: String, val deleteArgs: List<String>)

    private data class Snapshot(val rules: List<Entry>, val routes: List<Entry>) {
        val keys: Set<String> get() = (rules + routes).map { it.line }.toSet()
    }

    @Volatile
    private var baseline: Snapshot? = null

    /**
     * [OpenMinis#546] The reason the last cleanup could not remove something,
     * or null. Read by NetworkHub and appended to the visible network error
     * instead of being swallowed.
     */
    @Volatile
    var lastError: String? = null
        private set

    /** True once a run has a baseline to diff against (diagnostics). */
    val hasBaseline: Boolean get() = baseline != null

    /**
     * Record the pre-start state. Called from [TailscaleNode.start] before the
     * Go start; repeated calls while a baseline already exists are no-ops
     * (the node starts idempotently on every settings apply).
     */
    @Synchronized
    fun captureBaseline() {
        if (baseline != null) return
        baseline = runCatching { readSnapshot() }.onFailure {
            AppLogger.warning(TAG, "[Net][TS] route baseline capture failed: ${it.message}")
        }.getOrNull()
    }

    /**
     * Diff against [captureBaseline] and delete every entry that appeared
     * during the run. Returns the visible error string, or null on success /
     * when there was nothing to do.
     */
    @Synchronized
    fun cleanupAfterStop(reason: String): String? {
        val base = baseline ?: return null
        baseline = null
        val after = runCatching { readSnapshot() }.getOrElse {
            val err = "Tailscale routes were not cleaned ($reason): cannot read ip state: ${it.message}"
            lastError = err
            AppLogger.error(TAG, "[Net][TS] $err")
            return err
        }
        val before = base.keys
        val addedRules = after.rules.filter { it.line !in before }
        val addedRoutes = after.routes.filter { it.line !in before }
        if (addedRules.isEmpty() && addedRoutes.isEmpty()) {
            lastError = null
            AppLogger.info(TAG, "[Net][TS] route cleanup ($reason): nothing to remove")
            return null
        }
        val failures = mutableListOf<String>()
        var attempted = 0
        // Rules first: a stale policy rule is what keeps steering traffic into
        // a route that is about to disappear.
        for (entry in (addedRules + addedRoutes).take(MAX_DELETIONS)) {
            attempted++
            if (!runRoot(entry.deleteArgs)) {
                failures += entry.line
                // One failed deletion almost always means "no root / su prompt
                // not answered", which will fail identically for every other
                // entry. Stop here instead of stalling the serial network-apply
                // queue for up to 32 × timeout.
                break
            }
        }
        val error = if (failures.isEmpty()) {
            null
        } else {
            val remaining = (addedRules.size + addedRoutes.size) - (attempted - 1)
            "Tailscale route cleanup ($reason) could not remove $remaining entr" +
                (if (remaining == 1) "y" else "ies") +
                " (root unavailable?): " + failures.take(3).joinToString("; ")
        }
        lastError = error
        if (error != null) {
            AppLogger.error(TAG, "[Net][TS] $error")
        } else {
            AppLogger.info(
                TAG,
                "[Net][TS] route cleanup ($reason): removed ${addedRules.size} rule(s), " +
                    "${addedRoutes.size} route(s)",
            )
        }
        return error
    }

    /** Visible error from outside (settings / diagnostics). */
    fun visibleError(): String? = lastError

    // ── ip parsing / execution ─────────────────────────────────────────────

    private fun readSnapshot(): Snapshot {
        val rules = runIp(listOf("rule", "show"))?.let { text ->
            text.lineSequence().mapNotNull { line ->
                ruleDeleteArgs(line.trim())?.let { Entry(line.trim(), it) }
            }.toList()
        }.orEmpty()
        val routes = runIp(listOf("route", "show", "table", "all"))?.let { text ->
            text.lineSequence().mapNotNull { line ->
                routeDeleteArgs(line.trim())?.let { Entry(line.trim(), it) }
            }.toList()
        }.orEmpty()
        return Snapshot(rules, routes)
    }

    /** `32765: from all fwmark 0x80000 lookup 1076` → delete by priority. */
    internal fun ruleDeleteArgs(line: String): List<String>? {
        if (line.isEmpty() || line.startsWith("Priority") || !line.contains("lookup")) return null
        val priority = line.substringBefore(':').trim().toIntOrNull() ?: return null
        return listOf("rule", "del", "priority", priority.toString())
    }

    /**
     * Only entries bound to a `tun*` device are kept — the class of route a
     * Tailscale stop can leave behind. Everything else (wlan, rmnet, lo) is
     * outside this cleanup's scope by construction.
     *
     * `default dev tun0 table 1076 proto static scope link`
     *   → `ip route del default dev tun0 table 1076`
     */
    internal fun routeDeleteArgs(line: String): List<String>? {
        if (line.isEmpty()) return null
        val tokens = line.split(Regex("\\s+"))
        val devIndex = tokens.indexOf("dev")
        if (devIndex < 0 || devIndex + 1 >= tokens.size) return null
        val dev = tokens[devIndex + 1]
        if (!dev.startsWith("tun")) return null
        val dest = tokens.first()
        val args = mutableListOf("route", "del", dest, "dev", dev)
        val tableIndex = tokens.indexOf("table")
        if (tableIndex >= 0 && tableIndex + 1 < tokens.size) {
            args += listOf("table", tokens[tableIndex + 1])
        }
        return args
    }

    private fun runIp(args: List<String>): String? {
        val command = listOf("/system/bin/ip") + args
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                null
            } else {
                process.inputStream.bufferedReader().readText()
            }
        }.getOrNull()
    }

    /**
     * Run one `ip` deletion. Root is required on stock Android; a device
     * without `su` fails here and the entry is reported through [lastError].
     */
    private fun runRoot(args: List<String>): Boolean {
        val cmd = (listOf("/system/bin/ip") + args).joinToString(" ")
        val attempts = listOf(listOf("su", "-c", cmd), (listOf("/system/bin/ip") + args))
        for (attempt in attempts) {
            val ok = runCatching {
                val process = ProcessBuilder(attempt).redirectErrorStream(true).start()
                val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    false
                } else {
                    process.exitValue() == 0
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /** Test hook: clear the baseline without touching the system. */
    internal fun resetForTest() {
        baseline = null
        lastError = null
    }
}
