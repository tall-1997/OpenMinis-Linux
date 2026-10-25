package com.openminis.app.ui.chat

import androidx.lifecycle.ViewModelProvider
import com.openminis.app.MinisApp
import com.openminis.app.session.ChatSessionBinder
import com.openminis.app.session.ChatSessionPort

class ChatViewModelBinder(private val app: MinisApp) : ChatSessionBinder {
    override fun bind(sessionId: String): ChatSessionPort {
        val owner = ChatViewModelStore.ownerFor(sessionId)
        return ViewModelProvider(
            owner,
            ChatViewModel.factory(
                sessionId = sessionId,
                chatRepository = app.chatRepository,
                providerRepository = app.providerRepository,
                appContext = app.applicationContext,
                memoryRepository = app.memoryRepository,
                skillRepository = app.skillRepository,
                mcpRepository = app.mcpRepository,
            ),
        )[ChatViewModel::class.java]
    }

    override val activeSessionId: String?
        get() = ChatViewModelStore.activeSessionId
}
