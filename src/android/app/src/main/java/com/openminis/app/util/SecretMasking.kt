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

    /**
     * [T-secret-mask-assignments] `key=value` / `key: value` shapes that the
     * token patterns miss: `Authorization: <opaque>`, `"api_key": "…"`,
     * `?token=…`, `password=…` in a shell command. Optional quotes around the
     * key cover JSON; the value class excludes `&`/`#` so URL query params
     * after the secret survive. The KEY is kept so the masked command still
     * reads as an action.
     */
    private const val SECRET_KEYS =
        "authorization|x-api-key|api-key|api_key|apikey|apiKey|" +
            "access_token|accessToken|refresh_token|refreshToken|" +
            "client_secret|clientSecret|secret_key|secretKey|" +
            "private_key|privateKey|token|password|passwd|secret"

    // [T-secret-mask-shape] Mask only values SHAPED like a credential: a quoted
    // value of4+ chars, or an unquoted token of8+ credential-alphabet chars
    // that contains a digit or symbol. The old "any4+ non-space chars" form
    // rewrote ordinary prose ("secret: family recipe" -> "secret=[redacted]
    // recipe"), and this function's output is PERSISTED (compaction summaries,
    // memory sediment, hidden notes), so that damage was not cosmetic.
    private val ASSIGNMENTS = Regex(
        "(?i)[\"']?\\b($SECRET_KEYS)\\b[\"']?\\s*[:=]\\s*" +
        "(?:[\"'](?<quoted>[^\"']{4,})[\"']|" +
        // Bare values: 8+ credential-alphabet characters. No digit/symbol
        // requirement: a long alphabetic value (correcthorsebatterystaple) is a
        // password too, and these strings are PERSISTED (compaction summary,
        // memory sediment). Prose stays safe because a space ends the value, so
        // "secret: family recipe" cannot match.
        "(?<bare>[A-Za-z0-9_\\-./+=]{8,}))",
    )

    /**
     * [T-secret-mask-auth-scheme] `Authorization: Basic <base64>` /
     * `Bearer <opaque>`: the scheme token is short and would hide the real
     * credential from [ASSIGNMENTS]' 4-char floor. Consume scheme + secret.
     */
    private val AUTH_SCHEME = Regex(
        "(?i)\\b(authorization)\\s*[:=]\\s*(?:basic|bearer|digest|negotiate|token)?\\s*" +
            "[\"']?([^\\s\"',;&#]{4,})",
    )

    /**
     * [T-secret-mask-cli] Command-line secret flags use a SPACE, not `=`
     * (`mysql --password s3cret`, `--token abc`, `curl -u user:pass`).
     */
    private val CLI_SECRET = Regex(
        "(?i)(--?(?:password|passwd|token|api-key|apikey|secret|client-secret))\\s+" +
            "[\"']?([^\\s\"']{3,})",
    )

    /** curl-style basic auth: `-u user:password`. */
    private val CLI_USERINFO = Regex(
        "(?i)((?:^|\\s)-u)\\s+[^\\s:\"']{1,64}:[^\\s\"']{3,}",
    )

    /** sshpass passes the password after `-p`. */
    private val SSHPASS = Regex("(?i)\\b(sshpass)\\s+-p\\s+[^\\s\"']{2,}")

    const val REPLACEMENT = "[redacted]"

    /** Replace every credential-shaped substring with [REPLACEMENT]. */
    fun mask(text: String): String {
        var out = PATTERNS.fold(text) { acc, r -> acc.replace(r, REPLACEMENT) }
        out = AUTH_SCHEME.replace(out) { "${it.groupValues[1]}=$REPLACEMENT" }
        out = CLI_SECRET.replace(out) { "${it.groupValues[1]} $REPLACEMENT" }
        out = CLI_USERINFO.replace(out) { "${it.groupValues[1]} $REPLACEMENT" }
        out = SSHPASS.replace(out) { "${it.groupValues[1]} -p $REPLACEMENT" }
        return ASSIGNMENTS.replace(out) { "${it.groupValues[1]}=$REPLACEMENT" }
    }
}
