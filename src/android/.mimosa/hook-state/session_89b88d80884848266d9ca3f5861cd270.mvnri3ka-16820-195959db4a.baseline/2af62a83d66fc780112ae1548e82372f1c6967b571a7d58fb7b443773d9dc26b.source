package com.openminis.app.plugins

import com.openminis.app.tools.FetchUrlGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorNetworkPolicyTest {
    private fun plugin(baseUrl: String) = PluginRegistry.RemotePlugin(
        id = "trusted",
        name = "Trusted",
        description = "",
        icon = "",
        authType = "api_key",
        authHeader = "Authorization",
        baseUrl = baseUrl,
        tools = listOf(PluginRegistry.RemoteTool("read", "read", "GET", "/v1", emptyList())),
    )

    @Test fun `connector private authorization is scoped to complete config identity`() {
        val original = plugin("https://api.example.com/v1/")
        val changedOrigin = plugin("http://192.168.1.10:8080/v1/")
        assertNotEquals(original.configurationKey(), changedOrigin.configurationKey())
        assertFalse(plugin("https://api.example.com/v1").configurationKey().startsWith(connectorScopePrefix("trusted-extra")))
        assertNotEquals(
            original.configurationKey(),
            original.copy(tools = listOf(PluginRegistry.RemoteTool("write", "write", "POST", "/v1", emptyList()))).configurationKey(),
        )
        assertEquals(original.configurationKey(), plugin("https://API.EXAMPLE.COM/v1").configurationKey())
    }

    @Test fun `configuration accepts private connector URL but rejects metadata and schemes`() {
        assertNull(ConnectorNetworkPolicy.configurationBlockedReason("http://192.168.1.20:8080/api"))
        assertNotNull(ConnectorNetworkPolicy.configurationBlockedReason("http://169.254.169.254/latest/meta-data"))
        assertNotNull(ConnectorNetworkPolicy.configurationBlockedReason("file:///etc/passwd"))
        assertNotNull(ConnectorNetworkPolicy.configurationBlockedReason("https://user:secret@example.com/api"))
    }

    @Test fun `private DNS exception allows RFC1918 but never metadata or special ranges`() {
        fun address(host: String) = java.net.InetAddress.getByName(host)
        assertTrue(ConnectorNetworkPolicy.isDnsAddressAllowed(address("192.168.1.10"), true))
        assertTrue(ConnectorNetworkPolicy.isDnsAddressAllowed(address("127.0.0.1"), true))
        assertFalse(ConnectorNetworkPolicy.isDnsAddressAllowed(address("192.168.1.10"), false))
        assertFalse(ConnectorNetworkPolicy.isDnsAddressAllowed(address("169.254.169.254"), true))
        assertFalse(ConnectorNetworkPolicy.isDnsAddressAllowed(address("100.100.100.200"), true))
        assertFalse(ConnectorNetworkPolicy.isDnsAddressAllowed(address("168.63.129.16"), true))
        assertFalse(ConnectorNetworkPolicy.isDnsAddressAllowed(address("224.0.0.1"), true))
        assertFalse(ConnectorNetworkPolicy.isDnsAddressAllowed(address("fe80::1"), true))
        assertTrue(ConnectorNetworkPolicy.isDnsAddressAllowed(address("8.8.8.8"), false))
    }

    @Test fun `metadata and internal destinations remain hard blocked`() {
        assertTrue(ConnectorNetworkPolicy.isCloudMetadataOrInternal("169.254.12.1"))
        assertTrue(ConnectorNetworkPolicy.isCloudMetadataOrInternal("metadata.google.internal"))
        assertTrue(ConnectorNetworkPolicy.isCloudMetadataOrInternal("100.100.100.200"))
        assertTrue(ConnectorNetworkPolicy.isCloudMetadataOrInternal("168.63.129.16"))
        assertTrue(ConnectorNetworkPolicy.isCloudMetadataOrInternal("fd00:ec2::254"))
        assertTrue(ConnectorNetworkPolicy.isCloudMetadataOrInternal("fd20:ce::254"))
        assertFalse(ConnectorNetworkPolicy.isCloudMetadataOrInternal("192.168.1.10"))
    }

    @Test fun `resolved DNS address classifier separates public and private IPs`() {
        assertTrue(FetchUrlGuard.isUnsafeAddress(java.net.InetAddress.getByName("10.0.0.4")))
        assertTrue(FetchUrlGuard.isUnsafeAddress(java.net.InetAddress.getByName("127.0.0.1")))
        assertFalse(FetchUrlGuard.isUnsafeAddress(java.net.InetAddress.getByName("8.8.8.8")))
        assertTrue(FetchUrlGuard.isUnsafeAddress(java.net.InetAddress.getByName("fd00::1")))
        assertTrue(FetchUrlGuard.isUnsafeAddress(java.net.InetAddress.getByName("2001:db8::1")))
        assertFalse(FetchUrlGuard.isUnsafeAddress(java.net.InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test fun `public web fetch continues to block private network`() {
        assertNotNull(FetchUrlGuard.blockedReason("http://192.168.1.20:8080/api"))
        assertNotNull(FetchUrlGuard.blockedReason("http://127.0.0.1/"))
        assertNotNull(FetchUrlGuard.blockedReason("http://169.254.169.254/"))
    }
}
