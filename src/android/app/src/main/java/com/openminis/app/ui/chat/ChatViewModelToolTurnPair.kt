package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.ContextUsageAnchor
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.PendingTurnRow
import com.openminis.app.data.repository.appendTurnRows
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.selectableThinkingLevels
import com.openminis.app.service.ActiveRun
import com.openminis.app.tools.SubAgentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-retry-projection-reuse] Per-turn projection cache: a stream retry re-enters
 * dispatchTurn with an unchanged transcript; re-running the projection would
 * re-hash the whole payload and re-read the DB/file for gap + receipts on every
 * attempt. Reuse when nothing the projection consumes has moved — any real
 * change (steering, compaction via the marker id, adoption, tool set, thinking,
 * stall seed) fails one of the identity checks and forces a fresh projection.
 */
internal class DispatchProjectionCache {
    private var cached: ProjectedChatRequest? = null
    private var stamp: Triple<Int, LLMMessage?, LLMMessage?>? = null
    private var markerId: String? = null
    private var stallNote: String? = null
    private var prompt: MemoryDeliveryPrompt? = null
    private var tools: List<AgentToolDefinition>? = null
    private var provider: LLMProvider? = null
    private var thinking: ThinkingLevel? = null

    fun reuse(
        history: List<LLMMessage>,
        markerNow: String?,
        stall: String?,
        promptRef: MemoryDeliveryPrompt?,
        toolsRef: List<AgentToolDefinition>,
        providerRef: LLMProvider,
        thinkingLevel: ThinkingLevel,
    ): ProjectedChatRequest? = cached?.takeIf {
        stamp == Triple(history.size, history.firstOrNull(), history.lastOrNull()) &&
            markerId == markerNow &&
            stallNote == stall &&
            prompt === promptRef &&
            tools === toolsRef &&
            provider === providerRef &&
            thinking == thinkingLevel
    }

    fun store(
        projection: ProjectedChatRequest,
        history: List<LLMMessage>,
        markerNow: String?,
        stall: String?,
        promptRef: MemoryDeliveryPrompt?,
        toolsRef: List<AgentToolDefinition>,
        providerRef: LLMProvider,
        thinkingLevel: ThinkingLevel,
    ) {
        cached = projection
        stamp = Triple(history.size, history.firstOrNull(), history.lastOrNull())
        markerId = markerNow
        stallNote = stall
        prompt = promptRef
        tools = toolsRef
        provider = providerRef
        thinking = thinkingLevel
    }
}

/**
 * Phase 3 of the tool turn: assemble resultParts in the model's emission order
 * (see dispatchTurn) — phase 2 may reorder EXECUTION, never the transcript.
 */
internal fun assembleTurnResultParts(
    toolCalls: List<Triple<String, String, JSONObject>>,
    plan: ToolTurnPlan,
    execution: ToolTurnExecution,
): MutableList<AgentContentPart> {
    val resultParts = mutableListOf<AgentContentPart>()
    for ((id, name, _) in toolCalls) {
        val refused = plan.refusedParts[id]
        val executed = execution.executedParts[id]
        when {
            refused != null -> resultParts.add(refused)
            executed != null -> resultParts.add(executed)
            SubAgentKind.isSpawnTool(name) -> {
                // A spawn call whose peer result never landed (fan-out
                // interrupted) still needs a tool_result so the
                // tool_use/tool_result pairing stays balanced.
                resultParts.add(
                    AgentContentPart.ToolResult(
                        id = id, name = name,
                        content = "Error: this spawn call's result is missing — the wave was " +
                            "collected without a report for this call. Re-issue the dispatch.",
                        isError = true,
                    ),
                )
            }
        }
    }
    return resultParts
}

/**
 * The tool turn's two rows — assistant(tool_use) and user(tool_result) — are
 * one logical turn. Writing them through two independent appendMessage
 * transactions left a crash window that stranded a tool_use without its
 * result: compact pairing could no longer close a prefix through that turn,
 * receipt reconciliation saw a dispatched call with no result row to settle,
 * and the persisted history was permanently half-of-a-round.
 *
 * [ChatRepository.appendTurnRows] commits both rows in ONE Room transaction;
 * this wrapper takes the persistence permit ONCE (it does not nest) around
 * the whole pair, then performs the same UI/history bookkeeping the two
 * single-row writers performed, only after the transaction succeeded.
 */
internal suspend fun ChatViewModel.persistToolTurnPairForRun(
    run: ActiveRun,
    turnParts: List<AgentContentPart>,
    blockMeta: Map<String, AssistantBlock>,
    lastUsage: LLMUsage?,
    turnReasoningContent: String?,
    assistantText: String?,
    agentHistory: MutableList<LLMMessage>,
    resultParts: List<AgentContentPart>,
    targetSessionId: String,
    uiMessageId: String?,
    requestedLevel: ThinkingLevel?,
    modelSnapshot: ModelAttributionSnapshot?,
    contextUsageAnchor: ContextUsageAnchor?,
): Pair<String?, String?> = run.withPersistencePermit {
    withContext(NonCancellable) {
        val anchor = turnContextUsageAnchor(lastUsage, modelSnapshot, contextUsageAnchor)
        // [T-thinking-dual-ledger] Same resolution contract as
        // persistAssistantTurnForRun: reasoning evidence and the requested tier
        // are both in scope here; the writer stays a dumb writer.
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
            tokenUsage = buildTurnTokenUsageJson(
                lastUsage, run.streamRate.takeDurationMs(), thinkingUsage, anchor,
            ),
            reasoningContent = turnReasoningContent,
            modelSnapshot = modelSnapshot ?: anchor?.modelSnapshot ?: currentModelSnapshot(),
        )
        val resultJson = buildToolResultPartsJson(resultParts)
        val rows = if (resultJson == null) {
            // No result parts to pair (defensive): fall back to the assistant
            // row alone rather than persisting an empty user carrier.
            listOf(assistantRow)
        } else {
            listOf(
                assistantRow,
                PendingTurnRow(role = "user", partsJson = resultJson),
            )
        }
        val assistantBeforeWrite = agentHistory.lastOrNull()
        val persisted = chatRepository.appendTurnRows(targetSessionId, rows)
        val assistantDbId = persisted.getOrNull(0)?.id
        val toolResultDbId = persisted.getOrNull(1)?.id
        if (assistantDbId != null) {
            withContext(Dispatchers.Main.immediate) { notePersistedUiRow(uiMessageId, assistantDbId) }
            if (assistantText != null) run.markAssistantTurnPersisted(assistantText)
            if (agentHistory.lastOrNull() === assistantBeforeWrite && assistantBeforeWrite?.role == LLMMessage.Role.ASSISTANT &&
                assistantBeforeWrite.dbMessageId == null
            ) {
                backfillPersistedAssistantTurn(agentHistory, assistantDbId, anchor, turnParts)
            }
        }
        if (toolResultDbId != null) {
            withContext(Dispatchers.Main.immediate) { notePersistedUiRow(uiMessageId, toolResultDbId) }
            val currentToolId = run.currentToolSnapshot()?.callId
            val resultIds = resultParts.filterIsInstance<AgentContentPart.ToolResult>().mapTo(mutableSetOf()) { it.id }
            if (currentToolId != null && currentToolId in resultIds) run.setCurrentTool(null, null)
        }
        assistantDbId to toolResultDbId
        }
    } ?: (null to null)

