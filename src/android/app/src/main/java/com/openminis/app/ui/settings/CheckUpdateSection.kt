package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.ClickableText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.PendingUpdateStore
import com.openminis.app.data.UpdateChecker
import com.openminis.app.data.UpdateDownloadManager
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.i18n.uppercaseForDisplay
import java.io.File

/**
 * Settings section that talks to [UpdateChecker] to surface a "Check for
 * Updates" affordance. Drop in anywhere — typically the bottom of an About
 * screen — and it owns its own state, dialogs, and download UI.
 *
 * The section is no-op visible: a button + transient status text. When an
 * update is found we open a modal AlertDialog showing the changelog and a
 * Download button; the dialog stays open through the download so the user
 * can watch progress.
 */
@Composable
fun CheckUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val selfBuild by SelfBuildRunner.state.collectAsState()
    // When the GitHub API returns 403 / 451 we surface a dedicated row with a
    // tappable "Open GitHub Releases" link beneath the row, so users behind a
    // geo-block know what to do without hunting for the URL themselves.
    var showReleasesLink by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateChecker.CheckResult.UpdateAvailable?>(null) }
    // Download progress is mirrored from UpdateDownloadManager's process-wide
    // StateFlow so the download survives leaving this screen (background
    // tolerant). Re-entering simply re-collects the live state.
    val dlState by UpdateDownloadManager.state.collectAsState()
    // Progress/error that originate from the background downloader are read
    // straight off the StateFlow. Local UI errors (install-launch failure) live
    // in their own mutable slot so they can be assigned/cleared.
    val dlError: String? = dlState.error
    var uiError by remember { mutableStateOf<String?>(null) }
    val downloadError: String? = uiError ?: dlError
    var confirmSelfBuild by remember { mutableStateOf(false) }

    // --- resumable flow state ------------------------------------------------
    //
    // Everything that decides "what should this screen be doing right now" is
    // re-read from the PackageManager / PendingUpdateStore rather than held in
    // remember{} slots.
    //
    // The old design kept `awaitingInstallPerm` in a remember{} slot, and the
    // only dialog that could rescue a stalled flow was gated on it. A process
    // kill while the user was in system Settings reset it to false, so on
    // return the prompt silently vanished and the flow was stranded — the
    // download button was disabled (see below) and nothing offered to install.
    var canInstallNow by remember { mutableStateOf(UpdateChecker.canInstall(context)) }
    var pendingIntent by remember { mutableStateOf(PendingUpdateStore.getPendingIntent(context)) }
    var pendingRecord by remember { mutableStateOf(PendingUpdateStore.getPending(context)) }
    // Lets the user wave off the "staged APK" prompt for this screen visit.
    // The record is untouched, so the next entry offers it again.
    var stagedPromptDismissed by remember { mutableStateOf(false) }

    // The staged APK, whether it finished downloading in THIS process
    // (dlState.doneFile) or a previous one (the persisted record). Without the
    // second source, a cold start after "download done, permission still
    // pending" shows a blank screen and forgets the ~98 MB file entirely.
    val stagedApk: File? = dlState.doneFile
        ?: pendingRecord?.let { rec -> File(rec.apkPath).takeIf { it.exists() } }
    // An APK that is on disk but not yet handed to the installer.
    val stagedNotInstalled = stagedApk != null && !dlState.installLaunched
    // We auto-fire the installer exactly ONCE — on the resume where the user
    // has just granted permission. After that the user drives it, otherwise
    // every resume would re-open the system installer.
    val installAlreadyLaunched = (pendingRecord?.installLaunchedAtMs ?: 0L) > 0L
    val shouldAutoInstall = stagedNotInstalled && !installAlreadyLaunched
    val needsInstallPerm = !canInstallNow

    // Progress is a DISPLAY value only. It deliberately does not gate the
    // dialog's action button any more: `enabled = downloadProgress == null`
    // was permanently false once a download completed (doneFile stays non-null
    // for the life of the process), so any flow that failed to reach the
    // installer — MIUI refusing the intent, permission revoked mid-flight, a
    // failed integrity check — ended on a dead button whose only way out,
    // "Cancel", discarded the whole flow.
    //
    // Keyed on dlState.doneFile (this process) rather than stagedApk, so a
    // cold start that only knows about the persisted APK doesn't render a
    // bogus 0% bar.
    val downloadProgress: Float? = if (dlState.running || dlState.doneFile != null) dlState.progress else null

    fun refreshFlowState() {
        canInstallNow = UpdateChecker.canInstall(context)
        pendingIntent = PendingUpdateStore.getPendingIntent(context)
        pendingRecord = PendingUpdateStore.getPending(context)
    }

    /** Verify + launch the installer for the staged APK. */
    fun launchInstaller() {
        if (!UpdateChecker.canInstall(context)) {
            // Permission went away between the tap and the launch.
            canInstallNow = false
            return
        }
        UpdateChecker.installStagedApk(context) { ok ->
            if (ok) {
                UpdateDownloadManager.markInstallLaunched()
                refreshFlowState()
                update = null
                uiError = null
            } else {
                uiError = context.getString(R.string.check_update_install_launch_failed)
                refreshFlowState()
            }
        }
    }

    // Housekeeping on every entry: drop stale/installed APKs from the private
    // updates dir so old installers don't pile up.
    LaunchedEffect(Unit) {
        UpdateDownloadManager.pruneUpdateDir(context)
        refreshFlowState()
    }

    // Cold-start rehydration. If the previous process died mid-flow there is no
    // CheckResult in memory, so the update dialog has nothing to render and the
    // section looks empty even though an APK is sitting on disk. Re-run the
    // (cheap) release check once to rebuild it. A failure here is harmless —
    // the persisted records still drive the install path.
    LaunchedEffect(Unit) {
        if (update == null && PendingUpdateStore.hasPendingWork(context)) {
            val r = UpdateChecker.check(context)
            if (r is UpdateChecker.CheckResult.UpdateAvailable) update = r
        }
    }

    // The user asked for a download but we had to send them to Settings for
    // install permission first (see onDownload below). On return, if the
    // permission is now granted, start exactly the download they asked for.
    LaunchedEffect(canInstallNow, pendingIntent) {
        val intent = pendingIntent ?: return@LaunchedEffect
        if (!UpdateChecker.canInstall(context)) return@LaunchedEffect
        PendingUpdateStore.clearPendingIntent(context)
        pendingIntent = null
        UpdateDownloadManager.start(context, intent.apkUrl, intent.targetVersionName, intent.apkSize)
    }

    // Download finished — fire the installer, but only when permission is
    // already in hand. If it is not, we leave the staged APK alone and the
    // permission prompt takes over; nothing is lost, because both the file and
    // its record are on disk.
    //
    // Keyed on `installAlreadyLaunched` too, so a permission toggle later in
    // the session cannot re-trigger an install the user already backed out of.
    LaunchedEffect(dlState.doneFile, canInstallNow, installAlreadyLaunched) {
        if (!shouldAutoInstall) return@LaunchedEffect
        if (!UpdateChecker.canInstall(context)) return@LaunchedEffect
        launchInstaller()
    }

    // Resume handling. Registered with DisposableEffect so the observer is
    // REMOVED when this composable leaves composition — the previous
    // LaunchedEffect version added one observer per entry and never removed
    // any, so after N visits a single ON_RESUME fired the system installer N
    // times (N stacked install prompts).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            refreshFlowState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsSection(
        header = stringResource(R.string.check_update_section_header),
        footer = stringResource(R.string.check_update_current_version, BuildConfig.VERSION_NAME),
    ) {
        SettingsRow(
            icon = Icons.Outlined.SystemUpdate,
            iconColor = Color(0xFF007AFF),
            title = stringResource(
                if (checking) R.string.check_update_checking
                else R.string.check_update_check_button
            ),
            subtitle = statusMessage,
            showDivider = true,
            onClick = if (checking) null else {
                {
                    checking = true
                    statusMessage = null
                    showReleasesLink = false
                    scope.launch {
                        when (val r = UpdateChecker.check(context)) {
                            is UpdateChecker.CheckResult.UpdateAvailable -> {
                                update = r
                                statusMessage = null
                            }
                            UpdateChecker.CheckResult.UpToDate ->
                                statusMessage = context.getString(R.string.check_update_up_to_date)
                            UpdateChecker.CheckResult.NoReleaseAvailable ->
                                statusMessage = context.getString(R.string.check_update_no_release)
                            is UpdateChecker.CheckResult.NoApkAsset ->
                                statusMessage = context.getString(R.string.check_update_no_apk_asset, r.tagName)
                            UpdateChecker.CheckResult.Forbidden -> {
                                statusMessage = context.getString(R.string.update_error_forbidden_with_link)
                                showReleasesLink = true
                            }
                            UpdateChecker.CheckResult.NetworkUnreachable ->
                                statusMessage = context.getString(R.string.update_error_network_unreachable)
                            is UpdateChecker.CheckResult.Error ->
                                statusMessage = context.getString(R.string.check_update_error, r.message)
                        }
                        checking = false
                    }
                }
            },
        )
        SettingsRow(
            icon = Icons.Outlined.Build,
            iconColor = Color(0xFF5856D6),
            title = stringResource(R.string.check_update_self_build),
            subtitle = selfBuild.message ?: stringResource(R.string.check_update_self_build_sub),
            showDivider = false,
            onClick = if (selfBuild.running) null else {
                { confirmSelfBuild = true }
            },
        )
        if (confirmSelfBuild) {
            AlertDialog(
                onDismissRequest = { confirmSelfBuild = false },
                title = { Text(stringResource(R.string.check_update_self_build_confirm_title)) },
                text = { Text(stringResource(R.string.check_update_self_build_confirm_body)) },
                confirmButton = {
                    MinisTextButton(onClick = {
                        confirmSelfBuild = false
                        SelfBuildRunner.start(context)
                    }) { Text(stringResource(R.string.check_update_self_build_run)) }
                },
                dismissButton = {
                    MinisTextButton(onClick = { confirmSelfBuild = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
        if (showReleasesLink) {
            val linkLabel = stringResource(R.string.update_error_open_releases)
            val annotated = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )
                ) {
                    append(linkLabel)
                }
                addStringAnnotation(
                    tag = "URL",
                    annotation = UpdateChecker.RELEASES_URL,
                    start = 0,
                    end = length,
                )
            }
            ClickableText(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                onClick = { offset ->
                    annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()
                        ?.let { ann ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(ann.item))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                },
            )
        }
    }

    update?.let { u ->
        UpdateDialog(
            update = u,
            downloadProgress = downloadProgress,
            downloadError = downloadError,
            needsInstallPerm = needsInstallPerm,
            installReady = stagedNotInstalled,
            probing = dlState.probing,
            activeNode = dlState.activeNode,
            downloadActive = dlState.running,
            onDownload = {
                // Ask for install permission BEFORE the download, not after.
                //
                // This is the fix that removes the failure mode rather than
                // patching its symptoms. Downloading first means the user ends
                // up in system Settings with a ~98 MB APK already on disk and
                // a process that MIUI is free to kill while they are away —
                // which is exactly the trip that used to strand the flow. With
                // the permission in hand up front, the download lands on a
                // device that can actually install it and the installer fires
                // straight off `dlState.doneFile` with no round trip.
                if (UpdateChecker.canInstall(context)) {
                    // Kick off the mirror-accelerated, resumable, background
                    // downloader. It owns a process-wide scope, so leaving the
                    // screen does not cancel it; re-entering re-collects state.
                    UpdateDownloadManager.start(context, u.apkUrl, u.versionName, u.apkSizeBytes)
                } else {
                    // Persist the request so the trip to Settings — and a
                    // process kill while there — cannot lose it. The
                    // LaunchedEffect above starts the download on return.
                    PendingUpdateStore.setPendingIntent(
                        context,
                        PendingUpdateStore.PendingIntent(
                            targetVersionName = u.versionName,
                            apkUrl = u.apkUrl,
                            apkSize = u.apkSizeBytes,
                            requestedAtMs = System.currentTimeMillis(),
                        ),
                    )
                    pendingIntent = PendingUpdateStore.getPendingIntent(context)
                    UpdateChecker.openInstallPermissionSettings(context)
                }
            },
            onInstall = { launchInstaller() },
            onOpenSettings = { UpdateChecker.openInstallPermissionSettings(context) },
            onDismiss = {
                // Allow closing once nothing is actively downloading. The
                // downloader keeps the completed file and PendingUpdateStore
                // keeps the record, so a re-visit resumes the install — and
                // even a process death cannot lose them.
                if (!dlState.running) {
                    update = null
                    uiError = null
                }
            },
        )
    }

    // A staged APK that the main dialog is not covering. Two ways in: the user
    // dismissed the dialog mid-flow, or the process died and came back with no
    // CheckResult to render the main dialog from. Either way the downloaded
    // APK must not be silently stranded — the old gate (`awaitingInstallPerm`)
    // was a remember{} slot, so precisely the process-death case could never
    // reach this prompt.
    if (update == null && stagedNotInstalled && !stagedPromptDismissed) {
        AlertDialog(
            onDismissRequest = { stagedPromptDismissed = true },
            title = {
                Text(
                    stringResource(
                        if (needsInstallPerm) R.string.check_update_install_perm_required
                        else R.string.check_update_staged_title,
                    ),
                )
            },
            text = {
                if (needsInstallPerm) {
                    // No version placeholder on this one.
                    Text(stringResource(R.string.check_update_download_complete_install_hint))
                } else {
                    Text(
                        stringResource(
                            R.string.check_update_staged_hint,
                            pendingRecord?.targetVersionName.orEmpty(),
                        ),
                    )
                }
            },
            confirmButton = {
                if (needsInstallPerm) {
                    MinisButton(onClick = { UpdateChecker.openInstallPermissionSettings(context) }) {
                        Text(stringResource(R.string.check_update_open_install_settings))
                    }
                } else {
                    MinisButton(onClick = { launchInstaller() }) {
                        Text(stringResource(R.string.check_update_install_now))
                    }
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { stagedPromptDismissed = true }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun UpdateDialog(
    update: UpdateChecker.CheckResult.UpdateAvailable,
    downloadProgress: Float?,
    downloadError: String?,
    needsInstallPerm: Boolean,
    installReady: Boolean,
    probing: Boolean,
    activeNode: String?,
    downloadActive: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.check_update_available_title))
                Text(
                    stringResource(
                        R.string.check_update_available_subtitle,
                        BuildConfig.VERSION_NAME,
                        update.versionName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.check_update_changelog_header).uppercaseForDisplay(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = update.changelog.ifBlank {
                            stringResource(R.string.check_update_changelog_empty)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }
                Text(
                    stringResource(R.string.check_update_install_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (probing) {
                    Text(
                        stringResource(R.string.check_update_probing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (activeNode != null && downloadActive) {
                    Text(
                        stringResource(R.string.check_update_active_node, activeNode),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.check_update_downloading, (downloadProgress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (downloadError != null) {
                    Text(
                        stringResource(R.string.check_update_download_failed, downloadError),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (needsInstallPerm) {
                    Text(
                        stringResource(R.string.check_update_install_perm_required),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            when {
                // Install permission is the blocker; nothing else can proceed.
                needsInstallPerm -> MinisButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.check_update_open_install_settings))
                }
                // Already downloaded. Offering "Download & Install" again here
                // is what produced the dead, permanently-disabled button —
                // `enabled` was computed from a `downloadProgress` that never
                // returns to null once a download completes. The honest action
                // for a staged APK is install.
                installReady -> MinisButton(onClick = onInstall) {
                    Text(stringResource(R.string.check_update_install_now))
                }
                else -> MinisButton(
                    onClick = onDownload,
                    enabled = !downloadActive,
                ) {
                    if (downloadActive) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                            )
                            Text(stringResource(R.string.check_update_downloading, ((downloadProgress ?: 0f) * 100).toInt()))
                        }
                    } else {
                        Text(stringResource(R.string.check_update_download_button))
                    }
                }
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss, enabled = !downloadActive) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
