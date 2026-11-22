package com.openminis.app.cottranslate

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-cot-translate-test] Covers the two places where reasoning translation can
 * silently corrupt what the user reads: fragment splitting (must never lose or
 * reorder whitespace, never split a surrogate pair) and the publish/splice
 * logic (cached translations + untranslated tail, in the original order).
 *
 * The HTTP boundary is a `fun interface`, so a lambda stands in for the network
 * and the whole pipeline is exercised without Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReasoningTranslationTest {

    /** The store is process-global; reset it so a cache hit from a previous
     *  test cannot make a "must hit the network" assertion pass vacuously. */
    @Before
    fun resetStore() {
        TranslationStore.clear()
    }

    /**
     * A well-formed Microsoft Edge translate response for one input.
     * The controller parses this shape, so a raw string stub would land in
     * `Failed` and hide real regressions behind a fake failure.
     */
    private fun bingPayload(text: String): String =
        org.json.JSONArray().put(
            org.json.JSONObject().put(
                "translations",
                org.json.JSONArray().put(org.json.JSONObject().put("text", text).put("to", "en")),
            ),
        ).toString()

    // ── Fragment splitting ────────────────────────────────────────────────

    @Test
    fun `complete text splits on blank lines and keeps separators`() {
        val text = "first paragraph\n\nsecond paragraph\n\n\nthird"
        val frags = reasoningFragments(text, running = false)

        assertEquals(3, frags.size)
        assertEquals("first paragraph", frags[0].text)
        assertEquals("\n\n", frags[0].suffix)
        assertEquals("second paragraph", frags[1].text)
        assertEquals("\n\n\n", frags[1].suffix)
        assertEquals("third", frags[2].text)
        assertEquals("", frags[2].suffix)

        // Nothing lost: concatenating text+suffix reproduces the input exactly.
        assertEquals(text, frags.joinToString("") { it.text + it.suffix })
        assertTrue(frags.all { it.complete })
    }

    @Test
    fun `streaming tail is marked incomplete and everything before it complete`() {
        val text = "done paragraph\n\nstill growing"
        val frags = reasoningFragments(text, running = true)

        assertEquals(2, frags.size)
        assertTrue(frags[0].complete)
        assertFalse("the trailing fragment is still streaming", frags[1].complete)
    }

    @Test
    fun `single line whitespace is not a paragraph break`() {
        val frags = reasoningFragments("a\nb\nc", running = false)
        assertEquals(1, frags.size)
        assertEquals("a\nb\nc", frags[0].text)
    }

    @Test
    fun `oversized paragraph is cut on a whitespace boundary`() {
        val text = "word ".repeat(20).trim() // 99 chars
        val frags = reasoningFragments(text, running = false, maxTextChars = 20)

        assertTrue("must actually split", frags.size > 1)
        // No fragment exceeds the request limit once whitespace is accounted.
        assertTrue(frags.all { it.text.length <= 20 })
        // Reassembly is exact.
        assertEquals(text, frags.joinToString("") { it.text + it.suffix })
    }

    @Test
    fun `surrogate pairs are never split across fragments`() {
        // Emoji are surrogate pairs in UTF-16; cutting one yields broken text.
        val text = "\uD83D\uDE00".repeat(30) // 30 emoji, 60 UTF-16 units
        val frags = reasoningFragments(text, running = false, maxTextChars = 16)

        assertTrue(frags.size > 1)
        for (f in frags) {
            for (i in f.text.indices) {
                val c = f.text[i]
                if (Character.isHighSurrogate(c)) {
                    assertTrue(
                        "high surrogate at ${f.text.length} char fragment must be followed by its low surrogate",
                        i + 1 < f.text.length && Character.isLowSurrogate(f.text[i + 1]),
                    )
                }
            }
        }
        assertEquals(text, frags.joinToString("") { it.text + it.suffix })
    }

    @Test
    fun `empty text produces a single empty fragment`() {
        val frags = reasoningFragments("", running = false)
        assertEquals(1, frags.size)
        assertEquals("", frags[0].text)
        assertTrue(frags[0].complete)
    }

    // ── Auto target-language resolution ───────────────────────────────────

    @Test
    fun `auto resolves to the device language and explicit tags pass through`() {
        assertEquals("zh", resolveTargetLanguage("auto", "zh"))
        assertEquals("zh", resolveTargetLanguage("AUTO", "zh"))
        assertEquals("en", resolveTargetLanguage("en", "zh"))
        assertEquals("ja", resolveTargetLanguage("  ja  ", "zh"))
    }

    // ── Publish / splice ──────────────────────────────────────────────────

    @Test
    fun `update publishes translations for complete fragments and the raw tail`() = runTest {
        val translated = mutableListOf<String>()
        var state: ReasoningTranslationController.CotTranslationState? = null
        val http = TranslationHttp { _, body, _, _ ->
            // Echo a marker so the test can assert which fragment was sent.
            translated.add(body)
            bingPayload("T:ok")
        }

        val controller = ReasoningTranslationController(
            blockKey = "block-1",
            provider = TranslationProvider.BING,
            targetLanguage = "en",
            scope = this,
            http = http,
            publish = { state = it },
        )

        controller.update("first\n\nsecond", running = false)
        advanceUntilIdle()

        assertTrue("at least one request was issued", translated.isNotEmpty())
        val published = requireNotNull(state).text
        // Both paragraphs got the stubbed translation.
        assertTrue("published text lost the translation: $published", published.contains("T:ok"))
        assertFalse("nothing should still be pending", requireNotNull(state).pending)
        assertFalse("nothing should have failed", requireNotNull(state).failed)

        controller.dispose()
    }

    @Test
    fun `incomplete tail is left untranslated in the published text`() = runTest {
        var state: ReasoningTranslationController.CotTranslationState? = null
        val http = TranslationHttp { _, _, _, _ -> bingPayload("T") }

        val controller = ReasoningTranslationController(
            blockKey = "block-2",
            provider = TranslationProvider.BING,
            targetLanguage = "en",
            scope = this,
            http = http,
            publish = { state = it },
        )

        controller.update("finished paragraph\n\npartial tail", running = true)
        advanceUntilIdle()

        val published = requireNotNull(state).text
        // The streaming tail is never sent, so it must survive verbatim.
        assertTrue(
            "streaming tail must stay in the original language: $published",
            published.contains("partial tail"),
        )

        controller.dispose()
    }

    @Test
    fun `http failure surfaces as failed state with the original text`() = runTest {
        var state: ReasoningTranslationController.CotTranslationState? = null
        val http = TranslationHttp { _, _, _, _ -> null } // null == request failed

        val controller = ReasoningTranslationController(
            blockKey = "block-3",
            provider = TranslationProvider.BING,
            targetLanguage = "en",
            scope = this,
            http = http,
            publish = { state = it },
        )

        controller.update("a paragraph that fails", running = false)
        advanceUntilIdle()

        val s = requireNotNull(state)
        assertTrue("a failed fragment must flip the failed flag", s.failed)
        assertTrue(
            "failed translation must fall back to the original text: ${s.text}",
            s.text.contains("a paragraph that fails"),
        )

        controller.dispose()
    }

    @Test
    fun `repeated update reuses the cache instead of re-requesting`() = runTest {
        var requests = 0
        val http = TranslationHttp { _, _, _, _ -> requests++; bingPayload("T") }

        val controller = ReasoningTranslationController(
            blockKey = "block-4",
            provider = TranslationProvider.BING,
            targetLanguage = "en",
            scope = this,
            http = http,
            publish = { },
        )

        controller.update("stable paragraph", running = false)
        advanceUntilIdle()
        val afterFirst = requests

        controller.update("stable paragraph", running = false)
        advanceUntilIdle()

        assertEquals("unchanged text must not hit the network twice", afterFirst, requests)

        controller.dispose()
    }

    @Test
    fun `dispose stops publishing`() = runTest {        var published = 0
        val http = TranslationHttp { _, _, _, _ -> bingPayload("T") }

        val controller = ReasoningTranslationController(
            blockKey = "block-5",
            provider = TranslationProvider.BING,
            targetLanguage = "en",
            scope = this,
            http = http,
            publish = { published++ },
        )

        controller.update("before dispose", running = false)
        advanceUntilIdle()
        val before = published

        controller.dispose()
        controller.update("after dispose", running = false)
        advanceUntilIdle()

        assertEquals("a disposed controller must not publish", before, published)
    }
}
