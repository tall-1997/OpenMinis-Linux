package com.openminis.app.quick

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [E-batch-quick-invocation] Quick-prompt session targeting: the newest
 * conversation wins; with no sessions AND no provider there is nothing to run
 * into and the dispatcher aborts with null (the panel keeps the draft).
 *
 * Drives [QuickPromptDispatcher.resolveSessionId] against a real in-memory
 * Room DB. The no-provider path relies on a fresh ProviderRepository under
 * Robolectric having an empty config (no default group, no visible entries).
 */
@RunWith(RobolectricTestRunner::class)
// Plain android.app.Application, NOT MinisApp — same rationale as
// ScheduledNewSessionPersistenceTest (no sandbox wiring under Robolectric).
@Config(sdk = [35], application = android.app.Application::class)
class QuickSessionResolveTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var providers: ProviderRepository
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ChatRepository(
            dao = db.chatDao(),
            goalDao = db.goalDao(),
            filesDir = null,
            db = db,
        )
        providers = ProviderRepository(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun session(id: String, updatedAt: Long) = ChatSessionEntity(
        id = id,
        title = "t-$id",
        modelId = "m",
        createdAt = updatedAt - 1000,
        updatedAt = updatedAt,
        source = "main",
    )

    @Test
    fun `newest session is the quick-send target`() = runBlocking {
        db.chatDao().insertSession(session("older", 1_000L))
        db.chatDao().insertSession(session("newer", 2_000L))
        assertEquals("newer", QuickPromptDispatcher.resolveSessionId(repo, providers, context))
    }

    @Test
    fun `existing session is returned untouched - source column preserved`() = runBlocking {
        db.chatDao().insertSession(session("only", 5_000L))
        val id = QuickPromptDispatcher.resolveSessionId(repo, providers, context)
        assertEquals("only", id)
        // Reuse must not rewrite the origin marker of a human chat.
        assertEquals("main", db.chatDao().getSession("only")?.source)
    }

    @Test
    fun `no sessions and no provider aborts with null`() = runBlocking {
        assertNull(QuickPromptDispatcher.resolveSessionId(repo, providers, context))
    }
}
