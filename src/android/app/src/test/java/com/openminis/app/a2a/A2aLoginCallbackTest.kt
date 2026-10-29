package com.openminis.app.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM contract tests for the shell-side login-callback rewrites.
 *
 * Both shapes come from upstream v3.0.8 (login.rs `is_login_callback` /
 * `autoclaw_callback_forward`); the assertions pin the gateway route each
 * one is forwarded to, because a mismatch is silent: the provider reports
 * success while the gateway never receives the code.
 */
class A2aLoginCallbackTest {

    private val port = 18901

    @Test
    fun `raccoon deep link is forwarded to the public callback route`() {
        val url = "office-raccoon://auth/callback?state=t-1&authorization_code=abc"

        assertEquals(
            "http://127.0.0.1:$port/api/session/login/raccoon-callback?state=t-1&authorization_code=abc",
            A2aLoginCallback.rewrite(url, port),
        )
    }

    @Test
    fun `raccoon deep link without a task state is left alone`() {
        assertNull(A2aLoginCallback.rewrite("office-raccoon://auth/callback?code=abc", port))
        assertNull(A2aLoginCallback.rewrite("office-raccoon://auth/callback", port))
        assertNull(A2aLoginCallback.rewrite("office-raccoon://auth/callback?state=", port))
    }

    @Test
    fun `raccoon deep link outside the callback shape is left alone`() {
        assertNull(A2aLoginCallback.rewrite("office-raccoon://other/callback?state=t", port))
        assertNull(A2aLoginCallback.rewrite("office-raccoon://auth/other?state=t", port))
        // Only the custom scheme is the shell's to deliver; the http variant
        // has to reach whatever listener the gateway opened itself.
        assertNull(A2aLoginCallback.rewrite("https://auth/callback?state=t", port))
    }

    @Test
    fun `autoclaw registered port is rewritten onto the gateway`() {
        val url = "http://localhost:18432/auth/callback-zai?code=c1&state=s1"

        assertEquals(
            "http://127.0.0.1:$port/auth/callback-zai?code=c1&state=s1",
            A2aLoginCallback.rewrite(url, port),
        )
        assertEquals(
            "http://127.0.0.1:$port/auth/callback-google?code=c2",
            A2aLoginCallback.rewrite("http://127.0.0.1:53699/auth/callback-google?code=c2", port),
        )
    }

    @Test
    fun `autoclaw callback already on the gateway port is not rewritten`() {
        assertNull(
            A2aLoginCallback.rewrite("http://127.0.0.1:$port/auth/callback-zai?code=c1", port),
        )
    }

    @Test
    fun `other loopback listeners keep their own callbacks`() {
        // Trae/Accio/Antigravity redirects point at listeners the gateway
        // opened; stealing those would break the flows that already work.
        assertNull(A2aLoginCallback.rewrite("http://127.0.0.1:1455/auth/callback-zai?code=c", port))
        assertNull(A2aLoginCallback.rewrite("http://127.0.0.1:18432/auth/other?code=c", port))
        assertNull(A2aLoginCallback.rewrite("https://localhost:18432/auth/callback-zai?code=c", port))
        assertNull(A2aLoginCallback.rewrite("http://example.com:18432/auth/callback-zai?code=c", port))
    }

    @Test
    fun `non-callback URLs and bad input are left alone`() {
        assertNull(A2aLoginCallback.rewrite(null, port))
        assertNull(A2aLoginCallback.rewrite("", port))
        assertNull(A2aLoginCallback.rewrite("tel:+10000000000", port))
        assertNull(A2aLoginCallback.rewrite("http://127.0.0.1:18432/auth/callback-zai", 0))
    }
}
