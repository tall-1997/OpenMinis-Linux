package com.openminis.app.ui.chat

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-job-awareness] Jobs are listed so the model reads them instead of polling,
 * and the block stays cheap: ids, states and clipped commands, no output.
 */
class BackgroundJobsPromptTest {

    private fun row(id: String, state: String = "RUNNING", exit: Int? = null, command: String = "npm test") =
        BackgroundJobsPrompt.Row(id = id, state = state, exitCode = exit, command = command)

    @Test
    fun noJobsRendersNothing() {
        assertNull(BackgroundJobsPrompt.render(emptyList()))
    }

    @Test
    fun aJobIsListedWithStateAndCommand() {
        val text = BackgroundJobsPrompt.render(listOf(row("job-1", exit = 0, command = "npm test")))!!
        assertTrue(text.contains("<jobs>"))
        assertTrue(text.contains("job-1"))
        assertTrue(text.contains("RUNNING") || text.contains("EXITED"))
        assertTrue(text.contains("npm test"))
        assertTrue(text.contains("job_output"))
    }

    @Test
    fun theListIsCapped() {
        val many = (1..BackgroundJobsPrompt.MAX_JOBS + 3).map { row("job-$it") }
        val text = BackgroundJobsPrompt.render(many)!!
        assertTrue(text.contains("and 3 more"))
        assertTrue(!text.contains("job-${BackgroundJobsPrompt.MAX_JOBS + 3}"))
    }

    @Test
    fun longCommandsAreClipped() {
        val text = BackgroundJobsPrompt.render(
            listOf(row("job-1", command = "x".repeat(BackgroundJobsPrompt.MAX_COMMAND_CHARS + 40))),
        )!!
        assertTrue(text.contains("x".repeat(BackgroundJobsPrompt.MAX_COMMAND_CHARS)))
        assertTrue(!text.contains("x".repeat(BackgroundJobsPrompt.MAX_COMMAND_CHARS + 1)))
    }
}
