package com.openminis.app.data.repository

/**
 * [T-android-session-paused-badge-hardkill] The interrupted-tail predicate
 * over a raw `parts_json` string, matching ChatViewModel.loadSession's
 * AgentContentPart-based logic:
 *   - role USER + ALL parts are tool_result (tools ran, next model call never
 *     fired), OR the single synthetic "Continue" reminder text part, OR
 *   - role ASSISTANT + any tool_use part (model asked for tools that never ran)
 * Part type discriminator is the JSON "type" field — the @SerialName values
 * from [com.openminis.app.data.model.ContentPart]: "toolUse" / "toolResult"
 * / "text" (camelCase, NOT snake_case); text payload is the "value" field.
 *
 * Lives outside [ChatRepository] so the repository stays under its
 * architecture-policy line ceiling. The predicate is pure, so the move
 * changes nothing about who it matches.
 */
internal fun isInterruptedTail(role: String, partsJson: String): Boolean {
    val arr = runCatching { org.json.JSONArray(partsJson) }.getOrNull() ?: return false
    val types = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        arr.optJSONObject(i)?.let { types.add(it.optString("type")) }
    }
    return when (role.uppercase()) {
        "USER" -> {
            val allToolResults = types.isNotEmpty() && types.all { it == "toolResult" }
            val isContinueReminder = arr.length() == 1 &&
                arr.optJSONObject(0)?.takeIf { it.optString("type") == "text" }
                    ?.optString("value")
                    ?.contains("The user stopped the previous response") == true
            allToolResults || isContinueReminder
        }
        "ASSISTANT" -> types.any { it == "toolUse" }
        else -> false
    }
}
