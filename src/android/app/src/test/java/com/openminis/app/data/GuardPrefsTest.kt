package com.openminis.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [T-guard-stop-policy] The loop-guard switch: default ON, togglable. */
class GuardPrefsTest {

    @Before
    fun setUp() {
        GuardPrefs.resetForTest()
    }
    
    @After
    fun tearDown() {
        GuardPrefs.resetForTest()
    }
    
    @Test
    fun theShippedDefaultStopsTheSession() {
        assertTrue(GuardPrefs.stopEnabled())
    }
    
    @Test
    fun turningItOffIsRememberedUntilTurnedBackOn() {
        GuardPrefs.setStopEnabled(false)
        assertFalse(GuardPrefs.stopEnabled())
        GuardPrefs.setStopEnabled(true)
        assertTrue(GuardPrefs.stopEnabled())
    }
    
    @Test
    fun everyRealChangeBumpsTheRevisionButARepeatDoesNot() {
        val start = GuardPrefs.revision.value
        GuardPrefs.setStopEnabled(false)
        val afterChange = GuardPrefs.revision.value
        assertTrue(afterChange > start)
        GuardPrefs.setStopEnabled(false)
        assertEquals(afterChange, GuardPrefs.revision.value)
    }
    
    @Test
    fun resetRestoresTheDefault() {
        GuardPrefs.setStopEnabled(false)
        GuardPrefs.resetForTest()
        assertTrue(GuardPrefs.stopEnabled())
    }
}

