package com.openminis.app.ui.chat

import com.openminis.app.BuildConfig
import com.openminis.app.auth.ClaudeOAuthManager
import com.openminis.app.data.repository.MemoryDeliverySpan
import com.openminis.app.data.repository.MemoryEntryRef
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.openai.OpenAIProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OAuthPromptProjectionTest {
    @Test
    fun apiKeyAndOtherProvidersNeverReadTheOAuthIdentifier() {
        val prompt = MemoryDeliveryPrompt("identity\nquestion", null, stablePrefixLen = 8)
        val providers = listOf(AnthropicProvider("test-key"), OpenAIProvider("test-key"))
        for (provider in providers) {
            assertSame(prompt, prompt.withOAuthPrefix(provider) { error("OAuth metadata must not be read") })
        }
    }

    @Test
    fun unconfiguredOAuthKeepsSessionEstimatesReadable() {
        val provider = AnthropicProvider("test-token", isOAuth = true)
        val prompt = MemoryDeliveryPrompt("identity\nquestion", null, stablePrefixLen = -1)
        for (missing in listOf(null, "", " \t\n")) {
            assertSame(prompt, prompt.withOAuthPrefix(provider) { missing })
            assertNull(oauthIdentifierPrefix(provider) { missing })
        }
    }

    @Test
    fun optionalBuildMetadataCanBeReadWithoutThrowing() {
        assertEquals(
            BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT.takeIf { it.isNotBlank() },
            ClaudeOAuthManager.identifierPromptOrNull(),
        )
        val provider = AnthropicProvider("test-key")
        val prompt = MemoryDeliveryPrompt("rules", null)
        assertSame(prompt, prompt.withOAuthPrefix(provider))
    }

    @Test
    fun configuredOAuthMovesAllMemoryCoordinatesAndWrapsOnlyOnce() {
        val provider = AnthropicProvider("test-token", isOAuth = true)
        val identifier = "fixture OAuth identity"
        val prefix = "$identifier\n\n"
        val body = "rules\n\nmemory \uD83E\uDDED\nquestion"
        val memoryStart = body.indexOf("memory")
        val memoryEnd = body.indexOf("\nquestion")
        val ref = MemoryEntryRef(File("memory"), "2026-11-20.md", "entry", "a".repeat(64))
        val span = MemoryDeliverySpan(ref, memoryStart, memoryEnd)
        val slot = DailyMemoryDelivery(body, 5, memoryEnd, "sid", "sid", emptyList(), listOf(span), emptySet(), 5, projectedAt = 123)
        val prompt = MemoryDeliveryPrompt(body, slot, stablePrefixLen = 5)

        val wrapped = prompt.withOAuthPrefix(provider) { identifier }
        assertEquals(prefix + body, wrapped.prompt)
        assertEquals(5 + prefix.length, wrapped.stablePrefixLen)
        val shifted = checkNotNull(wrapped.daily)
        assertEquals(slot.start + prefix.length, shifted.start)
        assertEquals(slot.end + prefix.length, shifted.end)
        assertEquals(slot.stablePrefixLen + prefix.length, shifted.stablePrefixLen)
        assertEquals(listOf(span.shifted(prefix.length)), shifted.spans)
        assertEquals(setOf(ref), shifted.deliveredIn(wrapped.prompt))
        assertSame(wrapped, wrapped.withOAuthPrefix(provider) { identifier })
        val coincidentalText = prompt.copy(prompt = identifier + " is ordinary prompt text")
        assertEquals(prefix + coincidentalText.prompt, coincidentalText.withOAuthPrefix(provider) { identifier }.prompt)
        assertEquals(-1, prompt.copy(stablePrefixLen = -1).withOAuthPrefix(provider) { identifier }.stablePrefixLen)
    }
}
