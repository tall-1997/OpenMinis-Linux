package com.openminis.app.data.repository

import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.json.JSONArray
import org.json.JSONObject

/**
 * [#24 provider-transfer] Pure-JVM codec for the batch provider config
 * transfer document `{"version":1,"providers":[…]}`. Used by the provider
 * management page's Export / Import menu items (SAF round-trip in
 * [ProviderTransferStore]).
 *
 * Deliberately Android-free (org.json + core:model types only) so the
 * round-trip, dedupe-merge, and no-key-compat behaviors are unit-testable
 * on the JVM — the same constraint as the per-instance
 * [ProviderBackupCoordinator] codec, extracted so the repository class
 * never has to grow again.
 *
 * Wire shape per provider (field names echo the per-instance format where a
 * key already exists there — customBaseURL / appendV1Suffix / useResponsesAPI /
 * customUserAgent — so readers can be shared; the batch envelope adds
 * `name` / `type` / `baseURL` aliases that carry the batch identity):
 *
 * ```
 * { "version": 1,
 *   "providers": [ { "name": "Relay", "type": "openAI",
 *                    "credentialType": "apiKey", "baseURL": "https://…",
 *                    "appendV1Suffix": true, "useResponsesAPI": false,
 *                    "azureMode": false, "customUserAgent": null,
 *                    "apiKey": "sk-…",              // ONLY when opted in
 *                    "models": [ { "modelId": …, "displayName": …, … } ] } ] }
 * ```
 *
 * The API key is PLAIN TEXT when present — the UI defaults the
 * include-keys switch OFF and shows a plaintext-risk confirmation before
 * writing such a file (see ProviderExportDialog).
 */
object ProviderTransferCodec {

    const val FORMAT_VERSION = 1

    // ── Model layer ──────────────────────────────────────────────────────

    /** One model entry inside a transferable provider (snapshot only). */
    data class TransferableModel(
        val modelId: String,
        val displayName: String,
        val isCustom: Boolean = false,
        val isHidden: Boolean = false,
        val contextWindow: Int? = null,
        val maxOutputTokens: Int? = null,
        val supportsReasoning: Boolean? = null,
        val temperature: Double? = null,
    )

    /** One provider instance distilled to its transferable configuration. */
    data class TransferableProvider(
        val label: String,
        val providerType: String,
        val credentialType: String,
        val customBaseURL: String? = null,
        val appendV1Suffix: Boolean = true,
        val useResponsesAPI: Boolean = false,
        val customUserAgent: String? = null,
        val azureMode: Boolean = false,
        val imageEndpointMode: ImageEndpointMode = ImageEndpointMode.auto,
        /**
         * Plain-text API key. Null = "no key in this document" — import must
         * still succeed (keyless import compatibility); export only sets it
         * when the user explicitly opted in.
         */
        val apiKey: String? = null,
        val models: List<TransferableModel> = emptyList(),
    )

    // ── Snapshot from the live model --------------------------------------

    /** Distill one instance + its model entries (+ optional key) for export. */
    fun toTransferable(
        instance: ProviderInstance,
        entries: List<ModelEntry>,
        apiKey: String?,
    ): TransferableProvider = TransferableProvider(
        label = instance.label,
        providerType = instance.providerType.name,
        credentialType = instance.credentialType.name,
        customBaseURL = instance.customBaseURL,
        appendV1Suffix = instance.appendV1Suffix,
        useResponsesAPI = instance.useResponsesAPI,
        customUserAgent = instance.customUserAgent,
        azureMode = instance.azureMode,
        imageEndpointMode = instance.imageEndpointMode,
        apiKey = apiKey?.takeIf { it.isNotEmpty() },
        models = entries.map { e ->
            TransferableModel(
                modelId = e.baseModel.id,
                displayName = e.baseModel.displayName,
                isCustom = e.isCustom,
                isHidden = e.isHidden,
                contextWindow = e.baseModel.contextWindow,
                maxOutputTokens = e.baseModel.maxOutputTokens,
                supportsReasoning = e.baseModel.supportsReasoning,
                temperature = e.overrides.temperature,
            )
        },
    )

    /** Rebuild a live instance (fresh id) from a transferable snapshot. */
    fun toInstance(
        provider: TransferableProvider,
        id: String,
        label: String,
    ): ProviderInstance = ProviderInstance(
        id = id,
        label = label,
        providerType = ProviderType.decoded(provider.providerType),
        credentialType = runCatching {
            ProviderCredential.valueOf(provider.credentialType)
        }.getOrElse { ProviderCredential.apiKey },
        customBaseURL = provider.customBaseURL,
        appendV1Suffix = provider.appendV1Suffix,
        useResponsesAPI = provider.useResponsesAPI,
        customUserAgent = provider.customUserAgent,
        azureMode = provider.azureMode,
        imageEndpointMode = provider.imageEndpointMode,
    )

    /** Rebuild the model entries for an instance id from the snapshot. */
    fun toModelEntries(
        providerInstanceId: String,
        providerType: ProviderType,
        models: List<TransferableModel>,
    ): List<ModelEntry> = models.filter { it.modelId.isNotBlank() }.map { m ->
        ModelEntry(
            providerInstanceId = providerInstanceId,
            baseModel = LLMModel(
                id = m.modelId,
                displayName = m.displayName,
                provider = providerType.displayName,
                contextWindow = m.contextWindow?.takeIf { it > 0 },
                maxOutputTokens = m.maxOutputTokens?.takeIf { it > 0 },
                supportsReasoning = m.supportsReasoning,
            ),
            overrides = ModelOverrides(temperature = m.temperature),
            isCustom = m.isCustom,
            isHidden = m.isHidden,
        )
    }

    // ── JSON: export ─────────────────────────────────────────────────────

    /** Build the `{"version":1,"providers":[…]}` document. */
    fun buildExportDocument(providers: List<TransferableProvider>): String {
        val arr = JSONArray()
        for (p in providers) {
            arr.put(providerToJSON(p))
        }
        return JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("providers", arr)
        }.toString(2)
    }

    private fun providerToJSON(p: TransferableProvider): JSONObject = JSONObject().apply {
        put("name", p.label)
        put("type", p.providerType)
        put("credentialType", p.credentialType)
        p.customBaseURL?.let { put("baseURL", it) }
        if (!p.appendV1Suffix) put("appendV1Suffix", false)
        if (p.useResponsesAPI) put("useResponsesAPI", true)
        p.customUserAgent?.takeIf { it.isNotBlank() }?.let { put("customUserAgent", it) }
        if (p.azureMode) put("azureMode", true)
        if (p.imageEndpointMode != ImageEndpointMode.auto) {
            put("imageEndpointMode", p.imageEndpointMode.name)
        }
        // Plain text — ONLY written when the user opted in via the export dialog.
        p.apiKey?.let { put("apiKey", it) }
        val models = JSONArray()
        for (m in p.models) {
            models.put(JSONObject().apply {
                put("modelId", m.modelId)
                put("displayName", m.displayName)
                if (m.isCustom) put("isCustom", true)
                if (m.isHidden) put("isHidden", true)
                m.contextWindow?.let { put("contextWindow", it) }
                m.maxOutputTokens?.let { put("maxOutputTokens", it) }
                m.supportsReasoning?.let { put("supportsReasoning", it) }
                m.temperature?.let { put("temperature", it) }
            })
        }
        put("models", models)
    }

    // ── JSON: import ─────────────────────────────────────────────────────

    /**
     * Parse the batch document. Returns null when the whole document is
     * unreadable (bad JSON / foreign version) — individual provider entries
     * are separately tolerated (unknown type decodes to
     * [ProviderType.unsupported]; a blank label drops just that entry).
     */
    fun parseDocument(json: String): List<TransferableProvider>? {
        val root = try { JSONObject(json) } catch (_: Exception) { return null }
        if (!root.has("providers")) return null
        val version = root.optInt("version", FORMAT_VERSION)
        if (version != FORMAT_VERSION) return null
        val arr = root.optJSONArray("providers") ?: return null
        val out = mutableListOf<TransferableProvider>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parseProvider(obj)?.let { out.add(it) }
        }
        return out
    }

    private fun parseProvider(obj: JSONObject): TransferableProvider? {
        val label = obj.optString("name", "").trim().ifEmpty { return null }
        val typeRaw = obj.optString("type", "").ifEmpty {
            obj.optString("providerType", "")
        }
        if (typeRaw.isEmpty()) return null
        val models = mutableListOf<TransferableModel>()
        obj.optJSONArray("models")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val modelId = m.optString("modelId", "").trim()
                if (modelId.isEmpty()) continue
                models.add(
                    TransferableModel(
                        modelId = modelId,
                        displayName = m.optString("displayName", modelId).ifEmpty { modelId },
                        isCustom = m.optBoolean("isCustom", false),
                        isHidden = m.optBoolean("isHidden", false),
                        contextWindow = if (m.has("contextWindow")) m.optInt("contextWindow").takeIf { it > 0 } else null,
                        maxOutputTokens = if (m.has("maxOutputTokens")) m.optInt("maxOutputTokens").takeIf { it > 0 } else null,
                        supportsReasoning = if (m.has("supportsReasoning")) m.optBoolean("supportsReasoning") else null,
                        temperature = if (m.has("temperature")) {
                            m.optDouble("temperature").takeUnless { it.isNaN() }?.takeIf { it in 0.0..2.0 }
                        } else null,
                    ),
                )
            }
        }
        val imageMode = runCatching {
            ImageEndpointMode.valueOf(obj.optString("imageEndpointMode", "auto"))
        }.getOrElse { ImageEndpointMode.auto }
        return TransferableProvider(
            label = label,
            providerType = typeRaw,
            credentialType = obj.optString("credentialType", "apiKey"),
            // `baseURL` is the batch envelope's own key; `customBaseURL` keeps
            // the per-instance spelling so hand-merged files work both ways.
            customBaseURL = obj.optString("baseURL", "").ifEmpty { null }
                ?: obj.optString("customBaseURL", "").ifEmpty { null },
            appendV1Suffix = obj.optBoolean("appendV1Suffix", true),
            useResponsesAPI = obj.optBoolean("useResponsesAPI", false),
            customUserAgent = obj.optString("customUserAgent", "").ifEmpty { null },
            azureMode = obj.optBoolean("azureMode", false),
            imageEndpointMode = imageMode,
            // No `apiKey` key at all → null → the importer creates the
            // instance WITHOUT a credential (keyless compat path).
            apiKey = obj.optString("apiKey", "").trim().ifEmpty { null },
            models = models,
        )
    }

    // ── Dedupe merge ─────────────────────────────────────────────────────

    /**
     * [#24 dedupe] Resolve (name+type) conflicts against the existing
     * provider set: a colliding label gets the " (imported)" suffix; if even
     * that collides, " (imported 2)", " (imported 3)", … — mirroring the
     * existing per-instance importer's ("$label ($suffix)") ladder.
     *
     * Non-conflicting labels pass through unchanged, so re-importing a
     * document whose providers are all absent reproduces the original names
     * exactly. (Built-in free lanes are excluded at export time — they are
     * re-seeded by the build itself and would collide on every fresh
     * install.)
     *
     * @param existingKeys set of lowercase ("label","type") pairs already stored.
     */
    fun dedupeLabels(
        providers: List<TransferableProvider>,
        existingKeys: Set<Pair<String, String>>,
    ): List<TransferableProvider> {
        val taken = existingKeys.toMutableSet()
        return providers.map { p ->
            val key = p.label.lowercase() to p.providerType.lowercase()
            if (key !in taken) {
                taken.add(key)
                p
            } else {
                var suffix = 2
                var candidate: String
                var candidateKey: Pair<String, String>
                do {
                    candidate = if (suffix == 2) "${p.label} (imported)" else "${p.label} (imported ${suffix - 1})"
                    candidateKey = candidate.lowercase() to p.providerType.lowercase()
                    suffix++
                } while (candidateKey in taken)
                taken.add(candidateKey)
                p.copy(label = candidate)
            }
        }
    }
}
