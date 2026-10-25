package com.openminis.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Thread-safe timestamps. [java.text.SimpleDateFormat] is not safe to share
 * on an `object` / companion; hang/crash/config/log/offload paths all run
 * off the main thread.
 */
object IsoTime {

    private val MINUTES: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    private val LOCAL_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    private val UTC_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
    private val LOCAL_TIME_MS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val HMS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val FILE_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault())
    private val COMPACT_MINUTES: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault())
    private val COMPACT_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
    private val PATH_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneId.systemDefault())
    private val MONTH_DAY_HM: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
    private val LOCAL_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val LOCAL_DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val LOCAL_DATE_MINUTE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val OFFSET: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(ZoneId.systemDefault())
    private val HTTP_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC)

    fun formatOffset(ms: Long): String = OFFSET.format(Instant.ofEpochMilli(ms))

    fun formatMinutes(ms: Long): String = MINUTES.format(Instant.ofEpochMilli(ms))

    fun formatLocalSeconds(ms: Long): String = LOCAL_SECONDS.format(Instant.ofEpochMilli(ms))

    /** Config / env-var / backup wire format: UTC seconds with a literal `Z`. */
    fun formatUtcSeconds(ms: Long): String = UTC_SECONDS.format(Instant.ofEpochMilli(ms))

    fun formatLocalDate(ms: Long = System.currentTimeMillis()): String =
        LOCAL_DATE.format(Instant.ofEpochMilli(ms))

    fun formatLocalTimeMillis(ms: Long = System.currentTimeMillis()): String =
        LOCAL_TIME_MS.format(Instant.ofEpochMilli(ms))

    fun formatHms(ms: Long): String = HMS.format(Instant.ofEpochMilli(ms))

    fun formatFileStamp(ms: Long = System.currentTimeMillis()): String =
        FILE_STAMP.format(Instant.ofEpochMilli(ms))

    fun formatCompactMinutes(ms: Long = System.currentTimeMillis()): String =
        COMPACT_MINUTES.format(Instant.ofEpochMilli(ms))

    fun formatCompactSeconds(ms: Long = System.currentTimeMillis()): String =
        COMPACT_SECONDS.format(Instant.ofEpochMilli(ms))

    fun formatPathDate(ms: Long = System.currentTimeMillis()): String =
        PATH_DATE.format(Instant.ofEpochMilli(ms))

    fun formatMonthDayHm(ms: Long): String = MONTH_DAY_HM.format(Instant.ofEpochMilli(ms))

    fun formatLocalMillis(ms: Long): String =
        LOCAL_MILLIS.format(Instant.ofEpochMilli(ms))

    fun formatHttpDate(ms: Long): String = HTTP_DATE.format(Instant.ofEpochMilli(ms))

    fun formatPattern(ms: Long, pattern: String, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(pattern, locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(ms))

    fun parseLocalMillis(s: String): Long? = try {
        Instant.from(LOCAL_MILLIS.parse(s)).toEpochMilli()
    } catch (_: Throwable) {
        null
    }

    fun parseUtcSeconds(s: String): Long? = try {
        Instant.parse(s).toEpochMilli()
    } catch (_: Throwable) {
        try {
            Instant.from(UTC_SECONDS.parse(s)).toEpochMilli()
        } catch (_: Throwable) {
            null
        }
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
