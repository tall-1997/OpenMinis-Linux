package com.openminis.app.harness.runtime

import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-seam-extraction] 接缝契约验收：chunk 流原样穿透、顺序保持、
 * 请求字段不丢。消费方（循环迁移）尚未切换，这里钉的是接缝本身的行为。
 */
class ProviderStreamClientContractTest {

    private class FakeClient(private val chunks: List<LLMStreamChunk>) : ProviderStreamClient {
        var lastRequest: HarnessRoundRequest? = null
        override suspend fun streamRound(request: HarnessRoundRequest): Flow<LLMStreamChunk> {
            lastRequest = request
            return flowOf(*chunks.toTypedArray())
        }
    }

    @Test
    fun `chunks pass through in order`() = runBlocking {
        val client = FakeClient(
            listOf(
                LLMStreamChunk.Started,
                LLMStreamChunk.Text("hel"),
                LLMStreamChunk.Text("lo"),
                LLMStreamChunk.Usage(LLMUsage(inputTokens = 10, outputTokens = 2)),
                LLMStreamChunk.Finished("end_turn"),
            ),
        )
        val seen = client.streamRound(HarnessRoundRequest(messages = emptyList(), maxTokens = 64)).toList()
        assertEquals(5, seen.size)
        assertEquals(LLMStreamChunk.Text("hel"), seen[1])
        assertEquals(LLMStreamChunk.Finished("end_turn"), seen.last())
    }

    @Test
    fun `request fields survive the seam`() = runBlocking {
        val client = FakeClient(emptyList())
        val request = HarnessRoundRequest(messages = emptyList(), systemPrompt = "sys", maxTokens = 128, temperature = 0.2)
        client.streamRound(request).toList()
        assertEquals(request, client.lastRequest)
        assertEquals("sys", client.lastRequest!!.systemPrompt)
        assertEquals(128, client.lastRequest!!.maxTokens)
    }
}
