package com.openminis.app.security

import android.content.Context
import com.openminis.app.notification.ApprovalNotifier
import com.openminis.app.service.ApprovalGate
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide gate + persisted mode/rules. ApprovalGate is only the UI wait.
 */
object SecurityGateHolder {
    val gate: SecurityGateImpl = SecurityGateImpl()

    private const val PREFS = "security_gate"
    private const val KEY_MODE = "permission_mode"
    private const val KEY_RULES = "permission_rules"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = p.getString(KEY_MODE, PermissionMode.ASK.name) ?: PermissionMode.ASK.name
        gate.setPermissionMode(runCatching { PermissionMode.valueOf(mode) }.getOrDefault(PermissionMode.ASK))
        val raw = p.getString(KEY_RULES, "[]") ?: "[]"
        val rules = mutableListOf<PermissionRule>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                rules += PermissionRule(
                    action = o.optString("action"),
                    toolFilter = o.optString("toolFilter", "*"),
                    pattern = o.optString("pattern", ""),
                )
            }
        }
        gate.setPermissionRules(rules)
    }

    fun persist(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        p.putString(KEY_MODE, gate.getPermissionMode().name)
        val arr = JSONArray()
        // rules are not exposed from impl; persist is called after setMode/setRules
        p.apply()
    }

    fun setMode(context: Context, mode: PermissionMode) {
        gate.setPermissionMode(mode)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
    }

    fun setRules(context: Context, rules: List<PermissionRule>) {
        gate.setPermissionRules(rules)
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject()
                    .put("action", r.action)
                    .put("toolFilter", r.toolFilter)
                    .put("pattern", r.pattern),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RULES, arr.toString()).apply()
    }

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
        val sessionAllowAll = ApprovalGate.isSessionAllowAll()
        val mode = effectivePermissionMode(gate.getPermissionMode(), sessionAllowAll)
        val decision = gate.withCallerSession(callerSessionId) { gate.decide(cmd, mode) }
        gate.audit(cmd, decision, null)
        return when (decision) {
            is Decision.Allow -> null
            is Decision.Denied -> {
                // Explicit deny rules still win, and so do the isolation
                // boundaries: allow-all speeds up the user's own work, it does
                // not hand one chat another chat's files.
                if (sessionAllowAll && !decision.hard && !decision.reason.startsWith("规则拒绝")) {
                    return null
                }
                InterceptFeedback.publishDenied(canonical, decision.reason)
                ToolExecutionResult(
                    "SecurityGate denied before start: ${decision.reason}. Command was not started.",
                    false,
                    toolTitle = canonical,
                )
            }
            is Decision.NeedConfirm -> {
                val preview = decision.preview.take(240).ifBlank { decision.reason }
                val id = ApprovalGate.requestApproval(canonical, preview)
                // Empty id = auto-approved by the session allow-all switch; no
                // card, no notification — just execute.
                if (id.isEmpty()) return null
                ApprovalNotifier(context).notifyApproval(
                    id,
                    canonical,
                    preview,
                )
                val approved = ApprovalGate.waitFor(id)
                ApprovalNotifier.cancelApproval(context, id)
                if (!approved) {
                    InterceptFeedback.publishRejected(canonical, "User rejected or timed out")
                    ToolExecutionResult(
                        "User rejected or timed out $canonical",
                        false,
                        toolTitle = canonical,
                    )
                } else {
                    null
                }
            }
        }
    }
}
