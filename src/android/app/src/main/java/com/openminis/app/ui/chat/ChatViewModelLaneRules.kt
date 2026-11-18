package com.openminis.app.ui.chat

import com.openminis.app.data.repository.InstructionDedupe
import com.openminis.app.data.repository.MemoryPromptBudget
import com.openminis.app.data.repository.ProjectInstructionStore

/**
 * [T-lanes-inherit-session-rules] The standing-rules block re-assembled for
 * the DELEGATION lanes — sub-agent workers, group-chat speakers, and the
 * supervisor. Before this existed, `spawn_agent`, group chat, and supervisor
 * prompts were hardcoded in their own subsystems and none of them carried the
 * session rules: the user's "本会话规则" bound the coordinator only, and every
 * dispatched worker silently ignored it. The user decision (2026-11-18): all
 * lanes inherit.
 *
 * This mirrors the parent prompt's standing-rules assembly — same three
 * loaders (project AGENTS.md → session GLOBAL.md → app GLOBAL.md), same
 * [InstructionDedupe] narrowest-scope collapse, same enveloped fragments, and
 * the SAME [MemoryPromptBudget] discipline: an oversized GLOBAL.md is
 * truncated with the visible rules marker instead of shipping an unbounded
 * lane system prompt (small-model workers blow their context window before
 * the coordinator does, so "not budgeted here" was a real defect). The one
 * deliberate difference is ORDER: the lane's own identity framing reads first
 * and the inherited rules after (see [laneSystemWithRules]) — workers anchor
 * on their role before they read the session's constraints.
 *
 * Pure assembly over fingerprint-cached loaders: cheap enough to call at
 * every spawn / every group turn. Null when the session has no rules at all
 * (then the lane prompt is used byte-identical to before).
 */
internal fun ChatViewModel.sessionRulesForLanes(): String? {
    val memorySessionId = com.openminis.app.sandbox.ExecutionCoordinator.ownerSessionId(
        realSessionId.ifEmpty { sessionId },
    )
    val sessionMem = sessionMemoryRepo()
    val projectResolved = ProjectInstructionStore.resolve(context.filesDir, memorySessionId)
    val kept = InstructionDedupe.firstSeen(
        listOf(
            "project-rules" to projectResolved?.content,
            "session-global" to sessionMem.loadGlobalMemoryBody(sessionScoped = true),
            "app-global" to memoryRepository?.loadGlobalMemoryBody(sessionScoped = false),
        ),
    )
    val fragments = listOfNotNull(
        if ("project-rules" in kept) {
            ProjectInstructionStore.fragment(context.filesDir, memorySessionId)
        } else null,
        if ("session-global" in kept) {
            sessionMem.loadGlobalMemoryFragment(sessionScoped = true)
        } else null,
        if ("app-global" in kept) {
            memoryRepository?.loadGlobalMemoryFragment(sessionScoped = false)
        } else null,
    )
    if (fragments.isEmpty()) return null
    val allocation = MemoryPromptBudget.allocate(
        fragments.mapIndexed { i, text ->
            MemoryPromptBudget.Fragment(
                key = listOf("project-rules", "session-global", "app-global")[i],
                text = text,
                priority = listOf(
                    MemoryPromptBudget.PRIORITY_PROJECT,
                    MemoryPromptBudget.PRIORITY_SESSION,
                    MemoryPromptBudget.PRIORITY_APP,
                )[i],
                // Non-truncatable = the binding-rules marker wording when cut
                // ([T-rules-never-silently-vanish] semantics, same as parent).
                truncatable = false,
            )
        },
    )
    return allocation.rendered
}

/**
 * Prepends the session rules to a lane's own system prompt, or returns the
 * base prompt untouched when the session has no rules. Single choke point for
 * the group-chat / supervisor call sites so the inherited-block framing stays
 * identical across lanes. Order: the lane's own identity/role framing FIRST,
 * inherited rules after — the same direction the sub-agent worker prompt
 * reads (identity → rules), so every lane gets the same instruction recency.
 */
internal fun ChatViewModel.laneSystemWithRules(base: String): String =
    sessionRulesForLanes()?.let { rules -> "$base\n\n$rules" } ?: base
