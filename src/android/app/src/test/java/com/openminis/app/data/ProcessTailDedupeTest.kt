package com.openminis.app.data

import com.openminis.app.ui.chat.reuseProcessTail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [T-process-tail-content-dedupe] Content-level dedupe of the host-injected
 * process tail.
 *
 * The tail is sent once per request in the system prompt (not grafted onto the
 * history, see [ProcessInjectionArchitectureTest]). When none of the injected
 * blocks changed between requests, the previous string must be reused so the
 * tail bytes are identical — a stable cache suffix and no re-render churn.
 */
class ProcessTailDedupeTest {

    @Test
    fun `unchanged content reuses the previous instance`() {
        val first = "<work_report>\nstage: working\n</work_report>"
        val second = reuseProcessTail(first, cached = first)
        assertSame("identical bytes must reuse the cached string", first, second)
        assertEquals(first, second)
    }

    @Test
    fun `changed content produces the fresh string`() {
        val old = "<plan_board>\n- [todo] step 1\n</plan_board>"
        val fresh = "<plan_board>\n- [done] step 1\n- [todo] step 2\n</plan_board>"
        val out = reuseProcessTail(fresh, cached = old)
        assertNotSame(old, out)
        assertEquals(fresh, out)
    }

    @Test
    fun `no cache yields the fresh string`() {
        val raw = "## Recalled relevant memories\n- **2026-11-01.md**: line"
        assertEquals(raw, reuseProcessTail(raw, cached = null))
    }
}
