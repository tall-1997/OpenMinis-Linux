package com.openminis.app.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Invisible activity that captures OAuth redirect URLs (http://localhost:54545/callback).
 *
 * When the OAuth provider (e.g. Anthropic) redirects to localhost, Android would normally
 * show a "Choose activity" dialog. This activity's intent filter in AndroidManifest catches
 * the redirect URL instead and forwards it to the OAuthCallbackServer via a local HTTP
 * request - only when the server is still waiting for it (Chrome usually requests
 * localhost itself first) - then brings MainActivity back to the front, which closes the
 * Custom Tab above it ([T-oauth-return-to-app], AppAuth's RedirectUriReceiverActivity).
 *
 * The activity finishes immediately (Theme.NoDisplay) — the user never sees it.
 */
class OAuthRedirectActivity : Activity() {

    companion object {
        private const val TAG = "OAuthRedirect"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        // [T-android-oauth-log-redact] The redirect carries code + state.
        Log.i(TAG, "OAuth redirect received: ${OAuthLogRedaction.url(uri?.toString())}")

        if (uri != null) forwardIfNeeded(uri)

        // [T-oauth-return-to-app] AppAuth's RedirectUriReceiverActivity pattern.
        // Field log (Huawei, Android 12, 18:01:41-42): Chrome requested
        // localhost:1455, the server answered 200 with the code, and Chrome
        // ALSO handed the same URL to this activity, then aborted the tab's
        // navigation - the "Select a workspace" page kept spinning. This
        // activity used to only finish(), so nothing brought Minis back:
        // the token exchange waits for the foreground (background network is
        // blocked) and the user waited on the page, until they switched back
        // by hand. Starting MainActivity (singleTask, the Custom Tab sits
        // above it in the same task) with CLEAR_TOP clears the tab and puts
        // Minis in front, so the exchange runs at once - for an error
        // redirect too, so a denial shows in Minis instead of a stuck page.
        // [port-upstream-1.16] Fixes the reported "华为设备授权后登录卡住"
        // (stuck on the browser page after granting consent on HarmonyOS).
        bringAppForward()
        finish()
    }

    /**
     * Forward the redirect to the local server only when it is still waiting
     * for it. Chrome normally delivers it to the server first; forwarding a
     * callback the server already answered only met a closed port
     * (ConnectException in every field log).
     */
    private fun forwardIfNeeded(uri: android.net.Uri) {
        val hasCode = uri.getQueryParameter("code") != null
        val hasError = uri.getQueryParameter("error") != null
        if (!hasCode && !hasError) {
            Log.w(TAG, "No 'code' or 'error' parameter in redirect URI")
            return
        }
        val port = uri.port.takeIf { it > 0 } ?: 54545
        when (OAuthCallbackServer.forwardDecision(port, uri.getQueryParameter("state"))) {
            OAuthCallbackServer.Forward.ALREADY_SERVED -> {
                Log.i(TAG, "Server on :$port already answered this callback - not forwarding")
                return
            }
            OAuthCallbackServer.Forward.NOT_LISTENING -> {
                Log.i(TAG, "No server waiting on :$port - nothing to forward")
                return
            }
            OAuthCallbackServer.Forward.FORWARD -> Unit
        }
        // Forward the callback to our local server by making a local HTTP request.
        Thread {
            try {
                val localUrl = "http://127.0.0.1:$port${uri.path}?${uri.query}"
                Log.d(TAG, "Forwarding to local server: ${OAuthLogRedaction.url(localUrl)}")
                val conn = java.net.URL(localUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                val responseCode = conn.responseCode
                Log.i(TAG, "Local server response: $responseCode")
                conn.disconnect()
            } catch (e: java.net.ConnectException) {
                // The server stopped in between (it got the browser's own
                // request first). Nothing was lost.
                Log.i(TAG, "Local server no longer listening on :$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to forward to local server: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun bringAppForward() {
        try {
            startActivity(
                Intent(this, com.openminis.app.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            Log.i(TAG, "Redirect handed to the app - bringing MainActivity forward")
        } catch (e: Exception) {
            Log.w(TAG, "Could not bring MainActivity forward: ${e.javaClass.simpleName}")
        }
    }
}
