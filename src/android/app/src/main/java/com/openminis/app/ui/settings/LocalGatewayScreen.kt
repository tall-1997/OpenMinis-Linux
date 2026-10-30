package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.restoreBuiltInFreeLaneAndRefresh
import com.openminis.app.plugins.builtin.BuiltinPluginCatalog
import com.openminis.app.plugins.builtin.BuiltinPluginInstaller
import com.openminis.app.plugins.builtin.BuiltinPluginStore
import com.openminis.app.plugins.builtin.GatewayServiceManager
import com.openminis.app.provider.BuiltinFreeLanes
import com.openminis.app.provider.LocalGatewayFreeLane
import com.openminis.app.ui.components.MinisCenterTopBar
import com.openminis.app.ui.theme.m3Icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-builtin-gateway-native] The local model gateway's first-class settings
 * page: what the gateway is for, install state, the loopback address the
 * provider row points at, and the run switch.
 *
 * The gateway used to be one row of the plugin market's "Built-in gateways"
 * section, next to the a2a row. a2a left that section for its own settings
 * entry ([A2aScreen]) and the section stopped being a catalog of everything
 * the app can import — it read as a marketplace listing for something the
 * user had already configured elsewhere. A gateway is app infrastructure, not
 * a third-party connector, so it lives in Settings where its provider row,
 * its port and its on/off switch can be read together.
 *
 * Two behaviours here are deliberate and both were absent from the market row:
 *
 *  - **Cold-start truth.** [BuiltinPluginStore] knows whether the payload is
 *    installed, not whether the process is alive. Re-entering the page probes
 *    the loopback port once ([GatewayServiceManager.refresh]) — a switch that
 *    claims "stopped" while the gateway is serving makes the user start a
 *    second copy on top of the first.
 *  - **Failure reasons are labelled.** A failed install shows the localized
 *    status plus the sanitized guest line (see
 *    [com.openminis.app.plugins.builtin.FailureOutput]); raw PRoot allocator
 *    dumps never appear as the row title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalGatewayScreen(
    providerRepository: ProviderRepository?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // The catalog is the single source for id/port/name; the lane owns the
    // URL string the provider instance actually calls.
    val descriptor = remember { BuiltinPluginCatalog.OFM }
    val states by BuiltinPluginStore.states.collectAsStateWithLifecycle()
    val services by GatewayServiceManager.statuses.collectAsStateWithLifecycle()
    val busy by BuiltinPluginInstaller.busy.collectAsStateWithLifecycle()

    val state = states[descriptor.id] ?: BuiltinPluginStore.PluginState(descriptor.id)
    val service = services[descriptor.id] ?: GatewayServiceManager.ServiceStatus(descriptor.id)
    val uiState = localGatewayUiState(state.status, service.state)
    // "Installed" = the payload is on disk and the switch is meaningful. A
    // FAILED marker is not: the payload is incomplete, so the page offers a
    // retry instead of a start button.
    val installed = uiState == LocalGatewayUiState.STOPPED ||
        uiState == LocalGatewayUiState.RUNNING ||
        uiState == LocalGatewayUiState.STARTING
    val progressText = state.progressLine.ifBlank {
        stringResource(R.string.builtin_plugin_stage_payload)
    }
    val failureText = state.diagnosis.ifBlank {
        stringResource(R.string.builtin_plugin_interrupted)
    }

    // Same crash-recovery discipline as the dev-suite section: an INSTALLING
    // marker after process death means the install died — flip it to FAILED so
    // the page offers a retry. Never while an install is actually running.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (!BuiltinPluginInstaller.busy.value) BuiltinPluginStore.recoverInterrupted(context)
            val installed = BuiltinPluginStore.snapshot(context)[descriptor.id]?.status ==
                BuiltinPluginStore.Status.INSTALLED
            if (installed) GatewayServiceManager.refresh(descriptor)
        }
    }

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = {
                    Text(
                        stringResource(descriptor.nameRes),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            m3Icon(Icons.AutoMirrored.Filled.ArrowBack),
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // The "what is this" answer sits in the section footer — the iOS
            // grouped-list idiom for explanatory copy, and the reason the page
            // does not need a marketing paragraph above the controls.
            SettingsSection(
                header = stringResource(R.string.shizuku_status_header),
                footer = stringResource(descriptor.summaryRes),
            ) {
                SettingsRow(
                    title = stringResource(localGatewayLabelRes(uiState)),
                    subtitle = if (uiState == LocalGatewayUiState.INSTALLING) progressText else null,
                    icon = localGatewayIcon(uiState),
                    iconColor = localGatewayColor(uiState),
                    showChevron = false,
                    // The status row is the last row only while installing.
                    showDivider = uiState != LocalGatewayUiState.INSTALLING,
                )
                if (installed) {
                    SettingsValueRow(
                        title = stringResource(R.string.mcp_builtin_port),
                        value = descriptor.servicePort.toString(),
                    )
                    // The exact URL the provider instance calls: copied by hand
                    // into a third-party client, so it is a value row with a
                    // copy button instead of prose.
                    val address = LocalGatewayFreeLane.BASE_URL
                    val clipboard = LocalClipboardManager.current
                    SettingsRow(
                        title = stringResource(R.string.add_provider_endpoint),
                        subtitle = address,
                        showChevron = false,
                        showDivider = false,
                        trailing = {
                            IconButton(onClick = { clipboard.setText(AnnotatedString(address)) }) {
                                Icon(
                                    m3Icon(Icons.Filled.ContentCopy),
                                    contentDescription = stringResource(R.string.common_copy),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                }
                // Failure reason: the sanitized guest line, in error color, on
                // its own row so the status label above stays a label.
                if (uiState == LocalGatewayUiState.FAILED) {
                    SettingsRow(
                        title = failureText,
                        titleColor = MaterialTheme.colorScheme.error,
                        icon = Icons.Outlined.ErrorOutline,
                        iconColor = Color(0xFFFF3B30),
                        showChevron = false,
                        showDivider = false,
                    )
                }
                if (uiState == LocalGatewayUiState.NOT_INSTALLED ||
                    uiState == LocalGatewayUiState.FAILED
                ) {
                    SettingsRow(
                        title = stringResource(
                            if (uiState == LocalGatewayUiState.FAILED) {
                                R.string.builtin_plugin_retry
                            } else {
                                R.string.builtin_plugin_install
                            },
                        ),
                        onClick = if (busy) {
                            null
                        } else {
                            { install(context, descriptor, providerRepository) }
                        },
                        showChevron = false,
                        showDivider = false,
                        trailing = {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        },
                    )
                }
            }

            // Run switch: only meaningful once the payload is on disk, and it
            // never toggles optimistically — STARTING/STOPPING keep it
            // disabled until the service reports a terminal state.
            if (installed) {
                val transitioning =
                    service.state == GatewayServiceManager.ServiceState.STARTING ||
                        service.state == GatewayServiceManager.ServiceState.STOPPING
                SettingsSection(footer = stringResource(R.string.builtin_plugin_section_footer)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.a2a_start_gateway),
                        checked = service.state == GatewayServiceManager.ServiceState.RUNNING,
                        onCheckedChange = { target ->
                            GatewayServiceManager.toggle(descriptor, target)
                        },
                        enabled = !transitioning,
                        showDivider = false,
                    )
                }
            }
        }
    }
}

/**
 * Page state ladder, derived from the two independent sources (install marker,
 * service state). Pure so the ordering rules — a failed install outranks any
 * stale service state — are testable without a Context.
 */
internal enum class LocalGatewayUiState { NOT_INSTALLED, INSTALLING, FAILED, STOPPED, STARTING, RUNNING }

internal fun localGatewayUiState(
    status: BuiltinPluginStore.Status,
    service: GatewayServiceManager.ServiceState,
): LocalGatewayUiState = when {
    status == BuiltinPluginStore.Status.INSTALLING -> LocalGatewayUiState.INSTALLING
    status == BuiltinPluginStore.Status.FAILED -> LocalGatewayUiState.FAILED
    status != BuiltinPluginStore.Status.INSTALLED -> LocalGatewayUiState.NOT_INSTALLED
    service == GatewayServiceManager.ServiceState.RUNNING -> LocalGatewayUiState.RUNNING
    service == GatewayServiceManager.ServiceState.STARTING -> LocalGatewayUiState.STARTING
    else -> LocalGatewayUiState.STOPPED
}

/**
 * Status-row label. Reuses the vocabulary the a2a page and the dev-suite
 * section already ship ("Not installed", "Installing…", "Running"…) so the
 * gateways read the same everywhere and no locale gains a near-duplicate
 * string.
 */
internal fun localGatewayLabelRes(state: LocalGatewayUiState): Int = when (state) {
    LocalGatewayUiState.NOT_INSTALLED -> R.string.dev_suite_not_installed
    LocalGatewayUiState.INSTALLING -> R.string.dev_suite_installing
    LocalGatewayUiState.FAILED -> R.string.local_gateway_install_failed
    LocalGatewayUiState.STOPPED -> R.string.backup_history_stopped
    LocalGatewayUiState.STARTING -> R.string.a2a_panel_starting
    LocalGatewayUiState.RUNNING -> R.string.a2a_status_running
}

private fun localGatewayIcon(state: LocalGatewayUiState): ImageVector = when (state) {
    LocalGatewayUiState.NOT_INSTALLED -> Icons.Outlined.CloudUpload
    LocalGatewayUiState.INSTALLING -> Icons.Outlined.CloudUpload
    LocalGatewayUiState.FAILED -> Icons.Outlined.ErrorOutline
    LocalGatewayUiState.STOPPED -> Icons.Outlined.Stop
    LocalGatewayUiState.STARTING -> Icons.Outlined.CloudUpload
    LocalGatewayUiState.RUNNING -> Icons.Filled.CheckCircle
}

/** Status chip color (iOS-family palette used across Settings). */
private fun localGatewayColor(state: LocalGatewayUiState): Color = when (state) {
    LocalGatewayUiState.RUNNING -> Color(0xFF34C759)
    LocalGatewayUiState.FAILED -> Color(0xFFFF3B30)
    else -> Color(0xFF8E8E93)
}

private fun install(
    context: android.content.Context,
    descriptor: BuiltinPluginCatalog.Descriptor,
    providerRepository: ProviderRepository?,
) {
    // Install runs in the installer's own scope: leaving the page must not
    // cancel a multi-minute payload unpack.
    BuiltinPluginInstaller.installAsync(context, descriptor) { ok ->
        if (!ok) return@installAsync
        // Seed the provider row the moment the install lands, so the user does
        // not have to hunt for the add-provider screen afterwards.
        val lane = BuiltinFreeLanes.ALL.firstOrNull { it.lane.id == descriptor.laneId }
        if (lane != null) providerRepository?.restoreBuiltInFreeLaneAndRefresh(lane)
    }
}
