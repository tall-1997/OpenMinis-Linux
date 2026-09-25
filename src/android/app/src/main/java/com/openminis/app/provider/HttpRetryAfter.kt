package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.random.Random

/**
 * Shared 429 Retry-After parsing, capacity-body classification, secret
 * redaction, and bounded exponential backoff for model calls.
 */
object HttpRetryAfter {
    const val DEFAULT_MAX_RETRIES = 5
    val DELAYS_SEC = intArrayOf(1, 2, 4, 8, 16)

    /**
     * [T-android-retryafter-honor] Upper bound for an EXPLICIT Retry-After value
     * (integer seconds or HTTP-date). The old blanket 120s clamp turned a
     * day-quota `Retry-After: 86400` into a 2-minute re-hammer; explicit
     * server-provided values are now honored up to one hour, while the
     * ladder-only path keeps its old 120s ceiling.
     */
    const val MAX_HONORED_RETRY_AFTER_SEC = 3600

    private const val LADDER_MAX_SEC = 120

    fun parseSeconds(header: String?, body: String? = null): Int? {
        header?.trim()?.takeIf { it.isNotEmpty() }?.let { h ->
            h.toIntOrNull()?.let { return it.coerceIn(1, MAX_HONORED_RETRY_AFTER_SEC) }
            httpDateSeconds(h)?.let { return it }
        }
        if (body.isNullOrBlank()) return null
        val match = RETRY_AFTER_IN_BODY.find(body) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
            ?.coerceIn(1, MAX_HONORED_RETRY_AFTER_SEC)
    }

    /**
     * RFC 7231 HTTP-date form (`Retry-After: Wed, 21 Oct 2026 07:28:00 GMT`).
     * Returns seconds-until, clamped to [1, MAX_HONORED_RETRY_AFTER_SEC]; a
     * past date means "retry now" → 1. Non-date strings return null.
     */
    private fun httpDateSeconds(value: String): Int? {
        return try {
            // Some proxies emit an incorrect weekday token. RFC recipients
            // should still honor the absolute date rather than discard it.
            val withoutWeekday = value.replace(Regex("^[A-Za-z]{3},\\s*"), "")
            val formatter = DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss z", Locale.US)
            val whenUtc = ZonedDateTime.parse(withoutWeekday, formatter).toInstant()
            Duration.between(Instant.now(), whenUtc).seconds.toInt().coerceIn(1, MAX_HONORED_RETRY_AFTER_SEC)
        } catch (_: DateTimeParseException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    fun delaySeconds(
        attemptZeroBased: Int,
        retryAfterSeconds: Int? = null,
        schedule: IntArray = DELAYS_SEC,
    ): Int {
        val backoff = when {
            schedule.isEmpty() -> 2
            attemptZeroBased < 0 -> schedule.first()
            attemptZeroBased < schedule.size -> schedule[attemptZeroBased]
            else -> {
                val extraSteps = (attemptZeroBased - schedule.lastIndex).coerceAtMost(30)
                (schedule.last().toLong() shl extraSteps)
                    .coerceAtMost(LADDER_MAX_SEC.toLong())
                    .toInt()
            }
        }
        return if (retryAfterSeconds != null) {
            // Explicit server value: honor it beyond the ladder ceiling.
            maxOf(backoff, retryAfterSeconds).coerceIn(1, MAX_HONORED_RETRY_AFTER_SEC)
        } else {
            backoff.coerceIn(1, LADDER_MAX_SEC)
        }
    }

    fun jitterMs(maxExclusive: Long = 800L): Long =
        if (maxExclusive <= 1L) 0L else Random.nextLong(0, maxExclusive)

    /**
     * Relay 429s are often permanent (quota, no channel, unknown model) rather
     * than a 60s window. Those must not be retried on the same key.
     *
     * [T-android-capacity-tiering] Markers are split into STRONG (unambiguous
     * capacity/quota statements — always permanent) and WEAK (short phrases
     * that can also appear inside transient rate-limit copy — treated as
     * permanent only while another group member exists to fall back to; see
     * the ChatViewModel classification site). This keeps "余额不足" from
     * burning the whole ladder on the last candidate while still switching
     * away immediately when a sibling is available.
     */
    fun isStrongCapacityBody(body: String): Boolean =
        containsAny(body, STRONG_CAPACITY_MARKERS)

    fun isWeakCapacityBody(body: String): Boolean =
        containsAny(body, WEAK_CAPACITY_MARKERS)

    fun isPermanentCapacityBody(body: String): Boolean =
        isStrongCapacityBody(body) || isWeakCapacityBody(body)

    private fun containsAny(body: String, markers: List<String>): Boolean {
        if (body.isBlank()) return false
        val t = body.lowercase()
        return markers.any { t.contains(it) }
    }

    /**
     * [T-android-429-redact] UI-facing 429 snippets may echo secrets when a
     * relay reflects the Authorization value or a long credential in its error
     * body. Mask the common shapes before the string reaches a banner.
     */
    fun redactSecrets(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = SK_TOKEN.replace(out, "sk-***")
        out = BEARER_TOKEN.replace(out, "Bearer ***")
        out = QUERY_KEY_VALUE.replace(out, "$1=***")
        out = LONG_HEX.replace(out) { m -> m.value.take(4) + "***" }
        return out
    }

    fun map429(body: String, retryAfterHeader: String? = null): LLMError {
        val err = if (isStrongCapacityBody(body)) {
            LLMError.ProviderError("[429] ${snippet(body, 240)}")
        } else {
            LLMError.RateLimited(parseSeconds(retryAfterHeader, body), snippet(body, 160))
        }
        android.util.Log.w(
            "Minis.HTTP",
            "HTTP 429 → ${err.javaClass.simpleName}: ${err.message}",
        )
        return err
    }

    private fun snippet(body: String, max: Int): String =
        redactSecrets(
            body.trim().replace('\n', ' ').replace('\r', ' ').take(max * 2),
        ).take(max)

    /** Unambiguous capacity/quota statements — permanent in every context. */
    private val STRONG_CAPACITY_MARKERS = listOf(
        "no_available_providers",
        "no available providers",
        "no available channel",
        "no available channels",
        "model_not_found",
        "model not found",
        "insufficient_quota",
        "insufficient quota",
        "quota exceeded",
        "quota_exceeded",
        "payment required",
        "无可用渠道",
        "负载已饱和",
    )

    /**
     * Short phrases that also occur inside transient rate-limit copy — only
     * treated as permanent while a fallback sibling exists (caller decides).
     */
    private val WEAK_CAPACITY_MARKERS = listOf(
        "no available",
        "billing",
        "无可用",
        "额度",
        "余额",
    )

    private val RETRY_AFTER_IN_BODY =
        Regex("""(?i)retry[_-]?after["'\s:=]+(\d+)""")

    private val SK_TOKEN = Regex("""sk-[A-Za-z0-9_-]{8,}""")
    private val BEARER_TOKEN = Regex("""(?i)bearer\s+[A-Za-z0-9._-]{8,}""")
    private val QUERY_KEY_VALUE = Regex("""(?i)(api[_-]?key|access[_-]?token|token|key)\s*[=:]\s*[^\s&"',}]{8,}""")
    private val LONG_HEX = Regex("""[A-Fa-f0-9]{32,}""")
}
