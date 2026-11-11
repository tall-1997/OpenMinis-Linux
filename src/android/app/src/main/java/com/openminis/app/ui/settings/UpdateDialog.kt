package com.openminis.app.ui.settings
import com.openminis.app.ui.theme.IosBlue
import com.openminis.app.ui.theme.IosIndigo
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ScrollState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.openminis.app.ui.markdown.MarkdownText
import com.openminis.app.i18n.uppercaseForDisplay
import java.io.File


@Composable
internal fun UpdateDialog(
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
                // [#38] Release notes come verbatim from the GitHub release
                // `body` (update.changelog — see UpdateChecker's KDoc): CI
                // metadata stripped, markdown preserved. Rendered with
                // MarkdownText so headings / lists / links the release author
                // wrote actually show up instead of raw markdown syntax.
                //
                // Releases without notes render NO section at all — no empty
                // panel under the "What's New" header. Network failures never
                // reach this dialog: check() maps them to Error / Forbidden /
                // NetworkUnreachable results that only set statusMessage.
                if (update.changelog.isNotBlank()) {
                    // Collapse state is keyed on the notes themselves so a
                    // re-check that surfaces a different release starts
                    // collapsed again.
                    var notesExpanded by remember(update.changelog) { mutableStateOf(false) }
                    val notesScroll = remember(update.changelog) { ScrollState(0) }
                    Text(
                        stringResource(R.string.check_update_changelog_header).uppercaseForDisplay(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(
                                max = if (notesExpanded) {
                                    UpdateNotesExpandedHeight
                                } else {
                                    UpdateNotesCollapsedHeight
                                },
                            )
                            .verticalScroll(notesScroll),
                    ) {
                        MarkdownText(
                            markdown = update.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        )
                    }
                    // Toggle only when the notes actually overflow the collapsed
                    // cap (ScrollState.maxValue > 0 after layout — content that
                    // fits shows no affordance). Expanded flips it to
                    // "show less"; the expanded cap still scrolls so even very
                    // long notes never push the dialog past the screen.
                    if (notesExpanded || notesScroll.maxValue > 0) {
                        MinisTextButton(
                            onClick = { notesExpanded = !notesExpanded },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(
                                stringResource(
                                    if (notesExpanded) {
                                        R.string.check_update_notes_collapse
                                    } else {
                                        R.string.check_update_notes_expand
                                    },
                                ),
                            )
                        }
                    }
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