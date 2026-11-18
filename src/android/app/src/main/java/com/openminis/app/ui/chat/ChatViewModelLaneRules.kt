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
 * This mirrors the parent prompt's standing-rules assembly exactly — same
 * three loaders (project AGENTS.md → session GLOBAL.md → app GLOBAL.md), same
 * [InstructionDedupe] narrowest-scope collapse, same enveloped fragments — so
 * a lane sees the SAME rules text the coordinator sees. Not budgeted here:
 * the lanes have no daily block competing for the memory budget, and the
 * rules layers are user-authored (the [MemoryPromptBudget] starvation case
 * the parent prompt already guards is about daily history, not lanes).
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
    return fragments.joinToString("\n\n")
}

/**
 * Prepends the session rules to a lane's own system prompt, or returns the
 * base prompt untouched when the session has no rules. Single choke point for
 * the group-chat / supervisor call sites so the inherited-block framing stays
 * identical across lanes.
 */
internal fun ChatViewModel.laneSystemWithRules(base: String): String =
    sessionRulesForLanes()?.let { rules -> "$rules\n\n$base" } ?: base
