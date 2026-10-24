package com.openminis.app.ui.chat

import com.openminis.app.tools.SupervisorChat
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the live-session host: one live loop per session id,
 * registration is the single source of truth for liveness, and removal is
 * idempotent. The host is the piece the UI consults before routing user
 * input to a supervisor inbox, so its semantics must not drift.
 */
class SupervisorSessionHostTest {

    private fun live(sessionId: String): SupervisorSessionHost.Live =
        SupervisorSessionHost.Live(
            sessionId = sessionId,
            objective = "objective for $sessionId",
            inbox = SupervisorChat.SupervisorInbox(),
            job = Job(),
        )

    @Test
    fun register_makesSessionLiveAndLookupReturnsSameInstance() {
        SupervisorSessionHost.remove("host-1")
        val l = live("host-1")
        SupervisorSessionHost.register(l)
        assertTrue(SupervisorSessionHost.isLive("host-1"))
        assertSame(l, SupervisorSessionHost.live("host-1"))
        SupervisorSessionHost.remove("host-1")
    }

    @Test
    fun remove_clearsLivenessAndIsIdempotent() {
        SupervisorSessionHost.register(live("host-2"))
        SupervisorSessionHost.remove("host-2")
        assertFalse(SupervisorSessionHost.isLive("host-2"))
        assertNull(SupervisorSessionHost.live("host-2"))
        SupervisorSessionHost.remove("host-2")
        assertNull(SupervisorSessionHost.live("host-2"))
    }

    @Test
    fun secondRegisterReplacesTheLiveLoopForTheSameSession() {
        SupervisorSessionHost.remove("host-3")
        val first = live("host-3")
        SupervisorSessionHost.register(first)
        val second = live("host-3")
        SupervisorSessionHost.register(second)
        assertSame(second, SupervisorSessionHost.live("host-3"))
        SupervisorSessionHost.remove("host-3")
    }

    @Test
    fun sessionsAreIsolatedBySessionId() {
        SupervisorSessionHost.remove("host-4a")
        SupervisorSessionHost.remove("host-4b")
        SupervisorSessionHost.register(live("host-4a"))
        assertFalse(SupervisorSessionHost.isLive("host-4b"))
        SupervisorSessionHost.remove("host-4a")
        assertFalse(SupervisorSessionHost.isLive("host-4a"))
    }

    @Test
    fun inboxPushedBeforeRemovalIsStillReadableFromTheLiveInstance() {
        SupervisorSessionHost.remove("host-5")
        val l = live("host-5")
        SupervisorSessionHost.register(l)
        l.inbox.push("interjection")
        val drained = l.inbox.drain()
        assertTrue(drained.contains("interjection"))
        SupervisorSessionHost.remove("host-5")
    }
}
