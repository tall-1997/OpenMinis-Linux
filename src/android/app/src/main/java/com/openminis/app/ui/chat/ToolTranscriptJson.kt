package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.provider.NonFiniteJson
import org.json.JSONObject

/** Validate and normalize stored JSON before publishing it as a stable snapshot. */
internal fun readTranscriptToolUse(value: JSONObject): AgentContentPart.ToolUse {
    val inputJson = NonFiniteJson.parseOrNull(value.optString("input", "{}"))?.toString() ?: "{}"
    return AgentContentPart.ToolUse.fromInputJson(
        id = value.optString("toolUseId", ""),
        name = value.optString("name", ""),
        inputJson = inputJson,
        thoughtSignature = (value.opt("thoughtSignature") as? String)?.ifEmpty { null },
    )
}

internal fun readTranscriptToolResult(value: JSONObject): AgentContentPart.ToolResult = AgentContentPart.ToolResult(
    id = value.optString("toolUseId", ""),
    name = value.optString("name", ""),
    content = value.optString("output", ""),
    isError = !value.optBoolean("success", true),
    imageLinuxPath = (value.opt("imageLinuxPath") as? String)?.ifBlank { null },
    // Legacy success semantics are not evidence. The receipt store validates this proof separately.
    receiptProof = (value.opt("receiptProof") as? String)?.ifBlank { null },
)
