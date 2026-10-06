package com.openminis.app.mcp.client

import com.openminis.app.mcp.client.McpJsonRpc.McpError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.TimeUnit

/**
 * [T-mcp-native] Native Streamable-HTTP (MCP 2025-03-26+) transport — the
 * Kotlin replacement for the in-guest `minis-mcp-cli` HTTP path. Behaviour is
 * deliberately line-for-line compatible with `transport/http.py`:
 *
 *  - One POST per JSON-RPC message; `Accept: application/json,
 *    text/event-stream` is FORCED (user headers cannot drop it — some
 *    gateways reject an incomplete Accept).
 *  - The reply body may be a plain JSON document or an SSE event stream;
 *    both are parsed for the matching id.
 *  - `Mcp-Session-Id` from the initialize response is replayed on every
 *    later request; stateful servers reject a re-`initialize`, so the
 *    handshake happens exactly once per client instance.
 *  - `MCP-Protocol-Version` is declared on every post-initialize request.
 *  - Connect phase is bounded (15s) with ONE retry — nothing was sent, so
 *    the retry cannot double-execute a tool call. Post-send failures are
 *    never retried (tools/call may be non-idempotent).
 *  - OAuth servers get `Authorization: Bearer <token>` from [oauthToken];
 *    HTTP 401 surfaces as AUTH_REQUIRED so the agent can point the user at
 *    Settings → MCP re-authorization.
 *
 * Blocking OkHttp calls — the tool dispatcher invokes this on a worker
 * dispatcher. Thread-safety: [lock] serializes requests (a single in-flight
 * JSON-RPC exchange per client), matching the CLI daemon's per-server lock.
 */
class McpHttpClient(
    override val serverName: String,
    private val url: String,
    /** Static headers from servers.json, already $$VAR-expanded. */
    private val headers: Map<String, String>,
    /** Current bearer token, or null when the server has no (usable) OAuth grant. */
    private val oauthToken: () -> String?,
    /** True when the server declares OAuth — 401 then means AUTH_REQUIRED. */
    private val oauthConfigured: Boolean,
    private val http: OkHttpClient = defaultClient(),
) : McpClient {

    companion object {
        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 300L
        private const val WRITE_TIMEOUT_S = 30L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private val lock = Any()

    @Volatile private var sessionId: String? = null
    @Volatile private var initialized = false

    override fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val id = McpJsonRpc.nextId()
            val result = post(McpJsonRpc.request(id, "initialize", McpJsonRpc.initializeParams()), id)
            val serverVersion = result?.optString("protocolVersion", "") ?: ""
            if (serverVersion.isNotEmpty() && serverVersion != McpJsonRpc.PROTOCOL_VERSION) {
                // Downgrade notice only — servers answer with the version THEY
                // speak; the spec allows the client to continue or disconnect.
                // The CLI continues, so we do too (and echo the negotiated
                // version back on later requests per 2025-06-18).
                negotiatedVersion = serverVersion
            }
            // notifications/initialized — fire and forget (202 is success).
            runCatching {
                post(McpJsonRpc.notification("notifications/initialized"), wantId = null)
            }
            initialized = true
        }
    }

    @Volatile private var negotiatedVersion: String = McpJsonRpc.PROTOCOL_VERSION

    override fun listTools(): JSONArray {
        ensureInitialized()
        val id = McpJsonRpc.nextId()
        val result = post(McpJsonRpc.request(id, "tools/list", JSONObject()), id)
            ?: throw McpError(McpError.PROTOCOL_ERROR, "tools/list returned no result")
        return result.optJSONArray("tools") ?: JSONArray()
    }

    override fun callTool(tool: String, arguments: JSONObject): JSONObject {
        ensureInitialized()
        val id = McpJsonRpc.nextId()
        val params = JSONObject().apply {
            put("name", tool)
            put("arguments", arguments)
        }
        return post(McpJsonRpc.request(id, "tools/call", params), id)
            ?: throw McpError(McpError.PROTOCOL_ERROR, "tools/call returned no result")
    }

    override fun close() {
        // OkHttp connections are pooled app-wide; nothing server-specific to
        // tear down. Session state is dropped so a later reuse re-handshakes.
        synchronized(lock) {
            initialized = false
            sessionId = null
        }
    }

    /**
     * POST one JSON-RPC message and return the `result` object for [wantId]
     * (null for notifications / 202 replies). Throws [McpError] on transport
     * or JSON-RPC level failures.
     */
    private fun post(payload: JSONObject, wantId: Int?): JSONObject? {
        val body = payload.toString().toRequestBody(JSON_MEDIA)
        var connectAttempts = 0
        while (true) {
            connectAttempts++
            val builder = Request.Builder().url(url).post(body)
            // User headers first, then the protocol-mandated overrides — a
            // user-supplied accept/content-type/authorization cannot break the
            // handshake (case-insensitive replace, per http.py).
            for ((k, v) in headers) {
                if (k.equals("accept", true) || k.equals("content-type", true)) continue
                builder.header(k, v)
            }
            builder.header("Content-Type", "application/json")
            builder.header("Accept", "application/json, text/event-stream")
            oauthToken()?.takeIf { it.isNotEmpty() }?.let {
                builder.header("Authorization", "Bearer $it")
            }
            sessionId?.let { builder.header("Mcp-Session-Id", it) }
            if (initialized) builder.header("MCP-Protocol-Version", negotiatedVersion)

            try {
                http.newCall(builder.build()).execute().use { resp ->
                    if (resp.code == 401 && oauthConfigured) {
                        throw McpError(
                            McpError.AUTH_REQUIRED,
                            "server rejected the stored OAuth token (HTTP 401) — re-authorize in Settings → MCP",
                        )
                    }
                    if (resp.code == 401) {
                        throw McpError(McpError.AUTH_REQUIRED, "HTTP 401 Unauthorized")
                    }
                    if (!resp.isSuccessful) {
                        val preview = runCatching { resp.body?.string()?.take(300) ?: "" }.getOrDefault("")
                        throw McpError(McpError.SERVER_ERROR, "HTTP ${resp.code}: $preview")
                    }
                    // Capture the session id from whichever response carries it
                    // (servers send it on the initialize reply).
                    resp.header("Mcp-Session-Id")?.takeIf { it.isNotEmpty() }?.let {
                        sessionId = it
                    }
                    if (wantId == null || resp.code == 202) return null
                    val ct = resp.header("Content-Type") ?: ""
                    val text = resp.body?.string() ?: ""
                    val reply = McpJsonRpc.extractResponse(text, ct, wantId)
                        ?: throw McpError(
                            McpError.PROTOCOL_ERROR,
                            "no JSON-RPC reply for id=$wantId (content-type=$ct, ${text.length} bytes)",
                        )
                    return McpJsonRpc.unwrapResult(reply)
                }
            } catch (e: McpError) {
                throw e
            } catch (e: ConnectException) {
                // Connect-phase only retry: nothing reached the server.
                if (connectAttempts < 2) continue
                throw McpError(McpError.CONNECTION_ERROR, "connect failed: ${e.message}")
            } catch (e: java.net.SocketTimeoutException) {
                val msg = e.message ?: ""
                if (msg.contains("connect", true) && connectAttempts < 2) continue
                throw McpError(McpError.TIMEOUT, "timed out: $msg")
            } catch (e: IOException) {
                throw McpError(McpError.CONNECTION_ERROR, e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
