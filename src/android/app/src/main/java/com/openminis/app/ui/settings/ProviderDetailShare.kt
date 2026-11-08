package com.openminis.app.ui.settings

import android.content.Context
import com.openminis.app.R
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository

/**
 * [T-provider-detail-ceiling] Extracted from ProviderDetailScreen when the
 * architecture ceiling left no room: the credential-free share pipeline.
 */

/** Export the provider WITHOUT credentials and hand it to the system share sheet. */
internal fun exportProviderInstance(
    context: Context,
    providerRepository: ProviderRepository,
    instance: ProviderInstance,
) {
    // [T-export-credential-option] Shared files leave the device via an
    // arbitrary target — export WITHOUT credentials. The encrypted backup is
    // the flow for moving keys between devices.
    val json = providerRepository.exportInstanceJSON(instance.id, includeCredentials = false)
        ?: run {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.provider_share_no_credentials),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
    val safeLabel = instance.label.ifBlank { "provider" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val file = java.io.File(dir, "$safeLabel.json")
    runCatching { file.writeText(json) }.onFailure { return }

    // [T-share-fileprovider-unify] Shared FileProvider helper (clip + double
    // grant hardening, previously missing on this variant).
    shareViaFileProvider(
        context, file, "application/json",
        context.getString(R.string.provider_share_chooser_title),
    )
}
