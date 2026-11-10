package com.openminis.app.ui.sessions

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * [T-android-session-branch-tree] Branch action on the session list,
 * extracted from SessionListViewModel to keep it under its ceiling. Same
 * coroutine/dispatch discipline as the duplicate action it sits beside. The
 * tree read lives at the repository boundary ([branchTree] in
 * SessionBranchRepository.kt) so Room entities never cross into ui/.
 */
internal fun SessionListViewModel.branchAtLatest(id: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val fork = com.openminis.app.data.SessionForkManager(
            chatRepository = chatRepository,
            filesDir = context.filesDir,
        )
        val last = chatRepository.dao.loadMessagesTail(id, 1, 0).firstOrNull()
        if (last == null) {
            // An empty session has no fork point — fall back to a plain
            // duplicate, which is the same thing for zero messages.
            fork.duplicateSession(id)
            return@launch
        }
        fork.forkAtMessage(id, last.id)
    }
}
