package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/**
 * [#24 provider-transfer] Confirmation dialog for the batch provider export.
 *
 * The "Include API keys" switch defaults OFF (share-safe); turning it ON
 * reveals the plaintext-risk warning so the user cannot send keys into a
 * file without seeing what that means. The confirm callback receives the
 * switch state — the actual JSON build + SAF write live in
 * [ProviderListScreen]'s CreateDocument launcher.
 */
@Composable
internal fun ProviderExportDialog(
    providerCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (includeApiKeys: Boolean) -> Unit,
) {
    var includeKeys by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.provider_transfer_export_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.provider_transfer_export_body, providerCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // toggleable on the whole row so the switch is
                        // reachable by tap AND by screen readers as one target.
                        .toggleable(value = includeKeys, onValueChange = { includeKeys = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.provider_transfer_include_keys),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = includeKeys, onCheckedChange = { includeKeys = it })
                }
                if (includeKeys) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.provider_transfer_include_keys_risk),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(includeKeys) }) {
                Text(stringResource(R.string.provider_transfer_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
