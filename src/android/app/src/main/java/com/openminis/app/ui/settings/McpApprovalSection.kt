package com.openminis.app.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.mcp.server.approval.McpApprovalCore
import com.openminis.app.mcp.server.approval.McpApprovalExecutor
import com.openminis.app.mcp.server.approval.McpApprovalManager
import com.openminis.app.mcp.server.approval.McpApprovalRpc
import com.openminis.app.ui.components.SettingsRowDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-mcp-approval-ui] The phone's side of the three-phase approval flow.
 *
 * A stdio or remote client announces a task, submits a run, then waits. The
 * user decides here — on the device the tools actually touch — and only an
 * operation that was approved *by content* may execute. The card is silent when
 * there is nothing to decide, so a quiet phone shows no chrome.
 *
 * Polling (1.5s) instead of a flow: the store writes from the MCP server
 * thread, and the section is only mounted while the screen is visible.
 */
@Composable
fun McpApprovalSection(manager: McpApprovalManager?, surfaceProvider: () -> McpApprovalRpc?) {
    if (manager == null) return
    val needsServer = stringResource(R.string.mcp_approval_needs_server)

    var snapshot by remember { mutableStateOf(manager.state()) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // The executor is bound to a live dispatcher, so the surface appears only
    // once the server has started — re-read it on every poll instead of
    // freezing whatever existed at first composition.
    var surface by remember { mutableStateOf(surfaceProvider()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(manager) {
        var tick = 0
        while (true) {
            // Sweep expired grants so a stale approval never sits on screen
            // looking actionable; the sweep is cheap and runs every ~6s.
            if (tick++ % 4 == 0) manager.pruneExpired()
            surface = surfaceProvider()
            snapshot = manager.state()
            delay(1_500)
        }
    }

    // A task is worth showing while it still has a live decision: pending
    // tasks, and any task with operations attached. A revoked task is gone by
    // definition — keeping it would park a dead card on screen forever.
    val tasks = snapshot.tasks.values
        .filter { task ->
            task.status != McpApprovalCore.TaskStatus.REVOKED &&
                (task.status == McpApprovalCore.TaskStatus.PENDING ||
                    snapshot.operations.values.any { it.taskId == task.taskId })
        }
        .sortedByDescending { it.createdAtMs }
    // Restart bookkeeping: grants the epoch bump killed are already unreachable,
    // so they are not cards — but silently vanishing decisions read as a bug.
    // One line explains it, and the sweep ages the rows out on its own.
    val revokedCount = snapshot.operations.values
        .count { it.status == McpApprovalCore.OperationStatus.REVOKED }
    if (tasks.isEmpty() && revokedCount == 0) return
    val revokedNotice = if (revokedCount > 0) {
        stringResource(R.string.mcp_approval_restart_notice, revokedCount)
    } else {
        null
    }

    fun act(block: () -> McpApprovalCore.Outcome) {
        if (busy) return
        busy = true
        lastError = when (val outcome = block()) {
            is McpApprovalCore.Outcome.Ok -> null
            is McpApprovalCore.Outcome.Err -> outcome.message
        }
        snapshot = manager.state()
        busy = false
    }

    SettingsSection(
        header = stringResource(R.string.mcp_approvals_section),
        footer = lastError ?: revokedNotice,
    ) {
        tasks.forEachIndexed { index, task ->
            if (index > 0) SettingsRowDivider()
            ApprovalTaskBlock(
                task = task,
                operations = snapshot.operations.values
                    .filter { it.taskId == task.taskId }
                    .sortedBy { it.createdAtMs },
                executedIds = snapshot.executedOperationIds,
                nowMs = System.currentTimeMillis(),
                busy = busy,
                onApproveTask = { act { manager.approveTask(task.taskId) } },
                onDenyTask = { act { manager.revokeTask(task.taskId) } },
                onApproveOp = { opId ->
                    act { manager.approveOperation(opId, sourceFileHash = null, operationFingerprint = null) }
                },
                onCancelOp = { opId -> act { manager.cancelOperation(opId) } },
                onRunOp = { opId ->
                    val rpc = surface
                    if (rpc == null) {
                        lastError = needsServer
                    } else if (!busy) {
                        busy = true
                        lastError = null
                        scope.launch {
                            // A shell run can take minutes: execute off the UI
                            // thread, then re-read the store once it lands.
                            val outcome = withContext(Dispatchers.IO) {
                                rpc.executeApproved(
                                    operationId = opId,
                                    fingerprint = null,
                                    sourceContext = McpApprovalExecutor.SourceContext(
                                        sourceFileHash = null,
                                        sourceFilePath = null,
                                    ),
                                )
                            }
                            lastError = outcome.error?.message
                            snapshot = manager.state()
                            busy = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ApprovalTaskBlock(
    task: McpApprovalCore.Task,
    operations: List<McpApprovalCore.Operation>,
    executedIds: Set<String>,
    nowMs: Long,
    busy: Boolean,
    onApproveTask: () -> Unit,
    onDenyTask: () -> Unit,
    onApproveOp: (String) -> Unit,
    onCancelOp: (String) -> Unit,
    onRunOp: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            ApprovalBadge(
                label = when (task.status) {
                    McpApprovalCore.TaskStatus.PENDING -> stringResource(R.string.mcp_approval_waiting)
                    McpApprovalCore.TaskStatus.ACTIVE -> stringResource(R.string.mcp_approval_task_active)
                    McpApprovalCore.TaskStatus.REVOKED -> stringResource(R.string.mcp_approval_revoked)
                },
                tone = when (task.status) {
                    McpApprovalCore.TaskStatus.ACTIVE -> ApprovalTone.Positive
                    McpApprovalCore.TaskStatus.PENDING -> ApprovalTone.Warn
                    McpApprovalCore.TaskStatus.REVOKED -> ApprovalTone.Muted
                },
            )
        }
        Text(
            text = "${task.taskId} · " + DateUtils.getRelativeTimeSpanString(
                task.createdAtMs, nowMs, DateUtils.MINUTE_IN_MILLIS,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (task.status == McpApprovalCore.TaskStatus.PENDING) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ApprovalPill(stringResource(R.string.mcp_approval_approve), true, busy, onApproveTask)
                ApprovalPill(stringResource(R.string.mcp_approval_deny), false, busy, onDenyTask)
            }
        }
        operations.forEach { op ->
            ApprovalOperationRow(
                op = op,
                executed = op.operationId in executedIds,
                busy = busy,
                onApprove = { onApproveOp(op.operationId) },
                onCancel = { onCancelOp(op.operationId) },
                onRun = { onRunOp(op.operationId) },
            )
        }
    }
}

@Composable
private fun ApprovalOperationRow(
    op: McpApprovalCore.Operation,
    executed: Boolean,
    busy: Boolean,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
    onRun: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = op.tool,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            ApprovalBadge(
                label = when {
                    executed -> stringResource(R.string.mcp_approval_executed)
                    op.status == McpApprovalCore.OperationStatus.WAITING -> stringResource(R.string.mcp_approval_waiting)
                    op.status == McpApprovalCore.OperationStatus.APPROVED -> stringResource(R.string.mcp_approval_approved)
                    else -> stringResource(R.string.mcp_approval_revoked)
                },
                tone = when {
                    executed -> ApprovalTone.Positive
                    op.status == McpApprovalCore.OperationStatus.APPROVED -> ApprovalTone.Positive
                    op.status == McpApprovalCore.OperationStatus.WAITING -> ApprovalTone.Warn
                    else -> ApprovalTone.Muted
                },
            )
        }
        Text(
            text = summarizeArguments(op.argumentsJson),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        op.operationFingerprint?.let { fingerprint ->
            Text(
                text = "#$fingerprint",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        if (!executed && op.status == McpApprovalCore.OperationStatus.APPROVED && op.sourceFileHash != null) {
            // A source-bound grant is replay-protected: only the client that
            // holds the file can satisfy the binding, so the phone shows why
            // there is no Run button instead of failing after the tap.
            Text(
                text = stringResource(R.string.mcp_approval_source_bound),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (!executed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (op.status) {
                    McpApprovalCore.OperationStatus.WAITING -> {
                        ApprovalPill(stringResource(R.string.mcp_approval_approve), true, busy, onApprove)
                        ApprovalPill(stringResource(R.string.mcp_approval_deny), false, busy, onCancel)
                    }
                    McpApprovalCore.OperationStatus.APPROVED -> {
                        if (op.sourceFileHash == null) {
                            ApprovalPill(stringResource(R.string.mcp_approval_run), true, busy, onRun)
                        }
                        ApprovalPill(stringResource(R.string.cancel), false, busy, onCancel)
                    }
                    McpApprovalCore.OperationStatus.REVOKED -> Unit
                }
            }
        }
    }
}

// ── atoms ────────────────────────────────────────────────────────────────

private enum class ApprovalTone { Positive, Warn, Muted }

@Composable
private fun ApprovalBadge(label: String, tone: ApprovalTone) {
    val color = when (tone) {
        ApprovalTone.Positive -> Color(0xFF1F8F4E)
        ApprovalTone.Warn -> Color(0xFFB26A00)
        ApprovalTone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ApprovalPill(
    label: String,
    primary: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = !busy,
        shape = RoundedCornerShape(50),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp,
            color = if (primary) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One-line digest of the payload: whitespace collapsed, capped so the card never grows. */
internal fun summarizeArguments(json: String, limit: Int = 120): String {
    val compact = json.replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= limit) compact else compact.take(limit - 1) + "…"
}
