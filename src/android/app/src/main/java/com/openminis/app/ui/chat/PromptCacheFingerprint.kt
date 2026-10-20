package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition
import java.security.MessageDigest

/**
 * [T-prompt-cache-diag] Short fingerprint of the two request parts that MUST
 * stay byte-identical across turns for prefix caching to work: the system
 * prompt and the tool definitions (tools are serialized before messages on
 * every provider).
 *
 * The agent loop logs this once per run. Two consecutive turns with the same
 * fingerprint are expected to hit the provider's prefix cache (given the
 * conversation prefix is append-only); a changing fingerprint points at the
 * component that is invalidating it, which is exactly what was invisible
 * before.
 *
 * Canonical form: the system string, then each tool's OpenAI-shaped JSON in
 * list order (the same name/description/parameter fields every provider
 * serializes). Hashed with SHA-256 and truncated to 16 hex chars — enough to
 * compare log lines, not a security boundary.
 */
internal fun promptCacheFingerprint(
    systemPrompt: String?,
    tools: List<AgentToolDefinition>,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(systemPrompt.orEmpty().toByteArray(Charsets.UTF_8))
    for (tool in tools) {
        // Separator keeps ("ab","c") distinct from ("a","bc").
        digest.update(0)
        digest.update(tool.toOpenAIJson().toString().toByteArray(Charsets.UTF_8))
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
}
