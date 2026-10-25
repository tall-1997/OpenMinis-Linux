package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ModelSlotRef
import com.openminis.app.data.model.ProviderInstance

/**
 * Defaults-slot picker. Lists groups and individual enabled models.
 * A model is stored as `entry:<id>` so group lookups do not treat it as a group.
 * Search + LazyColumn avoids the freeze a 300+ item dropdown caused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SlotPickerField(
    label: String,
    groups: List<ModelGroup>,
    entries: List<ModelEntry>,
    instances: List<ProviderInstance>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val none = stringResource(R.string.model_groups_none)
    val selectedName = slotSelectionLabel(selectedId, groups, entries, instances) ?: none

    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { open = true },
        )
    }

    if (!open) return

    val q = query.trim()
    val shownGroups = groups.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
    val shownModels = entries.mapNotNull { entry ->
        if (entry.isHidden) return@mapNotNull null
        val inst = instances.find { it.id == entry.providerInstanceId } ?: return@mapNotNull null
        if (!inst.isEnabled) return@mapNotNull null
        val name = entry.model.displayName.ifBlank { entry.model.id }
        val provider = inst.label.ifBlank { entry.model.provider }
        val row = if (provider.isBlank()) name else "$name · $provider"
        if (q.isNotEmpty() && !row.contains(q, ignoreCase = true) && !entry.model.id.contains(q, ignoreCase = true)) {
            return@mapNotNull null
        }
        entry.id to row
    }

    Dialog(
        onDismissRequest = { open = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.add_models_to_group_search_models)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    item(key = "none") {
                        ListItem(
                            headlineContent = { Text(none) },
                            modifier = Modifier.clickable {
                                onSelect(null)
                                open = false
                            },
                        )
                    }
                    if (shownGroups.isNotEmpty()) {
                        item(key = "groups-header") {
                            Text(
                                stringResource(R.string.model_groups_slot_groups),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        items(shownGroups, key = { "g:${it.id}" }) { group ->
                            ListItem(
                                headlineContent = { Text(group.name) },
                                modifier = Modifier.clickable {
                                    onSelect(group.id)
                                    open = false
                                },
                            )
                        }
                    }
                    if (shownModels.isNotEmpty()) {
                        item(key = "models-header") {
                            Text(
                                stringResource(R.string.model_groups_slot_models),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        items(shownModels, key = { "e:${it.first}" }) { (id, row) ->
                            ListItem(
                                headlineContent = { Text(row) },
                                modifier = Modifier.clickable {
                                    onSelect(ModelSlotRef.entry(id))
                                    open = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun slotSelectionLabel(
    selectedId: String?,
    groups: List<ModelGroup>,
    entries: List<ModelEntry>,
    instances: List<ProviderInstance>,
): String? {
    if (selectedId.isNullOrBlank()) return null
    val entryId = ModelSlotRef.entryId(selectedId)
    if (entryId != null) {
        val entry = entries.find { it.id == entryId } ?: return null
        val inst = instances.find { it.id == entry.providerInstanceId }
        val name = entry.model.displayName.ifBlank { entry.model.id }
        val provider = inst?.label?.takeIf { it.isNotBlank() }
        return if (provider == null) name else "$name · $provider"
    }
    return groups.find { it.id == selectedId }?.name
}
