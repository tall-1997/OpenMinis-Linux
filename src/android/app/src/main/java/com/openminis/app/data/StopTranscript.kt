package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

internal const val USER_STOP_NOTE =
    "<system-reminder>The user stopped the previous response. Content may be incomplete.</system-reminder>"

internal const val LEGACY_ORPHAN_STOP_NOTE =
    "<system-reminder>The user cancelled an operation. Its outcome is unknown.</system-reminder>"

/** The exact host-authored, text-only tail written by Stop. */
internal fun isStoredUserStopNote(parts: JSONArray): Boolean = parts.length() == 2 &&
    parts.optJSONObject(0)?.let { it.opt("type") == "text" && it.opt("value") == USER_STOP_NOTE } == true &&
    parts.optJSONObject(1)?.let { it.opt("type") == "receiptOrigin" && it.opt("value") == "host" } == true

// Historical ChatViewModel.CANCELLED_MARKER and the pre-T13 spelling. Only exact, payload-free
// cancellation records qualify; tool-authored errors, successful outputs and mixed rows do not.
private fun isLegacyStopOutput(output: String): Boolean = output == "[cancelled by user]" ||
    output == "<system-reminder>The user cancelled this operation. The returned result may be incomplete.</system-reminder>"

internal fun JSONArray.isLegacyStopCarrier(): Boolean = length() > 0 && (0 until length()).all { index ->
    val part = optJSONObject(index) ?: return@all false
    if (part.length() != 2 || part.opt("type") != "toolResult") return@all false
    val value = part.optJSONObject("value") ?: return@all false
    val output = value.opt("output") as? String ?: return@all false
    if (value.opt("success") != false || !isLegacyStopOutput(output) ||
        !validLegacyStopKey(value.opt("toolUseId") as? String, value.opt("name") as? String) ||
        value.keys().asSequence().any { it !in legacyStopFields }
    ) return@all false
    val rawSnapshot = value.opt("snapshot") ?: return@all true
    val snapshot = rawSnapshot as? JSONObject ?: return@all false
    snapshot.length() == 2 && snapshot.opt("type") == "text" && snapshot.opt("text") == output
}

internal fun JsonArray.isLegacyStopCarrier(): Boolean = isNotEmpty() && all {
    val part = it as? JsonObject ?: return@all false
    if (part.size != 2 || part.string("type") != "toolResult") return@all false
    val value = part["value"] as? JsonObject ?: return@all false
    val output = value.string("output") ?: return@all false
    if (value["success"] != JsonPrimitive(false) || !isLegacyStopOutput(output) ||
        !validLegacyStopKey(value.string("toolUseId"), value.string("name")) ||
        value.keys.any { it !in legacyStopFields }
    ) return@all false
    val rawSnapshot = value["snapshot"] ?: return@all true
    val snapshot = rawSnapshot as? JsonObject ?: return@all false
    snapshot.size == 2 && snapshot.string("type") == "text" && snapshot.string("text") == output
}

private fun validLegacyStopKey(id: String?, name: String?): Boolean =
    id != null && id.length in 1..512 && name != null && name.length in 1..256 && compactPairKey(id, name) != null

private val legacyStopFields = setOf("toolUseId", "name", "output", "success", "snapshot")

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A result carrier has host origin only when the persisted decoder recognized the old Stop shape. */
internal fun LLMMessage.isAttestedLegacyStopCarrier(): Boolean =
    role == LLMMessage.Role.USER && isHostGenerated && !dbMessageId.isNullOrBlank() &&
        contentParts.isNotEmpty() && contentParts.all { it is AgentContentPart.ToolResult && it.isLegacyStopResult() }

internal fun AgentContentPart.ToolResult.isLegacyStopResult(): Boolean = isError && isLegacyStopOutput(content) &&
    receiptProof == null && imageData == null && imageMimeType == null && imageLinuxPath == null && compactPairKey(id, name) != null
