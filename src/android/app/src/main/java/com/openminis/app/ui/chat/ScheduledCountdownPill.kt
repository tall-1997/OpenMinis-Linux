package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.ScheduledRunNow
import com.openminis.app.scheduled.ScheduledTaskStore
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay

/**
 * [T-wave-e-countdown-pill] Countdown capsule under the chat's top bar.
 *
 * While a scheduled task aimed at THIS chat has its next run within
 * [SHOW_WINDOW_MS] (60 s), a small `mm:ss` capsule sits under the session
 * title so the user can see the run is about to land (upstream 1.16 ships
 * the always-on ScheduledTaskPill; minis gates it to the final minute to
 * keep the top area quiet — the run's whole wait is visible on the home
 * list instead).
 *
 * - Data source: [ScheduledTaskStore.observe] (enabled tasks whose
 *   [com.openminis.app.scheduled.ScheduledTargetMode] targets this session),
 *   collected lifecycle-aware; only the SOONEST next trigger counts.
 * - Ticks once a second, only while shown and the screen is RESUMED —
 *   the same contract as the upstream pill's ticker.
 * - Long-press offers "Run now" ([ScheduledRunNow.start] — the same claim +
 *   [com.openminis.app.scheduled.ScheduledAgentRunner.run] path an alarm
 *   fire takes, so the alarm and the pill can never both run the run) and
 *   "Hide this countdown" (in-memory, per chat, like upstream).
 */
internal object ScheduledPillDismissals {
    /** Task ids hidden per session, keyed at hide time (in memory only, like upstream). */
    val hidden = mutableStateMapOf<String, Set<String>>()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ScheduledCountdownPill(currentSessionId: () -> String) {
    val context = LocalContext.current
    val store = remember { ScheduledTaskStore(context.applicationContext) }
    val tasks by remember { store.observe() }
        .collectAsStateWithLifecycle(initialValue = store.all())
    // Read on every composition: a draft chat gets its real id on the first
    // message, before any task can name it.
    val sessionId = currentSessionId()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var menuOpen by remember { mutableStateOf(false) }

    val soonest = remember(tasks, sessionId, now) {
        tasks.asSequence()
            .filter { it.enabled && it.targetMode.sessionIdOrNull == sessionId }
            .mapNotNull { it.nextTriggerMs(now) }
            .filter { it in (now + 1)..(now + SHOW_WINDOW_MS) }
            .minOrNull()
    }
    val soonestTaskId = remember(tasks, sessionId, soonest) {
        tasks.asSequence()
            .filter { it.enabled && it.targetMode.sessionIdOrNull == sessionId }
            .filter { soonest != null && it.nextTriggerMs(now) == soonest }
            .minByOrNull { it.id }?.id
    }
    val hidden = ScheduledPillDismissals.hidden[sessionId].orEmpty()
    val shown = soonest != null && (soonestTaskId == null || soonestTaskId !in hidden)

    // 1 Hz tick only while shown and RESUMED (upstream pill contract).
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(shown, lifecycleOwner) {
        if (!shown) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000L - now % 1_000L)
            }
        }
    }

    if (!shown) return
    val remainingMs = (soonest - now).coerceAtLeast(0L)
    val haptics = LocalHapticFeedback.current
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 4.dp)
                .shadow(4.dp, CircleShape, clip = false)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .combinedClickable(
                    onClick = { menuOpen = true },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = stringResource(R.string.sched_pill_task_label),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
            // Tabular digits keep the capsule from twitching while ticking.
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(start = 6.dp)) {
                Text(
                    text = "00:00",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0f),
                    maxLines = 1,
                )
                Text(
                    text = formatCountdown(remainingMs),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    color = ChatColors.sendButton,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val runNowLabel = stringResource(R.string.sched_pill_run_now)
            val hideLabel = stringResource(R.string.sched_pill_hide)
            DropdownMenuItem(
                text = { Text(runNowLabel) },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    val id = soonestTaskId
                    if (id != null) {
                        val started = ScheduledRunNow.start(context, id)
                        AppLogger.info(
                            "SchedPill",
                            "run now session=${sessionId.take(8)} task=${id.take(8)} started=$started",
                        )
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(hideLabel) },
                leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    soonestTaskId?.let { id ->
                        ScheduledPillDismissals.hidden[sessionId] = hidden + id
                    }
                },
            )
        }
    }
}

/** "mm:ss" for the final-minute window (max 60 → never needs hours). */
internal fun formatCountdown(remainingMs: Long): String {
    val totalSeconds = (remainingMs + 999) / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

/** Show the capsule only for the final minute before a run (Wave E scope). */
internal const val SHOW_WINDOW_MS = 60_000L
