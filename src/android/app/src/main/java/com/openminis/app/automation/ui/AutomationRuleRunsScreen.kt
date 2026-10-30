package com.openminis.app.automation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.openminis.app.automation.AutomationRunEntity
import com.openminis.app.ui.components.MinisCenterTopBar
import com.openminis.app.util.IsoTime

/**
 * [T-android-automation-rules] Per-rule run records, newest first — same
 * shape as the scheduled-task run records screen. A run with a non-null
 * session id is tappable → opens that chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationRuleRunsScreen(
    ruleId: String,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: AutomationRulesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AutomationRulesViewModel.factory(context),
    )
    var runs by remember { mutableStateOf<List<AutomationRunEntity>>(emptyList()) }
    LaunchedEffect(ruleId) {
        runs = vm.runs(ruleId)
    }

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = {
                    Text(
                        stringResource(R.string.automation_rule_runs_title),
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
    ) { padding ->
        if (runs.isEmpty()) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.automation_rule_runs_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(runs, key = { it.runId }) { run ->
                RunRow(run = run, onOpenSession = onOpenSession)
            }
        }
    }
}

@Composable
private fun RunRow(run: AutomationRunEntity, onOpenSession: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = IsoTime.formatPattern(run.firedAt, "MMM d HH:mm:ss"),
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = run.outcome,
                color = when {
                    run.skipped -> MaterialTheme.colorScheme.tertiary
                    run.outcome.startsWith("error") -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        run.preview?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        run.sessionId?.let {
            TextButton(onClick = { onOpenSession(it) }) {
                Text(stringResource(R.string.automation_rule_runs_open_session))
            }
        }
    }
}
