package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RootfsManager.composeResolvConf] is the entire DNS-resilience story in one
 * pure function, so its ordering / dedup / cap rules get their own guard.
 *
 * Field background: a guest resolv.conf naming a single unreachable nameserver
 * — the gateway of a Wi-Fi the phone had already left, a dead VPN TUN address —
 * used to fail *every* lookup in the sandbox, because nothing else was in the
 * file to fall back to. Fallbacks existed only for the empty case, which is
 * precisely the case that never needed them.
 */
class ResolvConfCompositionTest {

    private fun nameservers(content: String): List<String> =
        content.lineSequence()
            .filter { it.startsWith("nameserver ") }
            .map { it.removePrefix("nameserver ") }
            .toList()

    @Test
    fun systemServersComeFirstAndFallbacksFillTheRest() {
        val content = RootfsManager.composeResolvConf(null, listOf("192.168.66.1"))
        assertEquals(listOf("192.168.66.1", "223.5.5.5", "8.8.8.8"), nameservers(content))
    }

    @Test
    fun fallbacksArePresentWhenTheSystemReportsServers() {
        // The regression itself: before, a reported-but-dead server meant no
        // fallback line at all, so one unreachable entry took down all lookups.
        for (servers in listOf(listOf("172.19.0.2"), listOf("192.168.1.1", "192.168.0.1"))) {
            val ns = nameservers(RootfsManager.composeResolvConf(null, servers))
            assertTrue("no fallback for $servers: $ns", ns.any { it in RootfsManager.DNS_FALLBACK_SERVERS })
        }
    }

    @Test
    fun noSystemServersYieldsFallbacksOnly() {
        assertEquals(RootfsManager.DNS_FALLBACK_SERVERS, nameservers(RootfsManager.composeResolvConf(null, emptyList())))
    }

    @Test
    fun twoSystemServersStillLeaveRoomForAFallback() {
        val ns = nameservers(RootfsManager.composeResolvConf(null, listOf("192.168.1.1", "192.168.0.1")))
        assertEquals(3, ns.size)
        assertTrue(ns.last() in RootfsManager.DNS_FALLBACK_SERVERS)
    }

    @Test
    fun neverExceedsTheResolvConfNameserverCap() {
        // resolv.conf's contract is three nameservers; a fourth line is
        // silently ignored by musl and glibc alike.
        val many = listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4")
        val ns = nameservers(RootfsManager.composeResolvConf(null, many))
        assertEquals(RootfsManager.MAX_NAMESERVERS, ns.size)
        assertEquals(many.take(3), ns)
    }

    @Test
    fun aSystemServerThatIsAlreadyAFallbackIsNotDuplicated() {
        val ns = nameservers(RootfsManager.composeResolvConf(null, listOf("223.5.5.5")))
        assertEquals(listOf("223.5.5.5", "8.8.8.8"), ns)
    }

    @Test
    fun blankEntriesAreIgnored() {
        val ns = nameservers(RootfsManager.composeResolvConf(null, listOf("", "  ", "1.1.1.1")))
        assertEquals(listOf("1.1.1.1", "223.5.5.5", "8.8.8.8"), ns)
    }

    @Test
    fun searchDomainIsEmittedTrimmedAndOmittedWhenAbsent() {
        assertTrue(
            RootfsManager.composeResolvConf(" lan.example.com ", listOf("192.168.66.1"))
                .startsWith("search lan.example.com\n")
        )
        assertFalse(RootfsManager.composeResolvConf(null, listOf("192.168.66.1")).contains("search"))
        assertFalse(RootfsManager.composeResolvConf("  ", listOf("192.168.66.1")).contains("search"))
    }

    @Test
    fun ipv6SystemServersAreKeptInOrder() {
        val ns = nameservers(RootfsManager.composeResolvConf(null, listOf("172.19.0.2", "fdfe:dcba:9876::2")))
        assertEquals(listOf("172.19.0.2", "fdfe:dcba:9876::2", "223.5.5.5"), ns)
    }
}
