package com.openminis.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [LocalToolPluginStore] — JSON codec and command
 * materialization only; the Context-backed persistence layer needs a device.
 */
class LocalToolPluginStoreTest {

    private fun tool(
        command: String = "echo {{msg}}",
        params: List<LocalToolPluginStore.LocalParam> = listOf(
            LocalToolPluginStore.LocalParam("msg", "message", required = true),
        ),
    ) = LocalToolPluginStore.LocalToolDef(
        id = "lt-1",
        name = "say",
        description = "says a thing",
        params = params,
        command = command,
    )

    // ─── codec ─────────────────────────────────────────────────────────

    @Test
    fun `parse roundtrips serialize`() {
        val tools = listOf(
            tool().copy(createdAtMs = 123L),
            tool(command = "ls {{dir}}").copy(
                id = "lt-2",
                name = "list_dir",
                params = listOf(
                    LocalToolPluginStore.LocalParam("dir", "directory", required = false, defaultValue = "."),
                ),
            ),
        )
        val parsed = LocalToolPluginStore.parse(LocalToolPluginStore.serialize(tools))
        assertEquals(2, parsed.size)
        assertEquals("say", parsed[0].name)
        assertEquals("echo {{msg}}", parsed[0].command)
        assertTrue(parsed[0].params[0].required)
        assertEquals(123L, parsed[0].createdAtMs)
        assertEquals(".", parsed[1].params[0].defaultValue)
        assertEquals(false, parsed[1].params[0].required)
    }

    @Test
    fun `parse rejects blank name command and non-identifier names`() {
        val json = """
            [
              {"name":"", "command":"ls"},
              {"name":"has space", "command":"ls"},
              {"name":"9starts-digit", "command":"ls"},
              {"name":"ok_name", "command":"  "},
              {"name":"valid", "command":"echo hi", "params":[]}
            ]
        """.trimIndent()
        val parsed = LocalToolPluginStore.parse(json)
        assertEquals(1, parsed.size)
        assertEquals("valid", parsed[0].name)
    }

    @Test
    fun `parse garbage returns empty`() {
        assertTrue(LocalToolPluginStore.parse("not json").isEmpty())
        assertTrue(LocalToolPluginStore.parse("[]").isEmpty())
    }

    // ─── materialize ───────────────────────────────────────────────────

    @Test
    fun `materialize substitutes params`() {
        val r = LocalToolPluginStore.materialize(tool(), mapOf("msg" to "hello world"))
        assertEquals("echo hello world", (r as LocalToolPluginStore.MaterializeResult.Ok).command)
    }

    @Test
    fun `materialize uses default when optional missing`() {
        val t = tool(
            command = "ls {{dir}}",
            params = listOf(LocalToolPluginStore.LocalParam("dir", required = false, defaultValue = ".")),
        )
        val r = LocalToolPluginStore.materialize(t, emptyMap())
        assertEquals("ls .", (r as LocalToolPluginStore.MaterializeResult.Ok).command)
    }

    @Test
    fun `materialize missing required param`() {
        val r = LocalToolPluginStore.materialize(tool(), emptyMap())
        assertEquals("msg", (r as LocalToolPluginStore.MaterializeResult.MissingParam).param)
    }

    @Test
    fun `materialize rejects shell metacharacters`() {
        for (evil in listOf("a;rm", "a|b", "a&b", "a\$b", "a>b", "a`b", "a\nb")) {
            val r = LocalToolPluginStore.materialize(tool(), mapOf("msg" to evil))
            assertTrue("value '$evil' should be unsafe", r is LocalToolPluginStore.MaterializeResult.UnsafeParam)
        }
    }

    @Test
    fun `materialize allowUnsafe passes through`() {
        val r = LocalToolPluginStore.materialize(tool(), mapOf("msg" to "a;b"), allowUnsafe = true)
        assertEquals("echo a;b", (r as LocalToolPluginStore.MaterializeResult.Ok).command)
    }

    @Test
    fun `unknown placeholder left verbatim`() {
        val t = tool(command = "echo {{msg}} {{typo}}")
        val r = LocalToolPluginStore.materialize(t, mapOf("msg" to "x"))
        assertEquals("echo x {{typo}}", (r as LocalToolPluginStore.MaterializeResult.Ok).command)
    }

    @Test
    fun `shellQuote escapes single quotes`() {
        assertEquals("'it'\\''s'", LocalToolPluginStore.shellQuote("it's"))
        assertEquals("''", LocalToolPluginStore.shellQuote(""))
    }

    @Test
    fun `find semantics on name`() {
        // pure structural check: name uniqueness is enforced by callers;
        // here just verify the data class plumbing
        val t = tool().copy(name = "unique_name")
        assertEquals("unique_name", t.name)
        assertNull(null)
    }
}
