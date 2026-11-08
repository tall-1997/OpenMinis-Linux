package com.openminis.app.cottranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-function tests for the reasoning-translation pipeline. */
class CotTranslateTest {

    // ── reasoningFragments ────────────────────────────────────────────────

    @Test
    fun `paragraphs split on blank lines with whitespace preserved`() {
        val text = "para one\n\npara two\n\npara three"
        val fragments = reasoningFragments(text, running = false)
        assertEquals(
            listOf("para one", "para two", "para three"),
            fragments.map { it.text },
        )
        // The suffix between fragments is the blank-line separator, verbatim.
        assertEquals("\n\n", fragments[0].suffix)
        assertEquals("\n\n", fragments[1].suffix)
        assertTrue(fragments.all { it.complete })
    }

    @Test
    fun `streaming tail paragraph is not complete`() {
        val fragments = reasoningFragments("done para\n\nstill typing", running = true)
        assertTrue(fragments[0].complete)
        assertFalse("the growing tail must not be translated", fragments[1].complete)
    }

    @Test
    fun `finished stream marks every fragment complete`() {
        val fragments = reasoningFragments("a\n\nb", running = false)
        assertTrue(fragments.all { it.complete })
    }

    @Test
    fun `oversized fragment splits at whitespace without breaking surrogates`() {
        // Build a 100-char paragraph of two 50-char words; cap at 60 → split
        // lands on the inner space, not mid-word.
        val word = "a".repeat(50)
        val text = "$word $word"
        val fragments = reasoningFragments(text, running = false, maxTextChars = 60)
        assertEquals(2, fragments.size)
        assertTrue(fragments[0].text.endsWith(word.take(49)))
        assertEquals(" ", fragments[0].suffix)
    }

    @Test
    fun `surrogate pair is never split at the cap`() {
        // Emoji is 2 UTF-16 units; place one straddling the 60-char boundary.
        val text = "x".repeat(59) + "\uD83D\uDE00" + "y".repeat(40)
        val fragments = reasoningFragments(text, running = false, maxTextChars = 60)
        // No fragment may end with an unpaired high surrogate.
        fragments.forEach { f ->
            assertFalse(
                "fragment ends with a lone high surrogate",
                f.text.isNotEmpty() && f.text.last().isHighSurrogate(),
            )
        }
    }

    // ── language normalization ────────────────────────────────────────────

    @Test
    fun `chinese variants map per provider`() {
        assertEquals("zh-CN", normalizedLanguage("zh-CN", TranslationProvider.GOOGLE))
        assertEquals("zh-Hans", normalizedLanguage("zh-CN", TranslationProvider.BING))
        assertEquals("zh-TW", normalizedLanguage("zh-TW", TranslationProvider.GOOGLE))
        assertEquals("zh-Hant", normalizedLanguage("zh-TW", TranslationProvider.BING))
    }

    // ── response parsing ──────────────────────────────────────────────────

    @Test
    fun `bing payload parses the outer array shape`() {
        val raw = """[{"detectedLanguage":"en","translations":[{"text":"你好","to":"zh-Hans"}]}]"""
        assertEquals("你好", parsePayload(raw, TranslationProvider.BING))
    }

    @Test
    fun `google payload parses the nested segment shape`() {
        val raw = """[[["你好","hello",null,null,10]],null,"en"]"""
        assertEquals("你好", parsePayload(raw, TranslationProvider.GOOGLE))
    }

    @Test
    fun `malformed payloads return null instead of throwing`() {
        assertTrue(parsePayload("not json", TranslationProvider.BING) == null)
        assertTrue(parsePayload("""{"unexpected":1}""", TranslationProvider.BING) == null)
        assertTrue(parsePayload("""[{"translations":[]}]""", TranslationProvider.BING) == null)
    }
}
