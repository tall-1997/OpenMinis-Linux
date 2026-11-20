package com.openminis.app.ui.chat

import com.openminis.app.data.CompactAssets
import com.openminis.app.data.compactPairKey
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.validCompactPath

internal object ChatCompactAssets {
    private val skillPath = Regex("/var/minis/skills/([A-Za-z0-9._-]+)/SKILL\\.md")
    private val fileTools = setOf("file_read", "file_write", "file_edit", "multi_edit", "read_image")
    // Browser artifacts are accepted only from the host-written structured field,
    // not URLs, captions, or instructions mentioned by a tool's result body.
    private val artifactTools = setOf("browser_use", "read_image")

    /** Called after Room verifies the actual submitted prefix, not on a live/request projection. */
    fun merge(previous: String?, covered: List<LLMMessage>, uploadsByRow: List<List<String>> = emptyList()): String? =
        CompactAssets.decode(previous).merge(extract(covered, uploadsByRow)).encode()

    fun extract(history: List<LLMMessage>, uploadsByRow: List<List<String>> = emptyList()): CompactAssets {
        val pending = HashMap<Pair<String, String>, ArrayDeque<AgentContentPart.ToolUse>>()
        var assets = CompactAssets()
        for ((index, msg) in history.withIndex()) {
            if (msg.role == LLMMessage.Role.USER) {
                val uploads = uploadsByRow.getOrNull(index).orEmpty().filter(::validCompactUploadPath)
                if (uploads.isNotEmpty()) assets = assets.merge(CompactAssets(attachments = uploads))
            }
            for (part in msg.contentParts) {
                when {
                    msg.role == LLMMessage.Role.ASSISTANT && part is AgentContentPart.ToolUse -> {
                        val key = compactPairKey(part.id, part.name) ?: continue
                        if (key.second in fileTools || key.second in artifactTools) {
                            pending.getOrPut(key, ::ArrayDeque).addLast(part)
                        }
                    }
                    msg.role == LLMMessage.Role.USER && part is AgentContentPart.ToolResult -> {
                        val key = compactPairKey(part.id, part.name) ?: continue
                        val calls = pending[key] ?: continue
                        val call = calls.removeFirst()
                        if (calls.isEmpty()) pending.remove(key)
                        // Failed results consume their own call and cannot turn a
                        // later duplicate into proof of a successful invocation.
                        if (part.isError) continue
                        val name = key.second
                        val path = if (name in fileTools) {
                            (call.input.opt("path") as? String)?.takeIf(::validCompactPath)
                        } else null
                        val read = path?.takeIf { name == "file_read" }
                        val skill = read?.let { skillPath.matchEntire(it)?.groupValues?.get(1) }
                        val artifact = if (name in artifactTools) part.imageLinuxPath?.takeIf(::validCompactPath) else null
                        assets = assets.merge(CompactAssets(listOfNotNull(path), listOfNotNull(read),
                            listOfNotNull(skill), listOfNotNull(artifact)))
                    }
                }
            }
        }
        return assets
    }
}
