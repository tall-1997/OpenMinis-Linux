package com.openminis.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-i18n-no-ui-import] `ThemeModeWrap` cannot import `ui.settings` (the
 * architecture policy forbids non-UI layers from importing the UI layer), so it
 * mirrors the `theme_mode` pref key as a literal. This test is the pin that
 * keeps the mirror honest — rename the canonical constant and this fails
 * instead of the first-frame theme silently reverting to the system value.
 */
class ThemeModeKeyParityTest {

    @Test
    fun theMirroredThemeModeKeyMatchesTheCanonicalConstant() {
        assertEquals(
            com.openminis.app.ui.settings.KEY_THEME_MODE,
            ThemeModeWrap.THEME_MODE_KEY,
        )
    }
}