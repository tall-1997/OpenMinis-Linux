package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import com.openminis.app.goal.GoalManager
import com.openminis.app.goal.GoalSheetSignal
import com.openminis.app.ui.theme.m3Icon
import kotlinx.coroutines.launch

/**
 * Host for the Goal bottom sheet. Anything can request it through
 * [GoalSheetSignal] — the [PlanGoalCard] header tap, the /goal slash entry —
 * without holding UI references. The sheet reads the goal fresh from the DB
 * on open and on every [GoalManager.version] bump, so it never shows stale
 * state after a model-side update.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalSheet(viewModel: ChatViewModel) {
    var open by remember { mutableStateOf(false) }
    val requests by GoalSheetSignal.requests.collectAsState()
    LaunchedEffect(requests) { if (requests > 0) open = true }
    if (!open) return
    ModalBottomSheet(
        onDismissRequest = { open = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { SheetDragHandle() },
    ) {
        GoalSheetContent(
            viewModel = viewModel,
            onClose = { open = false },
        )
    }
}

/** Slim 32×4dp drag handle matching the app's other sheets. */
@Composable
private fun SheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(2.dp),
                ),
        )
    }
}

@Composable
private fun GoalSheetContent(viewModel: ChatViewModel, onClose: () -> Unit) {
    val goalVersion by GoalManager.version.collectAsState()
    var goal by remember { mutableStateOf<SessionGoalEntity?>(null) }
    LaunchedEffect(goalVersion) { goal = viewModel.currentGoal() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        Text(
            text = stringResource(
                if (goal == null) R.string.goal_sheet_title_new else R.string.goal_sheet_title,
            ),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))
        if (goal == null) {
            GoalCreateForm(
                busy = busy,
                error = formError,
                onError = { formError = it },
                onStart = { objective, budget ->
                    scope.launch {
                        busy = true
                        val outcome = viewModel.createGoalFromSheet(objective, budget)
                        busy = false
                        formError = outcome.exceptionOrNull()?.message
                        if (outcome.isSuccess) onClose()
                    }
                },
            )
        } else {
            GoalManagePanel(
                goal = goal,
                busy = busy,
                error = formError,
                onError = { formError = it },
                onAction = { action ->
                    scope.launch {
                        busy = true
                        val outcome = when (action) {
                            GoalSheetAction.Pause -> viewModel.setGoalPausedFromSheet(true)
                            GoalSheetAction.Resume -> viewModel.setGoalPausedFromSheet(false)
                            GoalSheetAction.Clear -> viewModel.clearGoalFromSheet()
                        }
                        busy = false
                        formError = outcome?.exceptionOrNull()?.message
                    }
                },
                onSaveBudget = { budget ->
                    scope.launch {
                        busy = true
                        val outcome = viewModel.updateGoalBudgetFromSheet(budget)
                        busy = false
                        formError = outcome.exceptionOrNull()?.message
                    }
                },
            )
        }
    }
}

/** User intents the manage panel dispatches through [onAction]. */
private enum class GoalSheetAction { Pause, Resume, Clear }

@Composable
private fun GoalCreateForm(
    busy: Boolean,
    error: String?,
    onError: (String?) -> Unit,
    onStart: (objective: String, budget: Long?) -> Unit,
) {
    var objective by remember { mutableStateOf("") }
    var budget by remember { mutableStateOf("") }
    val objectiveEmptyError = stringResource(R.string.goal_error_objective_empty)
    val budgetInvalidError = stringResource(R.string.goal_error_budget_invalid)
    OutlinedTextField(
        value = objective,
        onValueChange = {
            objective = it
            onError(null)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.goal_field_objective)) },
        placeholder = { Text(stringResource(R.string.goal_field_objective_hint)) },
        isError = error != null,
        minLines = 2,
        enabled = !busy,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = budget,
        onValueChange = {
            budget = it.filter(Char::isDigit).take(18)
            onError(null)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.goal_field_budget)) },
        placeholder = { Text(stringResource(R.string.goal_field_budget_hint)) },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        enabled = !busy,
    )
    if (error != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = {
            val trimmed = objective.trim()
            val budgetValue = budget.trim().toLongOrNull()
            when {
                trimmed.isEmpty() -> onError(objectiveEmptyError)
                budget.isNotEmpty() && (budgetValue == null || budgetValue <= 0) ->
                    onError(budgetInvalidError)
                else -> onStart(trimmed, budgetValue)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
    ) {
        Text(stringResource(R.string.goal_action_start))
    }
}

@Composable
private fun GoalManagePanel(
    goal: SessionGoalEntity?,
    busy: Boolean,
    error: String?,
    onError: (String?) -> Unit,
    onAction: (GoalSheetAction) -> Unit,
    onSaveBudget: (Long?) -> Unit,
) {
    if (goal == null) return
    var showClearConfirm by remember { mutableStateOf(false) }
    var editingBudget by remember(goal.sessionId, goal.updatedAt) {
        mutableStateOf(goal.status == GoalStatus.BUDGET_LIMITED)
    }
    var budgetField by remember(goal.sessionId, goal.updatedAt) {
        mutableStateOf(goal.tokenBudget?.toString().orEmpty())
    }
    val budgetInvalidError = stringResource(R.string.goal_error_budget_invalid)
    Text(
        text = goal.objective,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(12.dp))
    GoalStatusRow(goal.status)
    Spacer(Modifier.height(4.dp))
    GoalUsageRow(goal)
    if (goal.status == GoalStatus.BLOCKED && goal.blockedCondition != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = goal.blockedCondition,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (error != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (goal.status) {
            GoalStatus.ACTIVE -> FilledTonalButton(
                onClick = { onAction(GoalSheetAction.Pause) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Icon(m3Icon(Icons.Default.Pause), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.goal_action_pause))
            }
            GoalStatus.PAUSED, GoalStatus.BLOCKED -> Button(
                onClick = { onAction(GoalSheetAction.Resume) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Icon(m3Icon(Icons.Default.PlayArrow), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.goal_action_resume))
            }
            GoalStatus.COMPLETE -> Button(
                onClick = { onAction(GoalSheetAction.Clear) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(stringResource(R.string.goal_action_new))
            }
            GoalStatus.BUDGET_LIMITED -> Unit
        }
        TextButton(
            onClick = {
                editingBudget = !editingBudget
                budgetField = goal.tokenBudget?.toString().orEmpty()
                onError(null)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        ) {
            Text(stringResource(R.string.goal_action_edit_budget))
        }
        if (editingBudget) {
            OutlinedTextField(
                value = budgetField,
                onValueChange = {
                    budgetField = it.filter(Char::isDigit).take(18)
                    onError(null)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.goal_field_budget)) },
                placeholder = { Text(stringResource(R.string.goal_field_budget_hint)) },
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !busy,
            )
            Button(
                onClick = {
                    val value = budgetField.trim().toLongOrNull()
                    if (budgetField.isNotEmpty() && (value == null || value <= 0)) {
                        onError(budgetInvalidError)
                    } else {
                        onSaveBudget(value)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(stringResource(R.string.goal_action_save))
            }
        }
        if (goal.status != GoalStatus.COMPLETE) {
            TextButton(
                onClick = { showClearConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(
                    text = stringResource(R.string.goal_action_clear),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.goal_confirm_clear_title)) },
            text = { Text(stringResource(R.string.goal_confirm_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    onAction(GoalSheetAction.Clear)
                }) { Text(stringResource(R.string.goal_confirm_clear_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun GoalStatusRow(status: String) {
    val description = stringResource(goalStatusContentDescription(status))
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            GoalStatus.ACTIVE -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            GoalStatus.COMPLETE -> Icon(
                imageVector = m3Icon(Icons.Default.CheckCircle),
                contentDescription = description,
                tint = ToolCheckColor,
                modifier = Modifier.size(18.dp),
            )
            GoalStatus.PAUSED -> Icon(
                imageVector = m3Icon(Icons.Default.Pause),
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            GoalStatus.BLOCKED -> Icon(
                imageVector = m3Icon(Icons.Default.Error),
                contentDescription = description,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            GoalStatus.BUDGET_LIMITED -> Icon(
                imageVector = m3Icon(Icons.Default.Warning),
                contentDescription = description,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(goalStatusLabel(status)),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = goalStatusColor(status),
        )
    }
}

private fun goalStatusContentDescription(status: String): Int = when (status) {
    GoalStatus.ACTIVE -> R.string.cd_goal_status_active
    GoalStatus.PAUSED -> R.string.cd_goal_status_paused
    GoalStatus.COMPLETE -> R.string.cd_goal_status_complete
    GoalStatus.BLOCKED -> R.string.cd_goal_status_blocked
    GoalStatus.BUDGET_LIMITED -> R.string.cd_goal_status_budget
    else -> R.string.cd_goal_status_active
}

private fun goalStatusLabel(status: String): Int = when (status) {
    GoalStatus.ACTIVE -> R.string.goal_status_active
    GoalStatus.PAUSED -> R.string.goal_status_paused
    GoalStatus.COMPLETE -> R.string.goal_status_complete
    GoalStatus.BLOCKED -> R.string.goal_status_blocked
    GoalStatus.BUDGET_LIMITED -> R.string.goal_status_budget
    else -> R.string.goal_status_active
}

@Composable
private fun goalStatusColor(status: String) = when (status) {
    GoalStatus.COMPLETE -> ToolCheckColor
    GoalStatus.BLOCKED, GoalStatus.BUDGET_LIMITED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun GoalUsageRow(goal: SessionGoalEntity) {
    val tokens = goal.tokenBudget?.let { "${formatGoalTokens(goal.tokensUsed)} / ${formatGoalTokens(it)}" }
        ?: formatGoalTokens(goal.tokensUsed)
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.goal_label_usage),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$tokens · ${formatDuration(goal.elapsedMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 252_000ms → "4m 12s"; 3_780_000ms → "1h 3m"; under a minute → "42s". */
private fun formatDuration(ms: Long): String {
    val seconds = ms / 1_000
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m ${seconds % 60}s"
    return "${minutes / 60}h ${minutes % 60}m"
}
