package com.openminis.app.tools

import okhttp3.Dns
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * URL and DNS SSRF guards for public fetches. [blockedReason] checks schemes
 * and literal hosts; [publicInternetDns] validates the exact addresses OkHttp
 * connects to, including redirects.
 */
object FetchUrlGuard {

    fun blockedReason(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "url is required"
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return "invalid URL"
        }
        val scheme = uri.scheme?.lowercase() ?: return "URL must include a scheme"
        if (scheme != "http" && scheme != "https") {
            return "only http/https URLs are allowed"
        }
        val host = uri.host?.lowercase()?.trim('.') ?: return "URL is missing a host"
        if (uri.rawUserInfo != null || uri.rawFragment != null) return "userinfo and fragments are not allowed"
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost") ||
            host == "0.0.0.0" || host == "::1" || host == "[::1]" ||
            host == "metadata.google.internal" || host.endsWith(".internal") ||
            host.endsWith(".local")
        ) {
            return "blocked host: $host"
        }
        if (isPrivateOrLoopbackIp(host)) return "blocked private/loopback address: $host"
        return null
    }

    internal fun publicInternetDns(): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val resolved = InetAddress.getAllByName(hostname).toList()
            if (resolved.isEmpty() || resolved.any(::isUnsafeAddress)) {
                throw UnknownHostException("Blocked non-public DNS answer for $hostname")
            }
            return resolved
        }
    }

    internal fun isUnsafeAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val bytes = address.address.map { it.toInt() and 0xff }
        if (bytes.size == 4) {
            val a = bytes[0]
            val b = bytes[1]
            val c = bytes[2]
            return a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b in 64..127) ||
                (a == 168 && b == 63 && c == 129) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 192 && b == 0 && c == 0) ||
                (a == 192 && b == 0 && c == 2) ||
                (a == 198 && b in 18..19) ||
                (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113)
        }
        if (bytes.size == 16) {
            val first = bytes[0]
            val second = bytes[1]
            val globalUnicast = (first and 0xe0) == 0x20
            val uniqueLocal = (first and 0xfe) == 0xfc
            val documentation = first == 0x20 && second == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8
            return !globalUnicast || uniqueLocal || documentation
        }
        return true
    }

    internal fun isExplicitPrivateAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isSiteLocalAddress ||
            isPrivateOrLoopbackIp(address.hostAddress.orEmpty())

    internal fun isPrivateOrLoopbackIp(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        if (h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) {
            return h.contains(':')
        }
        val parts = h.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        val a = nums[0]
        val b = nums[1]
        return a == 10 || a == 127 || (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) || (a == 169 && b == 254) || a == 0
    }
}
