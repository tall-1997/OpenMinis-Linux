package com.openminis.app.tools

import com.openminis.app.data.authority.MemoryAuthority
import com.openminis.app.data.authority.MemoryOrigin
import com.openminis.app.data.repository.MemoryRepository
import org.json.JSONObject

/**
 * Execution for memory_write and memory_get.
 *
 * [T-memory-tools-dead-schema] The four hand-built JSON schema factories that
 * used to open this file (`memoryWriteToolDefinition`, `memoryGetToolDefinition`,
 * `memoryWriteOpenAIDefinition`, `memoryGetOpenAIDefinition`) had NO callers left:
 * every provider gets its schema from [AgentTools] (`memoryWriteDefinition` /
 * `memoryGetDefinition`), which is also where the `scope` enum lives now. They
 * had additionally DRIFTED — they advertised `scope=evolution`, which the live
 * schema did not, while [executeMemoryGet] really does implement it. Deleted
 * rather than "kept in sync"; the enum is reconciled in AgentTools instead.
 */
object MemoryTools {

    // -- Execution --

    data class ToolResult(
        val output: String,
        val success: Boolean,
        val toolTitle: String = "",
    )

    /**
     * Tool-access gate ([MemoryAuthority.canAccessTool]): a recall path
     * must never write. `origin` defaults to [MemoryOrigin.AGENT] for normal
     * tool dispatch; interface/recall call sites pass their own.
     */
    fun guardAccess(origin: MemoryOrigin, toolName: String): Boolean =
        MemoryAuthority.canAccessTool(origin, toolName)

    fun executeMemoryWrite(
        inputJson: String,
        repository: MemoryRepository,
        origin: MemoryOrigin = MemoryOrigin.AGENT,
    ): ToolResult {
        return try {
            if (!guardAccess(origin, "memory_write")) {
                return ToolResult("Error: memory_write is not permitted from this origin", false)
            }
            val obj = JSONObject(inputJson)
            val content = obj.optString("content", "")
            val toolTitle = obj.optString("tool_title", "memory_write")
            val revision = obj.optString("revision", "").takeIf { it.isNotBlank() }

            if (content.isBlank()) {
                ToolResult("Error: Missing required 'content' parameter", false, toolTitle)
            } else {
                val result = repository.writeMemory(content, revision)
                val success = result.startsWith("Memory saved")
                ToolResult(result, success, toolTitle)
            }
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }

    fun executeMemoryGet(
        inputJson: String,
        repository: MemoryRepository,
        origin: MemoryOrigin = MemoryOrigin.AGENT,
    ): ToolResult {
        return try {
            // [T-p0-authority] This path had no gate at all: the read-only
            // matrix existed but only in tests. Reading is what AUTOMATIC_RECALL
            // is allowed to do, so the gate stays open for it and closed only
            // for origins that neither read nor write memory.
            if (!guardAccess(origin, "memory_get")) {
                return ToolResult("Error: memory_get is not permitted from this origin", false)
            }
            val obj = JSONObject(inputJson)
            val keywords = obj.optString("keywords", "")
            val scope = obj.optString("scope", "all")
            val toolTitle = obj.optString("tool_title", "memory_get")

            if (scope == "evolution") {
                val engine = com.openminis.app.di.AppGraph.evolutionEngineOrNull()
                val body = if (engine == null) {
                    "Evolution engine is not available."
                } else {
                    val proposals = engine.store.proposals.value
                    val pending = proposals.count { it.status.name == "PENDING" }
                    "enabled: ${engine.prefs.isEnabled}\nproposals: ${proposals.size}\npending: $pending\nUse the evolution tool to list, accept, or reject."
                }
                return ToolResult(body, true, toolTitle)
            }
            val result = repository.getMemory(keywords, scope)
            val revision = repository.currentDailyRevision()
            val withRevision = if (revision != null) {
                "$result\n\n[当前日志 revision: $revision — 用 memory_write 并发安全写入时，把它作为 revision 参数传入]"
            } else result
            ToolResult(withRevision, true, toolTitle)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }
}
