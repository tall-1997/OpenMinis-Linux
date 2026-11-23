package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-standing-rules-authority] Standing rules must read as binding CONSTRAINTS,
 * and the prompt must never order the model to go re-read something mid-task.
 *
 * Two failures this pins:
 *
 * 1. **Authority.** The rules line used to be a bare inventory ("the X blocks in
 *    this prompt are …"), which the model can read as background. It also sat
 *    behind six dynamic-tail fragments with the persona reminder LAST — most
 *    recent wins — so persona tone outranked the rules that scope the chat.
 *
 * 2. **Fragmentation.** The old wording contained imperatives: "re-read them
 *    before starting any multi-step or destructive action", "Do not assume you
 *    have read them", "ask the user or re-read the file", and the task anchor's
 *    "re-read it if a long tool loop made you lose track". The model obeyed and
 *    emitted progress prose between tool calls — the same mechanism #434 fixed
 *    on the tool-result side, still live on the system-prompt side.
 *
 * Every string asserted here must be a STATE DECLARATION. No verbs addressed to
 * the model.
 */
class StandingRulesAuthorityTest {

    @Test
    fun `the rules line declares binding force without ordering an action`() {
        val line = StandingContract.rulesLine(hasSession = true, hasGlobal = true, hasProject = false)
        requireNotNull(line) { "rules line must exist when rules are present" }

        assertTrue("must state the blocks are in force: $line", line.contains("IN FORCE"))
        assertTrue("must state binding-ness: $line", line.contains("binding"))
        assertTrue("must resolve the persona conflict: $line", line.contains("persona"))

        // The fragmentation contract: no instruction the model can execute.
        listOf("re-read", "Re-read", "Do not assume", "ask the user", "before acting", "Remember to", "You must")
            .forEach { assertFalse("rules line must not order an action ($it): $line", line.contains(it)) }
    }

    @Test
    fun `omitted rules are stated as absent, not as an action to take`() {
        val line = StandingContract.rulesLine(
            hasSession = false,
            hasGlobal = false,
            hasProject = false,
            omittedBlocks = listOf("session-scoped rules (/var/minis/memory/GLOBAL.md)"),
        )
        requireNotNull(line) { "omitted note must survive even with no rules present" }

        assertTrue("must name what is missing: $line", line.contains("not present"))
        listOf("Do not assume", "ask the user", "re-read", "before acting")
            .forEach { assertFalse("omitted note must not order an action ($it): $line", line.contains(it)) }
    }

    @Test
    fun `the task anchor header does not tell the model to re-read`() {
        // [T-standing-rules-authority] HEADER is private to the anchor object,
        // so assert on the PRODUCTION SOURCE rather than reading a private const.
        val source = ProductionSources.read("ui/chat/PromptAnchors.kt")
        val start = source.indexOf("const val HEADER")
        assertTrue("HEADER must exist in PromptAnchors", start >= 0)
        val block = source.substring(start, minOf(start + 400, source.length))
        assertFalse("task anchor must not order a re-read: $block", block.contains("re-read"))
    }

    @Test
    fun `a long persona never squeezes the rules line out`() {
        // MAX_CHARS used to clip from the TAIL, so a long persona survived and
        // the rules line — plus the omission warning — silently vanished.
        val longSoulName = "x".repeat(600)
        val block = StandingContract.block(
            soulName = longSoulName,
            hasSessionRules = true,
            hasGlobalRules = true,
            hasProjectRules = true,
            omittedRules = listOf("project rules (AGENTS.md)"),
        )
        assertTrue("persona must still be present", block.contains("Persona reminder"))
        assertTrue("rules must survive a long persona", block.contains("IN FORCE") || block.contains("standing"))
        assertTrue("omission warning must never be clipped", block.contains("not present"))
    }

    @Test
    fun `the standing memory section carries a constraint header`() {
        val sections = PromptSections.buildSections(
            base = "BASE",
            skillFragment = null,
            mcpFragment = null,
            globalMemoryFragment = "SESSION RULES BODY",
            dynamicTail = "\n\nTAIL",
        )
        val standing = sections.single { it.name == PromptSections.MEMORY_STANDING }
        assertTrue("constraint header must precede the rules: ${standing.content}",
            standing.content.contains("STANDING RULES"))
        assertTrue("the rules body itself must be untouched", standing.content.contains("SESSION RULES BODY"))
        // Section order is byte-frozen by PromptSectionsGoldenTest; this only
        // proves the header is additive.
        assertTrue("base section must stay first", sections.first().name == PromptSections.SYSTEM_TOOLS)
        assertTrue("tail section must stay last", sections.last().name == PromptSections.RUNTIME_TAIL)
    }
}
