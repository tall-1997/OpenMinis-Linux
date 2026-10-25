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
    private val sessionAllowAll = ConcurrentHashMap.newKeySet<String>()
    private val sessionAllowedTools = ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var legacyBoundSessionId: String? = null

    data class ApprovalRequest(
        val id: String,
        val toolName: String,
        val preview: String,
        val timestamp: Long = System.currentTimeMillis(),
        val sessionId: String? = null,
    )

    private fun legacySession(): String? = legacyBoundSessionId

    fun enableSessionAllowAll() { enableSessionAllowAll(legacySession()) }
    fun enableSessionAllowAll(sessionId: String?) {
        sessionId?.let { sessionAllowAll.add(it) }
        Log.i(TAG, "session allow-all enabled session=$sessionId")
    }

    fun allowToolForSession(toolName: String) { allowToolForSession(toolName, legacySession()) }
    fun allowToolForSession(toolName: String, sessionId: String?) {
        val sid = sessionId ?: return
        sessionAllowedTools.computeIfAbsent(sid) { ConcurrentHashMap.newKeySet() }.add(toolName)
    }

    fun isSessionAllowAll(): Boolean = isSessionAllowAll(legacySession())
    fun isSessionAllowAll(sessionId: String?): Boolean = sessionId?.let(sessionAllowAll::contains) == true

    fun isToolAllowedForSession(toolName: String): Boolean =
        isToolAllowedForSession(toolName, legacySession())
    fun isToolAllowedForSession(toolName: String, sessionId: String?): Boolean {
        val sid = sessionId ?: return false
        return sessionAllowAll.contains(sid) || sessionAllowedTools[sid]?.contains(toolName) == true
    }

    fun resetSessionAllowAll() { resetSessionAllowAll(legacySession()) }
    fun resetSessionAllowAll(sessionId: String?) {
        val sid = sessionId ?: return
        sessionAllowAll.remove(sid)
        sessionAllowedTools.remove(sid)
    }

    /** Compatibility default for old APIs; explicit session-aware calls never read this pointer. */
    fun bindSession(sessionId: String?) { legacyBoundSessionId = sessionId }

    private val _pendingApprovals = MutableStateFlow<Map<String, ApprovalRequest>>(emptyMap())
    val pendingApprovals: StateFlow<Map<String, ApprovalRequest>> = _pendingApprovals.asStateFlow()
    private val pendingBySession = ConcurrentHashMap<String, MutableStateFlow<Map<String, ApprovalRequest>>>()
    private val emptySessionApprovals = MutableStateFlow<Map<String, ApprovalRequest>>(emptyMap()).asStateFlow()

    fun pendingApprovals(sessionId: String?): StateFlow<Map<String, ApprovalRequest>> =
        sessionId?.let {
            pendingBySession.computeIfAbsent(it) { sid ->
                MutableStateFlow(approvalDetails.filterValues { request -> request.sessionId == sid })
            }.asStateFlow()
        } ?: emptySessionApprovals

    fun isConfigured(): Boolean = true

    fun requestApproval(): String = requestApproval(legacySession(), "", "", false)

    fun requestApproval(toolName: String, preview: String, mustPrompt: Boolean = false): String =
        requestApproval(legacySession(), toolName, preview, mustPrompt)

    fun requestApproval(sessionId: String?, toolName: String, preview: String, mustPrompt: Boolean = false): String {
        val sid = sessionId
        if (!mustPrompt && isToolAllowedForSession(toolName, sid)) return ""
        if (sessionAllowAllSkipsPrompt(isSessionAllowAll(sid), mustPrompt)) return ""
        val id = UUID.randomUUID().toString()
        pending[id] = MutableStateFlow(null)
        approvalDetails[id] = ApprovalRequest(id, toolName, preview, sessionId = sid)
        refreshPendingBroadcast()
        return id
    }

    suspend fun waitFor(id: String, sessionId: String? = null): Boolean {
        if (id.isEmpty()) return true
        val detail = approvalDetails[id] ?: return false
        if (sessionId != null && detail.sessionId != sessionId) return false
        val flow = pending[id] ?: return false
        return try { withTimeout(TIMEOUT_MS) { flow.first { it != null } == true } }
        catch (_: TimeoutCancellationException) { deny(id, sessionId); false }
        catch (_: Exception) { deny(id, sessionId); false }
    }

    fun approve(id: String, sessionId: String? = null) { resolve(id, true, sessionId) }
    fun deny(id: String, sessionId: String? = null) { resolve(id, false, sessionId) }

    /** The filed request for [id], or null — lets a UI act with the session
     *  the request belongs to even when the current session id has drifted
     *  (draft `__new__…` → persisted UUID mid-turn). */
    fun detailOf(id: String): ApprovalRequest? = approvalDetails[id]

    /**
     * [T-draft-approval-key-drift] Re-key every piece of session-scoped gate
     * state when a draft session is persisted (`__new__…` → real UUID):
     * in-flight requests, allow-all and per-tool grants, and the per-session
     * broadcast flows. Without this, an allow-all granted during the draft
     * window stops matching the very next tool call.
     */
    fun migrateSession(oldSid: String?, newSid: String?) {
        if (oldSid.isNullOrBlank() || newSid.isNullOrBlank() || oldSid == newSid) return
        if (sessionAllowAll.remove(oldSid)) sessionAllowAll.add(newSid)
        sessionAllowedTools.remove(oldSid)?.let { tools ->
            sessionAllowedTools.computeIfAbsent(newSid) { ConcurrentHashMap.newKeySet() }.addAll(tools)
        }
        approvalDetails.keys.toList().forEach { id ->
            approvalDetails.computeIfPresent(id) { _, detail ->
                if (detail.sessionId == oldSid) detail.copy(sessionId = newSid) else detail
            }
        }
        pendingBySession.remove(oldSid)
        refreshPendingBroadcast()
    }

    private fun resolve(id: String, value: Boolean, sessionId: String? = null) {
        val detail = approvalDetails[id] ?: return
        if (sessionId != null && detail.sessionId != sessionId) return
        approvalDetails.remove(id)
        pending.remove(id)?.value = value
        refreshPendingBroadcast()
    }

    private fun refreshPendingBroadcast() {
        _pendingApprovals.value = approvalDetails.toMap()
        pendingBySession.forEach { (sid, flow) ->
            flow.value = approvalDetails.filterValues { it.sessionId == sid }
        }
    }

    fun pendingIds(sessionId: String? = null): List<String> = approvalDetails.values
        .filter { sessionId == null || it.sessionId == sessionId }.map { it.id }

    fun cleanupAll(sessionId: String? = null) {
        val ids = pendingIds(sessionId)
        ids.forEach { resolve(it, false, sessionId) }
        if (sessionId == null) {
            approvalDetails.keys.toList().forEach { approvalDetails.remove(it) }
            pending.clear()
            sessionAllowAll.clear()
            sessionAllowedTools.clear()
            legacyBoundSessionId = null
        } else resetSessionAllowAll(sessionId)
        refreshPendingBroadcast()
    }

    fun pendingCount(): Int = pending.size
}
