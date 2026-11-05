package com.openminis.app.security

import android.content.Context
import com.openminis.app.notification.ApprovalNotifier
import com.openminis.app.service.ApprovalGate
import com.openminis.app.tools.ToolExecutionResult

/**
 * Process-wide gate + per-session mode registry. ApprovalGate is only the UI wait.
 *
 * [Wave3 #2/#16] The persistent allow/deny permission-rule layer was removed:
 * `setRules`/`persist` had no caller anywhere in the repo and the rules file
 * was never written, so the gate's rule engine always evaluated an empty set —
 * a permanent no-op that still cost a pass over every decision. Behaviour is
 * preserved exactly (rules were always empty ⇒ the engine never fired).
 *
 * [Wave3 #16] The session permission state lives HERE (one map), not also in
 * the gate's global `permissionMode` field. `setActiveSessionMode` no longer
 * writes the global gate getter — that writeback made "the last session to
 * speak" the authority for every other chat reading `gate.getPermissionMode()`.
 * Decisions in [intercept] always resolve the caller's own mode, and callers
 * that decide directly ([ChatViewModelCodeRunExt]) pass an explicit mode, so
 * the global getter has no remaining decision-making consumer.
 */
object SecurityGateHolder {
    val gate: SecurityGateImpl = SecurityGateImpl()

    private const val PREFS = "security_gate"
    private const val KEY_MODE = "permission_mode"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Drop the retired five-mode global default. Tool gate is per-session
        // now; leftover ALLOW_ALL / READ_ONLY / PLAN / DENY_ALL must not
        // apply to upgraded chats before a session VM binds.
        if (p.contains(KEY_MODE)) {
            p.edit().putString(KEY_MODE, PermissionMode.ASK.name).apply()
        }
        activeSessionModes.clear()
        legacyActiveSessionMode = PermissionMode.ASK
        gate.setPermissionMode(PermissionMode.ASK)
    }

    private val activeSessionModes = java.util.concurrent.ConcurrentHashMap<String, PermissionMode>()
    @Volatile private var legacyActiveSessionMode: PermissionMode = PermissionMode.ASK

    fun setActiveSessionMode(mode: PermissionMode, sessionId: String? = null) {
        val normalized = if (mode.isYoyo()) PermissionMode.ALLOW_ALL else PermissionMode.ASK
        if (sessionId.isNullOrBlank()) legacyActiveSessionMode = normalized
        else activeSessionModes[sessionId] = normalized
        // [Wave3 #16] No global gate writeback here any more: with concurrent
        // chats the global getter used to end up holding whichever session set
        // its mode last. Per-session reads go through [activeSessionMode];
        // decisions pass the caller's explicit mode.
    }

    fun activeSessionMode(sessionId: String? = null): PermissionMode =
        if (sessionId.isNullOrBlank()) legacyActiveSessionMode
        else activeSessionModes[sessionId] ?: PermissionMode.ASK

    /**
     * @param callerSessionId the chat that is asking, so the isolation policies
     *   can allow a session to touch its own tree while still refusing every
     *   other one. Callers that do not know it may omit it — a null caller keeps
     *   the deny-anything-private behaviour rather than widening access.
     * @return a failed result to short-circuit, or null to execute the tool.
     */
    suspend fun intercept(
        context: Context,
        name: String,
        argsJson: String,
        callerSessionId: String? = null,
    ): ToolExecutionResult? {
        val canonical = ToolAliases.canonical(name)
        val cmd = gate.classify(canonical, argsJson)
        // Session allow-all is the same decision as global ALLOW_ALL. Applying
        // it here — before any Denied short-circuit — is what stops "本会话全部
        // 允许" from swallowing the command with no dialog.
        val sessionAllowAll = ApprovalGate.isSessionAllowAll(callerSessionId)
        // A missing caller id is intentionally conservative: legacy global mode
        // state must never turn an unbound operation into YOYO.
        val storedMode = callerSessionId?.let(::activeSessionMode) ?: PermissionMode.ASK
        val mode = effectivePermissionMode(storedMode, sessionAllowAll)
        val decision = gate.withCallerSession(callerSessionId) { gate.decide(cmd, mode) }
        gate.audit(cmd, decision, null)
        return when (decision) {
            is Decision.Allow -> null
            is Decision.Denied -> {
                // The isolation boundaries still win: allow-all speeds up the
                // user's own work, it does not hand one chat another chat's
                // files. (Hard denies are never relaxed; the "规则拒绝" escape
                // hatch belonged to the removed rule layer — [Wave3 #2].)
                if (sessionAllowAll && !decision.hard) {
                    return null
                }
                InterceptFeedback.publishDenied(canonical, decision.reason)
                ToolExecutionResult(
                    "SecurityGate denied before start (exit 126): ${decision.reason}. Command was not started.",
                    false,
                    errorCode = com.openminis.app.tools.ToolErrorCode.PERMISSION_DENIED,
                    recoveryHint = "The permission rule for $canonical was denied. Ask the user to allow it in Settings → Permissions, or continue without this tool.",
                    toolTitle = canonical,
                )
            }
            is Decision.NeedConfirm -> {
                val preview = decision.preview.take(240).ifBlank { decision.reason }
                val id = ApprovalGate.requestApproval(
                    callerSessionId,
                    canonical,
                    preview,
                    mustPrompt = decision.mustPrompt,
                )
                // Empty id = auto-approved by the session allow-all switch.
                // Fatal confirms set mustPrompt, so rm -rf / still shows the dialog.
                if (id.isEmpty()) return null
                ApprovalNotifier(context).notifyApproval(
                    id,
                    canonical,
                    preview,
                )
                val approved = ApprovalGate.waitFor(id, callerSessionId)
                ApprovalNotifier.cancelApproval(context, id)
                if (!approved) {
                    // [T-android-rejected-tool-retry-loop] A bare "rejected"
                    // string reads to the model like any transient failure,
                    // so it re-issued the SAME call on the next turn (observed:
                    // deny → immediate `Retry shell echo after 20s`). Make the
                    // verdict unambiguous and forbid retrying the identical
                    // call; the model may still continue the task differently.
                    val verdict = "User REJECTED the approval for $canonical. " +
                        "This is a deliberate refusal, NOT a failure. Do NOT call " +
                        "$canonical with the same or equivalent arguments again. " +
                        "Continue the task without this tool if possible, or state " +
                        "what you cannot do without it."
                    InterceptFeedback.publishRejected(canonical, "用户已拒绝该工具调用")
                    ToolExecutionResult(
                        verdict,
                        false,
                        errorCode = com.openminis.app.tools.ToolErrorCode.PERMISSION_DENIED,
                        recoveryHint = "The user deliberately rejected this call. Do not retry the same arguments; continue differently or ask what they want.",
                        toolTitle = canonical,
                    )
                } else {
                    null
                }
            }
        }
    }
}
