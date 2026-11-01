package com.openminis.app.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM contract tests for the shell-side login-callback rewrites.
 *
 * The panel's own callbacks land on the panel port and are handled in
 * place; the only URLs the shell rewrites are the AutoClaw registered-port
 * callbacks of the 3.0.x lineage (upstream `autoclaw_callback_forward`),
 * kept as a compatibility net for packs that predate the SMS flow. The
 * assertions pin the rewritten route, because a mismatch is silent: the
 * provider reports success while the gateway never receives the code.
 */
class A2aLoginCallbackTest {

    /** The panel router's port — the one whose callbacks are served in place. */
    private val panelPort = 18902

    @Test
    fun `raccoon deep links are handled by the web shim, not the shell`() {
        // The 3.3 web shim intercepts raccoon's office-raccoon:// callback
        // itself (and refuses to launch when the panel origin cannot receive
        // it). Rewriting it here would point it at a raccoon-callback route
        // that no longer exists — a silent 404. The shell must not touch it.
        assertNull(
            A2aLoginCallback.rewrite(
                "office-raccoon://auth/callback?state=t-1&authorization_code=abc",
                panelPort,
            ),
        )
        assertNull(A2aLoginCallback.rewrite("office-raccoon://auth/callback?code=abc", panelPort))
        assertNull(A2aLoginCallback.rewrite("office-raccoon://auth/callback", panelPort))
        assertNull(A2aLoginCallback.rewrite("office-raccoon://auth/callback?state=", panelPort))
        assertNull(A2aLoginCallback.rewrite("office-raccoon://other/callback?state=t", panelPort))
        assertNull(A2aLoginCallback.rewrite("office-raccoon://auth/other?state=t", panelPort))
        // Only the custom scheme was ever the shell's to deliver; the http
        // variant has to reach whatever listener the gateway opened itself.
        assertNull(A2aLoginCallback.rewrite("https://auth/callback?state=t", panelPort))
    }

    @Test
    fun `autoclaw registered port is rewritten onto the panel`() {
        val url = "http://localhost:18432/auth/callback-zai?code=c1&state=s1"

        assertEquals(
            "http://127.0.0.1:$panelPort/auth/callback-zai?code=c1&state=s1",
            A2aLoginCallback.rewrite(url, panelPort),
        )
        assertEquals(
            "http://127.0.0.1:$panelPort/auth/callback-google?code=c2",
            A2aLoginCallback.rewrite(
                "http://127.0.0.1:53699/auth/callback-google?code=c2",
                panelPort,
            ),
        )
    }

    @Test
    fun `autoclaw callback already on the panel port is not rewritten`() {
        assertNull(
            A2aLoginCallback.rewrite(
                "http://127.0.0.1:$panelPort/auth/callback-zai?code=c1",
                panelPort,
            ),
        )
    }

    @Test
    fun `other loopback listeners keep their own callbacks`() {
        // Trae/Accio/Antigravity redirects point at listeners the gateway
        // opened; stealing those would break the flows that already work.
        assertNull(
            A2aLoginCallback.rewrite("http://127.0.0.1:1455/auth/callback-zai?code=c", panelPort),
        )
        assertNull(A2aLoginCallback.rewrite("http://127.0.0.1:18432/auth/other?code=c", panelPort))
        assertNull(A2aLoginCallback.rewrite("https://localhost:18432/auth/callback-zai?code=c", panelPort))
        assertNull(A2aLoginCallback.rewrite("http://example.com:18432/auth/callback-zai?code=c", panelPort))
    }

    @Test
    fun `non-callback URLs and bad input are left alone`() {
        assertNull(A2aLoginCallback.rewrite(null, panelPort))
        assertNull(A2aLoginCallback.rewrite("", panelPort))
        assertNull(A2aLoginCallback.rewrite("tel:+10000000000", panelPort))
        assertNull(A2aLoginCallback.rewrite("http://127.0.0.1:18432/auth/callback-zai", 0))
    }

    @Test
    fun `panel callback landings are recognized`() {
        // Every shape a login round can land on: the generic callback, the
        // remote-callback route (the 3.3 web shim's target) and the
        // AutoClaw rewrites.
        assertTrue(A2aLoginCallback.isCaptureCallback("http://127.0.0.1:$panelPort/callback?code=c", panelPort))
        assertTrue(
            A2aLoginCallback.isCaptureCallback(
                "http://127.0.0.1:$panelPort/api/session/login/remote-callback?state=s&code=c",
                panelPort,
            ),
        )
        assertTrue(
            A2aLoginCallback.isCaptureCallback(
                "http://127.0.0.1:$panelPort/auth/callback-google?code=c",
                panelPort,
            ),
        )
        assertTrue(
            A2aLoginCallback.isCaptureCallback("http://localhost:$panelPort/oauth-callback", panelPort),
        )
    }

    @Test
    fun `non-callback pages are not mistaken for a capture`() {
        // The panel itself, its login gate, provider pages and other ports.
        assertFalse(A2aLoginCallback.isCaptureCallback("http://127.0.0.1:$panelPort/", panelPort))
        assertFalse(A2aLoginCallback.isCaptureCallback("http://127.0.0.1:$panelPort/login", panelPort))
        assertFalse(
            A2aLoginCallback.isCaptureCallback("https://provider.example/callback?code=c", panelPort),
        )
        assertFalse(A2aLoginCallback.isCaptureCallback("http://127.0.0.1:1455/callback", panelPort))
        assertFalse(A2aLoginCallback.isCaptureCallback("office-raccoon://auth/callback?state=s", panelPort))
        assertFalse(A2aLoginCallback.isCaptureCallback(null, panelPort))
    }
}
