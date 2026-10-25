package com.openminis.app.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxNotifyActionsTest {

    @Test
    fun acceptsMinisPaths() {
        assertTrue(SandboxNotifyActions.isSafeGuestCommand("/var/minis/hooks/pause.sh"))
        assertTrue(SandboxNotifyActions.isSafeGuestCommand("/usr/local/bin/minis-self-build"))
        assertTrue(SandboxNotifyActions.isSafeGuestCommand("retry.sh"))
        assertTrue(SandboxNotifyActions.isSafeGuestCommand("/var/minis/workspace/retry.sh --once"))
    }

    @Test
    fun rejectsShellMetacharacters() {
        assertFalse(SandboxNotifyActions.isSafeGuestCommand("/var/minis/x.sh; rm -rf /"))
        assertFalse(SandboxNotifyActions.isSafeGuestCommand("/var/minis/x.sh | cat"))
        assertFalse(SandboxNotifyActions.isSafeGuestCommand("/var/minis/x.sh && true"))
        assertFalse(SandboxNotifyActions.isSafeGuestCommand("/var/minis/`id`"))
        assertFalse(SandboxNotifyActions.isSafeGuestCommand("/var/minis/\$(id)"))
        assertFalse(SandboxNotifyActions.isSafeGuestCommand("/tmp/evil.sh"))
        assertFalse(SandboxNotifyActions.isSafeGuestCommand("/usr/bin/curl http://x"))
    }
}
