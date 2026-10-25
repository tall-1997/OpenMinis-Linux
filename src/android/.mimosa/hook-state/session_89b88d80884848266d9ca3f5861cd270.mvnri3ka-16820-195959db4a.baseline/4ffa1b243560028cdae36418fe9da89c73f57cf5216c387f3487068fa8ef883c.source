package com.openminis.app.ui.chat

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.tools.SubAgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSubAgentSpawnTest {

    @Test
    fun missingPromptOrBadJsonIsNull() {
        assertNull(parseSubAgentSpawn("{}", 12))
        assertNull(parseSubAgentSpawn("not-json", 12))
        assertTrue(parseSubAgentBatch("{}", 12).isEmpty())
    }

    @Test
    fun parsesKindAndClampsTurns() {
        val spawn = parseSubAgentSpawn(
            """{"prompt":"look at Foo","kind":"explore","max_turns":99,"role":"search"}""",
            defaultCap = 12,
        )!!
        assertEquals(SubAgentKind.EXPLORE, spawn.kind)
        assertEquals(12, spawn.maxTurns)
        assertEquals("search", spawn.role)
    }

    @Test
    fun parsesTasksArrayInParallel() {
        val batch = parseSubAgentBatch(
            """
            {"tasks":[
              {"prompt":"look at Foo.kt","kind":"explore"},
              {"prompt":"implement Bar","kind":"worker","write_paths":["/var/minis/app"]},
              {"prompt":"design the API","kind":"plan"},
              {"prompt":"whatever leftover","kind":"general-purpose"}
            ]}
            """.trimIndent(),
            defaultCap = 60,
        )
        assertEquals(4, batch.size)
        assertEquals(SubAgentKind.EXPLORE, batch[0].kind)
        assertEquals(10, batch[0].maxTurns)
        assertEquals(SubAgentKind.WORKER, batch[1].kind)
        assertEquals(listOf("/var/minis/app"), batch[1].writePaths)
        assertEquals(40, batch[1].maxTurns)
        assertEquals(SubAgentKind.PLAN, batch[2].kind)
        assertEquals(SubAgentKind.GENERAL, batch[3].kind)
    }

    @Test
    fun parsesStringifiedTasksArray() {
        val batch = parseSubAgentBatch(
            """{"tasks":"[{\"prompt\":\"find crash\",\"kind\":\"explore\"}]"}""",
            defaultCap = 60,
        )
        assertEquals(1, batch.size)
        assertEquals(SubAgentKind.EXPLORE, batch[0].kind)
        assertEquals(10, batch[0].maxTurns)
    }

    @Test
    fun thinkingDefaultsToModelResolutionAndAcceptsExplicitOff() {
        assertNull(parseSubAgentSpawn("{\"prompt\":\"inspect\"}", 20)?.thinkingLevel)
        assertEquals(ThinkingLevel.OFF, parseSubAgentSpawn("{\"prompt\":\"inspect\",\"thinking_level\":\"off\"}", 20)?.thinkingLevel)
        assertNull(parseSubAgentSpawn("{\"prompt\":\"inspect\",\"thinking_level\":\"supreme\"}", 20)?.thinkingLevel)
    }

    @Test
    fun friendlyTitleCoversSpawnAlias() {
        assertEquals("Sub-agent", friendlyToolTitle("run_subagent"))
        assertEquals("Sub-agent", friendlyToolTitle("spawn_agent"))
    }
}
