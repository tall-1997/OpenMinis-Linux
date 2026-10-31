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
 * Target major is Chrome/157 (current stable at the time of writing). Older
 * and newer WebViews keep working (downward / upward compatible). UA and
 * Client Hints follow the actual package — never spoof 157 when Blink is
 * something else.
 *
 * [T-webview-stale-prompt] A WebView more than [STALE_GAP] majors below the
 * target is "stale": the Play push cycle would have delivered the new one,
 * so the user is on a frozen ROM build and browser_use / the in-app browser
 * are missing features the current stable has.
 */
object WebViewEngine {
    const val TARGET_MAJOR = 157

    /** Majors of lag below which the kernel counts as stale. */
    const val STALE_GAP = 2

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

    /** Majors behind the target; zero when unknown or already ahead. */
    fun lag(major: Int?): Int =
        if (major == null || major >= TARGET_MAJOR) 0 else TARGET_MAJOR - major

    /** True when the kernel is far enough behind that a prompt is warranted. */
    fun isStale(snapshot: Snapshot): Boolean =
        snapshot.major != null && lag(snapshot.major) > STALE_GAP

    /**
     * True when the engine-stale banner was already acknowledged (or the
     * update action was taken) this app session set — the banner stays a
     * one-time notice instead of nagging every sheet open.
     */
    fun stalePromptSeen(context: Context): Boolean =
        context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
            .getBoolean("engine_stale_seen_$TARGET_MAJOR", false)

    /** Mark the stale banner as acknowledged for the current target. */
    fun markStalePromptSeen(context: Context) {
        context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("engine_stale_seen_$TARGET_MAJOR", true).apply()
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
                // No GMS: Play links are dead on ROMs without Google services.
                // Fall back to the system app-info page — the only system
                // surface that works everywhere (update path then depends on
                // the vendor, e.g. a system OTA).
                try {
                    context.startActivity(settingsIntent(packageName))
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** `package:` URI for the WebView package's app-info settings page. */
    internal fun settingsUri(packageName: String?): String {
        val id = packageName?.takeIf { it.isNotBlank() } ?: "com.google.android.webview"
        return "package:$id"
    }

    /** App-info settings page for the WebView package; works without GMS. */
    internal fun settingsIntent(packageName: String?): Intent {
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse(settingsUri(packageName)),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
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
