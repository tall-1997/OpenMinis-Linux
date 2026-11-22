package com.openminis.app.provider

import com.openminis.app.data.model.AssistantTextPhase.COMMENTARY
import com.openminis.app.data.model.AssistantTextPhase.FINAL_ANSWER
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-responses-missing-finished] The Responses API terminates a stream
 * with `response.completed` and, on many relays, no `data: [DONE]` sentinel
 * afterwards — the socket just closes.
 *
 * Finished used to be emitted ONLY from the [DONE] branch, so those streams
 * produced no terminal chunk at all. ChatViewModel.runAgentLoop assigns
 * turnFinishReason exclusively from Finished, so it stayed null and a complete
 * reply was surfaced as "连接中断，此回复可能不完整".
 *
 * The distinction these tests pin down is the whole fix: a stream that SAW a
 * finish reason must report one even without [DONE], while a stream that never
 * saw one must still report nothing — that null is how a real truncation is
 * detected ([T-android-silent-stream-drop], see StreamDropNoFinishTest).
 */
class ResponsesApiFinishedTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    /** Streams against a provider configured for /v1/responses. */
    private fun responsesChunks(body: String): List<LLMStreamChunk> {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/v1").toString().trimEnd('/'),
            useResponsesAPI = true,
        )
        return runBlocking {
            provider.streamMessageClamped(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
                systemPrompt = null,
                maxTokens = 256,
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.OFF,
            ).toList()
        }
    }

    @Test
    fun `interleaved assistant items keep their own text phases`() {
        val chunks = responsesChunks("""
            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_work","type":"message","role":"assistant","phase":"commentary"}}

            data: {"type":"response.output_text.delta","item_id":"msg_work","output_index":0,"delta":"Checking "}

            data: {"type":"response.output_item.added","output_index":1,"item":{"id":"msg_answer","type":"message","role":"assistant","phase":"final_answer"}}

            data: {"type":"response.output_text.delta","item_id":"msg_answer","output_index":1,"delta":"All clear."}

            data: {"type":"response.output_text.delta","item_id":"msg_work","delta":"now."}

            data: {"type":"response.output_item.done","output_index":0,"item":{"id":"msg_work","type":"message","role":"assistant","phase":"commentary"}}

            data: {"type":"response.completed","response":{"status":"completed"}}

        """.trimIndent() + "\n")

        assertEquals(
            listOf(
                LLMStreamChunk.Text("Checking ", COMMENTARY, "msg_work"),
                LLMStreamChunk.Text("All clear.", FINAL_ANSWER, "msg_answer"),
                LLMStreamChunk.Text("now.", COMMENTARY, "msg_work"),
            ),
            chunks.filterIsInstance<LLMStreamChunk.Text>(),
        )
        assertTrue(chunks.none { it is LLMStreamChunk.TextPhase })
    }

    @Test
    fun `missing and unknown assistant phases stay null`() {
        val chunks = responsesChunks("""
            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_missing","type":"message","role":"assistant"}}

            data: {"type":"response.output_text.delta","item_id":"msg_missing","delta":"A"}

            data: {"type":"response.output_item.added","output_index":1,"item":{"id":"msg_future","type":"message","role":"assistant","phase":"future_phase"}}

            data: {"type":"response.output_text.delta","item_id":"msg_future","output_index":1,"delta":"B"}

            data: {"type":"response.output_item.added","output_index":2,"item":{"id":"msg_user","type":"message","role":"user","phase":"final_answer"}}

            data: {"type":"response.output_text.delta","item_id":"msg_user","output_index":2,"delta":"C"}

            data: {"type":"response.output_text.delta","item_id":null,"output_index":null,"delta":"D"}

            data: {"type":"response.completed","response":{"status":"completed"}}

        """.trimIndent() + "\n")

        assertEquals(
            listOf(
                LLMStreamChunk.Text("A", itemId = "msg_missing"),
                LLMStreamChunk.Text("B", itemId = "msg_future"),
                LLMStreamChunk.Text("C", itemId = "msg_user"),
                LLMStreamChunk.Text("D"),
            ),
            chunks.filterIsInstance<LLMStreamChunk.Text>(),
        )
        assertTrue(chunks.none { it is LLMStreamChunk.TextPhase })
    }

    @Test
    fun `phase known only at item done updates metadata without replaying text`() {
        val chunks = responsesChunks("""
            data: {"type":"response.output_text.delta","item_id":"msg_late","delta":"All "}

            data: {"type":"response.output_text.delta","item_id":"msg_late","delta":"clear."}

            data: {"type":"response.output_text.done","item_id":"msg_late","text":"All clear."}

            data: {"type":"response.output_item.done","item":{"id":"msg_late","type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":"All clear."}]}}

            data: {"type":"response.output_item.done","item":{"id":"msg_late","type":"message","role":"assistant","phase":"final_answer"}}

            data: {"type":"response.completed","response":{"status":"completed"}}

        """.trimIndent() + "\n")

        assertEquals(
            listOf(
                LLMStreamChunk.Started,
                LLMStreamChunk.Text("All ", itemId = "msg_late"),
                LLMStreamChunk.Text("clear.", itemId = "msg_late"),
                LLMStreamChunk.TextPhase("msg_late", FINAL_ANSWER),
                LLMStreamChunk.Finished("stop"),
            ),
            chunks,
        )
    }

    @Test
    fun `output index fallback preserves an emitted key when a wire id arrives late`() {
        val chunks = responsesChunks("""
            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_work","type":"message","role":"assistant","phase":"commentary"}}

            data: {"type":"response.output_text.delta","output_index":0,"delta":"Checking."}

            data: {"type":"response.output_item.added","output_index":1,"item":{"type":"message","role":"assistant"}}

            data: {"type":"response.output_text.delta","output_index":1,"delta":"All "}

            data: {"type":"response.output_text.delta","output_index":1,"item_id":"msg_answer","delta":"clear."}

            data: {"type":"response.output_item.done","item":{"id":"msg_answer","type":"message","phase":"final_answer"}}

            data: {"type":"response.completed","response":{"status":"completed"}}

        """.trimIndent() + "\n")

        val texts = chunks.filterIsInstance<LLMStreamChunk.Text>()
        val indexedId = requireNotNull(texts[1].itemId)
        assertTrue(indexedId != "msg_work")
        assertEquals(
            listOf(
                LLMStreamChunk.Text("Checking.", COMMENTARY, "msg_work"),
                LLMStreamChunk.Text("All ", itemId = indexedId),
                LLMStreamChunk.Text("clear.", itemId = indexedId),
            ),
            texts,
        )
        assertEquals(LLMStreamChunk.TextPhase(indexedId, FINAL_ANSWER), chunks.filterIsInstance<LLMStreamChunk.TextPhase>().single())
    }

    @Test
    fun `a bridging done event reclassifies every alias of one text item`() {
        val chunks = responsesChunks("""
            data: {"type":"response.output_text.delta","item_id":"msg_a","delta":"Checking "}

            data: {"type":"response.output_text.delta","output_index":3,"delta":"the result."}

            data: {"type":"response.output_item.done","output_index":3,"item":{"id":"msg_a","type":"message","role":"assistant","phase":"commentary"}}

            data: {"type":"response.completed","response":{"status":"completed"}}

        """.trimIndent() + "\n")

        val texts = chunks.filterIsInstance<LLMStreamChunk.Text>()
        assertEquals(listOf("Checking ", "the result."), texts.map { it.text })
        val phases = chunks.filterIsInstance<LLMStreamChunk.TextPhase>()
        assertEquals("Both emitted aliases carry the single item's phase", setOf(texts[0].itemId, texts[1].itemId), phases.map { it.itemId }.toSet())
        assertTrue(phases.all { it.phase == COMMENTARY })
    }

    @Test
    fun `a phase delivered only in the completed output still reclassifies text`() {
        val chunks = responsesChunks("""
            data: {"type":"response.output_text.delta","item_id":"msg_only","delta":"Plain answer."}

            data: {"type":"response.completed","response":{"status":"completed","output":[{"id":"msg_only","type":"message","role":"assistant","phase":"final_answer"}]}}

        """.trimIndent() + "\n")

        assertTrue(
            "The final-only phase must reach the already-emitted item",
            LLMStreamChunk.TextPhase("msg_only", FINAL_ANSWER) in chunks,
        )
        assertEquals("stop", chunks.filterIsInstance<LLMStreamChunk.Finished>().single().stopReason)
    }

    @Test
    fun `conflicting explicit item ids disable phase lookup by output index`() {
        val chunks = responsesChunks("""
            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_work","type":"message","role":"assistant","phase":"commentary"}}

            data: {"type":"response.output_text.delta","item_id":"msg_other","output_index":0,"delta":"A"}

            data: {"type":"response.output_text.delta","output_index":0,"delta":"B"}

            data: {"type":"response.output_text.delta","item_id":"msg_work","output_index":0,"delta":"C"}

            data: {"type":"response.completed","response":{"status":"completed"}}

        """.trimIndent() + "\n")

        assertEquals(
            listOf(
                LLMStreamChunk.Text("A", itemId = "msg_other"),
                LLMStreamChunk.Text("B"),
                LLMStreamChunk.Text("C", COMMENTARY, "msg_work"),
            ),
            chunks.filterIsInstance<LLMStreamChunk.Text>(),
        )
    }

    // ── The regression ───────────────────────────────────────────────────

    @Test
    fun `response completed without DONE still emits Finished`() {
        // Exactly what the failing relays send: deltas, response.completed,
        // then the socket closes. No [DONE] ever arrives.
        val body = """
            data: {"type":"response.output_text.delta","delta":"complete reply"}

            data: {"type":"response.completed","response":{"status":"completed"}}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        val text = chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }
        assertEquals("complete reply", text)

        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>().firstOrNull()
        assertEquals(
            "response.completed must yield stopReason=stop even without a [DONE] sentinel — " +
                "a null here is what produced the bogus 'interrupted reply' banner",
            "stop",
            finished?.stopReason,
        )
    }

    @Test
    fun `response completed with DONE emits exactly one Finished`() {
        // Relays that DO append [DONE] must not now get two terminal chunks:
        // a duplicate would make the agent loop see a second, spurious turn end.
        val body = """
            data: {"type":"response.output_text.delta","delta":"complete reply"}

            data: {"type":"response.completed","response":{"status":"completed"}}

            data: [DONE]

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)
        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals("expected exactly one Finished chunk, got ${finished.size}", 1, finished.size)
        assertEquals("stop", finished.first().stopReason)
    }

    @Test
    fun `tool calls yield tool_use rather than stop`() {
        // A turn that emitted function calls completes with status=completed but
        // must surface tool_use, or the agent loop would end the turn instead of
        // dispatching the calls.
        val body = """
            data: {"type":"response.output_item.done","item":{"type":"function_call","call_id":"c1","name":"shell_execute","arguments":"{}"}}

            data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}]}}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)
        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>().firstOrNull()
        assertEquals("tool_use", finished?.stopReason)
    }

    // ── The behaviour that must NOT regress ──────────────────────────────

    @Test
    fun `truncated responses stream still reports no finish reason`() {
        // Deltas then silence — a genuine drop, with no response.completed.
        // This must keep producing a null stopReason, because that null is the
        // only signal the interrupted-reply UI has.
        val body = """
            data: {"type":"response.output_text.delta","delta":"partial reply "}

            data: {"type":"response.output_text.delta","delta":"that got cut"}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        val text = chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }
        assertTrue("content should have arrived, got '$text'", text.isNotEmpty())

        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>().firstOrNull()
        assertNull(
            "a truncated stream must still NOT report a finish reason",
            finished?.stopReason,
        )
    }
}
