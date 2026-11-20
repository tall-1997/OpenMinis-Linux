package com.openminis.app.data

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactGateArchitectureTest {
    @Test
    fun `coverage measurement and formal requests do not budget away active rows`() {
        val token = ProductionSources.read("ui/chat/ChatViewModelTokenExt.kt")
        assertTrue(token.contains("applyTailBudget = false"))
        assertTrue(token.contains("PolicyMeasurement.REQUEST -> estimateAgentHistoryTokens()"))
        val projection = ProductionSources.read("ui/chat/ChatViewModelRequestProjection.kt")
        assertTrue(projection.contains("applyTailBudget = false"))
        val history = ProductionSources.read("ui/chat/ChatViewModelAgentHistoryExt.kt")
        assertTrue(history.contains("applyTailBudget: Boolean = true"))
        assertTrue(history.contains("PostAnchorTail.budgetTokensForWindow("))
        assertTrue(history.contains("detachedTailStartForWindow(history, windowTokens, applyTailBudget, harnessConfig)"))
        assertTrue(history.contains("if (forRequest && cut > 0)"))
        assertFalse(history.contains("stateSnapshotSha = null"))
    }

    @Test
    fun `reduced compaction anchors only over the summarized prefix`() {
        val twoShot = ProductionSources.read("ui/chat/ChatViewModelTwoShotCompactExt.kt")
        assertTrue(twoShot.contains("CompactOutcome(text, input.size)"))
        assertTrue(twoShot.contains("throw lastError"))
        assertFalse(twoShot.contains("Truncating older context"))
        assertFalse(twoShot.contains("before dropping older context"))
        val compact = ProductionSources.read("ui/chat/ChatViewModelCompactExt.kt")
        assertTrue(compact.contains("cappedCompactEnd("))
        assertTrue(compact.contains("coveredCountFor(outcome, input.size)"))
        assertTrue(compact.contains("takeIf(boundaries::isClosed)"))
        assertTrue(compact.contains("source, coveredCount, outcome.summary"))
        val reducer = ProductionSources.read("data/repository/ChatRepositoryCompact.kt")
        assertTrue(reducer.contains("source.rows[coveredCount - 1]"))
        assertTrue(reducer.contains("compactedCount = coveredCount"))
        val commit = compact.indexOf("chatRepository.commitCompactPrefix(")
        assertTrue(commit >= 0 && compact.indexOf("_cachedLatestMarker = marker") > commit)
        assertTrue(compact.contains("MemorySediment.recordCompaction(memory, marker.summary, coveredCount)"))
    }

    @Test
    fun `compact body digest verification stays outside the marker write transaction`() {
        val reducer = ProductionSources.read("data/repository/ChatRepositoryCompact.kt")
        val preVerify = reducer.indexOf("verifyCompactRows(source.sessionId, source.start, expected)")
        val txnOpen = reducer.indexOf("withTransaction {", preVerify)
        assertTrue("digest verification must run before the write transaction opens", preVerify in 0 until txnOpen)
        val functionEnd = reducer.indexOf("private fun reduceCompactMarker", txnOpen)
        val inside = reducer.substring(txnOpen, if (functionEnd > txnOpen) functionEnd else reducer.length)
        assertFalse(inside.contains("verifyCompactRows") || inside.contains("Digest.sha256Hex") ||
            inside.contains("compactInlineDigest") || inside.contains("resolveCompactStart"))
        assertTrue(inside.contains("dao.compactSourceRows(source.sessionId, source.start"))
        assertTrue(reducer.contains("Conversation rows changed while summarizing. Retry compaction."))
    }

    @Test
    fun `failure notices do not advertise truncation`() {
        val main = ProductionSources.mainRoot() ?: error("production root missing")
        val res = java.io.File(main, "../../../../res")
        assertTrue(res.isDirectory)
        res.listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty().forEach { dir ->
            val strings = java.io.File(dir, "strings.xml")
            if (strings.isFile) {
                val text = strings.readText()
                assertFalse("$strings still claims truncation", text.contains("older context is truncated") ||
                    text.contains("truncate older context"))
            }
        }
    }

    @Test
    fun `standing rules are assembled regardless of the memory toggle`() {
        val prompt = ProductionSources.read("ui/chat/ChatViewModelPromptExt.kt")
        assertFalse(prompt.contains("val memoryAssembly = if (memoryOn)"))
        assertTrue(prompt.contains("liveRecallInput()?.query.takeIf { memoryOn }, standing, memoryOn"))
        val diary = ProductionSources.read("ui/chat/ChatViewModelMemoryDeliveryExt.kt")
        assertTrue(diary.contains("val daily = if (enabled) session.loadRecentDailyFragments("))
    }
}
