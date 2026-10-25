package com.openminis.app.ui.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MirrorDisplayTest {
    @Test
    fun everyCatalogNameAndRegionHasALocalizedString() {
        val names = MirrorCatalog.allMirrors.map { it.name }.distinct()
        val missingNames = names.filter { mirrorNameStringRes(it) == null }
        assertTrue(missingNames.toString(), missingNames.isEmpty())

        val regions = MirrorCatalog.allMirrors.map { it.region }.distinct()
        val missingRegions = regions.filter { mirrorRegionStringRes(it) == null }
        assertTrue(missingRegions.toString(), missingRegions.isEmpty())
    }

    @Test
    fun chineseStringsCoverTheNamesThatUsedToFallThrough() {
        val zh = readZhStrings()
        assertTrue(zh.contains("<string name=\"mirror_name_ustc\">中科大</string>"))
        assertTrue(zh.contains("<string name=\"mirror_name_sjtu\">上海交大</string>"))
        assertTrue(zh.contains("<string name=\"mirror_name_npmmirror\">淘宝 npm</string>"))
        assertNotNull(mirrorNameStringRes("USTC"))
        assertNotNull(mirrorNameStringRes("SJTU"))
        assertNotNull(mirrorNameStringRes("npmmirror"))
        assertEquals(null, mirrorNameStringRes("not-a-mirror"))
    }

    private fun readZhStrings(): String {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir missing"))
        repeat(6) {
            val candidate = File(dir, "src/main/res/values-zh/strings.xml")
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "app/src/main/res/values-zh/strings.xml")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("values-zh/strings.xml not found from ${System.getProperty("user.dir")}")
    }
}
