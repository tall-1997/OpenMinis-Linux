package com.openminis.app.security

import com.openminis.app.service.ApprovalGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityGateSessionIsolationTest {
    @After fun cleanup() {
        ApprovalGate.cleanupAll()
        SecurityGateHolder.setActiveSessionMode(SessionPermissionMode.ASK, "a")
        SecurityGateHolder.setActiveSessionMode(SessionPermissionMode.ASK, "b")
    }

    // [#31] The holder stores session TIERS (SessionPermissionMode) now;
    // ALLOW_ALL was renamed to the YOLO tier at this boundary.

    @Test fun changingOneSessionModeDoesNotChangeAnother() {
        SecurityGateHolder.setActiveSessionMode(SessionPermissionMode.YOLO, "a")
        SecurityGateHolder.setActiveSessionMode(SessionPermissionMode.ASK, "b")
        assertEquals(SessionPermissionMode.YOLO, SecurityGateHolder.activeSessionMode("a"))
        assertEquals(SessionPermissionMode.ASK, SecurityGateHolder.activeSessionMode("b"))
        SecurityGateHolder.setActiveSessionMode(SessionPermissionMode.ASK, "a")
        assertEquals(SessionPermissionMode.ASK, SecurityGateHolder.activeSessionMode("b"))
    }

    @Test fun nullCallerRemainsAskEvenWhenSessionIsYolo() {
        SecurityGateHolder.setActiveSessionMode(SessionPermissionMode.YOLO, "a")
        assertEquals(SessionPermissionMode.ASK, SecurityGateHolder.activeSessionMode(null))
    }
}
