package com.openminis.app.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

/**
 * [T-android-subagent-attribution] Pins the session attribution contract of
 * [CronJobTool]: a created task must be recorded against the OWNER chat
 * (`ExecutionCoordinator.ownerSessionId` normalizes a sub-agent lane id to
 * its parent) so fires land in — and run records / notification deep links
 * point at — the chat that scheduled them. A blank session (no chat context)
 * keeps the legacy fresh-chat target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CronJobToolTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ScheduledTaskStore(context).clear()
    }

    private fun createArgs(prompt: String = "check the build"): String = JSONObject()
        .put("action", "create")
        .put("name", "nightly check")
        .put("schedule", "30m")
        .put("prompt", prompt)
        .toString()

    private fun storedTasks(): List<com.openminis.app.scheduled.ScheduledTask> =
        ScheduledTaskManager(context).list()

    @Test
    fun createFromLaneSessionAttributesTaskToOwnerChat() {
        val owner = "92345ace-1111-2222-3333-444444444444"
        val result = CronJobTool.execute(
            createArgs(),
            "subagent:$owner:2",
            context,
        )
        assertTrue(result.success)
        val tasks = storedTasks()
        assertEquals(1, tasks.size)
        val mode = tasks.single().targetMode
        assertTrue("expected AppendToSession(owner) but was $mode", mode is ScheduledTargetMode.AppendToSession)
        assertEquals(owner, (mode as ScheduledTargetMode.AppendToSession).sessionId)
    }

    @Test
    fun createFromPlainSessionRecordsThatSessionDirectly() {
        val owner = "92345ace-1111-2222-3333-444444444444"
        val result = CronJobTool.execute(createArgs(), owner, context)
        assertTrue(result.success)
        val mode = storedTasks().single().targetMode
        assertEquals(ScheduledTargetMode.AppendToSession(owner), mode)
    }

    @Test
    fun createWithBlankSessionKeepsFreshChatTarget() {
        val result = CronJobTool.execute(createArgs(), "", context)
        assertTrue(result.success)
        val mode = storedTasks().single().targetMode
        assertEquals(ScheduledTargetMode.NewSession, mode)
    }

    @Test
    fun listSurfacesTheOwningSession() {
        val owner = "92345ace-1111-2222-3333-444444444444"
        CronJobTool.execute(createArgs(), "subagent:$owner:7", context)
        val result = CronJobTool.execute("""{"action":"list"}""", owner, context)
        assertTrue(result.success)
        val first = JSONObject(result.output).getJSONArray("tasks").getJSONObject(0)
        assertEquals(owner, first.getString("target_session"))
    }

    @Test
    fun removeStillDeletesByPrefixUnderNewSignature() {
        val owner = "92345ace-1111-2222-3333-444444444444"
        val created = CronJobTool.execute(createArgs(), owner, context)
        assertTrue(created.success)
        val id = JSONObject(created.output).getString("id")
        val removed = CronJobTool.execute(
            JSONObject().put("action", "remove").put("id", id.take(8)).toString(),
            owner,
            context,
        )
        assertTrue(removed.success)
        assertNull(ScheduledTaskManager(context).get(id))
    }
}
