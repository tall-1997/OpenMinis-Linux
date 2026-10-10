package com.openminis.app.i18n

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-model-stream-translate] 模型兜底实时翻译器：句级入队、批间 in-flight
 * 合并、flush 等待、失败句静默跳过（与 ML Kit 路径同语义）。
 * UnconfinedTestDispatcher：feed 触发的翻译 job 同步跑完，断言无需调度。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelStreamTranslatorTest {
    @Test
    fun `feed splits sentences and translates asynchronously`() = runTest(UnconfinedTestDispatcher()) {
        val batches = mutableListOf<List<String>>()
        val updates = mutableListOf<String>()
        val t = ModelStreamTranslator(
            scope = this,
            translateBatch = { batch ->
                batches.add(batch)
                batch.map { "[$it]" }
            },
            onUpdate = { updates.add(it) },
        )
        t.feed("Hello world. ")
        t.feed("Second sentence!\n")
        assertEquals(listOf(listOf("Hello world."), listOf("Second sentence!")), batches)
        assertEquals("[Hello world.]\n[Second sentence!]", t.translatedSoFar)
        assertEquals(2, updates.size)
    }

    @Test
    fun `flush translates the trailing fragment and waits`() = runTest(UnconfinedTestDispatcher()) {
        val t = ModelStreamTranslator(
            scope = this,
            translateBatch = { batch -> batch.map { "T:$it" } },
        )
        t.feed("One. Two.")
        assertEquals("T:One.", t.translatedSoFar)
        t.flush("tail without punctuation")
        assertEquals("T:One.\nT:Two.\nT:tail without punctuation", t.translatedSoFar)
    }

    @Test
    fun `failed sentences are skipped silently`() = runTest(UnconfinedTestDispatcher()) {
        val t = ModelStreamTranslator(
            scope = this,
            translateBatch = { batch -> batch.map { null } },
        )
        t.feed("Broken sentence.")
        assertEquals("", t.translatedSoFar)
    }

    @Test
    fun `in-flight batches merge queued sentences`() = runTest(UnconfinedTestDispatcher()) {
        val batches = mutableListOf<List<String>>()
        val t = ModelStreamTranslator(
            scope = this,
            translateBatch = { batch ->
                batches.add(batch)
                batch.map { "[$it]" }
            },
        )
        t.feed("A. B. C.")
        // 行尾 "C." 的句号无空格前瞻（后续 delta 可能续句），流中不切——
        // flush 兜底。所以流中批次是 A/B 两句。
        assertEquals(1, batches.size)
        assertEquals(2, batches[0].size)
        t.flush()
        assertEquals(3, batches.sumOf { it.size })
    }

    @Test
    fun `language catalog localizes and covers common codes`() {
        assertTrue(TranslationLanguages.CODES.contains("en"))
        assertTrue(TranslationLanguages.CODES.contains("zh"))
        assertTrue(TranslationLanguages.CODES.size >= 16)
        TranslationLanguages.CODES.forEach { code ->
            assertTrue("blank name for $code", TranslationLanguages.displayName(code).isNotBlank())
        }
    }
}
