package com.openminis.app.service

import android.util.Log
import com.openminis.app.security.sessionAllowAllSkipsPrompt
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ApprovalGate {

    private const val TAG = "ApprovalGate"
    private const val TIMEOUT_MS = 90_000L

    private val pending = ConcurrentHashMap<String, MutableStateFlow<Boolean?>>()
    private val approvalDetails = ConcurrentHashMap<String, ApprovalRequest>()

    // Session-scoped allow list. "本次会话全部允许" only auto-approves the
    // same tool name for the rest of this session. Fatal confirms still prompt.
    @Volatile private var sessionAllowAll = false
    private val sessionAllowedTools = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun enableSessionAllowAll() {
        sessionAllowAll = true
        Log.i(TAG, "session allow-all enabled")
    }

    fun allowToolForSession(toolName: String) {
        sessionAllowedTools.add(toolName)
        Log.i(TAG, "session allow-same-tool enabled tool=$toolName")
    }

    fun isSessionAllowAll(): Boolean = sessionAllowAll

    fun isToolAllowedForSession(toolName: String): Boolean =
        sessionAllowAll || sessionAllowedTools.contains(toolName)

    fun resetSessionAllowAll() {
        if (sessionAllowAll || sessionAllowedTools.isNotEmpty()) {
            Log.i(TAG, "session allow-all reset")
        }
        sessionAllowAll = false
        sessionAllowedTools.clear()
    }

    fun bindSession(sessionId: String?) {
        if (boundSessionId == sessionId) return
        resetSessionAllowAll()
        boundSessionId = sessionId
    }

    @Volatile private var boundSessionId: String? = null

    private val _pendingApprovals = MutableStateFlow<Map<String, ApprovalRequest>>(emptyMap())
    val pendingApprovals: StateFlow<Map<String, ApprovalRequest>> = _pendingApprovals.asStateFlow()

    data class ApprovalRequest(
        val id: String,
        val toolName: String,
        val preview: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun isConfigured(): Boolean = true

    fun requestApproval(): String {
        val id = UUID.randomUUID().toString()
        pending[id] = MutableStateFlow(null as Boolean?)
        Log.d(TAG, "approval requested id=$id")
        return id
    }

    /**
     * Returns a pending-approval id, or "" when the request was auto-approved
     * by the session allow-all switch. An empty id never enters [pending] or
     * the broadcast map, so no card / notification is surfaced; [waitFor]
     * treats it as approved.
     */
    fun requestApproval(toolName: String, preview: String, mustPrompt: Boolean = false): String {
        if (!mustPrompt && isToolAllowedForSession(toolName)) {
            Log.d(TAG, "approval auto-allowed (session same-tool) tool=$toolName")
            return ""
        }
        if (sessionAllowAllSkipsPrompt(sessionAllowAll, mustPrompt)) {
            Log.d(TAG, "approval auto-allowed (session allow-all) tool=$toolName")
            return ""
        }
        val id = UUID.randomUUID().toString()
        pending[id] = MutableStateFlow(null as Boolean?)
        approvalDetails[id] = ApprovalRequest(id, toolName, preview)
        refreshPendingBroadcast()
        Log.d(TAG, "approval requested id=$id tool=$toolName")
        return id
    }

    suspend fun waitFor(id: String): Boolean {
        if (id.isEmpty()) return true
        val flow = pending[id] ?: return false
        return try {
            withTimeout(TIMEOUT_MS) {
                flow.first { it != null } == true
            }
        } catch (e: TimeoutCancellationException) {
            Log.i(TAG, "approval timed out -> denied")
            deny(id)
            false
        } catch (e: Exception) {
            Log.w(TAG, "approval wait threw: ${e.message}")
            deny(id)
            false
        }
    }

    fun approve(id: String) {
        resolve(id, true)
        Log.d(TAG, "approved id=$id")
    }

    fun deny(id: String) {
        resolve(id, false)
        Log.d(TAG, "denied id=$id")
    }

    /**
     * Resolves [id] exactly once and drops both its flow and its detail entry.
     *
     * Both maps are cleared regardless of whether the flow was still pending:
     * a caller (or [cleanupAll]) may resolve an id whose flow was already
     * removed, and leaving the [ApprovalRequest] behind would keep it visible
     * in [pendingApprovals] forever. The flow is removed from [pending] first
     * but its terminal value is still delivered — [waitFor] captured the flow
     * reference, so the waiter wakes regardless of the map removal.
     */
    private fun resolve(id: String, value: Boolean) {
        val flow = pending.remove(id)
        approvalDetails.remove(id)
        flow?.value = value
        refreshPendingBroadcast()
    }

    private fun refreshPendingBroadcast() {
        _pendingApprovals.value = pending.keys
            .mapNotNull { id ->
                approvalDetails[id]?.let { request -> id to request }
            }
            .toMap()
    }

    /**
     * Snapshot of the ids that still have a live approval flow. Used by
     * AgentForegroundService to clear the matching notification-bar entries
     * before [cleanupAll] empties the queue (ApprovalGate cannot reach the
     * notification manager itself).
     */
    fun pendingIds(): List<String> = pending.keys.toList()

    /**
     * Denies every pending request so any coroutine blocked in [waitFor] wakes
     * up immediately instead of waiting out its 90 s timeout, then drops any
     * orphaned detail entries and republishes [pendingApprovals].
     */
    fun cleanupAll() {
        val ids = pending.keys.toList()
        Log.d(TAG, "cleanupAll: resolving ${ids.size} pending approvals")
        ids.forEach { id -> resolve(id, false) }
        // Defensive sweep: a detail whose flow already resolved but whose
        // broadcast map was not refreshed would otherwise stay visible.
        approvalDetails.keys.toList().forEach { approvalDetails.remove(it) }
        refreshPendingBroadcast()
        // A torn-down session must not leak the allow-all switch into the next
        // conversation — reset it together with the queue.
        resetSessionAllowAll()
    }

    fun pendingCount(): Int = pending.size
}
