package com.openminis.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * [T-iso8601-serializer-single-source] The timestamp piece of the ISO-8601
 * wire format, sunk from the app's `com.openminis.app.util.IsoTime` so
 * `:core:model` no longer carries a second, drift-prone copy inside
 * [Iso8601MillisSerializer].
 *
 * [parseFlexible] is the FULL superset candidate chain (the app version was a
 * superset of the old core version: it added the `HH:mm:ss`/`HH:mm` local
 * date-time fallbacks and the `yyyy-MM-dd'T'HH:mm:ss.SSS` final candidate).
 * Sinking the superset keeps every string the app side ever accepted
 * parseable — [parseFlexible] here is byte-for-byte the app behavior, and the
 * old 5-candidate core chain is a subset of it.
 *
 * Pure java.time; no org.json involvement.
 */
object IsoTimeCore {

    /** Config / env-var / backup wire format: UTC seconds with a literal `Z`. */
    private val UTC_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    private val LOCAL_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private val LOCAL_DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private val LOCAL_DATE_MINUTE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun formatUtcSeconds(ms: Long): String = UTC_SECONDS.format(Instant.ofEpochMilli(ms))

    fun parseLocalMillis(s: String): Long? = try {
        Instant.from(LOCAL_MILLIS.parse(s)).toEpochMilli()
    } catch (_: Throwable) {
        null
    }

    /**
     * Agent/offload ISO dates: offset, `Z`, local date-time, or date-only.
     * Relative shorthands (`-7d`) stay in the caller.
     */
    fun parseFlexible(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        try {
            return Instant.parse(t).toEpochMilli()
        } catch (_: Throwable) {}
        try {
            return OffsetDateTime.parse(t).toInstant().toEpochMilli()
        } catch (_: Throwable) {}
        try {
            return Instant.from(UTC_SECONDS.parse(t)).toEpochMilli()
        } catch (_: Throwable) {}
        try {
            return LocalDateTime.parse(t, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Throwable) {}
        try {
            return LocalDateTime.parse(t, LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Throwable) {}
        try {
            return LocalDateTime.parse(t, LOCAL_DATE_MINUTE)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Throwable) {}
        try {
            return LocalDate.parse(t).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Throwable) {}
        return parseLocalMillis(t)
    }
}
