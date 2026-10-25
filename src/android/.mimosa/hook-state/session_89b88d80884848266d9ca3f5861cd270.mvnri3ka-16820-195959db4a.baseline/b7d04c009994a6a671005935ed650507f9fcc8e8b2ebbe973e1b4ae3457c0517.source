package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
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
}
