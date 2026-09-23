package com.openminis.app.sandbox

import com.openminis.app.logging.AppLogger
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
