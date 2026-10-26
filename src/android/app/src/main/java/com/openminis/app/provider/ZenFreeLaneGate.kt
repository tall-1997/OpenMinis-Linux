package com.openminis.app.provider

import com.openminis.app.logging.AppLogger
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Satisfies the keyless Zen lane's v2 request-shape gate.
 *
 * [T-zen-gate-v2] Upstream moved the free lane to `/inference/v1` and replaced
 * the v1 body-shape check with a **v2 client fingerprint**. Measured
 * 2026-10-14→17 (each row verified alone against the live gateway; every
 * violation is its own `403 FreeTierError`):
 *
 * | 项 | v1（旧，本文件 2026-10-23 版） | v2（现在） |
 * |---|---|---|
 * | 聊天路径 | `/zen/v1/chat/completions` | `/inference/v1/chat/completions`；`/zen/v1` 即使其余全对也 403 |
 * | User-Agent | `opencode/prod/<v>/cli` | `opencode/latest/2.0.26/cli` 整串匹配 |
 * | 工具闸门 | `shell`/`glob`/`grep`/`read` 四个名字，各带至少一个 property | **`read` 与 `shell` 两个名字，description 与参数 schema 必须与官方客户端逐字一致** |
 * | `tool_choice` | 无工具时补 `none` | **`none` 即 403**，绝不下发 |
 * | 会话 id | 形状即可 | 形状 + **计数器 1–63**（见 [ZenSession]） |
 * | 请求头 | — | 不带 `x-opencode-request`（v1 专用，带了即 403） |
 *
 * The rewrite happens on the wire, in one place, so every caller — streaming,
 * non-streaming, subagents, vision, compaction, title generation — gets it
 * without a per-call-site change, and no request-building code outside this
 * file needs to know the lane exists.
 *
 * **Scope is deliberately narrow.** [appliesTo] requires a Zen host *and* the
 * `Bearer public` placeholder credential, so a user-built Zen instance with a
 * real API key is never rewritten, and no other provider's traffic is touched
 * at all: a non-matching request is passed through byte-identical.
 *
 * **The seeded base URL stays `/zen/v1`.** It is the cache key and the join
 * key for seeded rows, slot refs and the recovery channel, so moving it would
 * strand every existing user's configuration. [rewriteUrl] retargets the
 * *request path* to `/inference/v1` on the wire instead, which keeps the
 * stored configuration stable while speaking the path the lane now answers
 * on. The models listing answers on both paths today; it is retargeted too
 * so a future retirement of `/zen/v1/models` cannot break the catalogue.
 */
internal object ZenFreeLaneGate : Interceptor {
    private const val TAG = "ZenFreeLaneGate"

    /** A refused session id is rotated at most this many times before giving up. */
    private const val MAX_SID_RETRIES = 3

    private const val PUBLIC_CREDENTIAL = "Bearer public"
    private val JSON_MEDIA: MediaType = "application/json".toMediaType()

    /** The v2 gate names, in the order the official client emits them. */
    private val GATE_NAMES = listOf("read", "shell")

    /**
     * Appended to the first system message when a placeholder was injected, so
     * the model does not call a tool that has no implementation behind it.
     */
    private const val PLACEHOLDER_NOTICE =
        "\n\nThe tools named read and shell in this request are " +
            "placeholders. Do not call them; use the other tools you have been given."

    // -- request selection ---------------------------------------------------

    /**
     * The lane owns POSTs anywhere under a Zen host (chat traffic — the path
     * is retargeted by [rewriteUrl]) and the models listing GET. Other GETs —
     * a user browsing the host, an unrelated fetch — are left alone.
     */
    fun appliesTo(request: Request): Boolean {
        if (!ZenEndpoint.isZenHost(request.url.toString())) return false
        if (request.header("Authorization") != PUBLIC_CREDENTIAL) return false
        if (request.method.equals("POST", ignoreCase = true)) return true
        return request.method.equals("GET", ignoreCase = true) &&
            request.url.encodedPath.endsWith("/models")
    }

    // -- path retarget -------------------------------------------------------

    /**
     * `/zen/v1/<rest>` becomes `/inference/v1/<rest>`; every other path —
     * including a base URL the user already wrote as `/inference/v1` — comes
     * back null and is sent as-is. Query strings are preserved.
     */
    fun rewriteUrl(url: HttpUrl): HttpUrl? {
        // The narrow-scope invariant lives here, not only in appliesTo: a
        // non-Zen host that happens to carry a /zen/v1/ path is never retargeted.
        if (!ZenEndpoint.isZenHost(url.toString())) return null
        val path = url.encodedPath
        if (!path.startsWith("/zen/v1/")) return null
        return url.newBuilder()
            .encodedPath("/inference/v1" + path.removePrefix("/zen/v1"))
            .build()
    }

    // -- body rewrite --------------------------------------------------------

    /**
     * In-place rewrite, four ordered steps. Idempotent: a body that already
     * satisfies the gate comes back unchanged apart from `stream`.
     */
    fun rewriteBody(json: JSONObject): JSONObject {
        // 1. A non-streaming request is refused even when the gate is satisfied.
        json.put("stream", true)

        // 2. Both gate names must be present with the verbatim v2 definitions.
        //    A same-named tool whose definition differs in any way is replaced,
        //    not patched: the gate compares the whole definition, and a
        //    near-miss is a 403.
        val tools = json.optJSONArray("tools") ?: JSONArray().also { json.put("tools", it) }
        var injected = 0
        for (name in GATE_NAMES) {
            // Exactly one verbatim entry per gate name survives: a satisfied
            // entry is kept, every other same-named declaration — near-miss or
            // duplicate — is removed, and a verbatim one is appended only when
            // none survived. The gate reads the whole tools array; a leftover
            // second declaration of a gate name is as fatal as a near-miss.
            var keptVerbatim = false
            var removedSameNamed = false
            var i = 0
            while (i < tools.length()) {
                val entry = tools.optJSONObject(i)
                if (entry?.optJSONObject("function")?.optString("name") == name) {
                    if (!keptVerbatim && isSatisfied(entry, name)) {
                        keptVerbatim = true
                        i++
                    } else {
                        tools.remove(i)
                        removedSameNamed = true
                    }
                } else {
                    i++
                }
            }
            if (!keptVerbatim) {
                tools.put(verbatimTool(name))
                injected++
            } else if (removedSameNamed) {
                // A near-miss was replaced by the surviving verbatim entry, so
                // the gate tool in the array is hollow — the notice must fire.
                injected++
            }
        }

        // 3. `tool_choice: "none"` is a 403 on the v2 gate, and it would also
        //    forbid the tool calls the agent needs. Both spellings — the bare
        //    string and the `{"type":"none"}` object — are removed.
        if (json.optString("tool_choice") == "none") json.remove("tool_choice")
        val choice = json.optJSONObject("tool_choice")
        if (choice != null && choice.optString("type") == "none") json.remove("tool_choice")

        // 4. Tell the model which of its tools are hollow.
        if (injected > 0) appendNotice(json)
        return json
    }

    /**
     * A same-named tool counts only when it carries the official definition
     * byte-for-byte — description and parameter schema both. The v2 gate
     * compares definitions, so "close" is a refusal.
     */
    private fun isSatisfied(existing: JSONObject?, name: String): Boolean {
        if (existing == null) return false
        val function = existing.optJSONObject("function") ?: return false
        if (function.optString("name") != name) return false
        if (function.optString("description") != DESCRIPTIONS[name]) return false
        val parameters = function.optJSONObject("parameters") ?: return false
        return jsonEquals(parameters, SCHEMAS[name])
    }

    /** Structural JSON equality; org.json objects do not implement equals. */
    private fun jsonEquals(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject ->
            a.length() == b.length() &&
                a.keys().asSequence().all { k -> b.has(k) && jsonEquals(a.get(k), b.get(k)) }
        a is JSONArray && b is JSONArray ->
            a.length() == b.length() &&
                (0 until a.length()).all { i -> jsonEquals(a.get(i), b.get(i)) }
        else -> a == b
    }

    private fun appendNotice(json: JSONObject) {
        val messages = json.optJSONArray("messages") ?: return
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val role = message.optString("role")
            if (role != "system" && role != "developer") continue
            when (val content = message.opt("content")) {
                is String -> message.put("content", content + PLACEHOLDER_NOTICE)
                is JSONArray -> content.put(
                    JSONObject().put("type", "text").put("text", PLACEHOLDER_NOTICE),
                )
                null -> message.put("content", PLACEHOLDER_NOTICE)
                else -> return
            }
            return
        }
    }

    // -- the v2 fingerprint definitions (verbatim from the official client) ----

    /**
     * ⚠️ Verbatim from the OpenCode 2.0.26 CLI. The v2 gate compares the
     * description and the parameter schema against its own copy; rewording,
     * trimming, renaming a property or dropping `additionalProperties: false`
     * is a `403 FreeTierError`. Do not "improve" these strings.
     */
    private val DESCRIPTIONS = mapOf(
        "read" to "Read the contents of a file or directory. Supports text files, " +
            "images, and PDFs. Images and PDFs are presented directly to the model. " +
            "Each text line is prefixed by its 1-based line number as <line>: <content>. " +
            "The prefix is for reference and is not part of the file content. " +
            "Directory entries are returned one per line. Use offset and limit to " +
            "read large files or directories in sections. Prefer one larger read " +
            "over many small slices, and use grep to find specific content in large files.",
        "shell" to "Execute a shell command and return its output. Commands run on " +
            "Linux using bash. Quote file paths containing spaces or special characters. " +
            "Prefer dedicated tools over shell commands when possible. When output is " +
            "large, the full result is saved to a file and a truncated preview is " +
            "returned. Rely on automatic truncation unless filtering the output is " +
            "more useful. Commands accept an optional timeout, background commands " +
            "have no timeout by default. Background commands return immediately, and " +
            "you will be notified when they complete.",
    )

    /** The parameter schemas that pair with [DESCRIPTIONS], same verbatim rule. */
    private val SCHEMAS: Map<String, JSONObject> = mapOf(
        "read" to JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "path": {"type": "string", "description": "File or directory to read"},
                "offset": {"type": "integer", "minimum": 0, "description": "The line or directory entry to start reading from (1-based)"},
                "limit": {"type": "integer", "minimum": 0, "description": "The maximum number of lines or directory entries to read (defaults to and capped at 2000)"}
              },
              "required": ["path"],
              "additionalProperties": false
            }
            """.trimIndent(),
        ),
        "shell" to JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "command": {"type": "string", "description": "Shell command string to execute"},
                "workdir": {"type": "string", "description": "Working directory to execute the command in. Defaults to the current working directory. When possible, avoid changing directories in the command and set the working directory here instead."},
                "timeout": {"type": "integer", "minimum": 0, "description": "Timeout in milliseconds. Set to 0 to disable the timeout. Defaults to 120000 for foreground commands. Background commands have no timeout by default."},
                "background": {"type": "boolean", "description": "Run the command in the background and return immediately (useful for dev servers and long-running builds). You do not need to use \"&\" at the end of the command when using this parameter. You will be notified when it completes. DO NOT poll for completion."}
              },
              "required": ["command"],
              "additionalProperties": false
            }
            """.trimIndent(),
        ),
    )

    private fun verbatimTool(name: String): JSONObject {
        val function = JSONObject()
            .put("name", name)
            .put("description", DESCRIPTIONS.getValue(name))
            .put("strict", false)
            .put("parameters", SCHEMAS.getValue(name))
        return JSONObject().put("type", "function").put("function", function)
    }

    // -- headers -------------------------------------------------------------

    /**
     * The v2 header set. `x-opencode-request` is deliberately absent: it is a
     * v1-only header and its presence is a 403 on the v2 gate.
     */
    fun headersFor(sid: String): Map<String, String> = linkedMapOf(
        "Authorization" to PUBLIC_CREDENTIAL,
        "User-Agent" to ZenClientVersion.userAgent(),
        "x-opencode-client" to "cli",
        "x-opencode-project" to "global",
        "x-opencode-session" to sid,
        "x-opencode-session-id" to sid,
        "x-session-affinity" to sid,
        "x-session-id" to sid,
        "Accept" to "text/event-stream",
        "Content-Type" to "application/json",
    )

    // -- interceptor ---------------------------------------------------------

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!appliesTo(request)) return chain.proceed(request)

        val retargeted = rewriteUrl(request.url)?.let { request.newBuilder().url(it).build() }
            ?: request

        // A GET (the models listing) carries the client identity but no body.
        if (!retargeted.method.equals("POST", ignoreCase = true)) {
            val builder = retargeted.newBuilder()
            for ((key, value) in headersFor(ZenSession.stableId())) builder.header(key, value)
            return chain.proceed(builder.build())
        }

        val originalBody = retargeted.body
        if (originalBody == null) {
            AppLogger.warning(TAG, "keyless lane request without a body, passing through")
            return chain.proceed(retargeted)
        }

        val raw = runCatching {
            Buffer().use { buffer ->
                originalBody.writeTo(buffer)
                buffer.readUtf8()
            }
        }.getOrNull()
        if (raw == null) {
            AppLogger.warning(TAG, "could not read the request body, passing through")
            return chain.proceed(retargeted)
        }

        val json = runCatching { JSONObject(raw) }.getOrNull()
        if (json == null) {
            AppLogger.warning(TAG, "request body is not JSON, passing through")
            return chain.proceed(retargeted)
        }

        rewriteBody(json)
        val payload = json.toString()
        val contentType = originalBody.contentType() ?: JSON_MEDIA

        var sid = ZenSession.stableId()
        var attempt = 0
        while (true) {
            val builder = retargeted.newBuilder()
                .method(retargeted.method, payload.toRequestBody(contentType))
            for ((key, value) in headersFor(sid)) builder.header(key, value)
            val response = chain.proceed(builder.build())
            if (response.code != 401 || attempt >= MAX_SID_RETRIES) return response
            response.close()
            attempt++
            sid = ZenSession.rotate()
            AppLogger.info(TAG, "401 on the keyless lane, rotating session id ($attempt/$MAX_SID_RETRIES)")
        }
    }
}
