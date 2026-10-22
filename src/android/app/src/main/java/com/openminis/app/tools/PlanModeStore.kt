package com.openminis.app.tools

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.SessionWorkspace
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * Session-scoped plan-mode flag: while enabled, the agent explores read-only
 * and must submit a plan via [ExitPlanModeTool] for user approval before
 * executing anything.
 *
 * Mirrors [AgentPlanStore]'s persistence shape on purpose: the session's
 * PRIVATE base dir (never a SHARED_SUBDIR like workspace) so two chats filed
 * into the same project folder never clobber each other's mode flag, and the
 * value survives a cold start. A corrupt file falls back to disabled — unlike
 * the plan board there is nothing to lose here, and a hard error would brick
 * the composer toggle on a bad write.
 */
object PlanModeStore {
    private const val TAG = "PlanModeStore"
    private const val DIR_NAME = "plan-mode"
    private const val FILE_NAME = "mode.json"
    private val memory = ConcurrentHashMap<String, Boolean>()

    /** Same refresh signal contract as [AgentPlanStore.version]. */
    val version = MutableStateFlow(0L)

    private fun key(sessionId: String): String = sessionId.ifBlank { "__default__" }

    private fun file(context: Context?, sessionId: String): File? {
        context ?: return null
        val owner = ExecutionCoordinator.ownerSessionId(key(sessionId))
        return File(File(SessionWorkspace.base(context.filesDir, owner), DIR_NAME), FILE_NAME)
    }

    fun isEnabled(sessionId: String, context: Context?): Boolean {
        val k = key(sessionId)
        memory[k]?.let { return it }
        val f = file(context, k) ?: return false
        val enabled = runCatching {
            JSONObject(AtomicFileWrite.read(f) ?: "{}").optBoolean("enabled", false)
        }.getOrElse { e ->
            AppLogger.warning(TAG, "plan-mode file unreadable at ${f.absolutePath}: ${e.message}")
            false
        }
        memory[k] = enabled
        return enabled
    }

    fun setEnabled(sessionId: String, context: Context?, enabled: Boolean) {
        val k = key(sessionId)
        val f = file(context, k)
        if (f != null) {
            runCatching { AtomicFileWrite.write(f, JSONObject().put("enabled", enabled).toString()) }
                .onFailure { AppLogger.warning(TAG, "plan-mode persist failed for session $k: ${it.message}") }
        }
        memory[k] = enabled
        version.value = version.value + 1
    }

    /**
     * Draft→real key migration. The composer pill writes the flag under the
     * route's draft key, but after [ChatViewModel.ensureSession] the guard and
     * the system prompt read the persisted id — without this, toggling plan
     * mode on a brand-new chat silently evaporates on first send (same drift
     * class as ApprovalGate.migrateSession, T-draft-approval-key-drift).
     * The old file is deleted either way so a reused draft key can't
     * resurrect a stale flag.
     */
    fun migrate(oldSid: String, newSid: String, context: Context?) {
        if (oldSid == newSid || newSid.isBlank()) return
        val oldK = key(oldSid)
        val enabled = memory.remove(oldK) ?: isEnabled(oldSid, context)
        memory.remove(oldK) // isEnabled() re-cached it under the old key
        file(context, oldK)?.takeIf { it.isFile }?.delete()
        if (enabled) setEnabled(newSid, context, true)
    }

    /** System-prompt section injected while the mode is active. */
    fun planModePrompt(): String = buildString {
        append("<plan_mode>\n")
        append(
            "Plan mode is ACTIVE for this chat. Investigate freely with read-only tools " +
                "(file_read, grep, glob, list_dir, web_search, code_graph, ...), but do NOT " +
                "modify files, run state-changing commands, or dispatch agents — mutating " +
                "tools are refused until the plan is approved.\n",
        )
        append(
            "When you understand the task well enough, call exit_plan_mode with a concise " +
                "plan_markdown and an items list (one item per actionable task, 1-20 items). " +
                "The user then approves, requests changes, or rejects. On approval, execute " +
                "the plan and advance the board items with agent_plan as you go.\n",
        )
        append("</plan_mode>")
    }

    /**
     * Live board snapshot for the system prompt so the model sees the current
     * progress every request without a tool round-trip. Empty board renders
     * nothing (no section at all) to keep the prompt lean.
     */
    fun boardSnapshotPrompt(plans: List<AgentPlanStore.Plan>): String? {
        if (plans.isEmpty()) return null
        return buildString {
            append("<plan_board>\n")
            append("Current plan board (advance items with agent_plan; the user sees it live):\n")
            plans.forEachIndexed { i, p ->
                append("${i + 1}. [${p.status}] ${p.title}")
                if (p.description.isNotBlank()) append(" — ${p.description}")
                append("\n")
            }
            append("</plan_board>")
        }
    }
}
