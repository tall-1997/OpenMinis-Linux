package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.sandbox.SessionWorkspace
import com.openminis.app.ssh.SshEngine
import com.openminis.app.ssh.SshWorkspaceBindings
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-ssh-workspace] (#32) Per-session "SSH workspace" binding sheet.
 *
 * Binds the CURRENT chat session to one configured SSH host: while bound,
 * the agent's shell tools execute on that host (see SshWorkspaceRouter) and
 * file-surface tools are refused. Hosts themselves (credentials, ports,
 * connection test) are managed in Settings → SSH Workspace; this sheet only
 * picks which host this session uses.
 *
 * Visibility rides a process-level [SshWorkspaceSheetState] (the
 * ContextUsageSheetState pattern): ChatTopBarSection's overflow menu calls
 * [SshWorkspaceSheetState.show] with no callback plumbing through the
 * ceiling-pinned ChatScreen, and ChatSheetsHost collects [visible] to
 * render the sheet.
 */
internal object SshWorkspaceSheetState {
    private val _visible = MutableStateFlow(false)

    /** Collect in ChatSheetsHost via collectAsStateWithLifecycle. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun show() {
        _visible.value = true
    }

    fun hide() {
        _visible.value = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SshWorkspaceBindingSheet(
    sessionId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Owner id: draft ids ("...__fld__...") and sub-agent lanes bind the
    // chat session they belong to, not the ephemeral alias.
    val ownerSessionId = remember(sessionId) { SessionWorkspace.ownerSessionId(sessionId) }
    val bindings = remember { SshWorkspaceBindings.forContext(context) }
    var boundHostId by remember(ownerSessionId) { mutableStateOf(bindings.hostIdFor(ownerSessionId)) }
    // Fresh read per open: the store caches in memory, the sheet is short-lived.
    val hosts = remember { SshEngine.backend(context).store.list() }
    val boundHost = remember(boundHostId, hosts) { hosts.firstOrNull { it.id == boundHostId } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.ssh_workspace_binding_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = boundHost?.let {
                    stringResource(R.string.ssh_workspace_bound_to, it.name, it.label)
                } ?: stringResource(R.string.ssh_workspace_bound_local),
                style = MaterialTheme.typography.bodySmall,
                color = ChatColors.secondaryText,
            )
            Spacer(Modifier.height(12.dp))

            // Local sandbox option — unbinds.
            BindingOptionRow(
                icon = { Icon(Icons.Default.Laptop, contentDescription = null) },
                title = stringResource(R.string.ssh_workspace_option_local),
                subtitle = stringResource(R.string.ssh_workspace_option_local_subtitle),
                selected = boundHostId == null,
            ) {
                bindings.unbind(ownerSessionId)
                boundHostId = null
            }

            // One row per configured host.
            hosts.forEach { host ->
                BindingOptionRow(
                    icon = { Icon(Icons.Default.Dns, contentDescription = null) },
                    title = host.name,
                    subtitle = host.label,
                    selected = boundHostId == host.id,
                ) {
                    bindings.bind(ownerSessionId, host.id)
                    boundHostId = host.id
                }
            }

            if (hosts.isEmpty()) {
                Text(
                    text = stringResource(R.string.ssh_workspace_no_hosts),
                    style = MaterialTheme.typography.bodySmall,
                    color = ChatColors.secondaryText,
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.ssh_workspace_shell_only_note),
                style = MaterialTheme.typography.bodySmall,
                color = ChatColors.secondaryText,
            )
        }
    }
}

@Composable
private fun BindingOptionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
    ) {
        icon()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ChatColors.secondaryText,
                )
            }
        }
        RadioButton(selected = selected, onClick = onSelect)
    }
}
