package com.openminis.app.provider

/**
 * Labels the relay rate-limit bucket for a model-group member so the Settings
 * UI can show collisions. Identity is host + credential + model name — the
 * same tuple [ProviderKeyGate] uses.
 *
 * This is display-only. Chat routing still follows the group the user picked
 * in the session; it never jumps to another group.
 */
object ModelGroupBuckets {
    fun bucket(baseUrl: String?, apiKey: String?, modelId: String?): String =
        ProviderKeyGate.key(baseUrl.orEmpty(), apiKey, modelId)

    /** Short, non-secret caption: 6 hex of the key fingerprint + model id. */
    fun caption(bucket: String): String {
        val parts = bucket.split('|')
        val fp = parts.getOrNull(1)?.take(6).orEmpty()
        val model = parts.getOrNull(2).orEmpty()
        return when {
            fp.isEmpty() && model.isEmpty() -> ""
            model.isEmpty() -> fp
            fp.isEmpty() -> model
            else -> "$fp · $model"
        }
    }

    fun duplicateBuckets(buckets: Collection<String>): Set<String> =
        buckets.filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .filter { it.value > 1 }
            .keys
}
