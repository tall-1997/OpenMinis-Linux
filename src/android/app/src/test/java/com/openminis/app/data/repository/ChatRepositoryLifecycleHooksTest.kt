package com.openminis.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.agent.lifecycle.LifecycleHooks
import com.openminis.app.data.db.AppDatabase
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
 * [GH#131] The two repository fire points: a session row becoming real and a
 * conversation being deleted.
 *
 * 中文：#131 的会话事件不能只靠“看代码”保证——createSession 的每一种创建路径
 * （用户草稿转正 / 定时任务 / 自动化）和 deleteSession（含多选删除）都必须
 * 各触发一次 SESSION_START / SESSION_END，且带对会话 id 与 detail。
 *
 * 这里用真 Room（Robolectric 内存库）驱动仓库本身，而不是直接调用
 * LifecycleHooks.fire，避免“fire 的单测通过、接线却漏了”的假阳性。
 */
@RunWith(RobolectricTestRunner::class)
// 与 QuickSessionResolveTest 相同：普通 Application，不跑 MinisApp 的沙箱初始化。
@Config(sdk = [35], application = android.app.Application::class)
class ChatRepositoryLifecycleHooksTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var context: Context
    private val recorder = LifecycleEventRecorder()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ChatRepository(
            dao = db.chatDao(),
            goalDao = db.goalDao(),
            filesDir = null,
            db = db,
        )
        LifecycleHooks.prime(context)
        LifecycleHooks.registerListener(recorder)
    }

    @After
    fun tearDown() {
        LifecycleHooks.unregisterListener(recorder)
        // Scripts and config live in SharedPreferences; leave the shared object
        // disabled for every other test in this class/sandbox.
        if (::context.isInitialized) {
            LifecycleHooks.setEnabled(context, false)
            LifecycleHooks.setCommand(context, LifecycleHooks.LifecycleEvent.RESPONSE, "")
        }
        LifecycleHooks.scriptRunnerForTest = null
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `createSession fires one SESSION_START with the new id and title`() = runBlocking {
        val session = repo.createSession(modelId = "test-model", title = "Hello hooks")

        assertEquals(
            listOf(
                LifecycleEventRecord(
                    LifecycleHooks.LifecycleEvent.SESSION_START,
                    session.id,
                    "Hello hooks",
                ),
            ),
            recorder.events,
        )
    }

    @Test
    fun `deleteSession fires one SESSION_END only after the row is gone`() = runBlocking {
        val session = repo.createSession(modelId = "test-model", title = "Bye")
        recorder.events.clear()

        repo.deleteSession(session.id)

        // The hook contract: fired after the DB commit, so a script can rely on
        // the conversation no longer existing (see ChatRepository.deleteSession).
        assertNull(db.chatDao().getSession(session.id))
        assertEquals(
            listOf(LifecycleEventRecord(LifecycleHooks.LifecycleEvent.SESSION_END, session.id, "deleted")),
            recorder.events,
        )
    }
}

/** [GH#131] One delivered lifecycle event, compared by value. */
private data class LifecycleEventRecord(
    val event: LifecycleHooks.LifecycleEvent,
    val sessionId: String,
    val detail: String,
)

/** [GH#131] Records every delivery so assertions can compare the full payload. */
private class LifecycleEventRecorder : LifecycleHooks.LifecycleListener {
    val events = mutableListOf<LifecycleEventRecord>()

    override fun onEvent(
        event: LifecycleHooks.LifecycleEvent,
        sessionId: String,
        detail: String,
    ) {
        events += LifecycleEventRecord(event, sessionId, detail)
    }
}
