package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [pi-port §2.4] Five-level tool exposure gate: schema membership for every
 * level, the zero-change guarantee for the pre-existing (direct) registry,
 * and the find_tools enablement path for deferred tools.
 */
class ToolExposureGateTest {

    private fun def(name: String, exposure: String?): AgentToolDefinition =
        AgentToolDefinition(name = name, description = "test tool $name", parameters = emptyMap(), exposure = exposure)

    private val fiveLevels = listOf(
        def("t_direct", ToolExposureGate.DIRECT),
        def("t_null", null),
        def("t_model_only", ToolExposureGate.MODEL_ONLY),
        def("t_deferred", ToolExposureGate.DEFERRED),
        def("t_codemode", ToolExposureGate.CODEMODE),
        def("t_hidden", ToolExposureGate.HIDDEN),
    )

    // ── filterForSchema: each level ──

    @Test
    fun directAndNullAreDeclared() {
        val schema = ToolExposureGate.filterForSchema(fiveLevels, activeSet = emptySet())
        assertTrue("t_direct" in schema.map { it.name })
        assertTrue("t_null" in schema.map { it.name })
    }

    @Test
    fun modelOnlyIsDeclared() {
        // pi: model-only = declared to the model (UI display is a consumer
        // concern). It must not be dropped from the schema by the gate.
        val schema = ToolExposureGate.filterForSchema(fiveLevels, activeSet = emptySet())
        assertTrue("t_model_only" in schema.map { it.name })
    }

    @Test
    fun deferredIsOutOfSchemaUntilEnabled() {
        assertFalse("t_deferred" in ToolExposureGate.filterForSchema(fiveLevels, emptySet()).map { it.name })
        assertTrue(
            "t_deferred" in ToolExposureGate.filterForSchema(
                fiveLevels, activeSet = setOf("t_deferred"),
            ).map { it.name },
        )
    }

    @Test
    fun codemodeAndHiddenAreNeverDeclared() {
        // Even explicit activation (find_tools enablement) must not declare
        // codemode or hidden tools.
        val active = setOf("t_codemode", "t_hidden")
        val names = ToolExposureGate.filterForSchema(fiveLevels, active).map { it.name }
        assertFalse("t_codemode" in names)
        assertFalse("t_hidden" in names)
    }

    // ── pi spelling normalization + fail-open unknowns ──

    @Test
    fun piHyphenSpellingNormalizesToModelOnly() {
        assertEquals(ToolExposureGate.MODEL_ONLY, ToolExposureGate.levelOf(def("x", "model-only")))
    }

    @Test
    fun unknownExposureFailsOpenAsDirect() {
        assertEquals(ToolExposureGate.DIRECT, ToolExposureGate.levelOf(def("x", "somewhere-else")))
        assertTrue("x" in ToolExposureGate.filterForSchema(listOf(def("x", "somewhere-else")), emptySet()).map { it.name })
    }

    // ── the existing registry: zero behaviour change ──

    /** The tools AgentTools stamps as deferred (see deferredExposureTools). */
    private val expectedDeferred = setOf(SshTool.NAME, JobTools.NAME, PresentTool.NAME)

    @Test
    fun everyPreExistingToolDefaultsToDirectExposure() {
        val registry = AgentTools.makeAgentTools(
            supportsImageInput = true,
            visionGroupConfigured = true,
            memoryEnabled = true,
            subAgentEnabled = true,
            codeGraphEnabled = true,
            mcpNativeEnabled = true,
            hasSpeechModel = true,
            includeLongTail = true,
        )
        assertTrue("registry must not be empty", registry.isNotEmpty())
        val stamped = registry.filter { it.exposure != null && it.exposure != ToolExposureGate.DIRECT }
        assertEquals(
            "only the deliberately deferred long-tail tools may carry a non-direct exposure",
            expectedDeferred,
            stamped.map { it.name }.toSet(),
        )
        // Everything else — the historical 45+ tools — stays null (= direct);
        // execute_code_js is the one tool that declares "direct" explicitly.
        registry.filter { it.name !in expectedDeferred }.forEach {
            assertTrue(
                "tool ${it.name} must stay direct (null or 'direct', got ${it.exposure})",
                it.exposure == null || it.exposure == ToolExposureGate.DIRECT,
            )
        }
    }

    @Test
    fun deferredToolsAreMarkedInTheBuiltRegistry() {
        val registry = AgentTools.makeAgentTools(includeLongTail = true)
        expectedDeferred.forEach { name ->
            assertEquals(
                ToolExposureGate.DEFERRED,
                ToolExposureGate.levelOf(registry.first { it.name == name }),
            )
        }
    }

    @Test
    fun fullModeStillDeclaresDeferredTools() {
        // Full mode keeps the historical all-tools schema: the active set names
        // every registered tool, so deferred stays declared there.
        val registry = AgentTools.makeAgentTools(includeLongTail = true)
        val declared = ToolExposureGate.filterForSchema(registry, registry.map { it.name }.toSet())
        assertEquals(registry.map { it.name }.toSet(), declared.map { it.name }.toSet())
    }

    // ── deferred × find_tools enablement (the existing long-tail path) ──

    @Test
    fun longTailModeHidesDeferredToolsUntilFindToolsEnablesThem() {
        val core = AgentTools.makeAgentTools(includeLongTail = false).map { it.name }
        expectedDeferred.forEach { name ->
            assertFalse("$name is long-tail: not in the core schema before find_tools", name in core)
        }

        // find_tools enablement: the enabled name rides enabledToolNames into
        // the schema — the deferred level expresses exactly this existing path.
        val enabled = AgentTools.makeAgentTools(
            includeLongTail = false,
            enabledToolNames = expectedDeferred,
        ).map { it.name }
        expectedDeferred.forEach { name ->
            assertTrue("$name enabled by find_tools must be declared", name in enabled)
        }
    }

    @Test
    fun findToolsSelfIsAlwaysDeclared() {
        assertTrue(FindTools.NAME in AgentTools.makeAgentTools(includeLongTail = false).map { it.name })
    }

    // ── codemode callable set ──

    @Test
    fun codemodeCallableSetExcludesModelOnlyAndHiddenOnly() {
        val callable = ToolExposureGate.callableNamesForCodemode(fiveLevels)
        assertEquals(setOf("t_direct", "t_null", "t_deferred", "t_codemode"), callable)
    }

    @Test
    fun executeCodeJsIsCallableFromCodemodeScripts() {
        // Bootstrap: the codemode entry tool itself stays callable from a
        // codemode script (pi: codemode tools list direct tools too).
        val registry = AgentTools.makeAgentTools(includeLongTail = true)
        assertTrue(CodemodeTool.NAME in ToolExposureGate.callableNamesForCodemode(registry))
    }
}
