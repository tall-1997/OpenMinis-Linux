package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollabRolesTest {

    @Test
    fun catalogLookupIsCaseInsensitive() {
        val role = CollabRoles.byName("产品经理")
        assertNotNull(role)
        assertEquals(role, CollabRoles.byName("产品经理"))
        assertNull(CollabRoles.byName("Android reviewer"))
        assertNull(CollabRoles.byName(""))
    }

    @Test
    fun productManagerCardHasRoutingAndDontDo() {
        val prompt = CollabRoles.promptFor("产品经理")!!
        assertTrue(prompt.contains("你盯着的东西"))
        assertTrue(prompt.contains("你不管的东西"))
        assertTrue(prompt.contains("什么时候不说话"))
        assertTrue(prompt.contains("@架构师"))
        assertFalse(prompt.contains("invoke_skill"))
    }

    @Test
    fun architectGetsShellButSecretaryDoesNot() {
        assertTrue("shell_execute" in CollabRoles.toolsFor("架构师")!!)
        assertFalse("shell_execute" in CollabRoles.toolsFor("秘书助理")!!)
        assertTrue("file_write" in CollabRoles.toolsFor("秘书助理")!!)
    }

    @Test
    fun filterToolsIntersectsRoleWhitelist() {
        val tools = AgentTools.makeAgentTools(
            supportsImageInput = false,
            visionGroupConfigured = false,
            memoryEnabled = true,
            subAgentEnabled = true,
        )
        val filtered = SubAgentKind.filterTools(SubAgentKind.WORKER, tools, "产品经理")
        val names = filtered.map { it.name }.toSet()
        assertTrue("file_read" in names)
        assertTrue("file_write" in names)
        assertFalse("shell_execute" in names)
        assertFalse("cronjob" in names)
        assertFalse("spawn_agent" in names)
    }

    @Test
    fun importTeamFromMindMapGeneratesCustomRoles() {
        val roles = CollabRoles.importTeamFromMindMap(
            """
            mindmap
              root((产品团队))
                PM[产品经理]
                QA[测试工程师]
                Intern[实习生]
            """.trimIndent(),
        )
        assertEquals(3, roles.size)
        val pm = roles.first { it.name == "产品经理" }
        assertFalse("imported roles are customs, not builtins", pm.builtin)
        assertNotNull("builtin reuse: 产品经理 keeps its catalog prompt", pm.prompt)
        assertTrue("产品经理 is a writer role", pm.tools.contains("file_write"))
        val qa = roles.first { it.name == "测试工程师" }
        assertTrue("测试工程师 gets builder tools", qa.tools.contains("shell_execute"))
        val intern = roles.first { it.name == "实习生" }
        assertTrue("unknown role gets a synthesized prompt", intern.prompt.contains(intern.name))
    }

    @Test
    fun importTeamFromMindMapReturnsEmptyForNonMap() {
        assertTrue(CollabRoles.importTeamFromMindMap("not a mind map at all").isEmpty())
        assertTrue(CollabRoles.importTeamFromMindMap("").isEmpty())
    }

    @Test
    fun mergeImportedCustomReplacesNamesCaseInsensitivelyAndKeepsOthers() {
        val existing = listOf(
            CollabRoles.Role("QA", "old", "old prompt", setOf("file_read"), builtin = false),
            CollabRoles.Role("Reviewer", "keep", "keep prompt", setOf("file_read"), builtin = false),
        )
        val imported = listOf(
            CollabRoles.Role("qa", "new", "new prompt", setOf("file_write"), builtin = false),
            CollabRoles.Role("Engineer", "new role", "engineer prompt", setOf("shell_execute"), builtin = false),
        )

        val merged = CollabRoles.mergeImportedCustom(existing, imported)
        assertEquals(listOf("Reviewer", "qa", "Engineer"), merged.map { it.name })
        assertEquals("new prompt", merged.first { it.name == "qa" }.prompt)
    }

    @Test
    fun duplicateMindMapMemberNamesProduceOnlyOneRole() {
        val roles = CollabRoles.importTeamFromMindMap(
            "Team\n  QA\n  QA",
        )
        assertEquals(1, roles.size)
    }
}
