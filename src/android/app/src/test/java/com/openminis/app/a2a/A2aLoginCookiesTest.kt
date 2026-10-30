package com.openminis.app.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for [A2aLoginCookies]. [T-android-a2a-incognito] */
class A2aLoginCookiesTest {

    @Test
    fun `loopback hosts are never tracked`() {
        assertTrue(A2aLoginCookies.isLoopbackHost("127.0.0.1"))
        assertTrue(A2aLoginCookies.isLoopbackHost("localhost"))
        assertTrue(A2aLoginCookies.isLoopbackHost("::1"))
        assertTrue(A2aLoginCookies.isLoopbackHost("[::1]"))
        assertFalse(A2aLoginCookies.isLoopbackHost("auth.provider.example"))
        assertFalse(A2aLoginCookies.isLoopbackHost(null))
    }

    @Test
    fun `only http and https origins are tracked`() {
        assertEquals("https://auth.provider.example", A2aLoginCookies.trackedOrigin("https://auth.provider.example/login?q=1"))
        assertEquals("http://catpaw.example", A2aLoginCookies.trackedOrigin("http://catpaw.example/cb"))
        assertNull(A2aLoginCookies.trackedOrigin("http://127.0.0.1:18901/api/session/login/raccoon-callback?state=x"))
        assertNull(A2aLoginCookies.trackedOrigin("http://localhost:18901/"))
        assertNull(A2aLoginCookies.trackedOrigin("office-raccoon://callback?code=1"))
        assertNull(A2aLoginCookies.trackedOrigin("javascript:alert(1)"))
        assertNull(A2aLoginCookies.trackedOrigin(null))
    }

    @Test
    fun `origin normalises host case`() {
        assertEquals("https://auth.example.com", A2aLoginCookies.trackedOrigin("https://auth.Example.com/"))
    }

    @Test
    fun `cookie names parse from a set-cookie style header`() {
        assertEquals(
            listOf("a2a_session", "account", "flag"),
            A2aLoginCookies.cookieNames("a2a_session=abc; account=1; flag=; "),
        )
        assertEquals(emptyList<String>(), A2aLoginCookies.cookieNames(null))
        assertEquals(emptyList<String>(), A2aLoginCookies.cookieNames(""))
    }

    @Test
    fun `duplicate cookie names collapse`() {
        assertEquals(listOf("dup"), A2aLoginCookies.cookieNames("dup=1; dup=2"))
    }

    /**
     * [T-android-a2a-incognito] `getCookie` never reports the `Domain`
     * attribute, so expiry has to cover the label chain — otherwise a
     * `.provider.com` SSO cookie survives a host-only expiry and the next
     * login round starts pre-authenticated as the previous account.
     */
    @Test
    fun `expiry covers host-only and every parent domain scope`() {
        assertEquals(
            listOf(null, ".auth.huaweicloud.com", ".huaweicloud.com"),
            A2aLoginCookies.cookieDomains("auth.huaweicloud.com"),
        )
        assertEquals(listOf(null, ".huaweicloud.com"), A2aLoginCookies.cookieDomains("HuaweiCloud.com"))
        // A public suffix is never a valid Domain attribute.
        assertEquals(listOf(null), A2aLoginCookies.cookieDomains("com"))
        // IP literals / single labels have no domain scope at all.
        assertEquals(listOf(null), A2aLoginCookies.cookieDomains("127.0.0.1"))
        assertEquals(listOf(null), A2aLoginCookies.cookieDomains("::1"))
        assertEquals(listOf(null), A2aLoginCookies.cookieDomains("localhost"))
        assertEquals(listOf(null), A2aLoginCookies.cookieDomains(null))
    }

    @Test
    fun `expiry header drops the cookie in the requested scope`() {
        assertEquals("a2a_session=; Max-Age=0; Path=/", A2aLoginCookies.expiryHeader("a2a_session", null))
        assertEquals(
            "a2a_session=; Max-Age=0; Path=/; Domain=.huaweicloud.com",
            A2aLoginCookies.expiryHeader("a2a_session", ".huaweicloud.com"),
        )
    }
}
