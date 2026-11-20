package com.openminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ChatViewModelDrainQueueTest {
    private val before = QueuedPrompt("before", "prepare first")
    private val after = QueuedPrompt("after", "enqueued during attachment IO")

    @Test
    fun preparedPrefixClaimKeepsAConcurrentEnqueueAndCannotBeClaimedTwice() {
        val queue = MutableStateFlow(listOf(before, after))
        assertTrue(claimPreparedPromptQueue(queue, listOf(before)) { true })
        assertFalse(claimPreparedPromptQueue(queue, listOf(before)) { true })
        assertEquals(listOf(after), queue.value)
    }

    @Test
    fun editingWithdrawingAndChangingOwnerBeforeClaimLeaveQueueAlone() {
        val edited = before.copy(text = "updated while preparing attachments")
        val queue = MutableStateFlow(listOf(edited, after))
        assertFalse(claimPreparedPromptQueue(queue, listOf(before)) { true })
        assertFalse(claimPreparedPromptQueue(queue, listOf(edited)) { false })
        assertEquals(listOf(edited, after), queue.value)
        queue.value = listOf(after)
        assertFalse(claimPreparedPromptQueue(queue, listOf(edited)) { true })
        assertEquals(listOf(after), queue.value)
    }

    @Test
    fun switchAfterPreparationDoesNotAppendOrConsumeUnsentPrompt() = runBlocking {
        val queue = MutableStateFlow(listOf(before))
        val result = persistPreparedQueueBatch(queue, listOf(before), { false }) { error("wrong owner") }
        assertNull(result)
        assertEquals(listOf(before), queue.value)
    }

    @Test
    fun switchImmediatelyAfterCasRestoresTheUnsentPrompt() = runBlocking {
        val queue = MutableStateFlow(listOf(before))
        val result = persistPreparedQueueBatch(queue, listOf(before), { queue.value.isNotEmpty() }) {
            error("do not append after owner changed at the claim boundary")
        }
        assertNull(result)
        assertEquals(listOf(before), queue.value)
    }

    @Test
    fun switchDuringAppendKeepsTheOldDurableRowWithoutReturningItToTheNewUi() = runBlocking {
        val queue = MutableStateFlow(listOf(before))
        val owner = MutableStateFlow("old")
        val rows = mutableListOf<Pair<String, String>>()
        val result = persistPreparedQueueBatch(queue, listOf(before), { owner.value == "old" }) {
            owner.value = "new"
            rows.add("old" to "persisted row")
            "persisted row"
        }
        assertNull(result)
        assertEquals(listOf("old" to "persisted row"), rows)
        assertTrue(queue.value.isEmpty())
    }

    @Test
    fun failedAppendRestoresTheUnsentBatchAheadOfNewPrompts() = runBlocking {
        val queue = MutableStateFlow(listOf(before))
        val failure = IOException("local fake write failed")
        val thrown = runCatching {
            persistPreparedQueueBatch(queue, listOf(before), { true }) {
                queue.value = listOf(after)
                throw failure
            }
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertEquals(failure.message, thrown?.message)
        assertEquals(listOf(before, after), queue.value)
    }

    @Test
    fun cancellationDuringAppendDoesNotCauseASecondAppend() = runBlocking {
        val queue = MutableStateFlow(listOf(before))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val persisted = CompletableDeferred<Unit>()
        val drain = async {
            persistPreparedQueueBatch(queue, listOf(before), { true }) {
                started.complete(Unit)
                finish.await()
                persisted.complete(Unit)
                "durable row"
            }
        }
        started.await()
        drain.cancel()
        finish.complete(Unit)
        drain.join()
        assertTrue(persisted.isCompleted)
        assertTrue(queue.value.isEmpty())
    }

    @Test
    fun promptRefreshSkipsMismatchedOwnerAndRejectsARebuildAfterSwitch() = runBlocking {
        val current = MemoryDeliveryPrompt("old", null, stablePrefixLen = 3)
        val new = MemoryDeliveryPrompt("new", null, stablePrefixLen = 3)
        val owner = MutableStateFlow("old")
        assertSame(current, rebuildOwnedRunPrompt(current, "other", { owner.value }) { error("wrong owner") })
        assertSame(new, rebuildOwnedRunPrompt(current, "old", { owner.value }) { new })
        assertSame(current, rebuildOwnedRunPrompt(current, "old", { owner.value }) { owner.value = "other"; new })
    }

    @Test
    fun promptRefreshRethrowsCancellationAndFallsBackOnOrdinaryFailure() = runBlocking {
        val current = MemoryDeliveryPrompt("old", null)
        assertSame(current, rebuildOwnedRunPrompt(current, "old", { "old" }) { throw IOException() })
        val failure = CancellationException("cancel fake rebuild")
        val thrown = runCatching { rebuildOwnedRunPrompt(current, "old", { "old" }) { throw failure } }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }
}
