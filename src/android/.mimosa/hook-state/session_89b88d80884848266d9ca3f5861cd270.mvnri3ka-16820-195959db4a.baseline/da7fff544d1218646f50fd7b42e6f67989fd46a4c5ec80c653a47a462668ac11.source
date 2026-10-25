package com.openminis.app.evolution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionDetectorTest {

    @Test
    fun matchesChineseCorrection() {
        assertTrue(CorrectionDetector.matches("不对，以后都用 bash"))
        assertTrue(CorrectionDetector.matches("记住不要再开 vim"))
        assertNotNull(CorrectionDetector.extractHint("必须用 apt 而不是 yum"))
    }

    @Test
    fun matchesEnglishCorrection() {
        assertTrue(CorrectionDetector.matches("Remember to always use the workspace path"))
        assertTrue(CorrectionDetector.matches("Don't use vim for this"))
        assertTrue(CorrectionDetector.matches("That's wrong, stop doing that"))
    }

    @Test
    fun ignoresOrdinaryChat() {
        assertFalse(CorrectionDetector.matches("帮我列一下目录"))
        assertFalse(CorrectionDetector.matches("I don't know how large the file is"))
        assertFalse(CorrectionDetector.extractHint("hello") != null && CorrectionDetector.matches("hello"))
        assertFalse(CorrectionDetector.matches("hello"))
    }

    @Test
    fun fingerprintIsStable() {
        val a = CorrectionDetector.fingerprint("记住 不要再用 vim")
        val b = CorrectionDetector.fingerprint("记住，不要再用 vim!")
        assertTrue(a == b)
    }
}
