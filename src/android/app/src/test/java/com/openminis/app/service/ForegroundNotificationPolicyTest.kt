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
        val first = state(tool = "shell_execute")
            .copy(surface = NotificationSurface.PROMOTED)
            .normalized()
        val browser = state(tool = "browser_use")
            .copy(surface = NotificationSurface.PROMOTED)
            .normalized()
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

    @Test fun oemQuietRowDoesNotRepublishForToolCountOrSubtitle() {
        val policy = ForegroundNotificationPolicy()
        val running = state(tool = "shell_execute", subtitle = "1 task")
            .copy(surface = NotificationSurface.OEM_QUIET)
            .normalized()
        val later = state(active = 4, tool = "browser_use", subtitle = "4 tasks")
            .copy(surface = NotificationSurface.OEM_QUIET)
            .normalized()
        assertEquals(running, later)
        policy.markPublished(running, 1_000L)
        assertEquals(ForegroundNotificationPolicy.Decision.Skip, policy.decide(later, 3_600_000L))
    }

    @Test fun oemQuietStillPublishesRunStartAndCompletion() {
        val policy = ForegroundNotificationPolicy()
        val running = state().copy(surface = NotificationSurface.OEM_QUIET).normalized()
        policy.markPublished(running, 1_000L)
        val done = state(active = 0, finished = 9_000L, promoted = false)
            .copy(surface = NotificationSurface.OEM_QUIET)
            .normalized()
        assertEquals(ForegroundNotificationPolicy.Decision.Publish, policy.decide(done, 9_100L))
        policy.markPublished(done, 9_100L)
        val nextRun = state(started = 20_000L).copy(surface = NotificationSurface.OEM_QUIET).normalized()
        assertEquals(ForegroundNotificationPolicy.Decision.Publish, policy.decide(nextRun, 20_000L))
    }

    // [T-android-hyperos-island] The HyperOS focus surface: the tool label
    // IS the island's big-island text, so it must survive normalization
    // (unlike PROMOTED); only the shade-row-only isToolRunning flag strips.

    @Test fun hyperOsFocusKeepsToolNameForIslandText() {
        val shell = state(tool = "shell_execute")
            .copy(surface = NotificationSurface.HYPER_OS_FOCUS)
            .normalized()
        val browser = state(tool = "browser_use")
            .copy(surface = NotificationSurface.HYPER_OS_FOCUS)
            .normalized()
        assertEquals("shell_execute", shell.toolName)
        assertEquals("browser_use", browser.toolName)
        assertEquals(false, shell.isToolRunning)
        org.junit.Assert.assertNotEquals(shell, browser)
    }

    @Test fun hyperOsFocusToolChangeUsesTheLongCoalescingWindow() {
        val policy = ForegroundNotificationPolicy()
        val first = state(tool = "shell_execute")
            .copy(surface = NotificationSurface.HYPER_OS_FOCUS)
            .normalized()
        policy.markPublished(first, 1_000L)
        val browser = state(tool = "browser_use")
            .copy(surface = NotificationSurface.HYPER_OS_FOCUS)
            .normalized()
        // 20s gap (promoted flag drives it), not the 5s plain gap: island
        // updates are re-binds on forked SystemUI, so multi-tool churn must
        // not spam them.
        assertEquals(ForegroundNotificationPolicy.Decision.Wait(18_000L), policy.decide(browser, 3_000L))
        assertEquals(ForegroundNotificationPolicy.Decision.Publish, policy.decide(browser, 21_000L))
    }

    @Test fun hyperOsFocusRunBoundariesAreImmediate() {
        val policy = ForegroundNotificationPolicy()
        val running = state(tool = "shell_execute")
            .copy(surface = NotificationSurface.HYPER_OS_FOCUS)
            .normalized()
        policy.markPublished(running, 1_000L)
        val done = state(active = 0, finished = 900L, tool = null)
            .copy(surface = NotificationSurface.HYPER_OS_FOCUS)
            .normalized()
        assertEquals(ForegroundNotificationPolicy.Decision.Publish, policy.decide(done, 1_100L))
        policy.markPublished(done, 1_100L)
        val nextRun = state(started = 2_000L)
            .copy(surface = NotificationSurface.HYPER_OS_FOCUS)
            .normalized()
        assertEquals(ForegroundNotificationPolicy.Decision.Publish, policy.decide(nextRun, 2_000L))
    }
}
