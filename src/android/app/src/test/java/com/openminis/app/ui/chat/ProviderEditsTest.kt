// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream
// (ui/chat/ProviderEditsTest.kt), adapted: this tree keeps the edit watch in
// ChatViewModelProviderEditExt.kt and calls rebuildProviderIfEdited() from
// four turn entry points (send / retry / rerun / resume) across three files.
package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** An open chat picks up a provider edited in Settings before its next turn. */
class ProviderEditsTest {

    private fun inst(id: String, responses: Boolean = false) = ProviderInstance(
        id = id,
        label = id,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        useResponsesAPI = responses,
    )

    @Test
    fun `switching the API format is an edit`() {
        val before = mapOf("a" to inst("a"), "b" to inst("b"))
        val now = mapOf("a" to inst("a", responses = true), "b" to inst("b"))
        assertEquals(setOf("a"), editedProviderInstanceIds(before, now))
    }

    @Test
    fun `adding or removing a provider is not an edit`() {
        val before = mapOf("a" to inst("a"))
        assertTrue(editedProviderInstanceIds(before, mapOf("a" to inst("a"), "c" to inst("c"))).isEmpty())
        assertTrue(editedProviderInstanceIds(before, emptyMap()).isEmpty())
    }

    @Test
    fun `a base URL or user agent change is an edit`() {
        val before = mapOf("a" to inst("a"))
        val now = mapOf("a" to inst("a").copy(customBaseURL = "https://relay.example.com/v1"))
        assertEquals(setOf("a"), editedProviderInstanceIds(before, now))
    }

    @Test
    fun `send, retry, rerun and resume check for an edit before taking the provider`() {
        val root = ProductionSources.mainRoot() ?: error("production source root not found")
        val turnEntryFiles = listOf(
            File(root, "ui/chat/ChatViewModel.kt"),
            File(root, "ui/chat/ChatViewModelSendExt.kt"),
            File(root, "ui/chat/ChatViewModelRetryExt.kt"),
            File(root, "ui/chat/ChatViewModelResumeQueueExt.kt"),
        )
        val calls = turnEntryFiles.sumOf {
            Regex("\\n\\s*rebuildProviderIfEdited\\(\\)\\n").findAll(it.readText()).count()
        }
        assertTrue("expected >= 4 turn entry points to rebuild after an edit, found $calls", calls >= 4)
    }
}
