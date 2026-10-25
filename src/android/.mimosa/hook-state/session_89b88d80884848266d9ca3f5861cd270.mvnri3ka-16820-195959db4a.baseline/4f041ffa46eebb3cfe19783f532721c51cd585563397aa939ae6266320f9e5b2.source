package com.openminis.app.sandbox.offload

import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Shared interactive permission flow for the offload handlers that cannot
 * work until the user grants an Android runtime permission (or flips a
 * system settings toggle): system dialog → post-DENY poll → in-app
 * settings gate → exit-77 envelope on either give-up branch.
 *
 * Consolidates eight structurally identical copies (Calendar read/write,
 * Contacts read/delete, Photos media + media-location, Speech mic,
 * Notification POST_NOTIFICATIONS + Notification access, Location) that
 * used to live inline in each handler. The Calendar version was the
 * canonical parameterized form; everything else was copy-paste with the
 * strings swapped.
 *
 * [T-android-offload-hang] The whole chain is bounded on purpose. Handlers
 * run synchronously on an offload worker thread while the guest blocks in
 * read(), so the flow bridges to the UI with `runBlocking` (handlers are
 * invoked from JNI/native dispatch and `handle` is not a suspend fun — the
 * blocking stays, only the duplication went away). The dialog + settings
 * gate are each sized for a user watching the screen (120s + 120s); with
 * no foreground UI that used to hold the thread — and the caller — for
 * ~4 minutes. The outer [OffloadPermissionManager.INTERACTIVE_BUDGET_MS]
 * cap turns an unanswered prompt into a structured "ask the user, then
 * retry" error instead.
 *
 * Per-handler differences (permission set, settings-gate copy, envelope
 * error literals, human-facing messages, log labels) are all explicit
 * parameters — nothing is silently unified. In particular the envelope
 * copy is verbatim per handler: Contacts still emits its older
 * `permission_timeout` literal with the inline budget, and the
 * Notification listener gate emits `notification_access_not_granted`.
 */
internal object OffloadInteractivePermission {

    /**
     * Envelope `error` literals for the give-up branches. Centralized so a
     * grep finds one definition; callers pass them (or the defaults) into
     * [DenialCopy] — never a fresh string literal.
     */
    const val ERROR_PERMISSION_DENIED = "permission_denied"
    const val ERROR_TIMEOUT = "timeout"
    const val ERROR_PERMISSION_TIMEOUT = "permission_timeout"
    const val ERROR_NOTIFICATION_ACCESS_NOT_GRANTED = "notification_access_not_granted"

    /**
     * Verbatim copy for the two exit-77 envelopes a handler emits when the
     * user declines or goes silent. The strings differ per handler on
     * purpose (older handlers shipped before the copy was standardized) —
     * callers supply their exact text; [deniedError]/[timeoutError] default
     * to the common literals.
     */
    data class DenialCopy(
        val deniedMessage: String,
        val timeoutMessage: String,
        val deniedError: String = ERROR_PERMISSION_DENIED,
        val timeoutError: String = ERROR_TIMEOUT,
    )

    /**
     * Full flow for a runtime permission. Returns null when [satisfied] holds
     * (or the user grants within budget) so the caller can proceed; otherwise
     * a ready-to-return exit-77 [NativeOffloadResult].
     *
     * @param logTag        caller's log TAG — the post-DENY poll grant is
     *                      logged under it so per-handler filters keep working
     * @param permissions   permission list for the system dialog
     * @param satisfied     grant check; re-evaluated after a DENIED (grant
     *                      propagation race) and as the settings-gate poll
     * @param gate          in-app settings fallback shown when the dialog
     *                      returns DENIED and the poll doesn't rescue it
     * @param pollGrantedLog exact info log line for the post-DENY rescue
     * @param copy          verbatim envelope copy for the give-up branches
     */
    fun ensureInteractive(
        logTag: String,
        permissions: List<String>,
        satisfied: () -> Boolean,
        gate: OffloadPermissionManager.SettingsGateRequest,
        pollGrantedLog: String,
        copy: DenialCopy,
        args: OffloadArgs,
    ): NativeOffloadResult? {
        if (satisfied()) return null
        val result = runBlocking {
            withTimeoutOrNull(OffloadPermissionManager.INTERACTIVE_BUDGET_MS) {
                var r = OffloadPermissionManager.requestAndroidPermission(permissions)
                if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                    OffloadPermissionManager.pollForPermissionGrant(satisfied)
                ) {
                    AppLogger.info(logTag, pollGrantedLog)
                    r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
                }
                if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                    r = OffloadPermissionManager.requestSettingsGate(gate, check = satisfied)
                }
                r
            }
        } ?: OffloadPermissionManager.AndroidPermissionResult.TIMEOUT
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> deniedResult(copy, args)
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> timeoutResult(copy, args)
        }
    }

    /**
     * Settings-gate-only variant for capabilities with no runtime dialog —
     * the grant lives behind a system settings page (Notification access),
     * so the flow is dialog-and-poll without the initial
     * [OffloadPermissionManager.requestAndroidPermission] round-trip.
     * Same contract as [ensureInteractive].
     */
    fun ensureInteractiveGate(
        gate: OffloadPermissionManager.SettingsGateRequest,
        satisfied: () -> Boolean,
        copy: DenialCopy,
        args: OffloadArgs,
    ): NativeOffloadResult? {
        if (satisfied()) return null
        val result = runBlocking {
            withTimeoutOrNull(OffloadPermissionManager.INTERACTIVE_BUDGET_MS) {
                OffloadPermissionManager.requestSettingsGate(gate, check = satisfied)
            }
        } ?: OffloadPermissionManager.AndroidPermissionResult.TIMEOUT
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> deniedResult(copy, args)
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> timeoutResult(copy, args)
        }
    }

    private fun deniedResult(copy: DenialCopy, args: OffloadArgs): NativeOffloadResult =
        NativeOffloadResult(
            77,
            OffloadOutput.formatBody(
                JSONObject()
                    .put("error", copy.deniedError)
                    .put("message", copy.deniedMessage)
                    .toString(),
                args,
            ) + "\n",
        )

    private fun timeoutResult(copy: DenialCopy, args: OffloadArgs): NativeOffloadResult =
        NativeOffloadResult(
            77,
            OffloadOutput.formatBody(
                JSONObject()
                    .put("error", copy.timeoutError)
                    .put("message", copy.timeoutMessage)
                    .toString(),
                args,
            ) + "\n",
        )
}
