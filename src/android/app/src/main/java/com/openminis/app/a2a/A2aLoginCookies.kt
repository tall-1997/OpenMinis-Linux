package com.openminis.app.a2a

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.openminis.app.logging.AppLogger
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
 * this guards are root-path scoped. Domain scopes are handled: the sweep
 * expires the host-only cookie AND every parent `Domain` the cookie could
 * have been set with ([cookieDomains]).
 *
 * This is the FALLBACK path. On platforms whose WebView supports multiple
 * profiles ([A2aLoginProfiles]) each login round gets its own throwaway
 * profile instead, which also covers localStorage/IndexedDB/service
 * workers/cache — the surfaces a cookie sweep cannot reach, and the reason
 * a "cleared" jar could still restore the previous account.
 */
object A2aLoginCookies {

    private const val TAG = "A2aLoginCookies"
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
     * a login window is created when incognito login is on — and again once
     * the round's windows close — so the next account never inherits the
     * previous one's session. Loopback is excluded by construction (never
     * remembered).
     */
    fun clearProviderCookies(context: Context) {
        val origins = rememberedOrigins(context)
        if (origins.isEmpty()) return
        val cookieManager = CookieManager.getInstance()
        for (origin in origins) expireOrigin(cookieManager, origin)
        cookieManager.flush()
        AppLogger.info(TAG, "login cookie sweep: ${origins.size} origin(s)")
    }

    /**
     * Expire one origin's cookies across every scope the cookie may carry.
     *
     * `getCookie` reports names and values only — never the `Domain`
     * attribute — and `Set-Cookie` without `Domain` only kills a HOST-ONLY
     * cookie. Provider SSO cookies are typically domain cookies
     * (`.huaweicloud.com`), so a host-only expiry left them intact and the
     * next login round came back pre-authenticated as the previous account
     * even though the sweep "ran". Expiring the whole label chain
     * ([cookieDomains]) closes that hole.
     */
    internal fun expireOrigin(cookieManager: CookieManager, origin: String) {
        val host = runCatching { URI(origin).host }.getOrNull() ?: return
        val domains = cookieDomains(host)
        for (url in listOf("$origin/", origin)) {
            for (name in cookieNames(cookieManager.getCookie(url))) {
                for (domain in domains) {
                    cookieManager.setCookie(url, expiryHeader(name, domain))
                }
            }
        }
    }

    /**
     * `Domain` values to expire for [host], widest last: `null` (host-only),
     * then `.host` and each parent scope that still has two or more labels.
     * The public suffix itself (`.com`, and a suffix-only `.co.uk`) is never
     * emitted — the browser would reject it anyway.
     *
     * IP literals and single-label hosts have no domain scope: host-only.
     */
    internal fun cookieDomains(host: String?): List<String?> {
        val h = host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return listOf(null)
        val ipLike = h.isEmpty() || h.contains(':') || h.all { it.isDigit() || it == '.' }
        if (ipLike) return listOf(null)
        val labels = h.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return listOf(null)
        val domains = mutableListOf<String?>(null)
        for (i in 0 until labels.size - 1) {
            domains += "." + labels.subList(i, labels.size).joinToString(".")
        }
        return domains
    }

    /** `Set-Cookie` value that deletes [name] in the given scope. */
    internal fun expiryHeader(name: String, domain: String?): String = buildString {
        append(name).append("=; Max-Age=0; Path=/")
        if (domain != null) append("; Domain=").append(domain)
    }

    /**
     * Drop the remembered provider origins' web storage (localStorage,
     * IndexedDB, WebSQL). Cookies alone are not isolation: many SPAs restore
     * a session from storage after the cookie jar is cleared, which is how a
     * "fresh" login can still come back as the previous account. Called when
     * a login round completes (status page reached) and on the next round's
     * start, both on the fallback path only — profiles cover this surface
     * wholesale.
     *
     * `WebStorage.deleteOrigin` is API 29+; on older devices only the cookie
     * sweep applies. Loopback (the panel's own origin) is never touched.
     */
    fun clearProviderStorage(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val origins = rememberedOrigins(context)
        if (origins.isEmpty()) return
        val storage = runCatching { WebStorage.getInstance() }.getOrNull() ?: return
        for (origin in origins) {
            // WebStorage names an origin `scheme://host[:port]`; default
            // ports are usually omitted but a site can record them
            // explicitly, so both spellings are attempted (a wrong one is a
            // harmless no-op).
            runCatching { storage.deleteOrigin(origin) }
            val withPort = origin + if (origin.startsWith("https://")) ":443" else ":80"
            runCatching { storage.deleteOrigin(withPort) }
        }
    }

    private fun rememberedOrigins(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_ORIGINS, emptySet())
            .orEmpty()
}
