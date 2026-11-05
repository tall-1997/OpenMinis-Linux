package com.openminis.app.ui.settings

/**
 * [T-share-fileprovider-unify] Single FileProvider share helper for the
 * settings package. Previously four near-copies drifted apart:
 * `exportProviderInstance` (ProviderDetailScreen), `shareMcpServerJson`
 * (MCPIntegrationsScreen), `shareSkillZip` (SkillsManagementScreen) and
 * `shareLogFile` (LogManagementScreen). Only the log variant carried the
 * Issue #17 hardening (clipData + grant flag on the outer chooser intent);
 * the other three shipped without it and could hit SecurityException
 * "Permission Denial: opening provider … requires the provider be exported,
 * or grantUriPermission()" on OEM chooser hosts.
 *
 * This version is the union of all four, built on the shared
 * `${packageName}.fileprovider` authority (Issue #21: mime/authority/grant
 * consistency):
 *   - EXTRA_STREAM content:// URI from [androidx.core.content.FileProvider]
 *   - ClipData carrying the same URI (chooser hosts introspect it when
 *     EXTRA_STREAM alone is not enough — Issue #17)
 *   - FLAG_GRANT_READ_URI_PERMISSION on BOTH the send intent and the outer
 *     chooser intent (the chooser starts the receiver on some OEM builds)
 *   - FLAG_ACTIVITY_NEW_TASK (skill/MCP/provider variants)
 *
 * Returns false when the FileProvider cannot map the file (bad authority or
 * missing files-path root) or no activity accepted the chooser — callers
 * surface their own failure UX (Toast, log fallback, or silence).
 */
internal fun shareViaFileProvider(
    context: android.content.Context,
    file: java.io.File,
    mime: String,
    chooserTitle: String,
): Boolean {
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
    } catch (t: Throwable) {
        return false
    }
    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mime
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        // Clip carries the URI in a way that downstream chooser hosts can
        // introspect for the grant when EXTRA_STREAM alone isn't enough
        // (Issue #17 — SecurityException "Permission Denial: opening
        // provider … requires the provider be exported, or
        // grantUriPermission()").
        clipData = android.content.ClipData.newRawUri(file.name, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = android.content.Intent.createChooser(sendIntent, chooserTitle)
    // The chooser itself starts the receiver; on some OEM builds the grant
    // flag has to live on the outer intent too or the receiver hits
    // SecurityException reading the content:// FileProvider URI.
    chooser.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(chooser)
        true
    } catch (t: Throwable) {
        false
    }
}
