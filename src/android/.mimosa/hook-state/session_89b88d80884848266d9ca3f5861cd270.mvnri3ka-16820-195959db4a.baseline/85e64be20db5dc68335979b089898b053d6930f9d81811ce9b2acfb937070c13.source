package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewEngineTest {

    @Test
    fun `target major is 151`() {
        assertEquals(151, WebViewEngine.TARGET_MAJOR)
    }

    @Test
    fun `majorFrom reads WebView package versionName`() {
        assertEquals(151, WebViewEngine.majorFrom("151.0.7444.52"))
        assertEquals(131, WebViewEngine.majorFrom("131.0.6778.135"))
        assertEquals(160, WebViewEngine.majorFrom("160.0.1"))
    }

    @Test
    fun `majorFrom falls back to Chrome UA token`() {
        val ua =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/151.0.7444.52 Mobile Safari/537.36"
        assertEquals(151, WebViewEngine.majorFrom(null, ua))
    }

    @Test
    fun `compat bands cover below at and above target`() {
        assertEquals(WebViewEngine.CompatBand.BELOW_TARGET, WebViewEngine.band(131))
        assertEquals(WebViewEngine.CompatBand.AT_TARGET, WebViewEngine.band(151))
        assertEquals(WebViewEngine.CompatBand.ABOVE_TARGET, WebViewEngine.band(160))
        assertEquals(WebViewEngine.CompatBand.UNKNOWN, WebViewEngine.band(null))
    }

    @Test
    fun `store intent targets the current WebView package`() {
        val uri = WebViewEngine.storeUri("com.google.android.webview")
        assertTrue(uri.startsWith("market://"))
        assertTrue(uri.contains("com.google.android.webview"))
    }
}
