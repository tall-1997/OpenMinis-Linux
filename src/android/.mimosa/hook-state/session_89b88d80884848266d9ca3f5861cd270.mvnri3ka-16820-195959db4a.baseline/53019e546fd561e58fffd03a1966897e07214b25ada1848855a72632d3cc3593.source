package com.openminis.app.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.36.16 keeps a back arrow on the tool-limits page under multi-agent.
 * 1.36.37 only removes arrows from the settings root and first-level children.
 */
class ToolLimitsBackArrowTest {
    @Test
    fun toolLimitsPassesTheCallerBackCallbackToTheScaffold() {
        val screen = read("app/src/main/java/com/openminis/app/ui/settings/ToolLimitsSettingsScreen.kt")
        assertTrue(screen.contains("fun ToolLimitsSettingsScreen(onBack: () -> Unit)"))
        assertTrue(screen.contains("onBack = onBack"))
        assertFalse(screen.contains("onBack = null"))
    }

    @Test
    fun navigationStillHandsThePopCallbackToToolLimits() {
        val nav = read("app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt")
        val start = nav.indexOf("ToolLimitsSettingsScreen(")
        assertTrue(start >= 0)
        val window = nav.substring(start, (start + 220).coerceAtMost(nav.length))
        assertTrue(window, window.contains("onBack = { navController.safePopBackStack() }"))
    }

    private fun read(relative: String): String {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir missing"))
        repeat(8) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("missing $relative from ${System.getProperty("user.dir")}")
    }
}
