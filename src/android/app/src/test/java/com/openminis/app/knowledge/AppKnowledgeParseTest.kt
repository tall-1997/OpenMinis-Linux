package com.openminis.app.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Frontmatter parsing of APP.md knowledge packs: full packs, missing fields, malformed YAML
 * lines (skipped, not fatal), required fields (packages, body, closed frontmatter), list
 * parsing and the injection-text cap.
 */
class AppKnowledgeParseTest {

    private val full = """
        ---
        name: demo
        display: Demo App
        description: A demo pack
        packages: ["com.example.demo"]
        aliases: ["Demo", "demo app"]
        ---

        ## Page structure
        - line one
        - line two

        ## Common entries
        - settings via gear icon
    """.trimIndent()

    @Test
    fun parsesFullFrontmatter() {
        val e = AppKnowledge.parse("demo", full)
        assertEquals("demo", e.id)
        assertEquals("demo", e.name)
        assertEquals("Demo App", e.display)
        assertEquals("A demo pack", e.description)
        assertEquals(listOf("com.example.demo"), e.packages)
        assertEquals(listOf("Demo", "demo app"), e.aliases)
        assertTrue(e.body.startsWith("## Page structure"))
    }

    @Test
    fun nameAndDisplayFallBackToId() {
        val text = """
            ---
            packages: ["com.example.x"]
            ---
            body line
        """.trimIndent()
        val e = AppKnowledge.parse("demoapp", text)
        assertEquals("demoapp", e.name)
        assertEquals("demoapp", e.display)
    }

    @Test
    fun missingPackagesFails() {
        try {
            AppKnowledge.parse("demo", "---\ndisplay: X\n---\nbody")
            fail("expected missing packages to throw")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun unterminatedFrontmatterFails() {
        try {
            AppKnowledge.parse("demo", "---\npackages: [\"a\"]\nno closing fence\nbody")
            fail("expected unterminated frontmatter to throw")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun emptyBodyFails() {
        try {
            AppKnowledge.parse("demo", "---\npackages: [\"a\"]\n---\n   \n")
            fail("expected empty body to throw")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun malformedAndUnknownLinesAreSkipped() {
        val text = """
            ---
            # a comment line
            packages: ["com.example.ok"]
            this line has no colon
            : no key either
            unknown_key: whatever
            display: Ok App
            ---
            body
        """.trimIndent()
        val e = AppKnowledge.parse("demo", text)
        assertEquals(listOf("com.example.ok"), e.packages)
        assertEquals("Ok App", e.display)
        assertEquals("", e.description)
    }

    @Test
    fun quotedScalarsAndListsWithSpacesParse() {
        val text = """
            ---
            display: "Quoted App"
            packages: ["com.a.b", "com.c.d"]
            aliases: ["One Two", "three"]
            ---
            body
        """.trimIndent()
        val e = AppKnowledge.parse("demo", text)
        assertEquals("Quoted App", e.display)
        assertEquals(listOf("com.a.b", "com.c.d"), e.packages)
        assertEquals(listOf("One Two", "three"), e.aliases)
    }

    @Test
    fun crlfIsNormalized() {
        val e = AppKnowledge.parse("demo", full.replace("\n", "\r\n"))
        assertEquals("Demo App", e.display)
        assertTrue(e.body.contains("## Common entries"))
    }

    @Test
    fun injectionTextHasSeparatorHeaderAndCap() {
        val e = AppKnowledge.parse("demo", full)
        val text = AppKnowledge.injectionText(e)
        assertTrue(text.startsWith("---\n[App knowledge: Demo App]\n"))
        assertTrue(text.length <= AppKnowledge.MAX_INJECT_CHARS)

        val huge = e.copy(body = "x".repeat(10_000))
        val capped = AppKnowledge.injectionText(huge)
        assertTrue(capped.length <= AppKnowledge.MAX_INJECT_CHARS)
    }

    @Test
    fun indexAndLookups() {
        val alt = """
            ---
            name: altpack
            display: Other Pack
            packages: ["com.other.app"]
            aliases: ["other"]
            ---
            ## Page structure
            - other line
        """.trimIndent()
        val knowledge = AppKnowledge { listOf("demo" to full, "alt" to alt) }
        assertEquals("demo", knowledge.forPackage("COM.EXAMPLE.DEMO")?.id)
        assertEquals("alt", knowledge.forPackage("com.other.app")?.id)
        assertEquals("demo", knowledge.find("Demo")?.id)
        assertEquals("demo", knowledge.find("demo app")?.id)
        assertEquals("alt", knowledge.find("other")?.id)
        // Substring fallback: a 2+ char query contained in a name hits the pack.
        assertEquals("demo", knowledge.find("emo")?.id)
        assertNull(knowledge.forPackage("com.unknown.pkg"))
        assertNull(knowledge.find(""))
        assertNull(knowledge.find("z"))
        assertNull(knowledge.guideFor("definitely-not-covered"))
        val guide = knowledge.guideFor("com.example.demo")
        assertTrue(guide!!.startsWith("---\n[App knowledge: Demo App]\n## Page structure"))
    }
}
