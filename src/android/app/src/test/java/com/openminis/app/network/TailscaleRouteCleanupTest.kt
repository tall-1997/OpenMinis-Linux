package com.openminis.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [OpenMinis#546] Parsing rules for the Tailscale stop-time policy-routing
 * cleanup. The diff/delete safety property is "only tun-bound entries are ever
 * candidates", so both the accepted shape and the ignored ones are pinned.
 */
class TailscaleRouteCleanupTest {

    @Test
    fun `policy rule deletes by priority`() {
        assertEquals(
            listOf("rule", "del", "priority", "32765"),
            TailscaleRouteCleanup.ruleDeleteArgs("32765: from all fwmark 0x80000 lookup 1076"),
        )
        assertEquals(
            listOf("rule", "del", "priority", "1000"),
            TailscaleRouteCleanup.ruleDeleteArgs("1000: from all lookup 1076"),
        )
    }

    @Test
    fun `non-rule lines are ignored`() {
        assertNull(TailscaleRouteCleanup.ruleDeleteArgs(""))
        assertNull(TailscaleRouteCleanup.ruleDeleteArgs("Priority Protocol"))
        assertNull(TailscaleRouteCleanup.ruleDeleteArgs("not a rule"))
    }

    @Test
    fun `tun route carries destination device and table`() {
        assertEquals(
            listOf("route", "del", "default", "dev", "tun0", "table", "1076"),
            TailscaleRouteCleanup.routeDeleteArgs(
                "default dev tun0 table 1076 proto static scope link",
            ),
        )
        assertEquals(
            listOf("route", "del", "fd7a:115c:a1e0::/48", "dev", "tun1"),
            TailscaleRouteCleanup.routeDeleteArgs("fd7a:115c:a1e0::/48 dev tun1"),
        )
    }

    @Test
    fun `non-tun routes are never cleanup candidates`() {
        assertNull(TailscaleRouteCleanup.routeDeleteArgs("default via 192.168.1.1 dev wlan0"))
        assertNull(TailscaleRouteCleanup.routeDeleteArgs("10.0.0.0/8 dev rmnet0 table 1002"))
        assertNull(TailscaleRouteCleanup.routeDeleteArgs("default dev ppp0"))
        assertNull(TailscaleRouteCleanup.routeDeleteArgs(""))
    }
}
