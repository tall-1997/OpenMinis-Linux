package com.openminis.app.config

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-prefs-guard-never-crashes] The registry is generated, so it is always
 * potentially incomplete. A gap must be a build-time failure (strict) and a
 * log line at runtime (lenient) — never a user-visible crash or a degraded
 * startup. Five production crashes in #419/#420 came from the opposite.
 */
class PrefsGuardLeniencyTest {

    private val strictWas = Prefs.strictKeyGuard

    @After
    fun restore() {
        Prefs.strictKeyGuard = strictWas
        Prefs.unregisteredKeys.clear()
        Prefs.uninstall()
    }

    @Test
    fun installLenientIsTheSingleAppEntryPoint() {
        Prefs.strictKeyGuard = true
        Prefs.installLenient(InMemorySource())
        assertFalse("the app install must also flip the guard", Prefs.strictKeyGuard)
        // And it must already be lenient for the very first read.
        val handle = Prefs.file("appearance_prefs")
        assertFalse(handle.bool("chat.notRegisteredYet", false))
    }

    @Test
    fun strictModeFailsOnAnUnregisteredKey() {
        Prefs.strictKeyGuard = true
        Prefs.install(InMemorySource())
        val handle = Prefs.file("appearance_prefs")
        try {
            handle.bool("chat.thisKeyDoesNotExist", false)
            fail("strict mode must throw so CI catches the missing registration")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("not registered"))
        }
    }

    @Test
    fun lenientModeKeepsTheAppAliveAndRecordsTheKey() {
        Prefs.strictKeyGuard = false
        Prefs.install(InMemorySource())
        Prefs.unregisteredKeys.clear()
        val handle = Prefs.file("appearance_prefs")
        // No throw: the feature keeps working exactly like the old SP path.
        assertFalse(handle.bool("chat.thisKeyDoesNotExist", false))
        handle.set("chat.thisKeyDoesNotExist", true)
        assertTrue(handle.bool("chat.thisKeyDoesNotExist", false))
        assertTrue("the gap must be recorded for diagnostics", Prefs.unregisteredKeys.any { it.endsWith("/chat.thisKeyDoesNotExist") })
    }

    @Test
    fun anUnknownFileDegradesToAnOpenKeySpaceInsteadOfCrashing() {
        Prefs.strictKeyGuard = false
        Prefs.install(InMemorySource())
        Prefs.unregisteredKeys.clear()
        val handle = Prefs.file("file_that_was_never_registered")
        handle.set("anything.goes", 1)
        assertEquals(1, handle.int("anything.goes", 0))
    }

    @Test
    fun aStrictBuildStillRejectsAnUnknownFile() {
        Prefs.strictKeyGuard = true
        Prefs.install(InMemorySource())
        try {
            Prefs.file("file_that_was_never_registered")
            fail("strict mode must reject an unregistered file")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not registered"))
        }
    }
}
