package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenSslSubjectHashTest {
    @Test
    fun printableCnUsesUtf8SetWithoutOuterSequence() {
        // Name: CN=Example.COM  (PrintableString)
        val subject = hex(
            "30 16 31 14 30 12 06 03 55 04 03 13 0b 45 78 61 6d 70 6c 65 2e 43 4f 4d",
        )
        val canon = OpenSslSubjectHash.canonicalSubject(subject)
        // SET { SEQUENCE { OID CN, UTF8String "example.com" } }
        val expected = hex(
            "31 14 30 12 06 03 55 04 03 0c 0b 65 78 61 6d 70 6c 65 2e 63 6f 6d",
        )
        assertEquals(expected.toList(), canon.toList())
        assertEquals("9cef4dea", OpenSslSubjectHash.newHash(subject))
    }

    @Test
    fun oldHashIsMd5OfSubjectDer() {
        val subject = hex("30 00")
        assertEquals("543b6ca4", OpenSslSubjectHash.oldHash(subject))
    }

    @Test
    fun asciiCanonCollapsesSpacesAndLowersAsciiOnly() {
        val out = OpenSslSubjectHash.asciiCanon("  A  B\tC ".toByteArray())
        assertEquals("a b c", String(out))
    }

    private fun hex(s: String): ByteArray =
        s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
}
