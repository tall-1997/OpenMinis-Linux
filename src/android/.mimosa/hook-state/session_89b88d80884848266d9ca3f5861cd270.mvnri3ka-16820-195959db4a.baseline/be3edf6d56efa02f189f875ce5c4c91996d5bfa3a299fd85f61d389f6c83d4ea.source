package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

class SandboxFirewallPolicyTest {

    @Test
    fun parse_acceptsAliases() {
        assertEquals(SandboxFirewall.Policy.ALLOW, SandboxFirewall.Policy.parse("allow"))
        assertEquals(SandboxFirewall.Policy.ALLOW, SandboxFirewall.Policy.parse(null))
        assertEquals(SandboxFirewall.Policy.WIFI_ONLY, SandboxFirewall.Policy.parse("wifi"))
        assertEquals(SandboxFirewall.Policy.WIFI_ONLY, SandboxFirewall.Policy.parse("WIFI-ONLY"))
        assertEquals(SandboxFirewall.Policy.DENY, SandboxFirewall.Policy.parse("block"))
        assertEquals(SandboxFirewall.Policy.DENY, SandboxFirewall.Policy.parse("off"))
    }
}
