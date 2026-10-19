package com.openminis.app.backup

import com.openminis.app.logging.AppLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "Restore"

// [T-android-restore-provider-type-tolerance] Lenient provider_config.json
// parsing, extracted from BackupImporter.kt: the architecture-policy baseline
// pins that file at a ceiling it may shrink under but never grow past, so new
// work there has to displace something. Nothing here changed in the move.
//
// The failure this exists to prevent, observed in the field: an iOS package
// contained a provider whose `providerType` was `openAIResponses`, a case
// Android's enum did not have. kotlinx.serialization threw on that one value,
// the single whole-document `decodeFromString` aborted, and all EIGHT providers
// in the package were discarded — credentials included. The user saw "no API
// keys in this backup", which was never true; nothing had been read at all.

/**
 * [T-android-restore-provider-type-tolerance] Result of a lenient
 * provider_config.json parse: the config that survived, plus how many
 * instances had to be dropped because this build could not decode them.
 */
internal data class LenientProviderConfig(
    val config: com.openminis.app.data.model.ProviderConfig,
    val droppedInstances: Int,
)

/**
 * [T-android-restore-provider-type-tolerance] Decode provider_config.json
 * so that ONE malformed instance cannot destroy the rest of the file.
 *
 * The failure this exists to prevent, observed in the field: an iOS package
 * contained a provider whose `providerType` was `openAIResponses`, a case
 * Android's enum did not have. kotlinx.serialization threw on that one
 * value, the single whole-document `decodeFromString` aborted, and all
 * EIGHT providers in the package were discarded — credentials included.
 * The user saw "no API keys in this backup", which was never true; nothing
 * had been read at all.
 *
 * Two independent layers, because the enum fix alone is not enough — the
 * next iOS-only type would reproduce it exactly:
 *
 *  1. `providerType` values this build doesn't know are rewritten to
 *     `unsupported` BEFORE decoding, so the instance survives as a visible
 *     (unusable) row rather than taking the file down. Mirrors iOS's
 *     `ProviderType.decoded`, which has always been forgiving here.
 *  2. Anything still undecodable — a genuinely malformed object, a missing
 *     required field — is dropped individually and counted, leaving every
 *     sibling instance intact.
 *
 * Top-level fields (modelEntries, groups, default pointers) are decoded
 * from the same object with the instances array replaced, so a bad instance
 * costs only that instance.
 */
internal fun parseProviderConfigLeniently(text: String): LenientProviderConfig {
    val root = BackupFormat.json.parseToJsonElement(text).jsonObject
    val rawInstances = root["instances"] as? kotlinx.serialization.json.JsonArray

    // No instances array at all — nothing to be tolerant about; let the
    // ordinary decode handle (or reject) the document.
    if (rawInstances == null) {
        return LenientProviderConfig(
            BackupFormat.json.decodeFromJsonElement(
                com.openminis.app.data.model.ProviderConfig.serializer(), root,
            ),
            droppedInstances = 0,
        )
    }

    val knownTypes = com.openminis.app.data.model.ProviderType.entries.map { it.name }.toSet()
    val kept = mutableListOf<kotlinx.serialization.json.JsonElement>()
    var dropped = 0
    for (element in rawInstances) {
        val obj = element as? JsonObject ?: run { dropped++; null } ?: continue
        val rawType = (obj["providerType"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull
        val normalized = if (rawType != null && rawType !in knownTypes) {
            AppLogger.warning(
                TAG,
                "[Restore] provider instance ${obj["id"]?.jsonPrimitive?.contentOrNull}: " +
                    "unknown providerType '$rawType' — importing as unsupported",
            )
            JsonObject(
                obj + ("providerType" to kotlinx.serialization.json.JsonPrimitive(
                    com.openminis.app.data.model.ProviderType.unsupported.name,
                )),
            )
        } else obj
        // Prove it decodes before keeping it, so a malformed sibling is
        // rejected here rather than at the whole-document decode below.
        val decodes = runCatching {
            BackupFormat.json.decodeFromJsonElement(
                com.openminis.app.data.model.ProviderInstance.serializer(), normalized,
            )
        }
        if (decodes.isSuccess) {
            kept.add(normalized)
        } else {
            dropped++
            AppLogger.warning(
                TAG,
                "[Restore] provider instance dropped (undecodable): " +
                    "${decodes.exceptionOrNull()?.message}",
            )
        }
    }

    val patched = JsonObject(root + ("instances" to kotlinx.serialization.json.JsonArray(kept)))
    return LenientProviderConfig(
        BackupFormat.json.decodeFromJsonElement(
            com.openminis.app.data.model.ProviderConfig.serializer(), patched,
        ),
        droppedInstances = dropped,
    )
}
