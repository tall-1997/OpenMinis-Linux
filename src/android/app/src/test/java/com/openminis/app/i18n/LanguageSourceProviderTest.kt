package com.openminis.app.i18n

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-lang-source-provider] 数据源测试：stale 首帧 → refresh 收窄、
 * fallbackReachable 全局开关、visible 三 flag 组合判定。全注入无 Android
 * 依赖，JVM 直测。
 */
class LanguageSourceProviderTest {

    @Test
    fun `stale first frame emits static catalog with downloaded false`() = runTest {
        val codes = TranslationLanguages.CODES
        val unsupported = codes.last() // 目录内真实一项被判定为 ML Kit 不支持
        val src = MlKitLanguageSource(
            isSupported = { it != unsupported },
        )
        // 首帧：CODES 全集、mlKitReady 按静态判定、downloaded 全 false。
        assertEquals(codes.size, src.languages.value.size)
        assertTrue(src.languages.value.all { !it.downloaded })
        assertTrue(src.languages.value.first { it.code == codes.first() }.mlKitReady)
        assertFalse(src.languages.value.first { it.code == unsupported }.mlKitReady)
    }

    @Test
    fun `refresh narrows downloaded from live query`() = runTest {
        val src = MlKitLanguageSource(
            queryDownloaded = { setOf("en", "ja") },
        )
        src.refresh()
        val en = src.languages.value.first { it.code == "en" }
        val ja = src.languages.value.first { it.code == "ja" }
        val zh = src.languages.value.first { it.code == "zh" }
        assertTrue(en.downloaded)
        assertTrue(ja.downloaded)
        assertFalse(zh.downloaded)
    }

    @Test
    fun `refresh query failure keeps stale snapshot`() = runTest {
        val src = MlKitLanguageSource(
            queryDownloaded = { throw IllegalStateException("no gms") },
        )
        src.refresh()
        assertTrue(src.languages.value.all { !it.downloaded })
    }

    @Test
    fun `fallbackReachable is a single global flag`() = runTest {
        var reachable = true
        val src = MlKitLanguageSource(probeFallback = { reachable })
        assertEquals(true, src.fallbackReachable.value)
        reachable = false
        src.refresh()
        assertEquals(false, src.fallbackReachable.value)
    }

    @Test
    fun `visible requires any of three live flags`() {
        val f = LanguageSourceProvider.LanguageStatus::visible
        // mlKitReady 单真即可见。
        assertTrue(f(LanguageSourceProvider.LanguageStatus("en", true, false), false))
        // downloaded 单真即可见（ML Kit 不支持但已下——防御性）。
        assertTrue(f(LanguageSourceProvider.LanguageStatus("xx", false, true), false))
        // 全局兜底可达：任何语言可见。
        assertTrue(f(LanguageSourceProvider.LanguageStatus("xx", false, false), true))
        // 三 flag 全假：不可见。
        assertFalse(f(LanguageSourceProvider.LanguageStatus("xx", false, false), false))
    }

    // ---- [T-lang-picker] 下拉可见集纯函数 ----

    private fun st(code: String, mlKit: Boolean = false, dl: Boolean = false) =
        LanguageSourceProvider.LanguageStatus(code, mlKit, dl)

    @Test
    fun `visibleCodes filters by live flags and keeps catalog order`() {
        val list = listOf(st("en", mlKit = true), st("zh", dl = true), st("ja"))
        assertEquals(listOf("en", "zh"), list.visibleCodes(fallbackReachable = false, selected = "en"))
    }

    @Test
    fun `visibleCodes keeps invisible selection appended at end`() {
        // 兜底下线后原语言对失去可见性，但下拉仍要显示「当前选的是什么」。
        val list = listOf(st("en", mlKit = true), st("xx"))
        assertEquals(listOf("en", "xx"), list.visibleCodes(fallbackReachable = false, selected = "xx"))
    }

    @Test
    fun `visibleCodes lets the global fallback reveal everything`() {
        val list = listOf(st("en"), st("ja"))
        assertEquals(listOf("en", "ja"), list.visibleCodes(fallbackReachable = true, selected = "en"))
    }

    @Test
    fun `visibleCodes excludes the other side of the pair`() {
        val list = listOf(st("en", mlKit = true), st("zh", mlKit = true))
        // 目标下拉排除源语言：源=目标翻译无意义。
        assertEquals(listOf("zh"), list.visibleCodes(fallbackReachable = false, selected = "zh", exclude = "en"))
        // 选中项正好等于 exclude 时也不追加。
        assertEquals(listOf("zh"), list.visibleCodes(fallbackReachable = false, selected = "en", exclude = "en"))
    }

    @Test
    fun `visibleCodes trims codes and drops blank selection`() {
        val list = listOf(st(" en ", mlKit = true))
        assertEquals(listOf("en"), list.visibleCodes(fallbackReachable = false, selected = "  "))
    }
}
