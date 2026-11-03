package com.openminis.app.mcp.server

import android.content.Context
import com.openminis.app.mcp.server.approval.McpApprovalExecutor
import com.openminis.app.mcp.server.approval.McpApprovalManager
import com.openminis.app.mcp.server.approval.McpApprovalRpc
import com.openminis.app.mcp.server.tools.McpToolDeps
import com.openminis.app.mcp.server.tools.McpToolShared
import com.openminis.app.mcp.server.tools.McpToolSpec
import com.openminis.app.mcp.server.tools.McpToolSchemas
import com.openminis.app.mcp.server.tools.approvalToolSpecs
import com.openminis.app.mcp.server.tools.appsTools
import com.openminis.app.mcp.server.tools.deviceTools
import com.openminis.app.mcp.server.tools.fileTools
import com.openminis.app.mcp.server.tools.scheduleTools
import com.openminis.app.mcp.server.tools.shellTools
import com.openminis.app.mcp.server.tools.uiTools
import com.openminis.app.security.PermissionMode
import com.openminis.app.security.SecurityGate
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-mcp-refactor] Registry-based dispatcher. The 14 built-in tools live in
 * `tools/` as self-contained [McpToolSpec] entries grouped by domain
 * (device / shell / ui / file / schedule / apps) — after OmniBot's DeviceTool
 * registry and taixu's HarnessToolProvider. Adding a tool means adding a
 * spec to its domain file; this class never changes.
 *
 * The companion keeps forwarding the pre-refactor public statics so the
 * existing JVM tests (777 lines of contract) stay untouched.
 *
 * @param context nullable for plain-JVM tests — every dereference degrades
 *   to an error result, never a crash.
 * @param gate the security gate every command goes through.
 * @param shellMode supplies the current permission mode (ALLOW_ALL / STRICT).
 * @param approvalManager when present, the three `phone_*` approval tools are
 *   registered and the approved-operation executor runs tools through this
 *   same dispatcher.
 */
class McpToolDispatcher(
    private val context: Context?,
    private val gate: SecurityGate,
    private val shellMode: () -> PermissionMode,
    approvalManager: McpApprovalManager? = null,
) : McpServerCore.ToolDispatcher {

    private val deps = McpToolDeps(context, gate, shellMode)

    /**
     * Executor runs business tools through this dispatcher. Lazy so `this` is
     * fully constructed before the executor captures it, and so a dispatcher
     * without an approval runtime never allocates one.
     */
    private val approvalExecutor: McpApprovalExecutor? by lazy {
        approvalManager?.let { McpApprovalExecutor(this) }
    }

    private val approvalRpc: McpApprovalRpc? by lazy {
        val executor = approvalExecutor ?: return@lazy null
        approvalManager?.let { McpApprovalRpc(it, executor) }
    }

    /**
     * [T-mcp-approval-ui] In-process handle on the approval surface. The
     * approvals sheet executes an approved run through this same instance the
     * external clients talk to, so both paths share one gate + one executor.
     * Null while no approval runtime is wired (server never started).
     */
    fun approvalSurface(): McpApprovalRpc? = approvalRpc

    private val tools: List<McpToolSpec> = buildList {
        addAll(deviceTools(deps))
        addAll(shellTools(deps))
        addAll(uiTools(deps))
        addAll(fileTools(deps))
        addAll(scheduleTools(deps))
        addAll(appsTools(deps))
        // Approval tools appear only when a runtime exists: advertising a tool
        // whose handler has no store behind it is the defect the announced-tool
        // gate exists for.
        addAll(approvalToolSpecs(approvalRpc))
    }

    private val byName: Map<String, McpToolSpec> = tools.associateBy { it.name }

    override fun listTools(): JSONArray = JSONArray().apply {
        tools.forEach { put(McpToolSchemas.descriptor(it)) }
    }

    override fun callTool(name: String, arguments: JSONObject): McpServerCore.CallResult {
        val tool = byName[name]
            ?: return McpToolShared.error("Error: unknown tool '$name'. Call list_tools first.")
        return tool.handler(arguments)
    }

    companion object {
        // ── Pre-refactor public surface (tests depend on these) ──────────────

        @JvmStatic
        fun text(s: String): JSONObject = McpToolShared.text(s)

        @JvmStatic
        fun shellEscape(s: String): String = McpToolShared.shellEscape(s)

        @JvmStatic
        fun normalizePath(p: String): String = McpToolShared.normalizePath(p)

        @JvmStatic
        fun validateFilePath(p: String): String? = McpToolShared.validateFilePath(p)

        @JvmStatic
        fun validateCoord(v: Any?, name: String): Pair<Int?, String?> =
            McpToolShared.validateCoord(v, name)

        val MAX_COORD: Int get() = McpToolShared.MAX_COORD

        val MAX_FILE_WRITE_CHARS: Int get() = McpToolShared.MAX_FILE_WRITE_CHARS

        val UI_ACTIONS: Set<String> get() = McpToolShared.UI_ACTIONS

        val SCROLL_DIRECTIONS: Set<String> get() = McpToolShared.SCROLL_DIRECTIONS

        val SHELL_STRICT_HINT: String get() = McpToolShared.SHELL_STRICT_HINT

        val SESSION_ID: String get() = McpToolShared.SESSION_ID
    }
}
