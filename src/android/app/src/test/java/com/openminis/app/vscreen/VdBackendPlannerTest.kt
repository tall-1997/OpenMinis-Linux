package com.openminis.app.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [T-android-vscreen-layers] The layered policy: SU/KSU first, Shizuku second,
 * native always last. A missing or failed layer must never hide the fallback,
 * and a recovered layer must be picked up again without an app restart.
 */
class VdBackendPlannerTest {

    private fun probes(
        root: Boolean = true,
        shizuku: Boolean = true,
        native: Boolean = true,
    ) = listOf(
        VdBackendProbe(VdBackend.ROOT, root, if (root) "su ready" else "no KSU/SU"),
        VdBackendProbe(VdBackend.SHIZUKU, shizuku, if (shizuku) "shizuku ready" else "Shizuku not ready"),
        VdBackendProbe(VdBackend.NATIVE, native, "always available"),
    )

    @Test
    fun `priority is root then shizuku then native`() {
        val plan = VdBackendPlanner().plan(probes(), 0L)
        assertEquals(
            listOf(VdBackend.ROOT, VdBackend.SHIZUKU, VdBackend.NATIVE),
            plan.map { it.backend },
        )
    }

    @Test
    fun `a missing higher layer is skipped with its reason`() {
        val planner = VdBackendPlanner()
        val all = probes(root = false)
        val plan = planner.plan(all, 0L)
        assertEquals(listOf(VdBackend.SHIZUKU, VdBackend.NATIVE), plan.map { it.backend })
        assertEquals("no KSU/SU", all.first { it.backend == VdBackend.ROOT }.detail)
    }

    @Test
    fun `native is always the guaranteed fallback`() {
        val plan = VdBackendPlanner().plan(probes(root = false, shizuku = false), 0L)
        assertEquals(listOf(VdBackend.NATIVE), plan.map { it.backend })
    }

    @Test
    fun `a failed layer cools down and is retried last`() {
        val planner = VdBackendPlanner(cooldownMs = 1_000L)
        planner.noteFailure(VdBackend.ROOT, 0L)

        val cooling = planner.plan(probes(), 500L).map { it.backend }
        assertEquals(listOf(VdBackend.SHIZUKU, VdBackend.NATIVE, VdBackend.ROOT), cooling)

        // Cooldown expiry is the upgrade path: root is first again, untouched.
        assertEquals(VdBackend.ROOT, planner.plan(probes(), 1_500L).first().backend)
    }

    @Test
    fun `a success clears the cooldown`() {
        val planner = VdBackendPlanner(cooldownMs = 10_000L)
        planner.noteFailure(VdBackend.SHIZUKU, 0L)
        planner.noteSuccess(VdBackend.SHIZUKU)
        assertFalse(planner.isCoolingDown(VdBackend.SHIZUKU, 1L))
        assertEquals(VdBackend.ROOT, planner.plan(probes(), 1L).first().backend)
    }

    @Test
    fun `an unavailable layer never appears in the attempt plan`() {
        val all = probes(root = false, shizuku = false)
        val plan = VdBackendPlanner().plan(all, 0L)
        assertEquals(1, plan.size)
        assertEquals(VdBackend.NATIVE, plan.single().backend)
    }
}
