package com.openminis.app.ui.chat

import com.openminis.app.ui.chat.SupervisorUiHost.Phase
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import kotlinx.coroutines.delay

/**
 * Live status bar for a supervisor-worker session (spec §3, PR#3).
 *
 * Renders [SupervisorUiHost.uiState] but ONLY when the state belongs to the
 * foregrounded [sessionId] — the host is a process-wide singleton and a
 * backgrounded session's runner keeps writing to it, so render-side gating
 * (not just [SupervisorUiHost.reset]) is what stops a live bar from
 * following the user into another conversation. Switching back re-shows it
 * via [SupervisorUiHost.republish] from `loadSession`.
 *
 * Terminal phases linger 4s (same contract as the plan-goal card) before the
 * bar clears itself, so a finished run reads as "done" instead of vanishing.
 * INTERRUPTED is the exception: it is not a completion, it is an offer — the
 * run record on disk says a dead process left work mid-flight, so the bar
 * holds Resume/Discard until the user decides (spec §3.8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SupervisorStatusBar(
    sessionId: String,
    modifier: Modifier = Modifier,
    onHalt: () -> Unit = {},
    onResume: () -> Unit = {},
    onDiscard: () -> Unit = {},
) {
    val state by SupervisorUiHost.uiState.collectAsState()
    val mine = state?.takeIf { it.sessionId == sessionId } ?: return
    var showSheet by remember { mutableStateOf(false) }

    // Terminal phases self-clear after a short linger so the bar does not
    // squat above the composer forever. Re-keying on the phase means a new
    // live phase arriving mid-linger restarts the effect harmlessly.
    // INTERRUPTED never self-clears: the decision (resume or discard) is
    // the user's, and the run record on disk outlives any linger window.
    LaunchedEffect(mine.phase, mine.sessionId) {
        if (!mine.isLive && mine.phase != Phase.INTERRUPTED) {
            delay(TERMINAL_LINGER_MS)
            SupervisorUiHost.reset(mine.sessionId)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .clickable { showSheet = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = phaseIcon(mine.phase),
                contentDescription = null,
                tint = phaseColor(mine.phase),
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = phaseLabel(mine.phase),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = phaseColor(mine.phase),
                    )
                    if (mine.workerTurns > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "· ${mine.workerTurns}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = mine.objective,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (mine.isLive) {
                IconButton(onClick = onHalt, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else if (mine.phase == Phase.INTERRUPTED) {
                TextButton(
                    onClick = onResume,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(
                        text = stringResource(R.string.supervisor_recovery_resume),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(
                    onClick = onDiscard,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(
                        text = stringResource(R.string.supervisor_recovery_discard),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = phaseLabel(mine.phase),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = phaseColor(mine.phase),
                )
                Text(
                    text = mine.objective,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.supervisor_sheet_worker_turns, mine.workerTurns),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val TERMINAL_LINGER_MS = 4_000L

private fun phaseIcon(phase: Phase): ImageVector = when (phase) {
    Phase.INSTRUCTING -> Icons.Filled.Psychology
    Phase.EXECUTING -> Icons.Filled.Build
    Phase.REVIEWING -> Icons.Filled.Visibility
    Phase.DONE -> Icons.Filled.CheckCircle
    Phase.HALTED, Phase.INTERRUPTED -> Icons.Filled.Stop
    Phase.FAILED -> Icons.Filled.ErrorOutline
}

@Composable
private fun phaseLabel(phase: Phase): String = when (phase) {
    Phase.INSTRUCTING -> stringResource(R.string.supervisor_phase_instructing)
    Phase.EXECUTING -> stringResource(R.string.supervisor_phase_executing)
    Phase.REVIEWING -> stringResource(R.string.supervisor_phase_reviewing)
    Phase.DONE -> stringResource(R.string.supervisor_phase_done)
    Phase.HALTED -> stringResource(R.string.supervisor_phase_halted)
    Phase.INTERRUPTED -> stringResource(R.string.supervisor_phase_interrupted)
    Phase.FAILED -> stringResource(R.string.supervisor_phase_failed)
}

@Composable
private fun phaseColor(phase: Phase): Color = when (phase) {
    Phase.DONE -> MaterialTheme.colorScheme.primary
    Phase.FAILED -> MaterialTheme.colorScheme.error
    Phase.HALTED, Phase.INTERRUPTED -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.primary
}
