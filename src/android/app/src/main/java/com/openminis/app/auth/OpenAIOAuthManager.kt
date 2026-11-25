package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.ProxySelector
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thrown by [OpenAIOAuthManager] when DNS lookup or socket connect to
 * the OpenAI auth endpoint fails. The UI catches this specifically to
 * render the localized "check network / proxy" prompt instead of the
 * raw OkHttp message.
 */
class OAuthNetworkUnreachableException(cause: Throwable) : Exception(cause.message, cause)

class OpenAIOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {
    companion object {
        private const val TAG = "CodexOAuth"

        /**
         * [T-android-codex-account-id-claim] The ChatGPT account id and plan in
         * an OpenAI id_token. They live in the namespaced claim object
         * `"https://api.openai.com/auth"` (where the Codex CLI and iOS's token
         * import read them). This used to look only at the top level of the
         * payload, found nothing, and the Chatgpt-Account-Id header was never
         * sent. The top level is still accepted as a fallback.
         * Null when the token has no payload segment; throws on a payload that
         * is not base64url JSON.
         */
        internal fun claimsFromIdToken(token: String): IdTokenClaims? {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = JSONObject(String(java.util.Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
            val auth = payload.optJSONObject("https://api.openai.com/auth")
            fun claim(name: String): String? =
                auth?.optString(name)?.takeIf { it.isNotEmpty() } ?: payload.optString(name).takeIf { it.isNotEmpty() }
            return IdTokenClaims(claim("chatgpt_account_id"), claim("chatgpt_plan_type"))
        }

        /**
         * [T-codex-account-id-refresh] The account claims of a token bundle:
         * from its id_token, else (GH #447) from the access_token when that is
         * a JWT carrying the same claims. Each claim is taken from the first
         * token that has it; [IdTokenClaims.source] names where the account id
         * came from. Null when neither token gives an account id or a plan.
         */
        internal fun claimsFromTokens(json: JSONObject): IdTokenClaims? {
            fun of(field: String): IdTokenClaims? =
                json.optString(field, "").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { claimsFromIdToken(it) }.getOrNull() }
            val id = of("id_token")
            val access = of("access_token")
            val accountId = id?.accountId ?: access?.accountId
            val planType = id?.planType ?: access?.planType
            if (accountId == null && planType == null) return null
            val source = when {
                id?.accountId != null -> "id_token"
                access?.accountId != null -> "access_token"
                else -> null
            }
            return IdTokenClaims(accountId, planType, source)
        }

        /**
         * Static helper: perform full login flow and save the access token.
         * Returns the access token. Mirrors ClaudeOAuthManager.login().
         */
        suspend fun login(context: Context, instanceId: String, providerRepository: com.openminis.app.data.repository.ProviderRepository): String {
            val manager = OpenAIOAuthManager(context, instanceId)
            val token = manager.performLogin(context)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    private var loginCallbackServer: OAuthCallbackServer? = null
    private var expectedState: String? = null

    /**
     * Perform the full OAuth login flow.
     * Returns the access token. Throws on failure.
     */
    suspend fun performLogin(context: Context): String {
        AppLogger.info(TAG, "=== OAuth login started (instance=$instanceId) ===")

        loginCallbackServer?.stop()
        loginCallbackServer = null

        val authUrl = buildAuthorizationUrl()
        // [T-android-oauth-log-redact] Mask code_challenge and state (length +
        // prefix still lets a diagnostician match state against the callback);
        // scope, redirect_uri and client_id stay readable.
        val redactedAuthUrl = OAuthLogRedaction.url(authUrl)
        AppLogger.info(TAG, "authorize URL: $redactedAuthUrl")
        AppLogger.info(TAG, "redirect_uri=$redirectUri callbackPort=$callbackPort")

        val accessToken = withContext(Dispatchers.IO) {
            // Step 1: Wait for callback code
            val (code, state) = suspendCancellableCoroutine<Pair<String, String?>> { cont ->
                val server = OAuthCallbackServer(
                    callbackPort,
                    // [T-oauth-callback-double-resume] Denied / failed on the
                    // provider's page: end the sign-in now with its reason.
                    onError = { err -> if (cont.isActive) cont.resumeWithException(err.toException(context)) },
                ) { receivedCode, receivedState ->
                    if (cont.isActive) {
                        cont.resume(receivedCode to receivedState)
                    }
                }
                loginCallbackServer = server
                server.start()
                AppLogger.info(TAG, "callback server listening on port $callbackPort path=$redirectPath")

                cont.invokeOnCancellation {
                    server.stop()
                    loginCallbackServer = null
                }

                // Open auth URL in Chrome Custom Tab. Do NOT set FLAG_ACTIVITY_NEW_TASK:
                // MainActivity is singleTask, and launching Custom Tab in a new task causes
                // the IME to be dismissed whenever the authorization page focuses an input.
                OAuthTabLauncher.launch(context, Uri.parse(authUrl))
                AppLogger.info(TAG, "Custom Tab launched")
            }

            loginCallbackServer?.stop()
            loginCallbackServer = null
            val stateMatches = expectedState != null && state == expectedState
            // [T-android-oauth-log-redact] state values redacted.
            AppLogger.info(
                TAG,
                "callback received: codeLen=${code.length} state=${OAuthLogRedaction.secret(state)} expected=${OAuthLogRedaction.secret(expectedState)} match=$stateMatches",
            )
            if (!stateMatches) {
                AppLogger.warning(TAG, "state mismatch — proceeding anyway to mirror prior behaviour")
            }

            // Step 2: Exchange code for tokens (JSON body, matching iOS CodexOAuthManager)
            // [T-android-oauth-foreground-exchange] The callback lands while the
            // user is still in the Custom Tab, i.e. with Minis Ultra in the background,
            // where the OS blocks this app's network (DNS answers "no address").
            // Exchange only once the user is back.
            OAuthForegroundGate.awaitForeground(TAG)
            exchangeCodeJson(code)
        }

        AppLogger.info(TAG, "=== OAuth login complete (instance=$instanceId tokenLen=${accessToken.length}) ===")
        return accessToken
    }

    override val authURL = "https://auth.openai.com/oauth/authorize"
    override val tokenURL = "https://auth.openai.com/oauth/token"
    override val clientId = "app_EMoamEEZ73f0CkXaXp7hrann"
    override val clientSecret: String? = null
    override val callbackPort = 1455
    override val redirectPath = "/auth/callback"
    override val scopes = "openid profile email offline_access"

    override fun buildAuthorizationUrl(): String {
        val (verifier, challenge) = generatePKCE()
        saveOAuthString("verifier", verifier)
        val state = generateState()
        expectedState = state
        AppLogger.debug(TAG, "PKCE: verifierLen=${verifier.length} stateLen=${state.length}")
        return "$authURL?" + listOf(
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "response_type=code",
            "scope=${Uri.encode(scopes)}",
            "state=$state",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
            "codex_cli_simplified_flow=true",
            "originator=codex_cli_rs",
            "id_token_add_organizations=true",
        ).joinToString("&")
    }

    // [T-android-codex-account-id-claim] Logins made before the claim was read
    // from the right place stored nothing; for those the value is taken from
    // the tokens kept with them, so no new sign-in is needed.
    // [T-codex-account-id-refresh] ...and written back (GH #447), so it no
    // longer depends on a later refresh keeping the id_token.
    var accountId: String?
        get() = loadOAuthString("account_id") ?: backfill()?.accountId
        private set(value) { value?.let { saveOAuthString("account_id", it) } }

    var planType: String?
        get() = loadOAuthString("plan_type") ?: backfill()?.planType
        private set(value) { value?.let { saveOAuthString("plan_type", it) } }

    private fun backfill(): IdTokenClaims? {
        val claims = claimsFromTokens(loadStoredTokens() ?: return null) ?: return null
        var wrote = false
        if (claims.accountId != null && loadOAuthString("account_id") == null) { accountId = claims.accountId; wrote = true }
        if (claims.planType != null && loadOAuthString("plan_type") == null) { planType = claims.planType; wrote = true }
        if (wrote) AppLogger.info(TAG, "account claims backfilled (instance=$instanceId hasAccountId=${claims.accountId != null} source=${claims.source})")
        return claims
    }

    override suspend fun onTokensReceived(json: JSONObject) = recordAccountClaims(json)

    override suspend fun onTokensRefreshed(json: JSONObject) = recordAccountClaims(json)

    /**
     * Store the account id and plan of a token bundle just received (sign-in
     * or refresh). A bundle without them leaves what was stored.
     */
    internal fun recordAccountClaims(json: JSONObject) {
        val claims = claimsFromTokens(json)
        claims?.accountId?.let { accountId = it }
        claims?.planType?.let { planType = it }
        AppLogger.info(TAG, "account claims (instance=$instanceId hasAccountId=${claims?.accountId != null} source=${claims?.source})")
    }

    /**
     * Exchange authorization code for tokens using JSON body.
     * OpenAI requires application/json (same as Anthropic).
     *
     * [T-android-codex-oauth-dns] Uses a dedicated OkHttp client wired to
     * the system [ProxySelector] (the default selector reads
     * `Settings.Global.HTTP_PROXY` + ConnectivityManager's link proxy +
     * any active VPN routing). Pre-fix the shared OAuthManager.httpClient
     * was built with .build() defaults, which silently bypass VPN-style
     * SOCKS / fake-IP DNS proxies common in CN (Clash etc.) — users could
     * complete the browser-side login (Custom Tab uses system network
     * stack) but the in-app token POST resolved auth.openai.com via the
     * raw carrier resolver and got UnknownHostException.
     */
    private fun exchangeCodeJson(code: String): String {
        val jsonBody = JSONObject().apply {
            put("grant_type", "authorization_code")
            put("client_id", clientId)
            put("code", code)
            put("redirect_uri", redirectUri)
            put("code_verifier", loadOAuthString("verifier") ?: "")
        }

        AppLogger.info(TAG, "token exchange POST $tokenURL bodyLen=${jsonBody.toString().length}")
        logNetworkEnvironment(tokenURL)

        val request = okhttp3.Request.Builder()
            .url(tokenURL)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        // Retry up to 3 times. [T-android-oauth-foreground-exchange] The
        // failure this used to paper over was not flaky DNS but the OS
        // blocking a BACKGROUND app's network while the user was still in the
        // Custom Tab; OAuthForegroundGate now waits that out. The retry stays
        // for the brief lag between foregrounding and the block lifting.
        var response: okhttp3.Response? = null
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                response = com.openminis.app.network.NetworkHttp.client(netTag, systemAwareHttpClient).newCall(request).execute()
                lastError = null
                break
            } catch (e: Exception) {
                AppLogger.warning(
                    TAG,
                    "token exchange attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}",
                )
                lastError = e
                if (attempt < 3) Thread.sleep(1000L * attempt)
            }
        }
        if (response == null) {
            AppLogger.error(
                TAG,
                "token exchange failed after 3 attempts: ${lastError?.javaClass?.simpleName}: ${lastError?.message}",
            )
            throw lastError?.let { wrapIfNetworkError(it) } ?: Exception("Token exchange failed")
        }
        val responseCode = response.code
        val responseBody = response.body?.string() ?: ""
        response.close()
        AppLogger.info(
            TAG,
            "token exchange response: $responseCode bodyLen=${responseBody.length} body[0..500]=${OAuthLogRedaction.jsonBody(responseBody).take(500)}",
        )

        if (responseCode !in 200..299) {
            AppLogger.error(TAG, "token exchange non-2xx: $responseCode")
            throw Exception("Token exchange failed ($responseCode): $responseBody")
        }

        val json = JSONObject(responseBody)
        val accessToken = json.optString("access_token", "")
        if (accessToken.isEmpty()) {
            AppLogger.error(TAG, "no access_token field in response")
            throw Exception("No access_token in response")
        }

        // Save full token data (for refresh later)
        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        saveOAuthString("tokens", json.toString())

        // Account id and plan, from the id_token or the access_token
        recordAccountClaims(json)

        AppLogger.info(
            TAG,
            "token exchange OK accessTokenLen=${accessToken.length} expiresIn=${expiresIn}s hasRefresh=${json.has("refresh_token")}",
        )
        return accessToken
    }

    /**
     * Wrap raw network failures so the UI can render the localized
     * "check network / proxy" prompt instead of the cryptic
     * UnknownHostException text. Anything else passes through unchanged
     * so token-format errors / 4xx still surface their original message.
     */
    private fun wrapIfNetworkError(e: Throwable): Throwable =
        if (e is UnknownHostException || e is SocketTimeoutException) {
            OAuthNetworkUnreachableException(e)
        } else {
            e
        }

    /**
     * Log the resolved IP for [url]'s host plus the system-selected
     * proxy chain. Useful for diagnosing the "browser works but OkHttp
     * doesn't" gap on networks where the user is running a VPN-style
     * proxy (Clash, Shadowsocks) that intercepts via fake-IP DNS but
     * doesn't register as an HTTP_PROXY.
     */
    private fun logNetworkEnvironment(url: String) {
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        try {
            val addr = InetAddress.getByName(host)
            AppLogger.info(TAG, "dns: $host → ${addr.hostAddress}")
        } catch (e: Exception) {
            AppLogger.warning(
                TAG,
                "dns lookup FAILED for $host: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
        try {
            val proxies = ProxySelector.getDefault().select(URI(url))
            AppLogger.info(TAG, "proxy chain for $host: $proxies")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "proxy lookup failed: ${e.message}")
        }
    }

    /**
     * Per-call OkHttp client wired to the system ProxySelector +
     * Authenticator with an interceptor that records the chosen route's
     * remote address. Lazy so the proxy state is read fresh each login
     * (network may have switched between cold-start and tap-Sign-in).
     */
    private val systemAwareHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .proxySelector(ProxySelector.getDefault())
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val route = chain.connection()?.route()
                AppLogger.info(
                    TAG,
                    "okhttp connect: url=${req.url} via proxy=${route?.proxy} socket=${route?.socketAddress}",
                )
                chain.proceed(req)
            }
            .build()
    }

    internal data class IdTokenClaims(val accountId: String?, val planType: String?, val source: String? = null)
}
