package com.openminis.app.util

import org.junit.Assert.assertTrue
import org.junit.Test

class IsoTimeTest {

    @Test
    fun formatOffsetIncludesTimezone() {
        val s = IsoTime.formatOffset(1_700_000_000_000L)
        assertTrue(s, s.contains("T"))
        assertTrue(
            s,
            s.endsWith("Z") || Regex("""[+-]\d{2}:\d{2}$""").containsMatchIn(s),
        )
    }

    @Test
    fun formatMinutesIsLocalWallClock() {
        val s = IsoTime.formatMinutes(1_700_000_000_000L)
        assertTrue(s, Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(s))
    }

    @Test
    fun formatUtcSecondsEndsWithZ() {
        val s = IsoTime.formatUtcSeconds(1_700_000_000_000L)
        assertTrue(s, s.endsWith("Z"))
        assertTrue(s, Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(s))
    }

    @Test
    fun localMillisRoundTrip() {
        val ms = 1_700_000_000_123L
        val s = IsoTime.formatLocalMillis(ms)
        val back = IsoTime.parseLocalMillis(s)
        assertTrue(s, s.contains("T"))
        assertTrue("parsed=$back from $s", back != null && kotlin.math.abs(back - ms) < 1000)
    }

    @Test
    fun parseFlexibleAcceptsZuluAndDateOnly() {
        val z = IsoTime.parseFlexible("2023-11-14T22:13:20Z")
        assertTrue("z=$z", z != null)
        val d = IsoTime.parseFlexible("2023-11-14")
        assertTrue("d=$d", d != null)
        val utc = IsoTime.formatUtcSeconds(1_700_000_000_000L)
        val back = IsoTime.parseFlexible(utc)
        assertTrue("utc=$utc back=$back", back != null && kotlin.math.abs(back - 1_700_000_000_000L) < 1000)
    }

    @Test
    fun formatHmsAndCompactAreFixedWidth() {
        val ms = 1_700_000_000_000L
        assertTrue(IsoTime.formatHms(ms), Regex("""\d{2}:\d{2}:\d{2}""").matches(IsoTime.formatHms(ms)))
        assertTrue(IsoTime.formatCompactSeconds(ms), Regex("""\d{8}-\d{6}""").matches(IsoTime.formatCompactSeconds(ms)))
        assertTrue(IsoTime.formatCompactMinutes(ms), Regex("""\d{8}-\d{4}""").matches(IsoTime.formatCompactMinutes(ms)))
        assertTrue(IsoTime.formatPathDate(ms), Regex("""\d{4}/\d{2}/\d{2}""").matches(IsoTime.formatPathDate(ms)))
        val http = IsoTime.formatHttpDate(ms)
        assertTrue(http, http.contains("GMT"))
    }
}
