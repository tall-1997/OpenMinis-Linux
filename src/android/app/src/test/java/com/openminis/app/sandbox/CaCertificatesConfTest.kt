package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CaCertificatesConfTest {
    @Test
    fun rewriteReplacesHostBlockAndDropsStaleLines() {
        val existing = """
            # mozilla
            mozilla/ISRG_Root_X1.crt
            minis-android/stale.crt
            ${CaBundle.HOST_CA_CONF_BEGIN}
            minis-android/old.crt
            ${CaBundle.HOST_CA_CONF_END}
            mozilla/DigiCert.crt
        """.trimIndent() + "\n"
        val out = CaBundle.rewriteCaCertificatesConf(
            existing,
            listOf("minis-android/bbbb.crt", "minis-android/aaaa.crt", "minis-android/aaaa.crt"),
        )
        assertTrue(out.contains("mozilla/ISRG_Root_X1.crt"))
        assertTrue(out.contains("mozilla/DigiCert.crt"))
        assertFalse(out.contains("stale.crt"))
        assertFalse(out.contains("old.crt"))
        val begin = out.indexOf(CaBundle.HOST_CA_CONF_BEGIN)
        val end = out.indexOf(CaBundle.HOST_CA_CONF_END)
        val block = out.substring(begin, end)
        assertTrue(block.indexOf("aaaa.crt") < block.indexOf("bbbb.crt"))
        assertEquals(1, block.split("aaaa.crt").size - 1)
    }

    @Test
    fun mozillaScanIgnoresHostExtraDirectory() {
        val root = File.createTempFile("ca-tree", "").apply {
            delete()
            mkdirs()
        }
        try {
            val mozilla = File(root, "mozilla/ISRG.crt")
            mozilla.parentFile?.mkdirs()
            mozilla.writeText(pem("AAAA"))
            val host = File(root, "minis-android/host.crt")
            host.parentFile?.mkdirs()
            host.writeText(pem("BBBB"))
            val loaded = CaBundle.loadPemsFromTree(root, excludeDirNames = setOf("minis-android"))
            assertEquals(1, loaded.size)
            assertTrue(loaded[0].contains("AAAA"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun pem(body: String): String =
        "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
}
