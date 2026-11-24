package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [T-thinking-tokens-only-card] A reply whose thinking was folded into a process
 * card still SHOWS its reasoning — the user reads it by expanding the card.
 *
 * Regression: `computeUsageFooterRows` only looked for a standalone
 * `AssistantThinking` row. Thinking that rides a card never appears as one, so
 * such a reply was labelled "reasoned but no body" while its body was on screen.
 * Field report: the footer read "思考4201 tokens（未返回正文）" on a reply whose
 * thinking the user could read after expanding the card.
 */
class ThinkingShownInCardTest {

 private fun step(id: String, kind: String, content: String) = ProcessStepRef(
 id = id,
 kind = kind,
 title = if (kind == "thinking") "" else "ls",
 status = null,
 durationMs =100L,
 running = false,
 block = AssistantBlock(id = id, kind = kind, content = content),
 )

 private fun card(vararg steps: ProcessStepRef) = FlatChatItem.AssistantProcessSummary(
 messageId = "m1",
 thinkingCount = steps.count { it.kind == "thinking" },
 toolCount = steps.count { it.kind == "tool_use" },
 expanded = false,
 hasFailure = false,
 steps = steps.toList(),
 )

 private val usage = mapOf("m1" to MessageUsageStats(10,5,0,0, reasoningTokens =4201))

 @Test
 fun `thinking inside a process card counts as shown`() {
 val rows = computeUsageFooterRows(
 listOf(card(step("th1", "thinking", "让我先看看这个文件。"))),
 usage,
 )
 val stats = rows.values.single()
 assertEquals("the card owns the thinking row", true, stats.thinkingTextShown)
 assertFalse("a readable body must not claim it is missing", stats.hasReasoningWithoutText)
 }

 @Test
 fun `a blank thinking block is NOT shown text`() {
 // A blank block still counts toward thinkingCount, which is exactly why the
 // check reads the block content instead of the count.
 val rows = computeUsageFooterRows(
 listOf(card(step("th1", "thinking", ""))),
 usage,
 )
 assertFalse(rows.values.single().thinkingTextShown)
 }

 @Test
 fun `a card with only tools still reports the missing body`() {
 val rows = computeUsageFooterRows(
 listOf(card(step("t1", "tool_use", "ls"))),
 usage,
 )
 assertFalse(rows.values.single().thinkingTextShown)
 }
}
