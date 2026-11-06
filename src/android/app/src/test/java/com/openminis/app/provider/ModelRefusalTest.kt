// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream
// (provider/ModelRefusalTest.kt), adapted: this tree's agent loop lives in
// ChatViewModelAgentLoopExt.kt, the local model constant is claudeSonnet5,
// and the locale check covers the two maintained resource roots.
package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.ui.chat.EmptyTurnRecovery
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-model-refusal] A model DECLINING a request (Anthropic
 * stop_reason=refusal, OpenAI content_filter) is surfaced on the reply instead
 * of ending the run silently. Port of iOS 54c8ea2a4.
 */
class ModelRefusalTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun anthropic(model: LLMModel = LLMModel.claudeSonnet5) =
        AnthropicProvider(apiKey = "k", model = model, basePath = server.url("/").toString().trimEnd('/'))

    // ---- provider: the refusal arrives as a distinct stop reason ------------

    @Test
    fun `the reported stream - refusal with empty content - finishes as refusal, not a dropped stream`() = runBlocking {
        val sse = buildString {
            appendLine("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":24989}}}")
            appendLine()
            appendLine("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"},\"usage\":{\"output_tokens\":0}}")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val chunks = anthropic().streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 1024).toList()
        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals(listOf("refusal"), finished.map { it.stopReason })
        assertTrue("no content", chunks.none { it is LLMStreamChunk.Text })
    }

    @Test
    fun `a refusal is not a silently truncated stream - no transient retry`() = runBlocking {
        val out = flowOf<LLMStreamChunk>(LLMStreamChunk.Finished("refusal"))
            .failOnSilentEmptyCompletion("test").toList()
        assertEquals(1, out.size)
    }

    // ---- the mapping --------------------------------------------------------

    @Test
    fun `refusal spellings`() {
        assertTrue(StopReasons.isRefusal("refusal"))        // Anthropic
        assertTrue(StopReasons.isRefusal("content_filter")) // OpenAI chat + Responses incomplete
        for (r in listOf(null, "", "stop", "end_turn", "length", "max_tokens", "tool_use", "tool_calls", "SAFETY")) {
            assertFalse("$r is not a refusal", StopReasons.isRefusal(r))
        }
    }

    @Test
    fun `a refusal is never classified as an empty turn - no nudge, no retry round`() {
        for (r in listOf("refusal", "content_filter")) {
            assertFalse(r, EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = false, stopReason = r))
        }
    }

    // ---- the loop-end branch --------------------------------------------------

    @Test
    fun `the loop surfaces the refusal on the reply without resume, before any empty-turn handling`() {
        val loop = ProductionSources.read("ui/chat/ChatViewModelAgentLoopExt.kt")
        val branch = loop.substringAfter("if (com.openminis.app.provider.StopReasons.isRefusal(turnFinishReason)) {")
            .substringBefore("\n            }\n")
        assertTrue("logged", branch.contains("model declined the request"))
        assertTrue(
            "shown on the reply",
            branch.contains("setInlineError(refusalText)") &&
                branch.contains("R.string.chat_error_model_refused_category") &&
                branch.contains("genericMessage = context.getString(R.string.chat_error_model_refused)"),
        )
        assertTrue("no resume affordance", branch.contains("_canResume.value = false") && !branch.contains("_canResume.value = true"))
        assertTrue("ends the run deliberately", branch.contains("loopExitedNormally = true") && branch.contains("break"))
        val refusalAt = loop.indexOf("if (com.openminis.app.provider.StopReasons.isRefusal(turnFinishReason)) {")
        val emptyAt = loop.indexOf("val isEmptyTurn = EmptyTurnRecovery.shouldClassifyAsEmptyTurn(")
        assertTrue("the refusal branch runs before the empty-turn path", refusalAt in 0 until emptyAt)
    }

    @Test
    fun `the message exists in the maintained locales`() {
        val res = listOf("src/main/res", "app/src/main/res", "src/android/app/src/main/res")
            .map { java.io.File(it) }.first { it.exists() }
        // Live in the split file (strings_wave.xml) so the L10n gate does not
        // demand a 14-locale sweep for a new string.
        for (d in listOf("values", "values-zh")) {
            assertTrue(d, java.io.File(res, "$d/strings_wave.xml").readText().contains("name=\"chat_error_model_refused\""))
        }
        assertTrue(
            java.io.File(res, "values/strings_wave.xml").readText().contains(
                ">The model declined to respond to this request. Try rephrasing, or switch to a different model.<",
            ),
        )
    }
}
