package com.openminis.app.ui.scheduled

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.openminis.app.ui.components.EmptyStateHint
import com.openminis.app.ui.components.MinisCenterTopBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.viewState
import com.openminis.app.util.IsoTime

/**
 * [T-android-scheduled-tasks-design / T-android-scheduled-tasks-run-records]
 * List + manage scheduled tasks. Entry point from SessionListScreen's
 * TopAppBar; tap a row to edit, FAB to create, switch to enable/disable.
 * Long-press opens a menu: Edit / Run records / Delete (with confirm).
 * The row no longer shows a result preview — execution history lives in
 * the Run records screen.
 *
 * [T-android-automation-rules] The page is now the "Automation" hub with two
 * tabs: Timers (the original cron list + editor flow, unchanged) and Rules
 * (conditional-trigger automation rules from the automation package).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(
    onBack: () -> Unit,
    onEditTask: (taskId: String?) -> Unit,
    onViewRuns: (taskId: String) -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    onEditRule: (ruleId: String?) -> Unit = {},
    onViewRuleRuns: (ruleId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val vm: ScheduledTasksViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ScheduledTasksViewModel.factory(context),
    )
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ScheduledTask?>(null) }
    // [T-android-automation-rules] 0 = Timers (legacy list), 1 = Rules.
    var tab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = {
                    Text(
                        stringResource(R.string.automation_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = { onEditTask(null) }, shape = CircleShape) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.scheduled_task_new))
                }
            } else {
                // [T-android-automation-rules] Create a conditional rule.
                FloatingActionButton(onClick = { onEditRule(null) }, shape = CircleShape) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.automation_rule_new))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.material3.TabRow(selectedTabIndex = tab) {
                androidx.compose.material3.Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.automation_tab_timers)) },
                )
                androidx.compose.material3.Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.automation_tab_rules)) },
                )
            }
            if (tab == 1) {
                com.openminis.app.automation.ui.AutomationRulesTab(
                    onEditRule = onEditRule,
                    onViewRuns = onViewRuleRuns,
                )
                return@Column
            }
            if (tasks.isEmpty()) {
                EmptyStateHint(
                    icon = Icons.Outlined.Schedule,
                    text = stringResource(R.string.scheduled_tasks_empty),
                )
                return@Column
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    ScheduledTaskRow(
                        task = task,
                        onClick = { onEditTask(task.id) },
                        onToggle = { vm.setEnabled(task.id, it) },
                        onEdit = { onEditTask(task.id) },
                        onViewRuns = { onViewRuns(task.id) },
                        onDelete = { pendingDelete = task },
                    )
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.scheduled_task_delete_title)) },
            text = { Text(stringResource(R.string.scheduled_task_delete_body, toDelete.label)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.delete(toDelete.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.scheduled_task_delete_confirm))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScheduledTaskRow(
    task: ScheduledTask,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onViewRuns: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(task.id) { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (task.enabled)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = if (task.enabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.label.ifBlank { task.prompt.take(40) },
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatScheduleSummary(task),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // [T-scheduled-card-status-line] Run count + next fire, and a
                // terminal badge (finished / paused) instead of the old empty
                // next segment once a task stops having an occurrence.
                statusLine(task)?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            SettingsSwitch(checked = task.enabled, onCheckedChange = onToggle)
        }

        // [T-android-scheduled-tasks-run-records] Long-press menu: Edit / Run
        // records / Delete (delete is confirmed by the caller's dialog).
        com.openminis.app.ui.components.MinisMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.scheduled_task_menu_edit)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { menuExpanded = false; onEdit() },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.scheduled_task_menu_runs)) },
                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                onClick = { menuExpanded = false; onViewRuns() },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.scheduled_task_menu_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { menuExpanded = false; onDelete() },
            )
        }
    }
}

/**
 * First card line: repeat pattern + time. The repeat words stay hardcoded
 * (pre-existing, mirrored by the editor's own labels) — the status line below
 * carries the localized run-count / next-fire / terminal state.
 */
internal fun formatScheduleSummary(task: ScheduledTask): String {
    val time = "%02d:%02d".format(task.timeOfDayHour, task.timeOfDayMinute)
    val repeat = when (task.repeatMode) {
        ScheduledRepeatMode.ONCE -> "Once"
        ScheduledRepeatMode.DAILY -> "Daily"
        ScheduledRepeatMode.WEEKDAYS -> "Weekdays"
        ScheduledRepeatMode.CUSTOM -> {
            val days = task.customDays.sorted().joinToString(",") { dowShort(it) }
            if (days.isBlank()) "Custom" else days
        }
        ScheduledRepeatMode.INTERVAL -> {
            val m = task.intervalMinutes
            when {
                m > 0 && m % 1440L == 0L -> "Every ${m / 1440L}d"
                m > 0 && m % 60L == 0L -> "Every ${m / 60L}h"
                m > 0 -> "Every ${m}m"
                else -> "Interval"
            }
        }
    }
    return "$repeat $time"
}

/**
 * [T-scheduled-card-status-line] Second card line: how many times the task
 * has run, when it fires next — or a terminal badge when it won't again
 * (ONCE fired out / end date passed) or the user paused it. Never empty,
 * so a finished task no longer renders a bare schedule with a missing tail.
 */
@Composable
internal fun statusLine(task: ScheduledTask): String {
    val s = task.viewState()
    val parts = buildList {
        if (s.runCount > 0) {
            add(stringResource(R.string.scheduled_task_runs_count, s.runCount))
        }
        when {
            s.nextFireMs != null -> add(
                stringResource(
                    R.string.scheduled_task_next_fire,
                    IsoTime.formatPattern(s.nextFireMs, "MMM d HH:mm"),
                ),
            )
            s.ended -> add(stringResource(R.string.scheduled_task_state_ended))
            s.paused -> add(stringResource(R.string.scheduled_task_state_paused))
        }
    }
    return parts.joinToString(" · ")
}

private fun dowShort(dow: Int): String = when (dow) {
    java.util.Calendar.SUNDAY -> "Sun"
    java.util.Calendar.MONDAY -> "Mon"
    java.util.Calendar.TUESDAY -> "Tue"
    java.util.Calendar.WEDNESDAY -> "Wed"
    java.util.Calendar.THURSDAY -> "Thu"
    java.util.Calendar.FRIDAY -> "Fri"
    java.util.Calendar.SATURDAY -> "Sat"
    else -> ""
}
