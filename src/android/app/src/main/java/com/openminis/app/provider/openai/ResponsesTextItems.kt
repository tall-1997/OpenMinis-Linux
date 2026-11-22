package com.openminis.app.provider.openai

import com.openminis.app.data.model.AssistantTextPhase
import com.openminis.app.data.model.LLMStreamChunk
import org.json.JSONObject
import java.util.UUID

/** Tracks visible assistant text items and their optional phase for one Responses SSE stream. */
internal class ResponsesTextItems {
    private class Item(var index: Int? = null) {
        var wireId: String? = null
        val emittedPhases = linkedMapOf<String, AssistantTextPhase?>()
        var isAssistant = false
        var phase: AssistantTextPhase? = null
    }

    private val byId = mutableMapOf<String, Item>()
    // Null marks an index that cannot safely identify a text item.
    private val byIndex = mutableMapOf<Int, Item?>()

    suspend fun onTextDelta(event: JSONObject, emit: suspend (LLMStreamChunk) -> Unit) {
        val delta = event.optString("delta", "")
        if (delta.isEmpty()) return
        val state = resolve(event.itemId("item_id"), event.outputIndex())
        val itemId = state?.let { item ->
            item.publishPhase(emit)
            item.emittedPhases.keys.firstOrNull() ?: (item.wireId ?: "responses-index-${UUID.randomUUID()}")
                .also { item.emittedPhases[it] = item.phase }
        }
        emit(LLMStreamChunk.Text(delta, state?.phase, itemId))
    }

    suspend fun onOutputItem(event: JSONObject, item: JSONObject, emit: suspend (LLMStreamChunk) -> Unit) =
        onOutputItem(event.outputIndex(), event.itemId("item_id"), item, emit)

    suspend fun onResponseOutput(response: JSONObject?, emit: suspend (LLMStreamChunk) -> Unit): Boolean {
        val output = response?.optJSONArray("output") ?: return false
        var hasTools = false
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") == "function_call") hasTools = true
            onOutputItem(i, null, item, emit)
        }
        return hasTools
    }

    private suspend fun onOutputItem(index: Int?, eventItemId: String?, item: JSONObject, emit: suspend (LLMStreamChunk) -> Unit) {
        val itemId = item.itemId("id")
        val role = item.opt("role") as? String
        val conflictingId = itemId != null && eventItemId != null && itemId != eventItemId
        if (item.optString("type") != "message" || (role != null && role != "assistant") || conflictingId) {
            if (index != null) byIndex[index] = null
            return
        }
        val state = resolve(itemId ?: eventItemId, index) ?: return
        // The done snapshot can omit the role if the same item already confirmed it.
        if (role == "assistant") state.isAssistant = true
        if (!state.isAssistant) return
        when (item.opt("phase")) {
            "commentary" -> state.phase = AssistantTextPhase.COMMENTARY
            "final_answer" -> state.phase = AssistantTextPhase.FINAL_ANSWER
        }
        state.publishPhase(emit)
    }

    // A late item can identify multiple earlier aliases. Reclassify every emitted key, never replay text.
    private suspend fun Item.publishPhase(emit: suspend (LLMStreamChunk) -> Unit) {
        val resolved = phase ?: return
        for (entry in emittedPhases.entries) {
            if (entry.value == resolved) continue
            entry.setValue(resolved)
            emit(LLMStreamChunk.TextPhase(entry.key, resolved))
        }
    }

    private fun resolve(id: String?, index: Int?): Item? {
        if (id == null) {
            index ?: return null
            if (index !in byIndex) byIndex[index] = Item(index)
            return byIndex[index]
        }

        val indexed = index?.let { byIndex[it] }
        // An explicit ID can claim an unclaimed slot, but can never borrow a different ID's state.
        // Carry the original emitted key forward if earlier deltas only had an index.
        val item = byId.getOrPut(id) {
            (indexed?.takeIf { it.wireId == null } ?: Item()).also { it.wireId = id }
        }
        if (index != null && indexed !== item) {
            when {
                index !in byIndex && item.index == null -> {
                    item.index = index
                    byIndex[index] = item
                }
                indexed != null && indexed.wireId == null && item.index == null -> {
                    // One delta had only a wire ID and another only an index. This event bridges them.
                    item.index = index
                    item.isAssistant = item.isAssistant || indexed.isAssistant
                    if (item.phase == null) item.phase = indexed.phase
                    item.emittedPhases.putAll(indexed.emittedPhases)
                    byIndex[index] = item
                }
                else -> byIndex[index] = null
            }
        }
        return item
    }

    private fun JSONObject.itemId(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }

    private fun JSONObject.outputIndex(): Int? = opt("output_index")?.toString()?.toIntOrNull()?.takeIf { it >= 0 }
}
