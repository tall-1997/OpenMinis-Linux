package com.openminis.app.security

/**
 * Adapted from XINCODE-Public GateCommand / Decision (GPL-3.0-or-later).
 * https://github.com/kusesad-1122/XINCODE-Public
 */
data class GateCommand(
    val toolName: String,
    val toolArgs: String,
    val capability: Capability,
    val reversibility: Reversibility,
    val why: String,
)

sealed class Decision {
    data class Allow(val reason: String) : Decision()
    data class NeedConfirm(
        val reason: String,
        val preview: String,
        val mustPrompt: Boolean = false,
    ) : Decision()
    /**
     * [hard] marks a denial no permission mode may downgrade: the isolation
     * boundaries (another chat's tree, the app database, unmounted guest
     * paths). Session allow-all and ALLOW_ALL exist to get out of the user's
     * own way — they must not turn *someone else's* data into an allowed read,
     * which is what happened while these denials were indistinguishable from an
     * ordinary mode denial.
     */
    data class Denied(val reason: String, val hard: Boolean = false) : Decision()
}
