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

    // ── auto target resolution ───────────────────────────────────────────

    @Test
    fun `auto target resolves to the device locale language`() {
        assertEquals("zh", resolveTargetLanguage("auto", "zh"))
        assertEquals("en", resolveTargetLanguage("auto", "en"))
        assertEquals("zh", resolveTargetLanguage("zh", "en"))
        assertEquals("JA", resolveTargetLanguage("JA", "en"))
    }

    @Test
    fun `translateOnce resolves auto instead of failing on empty target`() {
        // Default targetLanguage IS "auto" — the anonymous routes must
        // resolve it at request time, or every default-settings attempt
        // fails instantly.
        var seenUrl = ""
        val http = TranslationHttp { url, _, _, _ ->
            seenUrl = url
            """[{"translations":[{"text":"TR","to":"zh-Hans"}]}]"""
        }
        val result = translateOnce(
            TranslationRequest("hello", "auto"), TranslationProvider.BING, http,
            autoLanguage = "zh",
        )
        assertEquals("TR", (result as TranslationResult.Ok).text)
        assertTrue("resolved tag must reach the URL", seenUrl.contains("to=zh-Hans"))
    }

    // ── provider prefs readback ──────────────────────────────────────────

    @Test
    fun `stored provider names round-trip including llm`() {
        assertEquals(TranslationProvider.BING, CotTranslationPrefs.providerFromStored(null))
        assertEquals(TranslationProvider.BING, CotTranslationPrefs.providerFromStored("BING"))
        assertEquals(TranslationProvider.GOOGLE, CotTranslationPrefs.providerFromStored("GOOGLE"))
        assertEquals(TranslationProvider.LLM, CotTranslationPrefs.providerFromStored("LLM"))
        assertEquals(TranslationProvider.BING, CotTranslationPrefs.providerFromStored("garbage"))
    }

    // ── google multi-group payload ───────────────────────────────────────

    @Test
    fun `google payload with multiple sentence groups concatenates`() {
        val raw = """[[["first","a"]],null,"en",[[" second","b"]],null,"en"]"""
        assertEquals("first second", parsePayload(raw, TranslationProvider.GOOGLE))
    }
}


class CotTranslateLlmRouteTest {
    private class FakeHandle(
        blockKey: String,
        provider: TranslationProvider,
        route: (suspend (String) -> String)?,
    ) {
        val controller = ReasoningTranslationController(
            blockKey = blockKey,
            provider = provider,
            targetLanguage = "zh",
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            http = { _, _, _, _ -> null },
            publish = {},
            llmTranslate = route,
        )
    }

    @Test
    fun `llm route translates complete fragments through the scripted route`() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val handle = FakeHandle(
            "blk1", TranslationProvider.LLM,
            route = { text ->
                calls += text
                "译文(${text.take(5)})"
            },
        )
        handle.controller.update("hello world\n\nsecond para", running = false)
        kotlinx.coroutines.delay(200)
        // Both fragments went through the LLM route (not the anonymous HTTP).
        assertEquals(listOf("hello world", "second para"), calls)
        assertTrue(TranslationStore.hasCached("blk1", "hello world"))
    }

    @Test
    fun `llm route failure falls back to original text with failed flag`() = kotlinx.coroutines.runBlocking {
        val handle = FakeHandle(
            "blk2", TranslationProvider.LLM,
            route = { _ -> throw IllegalStateException("quota exceeded") },
        )
        var last: ReasoningTranslationController.CotTranslationState? = null
        // Rebuild with a publish capture.
        val c = ReasoningTranslationController(
            blockKey = "blk2", provider = TranslationProvider.LLM, targetLanguage = "zh",
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            http = { _, _, _, _ -> null }, publish = { last = it },
            llmTranslate = { throw IllegalStateException("quota exceeded") },
        )
        c.update("hello world", running = false)
        kotlinx.coroutines.delay(200)
        assertTrue(last!!.failed)
        assertTrue("original preserved on failure", last!!.text.contains("hello world"))
        assertEquals("no cache entry on failure", null, TranslationStore.cached("blk2", "hello world"))
    }

    @Test
    fun `anonymous route is untouched when provider is not LLM`() = kotlinx.coroutines.runBlocking {
        // Provider BING + route lambda set: the lambda must NOT be called.
        var called = false
        val c = ReasoningTranslationController(
            blockKey = "blk3", provider = TranslationProvider.BING, targetLanguage = "zh",
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            http = { _, _, _, _ -> org.json.JSONArray().put(org.json.JSONArray().put(org.json.JSONArray().put("anon-译"))).toString() },
            publish = {},
            llmTranslate = { called = true; "should-not-be-used" },
        )
        // translateOnce parses via parsePayload which expects the Bing OBJECT
        // shape — feed the array to make http return a malformed body → Failed.
        c.update("hello", running = false)
        kotlinx.coroutines.delay(200)
        assertFalse("route lambda must not run for anonymous providers", called)
    }

    // ── queue / failure semantics (T-cot-translate-queue) ────────────────

    @Test
    fun `fragments arriving while pending are translated by the running job`() = kotlinx.coroutines.runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val c = ReasoningTranslationController(
            blockKey = "blk-queue", provider = TranslationProvider.LLM, targetLanguage = "zh",
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            http = { _, _, _, _ -> null }, publish = {},
            llmTranslate = { text ->
                gate.await()
                calls += text
                "TR(${text.take(4)})"
            },
        )
        // First update starts a job that suspends inside the route (gate).
        c.update("first para\n\nsecond para", running = true)
        // A new paragraph arrives while pending — it must be picked up by
        // the SAME job from the newest split, not dropped.
        c.update("first para\n\nsecond para\n\nthird para", running = false)
        gate.complete(Unit)
        kotlinx.coroutines.delay(300)
        assertEquals(listOf("first para", "second para", "third para"), calls)
        assertTrue(TranslationStore.hasCached("blk-queue", "third para"))
    }

    @Test
    fun `failure publishes honest state and blocks new work until retry`() = kotlinx.coroutines.runBlocking {
        var attempts = 0
        var last: ReasoningTranslationController.CotTranslationState? = null
        val c = ReasoningTranslationController(
            blockKey = "blk-fail", provider = TranslationProvider.LLM, targetLanguage = "zh",
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            http = { _, _, _, _ -> null }, publish = { last = it },
            llmTranslate = {
                attempts++
                throw IllegalStateException("boom")
            },
        )
        c.update("a\n\nb", running = false)
        kotlinx.coroutines.delay(100)
        assertTrue(last!!.failed)
        assertFalse("pending must be honest after failure", last!!.pending)
        assertEquals(1, attempts)
        // More content while failed: display tracks it, no auto-retry storm.
        c.update("a\n\nb\n\nc", running = false)
        kotlinx.coroutines.delay(100)
        assertEquals(1, attempts)
        // Explicit retry resumes the pipeline.
        c.retry("a\n\nb\n\nc", running = false)
        kotlinx.coroutines.delay(100)
        assertEquals(2, attempts)
    }

    @Test
    fun `stream end completes the tail and translates it`() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val c = ReasoningTranslationController(
            blockKey = "blk-tail", provider = TranslationProvider.LLM, targetLanguage = "zh",
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            http = { _, _, _, _ -> null }, publish = {},
            llmTranslate = { text ->
                calls += text
                "TR(${text.take(4)})"
            },
        )
        // While streaming, only the first paragraph is complete.
        c.update("done para\n\ntail typing", running = true)
        // The stream ends without a length change; running=false completes
        // the tail, which must now go through the route.
        c.update("done para\n\ntail typing", running = false)
        kotlinx.coroutines.delay(200)
        assertEquals(listOf("done para", "tail typing"), calls)
        assertTrue(TranslationStore.hasCached("blk-tail", "tail typing"))
    }
}
