package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCookieCodecTest {

    @Test
    fun sameSiteNoneForcesSecureAndUsesCookieHost() {
        val encoded = BrowserCookieCodec.encode(
            "https://accounts.google.com/login",
            mapOf(
                "name" to "SID",
                "value" to "abc",
                "domain" to ".google.com",
                "path" to "/",
                "sameSite" to "None",
            ),
        )
        assertNotNull(encoded)
        assertTrue(encoded!!.setCookieHeader.contains("SameSite=None"))
        assertTrue(encoded.setCookieHeader.contains("Secure"))
        assertEquals("https://google.com/", encoded.applyUrl)
        assertEquals(".google.com", encoded.domain)
    }

    @Test
    fun sameSiteLaxDoesNotForceSecure() {
        val encoded = BrowserCookieCodec.encode(
            "http://example.com/",
            mapOf("name" to "a", "value" to "b", "same_site" to "lax"),
        )
        assertNotNull(encoded)
        assertTrue(encoded!!.setCookieHeader.contains("SameSite=Lax"))
        assertFalse(encoded.setCookieHeader.contains("Secure"))
        assertEquals("http://example.com/", encoded.applyUrl)
    }

    @Test
    fun chromeNoRestrictionMapsToNone() {
        assertEquals("None", BrowserCookieCodec.normalizeSameSite("no_restriction"))
        assertEquals("Strict", BrowserCookieCodec.normalizeSameSite("STRICT"))
    }
}
