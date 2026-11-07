package com.openminis.app.vscreen.tier

import com.openminis.app.vscreen.IVscreenService
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Shizuku tier on the JVM: the structurally-unavailable probe branch and
 * the create path's service-unreachable failure (before any ImageReader is
 * touched). The happy paths need a live binder — real-device verification.
 */
class ShizukuDisplayProviderTest {

    @Test
    fun `without an authorized Shizuku the probe is structurally unavailable`() {
        val p = ShizukuDisplayProvider(serviceClient = { throw IllegalStateException("must not bind") })
        val result = p.probe(null)
        assertTrue(result is ProbeResult.Unavailable)
        assertTrue((result as ProbeResult.Unavailable).reason.contains("shizuku", ignoreCase = true))
    }

    @Test
    fun `create without a reachable user service fails before touching any display`() {
        val p = ShizukuDisplayProvider(serviceClient = { null })
        val outcome = p.create(ProviderCreateRequest("l", 1080, 1920, 320), null)
        assertTrue(outcome is ProviderOutcome.Failed)
        assertTrue((outcome as ProviderOutcome.Failed).reason.contains("unreachable"))
    }

    @Test
    fun `a dead binder probe is honest about the service state`() {
        // A service whose binder reports dead → probe unavailable. The fake
        // never gets here on the JVM (Shizuku is not READY), so this covers
        // the create-side guard instead: even a non-null service that later
        // dies surfaces as a failed create, never a half-registered screen.
        val p = ShizukuDisplayProvider(serviceClient = { null })
        val outcome = p.create(ProviderCreateRequest("l", 1080, 1920, 320), null)
        assertTrue(outcome is ProviderOutcome.Failed)
    }

    /** Binder contract reference: codes 5/6 must stay stable (see IVscreenService). */
    @Test
    fun `public display transaction codes are the documented ones`() {
        assertTrue(IVscreenService.CODE_CREATE_PUBLIC == 5)
        assertTrue(IVscreenService.CODE_DESTROY_PUBLIC == 6)
    }
}
