package com.openminis.app.ui.settings

/**
 * Result of the file-picked provider import, computed on Dispatchers.IO and
 * turned into a toast back on the main thread — see [ProviderListScreen]'s
 * importLauncher for why the two halves are separated. Extracted verbatim
 * from [ProviderListScreen] (private → internal) as a mechanical split to
 * keep the screen file under the new-file cap.
 */
internal sealed class ProviderImportOutcome {
    data object ExtractFailed : ProviderImportOutcome()
    data object NoSupported : ProviderImportOutcome()
    data object Invalid : ProviderImportOutcome()
    data object ReadFailed : ProviderImportOutcome()
    data class Imported(val label: String) : ProviderImportOutcome()
    data class ZipSummary(val ok: Int, val total: Int) : ProviderImportOutcome()
    // [#24] A batch transfer document was imported; firstFailure ("label: reason") drives the toast detail.
    data class BatchImported(
        val imported: Int,
        val failed: Int,
        val firstFailure: String? = null,
    ) : ProviderImportOutcome()
}
