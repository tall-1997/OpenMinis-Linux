package com.openminis.app.provider

import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * [T-zen-gate-v2] The upstream keyless lane moved to `/inference/v1` and now
 * fingerprints the **v2 client** instead of checking a loose body shape.
 *
 * Measured 2026-10-14→17 (each row verified alone; every violation is its own
 * `403 FreeTierError`): the chat path must be `/inference/v1/chat/completions`,
 * the User-Agent must be the verbatim `opencode/latest/2.0.26/cli`, the tools
 * must declare `read` and `shell` with the official client's own definitions
 * (description and parameter schema compared in full), `tool_choice: none` is
 * refused, and the session id's embedded counter must sit in 1–63. The
 * reference gateways this lane is impersonated against decode all of it.
 *
 * These tests pin the rewrite so the v2 shape cannot silently regress back
 * into a 403 wall — which is exactly how the v1→v2 drift broke the lane in
 * the first place: every piece still looked plausible, none of it matched.
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

    private fun description(tool: JSONObject): String =
        tool.getJSONObject("function").getString("description")

    private fun parameters(tool: JSONObject): JSONObject =
        tool.getJSONObject("function").getJSONObject("parameters")

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

    // -- body rewrite: the v2 tool fingerprint --------------------------------

    @Test
    fun `a body with no tools gets both v2 gate tools injected`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","messages":[]}"""))
        val names = toolNames(out)
        assertTrue("read must be present: $names", names.contains("read"))
        assertTrue("shell must be present: $names", names.contains("shell"))
    }

    @Test
    fun `the v1 gate names glob and grep are no longer injected`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","messages":[]}"""))
        val names = toolNames(out)
        assertFalse("glob was a v1 name and now trips nothing: $names", names.contains("glob"))
        assertFalse("grep was a v1 name and now trips nothing: $names", names.contains("grep"))
    }

    @Test
    fun `the read definition is the official client's own wording`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","messages":[]}"""))
        val read = tool(out, "read") ?: error("read missing")
        assertEquals(
            "Read the contents of a file or directory. Supports text files, images, and PDFs. " +
                "Images and PDFs are presented directly to the model. Each text line is prefixed " +
                "by its 1-based line number as <line>: <content>. The prefix is for reference and " +
                "is not part of the file content. Directory entries are returned one per line. " +
                "Use offset and limit to read large files or directories in sections. Prefer one " +
                "larger read over many small slices, and use grep to find specific content in large files.",
            description(read),
        )
    }

    @Test
    fun `the shell definition is the official client's own wording`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","messages":[]}"""))
        val shell = tool(out, "shell") ?: error("shell missing")
        assertEquals(
            "Execute a shell command and return its output. Commands run on Linux using bash. " +
                "Quote file paths containing spaces or special characters. Prefer dedicated tools " +
                "over shell commands when possible. When output is large, the full result is saved " +
                "to a file and a truncated preview is returned. Rely on automatic truncation unless " +
                "filtering the output is more useful. Commands accept an optional timeout, " +
                "background commands have no timeout by default. Background commands return " +
                "immediately, and you will be notified when they complete.",
            description(shell),
        )
    }

    @Test
    fun `the read schema carries the official property descriptions and bounds`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","messages":[]}"""))
        val schema = parameters(tool(out, "read") ?: error("read missing"))
        val props = schema.getJSONObject("properties")
        assertEquals("File or directory to read", props.getJSONObject("path").getString("description"))
        assertEquals(0, props.getJSONObject("offset").getInt("minimum"))
        assertEquals(
            "The maximum number of lines or directory entries to read (defaults to and capped at 2000)",
            props.getJSONObject("limit").getString("description"),
        )
        assertEquals(listOf("path"), schema.getJSONArray("required").let { a -> (0 until a.length()).map { i -> a.getString(i) } })
        assertFalse("additionalProperties must be false like the official schema", schema.getBoolean("additionalProperties"))
    }

    @Test
    fun `the shell schema carries the official property descriptions`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","messages":[]}"""))
        val schema = parameters(tool(out, "shell") ?: error("shell missing"))
        val props = schema.getJSONObject("properties")
        assertEquals("Shell command string to execute", props.getJSONObject("command").getString("description"))
        assertTrue(props.getJSONObject("workdir").getString("description").startsWith("Working directory to execute"))
        assertEquals("boolean", props.getJSONObject("background").getString("type"))
        assertFalse(schema.getBoolean("additionalProperties"))
    }

    @Test
    fun `a near-miss read definition is replaced not patched`() {
        // Same name, same shape, one reworded description — the v2 gate compares
        // definitions, so a near-miss must be swapped for the verbatim one.
        val nearMiss = JSONObject(
            """
            {"type":"function","function":{"name":"read",
              "description":"Read a file or directory.",
              "parameters":{"type":"object","properties":{"path":{"type":"string"}},
                "required":["path"],"additionalProperties":false}}}
            """.trimIndent(),
        )
        val body = JSONObject("""{"model":"m","messages":[]}""")
        body.put("tools", JSONArray().put(nearMiss))
        val out = ZenFreeLaneGate.rewriteBody(body)
        val read = tool(out, "read") ?: error("read missing")
        assertTrue(
            "the reworded description must be replaced: ${description(read)}",
            description(read).startsWith("Read the contents of a file or directory."),
        )
        assertTrue(parameters(read).getJSONObject("properties").has("offset"))
    }

    @Test
    fun `a caller tool with another name is preserved untouched`() {
        val own = JSONObject(
            """
            {"type":"function","function":{"name":"file_read",
              "description":"Mine.","parameters":{"type":"object",
                "properties":{"path":{"type":"string"}},"required":["path"]}}}
            """.trimIndent(),
        )
        val body = JSONObject("""{"model":"m","messages":[]}""")
        body.put("tools", JSONArray().put(own))
        val out = ZenFreeLaneGate.rewriteBody(body)
        val names = toolNames(out)
        assertTrue(names.contains("file_read"))
        assertEquals("Mine.", tool(out, "file_read")!!.getJSONObject("function").getString("description"))
    }

    @Test
    fun `a duplicate same-named declaration is collapsed to one verbatim entry`() {
        val hollow = JSONObject("""{"type":"function","function":{"name":"read",
            "description":"x","parameters":{"type":"object","properties":{}}}}""".trimIndent())
        val body = JSONObject("""{"model":"m","messages":[]}""")
        body.put("tools", JSONArray().put(hollow).put(hollow))
        val out = ZenFreeLaneGate.rewriteBody(body)
        assertEquals(1, toolNames(out).count { it == "read" })
    }

    // -- body rewrite: stream and tool_choice ---------------------------------

    @Test
    fun `stream is forced true even when the caller asked for false`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","stream":false,"messages":[]}"""))
        assertTrue(out.getBoolean("stream"))
    }

    @Test
    fun `stream is added when the caller omitted it`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","messages":[]}"""))
        assertTrue(out.getBoolean("stream"))
    }

    @Test
    fun `tool_choice none is removed so the v2 gate does not refuse it`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","tool_choice":"none","messages":[]}"""))
        assertFalse(out.has("tool_choice"))
    }

    @Test
    fun `tool_choice none in object form is removed too`() {
        val out = ZenFreeLaneGate.rewriteBody(
            JSONObject("""{"model":"m","tool_choice":{"type":"none"},"messages":[]}"""),
        )
        assertFalse(out.has("tool_choice"))
    }

    @Test
    fun `an explicit tool_choice auto is preserved`() {
        val out = ZenFreeLaneGate.rewriteBody(JSONObject("""{"model":"m","tool_choice":"auto","messages":[]}"""))
        assertEquals("auto", out.optString("tool_choice"))
    }

    // -- the placeholder notice ------------------------------------------------

    @Test
    fun `a placeholder notice is appended to the first system message`() {
        val body = JSONObject("""{"model":"m","messages":[{"role":"system","content":"You are r."}]}""")
        val out = ZenFreeLaneGate.rewriteBody(body)
        val content = systemContent(out) ?: error("system content missing")
        assertTrue(content.startsWith("You are r."))
        assertTrue(content.contains("read and shell"))
    }

    @Test
    fun `the notice lands on a developer message when there is no system message`() {
        val body = JSONObject("""{"model":"m","messages":[{"role":"developer","content":"d"}]}""")
        val out = ZenFreeLaneGate.rewriteBody(body)
        assertTrue(systemContent(out)?.contains("read and shell") == true)
    }

    @Test
    fun `the notice is appended into array-shaped content without dropping parts`() {
        val body = JSONObject(
            """{"model":"m","messages":[{"role":"system","content":[
                {"type":"text","text":"one"},{"type":"text","text":"two"}]}]}""",
        )
        val out = ZenFreeLaneGate.rewriteBody(body)
        val content = systemContent(out) ?: error("system content missing")
        assertTrue(content.startsWith("onetwo"))
        assertTrue(content.contains("read and shell"))
    }

    @Test
    fun `no notice is appended when nothing was injected`() {
        // Build a body that already satisfies the gate: seed the verbatim tools
        // from a first rewrite, then rewrite again — the second pass must be a
        // no-op on the system message.
        val seed = ZenFreeLaneGate.rewriteBody(
            JSONObject("""{"model":"m","messages":[{"role":"system","content":"s"}]}"""),
        )
        val satisfied = JSONObject("""{"model":"m","messages":[{"role":"system","content":"s"}]}""")
        satisfied.put("tools", seed.getJSONArray("tools"))
        val out = ZenFreeLaneGate.rewriteBody(satisfied)
        assertEquals("s", systemContent(out))
    }

    @Test
    fun `an unrelated field survives the rewrite untouched`() {
        val out = ZenFreeLaneGate.rewriteBody(
            JSONObject("""{"model":"m","temperature":0.7,"user":"u","messages":[]}"""),
        )
        assertEquals(0.7, out.getDouble("temperature"), 0.0)
        assertEquals("u", out.getString("user"))
    }

    @Test
    fun `rewrite is idempotent`() {
        val first = ZenFreeLaneGate.rewriteBody(
            JSONObject("""{"model":"m","messages":[{"role":"system","content":"s"}],"tool_choice":"none"}"""),
        )
        val second = ZenFreeLaneGate.rewriteBody(first)
        assertEquals(first.toString(), second.toString())
    }

    // -- request selection ------------------------------------------------------

    @Test
    fun `appliesTo requires a zen host`() {
        assertFalse(
            ZenFreeLaneGate.appliesTo(req("https://api.example.com/v1/chat/completions", "Bearer public")),
        )
    }

    @Test
    fun `appliesTo requires the public placeholder credential`() {
        assertFalse(
            ZenFreeLaneGate.appliesTo(req("https://opencode.ai/zen/v1/chat/completions", "Bearer sk-real")),
        )
    }

    @Test
    fun `appliesTo accepts the keyless lane on every zen host spelling`() {
        assertTrue(ZenFreeLaneGate.appliesTo(req("https://opencode.ai/zen/v1/chat/completions", "Bearer public")))
        assertTrue(ZenFreeLaneGate.appliesTo(req("https://opencode.ai/inference/v1/chat/completions", "Bearer public")))
    }

    @Test
    fun `appliesTo matches the models listing GET and ignores other GETs`() {
        assertTrue(
            ZenFreeLaneGate.appliesTo(getReq("https://opencode.ai/zen/v1/models", "Bearer public")),
        )
        assertFalse(
            "an unrelated GET must pass through untouched",
            ZenFreeLaneGate.appliesTo(getReq("https://opencode.ai/zen/v1/other", "Bearer public")),
        )
    }

    // -- path retarget ----------------------------------------------------------

    @Test
    fun `the zen chat path is retargeted to the inference path`() {
        val out = ZenFreeLaneGate.rewriteUrl("https://opencode.ai/zen/v1/chat/completions".toHttpUrl())
        assertEquals("https://opencode.ai/inference/v1/chat/completions", out.toString())
    }

    @Test
    fun `the zen models listing path is retargeted too`() {
        val out = ZenFreeLaneGate.rewriteUrl("https://opencode.ai/zen/v1/models".toHttpUrl())
        assertEquals("https://opencode.ai/inference/v1/models", out.toString())
    }

    @Test
    fun `a query string survives the retarget`() {
        val out = ZenFreeLaneGate.rewriteUrl("https://opencode.ai/zen/v1/models?limit=512".toHttpUrl())
        assertEquals("https://opencode.ai/inference/v1/models?limit=512", out.toString())
    }

    @Test
    fun `an already-inference path is returned untouched`() {
        assertNull(ZenFreeLaneGate.rewriteUrl("https://opencode.ai/inference/v1/chat/completions".toHttpUrl()))
    }

    @Test
    fun `a non-zen path is returned untouched`() {
        assertNull(ZenFreeLaneGate.rewriteUrl("https://api.example.com/zen/v1/chat/completions".toHttpUrl()))
    }

    // -- headers ----------------------------------------------------------------

    @Test
    fun `headersFor carries the verbatim v2 client identity`() {
        val h = ZenFreeLaneGate.headersFor("ses_0123456789abABCDEFGHIJKLMN")
        assertEquals("opencode/latest/2.0.26/cli", h["User-Agent"])
        assertEquals("cli", h["x-opencode-client"])
        assertEquals("Bearer public", h["Authorization"])
    }

    @Test
    fun `the v1-only request header is never sent`() {
        val h = ZenFreeLaneGate.headersFor("ses_0123456789abABCDEFGHIJKLMN")
        assertFalse("x-opencode-request is a v1 header and a v2 403", h.containsKey("x-opencode-request"))
    }

    @Test
    fun `every session header carries the same id`() {
        val h = ZenFreeLaneGate.headersFor("ses_0123456789abABCDEFGHIJKLMN")
        val sid = h["x-opencode-session"]
        assertEquals(sid, h["x-opencode-session-id"])
        assertEquals(sid, h["x-session-affinity"])
        assertEquals(sid, h["x-session-id"])
    }

    // -- session ids --------------------------------------------------------------

    @Test
    fun `session id matches the measured shape`() {
        repeat(50) {
            val sid = ZenSession.newId()
            assertTrue(ZenSession.isWellFormed(sid))
        }
    }

    @Test
    fun `the embedded counter stays inside the 1-63 window the gate decodes`() {
        repeat(200) {
            val sid = ZenSession.newId()
            // ses_ + 12 hex: the inverted value's low 12 bits are 0xfff - counter,
            // so counter 1..63 lands them in 0xfc0..0xffe.
            val hex = sid.substring(4, 16)
            val low12 = hex.substring(9).toLong(16)
            assertTrue("low 12 bits $low12 outside fc0..ffe in $sid", low12 in 0xfc0..0xffe)
        }
    }

    @Test
    fun `session ids are unique within a millisecond`() {
        val ids = (1..200).map { ZenSession.newId() }
        assertEquals(200, ids.toSet().size)
    }

    @Test
    fun `stableId pins one id for the process and rotate replaces it`() {
        ZenSession.resetForTest()
        try {
            val first = ZenSession.stableId()
            assertEquals(first, ZenSession.stableId())
            val rotated = ZenSession.rotate()
            assertNotEqualsKt(first, rotated)
            assertEquals(rotated, ZenSession.stableId())
        } finally {
            ZenSession.resetForTest()
        }
    }

    @Test
    fun `isWellFormed rejects the shapes that got the lane 403 before`() {
        assertFalse(ZenSession.isWellFormed("ses_tooshort"))
        assertFalse(ZenSession.isWellFormed("0123456789abABCDEFGHIJKLMN"))
        assertFalse(ZenSession.isWellFormed(""))
        assertNull("a malformed stored id must not be reused", ZenSession.sanitizeOrNull("nonsense"))
    }

    private fun assertNotEqualsKt(a: String, b: String) {
        assertTrue("expected $a != $b", a != b)
    }
}
