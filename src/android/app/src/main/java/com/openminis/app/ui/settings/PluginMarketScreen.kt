package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.plugins.OnlinePluginStore
import com.openminis.app.plugins.PluginCatalog
import com.openminis.app.plugins.PluginRegistry
import com.openminis.app.tools.FetchUrlGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PluginMarketScreen(
    mcpRepository: MCPRepository?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var plugins by remember { mutableStateOf(PluginRegistry.cached(context)) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var keyTarget by remember { mutableStateOf<PluginRegistry.RemotePlugin?>(null) }
    var keyDraft by remember { mutableStateOf("") }
    val fallbackServers = remember {
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<MCPRepository.MCPServerConfig>())
    }
    val servers by (mcpRepository?.servers ?: fallbackServers).collectAsState()

    LaunchedEffect(Unit) {
        mcpRepository?.reloadFromDisk()
        val result = withContext(Dispatchers.IO) { PluginRegistry.fetch(context) }
        plugins = result.first
        loading = false
        status = if (result.second) {
            context.getString(R.string.plugin_market_live, result.first.size)
        } else {
            context.getString(R.string.plugin_market_cached, result.first.size)
        }
    }

    var installedTick by remember { mutableStateOf(0) }
    installedTick // read so toggles recompose

    SettingsScaffold(
        title = stringResource(R.string.plugin_market_title),
        onBack = null,
    ) {
        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }

        SettingsSection(
            header = stringResource(R.string.plugin_market_section_online),
            footer = stringResource(R.string.plugin_market_footer_online),
        ) {
            if (plugins.isEmpty()) {
                Text(
                    if (loading) stringResource(R.string.plugin_market_loading)
                    else stringResource(R.string.plugin_market_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            plugins.forEachIndexed { index, plugin ->
                val on = OnlinePluginStore.isInstalled(context, plugin.id)
                SettingsSwitchRow(
                    title = plugin.name,
                    subtitle = buildString {
                        append(plugin.description.take(120).ifBlank { plugin.category })
                        if (plugin.authType == "api_key") append(" · API key")
                        append(" · ").append(plugin.tools.size).append(" tools")
                    },
                    checked = on,
                    onCheckedChange = { checked ->
                        if (checked) {
                            FetchUrlGuard.blockedReason(plugin.baseUrl)?.let {
                                status = it
                                return@SettingsSwitchRow
                            }
                            if (plugin.authType == "api_key" &&
                                OnlinePluginStore.apiKey(context, plugin.id).isNullOrBlank()
                            ) {
                                keyDraft = ""
                                keyTarget = plugin
                                return@SettingsSwitchRow
                            }
                            OnlinePluginStore.setInstalled(context, plugin.id, true)
                        } else {
                            OnlinePluginStore.setInstalled(context, plugin.id, false)
                        }
                        installedTick++
                    },
                    showDivider = index < plugins.lastIndex,
                )
            }
        }
    }

    val pending = keyTarget
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { keyTarget = null },
            title = { Text(pending.name) },
            text = {
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    label = { Text(stringResource(R.string.plugin_market_api_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = { keyTarget = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            if (keyDraft.isBlank()) return@TextButton
                            OnlinePluginStore.setApiKey(context, pending.id, keyDraft)
                            OnlinePluginStore.setInstalled(context, pending.id, true)
                            keyTarget = null
                            installedTick++
                        },
                    ) {
                        Text(stringResource(R.string.plugin_market_install))
                    }
                }
            },
        )
    }
}
