package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#221] Validation contract for user-provided provider headers. Parsing is a
 * pure function on purpose: a bad header line must fail the whole save with an
 * actionable message (never a partially-applied set), and transport-managed
 * headers must be rejected before they reach OkHttp.
 */
class ProviderHeaderStoreTest {

    @Test
    fun `parses name value lines and ignores blanks and comments`() {
        val parsed = ProviderHeaderStore.parse(
            """
            # relay headers
            X-Relay-Token: abc123
            X-Trace:   enabled
            """.trimIndent(),
        )
        assertNotNull(parsed)
        assertEquals("abc123", parsed!!["X-Relay-Token"])
        assertEquals("enabled", parsed["X-Trace"])
    }

    @Test
    fun `empty text clears the set`() {
        assertEquals(emptyMap<String, String>(), ProviderHeaderStore.parse(""))
    }

    @Test
    fun `missing colon is rejected`() {
        assertNull(ProviderHeaderStore.parse("X-Broken"))
        assertNotNull(ProviderHeaderStore.parseError)
    }

    @Test
    fun `transport managed headers are rejected`() {
        for (name in listOf("Host", "Content-Length", "Connection", "Transfer-Encoding")) {
            assertNull("$name must be rejected", ProviderHeaderStore.parse("$name: x"))
        }
    }

    @Test
    fun `control characters in a value are rejected`() {
        assertNull(ProviderHeaderStore.parse("X-Evil: a\rb"))
    }

    @Test
    fun `duplicate header names are rejected case-insensitively`() {
        assertNull(ProviderHeaderStore.parse("X-A: 1\nx-a: 2"))
    }

    @Test
    fun `invalid name characters are rejected`() {
        assertNull(ProviderHeaderStore.parse("X Bad: 1"))
    }

    @Test
    fun `a blank instance id is rejected`() {
        // Deterministic regardless of whether another test primed the store.
        assertTrue(ProviderHeaderStore.save("", "X-A: 1") != null)
    }
}
