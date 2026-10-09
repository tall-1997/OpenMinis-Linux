package com.openminis.app.harness.effects

import com.openminis.app.harness.HarnessTool
import com.openminis.app.harness.operation.ReplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Adapted from taixu HarnessRuntimePolicyTest (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 */
class ToolReplayPolicyTest {

    @Test
    fun `only read-only tools are safe to replay`() {
        assertEquals(ReplayPolicy.SAFE, ToolReplayPolicy.forTool(HarnessTool.READ))
        assertEquals(ReplayPolicy.SAFE, ToolReplayPolicy.forTool(HarnessTool.HISTORY_SEARCH))
        assertEquals(ReplayPolicy.NEVER, ToolReplayPolicy.forTool(HarnessTool.WRITE))
        assertEquals(ReplayPolicy.NEVER, ToolReplayPolicy.forTool(HarnessTool.READ, "mcp__server__read"))
    }
}
