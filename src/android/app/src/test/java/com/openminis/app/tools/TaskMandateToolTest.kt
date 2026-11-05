package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-task-mandate] The boundary is only worth anything if the code refuses work
 * outside it — and if an undeclared session keeps behaving exactly as before.
 */
class TaskMandateToolTest {

    private val sid = "chat-mandate"

    @Before
    fun reset() = TaskMandateStore.clear(sid, null)

    @After
    fun tearDown() = TaskMandateStore.clear(sid, null)

    private fun declare(vararg scope: String) =
        TaskMandateStore.declare(sid, null, scope.toList(), "explicit", 0L, "fix the spacing")

    @Test
    fun noMandateMeansUnconstrained() {
        assertTrue(TaskMandateStore.allows(null, "worker"))
        assertNull(TaskMandateStore.get(sid, null))
    }

    @Test
    fun declaringRequiresAScope() {
        val error = runCatching { declare() }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("scope"))
    }

    @Test
    fun scopeSizeIsBounded() {
        val tooMany = (1..TaskMandateStore.MAX_SCOPE_TOKENS + 1).map { "token$it" }.toTypedArray()
        assertTrue(runCatching { declare(*tooMany) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun strengthIsValidated() {
        val error = runCatching {
            TaskMandateStore.declare(sid, null, listOf("worker"), "maybe", 0L, "x")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun aDeclaredScopeAllowsOnlyItsOwnKinds() {
        val mandate = declare("explore", "edit:/repo/app")
        assertTrue(TaskMandateStore.allows(mandate, "explore"))
        assertTrue(TaskMandateStore.allows(mandate, "edit:/repo/app/Main.kt"))
        assertFalse(TaskMandateStore.allows(mandate, "worker"))
        assertFalse(TaskMandateStore.allows(mandate, "edit:/etc/passwd"))
    }

    @Test
    fun theRefusalNamesTheBoundary() {
        val mandate = declare("explore")
        val text = TaskMandateStore.refusal(mandate, "worker")
        assertTrue(text.contains("worker"))
        assertTrue(text.contains("explore"))
        assertTrue(text.contains("task_mandate"))
    }

    @Test
    fun declareShowAndClearThroughTheTool() {
        val declared = TaskMandateTool.execute(
            """{"tool_title":"t","op":"declare","scope":["explore","test"],"objective":"check it"}""",
            sid,
            null,
        )
        assertTrue(declared.success)
        assertTrue(declared.output.contains("explore"))
        assertTrue(declared.output.contains("check it"))

        val shown = TaskMandateTool.execute("""{"tool_title":"t","op":"show"}""", sid, null)
        assertTrue(shown.success)
        assertTrue(shown.output.contains("explore"))

        TaskMandateTool.execute("""{"tool_title":"t","op":"clear"}""", sid, null)
        assertNull(TaskMandateStore.get(sid, null))
    }

    @Test
    fun showWithoutAMandateIsInformativeNotAnError() {
        val shown = TaskMandateTool.execute("""{"tool_title":"t","op":"show"}""", sid, null)
        assertTrue(shown.success)
        assertTrue(shown.output.contains("no task mandate"))
    }

    @Test
    fun theToolSurfacesContractBreaches() {
        val bad = TaskMandateTool.execute(
            """{"tool_title":"t","op":"declare","scope":[]}""",
            sid,
            null,
        )
        assertFalse(bad.success)
        assertTrue(bad.output.contains("scope"))
    }

    @Test
    fun scopeTokensAreNormalised() {
        val mandate = TaskMandateStore.declare(
            sid, null, listOf("  Explore  ", "EXPLORE", ""), "explicit", 7L, " x ",
        )
        assertEquals(listOf("explore"), mandate.scope)
        assertEquals(7L, mandate.policySeq)
        assertEquals("x", mandate.objective)
    }
}
