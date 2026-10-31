package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [P2-convergence] SoulMDParser.parse moved onto the shared Frontmatter
 * reader; these tests pin the SOUL.md-side tolerances that had to survive the
 * merge: pass-through without frontmatter, case-insensitive keys, the
 * data-URI icon value with its colons, and the leading-newline-only body trim.
 */
class SoulMDParserFrontmatterTest {

    @Test
    fun fileWithoutFrontmatterPassesThroughAsBody() {
        val source = "just personality text\nmore\n"
        val file = SoulMDParser.parse(source)
        assertEquals(SoulMetadata.DEFAULT, file.metadata)
        // The ORIGINAL source is the body, not the leading-blank-trimmed one.
        assertEquals(source, file.body)
    }

    @Test
    fun unterminatedFrontmatterFallsBackToDefault() {
        val source = "---\nname: Broken\nno closing delimiter"
        val file = SoulMDParser.parse(source)
        assertEquals(SoulMetadata.DEFAULT, file.metadata)
        assertEquals(source, file.body)
    }

    @Test
    fun keysMatchCaseInsensitivelyAndQuotedValuesAreStripped() {
        val file = SoulMDParser.parse("---\nNAME: \"Minis\"\nStyle: concise\nLANG: zh\n---\nBody line\n")
        assertEquals("Minis", file.metadata.name)
        assertEquals("concise", file.metadata.style)
        assertEquals("zh", file.metadata.lang)
        assertEquals("Body line\n", file.body)
    }

    @Test
    fun iconDataUriSurvivesItsColons() {
        val file = SoulMDParser.parse(
            "---\nname: \"A\"\nicon: \"data:image/png;base64,AAAA\"\n---\nB",
        )
        assertEquals("data:image/png;base64,AAAA", file.metadata.icon)
    }

    @Test
    fun serializeThenParseRoundTrips() {
        val file = SoulFile(SoulMetadata(name = "Minis", emoji = "", icon = "", style = "s", lang = "auto"), "body\n")
        val text = SoulMDParser.serialize(file)
        assertEquals(file, SoulMDParser.parse(text))
    }
}
