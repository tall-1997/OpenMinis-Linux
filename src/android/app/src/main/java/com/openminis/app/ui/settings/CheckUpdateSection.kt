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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.PendingUpdateStore
import com.openminis.app.data.UpdateChecker
import com.openminis.app.data.UpdateDownloadManager
import com.openminis.app.data.UpdateSourceRegistry
import com.openminis.app.data.UpdateSourceRegistry.ProbeResult
import com.openminis.app.data.UpdateSourceRegistry.UpdateSource
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
    // [T-update-source-choice] The user's download source pick for the
    // currently shown update, plus live probe results. Selection persists
    // via UpdateSourceRegistry so the next update pre-selects it.
    var selectedSourceId by remember { mutableStateOf(UpdateSourceRegistry.preferredSourceId(context)) }
    var probeResults by remember { mutableStateOf<List<ProbeResult>>(emptyList()) }
    var probingSources by remember { mutableStateOf(false) }
    // [T-update-rolling-channel] Opt-in toggle for the rolling prerelease
    // channel; persisted, default off so stable users never see CI builds.
    var includeRolling by remember { mutableStateOf(UpdateSourceRegistry.includeRolling(context)) }
    // [T-update-wifi-only] Standing preference: never download over metered
    // networks, no confirmation dialog. Persisted, default off.
    var wifiOnly by remember { mutableStateOf(UpdateDownloadManager.isWifiOnlyPreferred(context)) }
    // [T-update-download-fgs] Ask for notification permission at the moment
    // of user intent (tapping Download), not on screen entry. A refusal does
    // NOT block the download — the FGS still runs, Android 13+ just hides
    // its notification. One ask, never nagged again.
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

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

    // Probe every download source as soon as an update dialog is shown so
    // the list can annotate reachability + latency while the user reads the
    // changelog. Re-runs for each new update (asset URL changes per release).
    LaunchedEffect(update?.apkUrl) {
        val url = update?.apkUrl ?: return@LaunchedEffect
        probingSources = true
        probeResults = UpdateSourceRegistry.probeSources(url)
        probingSources = false
        // Default the selection to the user's saved pick, else the fastest
        // reachable source — a hint, not a decision: the user can override.
        if (selectedSourceId == null || probeResults.none { it.sourceId == selectedSourceId }) {
            selectedSourceId = (
                probeResults.filter { it.reachable }.minByOrNull { it.latencyMs }?.sourceId
                    ?: UpdateSourceRegistry.SOURCES.firstOrNull()?.id
                )
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
            val r = UpdateChecker.check(context, includeRolling)
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
        UpdateDownloadManager.start(
            context,
            intent.apkUrl,
            intent.targetVersionName,
            intent.apkSize,
            intent.apkSha256,
            intent.sourceId,
        )
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
                        when (val r = UpdateChecker.check(context, includeRolling)) {
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
        // [T-update-wifi-only] Never download updates over metered
        // networks. Refuses outright at start() — stronger than the metered
        // confirmation dialog, which this toggle bypasses entirely.
        com.openminis.app.ui.settings.SettingsSwitchRow(
            title = stringResource(R.string.check_update_wifi_only),
            subtitle = stringResource(R.string.check_update_wifi_only_sub),
            checked = wifiOnly,
            onCheckedChange = { checked ->
                wifiOnly = checked
                UpdateDownloadManager.setWifiOnlyPreferred(context, checked)
            },
        )
        // [T-update-rolling-channel] Opt-in for the rolling prerelease
        // channel (android-latest, CI builds). Off by default.
        com.openminis.app.ui.settings.SettingsSwitchRow(
            title = stringResource(R.string.check_update_include_rolling),
            subtitle = stringResource(R.string.check_update_include_rolling_sub),
            checked = includeRolling,
            onCheckedChange = { checked ->
                includeRolling = checked
                UpdateSourceRegistry.setIncludeRolling(context, checked)
                // Re-run the check so the dialog reflects the new channel
                // immediately instead of on the next manual tap.
                if (!checking) {
                    checking = true
                    statusMessage = null
                    scope.launch {
                        when (val r = UpdateChecker.check(context, checked)) {
                            is UpdateChecker.CheckResult.UpdateAvailable -> {
                                update = r
                                statusMessage = null
                            }
                            UpdateChecker.CheckResult.UpToDate -> {
                                update = null
                                statusMessage = context.getString(R.string.check_update_up_to_date)
                            }
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
            showDivider = true,
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
            val releasesUrl = UpdateChecker.RELEASES_URL
            val annotated = buildAnnotatedString {
                append(linkLabel)
                addLink(
                    LinkAnnotation.Url(
                        url = releasesUrl,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                        linkInteractionListener = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(releasesUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                    ),
                    start = 0,
                    end = length,
                )
            }
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
            sources = UpdateSourceRegistry.SOURCES,
            probeResults = probeResults,
            probingSources = probingSources,
            selectedSourceId = selectedSourceId,
            onSelectSource = { id ->
                selectedSourceId = id
                UpdateSourceRegistry.setPreferredSourceId(context, id)
            },
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
                    // [T-update-download-fgs] One-time ask at the moment of
                    // intent. Refusal doesn't block anything — the download
                    // and its foreground service run regardless; Android 13+
                    // merely hides the progress notification.
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    // Kick off the source-selected, resumable, background
                    // downloader. It owns a process-wide scope, so leaving the
                    // screen does not cancel it; re-entering re-collects state.
                    // The source choice + asset digest travel with the
                    // request: the digest gates the finished file, the source
                    // id orders the fallback chain.
                    UpdateDownloadManager.start(
                        context,
                        u.apkUrl,
                        u.versionName,
                        u.apkSizeBytes,
                        u.apkSha256,
                        selectedSourceId,
                    )
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
                            apkSha256 = u.apkSha256,
                            sourceId = selectedSourceId,
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

    // [T-update-metered-guard] The download was parked because the active
    // network is metered. Ask before burning the user's data plan; confirming
    // replays the exact request with allowMetered=true.
    if (dlState.needsMeteredConfirm) {
        AlertDialog(
            onDismissRequest = { UpdateDownloadManager.dismissMeteredConfirm() },
            title = { Text(stringResource(R.string.check_update_metered_title)) },
            text = { Text(stringResource(R.string.check_update_metered_body)) },
            confirmButton = {
                MinisButton(onClick = { UpdateDownloadManager.confirmMetered(context) }) {
                    Text(stringResource(R.string.check_update_metered_confirm))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { UpdateDownloadManager.dismissMeteredConfirm() }) {
                    Text(stringResource(R.string.check_update_metered_cancel))
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
    sources: List<UpdateSource>,
    probeResults: List<ProbeResult>,
    probingSources: Boolean,
    selectedSourceId: String?,
    onSelectSource: (String) -> Unit,
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
                // [T-update-source-choice] The user picks the download source;
                // the app no longer silently races mirrors. Each row carries a
                // live reachability annotation so the choice is informed.
                if (!downloadActive && downloadProgress == null) {
                    Column {
                        Text(
                            stringResource(R.string.check_update_source_header).uppercaseForDisplay(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        sources.forEach { src ->
                            val probe = probeResults.firstOrNull { it.sourceId == src.id }
                            val selected = selectedSourceId == src.id
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSelectSource(src.id) }
                                    .padding(vertical = 4.dp),
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { onSelectSource(src.id) },
                                )
                                Column(modifier = Modifier.padding(start = 4.dp)) {
                                    Text(
                                        text = sourceLabel(src),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    val status = when {
                                        probingSources || probe == null ->
                                            stringResource(R.string.check_update_source_probing)
                                        probe.reachable ->
                                            stringResource(R.string.check_update_source_latency, probe.latencyMs)
                                        else ->
                                            stringResource(R.string.check_update_source_unreachable)
                                    }
                                    Text(
                                        text = status,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (probe?.reachable == false) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
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

/** Display name for a download source id; falls back to the raw id. */
@Composable
private fun sourceLabel(source: UpdateSource): String = when (source.id) {
    "github-direct" -> stringResource(R.string.check_update_source_github)
    "gh-proxy" -> stringResource(R.string.check_update_source_ghproxy)
    else -> source.id
}
