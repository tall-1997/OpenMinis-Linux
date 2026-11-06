// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import com.openminis.app.logging.AppLogger
import java.net.Inet6Address
import java.net.NetworkInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * Feeds the OS network state to the embedded Tailscale node: the Android
 * counterpart of the NWPathMonitor in iOS `TailscaleNode.startPathMonitor`.
 *
 * Tailscale's network monitor cannot read it itself on Android: since API 30
 * an app may not bind a NETLINK_ROUTE socket, so Go's `net.Interfaces()`
 * fails, and tailscale's Android build only polls every 10 minutes. On every
 * default-network callback this pushes
 *  - the default interface name (LinkProperties.interfaceName, "" when lost),
 *  - the interface list (java.net.NetworkInterface, which Android permits),
 * then wakes the monitor. See deps/rclone-mobile/tsnet/netmon_app.go.
 *
 * Started with the node and never stopped: one default-network callback is
 * cheap, and a node restarted later finds the state already current.
 */
object TailscalePathMonitor {

    private const val TAG = "Network"

    @Volatile
    private var started = false

    @Volatile
    private var lastInterface: String? = null

    @Synchronized
    fun start(context: Context) {
        if (started) return
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: run {
            AppLogger.warning(TAG, "[Net][TS] ConnectivityManager unavailable; netmon keeps its own view")
            return
        }
        // Seed before the first callback, which can lag on a cold start.
        val active = cm.activeNetwork
        update(active?.let { cm.getLinkProperties(it) }?.interfaceName.orEmpty(), "initial")
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                update(linkProperties.interfaceName.orEmpty(), "linkProperties")
            }

            override fun onLost(network: Network) {
                update("", "lost")
            }
        })
        started = true
    }

    private fun update(ifName: String, reason: String) {
        val pushed = runCatching {
            val r = JSONObject(GomobileNet.tsSetInterfaces(interfacesJson().toString()))
            if (!r.optBoolean("ok")) r.optString("error") else null
        }.getOrElse { it.message }
        GomobileNet.tsSetDefaultRouteInterface(ifName)
        GomobileNet.tsNetworkChanged()
        val changed = ifName != lastInterface
        lastInterface = ifName
        if (changed || pushed != null) {
            AppLogger.info(TAG, "[Net][TS] default interface → ${ifName.ifEmpty { "none" }} ($reason)" +
                (pushed?.let { "; interface list not updated: $it" } ?: ""))
        }
    }

    /** JSON array of tsnet.AppInterface: name, index, mtu, flags, "ip/len" addresses. */
    internal fun interfacesJson(): JSONArray {
        val out = JSONArray()
        val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (ni in all) {
            val addrs = JSONArray()
            for (ia in ni.interfaceAddresses) {
                val a = ia.address ?: continue
                // Strip the "%wlan0" scope suffix; Go's netip rejects zones in prefixes.
                val host = (a.hostAddress ?: continue).substringBefore('%')
                val len = ia.networkPrefixLength.toInt().takeIf { it >= 0 } ?: if (a is Inet6Address) 128 else 32
                addrs.put("$host/$len")
            }
            out.put(JSONObject().apply {
                put("name", ni.name)
                put("index", ni.index)
                put("mtu", runCatching { ni.mtu }.getOrDefault(0))
                put("up", runCatching { ni.isUp }.getOrDefault(false))
                put("loopback", runCatching { ni.isLoopback }.getOrDefault(false))
                put("p2p", runCatching { ni.isPointToPoint }.getOrDefault(false))
                put("multicast", runCatching { ni.supportsMulticast() }.getOrDefault(false))
                put("broadcast", ni.interfaceAddresses.any { it.broadcast != null })
                put("addrs", addrs)
            })
        }
        return out
    }
}
