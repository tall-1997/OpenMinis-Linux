// ios-source: src/ios/Agent/Session/MCPOAuthDiscovery.swift @ 678fee08a (MCPOAuthDiscovery)
package com.openminis.app.mcp.oauth

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [T-mcp-oauth-signin-android] Discovery + Dynamic Client Registration for a
 * remote MCP server - what lets a user connect WITHOUT pasting a Client ID.
 * Port of iOS `MCPOAuthDiscovery` (issue #315 / #319, #432).
 *
 * Chain (MCP authorization spec, 2025-06-18):
 *   1. unauthenticated request -> 401 + `WWW-Authenticate: Bearer
 *      resource_metadata="..."`                                 (RFC 9728 §5.1)
 *   2. GET that URL -> `authorization_servers[]`                (RFC 9728)
 *   3. GET `<AS>/.well-known/oauth-authorization-server`        (RFC 8414)
 *   4. `registration_endpoint` advertised -> POST it            (RFC 7591)
 *      else -> [McpDiscoveryException.Kind.DCR_UNSUPPORTED], and the form
 *      falls back to the manual fields.
 *
 * No token work here: the output is an [MCPOAuthConfig] the existing
 * authorize flow takes.
 */
class MCPOAuthDiscovery(
    private val secrets: McpOAuthSecrets,
    private val cache: McpDcrCache,
    private val http: OkHttpClient = defaultClient,
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {

    /** What a discovery attempt produced. */
    data class Outcome(
        /** Ready for [MCPOAuthController.authorize]. */
        val config: MCPOAuthConfig,
        /** Authorization-server issuer: the cache key, and the RFC 9207 check. */
        val issuer: String,
        /** True when the client_id came from the cache, not a fresh POST. */
        val reusedCachedClient: Boolean,
        /** The registration's `client_secret`, when the server issued one (a
         *  public client rarely gets one). The sign-in copies it to the
         *  server's own secret, where the token requests look. */
        val clientSecret: String?,
    )

    // -- Registered-client cache --

    fun cachedClient(issuer: String, defaultRedirectUri: String): MCPOAuthSignInLogic.ClientRecord? =
        MCPOAuthSignInLogic.record(issuer, cache.v2(), cache.v1(), defaultRedirectUri)

    private fun cacheClient(record: MCPOAuthSignInLogic.ClientRecord, issuer: String) {
        val map = cache.v2() ?: JSONObject()
        map.put(issuer, record.toJson())
        cache.setV2(map)
    }

    /** Drop a cached registration - when an authorization server rejects a
     *  `client_id` it once issued, so the next attempt registers again instead
     *  of failing forever on a dead id. Both cache generations go, or the v1
     *  entry would bring the dead id back. */
    fun forgetClientId(issuer: String) {
        cache.v2()?.takeIf { it.has(issuer) }?.let { it.remove(issuer); cache.setV2(it) }
        cache.v1()?.takeIf { it.has(issuer) }?.let { it.remove(issuer); cache.setV1(it) }
        secrets.setRegistrationSecret(issuer, null)
        AppLogger.info(TAG, "[DCR] dropped cached client for issuer=$issuer")
    }

    /** "Reset Sign-In" for a server entry: forget the registration its
     *  `client_id` came from. Returns how many were forgotten. */
    fun forgetClient(clientId: String): Int {
        val issuers = MCPOAuthSignInLogic.issuers(clientId, cache.v2(), cache.v1())
        issuers.forEach { forgetClientId(it) }
        return issuers.size
    }

    // -- Public entry --

    /**
     * Run the whole chain for [serverUrl] and return a config with a
     * `client_id` filled in. Throws [McpDiscoveryException] (kind
     * DCR_UNSUPPORTED when the service has no automatic registration - the
     * caller shows the manual form). [forceRegister] skips the cached
     * registration: the sign-in passes it after the server rejected it.
     */
    suspend fun discover(serverUrl: String, redirectUri: String, forceRegister: Boolean = false): Outcome =
        withContext(Dispatchers.IO) {
            val mcpUrl = serverUrl.trim().toHttpUrlOrNull()
                ?: throw McpDiscoveryException(McpDiscoveryException.Kind.BAD_SERVER_URL)

            // 1. Unauthenticated probe. A 401 is the EXPECTED answer here.
            AppLogger.info(TAG, "[DCR] probing $mcpUrl")
            val resourceMetaUrl = probeForResourceMetadata(mcpUrl.toString())

            // 2. RFC 9728 -> authorization_servers[]
            val resourceMeta = getJson(resourceMetaUrl, "protected-resource")
            val servers = resourceMeta.optJSONArray("authorization_servers")
            val asBase = servers?.optString(0, "").orEmpty()
            if (asBase.isEmpty()) throw McpDiscoveryException(McpDiscoveryException.Kind.NO_AUTHORIZATION_SERVER)
            // Several are allowed; the first is the spec's own ordering hint.
            if ((servers?.length() ?: 0) > 1) {
                AppLogger.info(TAG, "[DCR] ${servers?.length()} authorization servers offered - using the first: $asBase")
            }

            // 3. RFC 8414, with the OIDC path as a documented fallback.
            val meta = fetchAsMetadata(asBase)
            val issuer = meta.optString("issuer", "").ifEmpty { asBase }
            val authEndpoint = meta.optString("authorization_endpoint", "")
            val tokenEndpoint = meta.optString("token_endpoint", "")
            if (authEndpoint.isEmpty() || tokenEndpoint.isEmpty()) {
                throw McpDiscoveryException(McpDiscoveryException.Kind.METADATA_INCOMPLETE)
            }
            // PKCE S256 is mandatory under OAuth 2.1: a server that lists its
            // methods without S256 cannot complete this flow, so say so now.
            meta.optJSONArray("code_challenge_methods_supported")?.let { methods ->
                if ((0 until methods.length()).none { methods.optString(it) == "S256" }) {
                    throw McpDiscoveryException(McpDiscoveryException.Kind.NO_PKCE)
                }
            }

            // 4. DCR, or the documented fallback.
            val registration = meta.optString("registration_endpoint", "")
            if (registration.isEmpty()) {
                AppLogger.info(TAG, "[DCR] issuer=$issuer advertises no registration_endpoint - manual fallback")
                throw McpDiscoveryException(McpDiscoveryException.Kind.DCR_UNSUPPORTED, issuer)
            }

            // A registration is reused only while it can still work: same
            // redirect URI, secret not expired, and not just rejected.
            val cached = cachedClient(issuer, redirectUri)
            val decision = if (forceRegister) {
                MCPOAuthSignInLogic.Reuse.RegisterAgain("the authorization server rejected the cached client")
            } else {
                MCPOAuthSignInLogic.reuse(cached, redirectUri, now())
            }
            val clientId: String
            val clientSecret: String?
            val reused: Boolean
            when (decision) {
                is MCPOAuthSignInLogic.Reuse.Cached -> {
                    AppLogger.info(TAG, "[DCR] reusing cached client for issuer=$issuer")
                    clientId = cached?.clientId.orEmpty()
                    clientSecret = secrets.registrationSecret(issuer)
                    reused = true
                    // A registration an older build made (v1: the client_id
                    // alone) is written in the new form the first time it is
                    // used again.
                    if (cached != null && MCPOAuthSignInLogic.ClientRecord.fromJson(cache.v2()?.opt(issuer)) == null) {
                        cacheClient(cached, issuer)
                        AppLogger.info(TAG, "[DCR] carried the v1 registration for issuer=$issuer over to v2")
                    }
                }
                is MCPOAuthSignInLogic.Reuse.RegisterAgain -> {
                    if (cached != null) {
                        AppLogger.info(TAG, "[DCR] not reusing the cached client for issuer=$issuer: ${decision.reason}")
                    }
                    val made = register(registration, redirectUri)
                    clientId = made.clientId
                    clientSecret = made.clientSecret
                    reused = false
                    cacheClient(
                        MCPOAuthSignInLogic.ClientRecord(made.clientId, redirectUri, now(), made.secretExpiresAt),
                        issuer,
                    )
                    secrets.setRegistrationSecret(issuer, made.clientSecret)
                    AppLogger.info(TAG, "[DCR] registered new client for issuer=$issuer (secret=${made.clientSecret != null})")
                }
            }

            val scopes = meta.optJSONArray("scopes_supported")
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } } }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(" ")
            Outcome(
                config = MCPOAuthConfig(
                    mode = MCPOAuthConfig.MODE_DYNAMIC,
                    clientId = clientId,
                    authorizationEndpoint = authEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    scopes = scopes,
                    redirectUri = redirectUri,
                ),
                issuer = issuer,
                reusedCachedClient = reused,
                clientSecret = clientSecret,
            )
        }

    // -- Steps --

    /** Send an unauthenticated request and read the challenge. Falls back to
     *  the well-known path when a server 401s without the header - some omit
     *  it although RFC 9728 §5.1 says MUST. */
    private fun probeForResourceMetadata(mcpUrl: String): String {
        val req = Request.Builder()
            .url(mcpUrl)
            .post(PROBE_BODY.toRequestBody(JSON))
            .header("Accept", "application/json, text/event-stream")
            .build()
        val (status, header) = try {
            routedHttp().newCall(req).execute().use { it.code to it.header("WWW-Authenticate").orEmpty() }
        } catch (e: Exception) {
            throw McpDiscoveryException(McpDiscoveryException.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
        }
        if (status != 401 && status != 403) throw McpDiscoveryException(McpDiscoveryException.Kind.NOT_PROTECTED)
        resourceMetadataUrl(header)?.let { return it }
        AppLogger.info(TAG, "[DCR] $status without a usable WWW-Authenticate - trying the well-known path")
        val url = mcpUrl.toHttpUrlOrNull()
            ?: throw McpDiscoveryException(McpDiscoveryException.Kind.BAD_SERVER_URL)
        return url.newBuilder()
            .encodedPath("/.well-known/oauth-protected-resource")
            .query(null).fragment(null)
            .build().toString()
    }

    /** RFC 8414 path first, OIDC discovery second. */
    private fun fetchAsMetadata(asBase: String): JSONObject {
        val base = asBase.toHttpUrlOrNull()
            ?: throw McpDiscoveryException(McpDiscoveryException.Kind.BAD_AUTHORIZATION_SERVER_URL)
        val basePath = base.encodedPath.trimEnd('/')
        for (suffix in listOf("/.well-known/oauth-authorization-server", "/.well-known/openid-configuration")) {
            val url = base.newBuilder().encodedPath(basePath + suffix).query(null).fragment(null).build()
            try {
                return getJson(url.toString(), "as-metadata")
            } catch (e: McpDiscoveryException) {
                AppLogger.info(TAG, "[DCR] $suffix did not resolve - ${e.kind} ${e.detail.orEmpty()}")
            }
        }
        throw McpDiscoveryException(McpDiscoveryException.Kind.METADATA_UNREADABLE)
    }

    private class Registration(val clientId: String, val clientSecret: String?, val secretExpiresAt: Double?)

    /**
     * RFC 7591. `token_endpoint_auth_method: "none"` marks us a PUBLIC client -
     * a phone app cannot hold a secret; PKCE protects the exchange.
     * `application_type: "native"` (MCP 2026-07-28): an OIDC server otherwise
     * assumes a web client and refuses the loopback redirect URI.
     */
    private fun register(endpoint: String, redirectUri: String): Registration {
        val url = endpoint.toHttpUrlOrNull()
            ?: throw McpDiscoveryException(McpDiscoveryException.Kind.BAD_REGISTRATION_ENDPOINT)
        val body = JSONObject().apply {
            put("redirect_uris", JSONArray().put(redirectUri))
            put("client_name", "Minis Ultra")
            put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            put("response_types", JSONArray().put("code"))
            put("token_endpoint_auth_method", "none")
            put("application_type", "native")
        }
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .build()
        val (status, text) = try {
            routedHttp().newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
        } catch (e: Exception) {
            throw McpDiscoveryException(McpDiscoveryException.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
        }
        if (status !in 200..201) {
            // The server's own error is actionable ("invalid_redirect_uri");
            // "registration failed" is not.
            val obj = runCatching { JSONObject(text) }.getOrNull()
            val detail = obj?.optString("error_description", "")?.ifEmpty { null }
                ?: obj?.optString("error", "")?.ifEmpty { null }
            throw McpDiscoveryException(McpDiscoveryException.Kind.REGISTRATION_REJECTED, detail, status)
        }
        val obj = runCatching { JSONObject(text) }.getOrNull()
        val id = obj?.optString("client_id", "").orEmpty()
        if (id.isEmpty()) throw McpDiscoveryException(McpDiscoveryException.Kind.REGISTRATION_UNREADABLE)
        return Registration(
            clientId = id,
            clientSecret = obj?.optString("client_secret", "")?.ifEmpty { null },
            secretExpiresAt = obj?.takeIf { it.has("client_secret_expires_at") }
                ?.optDouble("client_secret_expires_at")?.takeIf { !it.isNaN() },
        )
    }

    private fun getJson(url: String, label: String): JSONObject {
        val req = Request.Builder().url(url).get().header("Accept", "application/json").build()
        val (status, text) = try {
            routedHttp().newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
        } catch (e: Exception) {
            throw McpDiscoveryException(McpDiscoveryException.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
        }
        if (status !in 200..299) throw McpDiscoveryException(McpDiscoveryException.Kind.HTTP_STATUS, label, status)
        return runCatching { JSONObject(text) }.getOrNull()
            ?: throw McpDiscoveryException(McpDiscoveryException.Kind.UNREADABLE, label)
    }

    /**
     * [T-net-android-phase3] MCP sign-in requests follow the "MCP" route in
     * Settings > Network; [http] itself when no route applies.
     */
    private fun routedHttp(): OkHttpClient =
        com.openminis.app.network.NetworkHttp.client(com.openminis.app.network.NetworkScope.MCP.tag, http)

    companion object {
        private const val TAG = "MCPOAuthDCR"
        private val JSON = "application/json".toMediaType()
        private const val PROBE_BODY = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Parse `resource_metadata="..."` out of a `WWW-Authenticate` header
         * (RFC 9728 §5.1). Tolerates parameter order, extra parameters, and
         * unquoted values, which real servers vary on.
         */
        fun resourceMetadataUrl(header: String): String? {
            val at = header.indexOf("resource_metadata", ignoreCase = true)
            if (at < 0) return null
            var rest = header.substring(at + "resource_metadata".length).trimStart(' ')
            if (!rest.startsWith("=")) return null
            rest = rest.substring(1).trimStart(' ')
            val value = if (rest.startsWith("\"")) {
                val end = rest.indexOf('"', 1)
                if (end < 0) return null
                rest.substring(1, end)
            } else {
                rest.takeWhile { it != ',' && it != ' ' }
            }
            return value.takeIf { it.toHttpUrlOrNull() != null }
        }
    }
}

/**
 * A discovery that did not produce a config. [kind] picks the message the form
 * shows (iOS `MCPOAuthDiscovery.DiscoveryError`); [detail] is the server's own
 * words where it gave some, [status] the HTTP status where one applies.
 */
class McpDiscoveryException(
    val kind: Kind,
    val detail: String? = null,
    val status: Int = 0,
) : Exception("${kind.name}${detail?.let { ": $it" } ?: ""}${if (status != 0) " (HTTP $status)" else ""}") {
    enum class Kind {
        /** The documented fallback: no automatic registration (detail = issuer). */
        DCR_UNSUPPORTED,
        NOT_PROTECTED,
        BAD_SERVER_URL,
        NO_AUTHORIZATION_SERVER,
        BAD_AUTHORIZATION_SERVER_URL,
        METADATA_INCOMPLETE,
        METADATA_UNREADABLE,
        NO_PKCE,
        BAD_REGISTRATION_ENDPOINT,
        REGISTRATION_REJECTED,
        REGISTRATION_UNREADABLE,
        /** detail = what was asked for ("protected-resource" / "as-metadata"). */
        HTTP_STATUS,
        UNREADABLE,
        NETWORK,
    }
}
