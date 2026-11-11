package com.openminis.app.data.model

import java.util.Locale

/**
 * [GH#520] Bailian (阿里云百炼 / DashScope / Model Studio) base-URL detection.
 *
 * Every "is this a Bailian instance?" check used to spell its own predicate —
 * `base.contains("dashscope")` in the voice factory, the media router, the
 * OpenAI provider and the thinking-rule context. That missed the newer
 * `*.maas.aliyuncs.com` host Bailian hands out for a dedicated workspace, so a
 * voice-enabled instance on it fell through to the generic OpenAI-compatible
 * provider: TTS went to `/v1/audio/speech` and ASR to
 * `/v1/audio/transcriptions`, both of which the compatible-mode gateway does
 * not serve (404) — the transcript never arrived.
 *
 * The maas form is matched on the parsed HOST, not on a substring of the whole
 * URL, so `https://relay.example/maas.aliyuncs.com` or
 * `https://x.maas.aliyuncs.com.evil.example` is not mistaken for Bailian.
 */
object BailianEndpoint {

    private const val MAAS_HOST = "maas.aliyuncs.com"

    /**
     * True when [baseURL] points at Bailian (any region, any workspace host).
     *
     * Legacy `dashscope` matching is deliberately kept on the whole string:
     * users type scheme-less bases ("dashscope.aliyuncs.com/compatible-mode")
     * and relay-style paths, and every one of those worked before. The new
     * maas branch is strict about host labels because "maas.aliyuncs.com" is
     * short enough to be a plausible vanity path.
     */
    fun matches(baseURL: String?): Boolean {
        val raw = baseURL?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val lower = raw.lowercase(Locale.ROOT)
        // Legacy predicate, deliberately on the whole string: scheme-less bases
        // ("dashscope.aliyuncs.com/compatible-mode") and relay-style paths were
        // accepted before this helper existed.
        if (lower.contains("dashscope")) return true
        val host = hostOf(lower) ?: return false
        return host == MAAS_HOST || host.endsWith(".$MAAS_HOST")
    }

    /**
     * Host part of [baseURL] with scheme, userinfo, port and path removed.
     * Kept dependency-free (no android.net.Uri) so :core:model stays plain
     * Kotlin; also tolerates a scheme-less base.
     */
    fun hostOf(baseURL: String?): String? {
        val raw = baseURL?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var rest = raw.substringAfter("://", raw)
        rest = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        rest = rest.substringAfterLast('@')
        if (rest.startsWith("[")) {
            // IPv6 literal: [::1]:8080 -> ::1
            return rest.substringBefore(']').removePrefix("[").lowercase(Locale.ROOT)
                .takeIf { it.isNotEmpty() }
        }
        rest = rest.substringBefore(':')
        return rest.trimEnd('.').lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
    }
}
