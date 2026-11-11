package com.openminis.app.data.repository

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType

/**
 * [#24 provider-transfer] Repository-side wiring for the batch provider
 * export / import feature (provider management page menu). Pure string in /
 * string out — the SAF stream handling lives in the UI layer
 * (ProviderListScreen + ProviderExportDialog), the JSON shaping in the
 * pure-JVM [ProviderTransferCodec].
 *
 * Exists as its own class because ProviderRepository sits exactly at its
 * architecture-policy line ceiling (1522) — this file adds the batch
 * surface without growing it, following the same extraction pattern as
 * [ProviderBackupCoordinator] before it.
 *
 * Threading: every mutating call goes through the repository's existing
 * synchronized mutators ([addInstance], [ProviderRepository.saveConfig]
 * under [ProviderRepository.configLock]) — no new locks introduced.
 * Callers invoke on Dispatchers.IO (the UI launchers already do).
 */
internal class ProviderTransferStore(
    private val repo: ProviderRepository,
) {

    /** Result of one batch import — drives the summary toast. */
    data class ImportSummary(
        val importedLabels: List<String>,
        val failed: Int,
    )

    // ── Export ───────────────────────────────────────────────────────────

    /**
     * Build the `{"version":1,"providers":[…]}` document for EVERY stored
     * provider instance, in the user's list order. [includeApiKeys] = false
     * (the dialog default) writes NO credential material at all; true embeds
     * each instance's API key in PLAIN TEXT — the UI must have shown the
     * plaintext-risk confirmation before choosing that.
     *
     * Returns null when the config is still unreadable (cold-start window).
     */
    fun exportProvidersJSON(includeApiKeys: Boolean): String? {
        if (!repo.ensureConfigLoaded()) return null
        val instances = repo.instances
        val providers = instances.map { instance ->
            ProviderTransferCodec.toTransferable(
                instance = instance,
                entries = repo.entriesFor(instance.id),
                apiKey = if (includeApiKeys) repo.loadApiKey(instance.id) else null,
            )
        }
        return ProviderTransferCodec.buildExportDocument(providers)
    }

    /** Suggested SAF document name for the export launcher. */
    fun suggestedFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "minis-providers-$stamp.json"
    }

    // ── Import ───────────────────────────────────────────────────────────

    /**
     * True when [json] looks like a batch transfer document — used by the
     * existing import launcher to route batch JSON to [importProvidersJSON]
     * while per-instance JSON keeps flowing to the historical
     * [ProviderRepository.importInstanceJSON].
     */
    fun isBatchDocument(json: String): Boolean = ProviderTransferCodec.parseDocument(json) != null

    /**
     * [#24 import] Parse the batch document, resolve (name+type) conflicts
     * against the stored providers with the " (imported)" suffix ladder, and
     * land every entry in the existing storage: `addInstance` (+ seeded
     * models immediately replaced by the carried model list) and
     * `saveApiKey` only when the document actually carries a key — a
     * keyless document imports as a keyless instance (no-key compatibility).
     *
     * The custom base URL of an untrusted file is validated with the same
     * https / LAN-only rule the per-instance importer enforces
     * ([T-import-url-validation]); a violating entry fails THAT entry only
     * and is counted in [ImportSummary.failed] instead of aborting the batch.
     */
    fun importProvidersJSON(json: String): ImportSummary {
        // The UI router checks [isBatchDocument] first, so a non-document
        // string never reaches here; the null branch just keeps the API total.
        val parsed = ProviderTransferCodec.parseDocument(json)
            ?: return ImportSummary(emptyList(), failed = 0)
        if (!repo.ensureConfigLoaded()) return ImportSummary(emptyList(), parsed.size)

        val existingKeys = repo.instances.map { it.label.lowercase() to it.providerType.name.lowercase() }.toSet()
        val resolved = ProviderTransferCodec.dedupeLabels(parsed, existingKeys)

        val imported = mutableListOf<String>()
        var failed = 0
        for (p in resolved) {
            try {
                validateImportedBaseURL(p.customBaseURL)
                val instance = ProviderTransferCodec.toInstance(
                    provider = p,
                    id = java.util.UUID.randomUUID().toString(),
                    label = p.label,
                )
                repo.addInstance(instance)
                // Replace the just-seeded built-in entries with the carried
                // list — same sequence as importInstanceJSON.
                val entries: List<ModelEntry> = ProviderTransferCodec.toModelEntries(
                    providerInstanceId = instance.id,
                    providerType = instance.providerType,
                    models = p.models,
                )
                synchronized(repo.configLock) {
                    val cfg = repo.workingCopy()
                    cfg.modelEntries.removeAll { it.providerInstanceId == instance.id }
                    cfg.modelEntries.addAll(entries)
                    repo.saveConfig(cfg)
                }
                // Keyless compat: no apiKey key in the document → no key write.
                p.apiKey?.let { repo.saveApiKey(instance.id, it) }
                imported.add(p.label)
            } catch (e: Exception) {
                android.util.Log.w(
                    "ProviderTransfer",
                    "batch import skipped '${p.label}': ${e.message}",
                )
                failed++
            }
        }
        return ImportSummary(imported, failed)
    }

    /**
     * [T-import-url-validation] Copy of the per-instance importer's rule:
     * an imported custom base URL must be https, or plain http on an
     * explicit local-network host. Violations throw — the batch caller
     * counts them per entry.
     */
    private fun validateImportedBaseURL(raw: String?) {
        val url = raw ?: return
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull()
        val host = uri?.host?.lowercase()
        val scheme = uri?.scheme?.lowercase()
        val isLanHost = host != null && (
            host == "localhost" || host == "::1" || host.startsWith("127.") ||
                host.startsWith("192.168.") || host.startsWith("10.") ||
                (host.startsWith("172.") && host.substringAfter("172.").substringBefore(".").toIntOrNull() in 16..31)
            )
        when {
            scheme == "https" -> return
            scheme == "http" && isLanHost -> return
            scheme == "http" -> throw IllegalArgumentException(
                "Refusing imported provider: custom base URL is plain http on a public host ($host)",
            )
            else -> throw IllegalArgumentException(
                "Refusing imported provider: custom base URL has no valid http(s) scheme",
            )
        }
    }
}
