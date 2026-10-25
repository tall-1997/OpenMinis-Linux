package com.openminis.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the two shipped seeds against regressing to the states users
 * actually complained about: an effectively-empty GLOBAL.md (placeholder
 * bullets only) and an English default persona on a product whose users are
 * overwhelmingly Chinese.
 */
class DefaultSeedsTest {
    private fun assets(): File {
        var dir = File(System.getProperty("user.dir") ?: error("no user.dir"))
        repeat(8) {
            val candidate = File(dir, "src/android/app/src/main/assets")
            if (candidate.isDirectory) return candidate
            val alt = File(dir, "src/main/assets")
            if (alt.isDirectory) return alt
            dir = dir.parentFile ?: error("assets dir not found")
        }
        error("assets dir not found")
    }

    @Test
    fun globalRulesSeedCarriesRealRulesNotPlaceholders() {
        val text = File(assets(), "default_global.md").readText()
        assertFalse("placeholder bullets must not ship", text.contains("默认占位"))
        assertTrue(text.contains("## 通用"))
        assertTrue(text.contains("## 工具使用"))
        assertTrue(text.contains("## 产物"))
        assertTrue(
            "expected a real rule list, got ${text.lines().count { it.startsWith("- ") }} bullets",
            text.lines().count { it.startsWith("- ") } >= 9,
        )
    }

    @Test
    fun soulSeedBodyIsChinese() {
        val text = File(assets(), "default_soul.md").readText()
        val body = text.substringAfter("---\n").substringAfter("---\n")
        val cjk = body.count { it in '一'..'龥' }
        assertTrue("default persona body should be Chinese, cjk chars=$cjk", cjk > 200)
        // The voice rules must survive translation.
        assertTrue(body.contains("不表演"))
        assertTrue(body.contains("有立场"))
    }

    @Test
    fun previousEnglishSoulIsRegisteredForUpgradeReplacement() {
        assertTrue(SoulStore.shouldReplaceSoulOnUpgrade(PREVIOUS_DEFAULT_SOUL))
        assertFalse(
            "a customised soul must never be clobbered",
            SoulStore.shouldReplaceSoulOnUpgrade(PREVIOUS_DEFAULT_SOUL + "\nmy own rule"),
        )
        assertFalse(SoulStore.shouldReplaceSoulOnUpgrade(null))
    }
}
