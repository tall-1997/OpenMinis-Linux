package com.openminis.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mlkit-model-mgmt] 引擎纯逻辑测试：下载进度文本格式化（复用自 taixu
 * TranslationModelStatus.detailText）与 LRU 翻译缓存淘汰。
 */
class MlKitTranslationEngineTest {

    @Test
    fun `detailText byte mode renders MB pair and percent`() {
        val s = MlKitTranslationEngine.PackState.Downloading(
            progress = 0.5f, step = 1, totalSteps = 2,
            downloadedBytes = 12_582_912L, totalBytes = 29_882_912L,
        )
        // 12582912/1048576 = 12.0 MB; 29882912/1048576 = 28.5 MB; 42%
        assertEquals("12.0 MB / 28.5 MB (42%)", s.detailText)
    }

    @Test
    fun `detailText progress mode renders stage percent`() {
        val s = MlKitTranslationEngine.PackState.Downloading(progress = 0.5f, step = 1, totalSteps = 2)
        assertEquals("50% (第 1/2 阶段)", s.detailText)
    }

    @Test
    fun `detailText connecting mode renders stage only`() {
        val s = MlKitTranslationEngine.PackState.Downloading(progress = null, step = 2, totalSteps = 2)
        assertEquals("第 2/2 阶段 (连接中...)", s.detailText)
    }

    @Test
    fun `translation cache evicts eldest beyond 100 entries`() {
        val cache = MlKitTranslationEngine.translationCache
        cache.clear()
        repeat(101) { i -> cache[i] = "v$i" }
        assertEquals(100, cache.size)
        assertTrue("eldest evicted", !cache.containsKey(0))
        assertTrue("newest kept", cache.containsKey(100))
        cache.clear()
    }
}
