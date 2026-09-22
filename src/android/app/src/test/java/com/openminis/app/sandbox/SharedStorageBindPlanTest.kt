package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedStorageBindPlanTest {
    @Test
    fun blankHostBindsNothing() {
        assertTrue(SharedStorageBindPlan.plan(null, userNamedSdcard = false).isEmpty())
        assertTrue(SharedStorageBindPlan.plan("  ", userNamedSdcard = false).isEmpty())
    }

    @Test
    fun grantedHostBindsAbsolutePathsAndMountAlias() {
        val plan = SharedStorageBindPlan.plan("/storage/emulated/0", userNamedSdcard = false)
        assertEquals("/storage/emulated/0", plan[SharedStorageBindPlan.SDCARD])
        assertEquals("/storage/emulated/0", plan[SharedStorageBindPlan.EMULATED])
        assertEquals("/storage/emulated/0", plan[SharedStorageBindPlan.MOUNTS_SDCARD])
    }

    @Test
    fun userNamedSdcardIsNotOverridden() {
        val plan = SharedStorageBindPlan.plan("/storage/emulated/0", userNamedSdcard = true)
        assertFalse(plan.containsKey(SharedStorageBindPlan.MOUNTS_SDCARD))
        assertTrue(plan.containsKey(SharedStorageBindPlan.SDCARD))
    }
}
