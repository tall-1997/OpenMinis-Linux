package com.openminis.app.upgrade

import com.openminis.app.agent.SoulStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeSoulCompatTest {
    @Test
    fun onlyTheShippedStarterIsReplaced() {
        assertTrue(SoulStore.shouldReplaceSoulOnUpgrade(SoulStore.oldDefaultStarterForTest()))
        assertFalse(SoulStore.shouldReplaceSoulOnUpgrade(null))
        assertFalse(SoulStore.shouldReplaceSoulOnUpgrade("user wrote this"))
        assertFalse(SoulStore.shouldReplaceSoulOnUpgrade(""))
    }
}
