package com.openminis.app.provider

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class BundledModelsCatalogTest {
    @Test
    fun gzipCatalogPresentAndPlaintextCopyStaysDeleted() {
        val assets = find("src/android/app/src/main/assets")
            ?: find("app/src/main/assets")
            ?: find("src/main/assets")
            ?: error("assets dir not found from ${System.getProperty("user.dir")}")
        val gz = File(assets, "models-dev-api.json.gzip")
        val plain = File(assets, "models-dev-api.json")
        assertTrue(gz.isFile)
        // [T-apk-shrink] 明文副本（4.1 MB，gzip 成功时永不读取的死 fallback）
        // 已删。重新出现即体积回归——加这道断言防手滑把它再加回来。
        assertTrue("plaintext models-dev-api.json must stay deleted", !plain.isFile)
        val text = GZIPInputStream(gz.inputStream()).bufferedReader().use { it.readText() }
        assertTrue(text.isNotBlank())
        assertTrue(text.trimStart().startsWith("{"))
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
