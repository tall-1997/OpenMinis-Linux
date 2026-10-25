package com.openminis.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-local-tool-plugins] The agent-surface mapping of LocalToolPluginStore:
 * enabled local tools become AgentToolDefinitions with a JSON-schema
 * parameter map, a required list (params without defaults) and a stable
 * property ordering.
 */
class LocalToolAgentSurfaceTest {

    private fun tool(
        name: String = "resize_screenshot",
        params: List<LocalToolPluginStore.LocalParam> = emptyList(),
        enabled: Boolean = true,
        description: String = "Resize a screenshot",
        command: String = "convert {{src}} -resize 50% {{dst}}",
    ) = LocalToolPluginStore.LocalToolDef(
        id = "lt-$name",
        name = name,
        description = description,
        params = params,
        command = command,
        enabled = enabled,
        createdAtMs = 1L,
    )

    @Test
    fun `disabled tools are not exposed`() {
        val defs = LocalToolPluginStore.toAgentDefinitions(
            listOf(tool(), tool(name = "off_tool", enabled = false)),
        )
        assertEquals(listOf("resize_screenshot"), defs.map { it.name })
    }

    @Test
    fun `params map to schema entries and the required list`() {
        val defs = LocalToolPluginStore.toAgentDefinitions(
            listOf(
                tool(
                    params = listOf(
                        LocalToolPluginStore.LocalParam(name = "src", description = "input path"),
                        LocalToolPluginStore.LocalParam(
                            name = "dst",
                            description = "out",
                            required = true,
                            defaultValue = "/tmp/out.png",
                        ),
                    ),
                ),
            ),
        )
        val d = defs.single()
        assertEquals(setOf("src", "dst"), d.parameters.keys)
        assertEquals("input path", d.parameters.getValue("src").description)
        assertEquals("string", d.parameters.getValue("src").type)
        // A param with a default is optional: it drops out of `required`.
        assertEquals(listOf("src"), d.required)
        assertEquals(listOf("src", "dst"), d.propertyOrdering)
    }

    @Test
    fun `blank description falls back to the command template`() {
        val defs = LocalToolPluginStore.toAgentDefinitions(listOf(tool(description = "")))
        assertTrue(defs.single().description.contains("convert"))
    }
}
