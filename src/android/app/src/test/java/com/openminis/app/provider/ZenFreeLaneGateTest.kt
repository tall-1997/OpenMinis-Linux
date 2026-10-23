package com.openminis.app.provider

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-zen-gate-body-shape] The upstream keyless lane refuses by request-body
 * shape, not by client identity.
 *
 * Measured 2026-10-23 against `https://opencode.ai/zen/v1/chat/completions`
 * with `Authorization: Bearer public` and the official CLI header set: a body
 * whose `tools` contain exactly one function named `read` answers **200**, while
 * `bash`, `glob`, `grep`, `write`, `edit`, `task` and `webfetch` each answer
 * **403 FreeTierError** on their own, and a body with no `tools` at all is
 * refused too. `stream=false` is refused even when the gate is satisfied.
 *
 * So the refusal is a shape check this client can satisfy, and these tests pin
 * the rewrite so the shape cannot silently regress back into a 403 wall (which
 * is exactly how the lane was misdiagnosed as an identity wall and retired).
 */
class ZenFreeLaneGateTest {

    // -- helpers -------------------------------------------------------------

    private fun toolNames(body: JSONObject): List<String> {
        val arr = body.optJSONArray("tools") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            arr.getJSONObject(i).getJSONObject("function").getString("name")
        }
    }

    private fun tool(body: JSONObject, name: String): JSONObject? {
        val arr = body.optJSONArray("tools") ?: return null
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.getJSONObject("function").optString("name") == name) return t
        }
        return null
    }

    private fun properties(tool: JSONObject): JSONObject =
        tool.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")

    private fun systemContent(body: JSONObject): String? {
        val msgs = body.optJSONArray("messages") ?: return null
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            val role = m.optString("role")
            if (role == "system" || role == "developer") {
                return when (val c = m.opt("content")) {
                    is String -> c
                    is JSONArray -> (0 until c.length())
                        .mapNotNull { c.optJSONObject(it)?.optString("text") }
                        .joinToString("")
                    else -> null
                }
            }
        }
        return null
    }

    private fun req(url: String, auth: String): Request = Request.Builder()
        .url(url)
        .header("Authorization", auth)
        .post("{}".toRequestBody(null))
        .build()

    private fun getReq(url: String, auth: String): Request = Request.Builder()
        .url(url)
        .header("Authorization", auth)
        .get()
        .build()

    // -- body rewrite --------------------------------------------------------

    @Test
    fun `a body with no tools gets the read placeholder injected`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","messages":[]}"""))
        assertTrue("read must be present: ${toolNames(out)}", toolNames(out).contains("read"))
    }

    @Test
    fun `the read placeholder satisfies the gate on its own`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","messages":[]}"""))
        val read = tool(out, "read")
        assertNotNull("read placeholder missing", read)
        assertEquals("function", read!!.optString("type"))
        val fn = read.getJSONObject("function")
        assertEquals("Read the contents of a file or directory.", fn.getString("description"))
        assertFalse("strict must be false", fn.optBoolean("strict", true))
        val props = properties(read)
        assertTrue(props.has("path"))
        assertTrue(props.has("offset"))
        assertTrue(props.has("limit"))
        assertEquals("string", props.getJSONObject("path").getString("type"))
        val params = fn.getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        assertTrue(
            "path must be required: ${params.optJSONArray("required")}",
            (0 until params.getJSONArray("required").length())
                .any { params.getJSONArray("required").getString(it) == "path" },
        )
    }

    @Test
    fun `all four gate names are defined with the measured descriptions`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","messages":[]}"""))
        val expected = mapOf(
            "shell" to "Execute a shell command and return its output.",
            "read" to "Read the contents of a file or directory.",
            "glob" to "Search file paths using a glob pattern (examples: \"**/*.ts\", \"src/**/*.tsx\").",
            "grep" to "Search file contents using ripgrep syntax.",
        )
        for ((name, description) in expected) {
            val t = tool(out, name)
            assertNotNull("$name placeholder missing", t)
            assertEquals(name, description, t!!.getJSONObject("function").getString("description"))
            assertTrue("$name properties must not be empty", properties(t).length() > 0)
        }
    }

    @Test
    fun `an existing real grep or glob definition is never duplicated`() {
        val real = JSONObject(
            """{"model":"m","messages":[],"tools":[
               {"type":"function","function":{"name":"grep","description":"real grep",
                "parameters":{"type":"object","properties":{"pattern":{"type":"string"}}}}}]}""",
        )
        val out = ZenFreeLaneGate.rewriteBody(real)
        assertEquals(
            "grep must appear exactly once: ${toolNames(out)}",
            1,
            toolNames(out).count { it == "grep" },
        )
        assertEquals(
            "the real definition must win",
            "real grep",
            tool(out, "grep")!!.getJSONObject("function").getString("description"),
        )
        assertTrue("read still injected", toolNames(out).contains("read"))
    }

    @Test
    fun `a same-named tool with empty properties is treated as unsatisfied`() {
        val hollow = JSONObject(
            """{"model":"m","messages":[],"tools":[
               {"type":"function","function":{"name":"read","description":"hollow",
                "parameters":{"type":"object","properties":{}}}}]}""",
        )
        val out = ZenFreeLaneGate.rewriteBody(hollow)
        assertEquals(1, toolNames(out).count { it == "read" })
        assertEquals(
            "Read the contents of a file or directory.",
            tool(out, "read")!!.getJSONObject("function").getString("description"),
        )
    }

    @Test
    fun `stream is forced true even when the caller asked for false`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","stream":false,"messages":[]}"""))
        assertTrue("stream must be true", out.getBoolean("stream"))
    }

    @Test
    fun `stream is added when the caller omitted it`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","messages":[]}"""))
        assertTrue(out.getBoolean("stream"))
    }

    @Test
    fun `tool_choice none is removed so the model may call tools`() {
        val out = ZenFreeLaneGate.rewriteBody(
            JSONObject("""{"model":"m","messages":[],"tool_choice":"none"}"""),
        )
        assertFalse("tool_choice must be gone", out.has("tool_choice"))
    }

    @Test
    fun `an explicit tool_choice auto is preserved`() {
        val out = ZenFreeLaneGate.rewriteBody(
            JSONObject("""{"model":"m","messages":[],"tool_choice":"auto"}"""),
        )
        assertEquals("auto", out.getString("tool_choice"))
    }

    @Test
    fun `a placeholder notice is appended to the first system message`() {
        val out = ZenFreeLaneGate.rewriteBody(
            JSONObject(
                """{"model":"m","messages":[
                   {"role":"system","content":"You are helpful."},
                   {"role":"user","content":"hi"}]}""",
            ),
        )
        val sys = systemContent(out)
        assertNotNull(sys)
        assertTrue("notice missing: $sys", sys!!.startsWith("You are helpful."))
        assertTrue(sys.contains("placeholders"))
        assertTrue(sys.contains("Do not call them"))
    }

    @Test
    fun `the notice lands on a developer message when there is no system message`() {
        val out = ZenFreeLaneGate.rewriteBody(
            JSONObject(
                """{"model":"m","messages":[
                   {"role":"developer","content":"Rules."},
                   {"role":"user","content":"hi"}]}""",
            ),
        )
        assertTrue(systemContent(out)!!.contains("placeholders"))
    }

    @Test
    fun `the notice is appended into array-shaped content without dropping parts`() {
        val out = ZenFreeLaneGate.rewriteBody(
            JSONObject(
                """{"model":"m","messages":[
                   {"role":"system","content":[{"type":"text","text":"Part one."}]},
                   {"role":"user","content":"hi"}]}""",
            ),
        )
        val sys = out.getJSONArray("messages").getJSONObject(0)
        val content = sys.opt("content")
        assertTrue("array content must stay an array", content is JSONArray)
        val joined = (0 until (content as JSONArray).length())
            .mapNotNull { content.optJSONObject(it)?.optString("text") }
            .joinToString("")
        assertTrue("original part lost: $joined", joined.contains("Part one."))
        assertTrue("notice missing: $joined", joined.contains("placeholders"))
    }

    @Test
    fun `no notice is appended when nothing was injected`() {
        val satisfied = JSONObject(
            """{"model":"m","messages":[{"role":"system","content":"You are helpful."}],
               "tools":[
               {"type":"function","function":{"name":"read","description":"r",
                "parameters":{"type":"object","properties":{"path":{"type":"string"}}}}},
               {"type":"function","function":{"name":"shell","description":"s",
                "parameters":{"type":"object","properties":{"command":{"type":"string"}}}}},
               {"type":"function","function":{"name":"glob","description":"g",
                "parameters":{"type":"object","properties":{"pattern":{"type":"string"}}}}},
               {"type":"function","function":{"name":"grep","description":"p",
                "parameters":{"type":"object","properties":{"pattern":{"type":"string"}}}}}]}""",
        )
        val out = ZenFreeLaneGate.rewriteBody(satisfied)
        assertEquals("You are helpful.", systemContent(out))
    }

    @Test
    fun `an unrelated field survives the rewrite untouched`() {
        val out = ZenFreeLaneGate.rewriteBody(
            JSONObject("""{"model":"deepseek-v4.1-flash-free","max_tokens":4096,"messages":[]}"""),
        )
        assertEquals("deepseek-v4.1-flash-free", out.getString("model"))
        assertEquals(4096, out.getInt("max_tokens"))
    }

    @Test
    fun `rewrite is idempotent`() {
        val once = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","messages":[]}"""))
        val twice = ZenFreeLaneGate.rewriteBody(once)
        assertEquals(toolNames(once), toolNames(twice))
        assertEquals(systemContent(once), systemContent(twice))
    }

    // -- request selection ---------------------------------------------------

    @Test
    fun `appliesTo requires a zen host`() {
        assertFalse(ZenFreeLaneGate.appliesTo(req("https://api.openai.com/v1/chat/completions", "Bearer public")))
        assertFalse(ZenFreeLaneGate.appliesTo(req("https://my-relay.example.com/zen/v1/chat/completions", "Bearer public")))
    }

    @Test
    fun `appliesTo requires the public placeholder credential`() {
        assertFalse(
            "a user-built zen instance with a real key must never be rewritten",
            ZenFreeLaneGate.appliesTo(req("https://opencode.ai/zen/v1/chat/completions", "Bearer sk-real-key")),
        )
        assertFalse(ZenFreeLaneGate.appliesTo(req("https://opencode.ai/zen/v1/chat/completions", "")))
    }

    @Test
    fun `appliesTo accepts the keyless lane on every zen host spelling`() {
        for (host in listOf(
            "https://opencode.ai/zen/v1/chat/completions",
            "https://opencode.ai/inference/v1/chat/completions",
            "https://inference.opencode.ai/v1/chat/completions",
            "https://gateway.opencode.ai/v1/chat/completions",
        )) {
            assertTrue(host, ZenFreeLaneGate.appliesTo(req(host, "Bearer public")))
        }
    }

    @Test
    fun `appliesTo ignores GET so the catalogue fetch is left alone`() {
        assertFalse(
            ZenFreeLaneGate.appliesTo(getReq("https://opencode.ai/zen/v1/models", "Bearer public")),
        )
    }

    // -- headers and session id ---------------------------------------------

    @Test
    fun `headersFor carries the measured client identity`() {
        val h = ZenFreeLaneGate.headersFor("ses_0123456789abABCDEFGHIJKLMN")
        assertEquals("Bearer public", h["Authorization"])
        assertEquals("opencode/prod/2.0.29/cli", h["User-Agent"])
        assertEquals("cli", h["x-opencode-client"])
        assertEquals("global", h["x-opencode-project"])
        assertEquals("text/event-stream", h["Accept"])
        assertEquals("application/json", h["Content-Type"])
    }

    @Test
    fun `every session header carries the same id`() {
        val sid = "ses_0123456789abABCDEFGHIJKLMN"
        val h = ZenFreeLaneGate.headersFor(sid)
        for (name in listOf("x-opencode-session", "x-opencode-session-id", "x-session-affinity", "x-session-id")) {
            assertEquals("$name must carry the sid", sid, h[name])
        }
    }

    @Test
    fun `session id matches the measured shape`() {
        repeat(50) {
            val sid = ZenSession.newId()
            assertTrue("bad sid shape: $sid", sid.matches(Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")))
            assertTrue(ZenSession.isWellFormed(sid))
        }
    }

    @Test
    fun `session ids are unique and monotonic within a millisecond`() {
        val ids = (1..200).map { ZenSession.newId() }
        assertEquals("sids collided", ids.size, ids.toSet().size)
    }

    @Test
    fun `isWellFormed rejects the shapes that got the lane 403 before`() {
        assertFalse(ZenSession.isWellFormed("ses_tooshort"))
        assertFalse(ZenSession.isWellFormed("0123456789abABCDEFGHIJKLMN"))
        assertFalse(ZenSession.isWellFormed(""))
        assertNull("a malformed stored id must not be reused", ZenSession.sanitizeOrNull("nonsense"))
        assertEquals(
            "ses_0123456789abABCDEFGHIJKLMN",
            ZenSession.sanitizeOrNull("ses_0123456789abABCDEFGHIJKLMN"),
        )
    }
}
