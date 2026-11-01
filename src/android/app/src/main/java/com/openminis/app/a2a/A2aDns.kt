package com.openminis.app.a2a

import android.content.Context
import android.net.ConnectivityManager
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * [T-a2a-glibc] Feeds the host's live DNS into the glibc plugin runtime.
 *
 * The bundled glibc has its compile-time `/etc` paths retargeted into
 * `<home>/data/` (`resolv.conf` / `hosts` / `nsswitch.conf` — see
 * `scripts/a2a-glibc/glibc-2.39-android-paths.patch`). Android has no
 * `/etc` and no `resolv.conf` of its own, but it does know the live
 * servers: the active network's `LinkProperties` carries the DNS addresses
 * the DHCP/RA handshake delivered. [refresh] mirrors them into the file
 * the plugin processes' libc actually reads, so plugin DNS follows the
 * phone's network switches (Wi-Fi → 蜂窝, VPN, captive portals) instead of
 * freezing on whatever was true at install time.
 *
 * [refresh] is a no-op when the network reports no servers: keeping the
 * last-known file beats writing an empty one (glibc would then fail every
 * lookup until the next refresh). [ensureStatic] seeds the two files that
 * never change (`hosts` with the loopback line, `nsswitch.conf` with
 * `hosts: files dns`) — called by install and, idempotently, before every
 * start.
 */
object A2aDns {
    private const val TAG = "A2aDns"

    /** File names inside `<home>/data/` — the glibc compile-time paths. */
    internal const val RESOLV_CONF = "resolv.conf"
    internal const val HOSTS = "hosts"
    internal const val NSSWITCH = "nsswitch.conf"

    /**
     * Mirror the active network's DNS servers into
     * `<home>/data/resolv.conf`. No servers reported → keep the existing
     * file untouched. Blocking is negligible (a binder query) but call off
     * the main thread anyway — the callers already do.
     */
    fun refresh(context: Context) {
        val home = A2aGatewayProcess.homeDir(context.filesDir.path)
        val data = File(home, "data")
        if (!data.isDirectory) return
        val servers = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.activeNetwork?.let { cm.getLinkProperties(it)?.dnsServers }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "cannot read active network DNS: ${t.message}")
            null
        } ?: return
        val text = resolvConfText(servers.map { it.hostAddress }) ?: return
        writeAtomically(File(data, RESOLV_CONF), text)
    }

    /**
     * Seed `hosts` and `nsswitch.conf` when missing. Idempotent — existing
     * files (or a user's manual edits) are never overwritten.
     */
    fun ensureStatic(context: Context) {
        val data = File(A2aGatewayProcess.homeDir(context.filesDir.path), "data")
        if (!data.isDirectory) return
        val hosts = File(data, HOSTS)
        if (!hosts.isFile) writeAtomically(hosts, staticHostsText())
        val nsswitch = File(data, NSSWITCH)
        if (!nsswitch.isFile) writeAtomically(nsswitch, staticNsswitchText())
    }

    /**
     * resolv.conf content for the given servers; null for an empty list.
     * Pure — tests pin the exact format glibc parses.
     */
    internal fun resolvConfText(servers: List<String>): String? {
        if (servers.isEmpty()) return null
        return buildString {
            appendLine("# written by com.openminis.app a2a (do not edit — rewritten on gateway start)")
            for (server in servers) appendLine("nameserver $server")
        }
    }

    /** Static hosts content: the loopback line every resolver wants. */
    internal fun staticHostsText(): String =
        "# static hosts for the a2a glibc runtime\n127.0.0.1 localhost\n" +
            "::1 localhost ip6-localhost\n"

    /**
     * nsswitch.conf: files first (the bundled `hosts`), then DNS. No passwd
     * / group / shadow sections — the plugin processes run as the app uid
     * and never query those databases.
     */
    internal fun staticNsswitchText(): String =
        "# nsswitch.conf for the a2a glibc runtime\nhosts: files dns\n"

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.path + ".tmp")
        try {
            tmp.parentFile?.mkdirs()
            tmp.writeText(text)
            if (!tmp.renameTo(target)) tmp.delete()
        } catch (t: Throwable) {
            tmp.delete()
            AppLogger.warning(TAG, "cannot write ${target.path}: ${t.message}")
        }
    }
}
