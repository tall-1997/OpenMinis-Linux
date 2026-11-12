package com.openminis.app.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [OpenMinis#546] Parsing rules for the Tailscale stop-time policy-routing
 * cleanup. The diff/delete safety property is "only tun-bound entries are ever
 * candidates", so both the accepted shape and the ignored ones are pinned.
 *
 * [OpenMinis#546] The second half pins the FAILURE policy, which is where the
 * field bug lived: an unreadable/empty `ip` snapshot must never become a
 * baseline (an empty baseline would mark every pre-existing rule/route as
 * "added" and delete the user's other VPN), and a failed stop-time read must
 * keep the baseline so a later stop retries instead of disarming cleanup.
 */
class TailscaleRouteCleanupTest {

    private val deletedArgs = mutableListOf<List<String>>()

    @Before
    fun setUp() {
        TailscaleRouteCleanup.resetForTest()
        deletedArgs.clear()
        // Observable, root-free delete runner shared by the diff tests.
        TailscaleRouteCleanup.deleteRunnerForTest = { args ->
            deletedArgs.add(args)
            true
        }
    }

    @After
    fun tearDown() {
        TailscaleRouteCleanup.resetForTest()
    }

    // ── parsing ────────────────────────────────────────────────────────────

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
    fun `tailscale fwmark is accepted even when the table is not 1076`() {
        assertEquals(
            listOf("rule", "del", "priority", "5210"),
            TailscaleRouteCleanup.ruleDeleteArgs(
                "5210: from all fwmark 0x80000/0xff0000 lookup 52",
            ),
        )
    }

    @Test
    fun `non-rule lines are ignored`() {
        assertNull(TailscaleRouteCleanup.ruleDeleteArgs(""))
        assertNull(TailscaleRouteCleanup.ruleDeleteArgs("Priority Protocol"))
        assertNull(TailscaleRouteCleanup.ruleDeleteArgs("not a rule"))
    }

    @Test
    fun `rules outside tailscale table and fwmark are never candidates`() {
        // [OpenMinis#546] Any-`lookup` matching used to make these deletable.
        assertNull(TailscaleRouteCleanup.ruleDeleteArgs("1000: from all lookup 1002"))
        assertNull(TailscaleRouteCleanup.ruleDeleteArgs("5000: from all fwmark 0x20000 lookup 1002"))
    }

    @Test
    fun `kernel default rules are protected`() {
        // [OpenMinis#546] The defaults are priority 0/32766/32767; deleting one
        // breaks all connectivity, and an empty baseline used to expose them.
        for (line in listOf(
            "0: from all lookup local",
            "32766: from all lookup main",
            "32767: from all lookup default",
        )) {
            assertNull("must protect $line", TailscaleRouteCleanup.ruleDeleteArgs(line))
        }
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

    @Test
    fun `rule readability is tracked on the raw output even when nothing is deletable`() {
        // [OpenMinis#546] The kernel defaults are not candidates, but their
        // presence PROVES `ip rule show` was readable. A baseline must still be
        // armed in that state — otherwise every normal device (no
        // tailscale-table rule yet) would silently skip cleanup.
        val readable = TailscaleRouteCleanup.snapshotOf(
            "0: from all lookup local\n32766: from all lookup main",
            "default via 192.168.1.1 dev wlan0",
        )
        assertTrue(readable.hasRules)
        assertTrue("no deletable candidates yet", readable.rules.isEmpty())

        val unreadable = TailscaleRouteCleanup.snapshotOf("", "")
        assertFalse(unreadable.hasRules)
    }

    // ── failure policy ─────────────────────────────────────────────────────

    @Test
    fun `an empty ip snapshot never becomes a baseline`() {
        TailscaleRouteCleanup.snapshotReaderForTest = {
            TailscaleRouteCleanup.Snapshot(emptyList(), emptyList())
        }
        TailscaleRouteCleanup.captureBaseline()
        assertFalse("an empty snapshot must not arm the diff", TailscaleRouteCleanup.hasBaseline)

        assertNull(TailscaleRouteCleanup.cleanupAfterStop("no baseline"))
        assertTrue("nothing may be deleted without a baseline", deletedArgs.isEmpty())
    }

    @Test
    fun `an unreadable ip snapshot never becomes a baseline`() {
        TailscaleRouteCleanup.snapshotReaderForTest = { null }
        TailscaleRouteCleanup.captureBaseline()
        assertFalse(TailscaleRouteCleanup.hasBaseline)

        assertNull(TailscaleRouteCleanup.cleanupAfterStop("unreadable"))
        assertTrue(deletedArgs.isEmpty())
    }

    @Test
    fun `a baseline-less cleanup retires a stale visible error`() {
        // [OpenMinis#546] #546 residual: with no baseline there is no diff to run,
        // so a leftover message must not keep being appended to NetworkHub's
        // visible error row on every apply that never attempted cleanup.
        TailscaleRouteCleanup.seedLastErrorForTest("stale: could not remove 2 entries")
        assertEquals("stale: could not remove 2 entries", TailscaleRouteCleanup.visibleError())
        assertFalse(TailscaleRouteCleanup.hasBaseline)

        assertNull(TailscaleRouteCleanup.cleanupAfterStop("no baseline"))
        assertNull("no baseline means no cleanup obligation", TailscaleRouteCleanup.visibleError())
        assertTrue(deletedArgs.isEmpty())
    }

    @Test
    fun `a failed stop-time read keeps the baseline`() {
        val baseline = snapshot("1000: from all lookup 1076")
        var read: () -> TailscaleRouteCleanup.Snapshot? = { baseline }
        TailscaleRouteCleanup.snapshotReaderForTest = { read() }

        TailscaleRouteCleanup.captureBaseline()
        assertTrue(TailscaleRouteCleanup.hasBaseline)

        // Stop-time read fails: report it, delete nothing, but keep the diff armed.
        read = { null }
        val error = TailscaleRouteCleanup.cleanupAfterStop("node stop")
        assertNotNull("a failed read must be visible", error)
        assertTrue("baseline must survive a failed read", TailscaleRouteCleanup.hasBaseline)
        assertTrue(deletedArgs.isEmpty())

        // Next stop succeeds and retries the same diff.
        read = { snapshot("1000: from all lookup 1076\n2000: from all lookup 1076") }
        assertNull(TailscaleRouteCleanup.cleanupAfterStop("retry"))
        assertEquals(
            listOf(listOf("rule", "del", "priority", "2000")),
            deletedArgs,
        )
        assertFalse("a completed diff retires the baseline", TailscaleRouteCleanup.hasBaseline)
    }

    @Test
    fun `an empty stop-time snapshot also keeps the baseline`() {
        TailscaleRouteCleanup.snapshotReaderForTest = { snapshot("1000: from all lookup 1076") }
        TailscaleRouteCleanup.captureBaseline()

        TailscaleRouteCleanup.snapshotReaderForTest = {
            TailscaleRouteCleanup.Snapshot(emptyList(), emptyList())
        }
        assertNotNull(TailscaleRouteCleanup.cleanupAfterStop("empty read"))
        assertTrue(TailscaleRouteCleanup.hasBaseline)
        assertTrue(deletedArgs.isEmpty())
    }

    @Test
    fun `a failed deletion keeps the baseline for a retry`() {
        TailscaleRouteCleanup.snapshotReaderForTest = { snapshot("1000: from all lookup 1076") }
        TailscaleRouteCleanup.captureBaseline()

        TailscaleRouteCleanup.snapshotReaderForTest = {
            snapshot("1000: from all lookup 1076\n2000: from all lookup 1076")
        }
        TailscaleRouteCleanup.deleteRunnerForTest = { false }
        assertNotNull(TailscaleRouteCleanup.cleanupAfterStop("no root"))
        assertTrue(TailscaleRouteCleanup.hasBaseline)

        // Restore the recorder and retry: the same entry is attempted again.
        TailscaleRouteCleanup.deleteRunnerForTest = { args ->
            deletedArgs.add(args)
            true
        }
        assertNull(TailscaleRouteCleanup.cleanupAfterStop("retry"))
        assertEquals(
            listOf(listOf("rule", "del", "priority", "2000")),
            deletedArgs,
        )
        assertFalse(TailscaleRouteCleanup.hasBaseline)
    }

    @Test
    fun `only entries added during the run are deleted`() {
        // Pre-existing state: the kernel defaults, another VPN's rule, and a
        // stale tun route left by an earlier tailscaled run. NONE may be touched.
        TailscaleRouteCleanup.snapshotReaderForTest = {
            snapshot(
                rules = """
                    0: from all lookup local
                    32766: from all lookup main
                    32767: from all lookup default
                    1500: from all lookup 2500
                    32765: from all fwmark 0x80000 lookup 1076
                """.trimIndent(),
                routes = """
                    default dev tun9 table 1076 proto static scope link
                    default via 192.168.1.1 dev wlan0 table 1002
                """.trimIndent(),
            )
        }
        TailscaleRouteCleanup.captureBaseline()
        assertTrue(TailscaleRouteCleanup.hasBaseline)

        TailscaleRouteCleanup.snapshotReaderForTest = {
            snapshot(
                rules = """
                    0: from all lookup local
                    32766: from all lookup main
                    32767: from all lookup default
                    1500: from all lookup 2500
                    32765: from all fwmark 0x80000 lookup 1076
                    9000: from all lookup 1076
                """.trimIndent(),
                routes = """
                    default dev tun9 table 1076 proto static scope link
                    default via 192.168.1.1 dev wlan0 table 1002
                    100.64.0.0/10 dev tun0 table 1076 scope link
                """.trimIndent(),
            )
        }

        assertNull(TailscaleRouteCleanup.cleanupAfterStop("node stop"))
        assertEquals(
            listOf(
                listOf("rule", "del", "priority", "9000"),
                listOf("route", "del", "100.64.0.0/10", "dev", "tun0", "table", "1076"),
            ),
            deletedArgs,
        )
        assertFalse(TailscaleRouteCleanup.hasBaseline)
    }

    private fun snapshot(rules: String, routes: String = ""): TailscaleRouteCleanup.Snapshot =
        TailscaleRouteCleanup.snapshotOf(rules, routes)
}
