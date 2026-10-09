package com.openminis.app.harness.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class MessageTransformerChainTest {

    // ── ReasoningTagStripper (existing, regression) ──

    @Test
    fun stripsNamedAndSpecialTokenBlocks() {
        val text = "answer<thinking>secret</thinking> tail\n<|thinking|>more<|/thinking|> end"
        val out = MessageTransformerChain.apply(text)
        assertTrue(!out.contains("secret"))
        assertTrue(!out.contains("<|thinking|>"))
        assertTrue(out.contains("answer") && out.contains("tail") && out.contains("end"))
    }

    // ── [T-universal-think-tag-history] Unterminated reasoning tail ──

    @Test
    fun stripsUnterminatedThinkingTail() {
        // The screenshot failure: <thinking> opened, never closed, all the
        // trailing reasoning rendered in the bubble.
        val text = "开头可见内容\n<thinking>Inspecting exact current blocks before patching\nAdding state for entry pin"
        val out = MessageTransformerChain.apply(text)
        assertTrue("visible head must survive", out.contains("开头可见内容"))
        assertTrue("raw tag must be gone", !out.contains("<thinking>"))
        assertTrue("reasoning must be gone", !out.contains("Inspecting exact current blocks"))
        assertTrue("tail reasoning must be gone", !out.contains("Adding state"))
    }

    @Test
    fun stripsUnterminatedSpecialTokenTail() {
        val text = "visible\n<|thinking|>reasoning with no close"
        val out = MessageTransformerChain.apply(text)
        assertTrue(out.contains("visible"))
        assertTrue(!out.contains("reasoning with no close"))
    }

    @Test
    fun keepsOrdinaryAngleBracketText() {
        // A '<' that is NOT a reasoning tag must never be eaten — code
        // snippets, comparisons, HTML examples all stay verbatim.
        val text = "Use a < b for comparison. And <div>HTML</div> stays."
        assertEquals(text, MessageTransformerChain.apply(text))
    }

    @Test
    fun stripsUnterminatedTagAnywhereInText() {
        // An unclosed reasoning tag ANYWHERE in the text is a provider leak,
        // not prose. The visible prefix before the tag survives; everything
        // from the tag onward goes to thinking. A model merely EXPLAINING the
        // tag will close it or use code formatting — an unclosed raw tag is
        // never intentional.
        val text = "Use the <think> tag to mark reasoning."
        val out = MessageTransformerChain.apply(text)
        assertTrue(out.startsWith("Use the "))
        assertTrue(!out.contains("<think>"))
        assertTrue(!out.contains("tag to mark reasoning."))
    }

    @Test
    fun stripsClosedAndUnterminatedMixed() {
        val text = "A<thinking>x</thinking>B\n<thinking>unclosed tail"
        val out = MessageTransformerChain.apply(text)
        assertTrue(out.contains("A"))
        assertTrue(out.contains("B"))
        assertTrue(!out.contains("unclosed tail"))
    }

    // ── TimeReminder ──

    @Test
    fun timeReminderInjectsForTimeSensitiveText() {
        val ctx = UserTransformContext(
            nowMillis = java.time.LocalDateTime.of(2026, 10, 1, 14, 30)
                .atZone(TimeZone.getDefault().toZoneId())
                .toInstant().toEpochMilli(),
        )
        val out = MessageTransformerChain.applyUser("现在几点了？", ctx)
        assertTrue(out.contains("当前时间"))
        assertTrue(out.contains("14:30"))
    }

    @Test
    fun timeReminderSkipsCodeOnlyMessages() {
        val ctx = UserTransformContext(nowMillis = 1_000L)
        val out = MessageTransformerChain.applyUser("把 /tmp/a.py 第三行的 print 改掉", ctx)
        assertTrue(!out.contains("system-note"))
        assertEquals("把 /tmp/a.py 第三行的 print 改掉", out)
    }

    // ── WorkspaceReminder ──

    @Test
    fun workspaceReminderInjectsWhenHintPresent() {
        val ctx = UserTransformContext(workspaceHint = "/var/minis/workspace")
        val out = MessageTransformerChain.applyUser("改一下报告", ctx)
        assertTrue(out.contains("工作目录"))
        assertTrue(out.contains("/var/minis/workspace"))
    }

    @Test
    fun workspaceReminderNoopWithoutHint() {
        val out = MessageTransformerChain.applyUser("改一下报告", UserTransformContext())
        assertEquals("改一下报告", out)
    }

    // ── expandResourceReferences ──

    @Test
    fun expandsMinisLinksToReadInstructions() {
        val text = "看一下 minis://workspace/data.csv 的结果"
        val out = MessageTransformerChain.expandResourceReferences(text)
        assertTrue(out.contains("file_read"))
        assertTrue(out.contains("/var/minis/workspace/data.csv"))
    }

    @Test
    fun noLinksNoExpansion() {
        val text = "帮我算 1+1"
        assertEquals(text, MessageTransformerChain.expandResourceReferences(text))
    }

    // ── chain idempotence guard ──

    @Test
    fun assistantChainDoesNotTouchUserTransformers() {
        val text = "现在几点了？"
        // apply() 只跑 assistant 清洗，不做时间注入。
        assertEquals(text, MessageTransformerChain.apply(text))
    }

    @Test
    fun reApplicationOverAlreadyEnrichedTextIsANoop() {
        // [T-user-transformers-wired] retry / rerun / reload 会在已注入过的
        // 文本上重跑 applyUser；哨兵必须阻止第二份（跨小时还会互相矛盾的）
        // 时间戳和第二层 <system-note> 嵌套包装。
        val ctx = UserTransformContext(
            nowMillis = java.time.LocalDateTime.of(2026, 10, 1, 14, 30)
                .atZone(TimeZone.getDefault().toZoneId())
                .toInstant().toEpochMilli(),
            workspaceHint = "/var/minis/workspace",
        )
        val once = MessageTransformerChain.applyUser("现在几点了？", ctx)
        val twice = MessageTransformerChain.applyUser(once, ctx)
        assertEquals(once, twice)
        assertEquals(1, Regex("<system-note>当前时间").findAll(twice).count())
        assertEquals(1, Regex("<system-note>工作目录").findAll(twice).count())

        val linkOnce = MessageTransformerChain.expandResourceReferences("看 minis://workspace/a.csv")
        val linkTwice = MessageTransformerChain.expandResourceReferences(linkOnce)
        assertEquals(linkOnce, linkTwice)
        assertEquals(1, Regex("<system-note>消息中引用了文件").findAll(linkTwice).count())
    }
}