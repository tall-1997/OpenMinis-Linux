package com.openminis.app.data.repository

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.anthropic.AnthropicModelsApi
import com.openminis.app.provider.gemini.GeminiModelsApi
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openrouter.OpenRouterModelsApi

/**
 * [T-codex-dynamic-discovery GH#319] The Codex discovery route answered 401/403
 * for a user-initiated Refresh.
 *
 * Deliberately an exception rather than an empty list: the caller must be able
 * to say "sign in again" instead of presenting the previous catalogue as a
 * successful refresh. Background (non-forced) refreshes never throw this — they
 * fall back, so a transient auth blip cannot clear the picker.
 *
 * Lives here rather than in [ProviderRepository] because the architecture
 * ratchet freezes that file's size: provider-model fetching is its own concern
 * and now its own file.
 */
class CodexDiscoveryAuthException(val status: Int) :
    Exception("Codex model discovery rejected the credential (HTTP $status)")

/**
 * [T-codex-dynamic-discovery GH#319] Tier 1 of the Codex OAuth chain.
 *
 * @return the live catalog, or null meaning "learned nothing — caller should
 *   fall back". Null covers offline, a non-auth HTTP error, an unparseable body
 *   and an empty result alike: in every one of those the right move is to keep
 *   showing models, not to blank the picker.
 *
 * @throws CodexDiscoveryAuthException when the backend rejected the CREDENTIAL.
 *   That one case is NOT folded into null on a user-initiated refresh: GH#319
 *   asks for it explicitly — an auth failure must be reported as an auth error
 *   rather than disguised as "refresh succeeded, list empty". Silently falling
 *   back would redisplay the old list and read as success while the account is
 *   in fact signed out.
 */
internal suspend fun discoverCodexModels(
    context: Context,
    instance: ProviderInstance,
    accessToken: String?,
    forceRefresh: Boolean,
): List<LLMModel>? {
    if (accessToken.isNullOrBlank()) return null

    // accountId comes from the id_token parsed at login, so this is a local
    // read — no extra round trip and no new auth logic.
    val accountId = try {
        com.openminis.app.auth.OpenAIOAuthManager(context, instance.id).accountId
    } catch (e: Exception) {
        android.util.Log.w("ProviderRepo", "Codex accountId unavailable: ${e.message}")
        null
    }

    val result = com.openminis.app.provider.openai.CodexModelsApi.fetchModels(
        accessToken = accessToken,
        accountId = accountId,
        // Same constant the inference request advertises — see
        // OpenAIProvider.CODEX_CLIENT_VERSION.
        clientVersion = com.openminis.app.provider.openai.OpenAIProvider.CODEX_CLIENT_VERSION,
        context = context,
        forceRefresh = forceRefresh,
    )

    return when (result) {
        is com.openminis.app.provider.openai.CodexModelsApi.Result.Success -> {
            // The image models are NOT in the discovery response — they are not
            // chat SKUs — but they are real and routable on this auth path.
            // Merge them back or a successful discovery would silently delete a
            // working model from the picker.
            val imageModels = OpenAIModelsApi.codexImageModels()
            val discoveredIds = result.models.map { it.id }.toSet()
            android.util.Log.i(
                "ProviderRepo",
                "Codex discovery: ${result.models.size} live models for ${instance.label}",
            )
            result.models + imageModels.filter { it.id !in discoveredIds }
        }
        is com.openminis.app.provider.openai.CodexModelsApi.Result.AuthFailed -> {
            if (forceRefresh) throw CodexDiscoveryAuthException(result.status)
            android.util.Log.w(
                "ProviderRepo",
                "Codex discovery auth failure (HTTP ${result.status}) on background refresh — falling back",
            )
            null
        }
        is com.openminis.app.provider.openai.CodexModelsApi.Result.Failed -> {
            android.util.Log.i(
                "ProviderRepo",
                "Codex discovery unavailable (${result.reason}) for ${instance.label}",
            )
            null
        }
    }
}

/**
 * [T-copilot-provider] Copilot's own `/models`, not the OpenAI-compatible
 * helper: the call needs the short-lived session token plus the
 * editor-identity headers, and the response is filtered to
 * `model_picker_enabled` so the picker never offers an embedding or internal
 * model that would fail on first send.
 */
internal suspend fun fetchCopilotModels(
    context: Context,
    instance: ProviderInstance,
): List<LLMModel> =
    // [T-android-copilot-model-capabilities] Carry the window/output/vision/
    // reasoning fields Copilot already reports; dropping them left every
    // Copilot model with a null context window, which silently disables
    // everything that reasons about context size.
    com.openminis.app.auth.CopilotOAuthManager(context, instance.id)
        .fetchModelsDetailed()
        .map { m ->
            LLMModel(
                id = m.id,
                displayName = m.displayName,
                provider = "GitHub Copilot",
                contextWindow = m.contextWindow,
                maxOutputTokens = m.maxOutputTokens,
                supportsReasoning = m.supportsReasoning,
                // Copilot states the tiers it accepts (low/medium/high/xhigh/
                // max); passing them through lets the request builder clamp onto
                // a real set instead of guessing.
                reasoningEffortValues = m.reasoningEffortValues,
                // Vision is a modality list here, not a flag. "No" is written as
                // text-only, not null: null means "undeclared", and the Copilot
                // provider default fills an undeclared list with vision — which
                // would send photos raw to an endpoint that cannot read them.
                inputModalities = if (m.supportsVision) {
                    listOf("text", "image")
                } else listOf("text"),
            )
        }

/**
 * One per-provider-type model fetch, extracted from
 * [ProviderRepository.refreshModels] so that (size-frozen) file does not grow
 * as providers are added.
 *
 * Callers pass the already-resolved credential and base URL; an empty result
 * means "learned nothing", which the caller turns into "keep what we had".
 */
internal suspend fun fetchProviderModelsForRefresh(
    context: Context?,
    instance: ProviderInstance,
    apiKey: String,
    baseURL: String?,
    liveForce: Boolean,
): List<LLMModel> = when (instance.providerType) {
    ProviderType.anthropic -> AnthropicModelsApi.fetchModels(
        apiKey, baseURL,
        isOAuth = instance.credentialType == ProviderCredential.oauth,
        context = context,
        forceRefresh = liveForce,
        // [T-provider-custom-user-agent] models-list UA override.
        customUserAgent = instance.customUserAgent,
        cacheScope = instance.id,
    )
    ProviderType.gemini -> GeminiModelsApi.fetchModels(
        apiKey,
        isOAuth = instance.credentialType == ProviderCredential.oauth,
        context = context,
        forceRefresh = liveForce,
        cacheScope = instance.id,
    )
    // [T-android-provider-type-parity] openAIResponses lists models from the
    // same /v1/models endpoint — only the completion endpoint differs.
    ProviderType.openAI, ProviderType.openAIResponses ->
        OpenAIModelsApi.fetchModels(
            apiKey, baseURL, context = context, forceRefresh = liveForce,
            customUserAgent = instance.customUserAgent, cacheScope = instance.id,
        )
    ProviderType.openRouter ->
        OpenRouterModelsApi.fetchModels(apiKey, context = context, forceRefresh = liveForce, cacheScope = instance.id)
    // [T-provider-dynamic-catalog-reconcile] xAI: fetch the live catalog, fall
    // back to the built-in list.
    //
    // The `?:` default is load-bearing: an xAI instance added through the OAuth
    // button carries no customBaseURL, so `effectiveBaseURL` is null — and
    // `OpenAIModelsApi.buildURL(null)` resolves to api.openai.com/v1/models.
    // Passing the null straight through would send an xAI token to OpenAI and
    // show GPT models under a Grok provider.
    ProviderType.xAI -> OpenAIModelsApi.fetchModels(
        apiKey,
        baseURL ?: "https://api.x.ai/v1",
        context = context,
        forceRefresh = liveForce,
        customUserAgent = instance.customUserAgent,
        cacheScope = instance.id,
    ).ifEmpty { com.openminis.app.provider.xai.XAIModelsApi.fetchModelsOAuth() }
    // [T-kimi-oauth] Unlike Codex OAuth, the Kimi OAuth token CAN call the
    // models endpoint — real fetch from GET /coding/v1/models.
    ProviderType.kimiCode -> OpenAIModelsApi.fetchModels(
        apiKey,
        baseURL ?: "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1",
        context = context,
        forceRefresh = liveForce,
        customUserAgent = instance.customUserAgent,
        cacheScope = instance.id,
    )
    ProviderType.githubCopilot ->
        if (context == null) emptyList() else fetchCopilotModels(context, instance)
    // [T-android-provider-type-parity] No models endpoint to query for a type
    // this build cannot drive; the instance keeps whatever entries the restore
    // brought with it.
    ProviderType.antigravity, ProviderType.unsupported -> emptyList()
}
