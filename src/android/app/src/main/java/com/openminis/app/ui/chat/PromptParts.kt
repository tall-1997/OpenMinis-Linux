package com.openminis.app.ui.chat

/**
 * [T-prompt-parts] One registry for every block the host injects.
 *
 * The blocks grew one at a time — plan board, recall, work report, in-flight
 * lanes — each appended to a list literal in a different corner. There was no
 * single place that answered "what is injected, at which level, in what order",
 * which is exactly the question the prototype's prompt-parts table answers
 * (`docs/004-prompts.md:18-42`): parts have keys, keys have levels, and the order
 * they appear in is a property of the registry, not of whoever edited last.
 *
 * A part is a lazy renderer, not a string: stores change between turns, so the
 * value must be produced at request time (that is what keeps these blocks alive
 * across compaction).
 */
internal data class PromptPart(
    /** Stable key, `runtime/...` like the prototype's overridable parts. */
    val key: String,
    val level: PromptLevel,
    /** Null means "nothing to say this turn" — the block is skipped entirely. */
    val render: () -> String?,
)

internal object PromptParts {

    /** Registry keys in injection order. Tests pin these so a rename is deliberate. */
    val KEYS = listOf(
        "runtime/work_report",
        "runtime/in_flight",
        "runtime/jobs",
        "runtime/plan_board",
        "runtime/recall",
    )

    /**
     * The host-rendered parts, in order.
     *
     * Order is deliberate: facts the host enforces come first (stage, lanes,
     * board), retrieved evidence last, immediately before the caller's text —
     * so the weakest level is never mistaken for the instruction it follows.
     */
    internal fun of(vm: ChatViewModel): List<PromptPart> = listOf(
        PromptPart("runtime/work_report", PromptLevel.TOOL_ENFORCED) { vm.workReportPrompt() },
        PromptPart("runtime/in_flight", PromptLevel.TOOL_ENFORCED) { vm.inFlightLanesPrompt() },
        PromptPart("runtime/jobs", PromptLevel.TOOL_ENFORCED) { vm.backgroundJobsPrompt() },
        PromptPart("runtime/plan_board", PromptLevel.TOOL_ENFORCED) { vm.planBoardPrompt() },
        PromptPart("runtime/recall", PromptLevel.EXTERNAL) { vm.liveRecallPrompt() },
    )

    /** Every key present exactly once, in [KEYS] order. */
    internal fun validate(parts: List<PromptPart>): List<PromptPart> {
        require(parts.map { it.key } == KEYS) {
            "prompt parts drifted from the registry: ${parts.map { it.key }}"
        }
        return parts
    }

    /**
     * Renders every non-empty part, framing and labelling the weak level.
     *
     * The rule line appears once, before the first external part, and only when
     * that part actually produced content.
     */
    internal fun render(parts: List<PromptPart>): List<String> {
        val rendered = parts.mapNotNull { part ->
            part.render()?.takeIf { it.isNotBlank() }?.let { part to it }
        }
        val hasExternal = rendered.any { (part, _) -> part.level == PromptLevel.EXTERNAL }
        val out = mutableListOf<String>()
        PromptPrecedence.ruleFor(hasExternal)?.let { out += it }
        rendered.forEach { (part, body) ->
            out += PromptPrecedence.frame(part.level, body)
        }
        return out
    }
}
