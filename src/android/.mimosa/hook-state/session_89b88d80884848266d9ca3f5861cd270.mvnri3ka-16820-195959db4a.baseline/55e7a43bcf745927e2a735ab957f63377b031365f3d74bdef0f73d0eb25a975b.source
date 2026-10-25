package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-mindmap-team-import] mind-map → org structure parser. */
class MindMapTeamImportTest {

    @Test
    fun mermaidMindmapParsesRoomAndMembers() {
        val map = """
            mindmap
              root((产品团队))
                PM[产品经理]
                Architect[架构师]
                Dev[工程师]
                QA[测试工程师]
        """.trimIndent()
        val team = MindMapTeamImport.parse(map)
        assertNotNull(team)
        assertEquals("产品团队", team!!.roomName)
        assertEquals(4, team.members.size)
        assertEquals("产品经理", team.members[0].name)
        assertTrue("mermaid duty should have been extracted", team.members[0].description.isNotEmpty())
    }

    @Test
    fun plainIndentedTreeParses() {
        val tree = """
            产品团队
              产品经理 — 定需求划优先级
              架构师
              工程师
        """.trimIndent()
        val team = MindMapTeamImport.parse(tree)
        assertNotNull(team)
        assertEquals("产品团队", team!!.roomName)
        assertEquals(3, team.members.size)
        assertEquals("定需求划优先级", team.members[0].description)
    }

    @Test
    fun subTeamBecomesUnitWithLead() {
        val map = """
            mindmap
              root((公司))
                R&D[研发中心]
                  FE[前端工程师]
                  BE[后端工程师]
                Ops[运维]
        """.trimIndent()
        val team = MindMapTeamImport.parse(map)
        assertNotNull(team)
        assertEquals(4, team!!.members.size)
        val lead = team.members.first { it.name == "研发中心" }
        assertTrue(lead.isSubTeamLead)
        assertEquals("", lead.unit)
        val fe = team.members.first { it.name == "前端工程师" }
        assertEquals("研发中心", fe.unit)
    }

    @Test
    fun missingDutyGetsDeterministicFallback() {
        val team = MindMapTeamImport.parse("Team\n  QA\n  内容")
        assertNotNull(team)
        val qa = team!!.members.find { it.name == "QA" }
        assertNotNull(qa)
        assertTrue("QA fallback should mention 质量", qa!!.description.contains("质量"))
        val content = team.members.find { it.name == "内容" }
        assertTrue(content!!.description.isNotBlank())
    }

    @Test
    fun emptyOrNoMembersReturnsNull() {
        assertNull(MindMapTeamImport.parse(""))
        assertNull(MindMapTeamImport.parse("onlyroot"))
        assertNull(MindMapTeamImport.parse("   \n  "))
    }

    @Test
    fun doubleParenthesisDutyIsExtracted() {
        val map = "mindmap\n  root((Navy))\n    CO[指挥官]"
        val team = MindMapTeamImport.parse(map)
        assertNotNull(team)
        assertEquals("Navy", team!!.roomName)
        assertEquals(1, team.members.size)
    }

    @Test
    fun defaultDutyIsKeywordAware() {
        assertTrue(MindMapTeamImport.defaultDuty("测试工程师").contains("质量"))
        assertTrue(MindMapTeamImport.defaultDuty("产品经理").contains("需求"))
        assertTrue(MindMapTeamImport.defaultDuty("架构师").contains("设计"))
        assertFalse(MindMapTeamImport.defaultDuty("未知角色").isBlank())
    }
}