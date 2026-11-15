package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM pins for [BrowserActionInput.parse] — the JSON contract layer between
 * the model's tool call and the browser engine.
 */
class BrowserActionInputParseTest {

    @Test
    fun `get_cookies defaults to fuzzy matching per the schema contract`() {
        // [T-cookie-fuzzy-default] The data-class default is fuzzy=true and the
        // schema documents "default: true" — the parse layer must agree. It
        // once overrode both with false, so every call the model made without
        // an explicit fuzzy flag ran exact matching.
        val input = BrowserActionInput.parse("""{"action":"get_cookies"}""")
        assertTrue(input != null)
        assertTrue(input!!.fuzzy)
    }

    @Test
    fun `get_cookies honours an explicit fuzzy flag`() {
        val exact = BrowserActionInput.parse("""{"action":"get_cookies","fuzzy":false}""")
        assertTrue(exact != null)
        assertFalse(exact!!.fuzzy)

        val fuzzy = BrowserActionInput.parse("""{"action":"get_cookies","fuzzy":true}""")
        assertTrue(fuzzy!!.fuzzy)
    }

    @Test
    fun `get_cookies parses the keywords array verbatim`() {
        val input =
            BrowserActionInput.parse("""{"action":"get_cookies","keywords":["sid","auth"]}""")
        assertTrue(input != null)
        assertEquals(listOf("sid", "auth"), input!!.keywords)
        assertNull(input.selector)
    }
}
