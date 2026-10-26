package com.openminis.app.ui.scheduled

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledRun
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.util.IsoTime
import java.util.Calendar

/**
 * Support pieces for [ScheduledTaskEditScreen] — pickers, row widgets and the
 * task assembler. Extracted from the screen file so the editor stays under
 * its architecture-policy line ceiling; behaviour is unchanged, symbols are
 * `internal` to the module.
 */

/** Which chat the task acts on when it fires. Mirrors [ScheduledTargetMode]. */
internal enum class TargetKind { NEW, FOLLOW_UP, RERUN }

@Composable
internal fun SectionLabel(text: String) {
    Text(text, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
internal fun PickerRow(
    title: String,
    value: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text(title) },
        trailingIcon = if (onClear != null) {
            {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(0.dp))
                    Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionPickerDialog(
    vm: ScheduledTasksViewModel,
    onDismiss: () -> Unit,
    onPick: (id: String, title: String) -> Unit,
) {
    var sessions by remember { mutableStateOf<List<ScheduledTasksViewModel.SessionOption>?>(null) }
    LaunchedEffect(Unit) { sessions = vm.listSessions() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scheduled_task_pick_session)) },
        text = {
            val list = sessions
            if (list == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (list.isEmpty()) {
                Text(stringResource(R.string.scheduled_task_no_sessions))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.id }) { s ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(s.id, s.title) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessagePickerDialog(
    vm: ScheduledTasksViewModel,
    sessionId: String,
    onDismiss: () -> Unit,
    onPick: (id: String, preview: String) -> Unit,
) {
    var messages by remember { mutableStateOf<List<ScheduledTasksViewModel.MessageOption>?>(null) }
    LaunchedEffect(sessionId) { messages = vm.listUserMessages(sessionId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scheduled_task_pick_message)) },
        text = {
            val list = messages
            if (list == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (list.isEmpty()) {
                Text(stringResource(R.string.scheduled_task_no_messages_in_session))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.id }) { m ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(m.id, m.preview) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(m.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * [T-scheduled-new-session-folder] Workspace picker for a NewSession task.
 * "No Group" is always offered first — null files the created chat into the
 * main list, exactly what a FAB-started chat without a group does. When the
 * user has no groups at all the list degrades to the hint text but the
 * unfiled option stays selectable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspacePickerDialog(
    vm: ScheduledTasksViewModel,
    currentId: String?,
    onDismiss: () -> Unit,
    onPick: (id: String?, name: String?) -> Unit,
) {
    var folders by remember { mutableStateOf<List<ScheduledTasksViewModel.FolderOption>?>(null) }
    LaunchedEffect(Unit) { folders = vm.listFolders() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scheduled_task_field_workspace)) },
        text = {
            val list = folders
            Column {
                WorkspaceRow(
                    name = stringResource(R.string.group_none),
                    selected = currentId == null,
                    onClick = { onPick(null, null) },
                )
                when {
                    list == null -> Box(
                        Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    list.isEmpty() -> Text(
                        stringResource(R.string.scheduled_task_no_folders),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(list, key = { it.id }) { f ->
                            WorkspaceRow(
                                name = f.name,
                                selected = f.id == currentId,
                                onClick = { onPick(f.id, f.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun WorkspaceRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateDialog(initialMs: Long?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMs)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            MinisTextButton(onClick = {
                state.selectedDateMillis?.let { onPick(startOfLocalDay(it)) }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

/** DatePicker hands back a UTC-midnight ms; convert to local start-of-day. */
internal fun startOfLocalDay(utcMidnightMs: Long): Long {
    // The picker's value is UTC midnight of the picked calendar day. Re-anchor
    // to the local time zone's start-of-day so the active-window comparison in
    // ScheduledTask.nextTriggerMs (which works in local time) matches.
    val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = utcMidnightMs
    }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

internal fun dateLabel(ms: Long): String =
    IsoTime.formatPattern(ms, "MMM d")

@Suppress("LongParameterList")
internal fun buildTask(
    id: String?,
    label: String,
    prompt: String,
    hour: Int,
    minute: Int,
    repeatMode: ScheduledRepeatMode,
    customDays: Set<Int>,
    enabled: Boolean,
    createdAt: Long,
    lastFiredAt: Long?,
    lastResultPreview: String?,
    lastResultSessionId: String?,
    runHistory: List<ScheduledRun>,
    intervalMinutes: Long,
    fireAtMs: Long?,
    targetKind: TargetKind,
    targetSessionId: String?,
    targetMessageId: String?,
    modelBinding: String?,
    modelEntryIdLookup: (String) -> String?,
    startDateMs: Long?,
    endDateMs: Long?,
    newSessionPermissionMode: String?,
    newSessionFolderId: String?,
): ScheduledTask {
    val targetMode = when (targetKind) {
        TargetKind.NEW -> ScheduledTargetMode.NewSession
        TargetKind.FOLLOW_UP -> targetSessionId?.let { ScheduledTargetMode.AppendToSession(it) }
            ?: ScheduledTargetMode.NewSession
        TargetKind.RERUN ->
            if (targetSessionId != null && targetMessageId != null)
                ScheduledTargetMode.RerunMessage(targetSessionId, targetMessageId)
            else ScheduledTargetMode.NewSession
    }
    // For backcompat: if the binding pins to a specific entry, also fill in
    // legacy modelId so older code paths (and any external readers) still
    // resolve the same concrete model. Group bindings leave modelId null —
    // they resolve to a member entry at run time.
    val derivedModelId: String? = modelBinding?.let { json ->
        runCatching {
            val o = org.json.JSONObject(json)
            if (o.optString("type") == "entry") {
                o.optString("entryId").takeIf { it.isNotEmpty() }?.let(modelEntryIdLookup)
            } else null
        }.getOrNull()
    }
    return ScheduledTask(
        id = id ?: java.util.UUID.randomUUID().toString(),
        label = label.trim(),
        timeOfDayHour = hour,
        timeOfDayMinute = minute,
        repeatMode = repeatMode,
        customDays = if (repeatMode == ScheduledRepeatMode.CUSTOM) customDays else emptySet(),
        prompt = prompt.trim(),
        targetMode = targetMode,
        modelId = derivedModelId,
        modelBinding = modelBinding,
        enabled = enabled,
        createdAt = createdAt,
        startDateMs = startDateMs,
        endDateMs = endDateMs,
        lastFiredAt = lastFiredAt,
        lastResultPreview = lastResultPreview,
        lastResultSessionId = lastResultSessionId,
        // [T-scheduled-edit-echo] The editor used to drop these on save —
        // editing any task wiped its run history, and editing an
        // agent-created INTERVAL task zeroed its interval (nextTriggerMs
        // then returned null and the task never fired again). Both are now
        // echoed through editor state.
        runHistory = runHistory,
        intervalMinutes = intervalMinutes,
        fireAtMs = fireAtMs,
        newSessionPermissionMode = newSessionPermissionMode,
        newSessionFolderId = newSessionFolderId,
    )
}
