package com.openminis.app.ui.chat

import com.openminis.app.agent.GenerationRunLifecycle
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.provider.LLMProvider
import com.openminis.app.service.ActiveRunContext

/** Owns the transcript load, UI consumer and durable diagnostics of one loop. */
internal suspend fun ChatViewModel.runAgentLoop(
    provider: LLMProvider,
    systemPrompt: MemoryDeliveryPrompt?,
    fallbackProviders: List<ChatViewModel.FallbackCandidate> = emptyList(),
    skipInitialCompaction: Boolean = false,
    fallbackStrategy: FallbackStrategy = FallbackStrategy.default,
    goalExecutionRun: Boolean = false,
    overrideAssistantId: String? = null,
) {
    val sid = activeSessionId
    val epoch = snapshotResumeEpoch()
    val activeRun = ActiveRunContext.current()
    if (!rebuildAgentHistoryFromDatabase(sid, epoch) || !ownsTranscript(sid, epoch, activeRun)) return

    val journal = ChatRunJournal(context.filesDir, sid)
    val anchor = agentHistory.lastOrNull { it.dbMessageId != null }?.dbMessageId.orEmpty()
    val generationRunId = GenerationRunLifecycle.start(context, sid, provider.model.displayName, anchor)
    try {
        withRunUiBatcher(update = { id, text, streaming, blocks, awaiting ->
            if (ownsTranscript(sid, epoch, activeRun)) updateAssistantMessage(id, text, streaming, blocks, awaiting)
        }) { batcher ->
            runAgentLoopBody(
                provider = provider,
                systemPrompt = systemPrompt,
                fallbackProviders = fallbackProviders,
                fallbackStrategy = fallbackStrategy,
                goalExecutionRun = goalExecutionRun,
                overrideAssistantId = overrideAssistantId,
                skipInitialCompaction = skipInitialCompaction,
                journal = journal,
                loopResumeEpoch = epoch,
                generationRunId = generationRunId,
                uiBatcher = batcher,
            )
        }
    } finally {
        try {
            GenerationRunLifecycle.closeOpen(context, generationRunId)
        } finally {
            journal.close()
        }
    }
}
