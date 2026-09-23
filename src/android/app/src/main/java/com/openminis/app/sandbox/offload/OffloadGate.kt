package com.openminis.app.sandbox.offload

import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Tri-state agent gate for `android-*` offload CLIs. Sits at the
 * NativeOffloadHandler entry point so every shell invocation of a CLI —
 * regardless of how the LLM constructed the command — passes through
 * the same BYPASS / ASK_ONCE / NOT_ALLOWED policy stored in
 * [OffloadPermissionManager].
 *
 * Why a helper:
 *   * `NativeOffloadHandler.handle` is synchronous (the offload IPC
 *     server invokes it from a worker thread and writes the result
 *     into the guest's tmp file before replying). [checkPermission]
 *     is suspend (ASK_ONCE awaits user input on a coroutine), so we
 *     bridge with `runBlocking`. The block is fine on this thread —
 *     the IPC reply already gates further guest progress on its
 *     return.
 *   * Every handler shares the same prompt-and-cache flow, so the
 *     boilerplate lives here rather than 5+ copy-pastes.
 *
 * Session scope: T340 — prefer the chat session id forwarded by the
 * agent shell via the `MINIS_CHAT_SESSION_ID` env var (surfaced as
 * [NativeOffloadRequest.sessionId]). Falls back to
 * `OFFLOAD_GLOBAL_SESSION_ID` when the offload runs outside a chat
 * (e.g. interactive terminal). This keeps "Allow in this session"
 * grants from leaking across chat sessions.
 */
internal object OffloadGate {
    /**
     * @return true if the CLI may proceed; false → handler should
     * short-circuit with a PERMISSION_DENIED envelope pointing the
     * user to Settings → Permissions.
     */
    fun allow(toolName: String, displayName: String, sessionId: String? = null): Boolean =
        decide(toolName, displayName, sessionId) ?: false

    /** Overload that pulls the chat session id straight off the request. */
    fun allow(toolName: String, displayName: String, request: NativeOffloadRequest): Boolean =
        allow(toolName, displayName, request.sessionId)

    /**
     * [T-android-offload-hang] Three-valued decision behind [allow]/[enforce]:
     *
     *   true  → allowed, proceed
     *   false → denied by policy, caller should point at Settings
     *   null  → nobody answered the ASK_ONCE prompt within
     *           [OffloadPermissionManager.INTERACTIVE_BUDGET_MS]
     *
     * The third case is what used to hang forever: `checkPermission` suspends
     * on a UI prompt, and this bridge would `runBlocking` on the IPC worker
     * thread until the 120s dialog timeout — with no UI to answer it, every
     * retry left another blocked thread parked behind the same lock.
     */
    private fun decide(toolName: String, displayName: String, sessionId: String?): Boolean? =
        runBlocking {
            withTimeoutOrNull(OffloadPermissionManager.INTERACTIVE_BUDGET_MS) {
                OffloadPermissionManager.checkPermission(
                    toolName,
                    displayName,
                    sessionId ?: OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID,
                )
            }
        }

    /**
     * Convenience: gate the call and return null when allowed, or a
     * pre-formatted envelope when not. Lets a handler write
     * `enforce(...)?.let { return it }` at the top of `handle`.
     */
    fun enforce(
        toolName: String,
        displayName: String,
        args: OffloadArgs,
        request: NativeOffloadRequest? = null,
    ): NativeOffloadResult? = when (decide(toolName, displayName, request?.sessionId)) {
        true -> null
        false -> {
            val body = JSONObject()
                .put("error", "permission_denied")
                .put("message",
                    "Agent is not allowed to use $displayName. Open Settings → Permissions to change.")
                .toString()
            NativeOffloadResult(126, OffloadOutput.formatBody(body, args) + "\n")
        }
        // Distinct code + message: this is not a policy denial, so telling the
        // agent to "change Settings" would send it down the wrong path.
        null -> {
            val body = JSONObject()
                .put("error", "interaction_required")
                .put("tool", displayName)
                .put("budget_ms", OffloadPermissionManager.INTERACTIVE_BUDGET_MS)
                .put("message",
                    "$displayName needs the user to approve a prompt in the app, and nobody " +
                        "answered within ${OffloadPermissionManager.INTERACTIVE_BUDGET_MS / 1000}s. " +
                        "No work was done. Ask the user to approve it, or open Settings → " +
                        "Permissions, then retry.")
                .toString()
            NativeOffloadResult(124, OffloadOutput.formatBody(body, args) + "\n")
        }
    }
}
