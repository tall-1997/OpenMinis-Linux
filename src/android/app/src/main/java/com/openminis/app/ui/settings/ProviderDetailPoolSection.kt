package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisSmallButton
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.ui.theme.m3Icon
import com.openminis.app.ui.util.bringIntoViewOnFocus

private const val TAG = "ProviderDetailPool"

/**
 * [T-credential-pool-x] Multi-account credential pool editor. Slot 0 IS the
 * primary key (mirrored into the credential block above — see
 * [com.openminis.app.data.repository.ProviderConfigStore.saveApiKey]'s slot-0
 * sync), slots 1..n rotate round-robin with error cooldowns. Removing the
 * first slot promotes the survivor to primary; removing a backup drops it
 * permanently, hence the confirm dialog.
 *
 * Kept out of ProviderDetailScreen.kt on purpose: that file sits at its
 * architecture baseline ceiling (1199) and the ratchet never rises.
 */
@Composable
internal fun AccountPoolSection(
    instance: ProviderInstance,
    storedKey: String,
    providerRepository: ProviderRepository,
    onStoredKeyChanged: (String) -> Unit,
) {
    val configStore = providerRepository.configStore
    var poolKeys by remember(instance.id) { mutableStateOf(configStore.poolKeys(instance.id)) }
    var poolInput by remember { mutableStateOf("") }
    var poolError by remember { mutableStateOf(false) }
    var poolRemoveIndex by remember { mutableStateOf<Int?>(null) }

    fun refreshPool() {
        poolKeys = configStore.poolKeys(instance.id)
    }

    SettingsSection(
        header = stringResource(R.string.provider_detail_pool_header),
        footer = stringResource(R.string.provider_detail_pool_footer),
    ) {
        SettingsCardBlock {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTextField(
                    value = poolInput,
                    onValueChange = {
                        poolInput = it
                        poolError = false
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = stringResource(R.string.provider_detail_pool_add_hint),
                    isError = poolError,
                    fieldModifier = Modifier.bringIntoViewOnFocus(),
                )
                Spacer(modifier = Modifier.width(8.dp))
                MinisSmallButton(
                    onClick = {
                        val candidate = poolInput.trim()
                        when {
                            candidate == storedKey || candidate in poolKeys -> poolError = true
                            // First key: no primary exists yet, so this paste
                            // BECOMES the primary. The pool activates on the
                            // second key — store semantics reject pools of 1.
                            storedKey.isBlank() -> {
                                configStore.saveApiKey(instance.id, candidate)
                                onStoredKeyChanged(candidate)
                                poolInput = ""
                                AppLogger.info(TAG, "Pool add seeded primary for ${instance.id}")
                            }
                            else -> {
                                if (configStore.addPoolKey(instance.id, candidate)) {
                                    refreshPool()
                                    poolInput = ""
                                    poolError = false
                                    AppLogger.info(TAG, "Added pool key to ${instance.id} (now ${poolKeys.size})")
                                } else {
                                    poolError = true
                                }
                            }
                        }
                    },
                    enabled = poolInput.isNotBlank(),
                ) {
                    Text(stringResource(R.string.provider_detail_pool_add))
                }
            }
            if (poolError) {
                Text(
                    text = stringResource(R.string.provider_detail_pool_duplicate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (poolKeys.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                poolKeys.forEachIndexed { index, key ->
                    if (index > 0) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                    PoolKeyRow(
                        index = index,
                        key = key,
                        onRemove = { poolRemoveIndex = index },
                    )
                }
            }
        }
    }

    poolRemoveIndex?.let { index ->
        MinisAlertDialog(
            onDismissRequest = { poolRemoveIndex = null },
            title = stringResource(R.string.provider_detail_pool_remove),
            text = stringResource(R.string.provider_detail_pool_remove_confirm),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                configStore.removePoolKey(instance.id, index)
                refreshPool()
                // Slot-0 removal promotes the survivor into the primary slot —
                // mirror it back so the credential block above stays in sync.
                onStoredKeyChanged(configStore.loadApiKey(instance.id) ?: "")
                AppLogger.info(TAG, "Removed pool key $index from ${instance.id} (now ${poolKeys.size})")
                poolRemoveIndex = null
            },
        )
    }
}

@Composable
private fun PoolKeyRow(
    index: Int,
    key: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (index == 0) R.string.provider_detail_pool_slot_primary
                else R.string.provider_detail_pool_slot_extra,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (index == 0) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = maskPoolKey(key),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(
                m3Icon(Icons.Default.Delete),
                contentDescription = stringResource(R.string.provider_detail_pool_remove),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun maskPoolKey(key: String): String {
    if (key.length <= 10) return key.replace(Regex("."), "•")
    return key.take(6) + "..." + key.takeLast(4)
}
