package com.openminis.app.ui.settings.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.backup.remote.RcloneBackendCatalog
import com.openminis.app.backup.remote.RcloneRemoteStore
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitchRow
import com.openminis.app.ui.settings.SettingsValueRow
import java.text.DateFormat
import java.util.Date

/**
 * [T-android-backup-dest-edit] Which destination's detail page is showing,
 * and which of its sub-pages. Hosted inside [RcloneDestinationsScreen] rather
 * than as its own route: a rename changes the name a route argument would be
 * keyed on, and the host already switches its content in place for the add
 * flow. Port of upstream 1.16's BackupDestinationDetail.kt.
 */
@Stable
internal class DestinationDetailNav {
    var name by mutableStateOf<String?>(null)
        private set
    var editingConnection by mutableStateOf(false)

    val isOpen: Boolean get() = name != null

    fun open(destination: String) {
        name = destination
        editingConnection = false
    }

    fun renamed(to: String) {
        name = to
    }

    fun close() {
        name = null
        editingConnection = false
    }

    /** One step back: folder browser or editor → detail → list. */
    fun back(vm: RcloneDestinationsViewModel) {
        when {
            vm.browse.value?.existing == true -> vm.cancelPending()
            editingConnection -> {
                vm.clearError()
                vm.clearCertificateRejection()
                editingConnection = false
            }
            else -> {
                vm.clearError()
                close()
            }
        }
    }

    @Composable
    fun title(): String? = when {
        name == null -> null
        editingConnection -> stringResource(R.string.backup_dest_edit_connection_title)
        else -> name
    }
}

/**
 * Details of one saved destination — the Android peer of iOS
 * `BackupDestinationDetailView`. Rename and re-point the folder in place,
 * edit the connection (tested before it is saved), test it, remove it.
 *
 * The password is never shown. It lives in the Keystore-backed store and
 * nothing needs to read it back; the editor only replaces or clears it.
 */
@Composable
internal fun DestinationDetailContent(vm: RcloneDestinationsViewModel, nav: DestinationDetailNav) {
    val remotes by vm.remotes.collectAsStateWithLifecycle()
    val browse by vm.browse.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val name = nav.name ?: return
    val r = remotes.firstOrNull { it.name == name }
    if (r == null) {
        // Removed (here or elsewhere) while open: nothing left to show.
        LaunchedEffect(name) { nav.close() }
        return
    }

    if (browse?.existing == true) {
        // The folder browser over the saved server. The scaffold's onBack and
        // [DestinationDetailNav.back] both cancel it without saving.
        BackHandler { nav.back(vm) }
        FolderBrowser(vm) { }
        return
    }
    if (nav.editingConnection) {
        ConnectionEditor(vm, r, onDone = { nav.editingConnection = false }, onCancel = { nav.back(vm) })
        return
    }

    var renaming by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    // (ok, message) of the last test; reset when the destination changes.
    var testResult by remember(r) { mutableStateOf<Pair<Boolean, String?>?>(null) }

    val backend = RcloneBackendCatalog.backend(r.backend)
    SettingsSection(
        header = stringResource(R.string.backup_dest_configuration),
        footer = stringResource(R.string.backup_dest_detail_footer),
    ) {
        SettingsValueRow(
            title = stringResource(R.string.backup_dest_type),
            value = backend?.title ?: r.backend.uppercase(),
        )
        SettingsValueRow(
            title = stringResource(R.string.backup_dest_name),
            value = r.name,
            onClick = if (busy) null else ({ renaming = true }),
        )
        SettingsRow(
            title = stringResource(R.string.backup_dest_backup_folder),
            onClick = if (busy) null else ({ vm.browseExisting(r.name) }),
            showChevron = !busy,
            trailing = {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        "/${r.path.trimStart('/')}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        // Truncate the FRONT: the deepest component is the one
                        // that says where the backups go.
                        overflow = TextOverflow.StartEllipsis,
                    )
                }
            },
        )
        // What the user typed when adding it, minus the secret — and minus keys
        // no longer on the backend's form (an old SMB `share`), which would
        // state a setting that no longer has any effect.
        backend?.fields?.filter { !it.isSecret }?.forEach { f ->
            val v = r.params[f.key]
            if (!v.isNullOrEmpty()) SettingsValueRow(title = f.label, value = v)
        }
        if (r.createdAt > 0) {
            SettingsValueRow(
                title = stringResource(R.string.backup_dest_added),
                value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(r.createdAt)),
            )
        }
        // Addresses move and passwords rotate; retyping the whole server for
        // one field is worse than editing it, as long as the result is
        // re-tested — the editor does that before saving.
        SettingsRow(
            title = stringResource(R.string.backup_dest_edit_connection),
            titleColor = MaterialTheme.colorScheme.primary,
            onClick = if (busy) null else ({
                vm.clearError()
                vm.clearCertificateRejection()
                nav.editingConnection = true
            }),
            showDivider = false,
        )
    }

    error?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )
    }

    SettingsSection(
        footer = stringResource(R.string.backup_dest_remove_footer),
    ) {
        SettingsRow(
            title = stringResource(R.string.backup_dest_test_connection),
            titleColor = MaterialTheme.colorScheme.primary,
            onClick = if (testing) null else ({
                testing = true
                testResult = null
                vm.testConnection(r.name) { ok, msg ->
                    testing = false
                    testResult = ok to msg
                }
            }),
            showChevron = false,
            trailing = {
                when {
                    testing -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    testResult?.first == true -> Text(
                        stringResource(R.string.backup_dest_connected),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF34A853),
                    )
                }
            },
        )
        // A failure is the rclone error — a sentence, so it gets its own line
        // rather than being squeezed beside the button.
        testResult?.takeIf { !it.first }?.let { (_, msg) ->
            Text(
                msg.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        SettingsRow(
            title = stringResource(R.string.backup_dest_remove_destination),
            titleColor = MaterialTheme.colorScheme.error,
            onClick = if (busy) null else ({ confirmRemove = true }),
            showChevron = false,
            showDivider = false,
        )
    }

    if (renaming) {
        var draft by remember { mutableStateOf(r.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.backup_dest_rename_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.backup_dest_rename_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(stringResource(R.string.backup_dest_name)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        renaming = false
                        vm.rename(r.name, draft) { nav.renamed(it.name) }
                    },
                    enabled = draft.trim().isNotEmpty(),
                ) { Text(stringResource(R.string.backup_dest_save)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { renaming = false }) {
                    Text(stringResource(R.string.backup_dest_cancel))
                }
            },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.backup_dest_remove_confirm_title)) },
            // Say what is NOT destroyed — "remove" otherwise reads as though it
            // deletes the backups stored there.
            text = { Text(stringResource(R.string.backup_dest_remove_confirm_note)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    confirmRemove = false
                    vm.remove(r.name)
                    nav.close()
                }) {
                    Text(
                        stringResource(R.string.backup_dest_remove_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(R.string.backup_dest_cancel))
                }
            },
        )
    }
}

/**
 * Edit a saved server's address and credentials. Mirrors iOS
 * `RcloneConnectionEditor`.
 *
 * The password field starts empty and is only written when something is
 * typed: leaving it alone keeps the stored credential, which is what someone
 * changing only a hostname expects. "No password (anonymous)" clears it.
 * Save connects with the new settings first and stores nothing if that fails.
 */
@Composable
private fun ConnectionEditor(
    vm: RcloneDestinationsViewModel,
    remote: RcloneRemoteStore.Remote,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val certificateRejected by vm.certificateRejected.collectAsStateWithLifecycle()
    val backend = RcloneBackendCatalog.backend(remote.backend) ?: return
    val secretField = backend.fields.firstOrNull { it.isSecret }

    val values = remember(remote.name) {
        mutableStateMapOf<String, String>().apply {
            backend.fields.filter { !it.isSecret }.forEach { f -> put(f.key, remote.params[f.key].orEmpty()) }
        }
    }
    var secret by remember(remote.name) { mutableStateOf("") }
    var anonymous by remember(remote.name) { mutableStateOf(false) }
    var allowInsecureTLS by remember(remote.name) { mutableStateOf(remote.allowInsecureTLS) }

    Column(Modifier.padding(16.dp)) {
        Text(
            stringResource(R.string.backup_dest_connection_header).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        backend.fields.filter { !it.isSecret }.forEach { f ->
            Spacer(Modifier.height(8.dp))
            ServerFieldInput(
                field = f,
                value = values[f.key].orEmpty(),
                onValueChange = { values[f.key] = it },
                enabled = !busy,
            )
        }

        if (secretField != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.backup_dest_password_header).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ServerFieldInput(
                field = secretField,
                value = secret,
                onValueChange = { secret = it },
                enabled = !busy && !anonymous,
                label = if (secretField.key == "pass") {
                    stringResource(R.string.backup_dest_new_password)
                } else {
                    secretField.label
                },
                supportingText = stringResource(
                    if (anonymous) R.string.backup_dest_password_will_clear
                    else R.string.backup_dest_password_keep_hint,
                ),
            )
            // Only where the backend can actually connect without one; an
            // anonymous SFTP or S3 server is not a configuration to offer.
            if (secretField.isOptional) {
                SettingsSwitchRow(
                    title = stringResource(R.string.backup_dest_no_password),
                    checked = anonymous,
                    onCheckedChange = { anonymous = it },
                    enabled = !busy,
                    showDivider = false,
                )
            }
        }

        // Offered once the server has refused the certificate, or when the
        // destination already trusts it (so that can be turned back off).
        if (certificateRejected || remote.allowInsecureTLS) {
            Spacer(Modifier.height(8.dp))
            TrustCertificateRow(
                checked = allowInsecureTLS,
                onCheckedChange = { allowInsecureTLS = it },
                enabled = !busy,
            )
        }

        Spacer(Modifier.height(16.dp))
        MinisButton(
            onClick = {
                vm.saveConnection(
                    name = remote.name,
                    values = values.toMap(),
                    secret = when {
                        anonymous -> RcloneRemoteStore.SecretEdit.Clear
                        secret.isEmpty() -> RcloneRemoteStore.SecretEdit.Keep
                        else -> RcloneRemoteStore.SecretEdit.Replace(secret)
                    },
                    allowInsecureTLS = allowInsecureTLS,
                    onDone = { onDone() },
                )
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.backup_dest_save))
            }
        }
        MinisOutlinedButton(
            onClick = onCancel,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(stringResource(R.string.backup_dest_cancel)) }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
