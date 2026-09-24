package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class BundledModelsCatalogTest {
    @Test
    fun plaintextFallbackMatchesGzipAsset() {
        val assets = find("src/android/app/src/main/assets")
            ?: find("app/src/main/assets")
            ?: find("src/main/assets")
            ?: error("assets dir not found from ${System.getProperty("user.dir")}")
        val gz = File(assets, "models-dev-api.json.gz")
        val plain = File(assets, "models-dev-api.json")
        assertTrue(gz.isFile)
        assertTrue(plain.isFile)
        val fromGz = GZIPInputStream(gz.inputStream()).bufferedReader().use { it.readText() }
        assertEquals(fromGz, plain.readText())
    }

    private fun find(relative: String): File? {
        var dir = File(System.getProperty("user.dir") ?: return null)
        repeat(8) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }
}
