package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentProfileTest {

    private val systemUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.6778.135 Mobile Safari/537.36"

    @Test
    fun `profiles do not hardcode a Chrome version`() {
        assertNull(UserAgentProfile.MOBILE_CHROME.userAgentString)
        assertNull(UserAgentProfile.DESKTOP_CHROME.userAgentString)
    }

    @Test
    fun `versionFrom reads the system WebView Chrome token`() {
        assertEquals("131.0.6778.135", ChromeUserAgent.versionFrom(systemUa))
    }

    @Test
    fun `mobile resolve keeps the system UA`() {
        assertEquals(
            systemUa,
            ChromeUserAgent.resolve(UserAgentProfile.MOBILE_CHROME, systemUa),
        )
    }

    @Test
    fun `desktop resolve keeps Chrome version from the system UA`() {
        val ua = ChromeUserAgent.resolve(UserAgentProfile.DESKTOP_CHROME, systemUa)
        assertTrue(ua.contains("Chrome/131.0.6778.135"))
        assertTrue(ua.contains("X11; Linux x86_64"))
        assertEquals(false, ua.contains("Mobile"))
    }

    @Test
    fun `custom UA wins when set`() {
        assertEquals(
            "MyAgent/1",
            ChromeUserAgent.resolve(UserAgentProfile.CUSTOM, systemUa, "MyAgent/1"),
        )
    }
}
