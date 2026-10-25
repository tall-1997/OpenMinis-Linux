package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the supervisor instruction envelope.
 *
 * An instruction is a message the supervisor sends the worker. It must
 * carry a Task section and an Expected result section (the acceptance
 * criteria the next review will judge against); instructions missing
 * either are rejected back to the supervisor with the reason.
 */
class SupervisorChatInstructionTest {

    private val validEnvelope = """
        ## Task
        - Fix the StorageScanner hang in ScanWorker
        - write_paths: /var/minis/app/storage

        ## Expected result
        - scan completes under 5s on the 2GB fixture
        - no ANR in the stall detector
    """.trimIndent()

    @Test
    fun validEnvelopeParsesTaskAndAcceptance() {
        val check = SupervisorChat.validateInstruction(validEnvelope)
        assertTrue(check is SupervisorChat.InstructionCheck.Valid)
        val instruction = (check as SupervisorChat.InstructionCheck.Valid).instruction
        assertEquals(listOf("Fix the StorageScanner hang in ScanWorker"), instruction.task)
        assertEquals(
            listOf("scan completes under 5s on the 2GB fixture", "no ANR in the stall detector"),
            instruction.acceptance,
        )
    }

    @Test
    fun writePathsBulletIsExtracted() {
        val check = SupervisorChat.validateInstruction(validEnvelope)
        val instruction = (check as SupervisorChat.InstructionCheck.Valid).instruction
        assertEquals(listOf("/var/minis/app/storage"), instruction.writePaths)
    }

    @Test
    fun missingTaskSectionIsRejected() {
        val check = SupervisorChat.validateInstruction(
            """
            ## Expected result
            - scan completes
            """.trimIndent(),
        )
        assertTrue(check is SupervisorChat.InstructionCheck.Rejected)
        val reason = (check as SupervisorChat.InstructionCheck.Rejected).reason
        assertTrue("reason must name the missing section, got: $reason", reason.contains(SubAgentBrief.TASK))
    }

    @Test
    fun missingExpectedSectionIsRejected() {
        val check = SupervisorChat.validateInstruction(
            """
            ## Task
            - do the thing
            """.trimIndent(),
        )
        assertTrue(check is SupervisorChat.InstructionCheck.Rejected)
        val reason = (check as SupervisorChat.InstructionCheck.Rejected).reason
        assertTrue("reason must name the missing section, got: $reason", reason.contains(SubAgentBrief.EXPECTED))
    }

    @Test
    fun sectionsInReversedOrderAreStillValid() {
        val check = SupervisorChat.validateInstruction(
            """
            ## Expected result
            - scan completes

            ## Task
            - do the thing
            """.trimIndent(),
        )
        assertTrue(check is SupervisorChat.InstructionCheck.Valid)
    }

    @Test
    fun emptyBodyIsRejected() {
        val check = SupervisorChat.validateInstruction("   ")
        assertTrue(check is SupervisorChat.InstructionCheck.Rejected)
    }

    @Test
    fun emptyTaskBodyIsRejected() {
        val check = SupervisorChat.validateInstruction(
            """
            ## Task

            ## Expected result
            - scan completes
            """.trimIndent(),
        )
        assertTrue(check is SupervisorChat.InstructionCheck.Rejected)
    }
}
