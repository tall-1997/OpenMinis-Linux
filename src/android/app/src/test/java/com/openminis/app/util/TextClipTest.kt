package com.openminis.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-safe-clip] Code-point-safe truncation for persisted/model-facing text. */
class TextClipTest {

    @Test
    fun zeroLimitIsEmptyNotEllipsis() {
        assertEquals("", TextClip.safeEllipsize("abc", 0))
        assertEquals("", TextClip.safeTake("abc", 0))
    }

    @Test
    fun neverSplitsASurrogatePair() {
        val text = "x" + "😀".repeat(10)
        for (limit in 1..text.length) {
            val cut = TextClip.safeTake(text, limit)
            if (cut.isNotEmpty()) {
                assertFalse("limit=$limit ends high", Character.isHighSurrogate(cut.last()))
            }
            for (i in cut.indices) {
                if (Character.isHighSurrogate(cut[i])) {
                    assertTrue(i + 1 < cut.length && Character.isLowSurrogate(cut[i + 1]))
                }
            }
        }
    }

    @Test
    fun ellipsizeKeepsLimitPlusOne() {
        val out = TextClip.safeEllipsize("abcdef", 3)
        assertEquals("abc…", out)
        assertEquals("abcdef", TextClip.safeEllipsize("abcdef", 6))
    }
}
