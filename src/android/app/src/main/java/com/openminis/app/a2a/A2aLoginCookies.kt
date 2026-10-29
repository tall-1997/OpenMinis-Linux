package com.openminis.app.a2a

import android.content.Context
import android.webkit.CookieManager
import java.net.URI

/**
 * Per-login incognito hygiene for provider web logins — the Android
 * equivalent of the desktop shell's per-window incognito data store, without
 * its price: the panel's OWN session (loopback origin) is never touched, so
 * incognito logins and a persistent panel admin session coexist.
 *
 * [T-android-a2a-incognito] How it works:
 *  - every main-frame origin a login window visits is remembered (persisted
 *    in the :a2a process's own prefs);
 *  - before the next login window opens, exactly those PROVIDER origins get
 *    their cookies expired (`Max-Age=0` per cookie name, read back through
 *    the CookieManager), so a second account of the same provider starts
 *    fresh instead of being silently pre-authenticated as the first;
 *  - loopback origins (the gateway panel itself: 127.0.0.1 / localhost /
 *    [::1]) are excluded from both remembering and clearing — the panel
 *    admin session cookie survives every login.
 *
 * Cookie paths other than `/` (rare on auth cookies) may survive the sweep;
 * they are re-cleared on the next login attempt anyway, and the auth cookies
 * this guards are root-path scoped.
 */
object A2aLoginCookies {

    private const val PREFS = "a2a_login_cookies"
    private const val KEY_ORIGINS = "origins"

    /** Loopback hosts the panel itself lives on — never tracked, never cleared. */
    internal fun isLoopbackHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        return h == "127.0.0.1" || h == "localhost" || h == "::1"
    }

    /** `scheme://host` for http(s) URLs we should track; null for anything else. */
    internal fun trackedOrigin(url: String?): String? {
        val parsed = runCatching { URI(url?.trim() ?: "") }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = parsed.host ?: return null
        if (isLoopbackHost(host)) return null
        return "$scheme://${host.lowercase()}"
    }

    /** Parse a `getCookie(url)` header (`a=1; b=2`) into cookie names. */
    internal fun cookieNames(header: String?): List<String> =
        header.orEmpty()
            .split(';')
            .mapNotNull { part ->
                val name = part.substringBefore('=', "").trim()
                name.ifEmpty { null }
            }
            .distinct()

    /** Remember one login-window main-frame URL's origin (no-op for loopback). */
    fun rememberOrigin(context: Context, url: String?) {
        val origin = trackedOrigin(url) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getStringSet(KEY_ORIGINS, emptySet()).orEmpty()
        if (origin in existing) return
        prefs.edit().putStringSet(KEY_ORIGINS, existing + origin).apply()
    }

    /**
     * Expire every remembered provider origin's cookies. Called right before
     * a login window is created when incognito login is on. Loopback is
     * excluded by construction (never remembered).
     */
    fun clearProviderCookies(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val origins = prefs.getStringSet(KEY_ORIGINS, emptySet()).orEmpty()
        if (origins.isEmpty()) return
        val cookieManager = CookieManager.getInstance()
        for (origin in origins) {
            for (url in listOf("$origin/", "$origin")) {
                val names = cookieNames(cookieManager.getCookie(url))
                for (name in names) {
                    cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/")
                }
            }
        }
        cookieManager.flush()
    }
}
