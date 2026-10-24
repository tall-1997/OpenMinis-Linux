package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition

/**
 * Tool surfaces for the two seats of a Supervisor-Worker chat
 * (spec: docs/superpowers/specs/2026-10-24-supervisor-worker-loop-design.md §3.3).
 *
 * The Worker seat is the sub-agent execution surface: it may write files and
 * run shell commands inside the WritePathGuard scope the runner opens, but it
 * must never spawn nested agents or schedule background work — the nesting
 * ban is the same one SubAgentKind enforces for WORKER.
 *
 * The Supervisor seat is the review surface: the PLAN recon list (read tools
 * plus shell recon). File mutation tools are absent by construction, and the
 * runner additionally wraps the seat in WritePathGuard NO_WRITE so a slipped
 * write tool would still be denied at execution time.
 */
object SupervisorSeats {

    /** Worker seat: everything except the nesting-banned control tools. */
    fun workerTools(base: List<AgentToolDefinition>): List<AgentToolDefinition> =
        base.filter { !SubAgentKind.blocks(SubAgentKind.WORKER, it.name) }

    /** Supervisor seat: the PLAN recon surface — evidence, not mutation. */
    fun supervisorTools(base: List<AgentToolDefinition>): List<AgentToolDefinition> =
        base.filter { !SubAgentKind.blocks(SubAgentKind.PLAN, it.name) }
}
