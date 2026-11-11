package com.openminis.app.security

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [#28] Outbound privacy masking: API-key / e-mail / phone shapes are masked
 * with distinct placeholders; ordinary text passes untouched; a payload with
 * no matches comes back identity-equal so callers skip downstream work.
 */
class OutboundMaskerTest {

    @Test
    fun masksApiKeyShapes() {
        assertEquals(
            "my key is ${OutboundMasker.KEY_PLACEHOLDER} please use it",
            OutboundMasker.mask("my key is sk-abc123XYZ_def-456 please use it"),
        )
        assertEquals(
            "Authorization: ${OutboundMasker.KEY_PLACEHOLDER}",
            OutboundMasker.mask("Authorization: Bearer abc123.def-456_ghi"),
        )
        assertEquals(
            "token ${OutboundMasker.KEY_PLACEHOLDER} leaked",
            OutboundMasker.mask("token ghp_0123456789abcdefghij leaked"),
        )
        assertEquals(
            "jwt ${OutboundMasker.KEY_PLACEHOLDER} here",
            OutboundMasker.mask("jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.sig here"),
        )
    }

    @Test
    fun masksEmailAndPhones() {
        assertEquals(
            "contact ${OutboundMasker.EMAIL_PLACEHOLDER} for details",
            OutboundMasker.mask("contact alice.smith+work@example-company.com for details"),
        )
        assertEquals(
            "call ${OutboundMasker.PHONE_PLACEHOLDER}",
            OutboundMasker.mask("call 13812345678"),
        )
        assertEquals(
            "intl ${OutboundMasker.PHONE_PLACEHOLDER}",
            OutboundMasker.mask("intl +8613812345678"),
        )
    }

    @Test
    fun ordinaryTextAndDatesSurvive() {
        val text = "Meeting on 2026-11-12 at 10:30, build v2.3.1, id=12345678"
        assertEquals(text, OutboundMasker.mask(text))
        assertEquals("", OutboundMasker.mask(""))
    }

    @Test
    fun hyphenatedWordsWithSkPrefixAreNotMistakenForKeys() {
        // The "sk-" shape needs a left token boundary: risk-/task-/disk- plus
        // a plain word is ordinary English, not a credential — and sending
        // prose and code to the model is this feature's main scenario.
        assertEquals("risk-assessment", OutboundMasker.mask("risk-assessment"))
        assertEquals("task-management", OutboundMasker.mask("task-management"))
        assertEquals("disk-utilities", OutboundMasker.mask("disk-utilities"))
        assertEquals("mask-animation", OutboundMasker.mask("mask-animation"))
        assertEquals("the risk-assessment passed", OutboundMasker.mask("the risk-assessment passed"))
        // A real key right after a word boundary still masks.
        assertEquals(
            "key: ${OutboundMasker.KEY_PLACEHOLDER}",
            OutboundMasker.mask("key: sk-abc123XYZ_def-456"),
        )
    }

    @Test
    fun plusPrefixedNumbersThatAreNotPhonesSurvive() {
        // Diff line starts and UTC offsets carry a '+' followed by digits —
        // none of these is a phone number.
        assertEquals("+1234567890 added a line", OutboundMasker.mask("+1234567890 added a line"))
        assertEquals("offset UTC+1234567", OutboundMasker.mask("offset UTC+1234567"))
        // A standalone international number still masks.
        assertEquals(
            "call ${OutboundMasker.PHONE_PLACEHOLDER} now",
            OutboundMasker.mask("call +8613812345678 now"),
        )
    }

    @Test
    fun maskingIsIdempotent() {
        val once = OutboundMasker.mask("mail a@b.com or sk-abc123XYZ_def-456")
        val twice = OutboundMasker.mask(once)
        assertEquals(once, twice)
    }

    @Test
    fun maskMessageRewritesTextAndToolResultsButNotToolUse() {
        val m = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "email me at a@b.com",
            contentParts = listOf(
                AgentContentPart.ToolResult(id = "t1", name = "shell_exec", content = "key sk-abcdefgh12345678"),
                AgentContentPart.ToolUse(id = "t2", name = "shell_exec", input = org.json.JSONObject("""{"command":"echo sk-abcdefgh12345678"}""")),
            ),
        )
        val masked = OutboundMasker.maskMessage(m)
        assertEquals("email me at ${OutboundMasker.EMAIL_PLACEHOLDER}", masked.content)
        assertEquals(
            "key ${OutboundMasker.KEY_PLACEHOLDER}",
            (masked.contentParts[0] as AgentContentPart.ToolResult).content,
        )
        // ToolUse input is intentionally untouched (replay semantics).
        assertSame(
            m.contentParts[1],
            masked.contentParts[1],
        )
    }

    @Test
    fun maskPayloadIdentityWhenNothingMatches() {
        val messages = listOf(LLMMessage(LLMMessage.Role.USER, "just plan a release"))
        val (outMessages, outSystem) = OutboundMasker.maskPayload(messages, "system prompt")
        assertSame(messages, outMessages)
        assertSame("system prompt", outSystem)
    }

    @Test
    fun maskPayloadRewritesWhenSomethingMatches() {
        val messages = listOf(LLMMessage(LLMMessage.Role.USER, "mail me: a@b.com"))
        val (outMessages, outSystem) = OutboundMasker.maskPayload(messages, null)
        assertNotSame(messages, outMessages)
        assertEquals("mail me: ${OutboundMasker.EMAIL_PLACEHOLDER}", outMessages[0].content)
        assertEquals(null, outSystem)
    }
}
