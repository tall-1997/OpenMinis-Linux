package com.openminis.app.tools

import com.openminis.app.data.authority.MemoryAuthority
import com.openminis.app.data.authority.MemoryOrigin
import com.openminis.app.data.repository.MemoryEntryOrigin
import com.openminis.app.data.repository.MemoryLifecycle
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
                val importance = obj.optString("importance", "normal")
                if (importance !in setOf("normal", "low")) {
                    return ToolResult("Error: importance must be normal or low", false, toolTitle)
                }
                val pinned = if (!obj.has("pinned")) false else obj.opt("pinned") as? Boolean
                    ?: return ToolResult("Error: pinned must be a boolean", false, toolTitle)
                val low = importance == "low" && !pinned && origin == MemoryOrigin.AGENT
                val ttl = if (!obj.has("ttl_days")) null else {
                    val days = when (val value = obj.opt("ttl_days")) {
                        is Number -> value.toString().toIntOrNull()
                        else -> null
                    }
                    if (!low || days == null || days !in 1..MemoryLifecycle.MAX_TTL_DAYS) {
                        return ToolResult("Error: ttl_days must be an integer from 1 to ${MemoryLifecycle.MAX_TTL_DAYS} on an unpinned low-importance agent memory", false, toolTitle)
                    }
                    days
                }
                val result = if (origin == MemoryOrigin.AGENT) {
                    repository.writeHostMemory(content, revision, MemoryEntryOrigin.MEMORY_WRITE, ttl, autoArchive = low, pin = pinned)
                } else repository.writeMemory(content, revision)
                // [T-memory-dedup-on-write] The write-side dedup's "Skipped: …"
                // reply is a SUCCESS (the memory exists; nothing was written) —
                // classifying it as failure made the model see an error-shaped
                // tool result for a harmless skip and re-retry the write.
                val success = result.startsWith("Memory saved") || result.startsWith("Skipped")
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
            when (val action = obj.optString("action", "search")) {
                "restore" -> {
                    if (origin == MemoryOrigin.AUTOMATIC_RECALL || scope != "archive") {
                        return ToolResult("Error: restore requires explicit archive access", false, toolTitle)
                    }
                    val entryId = obj.optString("entry_id")
                    val restored = repository.restoreArchivedMemory(entryId)
                    return if (restored) ToolResult("Memory restored: $entryId", true, toolTitle)
                    else ToolResult("Error: No expired or archived entry with that ID in this scope", false, toolTitle)
                }
                "search" -> Unit
                else -> return ToolResult("Error: Unknown memory_get action '$action'", false, toolTitle)
            }
            if (scope == "archive") {
                if (origin == MemoryOrigin.AUTOMATIC_RECALL) {
                    return ToolResult("Error: archive search requires explicit access", false, toolTitle)
                }
                return ToolResult(repository.getMemory(keywords, scope), true, toolTitle)
            }

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
                "$result\n\n[当前日志 revision: $revision — 带日期前缀（YYYY-MM-DD:hash），只在当天有效；memory_write 并发安全写入时把它作为 revision 传入，跨天写入请省略该参数]"
            } else result
            ToolResult(withRevision, true, toolTitle)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }
}
