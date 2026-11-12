package com.openminis.app.provider

import com.openminis.app.BuildConfig
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel

/**
 * [T-llm-middleware] Ordered request middleware for the LLM pipeline.
 *
 * A middleware inspects — and, through [LlmRequestContext.headers], may
 * rewrite — one outbound LLM request BEFORE it reaches the provider's
 * Clamped entry. The chain is TRANSPORT-scoped: it never sees or alters
 * message content, so the [#28] OutboundMasker call in
 * [LLMProvider.sendMessage]/[LLMProvider.streamMessage] stays OUTSIDE the
 * chain and keeps masking the payload exactly as before.
 *
 * Contract: implementations MUST be side-effect-free on anything but the
 * context they return, and MUST NOT throw — a throwing middleware is a
 * self-inflicted request failure (wrap internals in runCatching if they can
 * fail). The assembly point ([LLMProvider]) passes the final context's
 * headers down as the per-call `extraHeaders` map, preserving the historical
 * merge semantics (provider defaults first, these LAST — replace per name).
 */
interface LlmMiddleware {
    fun onRequest(context: LlmRequestContext): LlmRequestContext
}

/**
 * [T-llm-middleware] Everything one outbound LLM request exposes to the
 * middleware chain.
 *
 * - [providerType] / [baseUrl] / [modelId] — read-only identity metadata,
 *   bound at construction by [ProviderFactory] via [SamplingIdentity] and
 *   null for directly-constructed providers (tests, one-shot lanes).
 *   `baseUrl` is the custom relay base ONLY — null on the official direct
 *   path.
 * - [body] — immutable snapshot of the request shape (counts and scalars
 *   only; never the message TEXT, so middlewares cannot accidentally leak
 *   user content into logs).
 * - [requestHeaders] — immutable snapshot of the caller-supplied per-call
 *   extra headers.
 * - [headers] — THE transform surface: the effective header set the chain
 *   hands to the provider entry. Seeded from [requestHeaders] (that seeding
 *   IS the absorbed per-call extraHeaders behavior); later middlewares add /
 *   override / remove entries. Same-name semantics match the historical
 *   per-call map: LAST write wins, applied after the provider's defaults.
 */
class LlmRequestContext(
    val providerType: String?,
    val baseUrl: String?,
    val modelId: String,
    val body: Body,
    requestHeaders: Map<String, String>,
) {
    /**
     * Immutable shape snapshot. Carries sizes, never text — a logging
     * middleware can report request scale without any chance of writing
     * user message content into a log line.
     */
    data class Body(
        val messageCount: Int,
        val systemPromptLength: Int,
        val maxTokens: Int,
        val temperature: Double?,
        val toolCount: Int,
        val thinkingLevel: String,
    )

    /** Snapshot of the caller-supplied per-call headers (defensively copied). */
    val requestHeaders: Map<String, String> = requestHeaders.toMap()

    /**
     * Effective headers — middlewares transform THIS map (or return a new
     * context with a different one). Insertion-ordered so header order is
     * deterministic for debugging.
     */
    val headers: MutableMap<String, String> = LinkedHashMap(requestHeaders)

    /** Derived context with [headers] replaced — how middlewares hand back a modified request. */
    fun withHeaders(newHeaders: Map<String, String>): LlmRequestContext =
        LlmRequestContext(providerType, baseUrl, modelId, body, requestHeaders).also {
            it.headers.clear()
            it.headers.putAll(newHeaders)
        }

    companion object {
        /**
         * [T-llm-middleware] Build the chain entry context from a public
         * [LLMProvider] call. Pure JVM — no Android APIs — so the middleware
         * tests exercise exactly what production runs.
         */
        internal fun of(
            provider: LLMProvider,
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
            extraHeaders: Map<String, String>,
        ): LlmRequestContext = LlmRequestContext(
            providerType = SamplingIdentity.providerTypeOf(provider),
            baseUrl = SamplingIdentity.baseURLOf(provider),
            modelId = provider.model.id,
            body = Body(
                messageCount = messages.size,
                systemPromptLength = systemPrompt?.length ?: 0,
                maxTokens = maxTokens,
                temperature = temperature,
                toolCount = tools.size,
                thinkingLevel = thinkingLevel.name,
            ),
            requestHeaders = extraHeaders,
        )
    }
}

/**
 * [T-llm-middleware] Sequential chain executor. An EMPTY list passes the
 * context through untouched (passthrough is itself a tested contract).
 */
object LlmMiddlewareChain {
    fun run(
        context: LlmRequestContext,
        middlewares: List<LlmMiddleware>,
    ): LlmRequestContext {
        var ctx = context
        for (middleware in middlewares) {
            ctx = middleware.onRequest(ctx)
        }
        return ctx
    }
}

/**
 * [T-llm-middleware] The production chain, in execution order.
 *
 * [ExtraHeadersMiddleware] is always first — it IS the absorbed per-call
 * extraHeaders step. [DebugLoggingMiddleware] joins only in debug builds;
 * release runs the single-link chain, whose net effect on [headers] is
 * identical to the pre-middleware behavior (the map starts seeded with the
 * caller's headers and nothing modifies it).
 *
 * `defaultChain(debug)` is the pure decision core (unit tested); the live
 * [middlewares] just pins it to [BuildConfig.DEBUG].
 */
object LlmMiddlewareRegistry {
    fun defaultChain(debug: Boolean): List<LlmMiddleware> =
        if (debug) {
            listOf(ExtraHeadersMiddleware, DebugLoggingMiddleware)
        } else {
            listOf(ExtraHeadersMiddleware)
        }

    val middlewares: List<LlmMiddleware> by lazy { defaultChain(BuildConfig.DEBUG) }
}

/**
 * [T-llm-middleware] First link of the production chain: absorbs the
 * historical per-call `extraHeaders` plumbing. The context is ALREADY
 * seeded from the caller's map ([LlmRequestContext.headers]), so this
 * middleware's job is formal — it re-applies the snapshot so an upstream
 * middleware that cleared [LlmRequestContext.headers] cannot silently drop
 * the caller's explicit headers. Per-call headers therefore always win over
 * anything a prior middleware put in the map, matching the old semantics
 * where the caller's map reached the wire verbatim.
 */
object ExtraHeadersMiddleware : LlmMiddleware {
    override fun onRequest(context: LlmRequestContext): LlmRequestContext {
        for ((key, value) in context.requestHeaders) {
            context.headers[key] = value
        }
        return context
    }
}

/**
 * [T-llm-middleware] Debug-only request trace. Registered ONLY in debug
 * builds ([LlmMiddlewareRegistry.defaultChain]) — in release this object is
 * never invoked, costing nothing. Emits one line per request with the
 * identity metadata and header NAMES only (values redacted — extra headers
 * routinely carry credentials) plus the body snapshot scalars.
 */
object DebugLoggingMiddleware : LlmMiddleware {
    private const val TAG = "LlmMiddleware"

    /** Pure decision core: header names kept for logging, values replaced. */
    internal fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.keys.associateWith { "***" }

    override fun onRequest(context: LlmRequestContext): LlmRequestContext {
        android.util.Log.d(
            TAG,
            "request provider=${context.providerType ?: "unbound"} " +
                "base=${context.baseUrl ?: "default"} model=${context.modelId} " +
                "body=${context.body} " +
                "extraHeaders=${redactHeaders(context.headers)}",
        )
        return context
    }
}
