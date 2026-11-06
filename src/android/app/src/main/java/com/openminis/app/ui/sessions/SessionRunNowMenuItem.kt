package com.openminis.app.ui.sessions

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledRunNow
import com.openminis.app.scheduled.ScheduledSessionHold
import com.openminis.app.scheduled.ScheduledTaskStore
import com.openminis.app.ui.theme.m3Icon

/**
 * [T-wave-e-run-now-menu] "Run now" in the session long-press menu (the chat's
 * countdown pill carries the same action): fire the task's PENDING next run
 * immediately, through the same path an alarm takes — [ScheduledRunNow.start]
 * (claim + [com.openminis.app.scheduled.ScheduledAgentRunner.run]), so the
 * alarm and the menu can never both run the run.
 *
 * Shown only when a live task targets THIS chat ([ScheduledSessionHold.isLive]
 * semantics: enabled, aimed at the session, with a next occurrence) — the item
 * drops out of the menu otherwise. The task store is read once per menu open;
 * the menu composes on demand, so idle home screens pay nothing.
 */
@Composable
internal fun RunNowMenuItem(sessionId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val task = remember(sessionId) {
        ScheduledTaskStore(context.applicationContext).all().firstOrNull { t ->
            t.enabled && t.targetMode.sessionIdOrNull == sessionId &&
                t.nextTriggerMs(System.currentTimeMillis()) != null
        }
    } ?: return
    DropdownMenuItem(
        text = { Text(stringResource(R.string.sched_pill_run_now)) },
        leadingIcon = {
            Icon(
                m3Icon(Icons.Filled.PlayArrow),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        onClick = {
            onDismiss()
            val started = ScheduledRunNow.start(context, task.id)
            com.openminis.app.logging.AppLogger.info(
                "SchedRunNowMenu",
                "run now session=${sessionId.take(8)} task=${task.id.take(8)} started=$started",
            )
        },
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
