package com.openminis.app.ui.chat

import com.openminis.app.data.instruction.InstructionSnapshotValues
import com.openminis.app.data.instruction.InstructionStateStore
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-p1-instruction-snapshot] Freezes the instruction values the next request is
 * built from, appending a delta to the append-only event stream whenever one of
 * them changed.
 *
 * The prompt injects two GLOBAL fragments from two *different* files: the
 * app-wide rules from [ChatViewModel.memoryRepository] (`minis-global/memory/`)
 * and the session-scoped rules from [ChatViewModel.sessionMemoryRepo] (this
 * chat's own workspace). The reads below are sourced apart on purpose — the
 * first cut fed both keys from the session repository, which recorded the same
 * file twice under two keys and never saw an app-wide GLOBAL.md edit.
 *
 * The per-session memory toggle is honoured for the same reason: with memory
 * off the prompt carries neither fragment, so recording them would describe
 * instructions the model never received.
 *
 * [goalValues] arrives already derived (see `InstructionStateStore.goalInstructionValues`)
 * so this UI-layer extension never has to name a Room entity.
 */
internal suspend fun ChatViewModel.commitInstructionSnapshot(goalValues: Map<String, String>) {
    val database = chatRepository.db ?: return
    val memoryEnabled = _memoryEnabled.value
    val sessionIdForSnapshot = realSessionId.ifEmpty { sessionId }
    withContext(Dispatchers.IO) {
        val appGlobal = memoryRepository?.loadGlobalMemoryFragment(sessionScoped = false)
        // [T-memory-project-rules] Third layer, so a project-rule edit shows up in
        // the instruction event stream like any other instruction change.
        val projectRules = com.openminis.app.data.repository.ProjectInstructionStore
            .fragment(context.filesDir, sessionIdForSnapshot)
        val sessionGlobal = sessionMemoryRepo().loadGlobalMemoryFragment(sessionScoped = true)
        val values = InstructionSnapshotValues.of(
            goalValues = goalValues,
            appGlobalFragment = appGlobal,
            sessionGlobalFragment = sessionGlobal,
            memoryEnabled = memoryEnabled,
            projectRulesFragment = projectRules,
        )
        // `sameSource` must stay false: true means both keys resolved to one
        // file, i.e. the app-wide fragment was not read from its own directory.
        AppLogger.info(
            "InstructionSnapshot",
            "[T-p1-instruction-snapshot] session=$sessionIdForSnapshot enabled=$memoryEnabled " +
                "appChars=${appGlobal?.length ?: 0} projectChars=${projectRules?.length ?: 0} " +
                "sessionChars=${sessionGlobal?.length ?: 0} " +
                "sameSource=${appGlobal != null && appGlobal == sessionGlobal}",
        )
        InstructionStateStore(database).observeAndCommit(sessionIdForSnapshot, values)
    }
}
