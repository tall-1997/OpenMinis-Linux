package com.openminis.app.mcp.oauth

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.AtomicFileWrite
import java.io.File
import java.io.IOException
import org.json.JSONObject

/**
 * [T-android-mcp-token-bridge] Materialize MCP OAuth tokens for the in-guest
 * CLI (`minis-mcp-cli`), which reads
 * `/var/minis/mcp-servers/oauth/<server>.json` (guest-visible path for
 * `<filesDir>/minis-global/mcp-servers/oauth/`) and attaches
 * `Authorization: Bearer <access_token>`, refreshing via the stored grant.
 *
 * Without this bridge an OAuth server authorized in the app still fails every
 * guest-side call with AUTH_REQUIRED — the tokens never left the app. The
 * format mirrors the Python contract in transport/http.py: `expires_at` in
 * epoch SECONDS (the CLI refreshes 60s early), `resource` included when the
 * server URL canonicalizes, `client_secret` optional. Written atomically
 * (tmp+rename) because the daemon may read it between calls; 0600 since it
 * carries bearer material.
 */
object MCPTokenBridge {

    private const val TAG = "MCPTokenBridge"

    fun oauthDir(context: Context): File =
        File(context.filesDir, "minis-global/mcp-servers/oauth")

    /**
     * Write/refresh the bridge for [server]. [expiresAtMs] uses epoch millis
     * (Android side); the bridge stores epoch seconds for the CLI. Best-effort:
     * a failed write logs and returns — the next authorize/refresh retries.
     */
    fun write(
        context: Context,
        server: String,
        tokens: MCPOAuthStore.StoredTokens,
        tokenEndpoint: String,
        clientId: String,
        clientSecret: String?,
        resource: String?,
    ) {
        if (server.isBlank() || server.contains('/') || server.contains('\\')) {
            AppLogger.warning(TAG, "refusing bridge write for odd server id '$server'")
            return
        }
        val payload = JSONObject().apply {
            put("access_token", tokens.accessToken)
            var refreshToken = tokens.refreshToken
            if (refreshToken.isNullOrBlank()) {
                // [T-android-mcp-token-bridge-merge] The CLI rotates the refresh
                // token on every refresh and rewrites the bridge; this write may
                // carry tokens WITHOUT one (e.g. a token-only update). Never
                // downgrade: keep the bridge's existing refresh token.
                val existing = runCatching {
                    JSONObject(File(oauthDir(context), "$server.json").readText())
                }.getOrNull()
                existing?.optString("refresh_token", "")?.takeIf { it.isNotBlank() }
                    ?.let { refreshToken = it }
            }
            if (!refreshToken.isNullOrBlank()) put("refresh_token", refreshToken)
            if (tokens.expiresAtMs > 0) put("expires_at", tokens.expiresAtMs / 1000)
            put("token_endpoint", tokenEndpoint)
            put("client_id", clientId)
            if (!clientSecret.isNullOrBlank()) put("client_secret", clientSecret)
            if (!resource.isNullOrBlank()) put("resource", resource)
        }
        try {
            val dir = oauthDir(context)
            dir.mkdirs()
            val target = File(dir, "$server.json")
            // Unified atomic write: the same tmp+rename dance plus per-path
            // lock, short-write detection and post-write verify. The old
            // rename-failed direct-writeText fallback is covered by the
            // copy-over fallback inside AtomicFileWrite; null (both failed,
            // previous bytes kept) throws so the catch below logs it as
            // before and the permission tightening only runs on a landed
            // write.
            if (AtomicFileWrite.write(target, payload.toString(), temp = File(dir, "$server.json.tmp")) == null) {
                throw IOException("atomic write did not verify")
            }
            target.setReadable(false, false)
            target.setWritable(false, false)
            target.setReadable(true, true)
            AppLogger.info(TAG, "bridge written for '$server' (hasRefresh=${!tokens.refreshToken.isNullOrBlank()})")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "bridge write failed for '$server': ${e.message}")
        }
    }

    /** Remove the bridge (sign-out / server deletion) — best-effort. */
    fun remove(context: Context, server: String) {
        if (server.isBlank() || server.contains('/') || server.contains('\\')) return
        try {
            val f = File(oauthDir(context), "$server.json")
            if (f.exists() && !f.delete()) {
                AppLogger.warning(TAG, "bridge remove failed for '$server'")
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "bridge remove failed for '$server': ${e.message}")
        }
    }
}
