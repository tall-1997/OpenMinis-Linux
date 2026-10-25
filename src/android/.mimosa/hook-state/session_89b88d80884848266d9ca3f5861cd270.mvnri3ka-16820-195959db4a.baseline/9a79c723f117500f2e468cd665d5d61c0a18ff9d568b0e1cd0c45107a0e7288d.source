package com.openminis.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ActiveRunPersistenceFenceTest {
    @Test fun `stop closes new writes and drains an already admitted write`() = runBlocking {
        val run = ActiveRunRegistry.begin("session", null)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = AtomicInteger()
        val writer = async {
            run.withPersistencePermit {
                entered.complete(Unit)
                release.await()
                writes.incrementAndGet()
                run.markAssistantTurnPersisted("committed")
            }
        }

        entered.await()
        run.stop()
        val denied = async { run.withPersistencePermit { writes.incrementAndGet() } }

        val drain = async {
            run.awaitPersistenceDrained()
            run.unpersistedAssistantText()
        }
        release.complete(Unit)
        writer.await()
        assertNull(denied.await())
        assertEquals("", drain.await())
        assertEquals(1, writes.get())
        ActiveRunRegistry.finish(run)
    }

    @Test fun `stop before write admission prevents persistence and retains tool for cleanup`() = runBlocking {
        val run = ActiveRunRegistry.begin("session", null)
        run.setCurrentTool("tool-1", "shell", "{}")
        run.stop()
        val result = run.withPersistencePermit { "persisted" }
        assertNull(result)
        assertTrue(run.isStopped)
        ActiveRunRegistry.finish(run)
        assertEquals(ActiveRun.CurrentTool("tool-1", "shell", "{}"), run.currentToolSnapshot())
        ActiveRunRegistry.finish(run)
    }
}
