package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellTimeoutPolicyTest {
    @Test
    fun longSetupScriptsGetAFloor() {
        assertEquals(ShellTimeoutPolicy.LONG_RUNNING_TIMEOUT_MS, ShellTimeoutPolicy.minimumMs("minis-dev-setup-full"))
        assertEquals(ShellTimeoutPolicy.LONG_RUNNING_TIMEOUT_MS, ShellTimeoutPolicy.minimumMs("sh /usr/local/bin/minis-android-sdk-setup"))
        assertEquals(ShellTimeoutPolicy.LONG_RUNNING_TIMEOUT_MS, ShellTimeoutPolicy.minimumMs("sh /usr/local/bin/minis-self-build"))
    }

    @Test
    fun lightweightSeedIsNotRaised() {
        assertEquals(0L, ShellTimeoutPolicy.minimumMs("minis-dev-setup"))
        assertEquals(0L, ShellTimeoutPolicy.minimumMs("apt-get install -y curl"))
        assertEquals(0L, ShellTimeoutPolicy.minimumMs("ls /tmp"))
    }
}
