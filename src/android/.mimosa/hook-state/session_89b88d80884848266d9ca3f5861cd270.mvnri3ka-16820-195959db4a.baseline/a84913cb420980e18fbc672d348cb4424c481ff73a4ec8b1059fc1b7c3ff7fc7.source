package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-browser-readonly-actions] explore/plan keep browser_use for
 * research but lose its mutating actions. The gate is a pure function of the
 * action string — pinned here: the five mutating actions deny (case
 *-insensitive), the research surface stays open, and unknown actions fail
 * open to the browser tool's own argument validation.
 */
class SubAgentBrowserDenialTest {

    @Test
    fun `mutating actions are denied`() {
        for (action in listOf("click", "type", "hover", "execute_js", "set_cookies")) {
            val denial = SubAgentKind.readOnlyBrowserDenial(action)
            assertNotNull(action, denial)
            assertTrue("denial should name the action", denial!!.contains(action))
        }
    }

    @Test
    fun `denial is case insensitive`() {
        assertNotNull(SubAgentKind.readOnlyBrowserDenial("CLICK"))
        assertNotNull(SubAgentKind.readOnlyBrowserDenial("Execute_JS"))
    }

    @Test
    fun `research surface stays open`() {
        for (action in listOf(
            "navigate", "screenshot", "get_text", "get_readable", "get_backbone",
            "find_elements", "get_page_info", "list_tabs", "scroll",
            "scroll_and_collect", "wait_for_dom_stable", "get_cookies",
            "set_viewport", "set_user_agent", "new_tab", "close_tab", "fetch",
        )) {
            assertNull(action, SubAgentKind.readOnlyBrowserDenial(action))
        }
    }

    @Test
    fun `unknown or blank actions fail open to the tool's own validation`() {
        assertNull(SubAgentKind.readOnlyBrowserDenial(""))
        assertNull(SubAgentKind.readOnlyBrowserDenial("teleport"))
    }

    @Test
    fun `denial message stays actionable`() {
        val msg = SubAgentKind.readOnlyBrowserDenial("click")!!
        assertTrue(msg.contains("read-only"))
        assertTrue(msg.contains("navigate"))
    }
}
