package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.service.SubAgentActivityTracker

/**
 * [T-wave-e-stop-options] Two-tier stop for the composer's stop button.
 *
 * Tap keeps the historic single-run stop (cancel this chat's stream —
 * [ChatViewModel.cancelStream], which also stops the run's in-flight tool
 * commands via [com.openminis.app.sandbox.ExecutionCoordinator
 * .stopCurrentCommand]). When the chat has sub agents or a scheduled fire in
 * flight there is MORE than one thing to stop, so long-press opens a scope
 * menu:
 *  - "Stop current tool only" ends THIS chat's run (stream + its foreground
 *    tool commands) and nothing else — sub agents and scheduled tasks keep
 *    running. (Upstream 1.16 additionally lets the agent loop continue past
 *    the killed tool; minis' loop has no tool-interrupt seam yet, so the
 *    turn ends with the tool, like the plain stop. The SCOPE distinction —
 *    this chat vs everything — is what this menu adds.)
 *  - "Stop everything" additionally stops every sub agent working for this
 *    session ([SubAgentActivityTracker.stop]).
 * The same menu carries the "Confirm before stop" toggle (persisted in the
 * appearance prefs): when ON, a tap opens a confirm sheet instead of
 * stopping directly, guarding against mis-taps during long unattended runs.
 */
internal object StopOptionsPrefs {
    private const val PREFS = com.openminis.app.config.PREF_APPEARANCE
    private const val KEY = "chat.confirmBeforeStop"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, value).apply()
    }
}

/** True when anything beyond this chat's own stream is running for [sessionId]. */
internal fun hasExtraRunningWork(sessionId: String): Boolean =
    SubAgentActivityTracker.members.value.any { it.parentSessionId == sessionId }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StopButtonWithOptions(
    sessionId: String,
    onStopRun: () -> Unit,
    onStopAll: () -> Unit,
    onContinueAfterToolStop: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmOpen by remember { mutableStateOf(false) }
    var confirmEnabled by remember { mutableStateOf(StopOptionsPrefs.enabled(context)) }
    Box(modifier = modifier.size(46.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(46.dp),
            strokeWidth = 2.dp,
            color = ToolErrorColor,
        )
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(ToolErrorColor, CircleShape)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = {
                        if (confirmEnabled) confirmOpen = true else onStopRun()
                    },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Stop,
                contentDescription = stringResource(R.string.cd_stop),
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.stop_menu_current_tool)) },
                onClick = { menuOpen = false; onStopRun() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.stop_menu_stop_all)) },
                onClick = { menuOpen = false; onStopAll() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.stop_menu_confirm_toggle)) },
                trailingIcon = {
                    Switch(
                        checked = confirmEnabled,
                        onCheckedChange = {
                            confirmEnabled = it
                            StopOptionsPrefs.setEnabled(context, it)
                        },
                    )
                },
                onClick = {
                    val next = !confirmEnabled
                    confirmEnabled = next
                    StopOptionsPrefs.setEnabled(context, next)
                },
            )
        }
    }
    if (confirmOpen) {
        val extraWork = hasExtraRunningWork(sessionId)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text(stringResource(R.string.stop_confirm_title)) },
            confirmButton = {
                Text(
                    text = stringResource(R.string.stop_confirm_current),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { confirmOpen = false; onStopRun() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    if (extraWork) {
                        // Scope reminder: sub agents keep working for this
                        // chat until "Stop everything" is chosen.
                        androidx.compose.material3.TextButton(
                            onClick = { confirmOpen = false; onStopAll() },
                        ) {
                            Text(
                                stringResource(R.string.stop_confirm_all),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    androidx.compose.material3.TextButton(onClick = { confirmOpen = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            },
        )
    }
}
