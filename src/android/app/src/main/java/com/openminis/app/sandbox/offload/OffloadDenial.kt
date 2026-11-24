package com.openminis.app.sandbox.offload

import com.openminis.app.offload.OffloadPermissionManager

/**
 * [GH#539] Explains a `PERMISSION_DENIED` from a privileged offload CLI.
 *
 * Two independent layers can refuse `android-a11y-cli` / `android-shizuku-cli`:
 *
 *  1. the Android system layer (Accessibility service enabled, Shizuku running
 *     and authorized), and
 *  2. the Agent permission gate in [OffloadPermissionManager] (BYPASS /
 *     ASK_ONCE / NOT_ALLOWED).
 *
 * The old one-line "Agent is not allowed … change Settings" did not say which
 * layer actually refused, so a user whose Accessibility service was simply off
 * was sent to the wrong settings page, and a user with the service running was
 * not told the refusal was the agent policy rather than the OS. A third state
 * exists too: a CLI missing from this build's permission registry would
 * silently fall through `checkPermission`'s unknown-tool BYPASS branch. The
 * handlers check [OffloadPermissionManager.isRegistered] first, so that
 * mismatch fails closed and is reported as such instead of being disguised as
 * "not allowed".
 */
object OffloadDenial {

    /** State of the Android service the CLI drives (not the agent gate). */
    enum class SystemLayer {
        /** Service connected/authorized: the gate is the only possible refuser. */
        RUNNING,

        /** Service running, but Minis has not been granted access to it. */
        NOT_AUTHORIZED,

        /** Shizuku app is not installed at all. */
        NOT_INSTALLED,

        /** Service installed but not started, or Accessibility service disabled. */
        NOT_RUNNING,
    }

    /**
     * Build the `PERMISSION_DENIED` message for [toolName].
     *
     * @param systemName human name of the Android service ("the Shizuku service").
     * @param systemFix what the user must do to bring that layer up.
     * @param settingsPath in-app path that changes the agent policy.
     */
    fun permissionDenied(
        toolName: String,
        displayName: String,
        systemName: String,
        systemState: SystemLayer,
        systemFix: String,
        settingsPath: String,
    ): String {
        val level = runCatching { OffloadPermissionManager.getLevel(toolName).name }.getOrNull()
        val gate = when (level) {
            // OffloadGate's ASK_ONCE bridge waits INTERACTIVE_BUDGET_MS; an
            // unanswered prompt is reported as a denial, so say so rather than
            // implying the user explicitly chose "Not Allowed".
            "ASK_ONCE" ->
                "level ASK_ONCE — the approval prompt was denied or went unanswered within " +
                    "${OffloadPermissionManager.INTERACTIVE_BUDGET_MS / 1000}s"
            null -> "current policy"
            else -> "level $level"
        }

        if (!OffloadPermissionManager.isRegistered(toolName)) {
            return "$displayName is not registered in this build's agent permission table, " +
                "so the call is refused instead of being silently allowed. This is a CLI/registry " +
                "mismatch in the build — update Minis or report a bug; changing $settingsPath " +
                "cannot create the missing entry."
        }

        return when (systemState) {
            SystemLayer.RUNNING ->
                "$systemName is running, but the Agent permission gate for $displayName " +
                    "refused this call ($gate). Open $settingsPath to allow it — the system " +
                    "service itself needs no attention."

            SystemLayer.NOT_AUTHORIZED ->
                "$systemName is running, but Minis is not authorized for it. $systemFix " +
                    "Separately, the Agent permission gate for $displayName refused this call " +
                    "($gate); both layers have to allow the tool."

            SystemLayer.NOT_INSTALLED ->
                "$systemName is not installed, so $displayName cannot run. $systemFix " +
                    "Separately, the Agent permission gate for $displayName refused this call " +
                    "($gate); both layers have to allow the tool."

            SystemLayer.NOT_RUNNING ->
                "$systemName is not running, so $displayName cannot execute. $systemFix " +
                    "Separately, the Agent permission gate for $displayName refused this call " +
                    "($gate); both layers have to allow the tool."
        }
    }
}
