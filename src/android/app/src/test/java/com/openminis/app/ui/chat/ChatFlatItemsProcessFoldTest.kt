package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-process-run-card] The unified process card owns the whole process of a
 * turn while folding is ON: live phase header, chronological entries, and a
 * collapsed bar once reply text streams. These tests pin the flat-item
 * projection semantics (what the list emits, in which order, folded how).
 */
class ChatFlatItemsProcessFoldTest {

    private fun thinking(id: String = "th1") =
        AssistantBlock(id = id, kind = "thinking", content = "reason")

    private fun text(id: String = "tx1", content: String = "hello") =
        AssistantBlock(id = id, kind = "text", content = content)

    private fun tool(
        id: String = "tool1",
        name: String = "bash",
        status: ToolBlockStatus = ToolBlockStatus.SUCCESS,
    ) = AssistantBlock(
        id = id,
        kind = "tool_use",
        toolName = name,
        toolStatus = status,
        toolTitle = name,
    )

    private fun assistant(
        streaming: Boolean = false,
        awaiting: Boolean = false,
        blocks: List<AssistantBlock>,
        role: String = "assistant",
        id: String = "m1",
    ) = ChatMessage(
        id = id,
        role = role,
        content = "",
        isStreaming = streaming,
        isAwaitingModelResponse = awaiting,
        toolBlocks = blocks,
    )

    private fun kinds(items: List<FlatChatItem>): List<String> = items.map { item ->
        when (item) {
            is FlatChatItem.UserBubble -> "user"
            is FlatChatItem.AssistantHeader -> "header"
            is FlatChatItem.AssistantText -> "text"
            is FlatChatItem.AssistantMarkdownBlock -> "md"
            is FlatChatItem.ProcessRunCard -> "card"
            is FlatChatItem.AssistantThinking -> "thinking"
            is FlatChatItem.AssistantToolUse -> "tool:${item.block.toolName}"
            is FlatChatItem.AssistantInfo -> "info"
            is FlatChatItem.AssistantTyping -> "typing"
            is FlatChatItem.AssistantError -> "error"
            is FlatChatItem.AssistantLegacyContent -> "legacy"
        }
    }

    private fun card(items: List<FlatChatItem>): FlatChatItem.ProcessRunCard =
        items.filterIsInstance<FlatChatItem.ProcessRunCard>().single()

    @Test
    fun `fold off keeps thinking tools and text with no card`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(), text()))),
            showCompletedToolCards = true,
            foldAiProcess = false,
        )
        assertFalse(kinds(items).contains("card"))
        assertTrue(kinds(items).contains("thinking"))
        assertTrue(kinds(items).contains("tool:bash"))
        assertTrue(kinds(items).contains("md"))
    }

    @Test
    fun `fold on finished collapses process and keeps every text block`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    blocks = listOf(
                        thinking(),
                        tool(),
                        text(id = "tx1", content = "one"),
                        text(id = "tx2", content = "two"),
                    ),
                ),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        // The card is emitted right after the header: under reverseLayout +
        // asReversed that is the visual TOP of the turn, so a finished turn
        // reads card-first, reply below.
        assertEquals(listOf("header", "card", "md", "md"), kinds(items))
        val c = card(items)
        assertEquals(listOf("th1", "tool1"), c.blocks.map { it.id })
        assertFalse(c.expanded)
        assertFalse(c.hasFailure)
        assertFalse(c.isRunning)
        assertEquals(ProcessPhaseKind.DONE, c.phaseKind)
        val blocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertFalse(blocks[0].showTranslate)
        assertTrue(blocks[1].showTranslate)
        assertTrue(blocks[1].translateWholeReply)
        assertEquals("one\n\ntwo", blocks[1].segmentText)
    }

    @Test
    fun `fold off translates each text block instead of the whole reply`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    blocks = listOf(
                        text(id = "tx1", content = "one"),
                        text(id = "tx2", content = "two"),
                    ),
                ),
            ),
            showCompletedToolCards = true,
            foldAiProcess = false,
        )
        val blocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].showTranslate)
        assertFalse(blocks[0].translateWholeReply)
        assertEquals("one", blocks[0].segmentText)
        assertTrue(blocks[1].showTranslate)
        assertEquals("two", blocks[1].segmentText)
    }

    @Test
    fun `fold on live thinking auto-expands the card with no standalone row`() {
        // Auto rule: running + no reply text => expanded. The card owns the
        // live thinking block (its live entry ticks), so the standalone
        // thinking row is gone — one surface, not two.
        val items = buildFlatChatItems(
            listOf(assistant(streaming = true, blocks = listOf(thinking()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertEquals(listOf("card"), k)
        val c = card(items)
        assertTrue(c.expanded)
        assertTrue(c.isRunning)
        assertEquals(ProcessPhaseKind.THINKING, c.phaseKind)
        assertEquals("th1", c.blocks.single().id)
    }

    @Test
    fun `fold on streaming collapses once reply text arrives`() {
        // Reply text streaming => the card auto-collapses ("一段运行完成后
        // 输出内容时折叠中间的过程"). The running tool rides inside the card.
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    streaming = true,
                    blocks = listOf(thinking(), tool(status = ToolBlockStatus.RUNNING), text()),
                ),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertEquals(listOf("header", "card", "md"), k)
        val c = card(items)
        assertFalse(c.expanded)
        assertTrue(c.isRunning)
        assertEquals(ProcessPhaseKind.TOOL, c.phaseKind)
        assertEquals("bash", c.phaseToolName)
        assertEquals(listOf("th1", "tool1"), c.blocks.map { it.id })
    }

    @Test
    fun `manual collapse overrides the auto-expand of a live turn`() {
        val items = buildFlatChatItems(
            listOf(assistant(streaming = true, blocks = listOf(thinking()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
            collapsedProcessIds = setOf("m1"),
        )
        assertFalse(card(items).expanded)
    }

    @Test
    fun `manual expand overrides the auto-collapse of a finished turn`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
            // The card is the slice closed by tx1 — its toggle id carries
            // the anchor block id (only the tail card keeps plain "m1").
            expandedProcessIds = setOf("m1:th1"),
        )
        val c = card(items)
        assertTrue(c.expanded)
        // Expanded card carries the entries; the standalone rows stay
        // suppressed — the card is the single process surface.
        val k = kinds(items)
        assertEquals(listOf("header", "card", "md"), k)
        assertEquals(listOf("th1", "tool1"), c.blocks.map { it.id })
    }

    @Test
    fun `tapping an expanded card collapses it and tapping again reopens it`() {
        // Regression: the collapse branch of nextProcessToggleState used to
        // return its pair in (collapsed, expanded) order while the expand
        // branch returned (expanded, collapsed) — tapping an expanded card
        // wrote its id into the EXPANDED set, so the card could never close.
        val live = buildFlatChatItems(
            listOf(assistant(streaming = true, blocks = listOf(thinking()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val auto = card(live)
        assertTrue(auto.expanded)
        // Tap 1 on the auto-expanded card: id moves to the collapsed set.
        val (expanded1, collapsed1) = nextProcessToggleState(auto, emptySet(), emptySet())
        assertEquals(emptySet<String>(), expanded1)
        assertEquals(setOf("m1"), collapsed1)
        // Tap 2 from the manually-collapsed state: id moves back.
        val closed = buildFlatChatItems(
            listOf(assistant(streaming = true, blocks = listOf(thinking()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
            collapsedProcessIds = setOf("m1"),
        )
        assertFalse(card(closed).expanded)
        val (expanded2, collapsed2) = nextProcessToggleState(card(closed), expanded1, collapsed1)
        assertEquals(setOf("m1"), expanded2)
        assertEquals(emptySet<String>(), collapsed2)
    }

    @Test
    fun `process slices collapse at their text while the live tail stays open`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    streaming = true,
                    blocks = listOf(
                        tool(id = "a"),
                        text(id = "tx1", content = "one"),
                        tool(id = "b", status = ToolBlockStatus.RUNNING),
                    ),
                ),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        // card above its reply text, live tail card last
        assertEquals(listOf("header", "card", "md", "card"), kinds(items))
        val cards = items.filterIsInstance<FlatChatItem.ProcessRunCard>()
        // The slice closed by its text ("text 到达时收拢") stays collapsed…
        assertEquals("m1:a", cards[0].toggleId)
        assertFalse(cards[0].expanded)
        assertEquals(listOf("a"), cards[0].blocks.map { it.id })
        // …and the running tail card auto-expands while the turn runs.
        assertEquals("m1", cards[1].toggleId)
        assertTrue(cards[1].expanded)
        assertTrue(cards[1].isRunning)
        assertEquals(listOf("b"), cards[1].blocks.map { it.id })
        // Segments are independently toggleable — distinct LazyColumn keys.
        assertNotEquals(cards[0].key, cards[1].key)
    }

    @Test
    fun `collapsed card still keeps completed process out of the list`() {
        val k = kinds(
            buildFlatChatItems(
                listOf(assistant(blocks = listOf(thinking(), tool(), text()))),
                showCompletedToolCards = false,
                foldAiProcess = true,
            ),
        )
        assertFalse(k.contains("thinking"))
        assertFalse(k.contains("tool:bash"))
        assertTrue(k.contains("card"))
    }

    @Test
    fun `ask_user_question stays visible while process is folded`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    blocks = listOf(
                        thinking(),
                        tool(id = "q", name = "ask_user_question", status = ToolBlockStatus.RUNNING),
                        tool(),
                        text(),
                    ),
                ),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertTrue(k.contains("card"))
        // Interactive prompt: must stay reachable outside the card.
        assertTrue(k.contains("tool:ask_user_question"))
        assertFalse(k.contains("tool:bash"))
        assertFalse(k.contains("thinking"))
        assertTrue(k.contains("md"))
        // And the card does not swallow it either.
        assertFalse(card(items).blocks.any { it.id == "q" })
    }

    @Test
    fun `system info rows are not folded`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    role = "system",
                    blocks = listOf(AssistantBlock(id = "i1", kind = "info", content = "note")),
                ),
            ),
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertFalse(k.contains("card"))
        assertTrue(k.contains("info"))
    }

    @Test
    fun `failed tool is flagged on the card`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(tool(status = ToolBlockStatus.FAILED)))),
            foldAiProcess = true,
        )
        val c = card(items)
        assertTrue(c.hasFailure)
        assertEquals(1, c.blocks.size)
    }

    @Test
    fun `fold on awaiting collapses completed process`() {
        val items = buildFlatChatItems(
            listOf(assistant(awaiting = true, blocks = listOf(thinking(), tool(), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertTrue(k.contains("card"))
        assertFalse(k.contains("thinking"))
        assertFalse(k.contains("tool:bash"))
        assertTrue(k.contains("md"))
        // Reply text already arrived => auto-collapsed even though awaiting.
        assertFalse(card(items).expanded)
    }

    @Test
    fun `turn error rides inside the card instead of a standalone banner`() {
        val msg = assistant(blocks = listOf(tool())).let { it.copy(error = "boom") }
        val items = buildFlatChatItems(listOf(msg), foldAiProcess = true)
        val k = kinds(items)
        assertFalse(k.contains("error"))
        assertEquals("boom", card(items).errorText)
    }

    @Test
    fun `floating overlay hides completed tools when fold is on`() {
        val done = tool(status = ToolBlockStatus.SUCCESS)
        val running = tool(id = "t2", status = ToolBlockStatus.RUNNING)
        assertTrue(isFloatingProcessTool(done, foldAiProcess = false))
        assertFalse(isFloatingProcessTool(done, foldAiProcess = true))
        assertTrue(isFloatingProcessTool(running, foldAiProcess = true))
        assertFalse(isFloatingProcessTool(thinking(), foldAiProcess = false))
    }

    @Test
    fun `card blocks still expose tools for the detail sheet`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(id = "tool1", name = "bash"), text()))),
            foldAiProcess = true,
        )
        val toolBlock = card(items).blocks.single { it.kind == "tool_use" }
        assertEquals("tool1", toolBlock.id)
        assertEquals("bash", toolBlock.toolName)
    }

    @Test
    fun `detail sheet helper keeps completed tools when fold hides overlay`() {
        val done = tool(id = "done1", status = ToolBlockStatus.SUCCESS)
        val running = tool(id = "run1", status = ToolBlockStatus.RUNNING)
        val msgs = listOf(assistant(blocks = listOf(thinking(), done, running, text())))
        val all = assistantToolUseBlocks(msgs)
        assertEquals(listOf("done1", "run1"), all.map { it.id })
        assertTrue(isDetailProcessTool(done))
        assertFalse(isFloatingProcessTool(done, foldAiProcess = true))
        assertTrue(isFloatingProcessTool(running, foldAiProcess = true))
    }

    // ── [T-android-fold-expanded-duplicate] ──────────────────────────────
    //
    // The overlay exists to surface process activity the card is currently
    // HIDING. Once the card is expanded its entries show the running tool
    // at its chronological position, so the same tool must not ALSO be
    // pinned to the viewport bottom.

    @Test
    fun `an expanded card does not also float its running tool`() {
        val running = tool(id = "t2", status = ToolBlockStatus.RUNNING)
        assertTrue(
            "collapsed: the card hides it, so the overlay must carry it",
            isFloatingProcessTool(running, foldAiProcess = true, processExpanded = false),
        )
        assertFalse(
            "expanded: the card already shows it in place",
            isFloatingProcessTool(running, foldAiProcess = true, processExpanded = true),
        )
    }

    @Test
    fun `effective expansion resolves auto and manual states`() {
        // Auto: running + no reply text.
        assertTrue(
            effectiveProcessExpanded("m1", isRunning = true, hasReplyText = false, expandedIds = emptySet(), collapsedIds = emptySet()),
        )
        // Auto collapse once reply text streams.
        assertFalse(
            effectiveProcessExpanded("m1", isRunning = true, hasReplyText = true, expandedIds = emptySet(), collapsedIds = emptySet()),
        )
        // Finished turns stay collapsed.
        assertFalse(
            effectiveProcessExpanded("m1", isRunning = false, hasReplyText = true, expandedIds = emptySet(), collapsedIds = emptySet()),
        )
        // Manual expand wins over auto.
        assertTrue(
            effectiveProcessExpanded("m1", isRunning = false, hasReplyText = true, expandedIds = setOf("m1"), collapsedIds = emptySet()),
        )
        // Manual collapse wins over auto-expand.
        assertFalse(
            effectiveProcessExpanded("m1", isRunning = true, hasReplyText = false, expandedIds = emptySet(), collapsedIds = setOf("m1")),
        )
        // Manual expand beats manual collapse (expanded checked first).
        assertTrue(
            effectiveProcessExpanded("m1", isRunning = false, hasReplyText = true, expandedIds = setOf("m1"), collapsedIds = setOf("m1")),
        )
    }

    // ── [T-process-summary-duration] aggregated card duration ──

    @Test
    fun `card sums block durations`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    blocks = listOf(
                        thinking("t1"),
                        tool("a").copy(durationMs = 1500L),
                        tool("b").copy(durationMs = 3200L),
                        text(id = "tx1", content = "done"),
                    ),
                ),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        assertEquals(4700L, card(items).totalMs)
    }

    @Test
    fun `card total is zero when no block recorded a duration`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        assertEquals(0L, card(items).totalMs)
        assertNull(formatProcessDuration(card(items).totalMs))
    }

    @Test
    fun `process duration formats as seconds minutes and hours`() {
        assertNull(formatProcessDuration(0L))
        assertNull(formatProcessDuration(-5L))
        assertEquals("47s", formatProcessDuration(47_000L))
        assertEquals("1m 23s", formatProcessDuration(83_000L))
        assertEquals("1h 5m", formatProcessDuration(3_900_000L))
        // 59.9s truncates to whole seconds, never rounds up to "60s"
        assertEquals("59s", formatProcessDuration(59_900L))
    }
}
