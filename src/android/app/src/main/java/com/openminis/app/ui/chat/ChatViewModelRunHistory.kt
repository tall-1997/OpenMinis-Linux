package com.openminis.app.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-android-stop-cleanup-race] / [T-android-resume-latency] Prepare
 * `agentHistory` before a run's first request.
 *
 * Room is the transcript authority. Rebuilding before the first request means
 * UI paging / cold-open tail limits / the hot cache can't hide older user
 * instructions from the model, and it restores raw BodyStore bodies.
 *
 * Two orderings matter:
 *  - The previous stop persists its interrupted tail on a background scope.
 *    Wait for that write BEFORE the rebuild, otherwise this run starts from a
 *    transcript that still ends at the last completed turn — the partial reply
 *    and the cancelled tool_use/result pair are missing, so the model
 *    continues from an OLD context and progress made before the interruption
 *    is silently lost (also after a model switch: the switch itself does not
 *    change which rows are in the DB).
 *  - Callers that already rebuilt history for THIS run (resume() must rebuild
 *    before deciding whether to append the "pick up where you left off"
 *    reminder; retry/rerun reconcile history themselves) pass
 *    [historyAlreadyRebuilt] so this doesn't repeat the DB read. On long
 *    sessions that duplicate read was a large part of the stretch where the
 *    UI showed "running" but no process bubble yet.
 */
internal suspend fun ChatViewModel.prepareRunHistory(historyAlreadyRebuilt: Boolean) {
    withContext(Dispatchers.IO) {
        awaitInterruptedTurnPersisted()
        if (!historyAlreadyRebuilt) rebuildAgentHistoryFromDatabase(activeSessionId)
    }
}
