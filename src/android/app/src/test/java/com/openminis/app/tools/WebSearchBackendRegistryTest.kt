package com.openminis.app.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [GH#298] The backend registry must be a live extension point, not dead code:
 * the shipped DuckDuckGo backend is registered at startup, and the tool-level
 * master switch still refuses an explicit call after it is turned off.
 */
@RunWith(RobolectricTestRunner::class)
class WebSearchBackendRegistryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WebSearchBackendRegistry.clearForTest()
        WebSearchSettings.setToolEnabled(context, true)
    }

    @After
    fun tearDown() {
        WebSearchBackendRegistry.clearForTest()
        WebSearchSettings.setToolEnabled(context, true)
    }

    @Test
    fun `default backend is registered exactly once`() {
        assertTrue("first call must register", WebSearchBackendRegistry.ensureDefaultRegistered())
        assertEquals(
            listOf("duckduckgo"),
            WebSearchBackendRegistry.backends().map { it.id },
        )

        assertFalse("second call is a no-op", WebSearchBackendRegistry.ensureDefaultRegistered())
        assertEquals(1, WebSearchBackendRegistry.backends().size)
    }

    @Test
    fun `tool switch off rejects a call even with the default backend registered`() {
        WebSearchBackendRegistry.ensureDefaultRegistered()
        WebSearchSettings.setToolEnabled(context, false)

        val result = WebSearchTool.execute(
            """{"query":"android","tool_title":"test"}""",
            context,
        )

        assertFalse(result.success)
        assertTrue(result.output.contains("disabled", ignoreCase = true))
    }
}
