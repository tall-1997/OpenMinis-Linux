 package com.openminis.app.ui.chat

 import com.openminis.app.ProductionSources
 import org.junit.Assert.assertFalse
 import org.junit.Assert.assertTrue
 import org.junit.Test

 /**
  * [T-session-title-not-model-input] G12 as an assertion.
  *
  * The reference contract is explicit: session titles persist through replay,
  * resume and paging but NEVER enter model input. Ours is generated inside the
  * agent loop (turn ==0), which is exactly the place where a stray append would
  * ship an assistant-facing artifact to the model and never be noticed — the
  * title is short, plausible prose that reads like context.
  *
  * A behavioural test cannot reach it: `generateSessionTitleIfNeeded` needs a
  * Context, a provider chain, a DB and a coroutine scope. What IS checkable, and
  * is the actual invariant, is that the title path has no write into the history
  * at all — so the test pins the source, the same way the compaction wiring test
  * does.
  */
 class TitleNotInModelInputTest {

    /** Every production file allowed to touch the outgoing history. */
    private val titleSources = listOf(
        "ui/chat/ChatViewModelTitleExt.kt",
        "ui/chat/TitlePromptLocale.kt",
    )

    @Test
    fun theTitlePathNeverWritesToTheAgentHistory() {
        for (rel in titleSources) {
            val src = ProductionSources.read(rel)
            assertFalse(
                "$rel must not append to agentHistory (title is UI metadata, not model input)",
                src.contains("agentHistory +=") || src.contains("agentHistory.add(") ||
                src.contains("appendBoundedHistory(") || src.contains("agentHistory = "),
            )
        }
    }

    @Test
    fun theTitleIsOnlyEverPublishedThroughTheUiState() {
        val src = ProductionSources.read("ui/chat/ChatViewModelTitleExt.kt")
        assertTrue("the title must reach the UI state", src.contains("_sessionTitle.value ="))
    }

    @Test
    fun theTitlePromptIsBuiltFromMessagesNotFromTheHistoryList() {
        // The prompt input is _messages (the UI projection). Reading agentHistory
        // here would be the first step toward echoing it back into the request.
        val src = ProductionSources.read("ui/chat/ChatViewModelTitleExt.kt")
        assertTrue(src.contains("val msgs = _messages.value"))
        assertFalse(src.contains("effectiveAgentHistory()"))
    }
 }
