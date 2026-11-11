package com.openminis.app.security

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [#28] Outbound privacy masking: rewrites credential-shaped, email-shaped
 * and phone-shaped substrings in everything that leaves the process toward a
 * model provider (chat history, system prompt, tool results echoed back into
 * the request).
 *
 * This is the OUTBOUND side of the existing privacy surfaces:
 *  - [com.openminis.app.data.EnvVarRedactor] masks env-var values in shell
 *    OUTPUT (tool results, on a separate opt-out switch);
 *  - [com.openminis.app.util.SecretMasking] masks credential-shaped text in
 *    logs and snapshots;
 *  - this class masks the request payload itself, pattern-based.
 *
 * Shape-based by design (no key-store consultation): it also masks secrets
 * this app has never seen. Patterns are deliberately conservative — a masked
 * token the model needs verbatim is worse than a leaked one the user opted
 * out of, so anything ambiguous stays untouched:
 *  - API keys: the SecretMasking pattern set (sk-…, Bearer …, ghp_…, JWT …);
 *  - e-mail: standard address shape;
 *  - phone: CN mobile (1[3-9]xxxxxxxxx) and international (+country…number)
 *    forms only — bare digit runs would mangle dates, ids and versions.
 *
 * Pure JVM, no Android imports, unit-tested directly.
 */
object OutboundMasker {

    private val API_KEY_PATTERNS = listOf(
        // Left boundary: "risk-assessment" / "task-list" must NOT mask — the
        // suffix after "sk-" is a plain word, not a key. A key is a token the
        // user (or a config file) wrote as its own unit.
        Regex("(?<![A-Za-z0-9])sk-[A-Za-z0-9_\\-]{8,}"),
        Regex("(?<![A-Za-z0-9])Bearer\\s+[A-Za-z0-9._\\-]{8,}", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])ghp_[A-Za-z0-9]{8,}"),
        Regex("(?<![A-Za-z0-9])eyJ[A-Za-z0-9._\\-]{16,}"),
    )
    private val EMAIL_PATTERN =
        Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val CN_MOBILE_PATTERN = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    // Right boundary: "+1234567890" at a diff line start or "UTC+1234567"
    // offset text is not a phone — the '+' must start the token (no
    // alphanumeric before it) and the run must be ≥11 digits: country code
    // plus subscriber number. A 10-digit run is far more often a diff line
    // id/timestamp than a phone, and mauling diffs is worse than missing an
    // occasional short-format number.
    private val INTERNATIONAL_PHONE_PATTERN = Regex("(?<![\\dA-Za-z])\\+\\d{11,14}(?!\\d)")

    const val KEY_PLACEHOLDER = "[masked-key]"
    const val EMAIL_PLACEHOLDER = "[masked-email]"
    const val PHONE_PLACEHOLDER = "[masked-phone]"

    /** Replace every credential/e-mail/phone-shaped substring in [text]. */
    fun mask(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        for (r in API_KEY_PATTERNS) out = out.replace(r, KEY_PLACEHOLDER)
        out = out.replace(EMAIL_PATTERN, EMAIL_PLACEHOLDER)
        out = out.replace(CN_MOBILE_PATTERN, PHONE_PLACEHOLDER)
        out = out.replace(INTERNATIONAL_PHONE_PATTERN, PHONE_PLACEHOLDER)
        return out
    }

    /**
     * Mask one message: text content, structured text/tool-result parts and
     * echoed reasoning content. ToolUse inputs stay intact on purpose: the
     * agent compares its own emitted tool calls against the history it sees
     * back, and rewriting arguments mid-conversation reads to the model as
     * its commands being corrupted. The command OUTPUT is masked above, so a
     * pasted secret in a command string reaches the wire once (inside that
     * tool_use block) rather than echoing through every later turn.
     */
    fun maskMessage(m: LLMMessage): LLMMessage {
        val content = mask(m.content)
        val parts = if (m.contentParts.isEmpty()) m.contentParts else m.contentParts.map(::maskPart)
        val reasoning = m.reasoningContent?.let(::mask)
        return if (content == m.content && parts === m.contentParts && reasoning === m.reasoningContent) {
            m
        } else {
            m.copy(content = content, contentParts = parts, reasoningContent = reasoning)
        }
    }

    private fun maskPart(p: AgentContentPart): AgentContentPart = when (p) {
        is AgentContentPart.Text -> p.copy(text = mask(p.text))
        is AgentContentPart.ToolResult -> p.copy(content = mask(p.content))
        is AgentContentPart.ToolUse, is AgentContentPart.ImageData -> p
    }

    /**
     * Mask the whole outbound payload. Returns the input instances untouched
     * (same references) when nothing changed, so callers can rely on identity
     * to skip downstream work.
     */
    fun maskPayload(
        messages: List<LLMMessage>,
        systemPrompt: String?,
    ): Pair<List<LLMMessage>, String?> {
        var changed = false
        val masked = messages.map { m ->
            val mm = maskMessage(m)
            if (mm !== m) changed = true
            mm
        }
        val maskedSystem = systemPrompt?.let(::mask)
        if (maskedSystem != systemPrompt) changed = true
        return if (changed) masked to maskedSystem else messages to systemPrompt
    }
}
