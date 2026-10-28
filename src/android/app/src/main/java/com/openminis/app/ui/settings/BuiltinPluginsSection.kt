package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.restoreBuiltInFreeLaneAndRefresh
import com.openminis.app.plugins.builtin.BuiltinPluginCatalog
import com.openminis.app.plugins.builtin.BuiltinPluginInstaller
import com.openminis.app.plugins.builtin.BuiltinPluginStore
import com.openminis.app.plugins.builtin.GatewayServiceManager
import com.openminis.app.plugins.builtin.NativeGatewayInstaller
import com.openminis.app.plugins.builtin.NativeGatewayService
import com.openminis.app.provider.BuiltinFreeLanes

/**
 * [T-builtin-plugins] "Built-in" section of the plugin market: the gateway
 * payloads the APK ships. One row per descriptor — install (offline, from
 * assets), live install progress, and once installed a service switch plus,
 * for gateways with a web panel, a panel button.
 *
 * The install runs in the installer's own scope
 * ([BuiltinPluginInstaller.installAsync], or [NativeGatewayInstaller] for
 * native plugins) so leaving the screen does not cancel it; the card
 * re-reads the store on return. On success the section seeds the provider
 * row right away ([ProviderRepository.restoreBuiltInFreeLaneAndRefresh]) —
 * the user should not have to hunt for the add-provider screen after an
 * install the app just finished.
 *
 * The service switch is driven by the service statuses only — no
 * optimistic toggling: STARTING/STOPPING render as a disabled switch with
 * a small spinner, and the flip happens when the health probe (or the
 * pidfile kill / TERM) actually confirms it. Guest plugins report through
 * [GatewayServiceManager], native ones through [NativeGatewayService] —
 * both expose the same status shape, so the card code is identical.
 */
@Composable
fun BuiltinPluginsSection(
    providerRepository: ProviderRepository?,
) {
    val context = LocalContext.current
    val states by BuiltinPluginStore.states.collectAsStateWithLifecycle()
    val guestServices by GatewayServiceManager.statuses.collectAsStateWithLifecycle()
    val nativeServices by NativeGatewayService.statuses.collectAsStateWithLifecycle()
    val busy by BuiltinPluginInstaller.busy.collectAsStateWithLifecycle()

    // Crash recovery, same discipline as the dev-suite section: a row stuck
    // in INSTALLING means the process died mid-install — flip it to FAILED
    // so the card offers a retry. Only when nothing is actually running.
    LaunchedEffect(busy) {
        if (!busy) BuiltinPluginStore.recoverInterrupted(context)
    }

    SettingsSection(
        header = stringResource(R.string.builtin_plugin_section_header),
        footer = stringResource(R.string.builtin_plugin_section_footer),
    ) {
        BuiltinPluginCatalog.ALL.forEachIndexed { index, d ->
            val state = states[d.id] ?: BuiltinPluginStore.PluginState(d.id)
            val service = if (d.isNative) {
                nativeServices[d.id] ?: GatewayServiceManager.ServiceStatus(d.id)
            } else {
                guestServices[d.id] ?: GatewayServiceManager.ServiceStatus(d.id)
            }
            val running = service.state == GatewayServiceManager.ServiceState.RUNNING
            val transitioning = service.state == GatewayServiceManager.ServiceState.STARTING ||
                service.state == GatewayServiceManager.ServiceState.STOPPING

            val subtitle = when (state.status) {
                BuiltinPluginStore.Status.INSTALLING ->
                    state.progressLine.ifBlank { stringResource(R.string.builtin_plugin_stage_payload) }
                BuiltinPluginStore.Status.FAILED ->
                    state.diagnosis.ifBlank { stringResource(R.string.builtin_plugin_interrupted) }
                else -> buildString {
                    append(stringResource(d.summaryRes))
                    append(" · v")
                    append(state.version.ifBlank { d.version })
                }
            }

            SettingsRow(
                title = stringResource(d.nameRes),
                subtitle = subtitle,
                showDivider = index < BuiltinPluginCatalog.ALL.size - 1,
                trailing = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (state.status) {
                            BuiltinPluginStore.Status.NOT_INSTALLED, BuiltinPluginStore.Status.FAILED ->
                                TextButton(
                                    onClick = { install(context, d, providerRepository) },
                                    enabled = !busy,
                                ) {
                                    Text(
                                        if (state.status == BuiltinPluginStore.Status.FAILED) {
                                            stringResource(R.string.builtin_plugin_retry)
                                        } else {
                                            stringResource(R.string.builtin_plugin_install)
                                        }
                                    )
                                }
                            BuiltinPluginStore.Status.INSTALLING ->
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            BuiltinPluginStore.Status.INSTALLED -> {
                                if (running && d.panelPath != null) {
                                    TextButton(onClick = { openPanel(context, d) }) {
                                        Text(stringResource(R.string.builtin_plugin_open_panel))
                                    }
                                }
                                Switch(
                                    checked = running,
                                    onCheckedChange = { target ->
                                        if (d.isNative) {
                                            NativeGatewayService.toggle(context, d, target)
                                        } else {
                                            GatewayServiceManager.toggle(d, target)
                                        }
                                    },
                                    enabled = !transitioning,
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

private fun install(
    context: android.content.Context,
    d: BuiltinPluginCatalog.Descriptor,
    providerRepository: ProviderRepository?,
) {
    val onResult: (Boolean) -> Unit = { ok ->
        if (ok) seedLane(d, providerRepository)
    }
    if (d.isNative) {
        NativeGatewayInstaller.installAsync(context, d, onResult)
    } else {
        BuiltinPluginInstaller.installAsync(context, d, onResult)
    }
}

private fun seedLane(
    d: BuiltinPluginCatalog.Descriptor,
    providerRepository: ProviderRepository?,
) {
    // Seed the provider row the moment the install lands — the lane
    // entry is ALL-generic, so this works for every builtin plugin.
    val lane = BuiltinFreeLanes.ALL.firstOrNull { it.lane.id == d.laneId }
    if (lane != null) providerRepository?.restoreBuiltInFreeLaneAndRefresh(lane)
}

private fun openPanel(context: android.content.Context, d: BuiltinPluginCatalog.Descriptor) {
    val url = BuiltinPluginCatalog.panelUrl(d) ?: return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
