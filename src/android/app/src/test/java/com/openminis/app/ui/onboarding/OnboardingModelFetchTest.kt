package com.openminis.app.ui.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-onboarding-model-fetch-fallback] Onboarding's "Select Models" page spun
 * forever when the provider's model list could not be fetched. The page now
 * runs its own bounded fetch and, when it ends with the page still empty,
 * shows the provider's error, Retry and Add Model Manually. iOS parity:
 * 7d17d29c4 / OnboardingModelFetchTests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingModelFetchTest {

    @Test
    fun `fast providers finish with their errors and no timeout`() = runTest {
        val r = OnboardingModelFetch.run(listOf("a", "b")) { if (it == "b") "401 Unauthorized" else null }
        assertFalse(r.timedOut)
        assertEquals(listOf("401 Unauthorized"), r.errors)
    }

    @Test
    fun `a slow provider times out at the page budget, and is cancelled`() = runTest {
        var cancelled = false
        val r = OnboardingModelFetch.run(listOf("slow")) {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        assertTrue(r.timedOut)
        assertEquals(OnboardingModelFetch.TIMEOUT_MS, currentTime)
        assertTrue("the loser observes cancellation", cancelled)
    }

    @Test
    fun `providers run in parallel within one budget`() = runTest {
        val r = OnboardingModelFetch.run(listOf(1, 2, 3)) { delay(15_000); null }
        assertFalse(r.timedOut)
        assertEquals("three 15 s fetches take 15 s, not 45", 15_000L, currentTime)
    }

    @Test
    fun `a thrown error is reported as its message and does not sink the others`() = runTest {
        val r = OnboardingModelFetch.run(listOf("boom", "ok")) {
            if (it == "boom") throw IllegalStateException("connect timed out") else "404 Not Found"
        }
        assertFalse(r.timedOut)
        assertEquals(setOf("connect timed out", "404 Not Found"), r.errors.toSet())
    }

    @Test
    fun `the budget is within the 15-20 s spec`() {
        assertTrue(OnboardingModelFetch.TIMEOUT_MS in 15_000L..20_000L)
    }

    // Wiring tests trimmed: the upstream file asserts its selection screen runs
    // `triggerAsyncModelReconcile` and that refreshModels exposes
    // `onVendorError`. This fork has neither API — the bounded fetch and its
    // Retry/failure panel are wired in OnboardingModelSelectionScreen, but the
    // vendor-error plumbing is a separate change. Asserting symbols this build
    // does not have would fail for the gap, not for the behaviour above.
}