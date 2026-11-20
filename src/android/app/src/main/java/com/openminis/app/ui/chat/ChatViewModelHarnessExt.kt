package com.openminis.app.ui.chat

import com.openminis.app.agent.HarnessConfig
import com.openminis.app.data.HarnessConfigStore

/** Resolve the actual serving entry for a request, or the selected entry for a history-only view. */
internal fun ChatViewModel.activeHarnessConfig(entryId: String? = _activeEntryId.value): HarnessConfig =
    HarnessConfigStore.forModel(context.filesDir, entryId)
