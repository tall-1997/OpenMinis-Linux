package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [#41] Gen UI pure rules. No Android/Compose types here — the fence
 * contract, the sandbox profile's safe defaults, and the CSP injection are
 * all pinned on the JVM. WebView behavior itself is instrumented territory
 * (leave real-device verification to a manual pass).
 */
class GenUiSandboxTest {

    // ── fence recognition ────────────────────────────────────────────────

    @Test
    fun `plain gen-ui tag is recognized`() {
        assertTrue(GenUi.isGenUiFence("gen-ui"))
    }

    @Test
    fun `tag matching is case-insensitive`() {
        for (lang in listOf("GEN-UI", "Gen-UI", "gEN-uI")) {
            assertTrue("expected $lang to be Gen UI", GenUi.isGenUiFence(lang))
        }
    }

    @Test
    fun `language tail after the tag is accepted`() {
        // The tail is a free-form authoring hint (flavor/variant); the first
        // whitespace-separated token decides.
        for (lang in listOf("gen-ui html", "gen-ui  v2", "gen-ui\ttable")) {
            assertTrue("expected '$lang' to be Gen UI", GenUi.isGenUiFence(lang))
        }
    }

    @Test
    fun `ordinary languages are not gen-ui`() {
        for (lang in listOf("", "   ", "kotlin", "html", "javascript", "diff")) {
            assertFalse("expected '$lang' to be an ordinary code block", GenUi.isGenUiFence(lang))
        }
    }

    @Test
    fun `tokens that merely start with the tag are not gen-ui`() {
        for (lang in listOf("gen-ui-html", "gen-uix", "gen_ui", "genui")) {
            assertFalse("expected '$lang' to be an ordinary code block", GenUi.isGenUiFence(lang))
        }
    }

    @Test
    fun `leading whitespace before the tag is tolerated`() {
        assertTrue(GenUi.isGenUiFence("  gen-ui"))
    }

    // ── sandbox profile defaults ─────────────────────────────────────────

    @Test
    fun `sandbox spec has the safe defaults`() {
        val s = GenUi.Spec.SANDBOXED
        assertTrue("local interactivity is the point", s.javaScriptEnabled)
        assertTrue("DOM storage enables local state", s.domStorageEnabled)
        assertFalse("no filesystem access", s.allowFileAccess)
        assertFalse("no content-provider access", s.allowContentAccess)
        assertFalse("no per-file URL bridge", s.allowFileAccessFromFileURLs)
        assertFalse("no universal file bridge", s.allowUniversalAccessFromFileURLs)
        assertTrue("media must wait for a gesture", s.mediaPlaybackRequiresUserGesture)
        assertTrue("WebKit-level network kill switch stays on", s.blockNetworkLoads)
    }

    @Test
    fun `base url is the localhost sandbox origin`() {
        assertEquals("http://localhost/", GenUi.BASE_URL)
    }

    // ── CSP injection ────────────────────────────────────────────────────

    @Test
    fun `csp is injected right after the head tag`() {
        val html = "<!DOCTYPE html>\n<html>\n<head>\n<title>t</title>\n</head>\n<body>hi</body>\n</html>"
        val wrapped = GenUi.wrapHtml(html)
        assertTrue(wrapped.contains("Content-Security-Policy"))
        assertTrue(
            "meta must sit inside <head>",
            wrapped.indexOf("<head>") < wrapped.indexOf("Content-Security-Policy") &&
                wrapped.indexOf("Content-Security-Policy") < wrapped.indexOf("</head>"),
        )
        // The original document is preserved verbatim after the injection point.
        assertTrue(wrapped.endsWith(html.substring(html.indexOf("</head>"))))
    }

    @Test
    fun `csp is case-insensitive on the head tag`() {
        val html = "<HTML><HEAD><title>x</title></HEAD><body></body></HTML>"
        val wrapped = GenUi.wrapHtml(html)
        val cspIdx = wrapped.indexOf("Content-Security-Policy")
        assertTrue(cspIdx > wrapped.indexOf("<HEAD>"))
        assertTrue(cspIdx < wrapped.indexOf("</HEAD>"))
    }

    @Test
    fun `csp is prepended when there is no head tag`() {
        val html = "<div>no head here</div>"
        val wrapped = GenUi.wrapHtml(html)
        assertTrue(wrapped.startsWith("<meta http-equiv=\"Content-Security-Policy\""))
        assertTrue(wrapped.endsWith(html))
    }

    @Test
    fun `csp blocks remote channels but keeps inline code`() {
        val html = GenUi.wrapHtml("<html><head></head><body></body></html>")
        val head = html.substring(0, html.indexOf("</head>"))
        assertTrue(head.contains("default-src 'none'"))
        assertTrue(head.contains("script-src 'unsafe-inline'"))
        assertTrue(head.contains("style-src 'unsafe-inline'"))
        assertTrue(head.contains("connect-src 'none'"))
        assertFalse(head.contains("script-src *"))
        assertFalse(head.contains("connect-src *"))
    }
}
