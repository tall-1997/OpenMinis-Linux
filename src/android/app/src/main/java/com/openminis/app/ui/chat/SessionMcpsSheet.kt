package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.R
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection

private const val MCP_NOTE_TRUNC = 200

/**
 * Bottom sheet showing all configured MCP servers with per-session
 * enable/disable toggles. Mirrors [SessionSkillsSheet]: [StandardChatSheet]
 * shell + shared [SettingsSection] / [SettingsRow] primitives. Each row shows
 * the server name + truncated note, with a Switch writing a session override
 * via [MCPRepository.setSessionOverride].
 *
 * [T-android-session-mcp-search, port-upstream-1.16] Like the Skills sheet
 * (and iOS SessionMCPsView), the list has a search field (name + note) and
 * Enable All / Disable All acting on the filtered rows — with a dozen MCP
 * servers, flipping each one by hand is the one interaction the sheet made
 * users do most.
 */
@Composable
fun SessionMcpsSheet(
    sessionId: String,
    mcpRepository: MCPRepository,
    onDismiss: () -> Unit,
) {
    val servers by mcpRepository.servers.collectAsStateWithLifecycle()

    // [T-mcp-review-fixes-android] Key the seed on `servers` so the override map
    // re-derives when the live server list arrives. A keyless remember{} seeds
    // once from a possibly-empty first-composition list and then never re-runs,
    // leaving every toggle stuck at its default (the review's stale-override race).
    val overrides = remember(servers) {
        mutableStateMapOf<String, Boolean>().apply {
            for (server in servers) {
                put(server.id, mcpRepository.isEnabledForSession(server.id, sessionId))
            }
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredServers = remember(servers, searchQuery) { filterMcpServers(servers, searchQuery) }

    fun setAll(enabled: Boolean) {
        for (server in filteredServers) {
            overrides[server.id] = enabled
            mcpRepository.setSessionOverride(sessionId, server.id, enabled)
        }
    }

    StandardChatSheet(
        title = stringResource(R.string.session_mcps_title),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (servers.isEmpty()) {
                EmptyMcpsCard()
            } else {
                DialogTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.mcps_search_placeholder),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp),
                )
                // Same header row as the Skills sheet: section label on the
                // left, Enable All / Disable All (on the filtered rows) on the
                // right.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .padding(start = 32.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.mcp_section_servers),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    MinisTextButton(
                        onClick = { setAll(true) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.session_skills_enable_all), fontSize = 12.sp)
                    }
                    MinisTextButton(
                        onClick = { setAll(false) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.session_skills_disable_all), fontSize = 12.sp)
                    }
                }

                if (filteredServers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.mcps_search_no_match, searchQuery),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                    )
                } else {
                    SettingsSection(
                        footer = stringResource(R.string.session_mcps_footer),
                    ) {
                        filteredServers.forEachIndexed { index, server ->
                            val note = server.note?.trim().orEmpty()
                            val sub = note
                                .ifEmpty { server.transportSummary }
                                .let { if (it.length > MCP_NOTE_TRUNC) it.substring(0, MCP_NOTE_TRUNC) + "…" else it }
                            SettingsRow(
                                title = server.id,
                                subtitle = sub.takeIf { it.isNotBlank() },
                                showChevron = false,
                                showDivider = index < filteredServers.size - 1,
                                trailing = {
                                    SettingsSwitch(
                                        checked = overrides[server.id] ?: server.enabled,
                                        onCheckedChange = { enabled ->
                                            overrides[server.id] = enabled
                                            mcpRepository.setSessionOverride(sessionId, server.id, enabled)
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

/** Servers whose name or note contains [query] (trimmed, case-insensitive), like iOS. */
internal fun filterMcpServers(
    servers: List<MCPRepository.MCPServerConfig>,
    query: String,
): List<MCPRepository.MCPServerConfig> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return servers
    return servers.filter { it.id.lowercase().contains(q) || it.note.orEmpty().lowercase().contains(q) }
}

@Composable
private fun EmptyMcpsCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Extension,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
            Text(
                stringResource(R.string.session_mcps_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.session_mcps_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
