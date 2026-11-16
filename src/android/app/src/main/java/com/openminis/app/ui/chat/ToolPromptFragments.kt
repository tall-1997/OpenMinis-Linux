package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition

/**
 * [T-tool-prompt-fragments] Single source of truth for the per-tool guidance
 * bullets rendered into the system prompt's "Available tools" section.
 *
 * History this prevents: `UI_SKILL_PROMPT_FRAGMENT` used to live in
 * AgentTools as a constant with ZERO readers — the ui_skill guidance text
 * was never sent to the model, while the tool itself was always registered
 * and dispatchable. A tool that is advertised in the schema but never
 * explained in the prompt is invisible to the model that is supposed to use
 * it. Registry + render() + the pinned test in
 * [ToolPromptFragmentsTest] make "added to the schema but never explained"
 * a test failure instead of a silent gap.
 *
 * Adding a tool with workflow guidance: register a [Fragment] here AND
 * interpolate [render] into the section in ChatViewModelPromptExt (the
 * render call is the single interpolation point). The CI gate
 * check_announced_tools.py covers schema↔prompt NAME parity; this registry
 * covers the prose.
 */
internal object ToolPromptFragments {

    /** One `- <name>: …` line for the Available tools section. */
    data class Fragment(
        val key: String,
        val bullet: String,
    )

    val allFragments: List<Fragment> = listOf(
        Fragment(
            key = "ui_skill",
            bullet = "- ui_skill: Replay previously recorded device-operation skills (recorded via Settings → Accessibility → 录制场景). For REPEATED device flows, try ui_skill action=match then action=replay BEFORE manual ui_read+ui_action chains — replay is label-first, auto-dismisses popups, and waits out loading screens; only fall back to manual ui_action when replay fails or action=match finds nothing. action=list shows every stored skill with its ready/stale state.",
        ),
    )

    /**
     * Render the bullets for the Available tools section. [excluded] lets a
     * caller drop fragments whose tool is not registered this turn
     * (mirror of the makeAgentTools gates) — default keeps everything.
     */
    fun render(excluded: Set<String> = emptySet()): String =
        allFragments.filter { it.key !in excluded }.joinToString("\n") { it.bullet }

    /**
     * [pi-port §4.1 aggregation] Render per-tool guidance straight from the
     * assembled [AgentToolDefinition] list: a definition's `promptSnippet`
     * field is read first, and the registry above stays as the compatibility
     * fallback for tools that have not moved their bullet onto the definition
     * yet (a fragment whose tool now carries its own snippet is not emitted
     * twice). makeAgentTools already produces the definition list, so a new
     * tool declares its guidance in ONE place (the definition) instead of
     * also registering here.
     *
     * Call-site note: the interpolation point is ChatViewModelPromptExt's
     * "Available tools" section — switching it from [render] to this method
     * belongs to the prompt-ext batch and is intentionally not wired here.
     */
    fun renderFromDefinitions(
        definitions: List<AgentToolDefinition>,
        excluded: Set<String> = emptySet(),
    ): String {
        val fromDefinitions = definitions
            .filter { it.name !in excluded && !it.promptSnippet.isNullOrBlank() }
            .joinToString("\n") { it.promptSnippet!! }
        val namesWithSnippet = definitions
            .filter { !it.promptSnippet.isNullOrBlank() }
            .map { it.name }
            .toSet()
        val registryFallback = allFragments
            .filter { it.key !in excluded && it.key !in namesWithSnippet }
            .joinToString("\n") { it.bullet }
        return listOf(fromDefinitions, registryFallback)
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
