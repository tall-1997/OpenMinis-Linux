package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLearnerParserTest {

    @Test
    fun `parseTokenSize handles M K and 暂无数据`() {
        assertEquals(1_050_000, DataLearnerParser.parseTokenSize("1.05M tokens"))
        assertEquals(128_000, DataLearnerParser.parseTokenSize("128K tokens"))
        assertEquals(1_000_000, DataLearnerParser.parseTokenSize("1000K tokens"))
        assertEquals(1_050_000, DataLearnerParser.parseTokenSize("1,050,000"))
        assertNull(DataLearnerParser.parseTokenSize("暂无数据"))
        assertNull(DataLearnerParser.parseTokenSize(""))
    }

    @Test
    fun `pickSearchHit prefers exact code over pro sibling`() {
        val hits = listOf(
            DataLearnerParser.SearchHit("gpt-6-astra-pro", "GPT-6 Astra Pro"),
            DataLearnerParser.SearchHit("gpt-6-astra", "GPT-6 Astra"),
        )
        assertEquals(
            "gpt-6-astra",
            DataLearnerParser.pickSearchHit("gpt-6-astra", "GPT-6 Astra", hits)?.modelCode,
        )
        assertEquals(
            "gpt-6-astra-pro",
            DataLearnerParser.pickSearchHit("gpt-6-astra-pro", "GPT-6 Astra Pro", hits)?.modelCode,
        )
    }

    @Test
    fun `pickSearchHit does not promote instant or pro for base gpt-5-5`() {
        val hits = listOf(
            DataLearnerParser.SearchHit("gpt-5-5-pro", "GPT-5.5 Pro"),
            DataLearnerParser.SearchHit("gpt-5-5", "GPT-5.5"),
            DataLearnerParser.SearchHit("gpt-5-5-instant", "GPT-5.5 Instant"),
        )
        assertEquals(
            "gpt-5-5",
            DataLearnerParser.pickSearchHit("gpt-5-5", "GPT-5.5", hits)?.modelCode,
        )
    }

    @Test
    fun `parseSearchHits reads model_code aliases`() {
        val json = """
            {"models":[
              {"model_code":"gpt-6-astra","model_abbr_name":"GPT-6 Astra",
               "aliases":["GPT-6"],"reasoningModel":1}
            ]}
        """.trimIndent()
        val hits = DataLearnerParser.parseSearchHits(json)
        assertEquals(1, hits.size)
        assertEquals("gpt-6-astra", hits[0].modelCode)
        assertEquals(listOf("GPT-6"), hits[0].aliases)
        assertEquals(1, hits[0].reasoningModel)
    }

    @Test
    fun `parseDetail reads basic-info and escaped thinkingModes`() {
        val html = """
            <div>上下文长度</div><div class="v">1.05M tokens</div>
            <div>最大输出长度</div><div class="v">128K tokens</div>
            <div>推理过程</div><div class="v">支持</div>
            self.__next_f.push([1,"{\"thinkingModes\":[{\"id\":1,\"modeKey\":\"low\",\"isDefault\":0},{\"id\":2,\"modeKey\":\"medium\",\"isDefault\":1},{\"id\":3,\"modeKey\":\"high\"},{\"id\":4,\"modeKey\":\"xhigh\"},{\"id\":5,\"modeKey\":\"max\"}]}"])
        """.trimIndent()
        val detail = DataLearnerParser.parseDetail(html)!!
        assertEquals(1_050_000, detail.contextWindow)
        assertEquals(128_000, detail.maxOutputTokens)
        assertEquals(true, detail.supportsReasoning)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), detail.reasoningEffortValues)
    }

    @Test
    fun `shouldLookup skips ollama tags and gguf files`() {
        assertTrue(DataLearnerParser.shouldLookup("gpt-6-astra"))
        assertTrue(DataLearnerParser.shouldLookup("openai/gpt-5.5"))
        assertFalse(DataLearnerParser.shouldLookup("llama3.1:8b"))
        assertFalse(DataLearnerParser.shouldLookup("model.gguf"))
        assertFalse(DataLearnerParser.shouldLookup("ab"))
    }

    @Test
    fun `needsSupplement is true when context or max-output is missing`() {
        assertTrue(DataLearnerParser.needsSupplement(null, null))
        assertTrue(DataLearnerParser.needsSupplement(128_000, null))
        assertTrue(DataLearnerParser.needsSupplement(null, 16_384))
        assertFalse(DataLearnerParser.needsSupplement(128_000, 16_384))
    }

    @Test
    fun `searchQueries prefer display name then id tail`() {
        assertEquals(
            listOf("GPT-6 Astra", "gpt-6-astra"),
            DataLearnerParser.searchQueries("openai/gpt-6-astra", "GPT-6 Astra"),
        )
        assertEquals(
            listOf("gpt-5.5", "gpt-5-5"),
            DataLearnerParser.searchQueries("gpt-5-5", "gpt-5.5"),
        )
    }
}
