package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLookupToolTest {

    @Test
    fun splitQueryDropsShortTokens() {
        assertEquals(listOf("backup", "WebDAV"), SessionLookupTool.splitQuery("  a backup WebDAV  x "))
    }

    @Test
    fun makeAgentToolsExposesSessionLookup() {
        val names = AgentTools.makeAgentTools().map { it.name }
        assertTrue(names.contains(SessionLookupTool.SEARCH))
        assertTrue(names.contains(SessionLookupTool.READ))
    }
}
