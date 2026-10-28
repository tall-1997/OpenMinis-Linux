package com.openminis.app.security

import org.json.JSONObject

/**
 * [T-android-vscreen] virtual_screen classification, extracted from
 * SecurityGateImpl so the gate file stays inside the architecture policy's
 * 800-line cap. Action split, same shape as classifyJob / classifyTerminal:
 * list + screenshot observe the agent's OWN virtual display (SCREEN
 * capability, so READ_ONLY / PLAN keep them readable and they auto-run as
 * low risk); create/launch/tap/swipe/text/key/destroy drive it (SYSTEM).
 * All actions are REVERSIBLE — the display is agent-owned and destroy
 * restores the prior state. Nothing here touches the user's physical screen.
 */
internal object VirtualScreenGatePolicy {
    val READ_ACTIONS = setOf("list", "screenshot")

    fun isReadOnly(toolArgs: String): Boolean = actionOf(toolArgs) in READ_ACTIONS

    fun classify(name: String, toolArgs: String): GateCommand =
        if (isReadOnly(toolArgs)) {
            GateCommand(
                name, toolArgs, Capability.SCREEN, Reversibility.REVERSIBLE,
                "Read-only virtual screen status/screenshot (agent-owned display)",
            )
        } else {
            GateCommand(
                name, toolArgs, Capability.SYSTEM, Reversibility.REVERSIBLE,
                "Drives an agent-owned virtual display; does not touch the user's physical screen",
            )
        }

    private fun actionOf(toolArgs: String): String =
        try { JSONObject(toolArgs).optString("action", "").lowercase() } catch (_: Exception) { "" }
}
