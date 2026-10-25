package com.openminis.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

class SubAgentLaneTest {

    @Test
    fun idFor_and_ownerSessionId_roundTrip() {
        val owner = "92345ace-1111-2222-3333-444444444444"
        val id = SubAgentLane.idFor(owner, 2)
        assertEquals("subagent:$owner:2", id)
        assertTrue(SubAgentLane.isLane(id))
        assertEquals(owner, SubAgentLane.ownerSessionId(id))
        assertEquals(owner, SubAgentLane.ownerSessionId(owner))
        assertFalse(SubAgentLane.isLane(owner))
    }

    @Test
    fun laneSurvivesDispatcherSwitch() = runBlocking {
        val id = SubAgentLane.idFor("parent-session", 0)
        withContext(SubAgentLane(id)) {
            withContext(Dispatchers.Default) {
                withContext(Dispatchers.IO) {
                    assertEquals(id, coroutineContext[SubAgentLane]?.id)
                    assertEquals("parent-session", SubAgentLane.ownerSessionId(coroutineContext[SubAgentLane]!!.id))
                }
            }
        }
        assertNull(coroutineContext[SubAgentLane])
    }
}
