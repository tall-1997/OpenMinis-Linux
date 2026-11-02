package com.openminis.app.ui

import com.openminis.app.data.model.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-routing-strategy-x] Pins the UI-side helpers for the five new
 * scheduling strategies: badge labels shown in picker/group lists, and the
 * tolerant numeric parsing for the settings editors (weights, first-token
 * timeout, quota window).
 *
 * Pure top-level-object functions — no Compose, no Android runtime — so
 * they run as plain JUnit and are replicated by the /tmp driver for the
 * sandbox red/green cycle.
 */
class RoutingStrategyUiTest {

    @Test
    fun `shortBadge keeps FB and LB and covers all five new strategies`() {
        assertEquals("FB", RoutingStrategyUi.shortBadge(RoutingStrategy.fallback))
        assertEquals("LB", RoutingStrategyUi.shortBadge(RoutingStrategy.loadBalance))
        assertEquals("RR", RoutingStrategyUi.shortBadge(RoutingStrategy.roundRobin))
        assertEquals("WT", RoutingStrategyUi.shortBadge(RoutingStrategy.weighted))
        assertEquals("LU", RoutingStrategyUi.shortBadge(RoutingStrategy.leastUsed))
        assertEquals("QW", RoutingStrategyUi.shortBadge(RoutingStrategy.quotaWindow))
        assertEquals("SM", RoutingStrategyUi.shortBadge(RoutingStrategy.smart))
    }

    @Test
    fun `parseWeight clamps junk to one`() {
        assertEquals(1, RoutingStrategyUi.parseWeight(""))
        assertEquals(1, RoutingStrategyUi.parseWeight("abc"))
        assertEquals(1, RoutingStrategyUi.parseWeight("0"))
        assertEquals(1, RoutingStrategyUi.parseWeight("-3"))
        assertEquals(1, RoutingStrategyUi.parseWeight("12.5"))
        assertEquals(7, RoutingStrategyUi.parseWeight(" 7 "))
    }

    @Test
    fun `parseTimeoutMs maps blank and nonpositive to null`() {
        assertNull(RoutingStrategyUi.parseTimeoutMs(""))
        assertNull(RoutingStrategyUi.parseTimeoutMs("   "))
        assertNull(RoutingStrategyUi.parseTimeoutMs("0"))
        assertNull(RoutingStrategyUi.parseTimeoutMs("-10"))
        assertEquals(45_000, RoutingStrategyUi.parseTimeoutMs("45"))
    }

    @Test
    fun `quotaRemainingAfterSave caps at total`() {
        assertEquals(5, RoutingStrategyUi.quotaRemainingAfterSave("9", 5))
        assertEquals(3, RoutingStrategyUi.quotaRemainingAfterSave("3", 5))
        assertEquals(1, RoutingStrategyUi.quotaRemainingAfterSave("junk", 5))
    }

    @Test
    fun `nextQuotaReset is now plus period`() {
        assertEquals(1_000_000L + 3_600_000L, RoutingStrategyUi.nextQuotaReset(1_000_000L, 3_600_000L))
    }
}
