package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewEngineTest {

    @Test
    fun `target major is 157`() {
        assertEquals(157, WebViewEngine.TARGET_MAJOR)
    }

    @Test
    fun `majorFrom reads WebView package versionName`() {
        assertEquals(157, WebViewEngine.majorFrom("157.0.8103.30"))
        assertEquals(131, WebViewEngine.majorFrom("131.0.6778.135"))
        assertEquals(160, WebViewEngine.majorFrom("160.0.1"))
    }

    @Test
    fun `majorFrom falls back to Chrome UA token`() {
        val ua =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/157.0.8103.30 Mobile Safari/537.36"
        assertEquals(157, WebViewEngine.majorFrom(null, ua))
    }

    @Test
    fun `compat bands cover below at and above target`() {
        assertEquals(WebViewEngine.CompatBand.BELOW_TARGET, WebViewEngine.band(131))
        assertEquals(WebViewEngine.CompatBand.AT_TARGET, WebViewEngine.band(157))
        assertEquals(WebViewEngine.CompatBand.ABOVE_TARGET, WebViewEngine.band(160))
        assertEquals(WebViewEngine.CompatBand.UNKNOWN, WebViewEngine.band(null))
    }

    @Test
    fun `lag is zero at or above target and unknown`() {
        assertEquals(0, WebViewEngine.lag(null))
        assertEquals(0, WebViewEngine.lag(157))
        assertEquals(0, WebViewEngine.lag(160))
        assertEquals(7, WebViewEngine.lag(150))
        assertEquals(26, WebViewEngine.lag(131))
    }

    @Test
    fun `stale only beyond the gap, never for unknown or newer`() {
        val snap = { major: Int? ->
            WebViewEngine.Snapshot("com.google.android.webview", "$major.0", major, WebViewEngine.band(major))
        }
        assertTrue(WebViewEngine.isStale(snap(150)))
        assertTrue(WebViewEngine.isStale(snap(131)))
        assertTrue(!WebViewEngine.isStale(snap(155)))
        assertTrue(!WebViewEngine.isStale(snap(157)))
        assertTrue(!WebViewEngine.isStale(snap(160)))
        assertTrue(!WebViewEngine.isStale(snap(null)))
    }

    @Test
    fun `store intent targets the current WebView package`() {
        val uri = WebViewEngine.storeUri("com.google.android.webview")
        assertTrue(uri.startsWith("market://"))
        assertTrue(uri.contains("com.google.android.webview"))
    }

    @Test
    fun `settings fallback uses app details action with package uri`() {
        assertEquals("package:com.android.webview", WebViewEngine.settingsUri("com.android.webview"))
        assertEquals(
            "package:com.google.android.webview",
            WebViewEngine.settingsUri(" "),
        )
    }

    @Test
    fun `settings fallback defaults to google webview package`() {
        assertEquals("package:com.google.android.webview", WebViewEngine.settingsUri(null))
    }
}
