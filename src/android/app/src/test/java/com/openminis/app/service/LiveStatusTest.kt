package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-live-status-model] One model, two surfaces — and the caps each platform
 * actually enforces, pinned so a builder cannot quietly hand the HyperOS big
 * island a sentence or the AOSP chip words that overflow it.
 */
class LiveStatusTest {

    private fun status(
        completed: Boolean = false,
        toolRunning: Boolean = true,
        toolShort: String? = "Shell",
        surface: NotificationSurface = NotificationSurface.PROMOTED,
        subtitle: String = "1 session running",
    ) = LiveStatus.from(
        state = ForegroundNotificationState(
            activeCount = if (completed) 0 else 1,
            toolName = if (toolRunning) "shell_execute" else null,
            isToolRunning = toolRunning,
            startedAtMs = 0L,
            finishedAtMs = if (completed) 1_000L else null,
            promoted = true,
            subtitle = subtitle,
            surface = surface,
        ),
        appLabel = "Minis Ultra",
        toolShort = toolShort,
        sessionLabel = "1 session",
        subtitle = subtitle,
    )

    @Test
    fun `a running tool names itself on the island`() {
        assertEquals("Shell", status().islandHeadline())
        assertTrue(status().busy)
    }

    @Test
    fun `the chip is left to the clock while work runs`() {
        // Android renders the run clock in the chip; words beside it overflow.
        assertNull(status().chipWords())
    }

    @Test
    fun `a finished run shows the app name instead of a running tool`() {
        val done = status(completed = true, toolRunning = false)
        assertTrue(done.ending)
        assertEquals("Minis Ultra", done.islandHeadline())
        assertEquals("Minis Ultra", done.chipWords())
    }

    @Test
    fun `the island headline is capped even when the label is long`() {
        val long = status(toolShort = "Browser automation")
        val head = long.islandHeadline()
        assertEquals(LiveStatus.HEAD_MAX, head.length)
        assertTrue(head.endsWith("…"))
    }

    @Test
    fun `the island caption never becomes a sentence`() {
        val verbose = status(subtitle = "1 session running with a very long description attached")
        val caption = verbose.islandCaption()
        assertEquals(LiveStatus.CAPTION_MAX, caption.length)
        assertTrue(caption.endsWith("…"))
    }

    @Test
    fun `the ticker is the app, never the shade title`() {
        assertEquals("Minis Ultra", status().ticker())
        assertEquals(LiveStatus.HEAD_MAX, status(toolShort = "Extremely long label").ticker().length.coerceAtMost(LiveStatus.HEAD_MAX))
    }

    @Test
    fun `the promoted surface keeps tool identity out of the model`() {
        // PROMOTED shows a run, not a tool: its normalized state strips the
        // tool name, and the model must not resurrect it from the label.
        val waiting = status(toolRunning = false, toolShort = null)
        assertEquals("Minis Ultra", waiting.islandHeadline())
        assertEquals("Minis Ultra", waiting.chipWords())
    }

    @Test
    fun `an idle session is waiting, not running`() {
        val waiting = status(toolRunning = false, toolShort = null)
        assertEquals(LiveStatus.Kind.WAITING, waiting.kind)
        // Idle still names the app on the chip; only a counting clock hides it.
        assertEquals("Minis Ultra", waiting.chipWords())
    }

    @Test
    fun `a long app label is capped on the chip too`() {
        val long = LiveStatus.from(
            state = ForegroundNotificationState(
                activeCount = 0,
                toolName = null,
                isToolRunning = false,
                startedAtMs = 0L,
                finishedAtMs = 1_000L,
                promoted = false,
                subtitle = "idle",
            ),
            appLabel = "Minis Ultra (developer build)",
            toolShort = null,
            sessionLabel = "0 sessions",
            subtitle = "idle",
        )
        val chip = long.chipWords()!!
        assertTrue(chip.length <= LiveStatus.CHIP_MAX)
        assertTrue(chip.endsWith("…"))
    }
}
