package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanDiscussionTriggerTest {

    @Test
    fun offNeverRuns() {
        assertFalse(PlanDiscussionTrigger.shouldRun(PlanDiscussionPrefs.Mode.OFF, "implement the storage scanner"))
    }

    @Test
    fun alwaysRunsNonEmpty() {
        assertTrue(PlanDiscussionTrigger.shouldRun(PlanDiscussionPrefs.Mode.ALWAYS, "ok"))
        assertFalse(PlanDiscussionTrigger.shouldRun(PlanDiscussionPrefs.Mode.ALWAYS, "  "))
    }

    @Test
    fun autoSkipsChitchatAndShort() {
        assertFalse(PlanDiscussionTrigger.shouldRun(PlanDiscussionPrefs.Mode.AUTO, "thanks"))
        assertFalse(PlanDiscussionTrigger.shouldRun(PlanDiscussionPrefs.Mode.AUTO, "好的"))
        assertFalse(PlanDiscussionTrigger.shouldRun(PlanDiscussionPrefs.Mode.AUTO, "hi there"))
        assertTrue(
            PlanDiscussionTrigger.shouldRun(
                PlanDiscussionPrefs.Mode.AUTO,
                "帮我排查 StorageScanner 为什么会 hang，并给出修复方案",
            ),
        )
        assertTrue(
            PlanDiscussionTrigger.shouldRun(
                PlanDiscussionPrefs.Mode.AUTO,
                "Please implement a robust retry path for the Android export flow including tests.",
            ),
        )
    }
}
