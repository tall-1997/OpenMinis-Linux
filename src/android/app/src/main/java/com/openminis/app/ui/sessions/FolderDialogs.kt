package com.openminis.app.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton

@Composable
internal fun NewFolderDialog(
    isDark: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val canCreate = trimmed.isNotEmpty() &&
        trimmed.length <= 400 // FolderEntity.DESC_MAX_CHARS (100) x 4 UTF-16 headroom

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessionlist_new_folder_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.sessionlist_new_folder_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.sessionlist_new_folder_desc_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            MinisButton(
                onClick = { onCreate(trimmed, description.trim().ifEmpty { null }) },
                enabled = canCreate,
            ) {
                Text(stringResource(R.string.sessionlist_new_folder_create))
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

