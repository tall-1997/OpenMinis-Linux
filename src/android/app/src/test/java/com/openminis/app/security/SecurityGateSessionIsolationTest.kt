package com.openminis.app.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** One session's tier must never leak into another session's decisions. */
class SecurityGateSessionIsolationTest {

    @After
    fun tearDown() {
        SecurityGateHolder.setActiveSessionMode(SessionPermissionMode.SAFEST, "a")
        SecurityGateHolder.setActiveSessionMode(SessionPermissionMode.SAFEST, "b")
    }

@Test
    fun modesAreKeyedPerSession() {
        SecurityGateHolder.setActiveSessionMode(SessionPermissionMode.ASK_BEFORE_CHANGES, "a")
        SecurityGateHolder.setActiveSessionMode(SessionPermissionMode.ASK_BEFORE_CHANGES, "b")
        assertEquals(SessionPermissionMode.ASK_BEFORE_CHANGES, SecurityGateHolder.activeSessionMode("a"))
        assertEquals(SessionPermissionMode.ASK_BEFORE_CHANGES, SecurityGateHolder.activeSessionMode("b"))

        SecurityGateHolder.setActiveSessionMode(SessionPermissionMode.FULL_ACCESS, "a")
        SecurityGateHolder.setActiveSessionMode(SessionPermissionMode.ASK_BEFORE_CHANGES, "b")
        assertEquals(SessionPermissionMode.FULL_ACCESS, SecurityGateHolder.activeSessionMode("a"))
        assertEquals(SessionPermissionMode.ASK_BEFORE_CHANGES, SecurityGateHolder.activeSessionMode("b"))
    }

@Test
    fun theMiddleTierIsIsolatedToo() {
        SecurityGateHolder.setActiveSessionMode(SessionPermissionMode.AUTONOMOUS, "a")
        assertEquals(SessionPermissionMode.AUTONOMOUS, SecurityGateHolder.activeSessionMode("a"))
        assertEquals(
            "an unknown session must not inherit another session's autonomy",
            SessionPermissionMode.SAFEST,
            SecurityGateHolder.activeSessionMode("never-seen"),
        )
    }
}
