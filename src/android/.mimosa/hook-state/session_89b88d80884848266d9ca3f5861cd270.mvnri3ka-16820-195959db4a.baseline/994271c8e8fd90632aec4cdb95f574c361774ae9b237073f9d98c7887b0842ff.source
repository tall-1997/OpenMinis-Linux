package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionCoordinatorLaneTest {

    @Test
    fun ownerSessionId_parsesLaneAndLeavesParentAlone() {
        val owner = "92345ace-1111-2222-3333-444444444444"
        val lane = "subagent:$owner:2"
        assertEquals(owner, ExecutionCoordinator.ownerSessionId(lane))
        assertEquals(owner, ExecutionCoordinator.ownerSessionId(owner))
        assertTrue(ExecutionCoordinator.isLaneSession(lane))
        assertFalse(ExecutionCoordinator.isLaneSession(owner))
    }
}
