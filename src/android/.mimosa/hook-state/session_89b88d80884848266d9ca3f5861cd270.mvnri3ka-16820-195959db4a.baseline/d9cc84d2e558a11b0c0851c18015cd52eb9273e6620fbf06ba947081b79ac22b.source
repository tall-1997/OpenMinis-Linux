package com.openminis.app.mcp.client

import java.net.URI

/**
 * [T-mcp-loopback-wiring] Loopback wiring between MCP client entries and the
 * built-in MCP server.
 *
 * A client entry pointing at the built-in server's configured loopback port
 * (http://127.0.0.1:8765/mcp — the documented default) is transparently
 * rewired to the port the server ACTUALLY bound and carries the built-in
 * bearer token, so:
 *  - a port conflict (another app squatting the configured port) no longer
 *    breaks the pair — the server falls back to a free port and the client
 *    follows;
 *  - the user never hand-copies the token into the entry's headers.
 *
 * Matching is deliberately narrow: the host must be 127.0.0.1 or localhost,
 * AND the URL port must equal the built-in server's CONFIGURED port.
 * Anything else (a foreign local server on another port, a LAN host, a URL
 * without an explicit port) is left untouched. When the URL matches the
 * configured port but the server is not running, the outcome is
 * [Outcome.ServerNotRunning] so the caller can fail fast with an actionable
 * error instead of timing out against whatever squats the port.
 *
 * Pure Kotlin, no Android dependencies — JVM-testable in isolation.
 */
object BuiltinLoopbackResolver {

    sealed interface Outcome {
        /** The entry targets the built-in server; connect to [effectiveUrl] and attach the built-in token. */
        data class Wired(val effectiveUrl: String) : Outcome

        /** The entry targets the built-in server's port but the server is not running. */
        object ServerNotRunning : Outcome

        /** The entry does not target the built-in server. */
        object NotBuiltin : Outcome
    }

    private fun isLoopbackHost(host: String?): Boolean =
        host == "127.0.0.1" || host == "localhost"

    /**
     * Resolve [url] against the built-in server's state. [actualPort] is the
     * port the server bound (equals [configuredPort] unless it fell back);
     * null means no rewrite. [running] gates both the rewrite and the token.
     */
    fun resolve(url: String, configuredPort: Int, actualPort: Int?, running: Boolean): Outcome {
        val u = runCatching { URI(url) }.getOrNull() ?: return Outcome.NotBuiltin
        if (u.scheme?.lowercase() != "http") return Outcome.NotBuiltin
        if (!isLoopbackHost(u.host)) return Outcome.NotBuiltin
        val port = u.port
        if (port == -1 || port != configuredPort) return Outcome.NotBuiltin
        if (!running) return Outcome.ServerNotRunning
        if (actualPort == null || actualPort == port) return Outcome.Wired(url)
        val rewritten = runCatching {
            URI(u.scheme, u.userInfo, u.host, actualPort, u.path, u.query, u.fragment).toString()
        }.getOrDefault(url)
        return Outcome.Wired(rewritten)
    }
}
