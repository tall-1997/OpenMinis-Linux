package com.openminis.app.browser

/**
 * Build browser_use / WebView UA strings from the **system WebView APK**
 * Chrome version ([WebViewEngine] target 151, older/newer still work).
 * Hardcoding Chrome/151 only spoofs the token; Blink and Client Hints
 * still follow the WebView package.
 *
 * Mobile profile: keep the WebView default UA unchanged.
 * Desktop profile: same Chrome version, Linux desktop platform.
 */
object ChromeUserAgent {
    private val CHROME = Regex("""Chrome/(\d+(?:\.\d+)*)""")

    fun versionFrom(seedUa: String): String? =
        CHROME.find(seedUa)?.groupValues?.getOrNull(1)

    fun desktop(seedUa: String): String {
        val ver = versionFrom(seedUa) ?: return seedUa
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$ver Safari/537.36"
    }

    fun resolve(
        profile: UserAgentProfile,
        seedUa: String,
        customUA: String? = null,
    ): String = when {
        profile == UserAgentProfile.CUSTOM && !customUA.isNullOrEmpty() -> customUA
        profile == UserAgentProfile.DESKTOP_CHROME -> desktop(seedUa)
        else -> seedUa
    }
}
