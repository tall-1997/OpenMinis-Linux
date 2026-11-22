package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AssistantTextPhase
import com.openminis.app.service.ActiveRun

/** Preserve the legacy/media fallback only when no block has a separate process role. */
internal fun replyContent(rawText: String, blocks: List<AssistantBlock>): String {
    if (blocks.none { it.isProcessText }) return rawText
    return buildString {
        for (block in blocks) if (block.isReplyText) append(block.content)
    }
}

/** One model response. Wire text is lossless; the host decides which blocks become a reply. */
internal class AgentTurnText(private val blocks: MutableList<AssistantBlock>, private val turn: Int) {
    private class Span(var index: Int, val itemId: String?, var phase: AssistantTextPhase?, val restored: Boolean)

    internal data class Fragment(val text: String, val phase: AssistantTextPhase?)

    val raw = StringBuilder()
    var deferReply = true
    private val spans = mutableListOf<Span>()
    private val itemPhases = mutableMapOf<String, AssistantTextPhase>()
    private var open: Span? = null
    private var pending = StringBuilder()

    fun phaseFor(itemId: String?, phase: AssistantTextPhase?): AssistantTextPhase? =
        phase ?: itemId?.let(itemPhases::get)

    fun append(
        text: String,
        itemId: String? = null,
        phase: AssistantTextPhase? = null,
        monolithic: Boolean = false,
        fromIndex: Int = blocks.size,
        restored: Boolean = false,
    ) {
        if (text.isEmpty()) return
        if (itemId != null && phase != null) notePhase(itemId, phase)
        val currentPhase = phaseFor(itemId, phase)
        val current = open
        if (current != null && current.itemId == itemId && current.phase == currentPhase &&
            (monolithic || current.index == blocks.lastIndex)
        ) {
            pending.append(text)
        } else {
            closeSpan()
            // Chat Completions has one content string per response, even when the
            // gateway emits its first content delta after the tool-call deltas.
            val index = if (monolithic) {
                (fromIndex until blocks.size).firstOrNull { blocks[it].kind == "tool_use" } ?: blocks.size
            } else blocks.size
            for (previous in spans) if (previous.index >= index) previous.index++
            val span = Span(index, itemId, currentPhase, restored)
            blocks.add(index, AssistantBlock(
                id = "text_${turn}_${blocks.size}", kind = "text", content = text,
                isProcessText = deferReply || currentPhase == AssistantTextPhase.COMMENTARY,
            ))
            open = span
            spans.add(span)
            pending.append(text)
        }
        raw.append(text)
    }

    fun notePhase(itemId: String, phase: AssistantTextPhase) {
        if (itemPhases.put(itemId, phase) == phase) return
        for (span in spans) {
            if (span.itemId != itemId) continue
            span.phase = phase
            blocks[span.index] = blocks[span.index].copy(
                isProcessText = deferReply || phase == AssistantTextPhase.COMMENTARY,
            )
        }
    }

    fun materialize() {
        val span = open ?: return
        val block = blocks[span.index]
        if (pending.length != block.content.length) blocks[span.index] = block.copy(content = pending.toString())
    }

    fun closeSpan() {
        materialize()
        open = null
        pending = StringBuilder()
    }

    fun requireProcess() {
        if (deferReply) return
        deferReply = true
        settle(asReply = false)
    }

    fun hasReplyCandidate(): Boolean {
        materialize()
        // A retry that only echoed the saved prefix did not deliver a new response.
        return spans.any { !it.restored && it.phase != AssistantTextPhase.COMMENTARY && blocks[it.index].content.isNotBlank() }
    }

    fun parts(asReply: Boolean): List<AgentContentPart.Text> {
        materialize()
        return spans.map { span ->
            AgentContentPart.Text(blocks[span.index].content, !asReply || span.phase == AssistantTextPhase.COMMENTARY)
        }
    }

    fun interruptedText(): List<ActiveRun.AssistantTextTail> {
        materialize()
        return spans.map { span ->
            val block = blocks[span.index]
            ActiveRun.AssistantTextTail(block.id, block.content, block.isProcessText)
        }
    }

    fun replyBlockIds(): List<String> = spans.mapNotNull { span ->
        blocks[span.index].id.takeUnless { span.phase == AssistantTextPhase.COMMENTARY }
    }

    fun settle(asReply: Boolean) {
        materialize()
        for (span in spans) {
            val block = blocks[span.index]
            val process = !asReply || span.phase == AssistantTextPhase.COMMENTARY
            if (block.isProcessText != process) blocks[span.index] = block.copy(isProcessText = process)
        }
    }

    fun fragments(forceProcess: Boolean): List<Fragment> {
        materialize()
        return spans.map {
            Fragment(blocks[it.index].content, if (forceProcess) AssistantTextPhase.COMMENTARY else it.phase)
        }
    }

    /** Discard after the caller removed the attempt's blocks. */
    fun reset() {
        open = null
        pending = StringBuilder()
        spans.clear()
        itemPhases.clear()
        raw.setLength(0)
    }
}
