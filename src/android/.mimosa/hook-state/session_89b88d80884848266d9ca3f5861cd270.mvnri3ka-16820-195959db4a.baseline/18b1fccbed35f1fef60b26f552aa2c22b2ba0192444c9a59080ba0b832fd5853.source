package com.openminis.app.tools

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-sub-agent execution lane. Survives [kotlinx.coroutines.withContext] dispatcher
 * switches so [shell_execute] can dispatch to a dedicated PersistentShell instead of
 * the parent session's one-command-at-a-time process.
 *
 * Id format: `subagent:<ownerSessionId>:<token>` so bind-mounts can recover the
 * parent session directory.
 */
class SubAgentLane(val id: String) : AbstractCoroutineContextElement(SubAgentLane) {
    companion object Key : CoroutineContext.Key<SubAgentLane> {
        const val PREFIX = "subagent:"

        fun idFor(ownerSessionId: String, token: Any): String = "$PREFIX$ownerSessionId:$token"

        fun isLane(sessionId: String): Boolean = sessionId.startsWith(PREFIX)

        fun ownerSessionId(sessionId: String): String {
            if (!isLane(sessionId)) return sessionId
            val rest = sessionId.substring(PREFIX.length)
            val cut = rest.lastIndexOf(':')
            return if (cut > 0) rest.substring(0, cut) else sessionId
        }
    }
}
