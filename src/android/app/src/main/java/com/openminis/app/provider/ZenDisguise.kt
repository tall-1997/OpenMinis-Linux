package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.math.BigInteger
import java.security.MessageDigest

/** OpenCode Zen request identity helpers. */
object ZenDisguise {
    private const val USER_AGENT = "opencode/1.18.31 (Android arm64; native)"
    private const val PROJECT = "prj_4d5348f7f5f7d0b3f4cc7a7e"
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    fun userAgent(): String = USER_AGENT

    /**
     * Whether [baseURL] addresses the OpenCode Zen endpoint.
     *
     * [T-zen-host-anchor] Anchored on the URL HOST, not a substring: the old
     * `contains("opencode.ai/zen")` also matched a bare path fragment, so a
     * relay like `https://example.com/opencode.ai/zen` was dressed in the
     * disguise and fed the Zen error dialect it knows nothing about. The host
     * must be exactly `opencode.ai` (case-insensitive — okhttp lowercases it)
     * and the path must live under `/zen`.
     *
     * Scope note: this predicate serves the WIRE layer only — the request
     * builder (bash/read gate tools), the streaming header injection, and the
     * error mapper (the Zen error dialect is recognised here and nowhere
     * else). The model-list filter / reconciliation layer does NOT consume
     * this predicate: ProviderRepository.isZenInstance matches the bundled
     * endpoint exactly (config-level), so a user's own variant Zen spelling is
     * never swept into the free-lane logic. See its KDoc for the split.
     */
    fun isZenHost(baseURL: String?): Boolean {
        val url = baseURL?.trim()?.toHttpUrlOrNull() ?: return false
        return url.host == "opencode.ai" &&
            (url.encodedPath == "/zen" || url.encodedPath.startsWith("/zen/"))
    }

    /**
     * The canonical session id the Zen free lane requires: `ses_` + exactly 12
     * lowercase hex + exactly 14 Base62 characters.
     *
     * Measured 2026-10-05 against `POST /zen/v1/chat/completions`: this shape
     * is the gate. A 23-, 25- or 27-character id, a missing `ses_` prefix, and
     * an uppercase hex prefix each answer 403 FreeTierError with a body
     * byte-identical to a request carrying no disguise at all — which is what
     * made the refusal read as an unsatisfiable client-identity wall rather
     * than a malformed header. With a canonical id the free lane streams 200.
     */
    private val CANONICAL_SESSION = Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")

    /**
     * The 14-character Base62 tail.
     *
     * `BigInteger(1, byteArray)` is UNSIGNED here — that constructor takes an
     * explicit signum, so a leading byte >= 0x80 does not make the value
     * negative and every remainder lands in 0..61. (Measured on the JVM: with
     * `digest[6]` at 0xbd/0xc6/0xa2 the result is positive and the tail is a
     * full 14 characters. An earlier note here claimed a sign bug and a
     * 22-character id; that was wrong, and the "fix" it motivated was a no-op.)
     *
     * The `mod` call is kept rather than `%` so the intent survives: `mod`
     * always returns a non-negative remainder for a positive modulus, which is
     * exactly the invariant the alphabet index depends on.
     */
    private fun base62Tail(digest: ByteArray): String {
        var value = BigInteger(1, digest.copyOfRange(6, 16))
        val base = BigInteger.valueOf(62L)
        val chars = CharArray(14)
        for (i in chars.indices.reversed()) {
            chars[i] = ALPHABET[value.mod(base).toInt()]
            value = value.divide(base)
        }
        return chars.concatToString()
    }

    /**
     * Seed rule: the FIRST USER message of the typed [messages] list (content
     * verbatim), or the constant fallback when the list has no user turn or
     * the first user turn is blank. The system prompt is deliberately NOT the
     * seed — it is passed to the body builders separately, and a dynamic
     * system prompt would drift the session id every turn.
     */
    fun sessionId(messages: List<LLMMessage>): String {
        val seed = messages.firstOrNull { it.role == LLMMessage.Role.USER }?.content?.toString().orEmpty()
            .ifBlank { "opencode2dsh-empty-conversation" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("ses\u0000" + seed).toByteArray(Charsets.UTF_8))
        val time = digest.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "ses_$time${base62Tail(digest)}"
    }

    /**
     * The same id for a caller that has no message list — a model probe or an
     * availability check. A probe has to be identified by the upstream exactly
     * as a real conversation is, or its answer says nothing about the model.
     */
    fun sessionIdForSeed(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("ses\u0000" + seed).toByteArray(Charsets.UTF_8))
        val time = digest.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "ses_$time${base62Tail(digest)}"
    }

    /**
     * Whether [id] has the exact shape the upstream accepts. Exposed so the
     * shape is asserted where it is produced, and so any code reasoning about
     * a session id it did not build itself can check it.
     */
    fun isCanonicalSession(id: String): Boolean = CANONICAL_SESSION.matches(id)

    fun requestId(): String = "req_" + java.util.UUID.randomUUID().toString().replace("-", "")

    /**
     * Applies the disguise headers. The session id derives from the typed
     * [messages] list — never from a re-parse of the serialized body (the old
     * applyToBody path re-parsed tens of MB per request, violating T302
     * single-serialization; folded the JSON `system` message into USER so the
     * seed was actually the system prompt; and left Responses bodies — which
     * have no `messages` array — sharing one constant seed).
     *
     * [T-zen-ua-preserve] [disguiseUserAgent] must be false when the caller
     * has already applied a user-configured customUserAgent: `.header()`
     * replaces, so the disguise UA would silently overwrite the user's choice
     * on a paid Zen instance. The `x-opencode-*` headers have no
     * user-configurable counterpart and are always applied.
     */
    fun apply(
        builder: Request.Builder,
        messages: List<LLMMessage>,
        disguiseUserAgent: Boolean = true,
    ): Request.Builder {
        val session = sessionId(messages)
        if (disguiseUserAgent) builder.header("User-Agent", USER_AGENT)
        return builder
            .header("x-opencode-client", "cli")
            .header("x-opencode-session", session)
            .header("x-session-affinity", session)
            .header("X-Session-Id", session)
            .header("x-opencode-request", requestId())
            .header("x-opencode-project", PROJECT)
    }
}
