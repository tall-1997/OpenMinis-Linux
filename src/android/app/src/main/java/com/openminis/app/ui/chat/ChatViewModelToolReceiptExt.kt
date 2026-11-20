package com.openminis.app.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Session-load diagnostics only; the legacy file carries no dispatch authority or result proof. */
internal suspend fun ChatViewModel.legacyToolReceiptDiagnostics(sessionId: String): LegacyToolReceiptStore.Diagnostics? =
    withContext(Dispatchers.IO) {
        if (activeSessionId != sessionId) return@withContext null
        LegacyToolReceiptStore.readDiagnostics(context.filesDir, sessionId)
            ?.takeIf { activeSessionId == sessionId }
    }
