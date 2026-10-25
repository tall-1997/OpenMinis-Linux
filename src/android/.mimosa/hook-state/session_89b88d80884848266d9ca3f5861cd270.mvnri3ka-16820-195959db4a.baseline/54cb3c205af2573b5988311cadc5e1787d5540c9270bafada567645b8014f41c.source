package com.openminis.app.browser

import com.openminis.app.util.IsoTime
import java.net.URI
import java.util.Locale

/** Result of encoding one cookie map into a CookieManager.setCookie pair. */
data class EncodedCookie(
    val applyUrl: String,
    val setCookieHeader: String,
    val name: String,
    val domain: String,
)

/**
 * Builds Set-Cookie headers for [android.webkit.CookieManager], including SameSite.
 * Chrome requires Secure when SameSite=None (cross-site captcha / OAuth).
 */
object BrowserCookieCodec {

    fun encode(pageUrl: String, raw: Map<String, Any?>): EncodedCookie? {
        val name = cookieString(raw, "name")?.takeIf { it.isNotEmpty() } ?: return null
        val value = cookieString(raw, "value") ?: return null
        val pageHost = runCatching { URI(pageUrl).host?.lowercase(Locale.US) }.getOrNull().orEmpty()
        if (pageHost.isEmpty()) return null
        val requestedDomain = cookieString(raw, "domain")?.trim()?.lowercase(Locale.US)
        val domain = requestedDomain?.takeIf { isAllowedDomain(pageHost, it) } ?: if (requestedDomain == null) pageHost else return null
        val path = cookieString(raw, "path")?.takeIf { it.isNotEmpty() } ?: "/"
        val sameSite = normalizeSameSite(cookieString(raw, "sameSite", "same_site"))
        var secure = cookieBool(raw, "secure") == true
        if (sameSite == "None") secure = true

        val sb = StringBuilder()
        sb.append(name).append('=').append(value)
        if (domain.isNotEmpty()) sb.append("; Domain=").append(domain)
        sb.append("; Path=").append(path)
        if (secure) sb.append("; Secure")
        if (cookieBool(raw, "http_only", "httpOnly") == true) sb.append("; HttpOnly")
        cookieNumber(raw, "expires", "expirationDate")?.takeIf { it > 0 }?.let { expires ->
            sb.append("; Expires=").append(IsoTime.formatHttpDate(expires.toLong() * 1000L))
        }
        if (sameSite != null) sb.append("; SameSite=").append(sameSite)

        return EncodedCookie(
            applyUrl = cookieApplyUrl(pageUrl, domain, path, secure),
            setCookieHeader = sb.toString(),
            name = name,
            domain = domain,
        )
    }

    internal fun isAllowedDomain(pageHost: String, cookieDomain: String): Boolean {
        val host = pageHost.trim().trimEnd('.').lowercase(Locale.US)
        val domain = cookieDomain.trim().removePrefix(".").trimEnd('.').lowercase(Locale.US)
        if (host.isEmpty() || domain.isEmpty() || domain.contains('/') || domain.contains(':')) return false
        return host == domain || host.endsWith(".$domain")
    }

    fun normalizeSameSite(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return when (raw.trim().lowercase(Locale.US)) {
            "none", "no_restriction" -> "None"
            "lax" -> "Lax"
            "strict" -> "Strict"
            else -> null
        }
    }

    fun cookieApplyUrl(pageUrl: String, domain: String, path: String, secure: Boolean): String {
        val page = runCatching { URI(pageUrl) }.getOrNull()
        val host = domain.removePrefix(".").ifEmpty { page?.host.orEmpty() }
        if (host.isEmpty()) return pageUrl
        val scheme = when {
            secure -> "https"
            page?.scheme.equals("http", ignoreCase = true) -> "http"
            else -> "https"
        }
        val p = if (path.startsWith("/")) path else "/$path"
        return "$scheme://$host$p"
    }

    internal fun cookieValue(raw: Map<String, Any?>, vararg aliases: String): Any? {
        for (key in aliases) raw[key]?.let { return it }
        val lowered = aliases.map { it.lowercase(Locale.US) }.toSet()
        for ((k, v) in raw) if (k.lowercase(Locale.US) in lowered && v != null) return v
        return null
    }

    internal fun cookieString(raw: Map<String, Any?>, vararg aliases: String): String? =
        when (val v = cookieValue(raw, *aliases)) {
            is String -> v
            is Number -> v.toString()
            else -> null
        }

    internal fun cookieBool(raw: Map<String, Any?>, vararg aliases: String): Boolean? =
        when (val v = cookieValue(raw, *aliases)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.lowercase(Locale.US) in setOf("true", "1", "yes")
            else -> null
        }

    internal fun cookieNumber(raw: Map<String, Any?>, vararg aliases: String): Double? =
        when (val v = cookieValue(raw, *aliases)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
}
