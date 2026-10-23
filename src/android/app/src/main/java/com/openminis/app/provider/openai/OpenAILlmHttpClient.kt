package com.openminis.app.provider.openai

import com.openminis.app.provider.ZenFreeLaneGate
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Builds the [OkHttpClient] that OpenAI-shaped calls go through.
 *
 * Lifted out of `OpenAIProvider` verbatim: the DoH resolver, the 30/600/30
 * second timeouts, the shared connection pool and the event listener factory
 * all keep their original comments, because each of them exists because of a
 * specific field failure and the reasoning is load-bearing.
 *
 * The single addition is [ZenFreeLaneGate], which satisfies the keyless
 * OpenCode Zen lane's request-body gate on the wire. Its scope is narrow by
 * construction — it rewrites only a POST to a Zen host that carries the
 * `Bearer public` placeholder credential — so every other provider's traffic
 * passes through byte-identical, and a user-built Zen instance configured with
 * a real API key is never rewritten either.
 */
internal object OpenAILlmHttpClient {

    // T-android-openai-codex-timeout: bump readTimeout 180s → 600s to
    // match iOS. T171 had cut it to 180s on the theory that GPT-5.x
    // thinking warm-up tops out around 60-90s, but the Codex Responses
    // OAuth path on gpt-5.5 with a real-world agent body (440KB, 20
    // messages, 8 tools) routinely sits silent on the SSE stream for
    // 2:50-3:10 between the reasoning `response.output_item.added`
    // event and the burst of text deltas after the reasoning step
    // completes — server-side it's still working, no keep-alive bytes
    // arrive in between, and OkHttp's idle-data-read counter trips.
    // The 180s cap turned that normal reasoning silence into a hard
    // SocketTimeoutException (observed in 0.10-preview, log file
    // minis-2026-05-27.log around 13:28 — 3:00 of silence then trip).
    // Going back to 600s leaves room for the longest realistic
    // reasoning bursts; the cancel-race concern T171 hedged against
    // (OkHttp call.cancel() racing a thread inside execute()) is
    // covered by the outer coroutine cancellation chain — Job.cancel
    // propagates down through the agent loop and the socket gets
    // closed via Call.cancel() from the coroutine's invokeOnCancellation,
    // so a stuck OAuth read never lingers past the agent turn.
    //
    // T-android-openai-codex-timeout: also attach an OkHttp EventListener
    // so future timeout reports show WHICH leg of the network path
    // stalled — DNS, proxy connect, TLS handshake, idle-after-headers,
    // or mid-stream silence. Previous OAuth-streaming logs only printed
    // request/response envelopes; when a SocketTimeoutException fired
    // we had no way to tell whether the upstream proxy went away
    // (idle-close after 3min, common with clash/v2ray), TLS renegotiated,
    // or the server itself stopped emitting bytes. Each milestone goes
    // through AppLogger.info at the OkHttpEvents tag with the call's
    // identity hash so concurrent streams can be disambiguated.
    /** A fresh client per call, matching the pre-extraction behaviour. */
    fun build(): OkHttpClient = OkHttpClient.Builder()
        // [T-doh-resolver-fallback] Custom/OpenRouter endpoints are arbitrary
        // hostnames, so this is the path most exposed to a resolver that
        // cannot name them. Must stay above the connection pool comment below.
        .dns(com.openminis.app.network.DohDns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // [T-android-stale-conn-retry-hang] Shared pool so NetworkMonitor's
        // network-transition eviction reaches THIS client's connections —
        // a per-client pool was never evicted, and a dead h2 tunnel through
        // a local proxy got reused on every retry (silent infinite hang).
        // The eviction is NetworkMonitor.evictSharedPoolOnReconnect(), which
        // calls evictAll() on the SHARED pool when a callback actually flips
        // the merged status to CONNECTED; the per-call watchdog in
        // OpenAIRawStream only ever closes its own socket, so this pool-level
        // pass is what clears tunnels that died with the previous interface.
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .eventListenerFactory { OkHttpNetTraceListener() }
        // Keyless Zen lane: the endpoint refuses by request-body shape, so the
        // gate has to sit on the wire where every call path (streaming,
        // subagents, vision, compaction, titles) picks it up at once.
        .addInterceptor(ZenFreeLaneGate)
        .build()
}
