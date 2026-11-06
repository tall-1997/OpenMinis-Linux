// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream
// (ui/chat/ProviderKeyEditRebuildTest.kt), adapted: this tree stores the
// credential writes (and the ProviderCredentialEdits.changed signals) in
// ProviderConfigStore.kt, and the open-chat watch lives in
// ChatViewModelProviderEditExt.kt.
package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.data.repository.ProviderCredentialEdits
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-261025-12] After replacing an API key in Settings, Retry in the open
 * chat still sent the old key: the key is not part of ProviderInstance, which
 * is all the chat's edit watch compared.
 */
class ProviderKeyEditRebuildTest {

    @Test fun `saving or removing a key announces its instance`() = runBlocking {
        val got = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { ProviderCredentialEdits.ids.first() } }
        ProviderCredentialEdits.changed("inst-1")
        assertEquals("inst-1", got.await())
        val store = ProductionSources.read("data/repository/ProviderConfigStore.kt")
        assertTrue(
            "save / delete / pool-add / pool-remove must all announce",
            Regex("ProviderCredentialEdits\\.changed\\(instanceId\\)").findAll(store).count() >= 2,
        )
    }

    @Test fun `the open chat treats a key change as an edit of the instance`() {
        val watch = ProductionSources.read("ui/chat/ChatViewModelProviderEditExt.kt")
        assertTrue(watch.contains("ProviderCredentialEdits.ids.collect"))
        assertTrue(watch.contains("pendingProviderEdits = pendingProviderEdits + id"))
        assertTrue(watch.contains("rebuildProviderIfEdited"))
    }
}
