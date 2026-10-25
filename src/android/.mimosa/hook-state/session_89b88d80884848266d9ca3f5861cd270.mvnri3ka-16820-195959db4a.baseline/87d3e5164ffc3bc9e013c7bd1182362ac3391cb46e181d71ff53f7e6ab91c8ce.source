package com.openminis.app.tools

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsWebSearchTest {
    @Test
    fun `makeAgentTools exposes web_search`() {
        val names = AgentTools.makeAgentTools().map { it.name }
        assertTrue(names.contains(WebSearchTool.NAME))
    }
}
