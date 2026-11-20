package com.openminis.app.ui.chat

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedHistoryPublicationTest {
    @Test
    fun aLateReadCannotReplaceTheNewRunInTheSameSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val epoch = AtomicInteger(1)
            val started = CompletableDeferred<Unit>()
            val finishRead = CompletableDeferred<Unit>()
            var resident = listOf("initial history")
            val oldRead = async {
                publishOwnedHistory(owns = { epoch.get() == 1 }, load = {
                    started.complete(Unit)
                    finishRead.await()
                    listOf("old read")
                }) { resident = it }
            }
            started.await()
            epoch.incrementAndGet()
            assertTrue(publishOwnedHistory({ epoch.get() == 2 }, { listOf("new history") }) { resident = it })
            finishRead.complete(Unit)
            assertFalse(oldRead.await())
            assertEquals(listOf("new history"), resident)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aFailedReadDoesNotClearTheResidentHistory() = runTest {
        val resident = mutableListOf("still present")
        val failure = runCatching {
            publishOwnedHistory<List<String>>({ true }, { throw IOException("could not read history") }) {
                resident.clear()
                resident.addAll(it)
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(listOf("still present"), resident)
    }
}
