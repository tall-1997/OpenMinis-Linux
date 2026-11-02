package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest is the data half of the dynamic-lane feature, so the contract
 * that matters is "a bad document behaves like an absent document". Every
 * test here is a refusal case or an acceptance case; the parser returning
 * null on any violation is what keeps a half-loaded lane list out of the
 * seed pass, the purge pass and the health store.
 */
class FreeLaneManifestTest {

    private fun zenEntry(extra: String = "") = """
        {
          "id": "zen",
          "behavior": "zen",
          "instanceId": "builtin-opencode-zen",
          "label": "OpenCode Zen (Free)",
          "baseURL": "https://opencode.ai/zen/v1",
          "credentialConvention": "fixed",
          "credentialValue": "public",
          "offlineSeedIds": ["deepseek-v4.1-flash-free", "big-pickle"]
          $extra
        }
    """.trimIndent()

    private fun kiloEntry() = """
        {
          "id": "kilo",
          "behavior": "kilo",
          "instanceId": "builtin-kilo",
          "label": "Kilo (Free)",
          "baseURL": "https://api.kilo.ai/api/gateway",
          "credentialConvention": "none",
          "offlineSeedIds": ["kilo-auto/free"]
        }
    """.trimIndent()

    private fun openaiEntry() = """
        {
          "id": "demo-free",
          "behavior": "openaiCompat",
          "instanceId": "builtin-demo-free",
          "label": "Demo (Free)",
          "baseURL": "https://demo.example.com/v1",
          "credentialConvention": "accountToken",
          "offlineSeedIds": ["demo-free-model"]
        }
    """.trimIndent()

    private fun parseSingle(entryJson: String): List<ManifestLaneEntry>? =
        FreeLaneManifest.parse("""{"version": 1, "lanes": [$entryJson]}""")

    @Test
    fun zenEntryParsesToFixedConvention() {
        val entries = parseSingle(zenEntry())!!
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("zen", e.id)
        assertEquals("builtin-opencode-zen", e.instanceId)
        assertEquals("https://opencode.ai/zen/v1", e.baseURL)
        assertEquals(CredentialConvention.FIXED, e.credentialConvention)
        assertEquals("public", e.credentialValue)
        assertEquals(listOf("deepseek-v4.1-flash-free", "big-pickle"), e.offlineSeedIds)
    }

    @Test
    fun kiloEntryParsesToNoneConvention() {
        val e = parseSingle(kiloEntry())!!.single()
        assertEquals(CredentialConvention.NONE, e.credentialConvention)
    }

    @Test
    fun openaiCompatEntryParsesToAccountToken() {
        val e = parseSingle(openaiEntry())!!.single()
        assertEquals(CredentialConvention.ACCOUNT_TOKEN, e.credentialConvention)
        assertEquals("demo-free", e.id)
    }

    @Test
    fun openaiCompatFixedEntryNeedsCredentialValue() {
        val fixed = openaiEntry()
            .replace("\"credentialConvention\": \"accountToken\",", "\"credentialConvention\": \"fixed\",")
            .replace("\"baseURL\": \"https://demo.example.com/v1\",", "\"baseURL\": \"https://demo.example.com/v1\", \"credentialValue\": \"public\",")
        assertEquals("public", parseSingle(fixed)!!.single().credentialValue)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val withExtras = zenEntry(extra = """, "futureField": {"nested": true}""")
        assertEquals(1, parseSingle(withExtras)!!.size)
    }

    @Test
    fun wrongVersionIsRefused() {
        assertNull(FreeLaneManifest.parse("""{"version": 2, "lanes": [${zenEntry()}]}"""))
        assertNull(FreeLaneManifest.parse("""{"lanes": [${zenEntry()}]}"""))
    }

    @Test
    fun missingOrEmptyLanesIsRefused() {
        assertNull(FreeLaneManifest.parse("""{"version": 1}"""))
        assertNull(FreeLaneManifest.parse("""{"version": 1, "lanes": []}"""))
        assertNull(FreeLaneManifest.parse("""{"version": 1, "lanes": "not-an-array"}"""))
    }

    @Test
    fun malformedJsonIsRefused() {
        assertNull(FreeLaneManifest.parse("{not json"))
        assertNull(FreeLaneManifest.parse(""))
        assertNull(FreeLaneManifest.parse("[]"))
    }

    @Test
    fun duplicateInstanceIdIsRefused() {
        val doc = """{"version": 1, "lanes": [${zenEntry()}, ${zenEntry()}]}"""
        assertNull(FreeLaneManifest.parse(doc))
    }

    @Test
    fun entryWithoutBuiltinInstanceIdPrefixIsRefused() {
        val bad = openaiEntry().replace("builtin-demo-free", "my-demo-free")
        assertNull(parseSingle(bad))
    }

    @Test
    fun entryWithBlankFieldsIsRefused() {
        assertNull(parseSingle(zenEntry().replace("\"id\": \"zen\",", "\"id\": \"\",")))
        assertNull(parseSingle(zenEntry().replace("\"label\": \"OpenCode Zen (Free)\",", "\"label\": \"\",")))
        assertNull(parseSingle(zenEntry().replace("\"behavior\": \"zen\",", "\"behavior\": \"\",")))
    }

    @Test
    fun unknownBehaviorIsRefused() {
        assertNull(parseSingle(zenEntry().replace("\"behavior\": \"zen\",", "\"behavior\": \"zendetta\",")))
    }

    @Test
    fun openaiCompatWithoutBaseURLIsRefused() {
        assertNull(parseSingle(openaiEntry().replace("\"baseURL\": \"https://demo.example.com/v1\",", "")))
    }

    @Test
    fun fixedConventionWithoutCredentialValueIsRefused() {
        assertNull(parseSingle(zenEntry().replace("\"credentialValue\": \"public\",", "\"credentialValue\": \"\",")))
    }

    @Test
    fun unknownConventionIsRefused() {
        assertNull(parseSingle(zenEntry().replace("\"credentialConvention\": \"fixed\",", "\"credentialConvention\": \"rotating\",")))
    }

    @Test
    fun conventionDefaultsToFixedWhenOmitted() {
        val noConvention = zenEntry().replace("\"credentialConvention\": \"fixed\",", "")
        assertEquals(CredentialConvention.FIXED, parseSingle(noConvention)!!.single().credentialConvention)
    }

    @Test
    fun aRefusedEntryRefusesTheWholeDocument() {
        // One bad entry must not yield a half-lane list.
        val doc = """{"version": 1, "lanes": [${zenEntry()}, ${openaiEntry().replace("builtin-demo-free", "user-demo")}]}"""
        assertNull(FreeLaneManifest.parse(doc))
    }

    @Test
    fun blankSeedIdsAreDropped() {
        val withBlanks = openaiEntry().replace(
            "\"offlineSeedIds\": [\"demo-free-model\"]",
            "\"offlineSeedIds\": [\"demo-free-model\", \"  \", \"\"]",
        )
        assertEquals(listOf("demo-free-model"), parseSingle(withBlanks)!!.single().offlineSeedIds)
    }

    @Test
    fun missingSeedIdsDefaultsToEmpty() {
        val noSeeds = openaiEntry().replace(" \"offlineSeedIds\": [\"demo-free-model\"]\n", "")
        assertTrue(parseSingle(noSeeds)!!.single().offlineSeedIds.isEmpty())
    }
}
