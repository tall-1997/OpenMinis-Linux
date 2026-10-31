package com.openminis.app.ui.scheduled

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledRun
import com.openminis.app.util.IsoTime

/**
 * [T-android-scheduled-tasks-run-records] Execution log for one scheduled
 * task. Lists each recorded run newest-first; a run that produced a chat
 * (non-null sessionId) is tappable → opens that chat. Backed by the live
 * tasks flow so new runs appear without re-entering the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTaskRunsScreen(
    taskId: String,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: ScheduledTasksViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ScheduledTasksViewModel.factory(context),
    )
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val task = tasks.firstOrNull { it.id == taskId }
    val runs = task?.runHistory ?: emptyList()

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.scheduled_task_runs_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        if (task != null && task.label.isNotBlank()) {
                            Text(
                                task.label,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
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
    ) { padding ->
        if (runs.isEmpty()) {
            EmptyStateHint(
                icon = Icons.Outlined.History,
                text = stringResource(R.string.scheduled_task_runs_empty),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // [v3 P0-3] Keyed by run identity, not raw firedAt: two runs can
            // share a millisecond timestamp (markFired uses
            // System.currentTimeMillis), and a duplicate key crashes the
            // LazyColumn. ScheduledRun carries no persisted run id (its schema
            // lives in ScheduledTask.kt and is intentionally untouched here),
            // so the list position disambiguates equal firedAt values.
            itemsIndexed(runs, key = { index, run -> "${run.firedAt}-$index" }) { _, run ->
                RunRow(run = run, onOpenSession = onOpenSession)
            }
        }
    }
}

@Composable
private fun RunRow(run: ScheduledRun, onOpenSession: (String) -> Unit) {
    val tappable = run.sessionId != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tappable) Modifier.clickable { onOpenSession(run.sessionId!!) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (run.ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (run.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = formatRunTime(run.firedAt),
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            val preview = run.preview
            if (!preview.isNullOrBlank()) {
                Text(
                    text = preview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (tappable) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.scheduled_task_runs_open_session),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun formatRunTime(ms: Long): String =
    IsoTime.formatPattern(ms, "MMM d, HH:mm:ss")
