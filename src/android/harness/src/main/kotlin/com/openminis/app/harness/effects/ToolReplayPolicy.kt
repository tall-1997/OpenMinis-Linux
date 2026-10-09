package com.openminis.app.harness.effects

import com.openminis.app.harness.HarnessTool
import com.openminis.app.harness.operation.ReplayPolicy

/**
 * Adapted from taixu ToolReplayPolicy (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * Recovery contract for external tool effects.
 */
object ToolReplayPolicy {
    fun forTool(tool: HarnessTool, rawToolName: String? = null): ReplayPolicy = when {
        rawToolName?.startsWith("mcp__") == true -> ReplayPolicy.NEVER
        tool in SAFE_TO_REPLAY -> ReplayPolicy.SAFE
        else -> ReplayPolicy.NEVER
    }

    private val SAFE_TO_REPLAY = setOf(
        HarnessTool.READ,
        HarnessTool.HISTORY_SEARCH,
        HarnessTool.HISTORY_READ,
    )
}
