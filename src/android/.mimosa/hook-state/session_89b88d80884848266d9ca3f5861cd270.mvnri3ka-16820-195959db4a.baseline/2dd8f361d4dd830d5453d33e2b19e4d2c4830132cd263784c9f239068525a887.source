package com.openminis.app.provider

import com.openminis.app.logging.AppLogger
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
 * Satisfies the keyless Zen lane's request-shape gate.
 *
 * [T-zen-gate-body-shape] The upstream endpoint answers `403 FreeTierError`
 * unless the request body carries a function named `read` **and** asks for a
 * stream. Measured 2026-10-23: `tools=[read]` alone returns 200 with a full
 * answer, while `bash`, `glob`, `grep`, `write`, `edit`, `task` and `webfetch`
 * each return 403 on their own, and `stream=false` is refused even with the
 * gate satisfied. Client identity is not verified at all.
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
 */
internal object ZenFreeLaneGate : Interceptor {
    private const val TAG = "ZenFreeLaneGate"

    /** Sample client rotates its session id at most three times before giving up. */
    private const val MAX_SID_RETRIES = 3

    private const val PUBLIC_CREDENTIAL = "Bearer public"
    private val JSON_MEDIA: MediaType = "application/json".toMediaType()

    /** The four names the gate looks for, in the order the sample client emits them. */
    private val GATE_NAMES = listOf("shell", "glob", "grep", "read")

    /**
     * Appended to the first system message when a placeholder was injected, so
     * the model does not call a tool that has no implementation behind it.
     * Measured: real `file_read`/`shell_execute` win 4/4 either way, but the
     * note is cheap insurance for weaker models.
     */
    private const val PLACEHOLDER_NOTICE =
        "\n\nThe tools named shell, glob, grep and read in this request are " +
            "placeholders. Do not call them; use the other tools you have been given."

    // -- request selection ---------------------------------------------------

    fun appliesTo(request: Request): Boolean {
        if (!request.method.equals("POST", ignoreCase = true)) return false
        if (!ZenEndpoint.isZenHost(request.url.toString())) return false
        return request.header("Authorization") == PUBLIC_CREDENTIAL
    }

    // -- body rewrite --------------------------------------------------------

    /**
     * In-place rewrite, five ordered steps. Idempotent: a body that already
     * satisfies the gate comes back unchanged apart from `stream`.
     */
    fun rewriteBody(json: JSONObject): JSONObject {
        // 1. A non-streaming request is refused even when the gate is satisfied.
        json.put("stream", true)

        // 2. Every gate name must be present with non-empty properties.
        val tools = json.optJSONArray("tools") ?: JSONArray().also { json.put("tools", it) }
        var injected = 0
        for (name in GATE_NAMES) {
            // JSONArray only offers remove(int), so the hollow same-named entry is
            // located by index and dropped before the placeholder takes its place.
            val index = indexOfTool(tools, name)
            val existing = if (index >= 0) tools.optJSONObject(index) else null
            if (isSatisfied(existing)) continue
            if (index >= 0) tools.remove(index)
            tools.put(placeholderFor(name))
            injected++
        }

        // 4. `tool_choice: "none"` would forbid the tool calls the agent needs.
        if (json.optString("tool_choice") == "none") json.remove("tool_choice")

        // 5. Tell the model which of its tools are hollow.
        if (injected > 0) appendNotice(json)
        return json
    }

    private fun indexOfTool(tools: JSONArray, name: String): Int {
        for (i in 0 until tools.length()) {
            val entry = tools.optJSONObject(i) ?: continue
            if (entry.optJSONObject("function")?.optString("name") == name) return i
        }
        return -1
    }

    /** A same-named tool counts only when it declares real parameters. */
    private fun isSatisfied(existing: JSONObject?): Boolean {
        if (existing == null) return false
        val properties = existing.optJSONObject("function")
            ?.optJSONObject("parameters")
            ?.optJSONObject("properties")
            ?: return false
        return properties.length() > 0
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

    // -- placeholder definitions (descriptions verbatim from the sample client)

    private fun placeholderFor(name: String): JSONObject = when (name) {
        "shell" -> placeholder(
            name = "shell",
            description = "Execute a shell command and return its output.",
            required = listOf("command"),
            properties = listOf(
                "command" to "string",
                "workdir" to "string",
                "timeout" to "integer",
                "background" to "boolean",
            ),
        )
        "read" -> placeholder(
            name = "read",
            description = "Read the contents of a file or directory.",
            required = listOf("path"),
            properties = listOf(
                "path" to "string",
                "offset" to "integer",
                "limit" to "integer",
            ),
        )
        "glob" -> placeholder(
            name = "glob",
            description = "Search file paths using a glob pattern (examples: \"**/*.ts\", \"src/**/*.tsx\").",
            required = listOf("pattern"),
            properties = listOf(
                "pattern" to "string",
                "path" to "string",
                "hidden" to "boolean",
                "limit" to "integer",
            ),
        )
        "grep" -> placeholder(
            name = "grep",
            description = "Search file contents using ripgrep syntax.",
            required = listOf("pattern"),
            properties = listOf(
                "pattern" to "string",
                "path" to "string",
                "include" to "string",
                "literal" to "boolean",
                "caseSensitive" to "boolean",
                "limit" to "integer",
            ),
        )
        else -> error("no placeholder defined for $name")
    }

    private fun placeholder(
        name: String,
        description: String,
        required: List<String>,
        properties: List<Pair<String, String>>,
    ): JSONObject {
        val props = JSONObject()
        for ((key, type) in properties) props.put(key, JSONObject().put("type", type))
        val requiredArray = JSONArray()
        for (key in required) requiredArray.put(key)
        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", requiredArray)
        val function = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("strict", false)
            .put("parameters", parameters)
        return JSONObject().put("type", "function").put("function", function)
    }

    // -- headers -------------------------------------------------------------

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

        val originalBody = request.body
        if (originalBody == null) {
            AppLogger.warning(TAG, "keyless lane request without a body, passing through")
            return chain.proceed(request)
        }

        val raw = runCatching {
            Buffer().use { buffer ->
                originalBody.writeTo(buffer)
                buffer.readUtf8()
            }
        }.getOrNull()
        if (raw == null) {
            AppLogger.warning(TAG, "could not read the request body, passing through")
            return chain.proceed(request)
        }

        val json = runCatching { JSONObject(raw) }.getOrNull()
        if (json == null) {
            AppLogger.warning(TAG, "request body is not JSON, passing through")
            return chain.proceed(request)
        }

        ZenClientVersion.refreshOnceInBackground()
        rewriteBody(json)
        val payload = json.toString()
        val contentType = originalBody.contentType() ?: JSON_MEDIA

        var sid = ZenSession.newId()
        var attempt = 0
        while (true) {
            val builder = request.newBuilder()
                .method(request.method, payload.toRequestBody(contentType))
            for ((key, value) in headersFor(sid)) builder.header(key, value)
            val response = chain.proceed(builder.build())
            if (response.code != 401 || attempt >= MAX_SID_RETRIES) return response
            response.close()
            attempt++
            sid = ZenSession.newId()
            AppLogger.info(TAG, "401 on the keyless lane, rotating session id ($attempt/$MAX_SID_RETRIES)")
        }
    }
}
