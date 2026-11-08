package com.openminis.app.ui.chat

/**
 * [T-subagent-owner-sid] Owner-session id for a sub-agent tool call. The
 * batch-level session wins (most specific identity the spawn carried), then
 * the parent VM's persisted id, then the declared session, then the UI-active
 * session.
 *
 * Sub-agent tool calls MUST carry this id into
 * [com.openminis.app.security.SecurityGateHolder.intercept]: a blank caller
 * degrades the gate to ASK and turns every write / execute into a prompt even
 * in a YOLO session. All-blank input stays blank — the gate's ASK fallback for
 * an unknown identity is intentional injection defence, so no id is invented
 * here.
 */
internal fun subAgentOwnerSid(
    batchSession: String,
    realSessionId: String,
    declaredSessionId: String,
    activeSessionId: String,
): String = batchSession.ifBlank {
    realSessionId.ifBlank { declaredSessionId }.ifBlank { activeSessionId }
}
