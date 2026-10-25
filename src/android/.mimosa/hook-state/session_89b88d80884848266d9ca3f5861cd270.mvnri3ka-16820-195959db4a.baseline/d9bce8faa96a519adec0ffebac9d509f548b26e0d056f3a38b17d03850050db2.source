package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentBriefTest {

    @Test
    fun wrapAddsRequiredSections() {
        val out = SubAgentBrief.wrap(
            "Fix StorageScanner hang",
            kind = SubAgentKind.WORKER,
            role = "Android reviewer",
            writePaths = listOf("/var/minis/app"),
        )
        assertTrue(out.contains(SubAgentBrief.TASK))
        assertTrue(out.contains(SubAgentBrief.EXPECTED))
        assertTrue(out.contains(SubAgentBrief.CONSTRAINTS))
        assertTrue(out.contains(SubAgentBrief.WORKFLOW))
        assertTrue(out.contains(SubAgentBrief.COLLABORATION))
        assertTrue(out.contains("Fix StorageScanner hang"))
        assertTrue(out.contains("do not call spawn_agent") || out.contains("Do not call spawn_agent"))
        assertTrue(out.contains("/var/minis/app"))
        assertTrue(out.contains("Android reviewer"))
        assertFalse(SubAgentBrief.isStructured("just do it"))
        assertTrue(SubAgentBrief.isStructured(out))
    }

    @Test
    fun wrapDoesNotDoubleWrapStructuredPrompt() {
        val structured = """
## Task
x
## Expected result
y
## Constraints
z
## Workflow
1
""".trimIndent()
        val out = SubAgentBrief.wrap(structured, kind = SubAgentKind.EXPLORE)
        assertTrue(out.contains(SubAgentBrief.COLLABORATION))
        assertTrue(out.indexOf(SubAgentBrief.TASK) == out.lastIndexOf(SubAgentBrief.TASK))
    }
}
