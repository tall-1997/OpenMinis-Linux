// [port-upstream-1.16] Ported verbatim from OpenMinis/OpenMinis upstream 1a71df0f (auth/ClaudeCliMimicryHeaders.kt) as a quota dependency: QuotaStore.extraHeaders sends the Claude CLI fingerprint on the /api/oauth/usage query.
package com.openminis.app.auth

import okhttp3.Request

/**
 * The Claude CLI client fingerprint, applied to Anthropic OAuth *auth* requests
 * (token exchange and refresh).
 *
 * [OpenMinis#360] Claude OAuth login and silent refresh were being served a
 * Cloudflare interstitial ("Just a moment...", HTTP 403 with an HTML body)
 * instead of JSON. Two causes, both on the auth path only:
 *
 *  1. The token endpoint had moved. `console.anthropic.com/v1/oauth/token` is
 *     the old host; the CLI now posts to `claude.ai/v1/oauth/token`.
 *  2. These requests carried no client fingerprint at all. The chat path in
 *     `AnthropicProvider` has sent the headers below since OAuth landed, so
 *     chat traffic looked like the CLI while the token requests did not —
 *     which is what Cloudflare's bot management scored as a non-official
 *     client. The failure was invisible in review because each path built its
 *     own request and nothing tied them together.
 *
 * Deliberately NOT included here: `anthropic-beta`. That header advertises
 * message-API capabilities (prompt caching, interleaved thinking, …) and is
 * meaningless on a token request; the real CLI does not send it there either,
 * so adding it would widen the gap between what we advertise and what we send
 * rather than close it.
 *
 * **One definition for both paths.** `AnthropicProvider.buildRequest`'s
 * `isOAuth` block applies this same object via
 * [applyClaudeCliMimicryHeaders]; a fingerprint that matches on one path but
 * not the other is the exact shape of this bug, so there is no second copy to
 * drift.
 *
 * Mirrors the iOS set in `OAuthHTTPClient`'s interceptor.
 */
object ClaudeCliMimicryHeaders {

    /**
     * [GH#423][GH#301] The single source of the CLI identity's version.
     *
     * The backend gates new models on this number and the floor only rises.
     * It used to be written twice — 2.1.280 here and 2.1.195 in
     * `AnthropicProvider.buildRequest` — so the OAuth auth path and the chat
     * path advertised different clients, and moving the floor meant finding
     * both literals. [ClaudeCliVersion] resolves it from a build pin, the npm
     * dist-tag cache (24 h), or its fallback.
     */
    val userAgent: String
        get() = "claude-cli/${ClaudeCliVersion.current} (external, cli)"

    /**
     * Header name → value, in the order the chat path sends them.
     *
     * The `X-Stainless-*` values describe the TypeScript SDK and Node runtime
     * rather than the CLI, and move on their own cadence. Bumping either
     * speculatively is how a working fingerprint gets broken.
     */
    val ALL: List<Pair<String, String>>
        get() = listOf(
            "User-Agent" to userAgent,
            "X-Stainless-Lang" to "js",
            "X-Stainless-Package-Version" to "0.106.0",
            "X-Stainless-OS" to "Linux",
            "X-Stainless-Arch" to "arm64",
            "X-Stainless-Runtime" to "node",
            "X-Stainless-Runtime-Version" to "v24.18.0",
            "X-Stainless-Retry-Count" to "0",
            "X-Stainless-Timeout" to "600",
            "X-App" to "cli",
            "Anthropic-Dangerous-Direct-Browser-Access" to "true",
        )
}

/**
 * Applies the full Claude CLI fingerprint to an OAuth token request.
 *
 * Every Anthropic auth request must go through this — see
 * [ClaudeCliMimicryHeaders] for why a partial or absent fingerprint gets the
 * request challenged by Cloudflare instead of answered.
 */
fun Request.Builder.applyClaudeCliMimicryHeaders(): Request.Builder {
    for ((name, value) in ClaudeCliMimicryHeaders.ALL) {
        header(name, value)
    }
    return this
}
