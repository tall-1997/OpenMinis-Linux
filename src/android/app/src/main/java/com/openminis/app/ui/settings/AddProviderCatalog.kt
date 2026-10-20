package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderType

/**
 * Per-provider-type presentation and capability tables for the Add Provider
 * flow.
 *
 * Extracted from `AddProviderScreen.kt`: the architecture ratchet freezes that
 * file at its current size and refuses growth, and these tables are pure
 * data/formatting with no Compose state — the natural piece to move.
 */

/** Display order matching iOS. */
internal val providerDisplayOrder = listOf(
    ProviderType.openAI,
    ProviderType.anthropic,
    ProviderType.gemini,
    ProviderType.xAI,
    ProviderType.kimiCode,
    // [T-copilot-provider] Plain row, exactly like the other OAuth providers:
    // consent is taken by the disclaimer dialog that stands between the user
    // and the first network call, not by hiding the row.
    ProviderType.githubCopilot,
    ProviderType.openRouter,
)

/** Icon and color per provider type, matching iOS SF Symbols. */
internal fun providerIcon(type: ProviderType): Pair<ImageVector, Color> = when (type) {
    ProviderType.openAI -> Icons.Default.Hub to Color(0xFF4CAF50)           // green
    ProviderType.anthropic -> Icons.Default.AutoAwesome to Color(0xFFAB47BC) // purple
    ProviderType.gemini -> Icons.Default.Diamond to Color(0xFF42A5F5)        // blue
    ProviderType.openRouter -> Icons.Default.AltRoute to Color(0xFF00BCD4)    // cyan
    ProviderType.xAI -> Icons.Default.FlashOn to Color(0xFFFF7043)           // orange — Grok visual cue
    // [T-kimi-oauth] Indigo — matches iOS's Kimi accent.
    ProviderType.kimiCode -> Icons.Default.Terminal to Color(0xFF5C6BC0)
    // [T-copilot-provider] Purple — GitHub accent, matching iOS.
    ProviderType.githubCopilot -> Icons.Default.Terminal to Color(0xFF6E5494)
    // [T-android-provider-type-parity] Types that arrive only from an iOS
    // package / newer build; never offered for creation, but the icon helper is
    // also used to render an already-restored instance.
    ProviderType.openAIResponses -> Icons.Default.Hub to Color(0xFF4CAF50)
    ProviderType.antigravity,
    ProviderType.unsupported -> Icons.Default.Cloud to Color(0xFF9E9E9E)
}

/** Returns available credential types per provider. */
internal fun availableCredentials(type: ProviderType): List<ProviderCredential> {
    return when (type) {
        ProviderType.openRouter -> listOf(ProviderCredential.apiKey, ProviderCredential.oauth)
        ProviderType.anthropic,
        ProviderType.openAI -> listOf(ProviderCredential.apiKey, ProviderCredential.oauth)
        ProviderType.gemini -> listOf(ProviderCredential.apiKey)
        // xAI Grok primarily targets SuperGrok / X Premium+ OAuth, but also
        // offers a plain API key path (api.x.ai). Mirror OpenAI's pattern of
        // exposing both so users on tiers without OAuth API access can still
        // configure manually.
        ProviderType.xAI -> listOf(ProviderCredential.oauth, ProviderCredential.apiKey)
        // [T-kimi-oauth] Primary target is the Coding Plan device-code sign-in;
        // a manual Moonshot API key remains available.
        ProviderType.kimiCode -> listOf(ProviderCredential.oauth, ProviderCredential.apiKey)
        // [T-copilot-provider] OAuth only: there is no API-key path into
        // api.githubcopilot.com — the session token is derived from the GitHub
        // account token, which only the device flow can obtain.
        ProviderType.githubCopilot -> listOf(ProviderCredential.oauth)
        // [T-android-provider-type-parity] Responses API instances authenticate
        // exactly like OpenAI ones (API key, or a Codex OAuth login).
        ProviderType.openAIResponses -> listOf(ProviderCredential.apiKey, ProviderCredential.oauth)
        // Undrivable types: an API key is the only thing worth showing, and the
        // screen never offers them for creation anyway.
        ProviderType.antigravity,
        ProviderType.unsupported -> listOf(ProviderCredential.apiKey)
    }
}

internal fun apiKeyDescription(type: ProviderType): String = when (type) {
    ProviderType.openAI -> "Supports OpenAI official API and compatible third-party endpoints"
    ProviderType.anthropic -> "Use an API key from your Anthropic account"
    ProviderType.gemini -> "Use an API key from your Google Gemini account"
    ProviderType.openRouter -> "Use an API key from your OpenRouter account"
    ProviderType.xAI -> "Use an API key from your xAI Console (api.x.ai)"
    ProviderType.kimiCode -> "Use an API key from your Moonshot account"
    ProviderType.githubCopilot -> "GitHub Copilot uses GitHub sign-in only"
    ProviderType.openAIResponses -> "Supports the OpenAI Responses API and compatible endpoints"
    ProviderType.antigravity,
    ProviderType.unsupported -> "This provider type is not supported on Android"
}

internal fun oauthDescription(type: ProviderType): String = when (type) {
    ProviderType.anthropic -> "Sign in with your Claude account"
    ProviderType.gemini -> "Sign in with Google for Cloud Code Assist"
    ProviderType.openAI -> "Sign in with OpenAI Codex"
    ProviderType.xAI -> "Sign in with xAI (requires SuperGrok or X Premium+)"
    ProviderType.openRouter -> "Sign in with OpenRouter"
    ProviderType.kimiCode -> "Sign in with your Kimi account (Coding Plan)"
    ProviderType.githubCopilot -> "Sign in with GitHub (requires a Copilot subscription)"
    ProviderType.openAIResponses -> "Sign in with OpenAI Codex"
    ProviderType.antigravity,
    ProviderType.unsupported -> "This provider type is not supported on Android"
}

/** Label of the primary sign-in button. */
internal fun oauthSignInLabel(type: ProviderType): String = when (type) {
    ProviderType.anthropic -> "Sign in with Claude"
    ProviderType.gemini -> "Sign in with Google"
    ProviderType.openAI -> "Sign in with OpenAI"
    ProviderType.openRouter -> "Sign in with OpenRouter"
    ProviderType.xAI -> "Sign in with xAI"
    ProviderType.kimiCode -> "Sign in with Kimi Code"
    ProviderType.githubCopilot -> "Sign in with GitHub"
    ProviderType.openAIResponses -> "Sign in with OpenAI"
    ProviderType.antigravity,
    ProviderType.unsupported -> "Sign in"
}

/** Placeholder for the manual API-key field (blank for OAuth-only providers). */
internal fun apiKeyPlaceholder(type: ProviderType): String = when (type) {
    ProviderType.anthropic -> "sk-ant-..."
    ProviderType.openAI -> "sk-..."
    ProviderType.gemini -> "Gemini API Key..."
    ProviderType.openRouter -> "sk-or-..."
    ProviderType.xAI -> "xai-..."
    ProviderType.kimiCode -> "sk-..."
    ProviderType.githubCopilot -> ""
    ProviderType.openAIResponses -> "sk-..."
    ProviderType.antigravity,
    ProviderType.unsupported -> "API Key..."
}

/** Prefilled base URL for the manual-configuration field. */
internal fun providerDefaultBaseURL(type: ProviderType): String = when (type) {
    ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta"
    ProviderType.anthropic -> "https://api.anthropic.com"
    ProviderType.openAI -> "https://api.openai.com"
    ProviderType.openRouter -> "https://openrouter.ai/api/v1"
    ProviderType.xAI -> "https://api.x.ai/v1"
    // [T-kimi-oauth] /v1 is load-bearing (/coding/ 404s without it).
    ProviderType.kimiCode -> "https://api.kimi.com/coding/v1"
    // [T-copilot-provider] No /v1 — Copilot serves off the API root.
    ProviderType.githubCopilot -> com.openminis.app.auth.CopilotDeviceFlow.API_BASE
    ProviderType.openAIResponses -> "https://api.openai.com"
    ProviderType.antigravity,
    ProviderType.unsupported -> ""
}
