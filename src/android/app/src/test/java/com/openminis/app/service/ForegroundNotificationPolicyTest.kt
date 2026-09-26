package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundNotificationPolicyTest {
    private fun state(
        active: Int = 1,
        tool: String? = null,
        started: Long = 100L,
        finished: Long? = null,
        promoted: Boolean = true,
        subtitle: String = "1 task running",
    ) = ForegroundNotificationState(active, tool, tool != null, started, finished, promoted, subtitle)

    @Test fun initialPostAndIdenticalCallbacksAreIdempotent() {
        val policy = ForegroundNotificationPolicy()
        val snapshot = state()
        assertEquals(ForegroundNotificationPolicy.Decision.Publish, policy.decide(snapshot, 1_000L))
        policy.markPublished(snapshot, 1_000L)
        assertEquals(ForegroundNotificationPolicy.Decision.Skip, policy.decide(snapshot, 1_000L))
        assertEquals(ForegroundNotificationPolicy.Decision.Skip, policy.decide(snapshot, 100_000L))
    }

    @Test fun promotedToolChangesNeverRebuildChip() {
        val policy = ForegroundNotificationPolicy()
        val first = state(tool = "shell_execute").stableForPromotion()
        val browser = state(tool = "browser_use").stableForPromotion()
        assertEquals(first, browser)
        policy.markPublished(first, 1_000L)
        assertEquals(ForegroundNotificationPolicy.Decision.Skip, policy.decide(browser, 60_000L))
    }

    @Test fun deferredOrdinaryToolChangeIsDiscardedWhenStateReturnsToPublished() {
        val policy = ForegroundNotificationPolicy()
        val first = state(tool = "shell_execute", promoted = false)
        val browser = first.copy(toolName = "browser_use")
        policy.markPublished(first, 1_000L)
        assertEquals(ForegroundNotificationPolicy.Decision.Wait(4_000L), policy.decide(browser, 2_000L))
        assertEquals(ForegroundNotificationPolicy.Decision.Skip, policy.decide(first, 60_000L))
        assertEquals(ForegroundNotificationPolicy.Decision.Publish, policy.decide(browser, 60_000L))
    }

    @Test fun ordinaryNotificationHasShorterCoalescingWindow() {
        val policy = ForegroundNotificationPolicy()
        val initial = state(promoted = false)
        policy.markPublished(initial, 100L)
        assertEquals(ForegroundNotificationPolicy.Decision.Wait(4_000L), policy.decide(initial.copy(toolName = "file_read"), 1_100L))
        assertEquals(ForegroundNotificationPolicy.Decision.Publish, policy.decide(initial.copy(toolName = "file_read"), 5_100L))
    }

    @Test fun completionNewRunAndPromotionChangesAreImmediate() {
        val policy = ForegroundNotificationPolicy()
        val initial = state()
        policy.markPublished(initial, 10_000L)
        val complete = state(active = 0, finished = 8_000L, promoted = false)
        assertEquals(ForegroundNotificationPolicy.Decision.Publish, policy.decide(complete, 10_100L))
        policy.markPublished(complete, 10_100L)
        assertEquals(ForegroundNotificationPolicy.Decision.Publish, policy.decide(state(started = 10_200L), 10_200L))
        policy.markPublished(initial, 10_200L)
        assertEquals(ForegroundNotificationPolicy.Decision.Publish, policy.decide(initial.copy(promoted = false), 10_201L))
    }

    @Test fun failedDeliveryDoesNotPoisonLedger() {
        val policy = ForegroundNotificationPolicy()
        val next = state(tool = "browser_use")
        assertEquals(ForegroundNotificationPolicy.Decision.Publish, policy.decide(next, 0L))
        assertEquals(ForegroundNotificationPolicy.Decision.Publish, policy.decide(next, 50_000L))
    }
}
