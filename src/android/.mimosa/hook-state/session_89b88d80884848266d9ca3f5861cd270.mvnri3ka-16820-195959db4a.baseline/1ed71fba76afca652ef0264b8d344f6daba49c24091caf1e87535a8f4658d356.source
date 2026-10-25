package com.openminis.app.upgrade

import com.openminis.app.data.repository.SkillEnableMigration
import org.junit.Assert.assertEquals
import org.junit.Test

class UpgradeSkillEnableCompatTest {
    @Test
    fun dbDisablesSurviveAnEmptyPrefsSet() {
        val merged = SkillEnableMigration.merge(emptySet(), setOf("android-device-ops", "custom"))
        assertEquals(setOf("android-device-ops", "custom"), merged)
    }

    @Test
    fun prefsAndDbAreUnioned() {
        val merged = SkillEnableMigration.merge(setOf("a"), setOf("b"))
        assertEquals(setOf("a", "b"), merged)
    }
}
