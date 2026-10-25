package com.openminis.app.evolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionJsonTest {

    @Test
    fun parseRuleFromFencedJson() {
        val raw = """```json
{"rule":"- Prefer apt","skip":false}
```"""
        assertEquals(listOf("- Prefer apt"), EvolutionJson.parseRules(raw))
    }

    @Test
    fun skipTrueYieldsNothing() {
        assertTrue(EvolutionJson.parseRules("""{"skip":true,"rule":"- no"}""").isEmpty())
        assertNull(EvolutionJson.parseSkillPatch("""{"skip":true,"patch":"x"}"""))
    }

    @Test
    fun parseSkillPatch() {
        val patch = EvolutionJson.parseSkillPatch("""{"patch":"Always cd to workspace first","skip":false}""")
        assertEquals("Always cd to workspace first", patch)
    }

    @Test
    fun parseReflectItems() {
        val raw = """{"items":[{"action":"retract","rule":"Prefer vim","evidence":"don't use vim"}],"skip":false}"""
        val items = EvolutionJson.parseReflect(raw)
        assertEquals(1, items.size)
        assertEquals("retract", items[0].action)
        assertEquals("Prefer vim", items[0].rule)
        assertTrue(EvolutionJson.parseReflect("""{"skip":true,"items":[{"action":"retract","rule":"x"}]}""").isEmpty())
    }
}
