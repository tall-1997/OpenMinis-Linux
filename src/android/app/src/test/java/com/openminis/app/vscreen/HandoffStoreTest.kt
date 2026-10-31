package com.openminis.app.vscreen

import com.openminis.app.vscreen.HandoffStateMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-vscreen2] JVM tests for the prefs-backed handoff store: JSON
 * round-trip, the change broadcast hook (every state change is persisted AND
 * broadcast), and expiry of handoffs whose display died.
 */
class HandoffStoreTest {

    private fun record(
        displayId: Int = 2,
        state: State = State.WAITING,
        taskId: Int = 42,
        note: String = "",
    ) = HandoffRecord(
        displayId = displayId,
        taskId = taskId,
        packageName = "com.agent.app",
        topActivity = "com.agent.app/.Login",
        kind = "login",
        reason = "please sign in",
        state = state,
        startedAtMs = 1_000L,
        lastChangedAtMs = 2_000L,
        note = note,
    )

    @Test
    fun `records round-trip through json`() {
        val prefs = MemoryPrefs()
        val store = HandoffStore(prefs)
        store.put(record())
        val loaded = store.get(2)!!
        assertEquals(42, loaded.taskId)
        assertEquals("com.agent.app", loaded.packageName)
        assertEquals("login", loaded.kind)
        assertEquals(State.WAITING, loaded.state)
        assertEquals(1_000L, loaded.startedAtMs)
        assertEquals("please sign in", loaded.reason)
    }

    @Test
    fun `every put and remove fires the broadcast hook`() {
        val broadcasts = mutableListOf<HandoffRecord?>()
        val store = HandoffStore(MemoryPrefs()) { broadcasts.add(it) }
        store.put(record())
        store.put(record(state = State.KEPT))
        store.remove(2)
        assertEquals(3, broadcasts.size)
        assertEquals(State.WAITING, broadcasts[0]!!.state)
        assertEquals(State.KEPT, broadcasts[1]!!.state)
        assertNull(broadcasts[2])
    }

    @Test
    fun `all lists records across displays`() {
        val store = HandoffStore(MemoryPrefs())
        store.put(record(displayId = 2))
        store.put(record(displayId = 5, taskId = 7))
        assertEquals(setOf(2, 5), store.all().map { it.displayId }.toSet())
    }

    @Test
    fun `waiting handoffs of dead displays expire and live ones survive`() {
        val store = HandoffStore(MemoryPrefs())
        store.put(record(displayId = 2))
        store.put(record(displayId = 9))
        val expired = store.expireDead(liveDisplayIds = setOf(9), nowMs = 3_000L)
        assertEquals(listOf(2), expired.map { it.displayId })
        assertNull(store.get(2))
        assertEquals(State.WAITING, store.get(9)!!.state)
    }

    @Test
    fun `corrupt records are dropped instead of thrown`() {
        val prefs = MemoryPrefs()
        prefs.putString(HandoffStore.KEY_PREFIX + "2", "{not json")
        val store = HandoffStore(prefs)
        assertNull(store.get(2))
        assertTrue(store.all().isEmpty())
    }
}
