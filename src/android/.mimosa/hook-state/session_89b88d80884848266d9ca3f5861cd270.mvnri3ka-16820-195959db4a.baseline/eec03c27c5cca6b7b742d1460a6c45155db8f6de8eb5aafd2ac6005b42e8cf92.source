package com.openminis.app.sandbox

import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import java.io.File

/**
 * Cross-session chat reads stay behind an explicit `session_read` grant.
 * Audit lines record ids and the action only — never message bodies.
 */
object SessionAccessPolicy {
    const val GRANT = "session_read"

    fun owner(sessionId: String?): String? =
        sessionId?.takeIf { it.isNotBlank() }?.let { SessionWorkspace.ownerSessionId(it) }

    fun isForeign(caller: String?, target: String): Boolean {
        val owner = owner(target) ?: target
        val self = caller?.let { owner(it) } ?: return true
        return owner != self
    }

    /**
     * Whether [caller] may read chat sessions it does not own.
     *
     * Deliberately routed through [OffloadPermissionManager] instead of a
     * bespoke flag: the agent CLI and the in-app tools then share one prompt,
     * one remembered answer per chat session, and the same "deny in this
     * session" that stops an agent from nagging after the user said no.
     * `session_read` must stay registered in that manager's toolRegistry — an
     * unknown tool falls through to BYPASS there, which would make this a
     * silent no-op.
     */
    suspend fun isCrossSessionGranted(caller: String): Boolean =
        OffloadPermissionManager.checkPermission(GRANT, "Read other chats", caller)
}

object SessionAccessAudit {
    @Volatile
    var dir: File? = null

    fun record(
        caller: String?,
        action: String,
        target: String?,
        allowed: Boolean,
        viaGrant: Boolean,
    ) {
        val line = "caller=${caller ?: "-"} action=$action target=${target ?: "-"} " +
            "allowed=$allowed grant=$viaGrant"
        runCatching { AppLogger.info("SessionAccess", line) }
        val root = dir ?: return
        runCatching {
            root.mkdirs()
            File(root, "session-access.log").appendText("${System.currentTimeMillis()} $line\n")
        }
    }
}
