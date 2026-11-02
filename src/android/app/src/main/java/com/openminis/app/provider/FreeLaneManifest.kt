package com.openminis.app.provider

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The free-lane manifest: instance-layer descriptors for built-in keyless
 * lanes, shipped as an APK asset and optionally refreshed from a remote copy.
 *
 * The split that makes the feature dynamic:
 *
 * - **Behaviour** (how an upstream speaks) stays compiled: [FreeLane] has to
 *   map an error dialect onto verdicts, and every lane so far has taught us
 *   that a dialect is not serialisable — Zen's `type` words, Kilo's `code`
 *   words, and the two incidents that emptied lanes were all dialect
 *   misreads. The `behavior` field picks a known implementation.
 * - **Instance layer** (which upstream, what label, what credential
 *   convention) is data. For the generic `openaiCompat` behaviour, adding a
 *   new free lane is therefore a JSON entry, no release required.
 *
 * Parse rules are strict on purpose: a bad manifest must behave like an
 * absent manifest, never like a half-loaded lane list. The parser returns
 * null on any violation and the loader falls back to the bundled asset, then
 * to the compiled-in [BuiltinFreeLanes.ALL].
 */
internal object FreeLaneManifest {
    private const val TAG = "FreeLaneManifest"
    const val REMOTE_FILE_NAME = "free_lanes.json"
    const val ACTIVE_IDS_FILE_NAME = "free_lanes_active.json"
    const val VERSION = 1

    /** FilesDir copy of a successfully refreshed remote manifest. */
    fun remoteFile(context: Context): File = File(context.filesDir, REMOTE_FILE_NAME)

    private fun activeIdsFile(context: Context): File = File(context.filesDir, ACTIVE_IDS_FILE_NAME)

    /**
     * The effective manifest: the refreshed remote copy when it parses, else
     * the bundled asset, else nothing (the compiled-in lanes are the
     * fallback of the fallback).
     *
     * Returns [ManifestState.remoteOk] so callers can tell "remote manifest
     * parsed and simply has fewer lanes" (a lane may retire) from "remote
     * copy unreadable" (nothing may retire).
     */
    fun load(context: Context): ManifestState {
        val remote = remoteFile(context)
        if (remote.isFile) {
            val parsed = parse(remote.readText())
            if (parsed != null) return ManifestState(parsed, remoteOk = true)
            AppLogger.warning(TAG, "remote manifest failed to parse — falling back to bundled asset")
        }
        val bundled = runCatching {
            context.assets.open(REMOTE_FILE_NAME).bufferedReader().use { it.readText() }
        }.getOrNull()
        val parsed = bundled?.let { parse(it) }
        return if (parsed != null) ManifestState(parsed, remoteOk = false)
        else ManifestState(emptyList(), remoteOk = false)
    }

    /**
     * Persists a refreshed manifest, but only when it parses. A refused
     * payload never touches the file, so a bad refresh cannot take lanes
     * away — the previous copy keeps ruling until something valid replaces it.
     */
    fun saveRefreshed(context: Context, json: String): Boolean {
        if (parse(json) == null) {
            AppLogger.warning(TAG, "refusing to persist a manifest that does not parse")
            return false
        }
        runCatching { remoteFile(context).writeText(json) }.onFailure {
            AppLogger.warning(TAG, "failed to write refreshed manifest: ${it.message}")
            return false
        }
        return true
    }

    /**
     * The instance-id set of the last manifest that actually seeded, used as
     * the retirement veto when a refresh is unreadable. A lane that was real
     * once must not vanish because a later download is garbage.
     */
    fun loadActiveIds(context: Context): Set<String> =
        runCatching {
            val json = JSONArray(activeIdsFile(context).readText())
            buildSet { for (i in 0 until json.length()) add(json.optString(i)) }.filter { it.isNotBlank() }.toSet()
        }.getOrDefault(emptySet())

    fun saveActiveIds(context: Context, ids: Set<String>) {
        runCatching {
            val json = JSONArray()
            ids.sorted().forEach { json.put(it) }
            activeIdsFile(context).writeText(json.toString())
        }.onFailure {
            AppLogger.warning(TAG, "failed to persist active lane ids: ${it.message}")
        }
    }

    /** Parsed and validated lanes, or null when the document is refused. */
    fun parse(json: String): List<ManifestLaneEntry>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optInt("version", -1) != VERSION) return null
        val array = root.optJSONArray("lanes") ?: return null
        if (array.length() == 0) return null
        val entries = mutableListOf<ManifestLaneEntry>()
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val entry = parseLane(array.optJSONObject(i) ?: return null) ?: return null
            if (!seen.add(entry.instanceId)) return null // duplicate instance id
            entries.add(entry)
        }
        return entries
    }

    private fun parseLane(obj: JSONObject): ManifestLaneEntry? {
        val id = obj.optString("id").trim()
        val behavior = obj.optString("behavior").trim()
        val instanceId = obj.optString("instanceId").trim()
        val label = obj.optString("label").trim()
        val baseURL = obj.optString("baseURL").trim()
        val convention = obj.optString("credentialConvention").trim().ifBlank { "fixed" }
        val credentialValue = obj.optString("credentialValue")
        val seeds = obj.optJSONArray("offlineSeedIds")
            ?.let { arr -> buildList { for (i in 0 until arr.length()) add(arr.optString(i).trim()) } }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        if (id.isEmpty() || behavior.isEmpty() || label.isEmpty()) return null
        // Retirement recognition keys on the builtin- prefix; anything else
        // would let a manifest entry strand user rows or survive retirement.
        if (!INSTANCE_ID_PATTERN.matches(instanceId)) return null
        val conventionValue = CredentialConvention.parse(convention) ?: return null
        if (behavior != BEHAVIOR_ZEN && behavior != BEHAVIOR_KILO && behavior != BEHAVIOR_OPENAI_COMPAT) return null
        // The generic lane has no compiled-in endpoint to default to.
        if (behavior == BEHAVIOR_OPENAI_COMPAT && baseURL.isEmpty()) return null
        // A fixed credential is exactly what the seed pass writes; a lane that
        // says "fixed" without one would seed a lane that cannot answer.
        if (conventionValue == CredentialConvention.FIXED && credentialValue.isEmpty()) return null
        return ManifestLaneEntry(
            id = id,
            behavior = behavior,
            instanceId = instanceId,
            label = label,
            baseURL = baseURL,
            credentialConvention = conventionValue,
            credentialValue = credentialValue,
            offlineSeedIds = seeds,
        )
    }

    private val INSTANCE_ID_PATTERN = Regex("^builtin-[a-z0-9-]+$")

    internal const val BEHAVIOR_ZEN = "zen"
    internal const val BEHAVIOR_KILO = "kilo"
    internal const val BEHAVIOR_OPENAI_COMPAT = "openaiCompat"
}

/** What [FreeLaneManifest.load] found, including how trustworthy it is. */
internal data class ManifestState(
    val entries: List<ManifestLaneEntry>,
    /** True when the refreshed remote copy parsed; false for asset/absent. */
    val remoteOk: Boolean,
)

/** How a lane wants its credential handled. */
internal enum class CredentialConvention {
    /** The upstream rejects any token: probe with none, never write one. */
    NONE,

    /** A fixed placeholder credential is part of the lane identity. */
    FIXED,

    /** Per-account free quota: the user supplies keys, pooled via M2. */
    ACCOUNT_TOKEN,
    ;

    companion object {
        fun parse(raw: String): CredentialConvention? = when (raw) {
            "none" -> NONE
            "fixed" -> FIXED
            "accountToken" -> ACCOUNT_TOKEN
            else -> null
        }
    }
}

/**
 * One instance-layer lane descriptor. Purely data: everything that decides
 * how the lane behaves lives in the [FreeLane] implementation the [behavior]
 * field selects.
 */
internal data class ManifestLaneEntry(
    val id: String,
    val behavior: String,
    val instanceId: String,
    val label: String,
    val baseURL: String,
    val credentialConvention: CredentialConvention,
    val credentialValue: String,
    val offlineSeedIds: List<String>,
)
