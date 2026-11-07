package com.openminis.app.vscreen.tier

import com.openminis.app.vscreen.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probe bookkeeping: first-contact probing, the degraded backoff, and
 * fingerprint invalidation. All on MemoryPrefs.
 */
class TierStateMachineTest {

    private class FakeProvider(tier: VscreenTier, private val result: ProbeResult) : DisplayProvider {
        var probes = 0
        override val tier: VscreenTier = tier
        override fun probe(context: android.content.Context?): ProbeResult {
            probes++
            return result
        }
        override fun create(request: ProviderCreateRequest, context: android.content.Context?) =
            ProviderOutcome.Failed("unused")
        override fun destroy(displayId: Int) { }
    }

    private fun machine(
        prefs: MemoryPrefs = MemoryPrefs(),
        nowMs: () -> Long = { 1_000L },
        fingerprint: () -> String = { "fp0" },
        vararg providers: DisplayProvider,
    ): TierStateMachine = TierStateMachine(providers.associateBy { it.tier }, prefs, nowMs, fingerprint)

    @Test
    fun `first contact probes every tier exactly once and caches the result`() {
        val root = FakeProvider(VscreenTier.ROOT, ProbeResult.Unavailable("no su"))
        val native = FakeProvider(
            VscreenTier.NATIVE,
            ProbeResult.Available(setOf(VscreenCapability.SCREENSHOT_READER)),
        )
        val sm = machine(providers = *arrayOf(root, native))
        val sheet1 = sm.sheet(null)
        assertEquals(1, root.probes)
        assertEquals(1, native.probes)
        assertTrue(sheet1[VscreenTier.ROOT] is ProbeResult.Unavailable)
        assertTrue(sheet1[VscreenTier.NATIVE] is ProbeResult.Available)
        // Second read is served from cache — no new probes.
        sm.sheet(null)
        assertEquals(1, root.probes)
        assertEquals(1, native.probes)
    }

    @Test
    fun `the sheet persists across instances like a process restart`() {
        val prefs = MemoryPrefs()
        val native = FakeProvider(VscreenTier.NATIVE, ProbeResult.Available(emptySet()))
        val sm = machine(prefs = prefs, providers = *arrayOf(native))
        sm.sheet(null)
        // A fresh machine over the SAME prefs restores the sheet (no probe).
        val fresh = FakeProvider(VscreenTier.NATIVE, ProbeResult.Unavailable("should not be called"))
        val restored = machine(prefs = prefs, providers = *arrayOf(fresh))
        assertTrue(restored.sheet(null)[VscreenTier.NATIVE] is ProbeResult.Available)
        assertEquals("restored from prefs, not re-probed", 0, fresh.probes)
    }

    @Test
    fun `markDegraded persists and is readable from a fresh instance`() {
        val prefs = MemoryPrefs()
        val native = FakeProvider(VscreenTier.NATIVE, ProbeResult.Available(emptySet()))
        val sm = machine(prefs = prefs, providers = *arrayOf(native))
        sm.markDegraded(VscreenTier.NATIVE, "ROM refused the display")
        val fresh = FakeProvider(VscreenTier.NATIVE, ProbeResult.Available(emptySet()))
        val restored = machine(prefs = prefs, providers = *arrayOf(fresh))
        val entry = restored.sheet(null)[VscreenTier.NATIVE]
        assertTrue(entry is ProbeResult.Degraded && entry.reason.contains("ROM refused"))
    }

    @Test
    fun `an expired degraded tier gets exactly one reprobe`() {
        var now = 1_000L
        val native = FakeProvider(VscreenTier.NATIVE, ProbeResult.Available(emptySet()))
        val sm = machine(nowMs = { now }, providers = *arrayOf(native))
        // Seed the degraded entry stamped at the base clock, so the window
        // arithmetic is exact.
        sm.put(VscreenTier.NATIVE, ProbeResult.Degraded("old", 1_000))
        native.probes = 0

        now = 1_000L + TierStateMachine.BACKOFF_MS - 1
        sm.sheet(null)
        assertEquals("inside the backoff window: no reprobe", 0, native.probes)

        now = 1_000L + TierStateMachine.BACKOFF_MS
        sm.sheet(null)
        assertEquals("past the window: one reprobe", 1, native.probes)
    }

    @Test
    fun `a fingerprint change invalidates non-degraded entries`() {
        var fp = "fp0"
        val native = FakeProvider(VscreenTier.NATIVE, ProbeResult.Available(emptySet()))
        val sm = machine(fingerprint = { fp }, providers = *arrayOf(native))
        sm.sheet(null)
        assertEquals(1, native.probes)
        fp = "fp1"
        sm.sheet(null)
        assertEquals("invalidated by the fingerprint change", 2, native.probes)
    }
}
