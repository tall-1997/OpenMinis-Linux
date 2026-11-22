package com.openminis.app.provider

import com.openminis.app.data.model.AssistantTextPhase
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-stream-trace] Record and replay `LLMStreamChunk` sequences as JSON —
 * the stream-parser regression net. Record real SSE sessions (a run that
 * renders thinking/tool/text) to `src/android/app/src/test/resources/
 * stream-traces/`, then replay in unit tests to diff the decoded chunk
 * sequence. A stream-parser change that would silently corrupt live output
 * now fails the suite instead of shipping.
 *
 * Kelivo-equivalent: trace_recorder + stream-traces snapshots.
 */
object StreamTrace {
    fun encode(chunks: List<LLMStreamChunk>): String = JSONArray().also { arr ->
        chunks.forEach { chunk -> arr.put(encodeChunk(chunk)) }
    }.toString()

    fun encodeChunk(chunk: LLMStreamChunk): JSONObject = when (chunk) {
        is LLMStreamChunk.Started -> JSONObject().put("kind", "started")
        is LLMStreamChunk.ThinkingDelta -> JSONObject().put("kind", "thinking").put("text", chunk.text)
        is LLMStreamChunk.ReasoningContent -> JSONObject().put("kind", "reasoning").put("content", chunk.content)
        is LLMStreamChunk.Text -> JSONObject().put("kind", "text").put("text", chunk.text)
            .put("phase", chunk.phase?.name).put("itemId", chunk.itemId)
        is LLMStreamChunk.TextPhase -> JSONObject().put("kind", "text_phase")
            .put("itemId", chunk.itemId).put("phase", chunk.phase.name)
        is LLMStreamChunk.ToolUseStart -> JSONObject().put("kind", "tool_use_start")
            .put("id", chunk.id).put("name", chunk.name)
        is LLMStreamChunk.ToolInputDelta -> JSONObject().put("kind", "tool_input_delta")
            .put("id", chunk.id).put("accumulated", chunk.accumulated)
        is LLMStreamChunk.ToolCallComplete -> JSONObject().put("kind", "tool_call_complete")
            .put("id", chunk.id).put("name", chunk.name).put("args", chunk.args.toString())
            .put("thoughtSignature", chunk.thoughtSignature ?: "")
            // [GH#61/#83/#146] Preserve the unparseable tail so a replayed
            // trace exercises the same ToolJsonRepair / preflight path the
            // live stream did.
            .put("rawArgs", chunk.rawArgs ?: "")
        is LLMStreamChunk.Finished -> JSONObject().put("kind", "finished").put("stopReason", chunk.stopReason ?: "")
        else -> JSONObject().put("kind", "unknown").put("class", chunk.javaClass.simpleName)
    }

    fun decode(json: String): List<LLMStreamChunk> {
        val arr = JSONArray(json)
        val out = mutableListOf<LLMStreamChunk>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            out += when (obj.optString("kind")) {
                "started" -> LLMStreamChunk.Started
                "thinking" -> LLMStreamChunk.ThinkingDelta(obj.getString("text"))
                "reasoning" -> LLMStreamChunk.ReasoningContent(obj.getString("content"))
                "text" -> LLMStreamChunk.Text(obj.getString("text"), obj.textPhase(), obj.textItemId())
                "text_phase" -> {
                    val itemId = obj.textItemId() ?: continue
                    val phase = obj.textPhase() ?: continue
                    LLMStreamChunk.TextPhase(itemId, phase)
                }
                "tool_use_start" -> LLMStreamChunk.ToolUseStart(obj.getString("id"), obj.getString("name"))
                "tool_input_delta" -> LLMStreamChunk.ToolInputDelta(obj.getString("id"), obj.getString("accumulated"))
                "tool_call_complete" -> LLMStreamChunk.ToolCallComplete(
                    obj.getString("id"), obj.getString("name"),
                    NonFiniteJson.parse(obj.optString("args", "{}")),
                    obj.optString("thoughtSignature").takeIf { it.isNotBlank() },
                    obj.optString("rawArgs").takeIf { it.isNotBlank() },
                )
                "finished" -> LLMStreamChunk.Finished(obj.optString("stopReason").takeIf { it.isNotBlank() })
                else -> LLMStreamChunk.Finished(null)
            }
        }
        return out
    }

    private fun JSONObject.textPhase(): AssistantTextPhase? {
        val name = opt("phase") as? String ?: return null
        return AssistantTextPhase.entries.firstOrNull { it.name == name }
    }

    private fun JSONObject.textItemId(): String? = opt("itemId") as? String

    /** Replay a decoded sequence as a Flow for watchdog / parser tests. */
    fun replay(chunks: List<LLMStreamChunk>): Flow<LLMStreamChunk> = flowOf(*chunks.toTypedArray())
}
