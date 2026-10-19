package com.openminis.app.backup

import com.openminis.app.util.IsoTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// Tolerant JsonObject readers over the package's own JSONL/envelope shapes.
// Extracted from BackupImporter.kt (architecture-policy ceiling); the whole
// package shares them.
//
// These return null rather than throwing on any mismatch: a package written by
// another platform legitimately omits fields this build expects, and a record
// that cannot be read is counted unreadable rather than aborting the file.

/**
 * [T-android-restore-ios-session-nesting] Flatten `{outer…, key:{inner…}}`
 * into one object, inner winning.
 *
 * iOS wraps some records — a session arrives as
 * `{"memoryEnabled":true,"session":{"id":…,"title":…}}` — while Android
 * writes the same information flat. Merging instead of choosing means
 * one reader handles both, and the wrapper's own fields
 * (`memoryEnabled`) stay reachable by their plain names.
 *
 * Returns `this` unchanged when [key] is absent or is not an object,
 * so a flat Android record costs nothing.
 */
internal fun JsonObject.unwrapNested(key: String): JsonObject {
    val inner = (this[key] as? JsonObject) ?: return this
    return JsonObject(this.filterKeys { it != key } + inner)
}

internal fun JsonObject.str(key: String): String? =
    this[key]?.takeIf { it.toString() != "null" }?.runCatching { jsonPrimitive.content }
        ?.getOrNull()

internal fun JsonObject.int(key: String): Int? =
    this[key]?.runCatching { jsonPrimitive.content.toInt() }?.getOrNull()

internal fun JsonObject.bool(key: String): Boolean? =
    this[key]?.runCatching { jsonPrimitive.content.toBooleanStrict() }?.getOrNull()

/**
 * Parse an ISO-8601 instant into epoch millis.
 *
 * iOS writes dates as ISO-8601 strings, but a package written by a
 * future build (or by a tool) could carry a numeric epoch, so both are
 * accepted — §2.2's tolerance rule applied to a value, not just a key.
 */
fun JsonObject.millis(key: String): Long? {
    val raw = str(key) ?: return null
    raw.toLongOrNull()?.let { return it }
    return IsoTime.parseFlexible(raw)
}
