package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.ContextUsageAnchor
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.PendingTurnRow
import com.openminis.app.data.repository.appendTurnRows
import com.openminis.app.provider.ToolPairing
import com.openminis.app.provider.selectableThinkingLevels
import com.openminis.app.service.ActiveRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Phase 3 keeps emission order even if the execution lanes finished out of order. */
internal fun assembleTurnResultParts(
    toolCalls: List<Triple<String, String, JSONObject>>,
    plan: ToolTurnPlan,
    execution: ToolTurnExecution,
): MutableList<AgentContentPart> = toolCalls.mapTo(mutableListOf()) { (id, name, _) ->
    plan.refusedParts[id] ?: execution.executedParts[id] ?: AgentContentPart.ToolResult(
        id = id,
        name = name,
        content = "No result was recorded for this call. Its outcome is unknown; verify it before retrying.",
        isError = true,
    )
}

/** An ambiguous call identity cannot be dispatched or stored as a verifiable pair. */
internal fun validateCompletedToolCalls(toolCalls: List<Triple<String, String, JSONObject>>) {
    val ids = HashSet<String>(toolCalls.size)
    check(toolCalls.all { (id, name, _) ->
        val key = ToolPairing.key(id)
        key.isNotBlank() && name.isNotBlank() && ids.add(key)
    }) { "The provider returned tool calls with missing or ambiguous identities. No tools from this turn were sent." }
}

/** The completed stream events own the calls. UI blocks only supply their position among text parts. */
internal fun completedToolTurnParts(
    renderedParts: List<AgentContentPart>,
    toolCalls: List<Triple<String, String, JSONObject>>,
    inputs: Map<String, String>,
    signatures: Map<String, String>,
    text: String,
): List<AgentContentPart> {
    val pending = toolCalls.associateTo(LinkedHashMap()) { (id, name, args) ->
        id to AgentContentPart.ToolUse.fromInputJson(id, name, inputs[id] ?: args.toString(), signatures[id])
    }
    // Some providers stream generated image links without emitting a text block.
    // Keep the complete turn text in that case, as in the live transcript.
    val renderedText = renderedParts.filterIsInstance<AgentContentPart.Text>().joinToString("") { it.text }
    return buildList {
        // [T-midturn-prose-is-reply] The fallback copy of the turn text is PROSE.
        // It used to hardcode isProcessText = true, which re-folded the reply
        // into the process card on the PERSIST path — a reopened session showed
        // the words back inside the card even though the live transcript had
        // them inline.
        if (renderedText != text && text.isNotEmpty()) add(AgentContentPart.Text(text, isProcessText = false))
        for (part in renderedParts) {
            when (part) {
                is AgentContentPart.Text -> if (renderedText == text) add(part)
                is AgentContentPart.ToolUse -> pending.remove(part.id)?.let(::add)
                else -> Unit
            }
        }
        addAll(pending.values)
    }
}

/** Reject an impossible pair before a serializer can omit or deduplicate a half. */
internal fun completeToolTurnResultJson(
    turnParts: List<AgentContentPart>,
    resultParts: List<AgentContentPart>,
): String {
    val calls = turnParts.filterIsInstance<AgentContentPart.ToolUse>()
    val results = resultParts.filterIsInstance<AgentContentPart.ToolResult>()
    val expected = calls.map { it.id to it.name }
    val actual = results.map { it.id to it.name }
    check(calls.isNotEmpty() && calls.all { ToolPairing.key(it.id).isNotBlank() && it.name.isNotBlank() } &&
        calls.map { it.id }.toSet().size == calls.size && expected.toSet() == actual.toSet() && calls.size == resultParts.size
    ) { "The tool turn was not saved because its calls and results did not match." }
    return checkNotNull(buildToolResultPartsJson(resultParts))
}

/** The persistence fence owns both rows, then publishes the same pair to resident history. */
internal suspend fun ChatViewModel.persistToolTurnPairForRun(
    run: ActiveRun,
    turnParts: List<AgentContentPart>,
    blockMeta: Map<String, AssistantBlock>,
    lastUsage: LLMUsage?,
    turnReasoningContent: String?,
    resultParts: List<AgentContentPart>,
    targetSessionId: String,
    expectedEpoch: Long,
    uiMessageId: String,
    requestedLevel: ThinkingLevel?,
    modelSnapshot: ModelAttributionSnapshot?,
    contextUsageAnchor: ContextUsageAnchor?,
): Pair<String, String>? = run.withPersistencePermit {
    if (!ownsTranscript(targetSessionId, expectedEpoch, run)) return@withPersistencePermit null
    withContext(NonCancellable) {
        val anchor = turnContextUsageAnchor(lastUsage, modelSnapshot, contextUsageAnchor)
        val thinkingUsage = requestedLevel?.let {
            resolveTurnThinkingUsage(
                requestedLevel = it,
                thinkingObserved = !turnReasoningContent.isNullOrEmpty(),
                declaredSelectableLevels = currentModel?.selectableThinkingLevels ?: emptyList(),
            )
        }
        val assistantRow = PendingTurnRow(
            role = "assistant",
            partsJson = buildAssistantPartsJson(turnParts, blockMeta),
            tokenUsage = buildTurnTokenUsageJson(lastUsage, run.streamRate.takeDurationMs(), thinkingUsage, anchor),
            reasoningContent = turnReasoningContent,
            modelSnapshot = modelSnapshot ?: anchor?.modelSnapshot ?: currentModelSnapshot(),
        )
        val resultRow = PendingTurnRow("user", completeToolTurnResultJson(turnParts, resultParts))
        val persisted = chatRepository.appendTurnRows(targetSessionId, listOf(assistantRow, resultRow)) {
            ownsTranscript(targetSessionId, expectedEpoch, run)
        }
        if (persisted.isEmpty()) return@withContext null
        val (assistant, result) = persisted
        run.markAssistantTurnPersisted()
        val currentToolId = run.currentToolSnapshot()?.callId
        if (resultParts.any { it is AgentContentPart.ToolResult && it.id == currentToolId }) run.setCurrentTool(null, null)
        withContext(Dispatchers.Main.immediate) {
            if (ownsTranscript(targetSessionId, expectedEpoch, run = null)) {
                // A Stop can await this permit. Keep the committed IDs even if it cancelled the producer.
                val assistantMessage = LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = turnParts.filterIsInstance<AgentContentPart.Text>().joinToString("") { it.text },
                    contentParts = turnParts,
                    reasoningContent = turnReasoningContent,
                    dbMessageId = assistant.id,
                    contextUsageAnchor = anchor,
                )
                val resultMessage = LLMMessage(LLMMessage.Role.USER, "", contentParts = resultParts, dbMessageId = result.id)
                appendBoundedHistoryAll(listOf(assistantMessage, resultMessage))
                notePersistedUiRow(uiMessageId, assistant.id)
                notePersistedUiRow(uiMessageId, result.id)
            }
        }
        assistant.id to result.id
    }
}
