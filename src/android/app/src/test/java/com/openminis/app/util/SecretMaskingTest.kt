package com.openminis.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-secret-mask-single-source][T-secret-mask-assignments] Credential-shaped
 * masking is defense in depth for text that is persisted or shown: provider
 * errors, context snapshots, and now working-memory / failure-ledger excerpts.
 */
class SecretMaskingTest {

    @Test
    fun masksTokenShapes() {
        val text = "key=sk-abcdefgh12345678 header=Bearer abcdefgh12345"
        val masked = SecretMasking.mask(text)
        assertFalse(masked.contains("sk-abcdefgh12345678"))
        assertFalse(masked.contains("abcdefgh12345"))
        assertTrue(masked.contains(SecretMasking.REPLACEMENT))
    }

    @Test
    fun masksAssignmentShapesButKeepsTheKey() {
        val text = "curl -H 'Authorization: opaque-token-123' https://x/?token=abcdef123"
        val masked = SecretMasking.mask(text)
        assertFalse(masked.contains("opaque-token-123"))
        assertFalse(masked.contains("abcdef123"))
        assertTrue(masked.contains("Authorization=[redacted]"))
        assertTrue(masked.contains("token=[redacted]"))
    }

    @Test
    fun bearerHeaderIsMaskedOnceWithoutBreakingTheCommand() {
        val text = "curl -H 'Authorization: Bearer sk-abcdefgh12345678' https://api.example.com"
        val masked = SecretMasking.mask(text)
        assertFalse(masked.contains("sk-abcdefgh12345678"))
        assertTrue(masked.contains("https://api.example.com"))
    }

    @Test
    fun ordinaryPathsAndCommandsPassThrough() {
        val text = "cargo build --release --manifest-path /src/Cargo.toml"
        assertEquals(text, SecretMasking.mask(text))
    }

    @Test
    fun masksQuotedJsonKeysAndAuthSchemes() {
        val text = """{"api_key": "hunter2x", "client_secret": "s3cr3t-value"}"""
        val masked = SecretMasking.mask(text)
        assertFalse(masked.contains("hunter2x"))
        assertFalse(masked.contains("s3cr3t-value"))
        assertTrue(masked.contains("api_key"))

        val basic = SecretMasking.mask("Authorization: Basic dXNlcjpwYXNzd29yZA==")
        assertFalse(basic.contains("dXNlcjpwYXNzd29yZA=="))
        assertTrue(basic.contains("Authorization=[redacted]"))
    }

    @Test
    fun urlQueryAfterTheSecretSurvives() {
        val masked = SecretMasking.mask("https://x/api?token=abcdef123&id=42#frag")
        assertFalse(masked.contains("abcdef123"))
        assertTrue(masked.contains("&id=42#frag"))
    }

    @Test
    fun masksCliSpaceForms() {
        val text = "mysql --password s3cret --token abc123; curl -u alice:hunter2; sshpass -p pw123 ssh host"
        val masked = SecretMasking.mask(text)
        for (secret in listOf("s3cret", "abc123", "hunter2", "pw123")) {
            assertFalse("leaked $secret: $masked", masked.contains(secret))
        }
        assertTrue(masked.contains("mysql"))
        assertTrue(masked.contains("ssh host"))
    }

    @Test
    fun authorizationTokenSchemeIsMasked() {
        val masked = SecretMasking.mask("Authorization: Token opaque-token-9")
        assertFalse(masked.contains("opaque-token-9"))
    }

    @Test
    fun shortValuesAreNotMaskedAsCredentials() {
        // "token: ok" is prose, not a credential; the 6-char floor avoids it.
        assertEquals("token: ok", SecretMasking.mask("token: ok"))
    }

    @Test
    fun aLongAlphabeticPasswordIsStillMasked() {
        // [T-secret-mask-shape] These strings are persisted (compaction summary,
        // memory sediment), so a leaked alphabetic password is unacceptable; only
        // the SPACE boundary protects ordinary prose.
        assertFalse(SecretMasking.mask("password=correcthorsebatterystaple").contains("correcthorse"))
    }

    @Test
    fun ordinaryProseAfterASecretKeywordIsLeftAlone() {
        assertTrue(SecretMasking.mask("secret: family recipe").contains("family recipe"))
    }
}
