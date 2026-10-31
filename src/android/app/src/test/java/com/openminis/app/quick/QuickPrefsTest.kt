package com.openminis.app.quick

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [E-batch-quick-invocation] The quick package's self-owned prefs: the
 * panel session record that drives the 10-minute reopen window. Robolectric
 * supplies a real SharedPreferences implementation.
 */
@RunWith(RobolectricTestRunner::class)
// Plain android.app.Application, NOT MinisApp — quick prefs must work in any
// process state, including safe-mode startups where MinisApp never initializes.
@Config(sdk = [35], application = android.app.Application::class)
class QuickPrefsTest {

    private lateinit var context: Context
    private lateinit var prefs: QuickPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(QuickPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        prefs = QuickPrefs(context)
    }

    @Test
    fun `defaults are empty`() {
        assertNull(prefs.panelSessionId)
        assertEquals(0L, prefs.panelUsedAtMs)
    }

    @Test
    fun `markPanelSession records id and timestamp together`() {
        val before = System.currentTimeMillis()
        prefs.markPanelSession("session-42")
        val after = System.currentTimeMillis()
        assertEquals("session-42", prefs.panelSessionId)
        assertTrue(prefs.panelUsedAtMs in before..after)
    }

    @Test
    fun `touchPanelSession refreshes only an existing record`() {
        // Nothing recorded yet: touch must NOT fabricate a timestamp.
        prefs.touchPanelSession()
        assertEquals(0L, prefs.panelUsedAtMs)

        prefs.markPanelSession("s1")
        val stamped = prefs.panelUsedAtMs
        // Force an old timestamp, then touch forward.
        prefs.panelUsedAtMs = stamped - QuickPanelPolicy.RECENT_PANEL_MS
        prefs.touchPanelSession()
        assertTrue(prefs.panelUsedAtMs > stamped - QuickPanelPolicy.RECENT_PANEL_MS)
        assertEquals("s1", prefs.panelSessionId)
    }

    @Test
    fun `recorded session drives the reuse policy end to end`() {
        // Fresh record → policy says reuse.
        prefs.markPanelSession("recent")
        assertTrue(
            QuickPanelPolicy.shouldReusePanelSession(
                prefs.panelSessionId, prefs.panelUsedAtMs, System.currentTimeMillis(),
            ),
        )
        // Aged-out record (timestamp manually pushed past the window) → no reuse.
        prefs.panelUsedAtMs = System.currentTimeMillis() - QuickPanelPolicy.RECENT_PANEL_MS - 1
        assertFalse(
            QuickPanelPolicy.shouldReusePanelSession(
                prefs.panelSessionId, prefs.panelUsedAtMs, System.currentTimeMillis(),
            ),
        )
    }
}
