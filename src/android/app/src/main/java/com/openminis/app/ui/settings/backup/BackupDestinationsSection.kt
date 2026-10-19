package com.openminis.app.ui.settings.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.backup.remote.RcloneRemoteStore

/**
 * [T-android-backup-destination-gate] The destinations a backup will be
 * delivered to, listed inline on the Backup tab. Mirrors iOS
 * `BackupSettingsView.destinationSection`.
 *
 * Android previously surfaced destinations only behind a "Manage Destinations…"
 * button, so the screen never showed whether any existed — a user with none
 * configured saw a normal-looking Start button, got "Backup ready", and ended
 * up with a package that never left the app sandbox.
 */
@Composable
internal fun DestinationsSection(
    destinations: List<RcloneRemoteStore.Remote>,
    phoneFolders: List<com.openminis.app.backup.PhoneBackupFolderStore.Folder>,
    enabled: Boolean,
    onManage: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onTogglePhone: (String, Boolean) -> Unit,
    onAddPhoneFolder: () -> Unit,
) {
    SettingsSection(
        header = stringResource(R.string.backup_section_destinations),
        footer = stringResource(
            if (destinations.isEmpty() && phoneFolders.isEmpty()) R.string.backup_destinations_empty_footer
            else R.string.backup_destinations_footer,
        ),
    ) {
        phoneFolders.forEach { folder ->
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(30.dp).background(Color(0xFF007AFF), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.backup_dest_phone_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    SettingsSwitch(
                        checked = folder.enabled,
                        onCheckedChange = { onTogglePhone(folder.id, it) },
                        enabled = enabled,
                    )
                }
                Box(
                    Modifier.fillMaxWidth().padding(start = 56.dp)
                        .height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        destinations.forEach { remote ->
            DestinationRow(
                remote = remote,
                enabled = enabled,
                onToggle = { on -> onToggle(remote.name, on) },
                onClick = onManage,
            )
        }
        RestoreSourceRow(
            icon = Icons.Outlined.CreateNewFolder,
            iconColor = Color(0xFF34C759),
            label = stringResource(R.string.backup_dest_add_folder),
            enabled = enabled,
            onClick = onAddPhoneFolder,
            showDivider = true,
        )
        RestoreSourceRow(
            icon = Icons.Outlined.Add,
            iconColor = Color(0xFF34C759),
            label = stringResource(R.string.backup_manage_destinations),
            enabled = enabled,
            onClick = onManage,
            showDivider = false,
        )
    }
}

/**
 * One destination: name over "BACKEND · /path", with a switch. Mirrors the
 * server rows in iOS `destinationSection`.
 *
 * The switch controls DELIVERY, not existence — disabling keeps the server and
 * its credential, so skipping one destination for a while costs nothing to
 * undo. That is also why disabled rows stay visible: hiding them would leave
 * the user unable to see the destination still existed, let alone switch it
 * back on.
 */
@Composable
internal fun DestinationRow(
    remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    // Material equivalents of the SF Symbols iOS picks per backend.
    val icon = when (remote.backend) {
        "smb" -> Icons.Outlined.Dns
        "webdav" -> Icons.Outlined.Cloud
        "sftp" -> Icons.Outlined.Terminal
        "s3" -> Icons.Outlined.Storage
        "ftp" -> Icons.Outlined.SwapVert
        else -> Icons.Outlined.Cloud
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                // 12dp, matching every other settings row — 10dp here left this
                // row 4dp shorter than its neighbours.
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(Color(0xFF007AFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    remote.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        R.string.backup_dest_row_subtitle,
                        remote.backend.uppercase(),
                        remote.path,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            SettingsSwitch(checked = remote.enabled, onCheckedChange = onToggle, enabled = enabled)
        }
        Box(
            Modifier.fillMaxWidth().padding(start = 56.dp)
                .height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}
