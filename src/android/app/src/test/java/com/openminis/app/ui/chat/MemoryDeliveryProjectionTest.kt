package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.*
import com.openminis.app.util.IsoTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryDeliveryProjectionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_798_200_000_000L
    private val user = LLMMessage(LLMMessage.Role.USER, "Real question", contentParts = listOf(AgentContentPart.Text("Real question")), dbMessageId = "actual-user")

    @Test fun dailySlotUsesPositionEvenWhenTextRepeatsInOtherSections() {
        val daily = "same \uD83E\uDDED text"
        val stable = "identity\n$daily"
        val runtime = renderRuntimeContext("2026-12-25", "UTC", "en", 1)
        var local = -1
        val tail = assembleDynamicTailWithDailySlot("\n\n$daily", null, null, runtime, null, daily, daily, { local = it })
        val prompt = stable + tail
        val start = stable.length + local
        val end = start + dailyMemorySlot(daily).length
        val slot = DailyMemoryDelivery(prompt, start, end, "sid", "sid", emptyList(), emptyList(), emptySet(), stable.length)
        val prefix = "oauth\n\n"
        val wrapped = prefix + prompt
        val shifted = slot.prefixed(prefix, wrapped)
        val refreshed = refreshRuntimeContextDate(wrapped, "2026-12-26")!!
        val replaced = shifted.project(refreshed, dailyMemorySlot("new note"), null, now)!!
        assertEquals(refreshed.replaceRange(start + prefix.length, end + prefix.length, "\n\nnew note"), replaced.prompt)
        assertEquals(stable.length + prefix.length, replaced.stablePrefixLen)
        assertEquals(3, replaced.prompt.split(daily).size - 1) // Identical text elsewhere remains intact.
        assertNull(shifted.project(refreshed.replace("Device language", "Foreign section"), "", null, now))
        assertNull(shifted.copy(stablePrefixLen = shifted.start + 1).project(refreshed, "", null, now))
    }

    @Test fun tocNoteDoesNotMoveEntryOffsetsAndBudgetDoesNotCreditPartialBody() {
        val dir = tmp.newFolder("scope")
        val repo = MemoryRepository(dir) { now }
        repo.writeHostMemory("preview \uD83E\uDDED body", origin = MemoryEntryOrigin.MEMORY_WRITE, autoArchive = true)
        val toc = MemoryIndex.fragmentWithEntries(null, dir, emptySet(), now = now)!!
        val annotated = MemoryIndex.appendBudgetNote(toc, "[budget note]")!!
        assertEquals(toc.entries, annotated.entries)
        val span = annotated.entries.single()
        assertEquals("preview \uD83E\uDDED body", annotated.text.substring(span.start, span.end))
        val huge = "a".repeat(600) + "b".repeat(700)
        val onlyPartial = MemoryAssembly.DailyFragment("2026-01-01", huge, listOf(span.copy(start = 600, end = huge.length)))
        val cut = MemoryAssembly.assembleTracked(emptyList(), listOf(onlyPartial), budgetChars = 800) { null }
        assertTrue(cut.allocation.truncatedKeys.isNotEmpty())
        assertTrue(cut.deliveredEntries.isEmpty())
        val withToc = MemoryAssembly.assembleTracked(emptyList(), emptyList(), header = "caption") { annotated }
        val transient = MemoryBlockSplit.split(withToc.allocation).transient!!
        val placed = withToc.deliverySpans.single()
        assertEquals("preview \uD83E\uDDED body", transient.substring(placed.start, placed.end))
    }

    @Test fun onlySuccessWithTheWireAndRealUserIdCanWriteOneReceipt() {
        val dir = tmp.newFolder("scope")
        val file = File(dir, "${IsoTime.formatLocalDate(now)}.md")
        val repo = MemoryRepository(dir) { now }
        repo.writeHostMemory("durable item", origin = MemoryEntryOrigin.MEMORY_WRITE, autoArchive = true)
        val entry = MemoryLifecycle.read(dir, file.name)!!.managed().single()
        val sidecar = MemoryLifecycle.sidecar(dir, file.name)!!
        val before = sidecar.readBytes()
        val assembly = assembleDailyMemory(repo, dir, tmp.newFolder("shared"), "durable", emptyList(), true, now).first
        val built = DailyMemoryDelivery("base\nRuntime", 4, 4, "sid", "sid", emptyList(), emptyList(), emptySet(), 4)
        val attempt = built.project("base\nRuntime", dailyMemorySlot(MemoryBlockSplit.split(assembly.allocation).transient), assembly, now)!!
        assertArrayEquals("read-only budget and preview", before, sidecar.readBytes())
        val hit = MemoryRecallEngine({ dir }, clock = { now }).recall("durable").single()
        assertEquals(entry.ref, hit.ref)
        val engine = MemoryRecallEngine({ dir }, clock = { now })
        val body = engine.formatAsPromptFragment(listOf(hit))
        val recall = RecallReminder("<system-reminder>\n$body\n</system-reminder>", listOf(hit), "sid", now, user.dbMessageId)
        val plain = listOf(user)
        val projected = plain.withMemoryRecallReminder(recall)
        val differentPrompt = attempt.prompt + " changed after attempt"
        // No real persisted USER ID, a dropped recall carrier, or a prompt from another build are not evidence.
        persistSuccessfulMemoryDelivery(attempt.daily, attempt.prompt, projected, recall, null, now + 1, filesDir = null)
        persistSuccessfulMemoryDelivery(attempt.daily, differentPrompt, plain, recall, user.dbMessageId, now + 1, filesDir = null)
        assertArrayEquals(before, sidecar.readBytes())
        persistSuccessfulMemoryDelivery(attempt.daily, attempt.prompt, projected, recall, user.dbMessageId, now + 10, filesDir = null)
        persistSuccessfulMemoryDelivery(attempt.daily, attempt.prompt, projected, recall, user.dbMessageId, now + 20, filesDir = null)
        val state = MemoryLifecycle.read(dir, file.name)!!.managed().single().state
        assertEquals(1, state.usage)
        assertEquals(now + 10, state.lastUsedAt)
    }

    @Test fun verifiedUsageOnlyCreditsTheWireFrameAndItsConsumingSession() {
        val filesDir = tmp.newFolder("files")
        val shared = MemoryIndex.sharedDir(filesDir).apply { mkdirs() }
        File(shared, "2026-10-10.md").writeText("kiwifruit recommendation\n")
        val engine = MemoryRecallEngine({ shared }, clock = { now })
        val rendered = engine.formatAsPromptFragmentWithHits(engine.recall("kiwifruit"))
        assertEquals(1, rendered.included.size)
        val a = memoryUsageSessionDirectory(filesDir, "session-a")!!
        val b = memoryUsageSessionDirectory(filesDir, "session-b")!!
        val recall = RecallReminder("<system-reminder>\n${rendered.text}\n</system-reminder>", rendered.included, "session-a", now, user.dbMessageId)
        val attempted = listOf(user).withMemoryRecallReminder(recall)
        val mirroredButNotSent = attempted.map { it.copy(contentParts = user.contentParts) }
        assertNull(persistSuccessfulMemoryDelivery(null, null, mirroredButNotSent, recall, user.dbMessageId, now + 1, filesDir))
        assertNull(persistSuccessfulMemoryDelivery(null, null, attempted, recall, "wrong-turn", now + 1, filesDir))
        assertNull(persistSuccessfulMemoryDelivery(null, null, attempted, recall, null, now + 1, filesDir))
        assertEquals(0 to 0, MemoryUsageLedger.verifiedSummaryFor(a))
        repeat(2) {
            assertNotNull(persistSuccessfulMemoryDelivery(null, null, attempted, recall, user.dbMessageId, now + 1, filesDir))
        }
        assertEquals(1 to 1, MemoryUsageLedger.verifiedSummaryFor(a))
        assertEquals(0 to 0, MemoryUsageLedger.verifiedSummaryFor(b))
        assertEquals(0 to 0, MemoryUsageLedger.verifiedSummaryFor(shared))
        val stored = MemoryUsageLedger.verifiedFile(a).readText()
        assertFalse(stored.contains("kiwifruit recommendation"))
        assertFalse(stored.contains(user.dbMessageId!!))
    }

    @Test fun usageSheetReadsVerifiedStatsForTheCapturedChatOffMain() {
        val sheet = ProductionSources.read("ui/chat/TokenUsageSheet.kt")
        assertTrue(sheet.contains("R.string.token_usage_section_memory"))
        assertTrue(sheet.contains("LaunchedEffect(viewModel, session, streaming)"))
        assertTrue(sheet.contains("withContext(Dispatchers.IO)"))
        assertTrue(sheet.contains("memoryUsageSessionDirectory(filesDir, session)?.let(MemoryUsageLedger::verifiedSummaryFor)"))
        val projection = ProductionSources.read("ui/chat/ChatViewModelPlanContextExt.kt")
        assertFalse("render and prefetch must not write usage", projection.contains("MemoryUsageLedger"))
    }

    @Test fun oldRecallRemovedButTranscriptAndToolPairAreUntouched() {
        val old = "<system-reminder>\n[MEMORY_REFERENCE · origin:AUTOMATIC_RECALL]\n$RECALL_NOTE_MARKER\nexpired body\n\n</system-reminder>"
        val goal = "<system-reminder>\n$GOAL_NOTE_MARKER\nkeep\n</system-reminder>"
        val result = AgentContentPart.ToolResult("result-id", "file_read", "preserve", false)
        val original = listOf(user.copy(content = "${user.content}\n\n$old\n\n$goal", contentParts = user.contentParts + AgentContentPart.Text(old) + AgentContentPart.Text(goal)),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(result)))
        val stripped = original.withoutStoredRecallReminders()
        assertTrue(original.first().content.contains("expired body"))
        assertFalse(stripped.first().content.contains("expired body"))
        assertTrue(stripped.first().content.contains(goal))
        val recall = RecallReminder("new frame", emptyList(), "sid", now)
        val projected = stripped.withMemoryRecallReminder(recall)
        assertEquals(listOf(result, AgentContentPart.Text("new frame")), projected.last().contentParts)
        assertTrue(recall.deliveredIn(projected))
        assertFalse(recall.deliveredIn(stripped))
    }
}
