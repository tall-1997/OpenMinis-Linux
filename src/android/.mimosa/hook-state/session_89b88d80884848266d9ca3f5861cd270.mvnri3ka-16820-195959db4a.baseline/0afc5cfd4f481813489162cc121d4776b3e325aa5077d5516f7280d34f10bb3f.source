package com.openminis.app.security

import com.openminis.app.service.ApprovalGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityGateSessionIsolationTest {
    @After fun cleanup() {
        ApprovalGate.cleanupAll()
        SecurityGateHolder.setActiveSessionMode(PermissionMode.ASK, "a")
        SecurityGateHolder.setActiveSessionMode(PermissionMode.ASK, "b")
    }

    @Test fun changingOneSessionModeDoesNotChangeAnother() {
        SecurityGateHolder.setActiveSessionMode(PermissionMode.ALLOW_ALL, "a")
        SecurityGateHolder.setActiveSessionMode(PermissionMode.ASK, "b")
        assertEquals(PermissionMode.ALLOW_ALL, SecurityGateHolder.activeSessionMode("a"))
        assertEquals(PermissionMode.ASK, SecurityGateHolder.activeSessionMode("b"))
        SecurityGateHolder.setActiveSessionMode(PermissionMode.ASK, "a")
        assertEquals(PermissionMode.ASK, SecurityGateHolder.activeSessionMode("b"))
    }

    @Test fun nullCallerRemainsAskEvenWhenSessionIsYoyo() {
        SecurityGateHolder.setActiveSessionMode(PermissionMode.ALLOW_ALL, "a")
        assertEquals(PermissionMode.ASK, SecurityGateHolder.activeSessionMode(null))
    }
}
