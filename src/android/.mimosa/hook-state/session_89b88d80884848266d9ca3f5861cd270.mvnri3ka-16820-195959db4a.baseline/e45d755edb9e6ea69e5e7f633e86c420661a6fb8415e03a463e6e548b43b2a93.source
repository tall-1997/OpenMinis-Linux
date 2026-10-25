package com.openminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the scene-announcement layer.
 *
 * Deliberately no Context, no Robolectric, no TTS: [VoiceAnnouncementPrefs]'s
 * strings and decision and [AnnouncementPrefs] are framework-free, and the
 * coordinator is exercised through its `(String) -> Unit` constructor. The
 * SharedPreferences entry points ([VoiceAnnouncementPrefs.load] /
 * [VoiceAnnouncementPrefs.setEnabled] / [VoiceAnnouncementPrefs.isEnabled]) are
 * therefore NOT covered here — that is the documented trade-off, not an
 * oversight.
 */
class VoiceAnnouncementPrefsTest {

    // -- shouldAnnounce / AnnoucementPrefs.isEnabled --

    @Test
    fun `shouldAnnounce follows an explicit true`() {
        val prefs = AnnouncementPrefs(mapOf(AnnouncementScene.TASK_COMPLETED to true))
        assertTrue(VoiceAnnouncementPrefs.shouldAnnounce(AnnouncementScene.TASK_COMPLETED, prefs))
    }

    @Test
    fun `shouldAnnounce follows an explicit false`() {
        val prefs = AnnouncementPrefs(mapOf(AnnouncementScene.REMINDER_DUE to false))
        assertFalse(VoiceAnnouncementPrefs.shouldAnnounce(AnnouncementScene.REMINDER_DUE, prefs))
    }

    /** An absent entry falls back to the scene's own default, not to "off". */
    @Test
    fun `a missing entry falls back to defaultEnabled`() {
        val empty = AnnouncementPrefs(emptyMap())
        assertTrue(VoiceAnnouncementPrefs.shouldAnnounce(AnnouncementScene.REMINDER_DUE, empty))
        assertFalse(VoiceAnnouncementPrefs.shouldAnnounce(AnnouncementScene.TASK_COMPLETED, empty))
        assertFalse(VoiceAnnouncementPrefs.shouldAnnounce(AnnouncementScene.ERROR_OCCURRED, empty))
        assertFalse(VoiceAnnouncementPrefs.shouldAnnounce(AnnouncementScene.IDLE_AGENT_DONE, empty))
    }

    /** A partial map must not disturb the scenes it doesn't mention. */
    @Test
    fun `a partial map leaves other scenes at their defaults`() {
        val prefs = AnnouncementPrefs(mapOf(AnnouncementScene.TASK_COMPLETED to true))
        assertTrue(VoiceAnnouncementPrefs.shouldAnnounce(AnnouncementScene.TASK_COMPLETED, prefs))
        assertTrue(VoiceAnnouncementPrefs.shouldAnnounce(AnnouncementScene.REMINDER_DUE, prefs))
        assertFalse(VoiceAnnouncementPrefs.shouldAnnounce(AnnouncementScene.ERROR_OCCURRED, prefs))
    }

    @Test
    fun `all scenes can be toggled independently`() {
        val all = AnnouncementScene.entries.associateWith { true }
        val prefs = AnnouncementPrefs(all)
        AnnouncementScene.entries.forEach {
            assertTrue("$it should be on", VoiceAnnouncementPrefs.shouldAnnounce(it, prefs))
        }

        val onlyErrors = AnnouncementPrefs(all + (AnnouncementScene.ERROR_OCCURRED to false))
        assertFalse(VoiceAnnouncementPrefs.shouldAnnounce(AnnouncementScene.ERROR_OCCURRED, onlyErrors))
        assertTrue(VoiceAnnouncementPrefs.shouldAnnounce(AnnouncementScene.TASK_COMPLETED, onlyErrors))
    }

    @Test
    fun `AnnouncementPrefs isEnabled mirrors shouldAnnounce`() {
        val prefs = AnnouncementPrefs(mapOf(AnnouncementScene.ERROR_OCCURRED to true))
        AnnouncementScene.entries.forEach {
            assertEquals(
                VoiceAnnouncementPrefs.shouldAnnounce(it, prefs),
                prefs.isEnabled(it),
            )
        }
    }

    // -- scene metadata --

    @Test
    fun `scene keys are unique and non-blank`() {
        val keys = AnnouncementScene.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { assertTrue("blank key in $keys", it.isNotBlank()) }
    }

    @Test
    fun `defaultEnabled values match intent`() {
        assertFalse(AnnouncementScene.TASK_COMPLETED.defaultEnabled)
        assertFalse(AnnouncementScene.ERROR_OCCURRED.defaultEnabled)
        assertTrue(AnnouncementScene.REMINDER_DUE.defaultEnabled)
        assertFalse(AnnouncementScene.IDLE_AGENT_DONE.defaultEnabled)
    }

    // -- announceText --

    @Test
    fun `task completed ignores detail`() {
        assertEquals("任务已完成", VoiceAnnouncementPrefs.announceText(AnnouncementScene.TASK_COMPLETED))
        assertEquals(
            "任务已完成",
            VoiceAnnouncementPrefs.announceText(AnnouncementScene.TASK_COMPLETED, "构建"),
        )
    }

    @Test
    fun `error occurred interpolates its detail`() {
        assertEquals(
            "执行出错：网络超时",
            VoiceAnnouncementPrefs.announceText(AnnouncementScene.ERROR_OCCURRED, "网络超时"),
        )
    }

    @Test
    fun `error occurred without a detail`() {
        assertEquals("执行出错", VoiceAnnouncementPrefs.announceText(AnnouncementScene.ERROR_OCCURRED))
        assertEquals("执行出错", VoiceAnnouncementPrefs.announceText(AnnouncementScene.ERROR_OCCURRED, null))
        assertEquals("执行出错", VoiceAnnouncementPrefs.announceText(AnnouncementScene.ERROR_OCCURRED, "   "))
    }

    @Test
    fun `reminder due distinguishes detail from no-detail`() {
        assertEquals(
            "提醒：交电费",
            VoiceAnnouncementPrefs.announceText(AnnouncementScene.REMINDER_DUE, "交电费"),
        )
        assertEquals("你有新的提醒", VoiceAnnouncementPrefs.announceText(AnnouncementScene.REMINDER_DUE))
        assertEquals("你有新的提醒", VoiceAnnouncementPrefs.announceText(AnnouncementScene.REMINDER_DUE, null))
        assertEquals("你有新的提醒", VoiceAnnouncementPrefs.announceText(AnnouncementScene.REMINDER_DUE, ""))
    }

    @Test
    fun `idle agent done is fixed text regardless of detail`() {
        val expected = "任务完成，回来查看结果吧"
        assertEquals(expected, VoiceAnnouncementPrefs.announceText(AnnouncementScene.IDLE_AGENT_DONE))
        assertEquals(expected, VoiceAnnouncementPrefs.announceText(AnnouncementScene.IDLE_AGENT_DONE, "x"))
        assertEquals(expected, VoiceAnnouncementPrefs.announceText(AnnouncementScene.IDLE_AGENT_DONE, null))
    }

    /** Every scene must have a non-empty phrase — silence here is a bug. */
    @Test
    fun `every scene has a non-blank phrase`() {
        AnnouncementScene.entries.forEach {
            assertTrue("empty phrase for $it", VoiceAnnouncementPrefs.announceText(it).isNotBlank())
            assertTrue("empty phrase for $it with detail", VoiceAnnouncementPrefs.announceText(it, "d").isNotBlank())
        }
    }

    // -- coordinator --

    private class RecordingSpeaker {
        val spoken = mutableListOf<String>()
        val speak: (String) -> Unit = { spoken.add(it) }
    }

    @Test
    fun `coordinator speaks when the scene is enabled`() {
        val sink = RecordingSpeaker()
        val coordinator = VoiceAnnouncementCoordinator(sink.speak)
        val prefs = AnnouncementPrefs(mapOf(AnnouncementScene.TASK_COMPLETED to true))

        val announced = coordinator.onScene(AnnouncementScene.TASK_COMPLETED, "构建", prefs)

        assertTrue(announced)
        assertEquals(listOf("任务已完成"), sink.spoken)
    }

    @Test
    fun `coordinator passes the detail through to the phrase`() {
        val sink = RecordingSpeaker()
        val coordinator = VoiceAnnouncementCoordinator(sink.speak)
        val prefs = AnnouncementPrefs(mapOf(AnnouncementScene.REMINDER_DUE to true))

        val announced = coordinator.onScene(AnnouncementScene.REMINDER_DUE, "喝水", prefs)

        assertTrue(announced)
        assertEquals(listOf("提醒：喝水"), sink.spoken)
    }

    @Test
    fun `coordinator stays silent when the scene is disabled`() {
        val sink = RecordingSpeaker()
        val coordinator = VoiceAnnouncementCoordinator(sink.speak)
        val prefs = AnnouncementPrefs(mapOf(AnnouncementScene.REMINDER_DUE to false))

        val announced = coordinator.onScene(AnnouncementScene.REMINDER_DUE, "喝水", prefs)

        assertFalse(announced)
        assertTrue("nothing should be spoken", sink.spoken.isEmpty())
    }

    /** Default-off scenes (the empty snapshot) must not speak. */
    @Test
    fun `an empty prefs snapshot keeps opt-in scenes silent`() {
        val sink = RecordingSpeaker()
        val coordinator = VoiceAnnouncementCoordinator(sink.speak)
        val prefs = AnnouncementPrefs()

        assertFalse(coordinator.onScene(AnnouncementScene.ERROR_OCCURRED, "boom", prefs))
        assertFalse(coordinator.onScene(AnnouncementScene.TASK_COMPLETED, null, prefs))
        assertFalse(coordinator.onScene(AnnouncementScene.IDLE_AGENT_DONE, null, prefs))
        assertTrue(sink.spoken.isEmpty())
    }

    /**
     * A broken engine (or any sink that throws) must not take down the caller's
     * event loop — the announcement is best-effort. The return value still
     * reports the GATE: the decision was "announce".
     */
    @Test
    fun `a throwing sink does not propagate and still reports the gate`() {
        val coordinator = VoiceAnnouncementCoordinator { error("engine not initialized") }
        val prefs = AnnouncementPrefs(mapOf(AnnouncementScene.TASK_COMPLETED to true))

        val announced = coordinator.onScene(AnnouncementScene.TASK_COMPLETED, null, prefs)

        assertTrue(announced)
    }

    @Test
    fun `a throwing sink is not consulted for a disabled scene`() {
        var calls = 0
        val coordinator = VoiceAnnouncementCoordinator {
            calls++
            error("must never be reached")
        }
        val prefs = AnnouncementPrefs(mapOf(AnnouncementScene.TASK_COMPLETED to false))

        val announced = coordinator.onScene(AnnouncementScene.TASK_COMPLETED, null, prefs)

        assertFalse(announced)
        assertEquals(0, calls)
    }
}
