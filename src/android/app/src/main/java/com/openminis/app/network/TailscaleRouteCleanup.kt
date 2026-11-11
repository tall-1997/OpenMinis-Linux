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
 * ## The empty-snapshot trap (why a baseline can be ABSENT)
 *
 * A baseline of "nothing" is indistinguishable from "the `ip` command failed":
 * both used to produce `Snapshot(emptyList(), emptyList())`, and diffing
 * against that made EVERY pre-existing rule and tun route look newly added —
 * the cleanup then deleted the user's other VPN's policy rules. [readSnapshot]
 * therefore returns null unless BOTH `ip` invocations exit 0 with non-blank
 * output, and both [captureBaseline] and [cleanupAfterStop] refuse a snapshot
 * whose `ip rule show` side is unreadable ([Snapshot.hasRules]). No baseline is
 * better than a destructive one.
 *
 * Likewise, a failed re-read at stop time KEEPS the baseline: the run is not
 * over as far as cleanup is concerned, and the next stop/start-failure retries
 * the diff instead of silently disarming it. The baseline is cleared only
 * after a successful diff that needed no deletions, or whose deletions all
 * succeeded.
 *
 * ## Why `ruleDeleteArgs` is table-scoped
 *
 * `ip rule show` lists the kernel's default rules (`0: … lookup local`,
 * `32766: … lookup main`, `32767: … lookup default`). The old predicate
 * accepted ANY line containing `lookup`, so an empty/bogus baseline turned the
 * static defaults themselves into deletion candidates. A candidate is now
 * accepted only when it resolves a Tailscale-owned table (1076 / `tailscale`)
 * or carries Tailscale's fwmark `0x80000`, and the kernel defaults are refused
 * even if a caller hands in a synthetic line.
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

    /** [OpenMinis#546] Tailscale's policy-routing table (numeric + `rt_tables` name). */
    private const val TAILSCALE_TABLE = "1076"
    private const val TAILSCALE_TABLE_NAME = "tailscale"

    /** Tailscale's default fwmark; rules that carry it belong to the run even if named differently. */
    private const val TAILSCALE_FWMARK = "0x80000"

    /** Kernel default policy rules — never deletable, whatever the table name looks like. */
    private val DEFAULT_RULE_PRIORITIES = setOf(0, 32766, 32767)

    /** One `ip` output line plus the command that would delete it. */
    internal data class Entry(val line: String, val deleteArgs: List<String>)

    internal data class Snapshot(
        val rules: List<Entry>,
        val routes: List<Entry>,
        /**
         * Non-blank lines in the raw `ip rule show` output. A real device always
         * lists at least the kernel's default rules (0/32766/32767), so ZERO
         * here means the command failed, printed nothing, or used an
         * unparseable format. Note this is the RAW line count, not
         * [rules].size: [rules] intentionally keeps only deletable candidates,
         * and "no candidate rule existed before the run" is a perfectly normal
         * baseline (cleanup must still be armed for the rules that appear).
         *
         * Zero parsed ROUTES is likewise normal — only tun-bound routes are
         * candidates and the device may have none.
         */
        val ruleLines: Int = rules.size,
    ) {
        val keys: Set<String> get() = (rules + routes).map { it.line }.toSet()

        /** Whether the `ip rule show` side was actually readable. */
        val hasRules: Boolean get() = ruleLines > 0
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
     * Test seam: replaces the real `ip` read (null = use [readSnapshot]).
     * Kept internal so the JVM tests can model failure/empty states without a
     * device, root, or network namespace.
     */
    @Volatile
    internal var snapshotReaderForTest: (() -> Snapshot?)? = null

    /** Test seam: replaces the root delete call (null = use [runRoot]). */
    @Volatile
    internal var deleteRunnerForTest: ((List<String>) -> Boolean)? = null

    private fun snapshot(): Snapshot? = snapshotReaderForTest?.invoke() ?: readSnapshot()

    private fun delete(args: List<String>): Boolean =
        deleteRunnerForTest?.invoke(args) ?: runRoot(args)

    /**
     * Record the pre-start state. Called from [TailscaleNode.start] before the
     * Go start; repeated calls while a baseline already exists are no-ops
     * (the node starts idempotently on every settings apply).
     *
     * [OpenMinis#546] The baseline is installed only from a READABLE snapshot
     * ([Snapshot.hasRules]). A failed/blank `ip` read would otherwise produce an
     * all-empty diff base, making every pre-existing rule/route look newly
     * added and deletable. A readable snapshot whose candidate list happens to
     * be empty is legitimate and IS installed — it means nothing
     * candidate-shaped existed before the run, so anything that appears later
     * really was added by this run.
     */
    @Synchronized
    fun captureBaseline() {
        if (baseline != null) return
        val snap = runCatching { snapshot() }.getOrElse {
            AppLogger.warning(TAG, "[Net][TS] route baseline capture failed: ${it.message}")
            null
        }
        if (snap == null || !snap.hasRules) {
            AppLogger.warning(
                TAG,
                "[Net][TS] route baseline not established (ip state unreadable or empty); " +
                    "stop-time cleanup stays disabled for this run",
            )
            return
        }
        baseline = snap
    }

    /**
     * Diff against [captureBaseline] and delete every entry that appeared
     * during the run. Returns the visible error string, or null on success /
     * when there was nothing to do.
     *
     * [OpenMinis#546] A failed/unreadable post-stop read keeps the existing
     * baseline so a later stop can retry; only a completed diff retires it.
     *
     * [OpenMinis#546] A missing baseline means there is NO cleanup obligation
     * for this stop — and therefore no error to show. [lastError] is cleared on
     * that path: NetworkHub appends [visibleError] to the settings status row on
     * every apply, so leaving a previous run's message behind made a stale
     * "route cleanup failed" reappear on applies that never even attempted a
     * diff. A prior FAILURE always keeps its baseline (see below), so clearing
     * here cannot hide a retryable deletion: the next stop still owns it.
     */
    @Synchronized
    fun cleanupAfterStop(reason: String): String? {
        val base = baseline
        if (base == null) {
            // [OpenMinis#546] baseline 为空 = 本次没有可执行的 diff，上一轮的错误
            // 不能继续被当成这次 apply 的失败（陈旧展示）。真失败一定会保留
            // baseline，所以这里清空不会丢掉待重试项。
            lastError = null
            return null
        }
        val after = runCatching { snapshot() }.getOrElse {
            val err = "Tailscale routes were not cleaned ($reason): cannot read ip state: ${it.message}"
            lastError = err
            AppLogger.error(TAG, "[Net][TS] $err (baseline kept for a later retry)")
            return err
        }
        if (after == null || !after.hasRules) {
            val err = "Tailscale routes were not cleaned ($reason): ip state unreadable or empty"
            lastError = err
            AppLogger.error(TAG, "[Net][TS] $err (baseline kept for a later retry)")
            return err
        }
        val before = base.keys
        val addedRules = after.rules.filter { it.line !in before }
        val addedRoutes = after.routes.filter { it.line !in before }
        if (addedRules.isEmpty() && addedRoutes.isEmpty()) {
            baseline = null
            lastError = null
            AppLogger.info(TAG, "[Net][TS] route cleanup ($reason): nothing to remove")
            return null
        }
        val failures = mutableListOf<String>()
        var attempted = 0
        val total = addedRules.size + addedRoutes.size
        // Rules first: a stale policy rule is what keeps steering traffic into
        // a route that is about to disappear.
        for (entry in (addedRules + addedRoutes).take(MAX_DELETIONS)) {
            attempted++
            if (!delete(entry.deleteArgs)) {
                failures += entry.line
                // One failed deletion almost always means "no root / su prompt
                // not answered", which will fail identically for every other
                // entry. Stop here instead of stalling the serial network-apply
                // queue for up to 32 × timeout.
                break
            }
        }
        val error = if (failures.isEmpty() && attempted < total) {
            // [OpenMinis#546] MAX_DELETIONS is a per-stop safety cap, not
            // "everything was cleaned": report the remainder and keep the
            // baseline so the next stop retries it. Silently clearing the
            // baseline would strand those entries as "pre-existing" forever.
            val left = total - attempted
            "Tailscale route cleanup ($reason) hit the $MAX_DELETIONS-entry safety cap; " +
                "$left entr" + (if (left == 1) "y" else "ies") + " left for the next stop"
        } else if (failures.isEmpty()) {
            null
        } else {
            val remaining = total - (attempted - 1)
            "Tailscale route cleanup ($reason) could not remove $remaining entr" +
                (if (remaining == 1) "y" else "ies") +
                " (root unavailable?): " + failures.take(3).joinToString("; ")
        }
        if (error == null) {
            // The whole diff was deleted: this run is done. Retiring the
            // baseline here is what lets the next node start capture a fresh one.
            baseline = null
        }
        // On failure the ORIGINAL pre-run baseline is kept: every entry the run
        // added is still "after - before", so the next stop retries it, while
        // pre-existing entries stay protected by construction.
        lastError = error
        if (error != null) {
            AppLogger.error(TAG, "[Net][TS] $error (baseline kept for a later retry)")
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

    /**
     * Both reads must succeed: a null on either side means we cannot tell
     * "nothing was added" from "the command is unavailable", and a partial
     * snapshot would silently bias the diff.
     */
    private fun readSnapshot(): Snapshot? {
        val rules = runIp(listOf("rule", "show")) ?: return null
        val routes = runIp(listOf("route", "show", "table", "all")) ?: return null
        return snapshotOf(rules, routes)
    }

    /** Pure parse of the two `ip` outputs; the caller decides whether it is usable. */
    internal fun snapshotOf(ruleOutput: String, routeOutput: String): Snapshot =
        Snapshot(
            rules = ruleOutput.lineSequence().mapNotNull { raw ->
                val line = raw.trim()
                ruleDeleteArgs(line)?.let { Entry(line, it) }
            }.toList(),
            routes = routeOutput.lineSequence().mapNotNull { raw ->
                val line = raw.trim()
                routeDeleteArgs(line)?.let { Entry(line, it) }
            }.toList(),
            ruleLines = ruleOutput.lineSequence().count { it.isNotBlank() },
        )

    /**
     * `32765: from all fwmark 0x80000 lookup 1076` → delete by priority.
     *
     * [OpenMinis#546] Only Tailscale-owned rules are candidates: table 1076
     * (number or `rt_tables` name) or the Tailscale fwmark. The kernel's
     * default rules are refused unconditionally, so even a garbage snapshot
     * can never turn `lookup local/main/default` into a delete.
     */
    internal fun ruleDeleteArgs(line: String): List<String>? {
        if (line.isEmpty() || line.startsWith("Priority") || !line.contains("lookup")) return null
        val priority = line.substringBefore(':').trim().toIntOrNull() ?: return null
        if (priority in DEFAULT_RULE_PRIORITIES) return null
        if (!isTailscaleRule(line.split(Regex("\\s+")))) return null
        return listOf("rule", "del", "priority", priority.toString())
    }

    private fun isTailscaleRule(tokens: List<String>): Boolean {
        for (keyword in listOf("lookup", "table")) {
            val idx = tokens.indexOf(keyword)
            if (idx < 0) continue
            val table = tokens.getOrNull(idx + 1)
            if (table == TAILSCALE_TABLE || table == TAILSCALE_TABLE_NAME) return true
        }
        val fwmarkIdx = tokens.indexOf("fwmark")
        if (fwmarkIdx >= 0) {
            // `0x80000` or `0x80000/0xff0000` (the selector tail is part of the same token).
            val fwmark = tokens.getOrNull(fwmarkIdx + 1)
            if (fwmark?.substringBefore('/')?.equals(TAILSCALE_FWMARK, ignoreCase = true) == true) {
                return true
            }
        }
        return false
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
                val text = process.inputStream.bufferedReader().readText()
                // [OpenMinis#546] A non-zero exit means `ip` refused (no
                // permission / unsupported syntax); a blank body is equally
                // unusable. Neither may be parsed: they would masquerade as
                // "no rules".
                if (process.exitValue() == 0 && text.isNotBlank()) text else null
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

    /** Test hook: clear the baseline/seams without touching the system. */
    internal fun resetForTest() {
        baseline = null
        lastError = null
        snapshotReaderForTest = null
        deleteRunnerForTest = null
    }

    /**
     * [OpenMinis#546] Test hook: seed a visible error without a real `ip` run,
     * so a JVM test can pin that a baseline-less cleanup RETIRES it instead of
     * leaving the settings row showing a stale failure.
     */
    internal fun seedLastErrorForTest(error: String?) {
        lastError = error
    }
}
