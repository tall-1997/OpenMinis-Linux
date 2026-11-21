package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SandboxHttpProxyParseTest {

    @Test
    fun connectLineHostPort() {
        val first = "CONNECT example.com:443 HTTP/1.1"
        val parts = first.split(' ')
        assertEquals("CONNECT", parts[0])
        assertEquals("example.com:443", parts[1])
    }

    @Test
    fun hostHeaderFallback() {
        val header = "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n"
        val hostLine = header.lineSequence().first { it.startsWith("Host:", true) }
        assertEquals("example.com", hostLine.substringAfter(':').trim())
    }

    // ---------------------------------------------- parseHostPort (authority)

    @Test
    fun `plain host and host with port`() {
        assertEquals("example.com" to 80, SandboxHttpProxy.parseHostPort("example.com", 80))
        assertEquals("example.com" to 8080, SandboxHttpProxy.parseHostPort("example.com:8080", 80))
        assertEquals("127.0.0.1" to 443, SandboxHttpProxy.parseHostPort("127.0.0.1:443", 443))
    }

    @Test
    fun `bracketed ipv6 literal with and without port`() {
        // [T-proxy-ipv6-literal] The old split(':') connected to host "[" —
        // every IPv6 destination died with UnknownHostException.
        assertEquals("::1" to 443, SandboxHttpProxy.parseHostPort("[::1]:443", 443))
        assertEquals("::1" to 80, SandboxHttpProxy.parseHostPort("[::1]", 80))
        assertEquals("2001:db8::1" to 8080, SandboxHttpProxy.parseHostPort("[2001:db8::1]:8080", 80))
    }

    @Test
    fun `bare unbracketed ipv6 literal keeps default port`() {
        // Non-RFC but seen in the wild; splitting at every colon is never right.
        assertEquals("2001:db8::1" to 80, SandboxHttpProxy.parseHostPort("2001:db8::1", 80))
        assertEquals("::1" to 80, SandboxHttpProxy.parseHostPort("::1", 80))
    }

    @Test
    fun `absolute form request target is stripped to authority`() {
        // Proxy-style GET without a Host header: target is an absolute URL.
        assertEquals("::1" to 8080, SandboxHttpProxy.parseHostPort("http://[::1]:8080/x", 80))
        assertEquals("example.com" to 80, SandboxHttpProxy.parseHostPort("http://example.com/a/b?c=d", 80))
        assertEquals("example.com" to 9000, SandboxHttpProxy.parseHostPort("http://example.com:9000/", 80))
    }

    @Test
    fun `malformed authorities return null`() {
        assertNull(SandboxHttpProxy.parseHostPort("", 80))
        assertNull(SandboxHttpProxy.parseHostPort("[::1", 80)) // unclosed bracket
        assertNull(SandboxHttpProxy.parseHostPort("[]", 80)) // empty literal
        assertNull(SandboxHttpProxy.parseHostPort("example.com:notaport", 80))
        assertNull(SandboxHttpProxy.parseHostPort("example.com:0", 80))
        assertNull(SandboxHttpProxy.parseHostPort("example.com:70000", 80))
        assertNull(SandboxHttpProxy.parseHostPort(":443", 80))
    }

    @Test
    fun `header roundtrips through iso-8859-1`() {
        // [T-proxy-header-bytes] readHeaders builds the header from
        // `b.toChar()` (chars 0..255); writing it back with the default
        // charset re-encoded every >=0x80 byte into two. ISO-8859-1 must
        // restore the exact byte sequence.
        val bytes = byteArrayOf(
            'H'.code.toByte(), 'o'.code.toByte(), ':'.code.toByte(), ' '.code.toByte(),
            0xC3.toByte(), 0xA9.toByte(), // UTF-8 "é" sent as-is by the client
        )
        val asRead = String(bytes, Charsets.ISO_8859_1)
        assertEquals(bytes.toList(), asRead.toByteArray(Charsets.ISO_8859_1).toList())
        // And UTF-8 re-encoding demonstrably corrupts it (the bug being fixed).
        org.junit.Assert.assertNotEquals(bytes.toList(), asRead.toByteArray().toList())
    }
}
