package com.openminis.app.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * browser_use and the in-app BrowserSheet share **one** Blink engine: the
 * system WebView APK. This app cannot ship Chromium; the real kernel is
 * whatever `WebView.getCurrentWebViewPackage()` provides.
 *
 * Target major is Chrome/151. Older and newer WebViews keep working
 * (downward / upward compatible). UA and Client Hints follow the actual
 * package — never spoof 151 when Blink is something else.
 */
object WebViewEngine {
    const val TARGET_MAJOR = 151

    enum class CompatBand {
        BELOW_TARGET,
        AT_TARGET,
        ABOVE_TARGET,
        UNKNOWN,
    }

    data class Snapshot(
        val packageName: String?,
        val versionName: String?,
        val major: Int?,
        val band: CompatBand,
    ) {
        val chromeLabel: String
            get() = major?.let { "Chrome/$it" } ?: (versionName ?: "unknown")

        val summary: String
            get() = "pkg=${packageName ?: "?"} ver=${versionName ?: "?"} " +
                "major=${major ?: "?"} target=$TARGET_MAJOR band=$band"
    }

    fun majorFrom(versionName: String?, chromeToken: String? = null): Int? {
        leadingMajor(versionName)?.let { return it }
        val token = chromeToken ?: return null
        return leadingMajor(ChromeUserAgent.versionFrom(token))
    }

    fun band(major: Int?): CompatBand = when {
        major == null -> CompatBand.UNKNOWN
        major < TARGET_MAJOR -> CompatBand.BELOW_TARGET
        major == TARGET_MAJOR -> CompatBand.AT_TARGET
        else -> CompatBand.ABOVE_TARGET
    }

    fun snapshot(context: Context, seedUa: String? = null): Snapshot {
        val pkg = currentPackage(context)
        val versionName = pkg?.versionName
        val major = majorFrom(versionName, seedUa)
        return Snapshot(
            packageName = pkg?.packageName,
            versionName = versionName,
            major = major,
            band = band(major),
        )
    }

    /**
     * Enable Chromium features that exist on this WebView build; skip the
     * rest so older (and future) packages stay usable.
     */
    fun applyCompat(webView: WebView) {
        val settings = webView.settings
        try {
            settings.offscreenPreRaster = true
        } catch (_: Throwable) {
        }
        try {
            settings.safeBrowsingEnabled = true
        } catch (_: Throwable) {
        }
        feature(WebViewFeature.OFF_SCREEN_PRERASTER) {
            WebSettingsCompat.setOffscreenPreRaster(settings, true)
        }
    }

    internal fun storeUri(packageName: String?): String {
        val id = packageName?.takeIf { it.isNotBlank() } ?: "com.google.android.webview"
        return "market://details?id=$id"
    }

    fun storeIntent(packageName: String?): Intent {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse(storeUri(packageName)))
        market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return market
    }

    fun storeHttpsIntent(packageName: String?): Intent {
        val id = packageName?.takeIf { it.isNotBlank() } ?: "com.google.android.webview"
        return Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$id"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openStore(context: Context, packageName: String?) {
        try {
            context.startActivity(storeIntent(packageName))
        } catch (_: Throwable) {
            try {
                context.startActivity(storeHttpsIntent(packageName))
            } catch (_: Throwable) {
            }
        }
    }

    private fun currentPackage(context: Context) = try {
        WebView.getCurrentWebViewPackage()
            ?: WebViewCompat.getCurrentWebViewPackage(context)
    } catch (_: Throwable) {
        try {
            WebViewCompat.getCurrentWebViewPackage(context)
        } catch (_: Throwable) {
            null
        }
    }

    private fun leadingMajor(version: String?): Int? {
        if (version.isNullOrBlank()) return null
        val digits = version.takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    private inline fun feature(name: String, block: () -> Unit) {
        try {
            if (WebViewFeature.isFeatureSupported(name)) block()
        } catch (_: Throwable) {
        }
    }
}
