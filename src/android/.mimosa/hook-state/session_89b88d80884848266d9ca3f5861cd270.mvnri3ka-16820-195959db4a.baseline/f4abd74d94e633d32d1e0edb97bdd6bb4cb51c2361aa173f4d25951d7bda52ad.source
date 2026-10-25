package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the long-session display path.
 *
 * The UI list is a contiguous oldest-to-newest model list and is reversed only
 * at the LazyColumn boundary. These tests deliberately exercise a middle row,
 * where a head/tail-only regression would otherwise pass visually.
 */
class ChatTimelineVisibilityRegressionTest {
    private fun user(index: Int) = ChatMessage(
        id = "user-$index",
        role = "user",
        content = "middle-user-$index",
    )

    private fun assistant(index: Int, withProcess: Boolean = false) = ChatMessage(
        id = "assistant-$index",
        role = "assistant",
        content = "",
        toolBlocks = buildList {
            if (withProcess) add(AssistantBlock("thinking-$index", "thinking", "reasoning"))
            add(AssistantBlock("text-$index", "text", "middle-assistant-$index"))
            if (withProcess) add(
                AssistantBlock(
                    id = "tool-$index",
                    kind = "tool_use",
                    toolName = "shell",
                    toolStatus = ToolBlockStatus.SUCCESS,
                ),
            )
        },
    )

    private fun session(count: Int = 180): List<ChatMessage> = buildList {
        repeat(count) { index ->
            add(user(index))
            add(assistant(index, withProcess = index % 11 == 0))
        }
    }

    @Test
    fun longSessionKeepsMiddleUserAndAssistantRows() {
        val messages = session()
        val rows = buildFlatChatItems(messages, foldAiProcess = true)
        val keys = rows.map { it.key }

        assertEquals(keys.size, keys.toSet().size)
        assertTrue(rows.any { it is FlatChatItem.UserBubble && it.message.id == "user-90" })
        assertTrue(rows.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
            .any { it.messageId == "assistant-90" && it.rawText.contains("middle-assistant-90") })
    }

    @Test
    fun middleRowKeysSurviveOlderPrependAndNewerAppend() {
        val base = session(80)
        val target = buildFlatChatItems(base, foldAiProcess = true)
            .first { it is FlatChatItem.UserBubble && it.message.id == "user-40" }
        val prepended = listOf(user(-1), assistant(-1)) + base
        val appended = base + listOf(user(80), assistant(80))

        val before = buildFlatChatItems(base, foldAiProcess = true)
        val afterPrepend = buildFlatChatItems(prepended, foldAiProcess = true)
        val afterAppend = buildFlatChatItems(appended, foldAiProcess = true)

        assertEquals(target.key, afterPrepend.first { it is FlatChatItem.UserBubble && it.message.id == "user-40" }.key)
        assertEquals(target.key, afterAppend.first { it is FlatChatItem.UserBubble && it.message.id == "user-40" }.key)
        // A new assistant turn contributes its header and markdown row in
        // addition to the new user bubble.
        assertEquals(before.size + 3, afterAppend.size)
    }

    @Test
    fun streamingOverlayChangesContentWithoutDroppingMiddleMessage() {
        val base = session(100)
        val middle = base[81] // assistant-40
        // The side-channel carries a real per-round block snapshot, NOT the
        // cumulative content smeared over one block: here the live text block
        // holds the newly streamed fragment itself.
        val streamedText = middle.toolBlocks.filter { it.kind == "text" }
            .map { it.copy(content = "streamed-middle") }
        val delta = StreamingDelta(
            content = "streamed-middle",
            toolBlocks = streamedText,
            isAwaitingModelResponse = false,
        )
        val overlaid = mergeStreamingOverlay(base, mapOf(middle.id to delta))
        val rows = buildFlatChatItems(overlaid, foldAiProcess = true)

        assertTrue(rows.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
            .any { it.messageId == middle.id && it.rawText == "streamed-middle" })
    }

    // ---------------- cumulative-content duplication regression ----------------
    //
    // [T-android-stream-duplicate-text] Repro of the duplicate-output bug:
    // `StreamingDelta.content` is the WHOLE reply accumulated across tool
    // rounds ("TEXT_A\n\nTEXT_B"), while `StreamingDelta.toolBlocks` is the
    // same reply split chronologically into text A, tool, text B. The overlay
    // used to write the cumulative content into the LAST text block, so the
    // rows read TEXT_A, tool, TEXT_A+TEXT_B — text A twice.

    private val textA = "TEXT_A_alpha"
    private val textB = "TEXT_B_beta"
    private val cumulativeContent = "$textA\n\n$textB"

    private fun blocksWithToolBetweenTwoTexts(id: String) = listOf(
        AssistantBlock("$id-text-a", "text", textA),
        AssistantBlock(
            id = "$id-tool",
            kind = "tool_use",
            toolName = "shell",
            toolTitle = "shell",
            toolStatus = ToolBlockStatus.SUCCESS,
        ),
        AssistantBlock("$id-text-b", "text", textB),
    )

    private fun cumulativeDelta(id: String) = StreamingDelta(
        content = cumulativeContent,
        toolBlocks = blocksWithToolBetweenTwoTexts(id),
        isAwaitingModelResponse = false,
    )

    private fun markdownRows(items: List<FlatChatItem>): List<FlatChatItem.AssistantMarkdownBlock> =
        items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    @Test
    fun cumulativeStreamingContentDoesNotDuplicateEarlierTextBlocksWhenFolded() {
        val base = session(100)
        val target = base.first { it.id == "assistant-40" }
        val overlaid = mergeStreamingOverlay(base, mapOf(target.id to cumulativeDelta(target.id)))
        val message = overlaid.first { it.id == target.id }

        // Content stays authoritative on its own field...
        assertEquals(cumulativeContent, message.content)
        assertTrue(message.isStreaming)
        // ...while the split blocks stay exactly as the producer snapped them.
        val texts = message.toolBlocks.filter { it.kind == "text" }.map { it.content }
        assertEquals(listOf(textA, textB), texts)

        val rows = markdownRows(buildFlatChatItems(overlaid, foldAiProcess = true))
            .filter { it.messageId == target.id }

        // Two text rows (one per text block), each carrying only its fragment —
        // the whole-reply markdown row must not repeat them.
        assertEquals(listOf(textA, textB), rows.map { it.rawText })
        assertEquals(listOf(cumulativeContent, cumulativeContent), rows.map { it.messageMarkdown })
        val renderedText = rows.joinToString("\n") { it.rawText }
        assertEquals(1, occurrences(renderedText, textA))
        assertEquals(1, occurrences(renderedText, textB))
    }

    @Test
    fun cumulativeStreamingContentDoesNotDuplicateEarlierTextBlocksWhenExpanded() {
        val base = session(100)
        val target = base.first { it.id == "assistant-40" }
        val overlaid = mergeStreamingOverlay(base, mapOf(target.id to cumulativeDelta(target.id)))
        val message = overlaid.first { it.id == target.id }

        assertEquals(cumulativeContent, message.content)
        assertEquals(
            listOf(textA, textB),
            message.toolBlocks.filter { it.kind == "text" }.map { it.content },
        )

        val rows = markdownRows(
            buildFlatChatItems(
                overlaid,
                foldAiProcess = true,
                expandedProcessIds = setOf(target.id),
            ),
        ).filter { it.messageId == target.id }

        assertEquals(listOf(textA, textB), rows.map { it.rawText })
        assertEquals(listOf(cumulativeContent, cumulativeContent), rows.map { it.messageMarkdown })
        val renderedText = rows.joinToString("\n") { it.rawText }
        assertEquals(1, occurrences(renderedText, textA))
        assertEquals(1, occurrences(renderedText, textB))
    }

    @Test
    fun cumulativeStreamingContentDoesNotTouchCompletedSiblingMessages() {
        val base = session(100)
        val target = base.first { it.id == "assistant-40" }
        val overlaid = mergeStreamingOverlay(base, mapOf(target.id to cumulativeDelta(target.id)))

        // Only the streaming turn changes; every other row keeps its snapshot.
        val others = overlaid.filter { it.id != target.id }
        assertEquals(base.filter { it.id != target.id }, others)
        assertTrue(others.none { it.isStreaming })
    }

    @Test
    fun modelHistoryMustUseTheMiddleSentinelAfterResidentWindowRebuild() {
        val messages = session(260)
        val sentinel = messages[80]
        val rebuilt = messages.map { message ->
            com.openminis.app.data.model.LLMMessage(
                role = if (message.role == "user") com.openminis.app.data.model.LLMMessage.Role.USER
                else com.openminis.app.data.model.LLMMessage.Role.ASSISTANT,
                content = message.content,
                dbMessageId = message.id,
                contentParts = message.toolBlocks.map { block ->
                    com.openminis.app.data.model.AgentContentPart.Text(block.content)
                },
            )
        }
        val middle = rebuilt.first { it.dbMessageId == sentinel.id }
        assertEquals("middle-user-40", middle.content)
        assertTrue(rebuilt.indexOf(middle) < rebuilt.lastIndex)
    }

    @Test
    fun foldingProcessKeepsEveryTextBlockInLongSession() {
        val messages = session(120)
        val rows = buildFlatChatItems(messages, showCompletedToolCards = false, foldAiProcess = true)
        val textIds = rows.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
            .map { it.messageId }
            .toSet()

        assertEquals((0 until 120).map { "assistant-$it" }.toSet(), textIds)
    }
}
