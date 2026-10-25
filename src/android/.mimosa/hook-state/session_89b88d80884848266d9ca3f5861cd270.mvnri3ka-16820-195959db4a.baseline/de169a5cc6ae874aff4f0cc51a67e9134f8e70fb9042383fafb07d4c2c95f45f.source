package com.openminis.app.util

/**
 * Credential-shaped substring masking for text that leaves the process.
 *
 * [T-secret-mask-single-source] This used to live privately inside
 * `provider/openai/OpenAIProviderErrors.kt`, which kept it correct for exactly
 * one consumer: upstream error bodies. Every other place that persists or
 * displays model-facing text had no masker to reach for, so the context
 * assembly snapshot wrote the full system prompt to disk verbatim. Same
 * single-source argument as `ReasoningTagVariants` and `FlatKeys` — when two
 * consumers need one rule, the rule gets one home.
 *
 * Scope: this is DEFENCE IN DEPTH, not the primary control. Provider keys live
 * in EncryptedPrefs and travel in request headers, never in the prompt, so a
 * snapshot normally contains nothing to mask. What this catches is the case
 * where a user pasted a credential into SOUL.md, GLOBAL.md or a memory entry —
 * which then rides into every prompt and, without masking, into every file the
 * agent can `cat`.
 *
 * Deliberately shape-based rather than value-based: it does not consult the
 * real key store, so it also masks credentials this app has never seen (one
 * typed into a pasted log, another tool's token).
 */
object SecretMasking {
    private val PATTERNS = listOf(
        // OpenAI-style
        Regex("sk-[A-Za-z0-9_\\-]{8,}"),
        // Authorization header value
        Regex("Bearer\\s+[A-Za-z0-9._\\-]{8,}", RegexOption.IGNORE_CASE),
        // GitHub personal access token
        Regex("ghp_[A-Za-z0-9]{8,}"),
        // JWT — three base64url segments; two are enough to identify
        Regex("eyJ[A-Za-z0-9._\\-]{16,}"),
    )

    const val REPLACEMENT = "[redacted]"

    /** Replace every credential-shaped substring with [REPLACEMENT]. */
    fun mask(text: String): String =
        PATTERNS.fold(text) { acc, r -> acc.replace(r, REPLACEMENT) }
}
