package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-announced-tools-core] The core schema (long tail off) must carry every tool
 * the system prompt NAMES.
 *
 * A fragment that says "advance items with agent_plan" or "use the cronjob tool"
 * is an instruction; `find_tools` only helps a model that already suspects the
 * tool exists, so an announced-but-hidden tool is a dead instruction. The plan
 * board is the case that shipped broken: the board rendered for the user, the
 * prompt told the model to move it, and `agent_plan` sat behind find_tools — so
 * the user watched a stale "step 2 of 4" on work that had long since merged, and
 * no board could be started from the main session at all (only sub-agent
 * dispatch filed entries).
 *
 * Source-level drift is caught by scripts/ci/check_announced_tools.py, which
 * scans prompt fragments for registry tool names; these tests pin the contract
 * from the other side (schema contents), so a refactor of the prompt cannot
 * silently drop a tool the fragments still announce.
 */
class AgentToolsCoreSchemaTest {

    /** Every gate off: what a plain main session with long-tail loading gets. */
    private fun coreNames(
        memoryEnabled: Boolean = false,
        subAgentEnabled: Boolean = false,
        codeGraphEnabled: Boolean = false,
        mcpNativeEnabled: Boolean = false,
        enabledToolNames: Set<String> = emptySet(),
    ): Set<String> = AgentTools.makeAgentTools(
        supportsImageInput = false,
        memoryEnabled = memoryEnabled,
        subAgentEnabled = subAgentEnabled,
        codeGraphEnabled = codeGraphEnabled,
        mcpNativeEnabled = mcpNativeEnabled,
        includeLongTail = false,
        enabledToolNames = enabledToolNames,
    ).map { it.name }.toSet()

    // ── announced tools are unconditional ──

    @Test
    fun agentPlanIsCoreSoTheUserVisibleBoardCanMove() {
        assertTrue(
            "the board fragment instructs the model to call agent_plan; without a schema slot " +
                "the board the user watches can never advance (or be created)",
            AgentPlanTool.NAME in coreNames(),
        )
    }

    @Test
    fun toolListAnnouncedToolsSurviveEveryGateBeingOff() {
        val names = coreNames()
        for (expected in listOf(
            CronJobTool.NAME,
            EvolutionTool.NAME,
            SessionLookupTool.SEARCH,
            SessionLookupTool.READ,
            "update_goal",
            "ask_user_question",
        )) {
            assertTrue("$expected is named by a prompt fragment but missing from the core schema", expected in names)
        }
    }

    @Test
    fun evolutionStaysCoreWhenMemoryIsOff() {
        // The learned-rules fragment carries evolution guidance and is NOT gated
        // by memoryOn (same standing as SOUL.md), so the tool cannot ride the
        // memory flag.
        assertTrue(EvolutionTool.NAME in coreNames(memoryEnabled = false))
        assertTrue(EvolutionTool.NAME in coreNames(memoryEnabled = true))
    }

    // ── gated tools follow their fragment's gate ──

    @Test
    fun memoryToolsFollowTheMemoryFragment() {
        assertFalse("memory_write" in coreNames(memoryEnabled = false))
        assertFalse("memory_get" in coreNames(memoryEnabled = false))
        assertTrue("memory_write" in coreNames(memoryEnabled = true))
        assertTrue("memory_get" in coreNames(memoryEnabled = true))
    }

    @Test
    fun dispatchToolsFollowTheSubAgentFragment() {
        assertFalse(SubAgentKind.SPAWN_AGENT in coreNames(subAgentEnabled = false))
        assertTrue(SubAgentKind.SPAWN_AGENT in coreNames(subAgentEnabled = true))
        // A background dispatch hands back a dispatch_id and tells the model to
        // call check_agent — stranding that tool would strand the wave.
        assertTrue(CheckAgentTool.NAME in coreNames(subAgentEnabled = true))
    }

    @Test
    fun mcpFollowsTheServerFragment() {
        assertFalse(McpNativeTool.NAME in coreNames(mcpNativeEnabled = false))
        assertTrue(McpNativeTool.NAME in coreNames(mcpNativeEnabled = true))
    }

    // ── long tail stays long tail ──

    @Test
    fun unannouncedToolsStayBehindFindTools() {
        val names = coreNames(memoryEnabled = true, subAgentEnabled = true)
        // Documented long-tail: nothing in the prompt names these, so they are
        // discovered on demand and the core schema stays small.
        for (hidden in listOf("ssh_exec", "ocr_image", "get_screen_time", "execute_code")) {
            assertFalse("$hidden should stay long-tail (no fragment announces it)", hidden in names)
        }
        assertTrue(FindTools.NAME in names)
    }

    @Test
    fun discoveredNamesAreAddedWithoutTouchingTheCoreSet() {
        val names = coreNames(enabledToolNames = setOf("ssh_exec"))
        assertTrue("ssh_exec" in names)
        assertTrue(AgentPlanTool.NAME in names)
    }

    @Test
    fun fullSchemaStillContainsEveryCoreTool() {
        // Same gate configuration on both sides: the full schema is a superset
        // of the core set, so enabling the long tail never drops a tool.
        val full = AgentTools.makeAgentTools(
            memoryEnabled = true,
            subAgentEnabled = true,
            codeGraphEnabled = true,
            mcpNativeEnabled = true,
            includeLongTail = true,
        ).map { it.name }.toSet()
        val core = coreNames(
            memoryEnabled = true,
            subAgentEnabled = true,
            codeGraphEnabled = true,
            mcpNativeEnabled = true,
        )
        for (name in core) {
            if (name == FindTools.NAME) continue // only appended in long-tail mode
            assertTrue("$name is core but missing from the full schema", name in full)
        }
        assertTrue(CodeGraphTool.NAME in core)
    }

    // ── [T-rule-allowlist-enforce] per-rule tool whitelist ──

    @Test
    fun allowlistKeepsOnlyTheNamedToolsInBothModes() {
        val wanted = setOf("shell_execute", "file_read")
        val full = AgentTools.makeAgentTools(includeLongTail = true, allowedTools = wanted)
            .map { it.name }.toSet()
        assertTrue(full == wanted)
        val core = AgentTools.makeAgentTools(includeLongTail = false, allowedTools = wanted)
            .map { it.name }.toSet()
        assertTrue(core == wanted)
    }

    @Test
    fun emptyAllowlistMeansUnrestricted() {
        val unrestricted = AgentTools.makeAgentTools(includeLongTail = false, allowedTools = null)
            .map { it.name }.toSet()
        val emptySet = AgentTools.makeAgentTools(includeLongTail = false, allowedTools = emptySet())
            .map { it.name }.toSet()
        // RuleValidation preserves an all-unknown allowedTools list as empty —
        // that must stay "unrestricted", not "no tools at all".
        assertTrue(unrestricted == emptySet)
        assertTrue(FindTools.NAME in emptySet)
    }

    @Test
    fun whitelistedRunCannotDiscoverToolsOutsideItsSet() {
        // find_tools itself is subject to the cut: without it in the whitelist
        // an unattended run can neither declare nor discover outside its set.
        val names = AgentTools.makeAgentTools(
            includeLongTail = false,
            allowedTools = setOf("shell_execute"),
        ).map { it.name }.toSet()
        assertFalse(FindTools.NAME in names)
        val withDiscovery = AgentTools.makeAgentTools(
            includeLongTail = false,
            allowedTools = setOf("shell_execute", FindTools.NAME),
        ).map { it.name }.toSet()
        assertTrue(FindTools.NAME in withDiscovery)
    }

    @Test
    fun subAgentExtrasInheritTheParentWhitelist() {
        val parent = setOf("shell_execute", "grep")
        val extras = AgentTools.makeSubAgentExtraTools(parent).map { it.name }
        // grep_source / execute_code / web_fetch … are outside the parent's
        // set and must be dropped from the lane's schema too.
        assertTrue(extras == listOf(GrepTool.NAME))
        val unrestricted = AgentTools.makeSubAgentExtraTools(null).map { it.name }
        assertTrue(GrepSourceTool.NAME in unrestricted)
        assertTrue(ExecuteCodeTool.NAME in unrestricted)
    }
}
