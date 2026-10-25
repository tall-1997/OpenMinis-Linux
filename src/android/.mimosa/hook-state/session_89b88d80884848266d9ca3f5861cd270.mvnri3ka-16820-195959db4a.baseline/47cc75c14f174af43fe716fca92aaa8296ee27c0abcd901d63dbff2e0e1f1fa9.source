package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CaBundleTest {

    @Test
    fun parsePemBlocksSplitsConcatenatedBundle() {
        val a = dummyPem(byteArrayOf(1, 2, 3))
        val b = dummyPem(byteArrayOf(4, 5, 6))
        val blocks = CaBundle.parsePemBlocks(a + "\n" + b)
        assertEquals(2, blocks.size)
        assertEquals(CaBundle.fingerprintPem(a), CaBundle.fingerprintPem(blocks[0]))
        assertEquals(CaBundle.fingerprintPem(b), CaBundle.fingerprintPem(blocks[1]))
    }

    @Test
    fun uniquePemsDropsDuplicateFingerprints() {
        val a = dummyPem(byteArrayOf(9, 8, 7, 6))
        val b = dummyPem(byteArrayOf(1))
        val out = CaBundle.uniquePems(listOf(a, a, b, a))
        assertEquals(2, out.size)
        assertEquals(CaBundle.fingerprintPem(a), CaBundle.fingerprintPem(out[0]))
        assertEquals(CaBundle.fingerprintPem(b), CaBundle.fingerprintPem(out[1]))
    }

    @Test
    fun fingerprintMatchesDerSha256() {
        val der = byteArrayOf(0x30, 0x03, 0x01, 0x02, 0x03)
        val pem = dummyPem(der)
        assertEquals(CaBundle.fingerprintDer(der), CaBundle.fingerprintPem(pem))
        assertNotEquals(CaBundle.fingerprintDer(der), CaBundle.fingerprintDer(byteArrayOf(0)))
    }

    @Test
    fun wrappedPemStillParses() {
        val der = ByteArray(80) { it.toByte() }
        val pem = dummyPem(der)
        assertTrue(pem.lines().any { it.length in 1..64 })
        assertEquals(CaBundle.fingerprintDer(der), CaBundle.fingerprintPem(pem))
    }

    private fun dummyPem(der: ByteArray): String {
        val b64 = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(der)
        return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
    }
}
