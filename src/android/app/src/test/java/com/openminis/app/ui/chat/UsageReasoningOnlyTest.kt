package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thinking-tokens-only] A gateway may reason server-side and still strip the
 * reasoning body: observably `reasoning_tokens` above zero with no thinking
 * text in the reply. The footer must explain that state instead of letting it
 * read like the app lost the thinking.
 *
 * Evidence behind it: on-device raw SSE from a DeepSeek-v4 relay contained only
 * `delta.content` while the final usage carried
 * `completion_tokens_details.reasoning_tokens =4`.
 */
class UsageReasoningOnlyTest {

 @Test
 fun `billed reasoning without a thinking body is surfaced`() {
 val stats = MessageUsageStats.fromJson(
 """{"inputTokens":10,"outputTokens":5,"reasoningTokens":4}""",
 )!!.copy(thinkingTextShown = false)
 assertTrue(stats.hasReasoningWithoutText)
 }

 @Test
 fun `reasoning that came with a thinking body is not duplicated in the footer`() {
 val stats = MessageUsageStats.fromJson(
 """{"inputTokens":10,"outputTokens":5,"reasoningTokens":4}""",
 )!!.copy(thinkingTextShown = true)
 assertFalse(stats.hasReasoningWithoutText)
 }

 @Test
 fun `no billed reasoning means no footer segment`() {
 val stats = MessageUsageStats.fromJson("""{"inputTokens":10,"outputTokens":5}""")!!
 assertFalse(stats.hasReasoningWithoutText)
 }

 @Test
 fun `merged rows keep the flag only when every row showed its body`() {
 val shown = MessageUsageStats(1,1,0,0, reasoningTokens =3, thinkingTextShown = true)
 val hidden = MessageUsageStats(1,1,0,0, reasoningTokens =3, thinkingTextShown = false)
 assertFalse((shown + shown).hasReasoningWithoutText)
 assertTrue((shown + hidden).hasReasoningWithoutText)
 }
}