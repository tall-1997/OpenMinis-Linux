package com.openminis.app.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/**
 * [T-android-startup-splash-hang] Shown when `MinisApp.onCreate` could not
 * finish building its subsystems, so `MainActivity` has no repositories to
 * compose against.
 *
 * ## The dead end this replaces
 *
 * The previous behaviour on this path was: log the cause with `Log.e`, return
 * from `MainActivity.onCreate` **without ever calling `setContent`**, show a
 * crash-share dialog if a burst of crash files happened to exist — and when
 * there were none, `maybeShowOnActivity` invokes `onClosed` immediately, which
 * ran `finishAndRestartProcess()`: a 1.2-second Toast, `finish()`, then
 * `killProcess`.
 *
 * An init failure is caught and logged, not written as a crash file, so
 * `pendingShareFiles` was essentially always null here. The user therefore saw
 * the white system splash, then a content-less window go black, then the app
 * vanish — with no dialog, no reason, and no in-app way out. Because
 * `Application.onCreate` never re-runs for the life of a process and the
 * underlying cause is usually deterministic (an unreadable store, a missing
 * migration path, a corrupt row), every subsequent tap repeated it exactly.
 * That reads as "stuck on the splash, then a black screen, app won't open",
 * and the only escape was clear-data or reinstall from system settings — which
 * is precisely the audience this screen exists for.
 *
 * The author's stated intent for this path was that it "FAILS VISIBLY AND
 * RECOVERABLY instead of dying on the first Compose frame forever". This screen
 * is what makes that true: the Activity always has content, the cause is on
 * screen and copyable, and the destructive exit is available in-app.
 *
 * Unlike [NewerDatabaseGuidanceScreen], a clear-data action IS offered here.
 * That screen deliberately omits one because the database is intact and
 * reinstalling restores everything; here the app cannot start at all, so a
 * deterministic loop has no non-destructive exit. It is therefore last,
 * explicitly labelled as destroying local data, and behind a confirmation.
 */
@Composable
fun StartupFailureScreen(
    reason: String?,
    onRestart: () -> Unit,
) {
    val context = LocalContext.current
    var showClearConfirm by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    val details = remember(reason) {
        buildString {
            append("Minis Ultra startup failure\n")
            append("versionName=").append(com.openminis.app.BuildConfig.VERSION_NAME)
            append(" versionCode=").append(com.openminis.app.BuildConfig.VERSION_CODE)
            append('\n')
            append("cause=").append(reason ?: "unknown")
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.startup_failure_title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.startup_failure_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    // The concrete cause. This is the whole point of the screen:
                    // before, it existed only in logcat, which the affected user
                    // has no way to read.
                    Text(
                        text = reason ?: stringResource(R.string.startup_failure_reason_unknown),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.startup_failure_restart))
                    }
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("Minis startup failure", details))
                            copied = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (copied) R.string.startup_failure_copied
                                else R.string.startup_failure_copy,
                            ),
                        )
                    }
                    TextButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.startup_failure_clear_data))
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.startup_failure_clear_title)) },
            text = { Text(stringResource(R.string.startup_failure_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    // Kills the process itself; nothing after this runs.
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    am?.clearApplicationUserData()
                }) {
                    Text(stringResource(R.string.startup_failure_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
