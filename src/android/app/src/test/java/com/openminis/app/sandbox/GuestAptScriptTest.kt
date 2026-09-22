package com.openminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestAptScriptTest {
    @Test
    fun installDoesNotDisableTlsVerification() {
        val script = GuestAptScript.install(listOf("ca-certificates", "curl"))
        assertFalse(script.contains("Verify-Peer"))
        assertFalse(script.contains("Verify-Host"))
        assertTrue(script.contains("apt-get update -qq"))
        assertTrue(script.contains("minis-mirror auto"))
        assertTrue(script.contains("minis_release_apt_lock"))
        assertTrue(script.contains("minis_acquire_apt_lock 120 || exit 1"))
        assertFalse(script.contains("minis_acquire_apt_lock 120 || true"))
        assertTrue(script.contains("apt-get install -y -qq --no-upgrade --no-install-recommends ca-certificates curl"))
    }
}
