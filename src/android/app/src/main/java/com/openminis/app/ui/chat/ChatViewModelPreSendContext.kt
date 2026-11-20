package com.openminis.app.ui.chat

import com.openminis.app.R
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.LLMProvider
import com.openminis.app.session.InputAttachment
import com.openminis.app.ui.chat.ChatViewModel.PreSendContextAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Composer preflight never copies attachments to disk or publishes delivery state. */
internal suspend fun ChatViewModel.checkContextBeforeSend(
    provider: LLMProvider,
    text: String,
    attachments: List<InputAttachment>,
): PreSendContextAction {
    val projected = withContext(Dispatchers.IO) {
        // Draft text is available now; images have not yet been opened or resized.
        val expanded = _pastedTexts.value.fold(text) { body, paste -> body.replace(paste.placeholder, paste.text) }
        val parts = buildList {
            if (expanded.isNotEmpty()) add(AgentContentPart.Text(expanded))
            attachments.forEach { attachment ->
                if (attachment.isImage) {
                    // The shared meter counts nonempty image parts. Exact bytes, paths and
                    // request-budget elision are resolved again at dispatch.
                    add(AgentContentPart.ImageData(PENDING_IMAGE, attachment.mimeType))
                } else add(AgentContentPart.Text(attachment.fileName))
            }
        }
        val sid = activeSessionId
        val history = agentHistorySnapshot()
        val compact = requestCompactStateFor(history, sid)
        projectChatRequest(
            provider, buildMemoryDeliveryPrompt(publish = false),
            history + LLMMessage(LLMMessage.Role.USER, expanded, contentParts = parts),
            agentTools, requestThinkingLevel(provider),
            uncoveredBeforeResident = compact.hasCoverageGap,
            requestSessionId = sid, marker = compact.marker,
        )
    }
    return when (projected.capacity()) {
        ContextPolicy.CheckResult.OK -> PreSendContextAction.PROCEED
        ContextPolicy.CheckResult.NEEDS_COMPACT -> if (effectiveAutoCompact()) {
            PreSendContextAction.COMPACT_THEN_SEND
        } else PreSendContextAction.ASK_USER
        ContextPolicy.CheckResult.EXHAUSTED -> {
            appendSystemInfo(context.getString(R.string.vm_context_near_limit, projected.estimate.tokens, projected.window), "compact")
            PreSendContextAction.PROCEED // the exact dispatch projection rechecks hard limits
        }
    }
}

private val PENDING_IMAGE = byteArrayOf(1)
