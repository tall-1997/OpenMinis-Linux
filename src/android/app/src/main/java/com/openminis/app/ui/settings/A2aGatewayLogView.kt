package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.a2a.A2aGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [T-android-a2a-login-capture] Live gateway log, in-app.
 *
 * The engine writes every login state-machine step to stdout, redirected to
 * `data/gateway.log` — `[Login]` lines carry exactly why a provider login
 * did or did not complete (auth-URL fetch failures, token exchange errors,
 * state mismatches). Desktop shells surface this in their dev tooling; the
 * phone has no such console, which is why a failed capture used to be a
 * silent dead end. This viewer reads the same file from the :a2a process
 * (app-private storage, same uid) and refreshes while open.
 */

/** Last [maxLines] lines of a log file; tolerates a missing file. Pure-ish (file I/O). */
internal fun tailLogLines(file: File, maxLines: Int, maxBytes: Long = 256 * 1024): List<String> {
    if (!file.isFile) return emptyList()
    val length = file.length()
    val start = (length - maxBytes).coerceAtLeast(0)
    val text = runCatching {
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull() ?: return emptyList()
    val lines = text.lines().filter { it.isNotBlank() }
    // A tail window can start mid-line — drop the torn first line.
    val stable = if (start > 0 && lines.isNotEmpty()) lines.drop(1) else lines
    return stable.takeLast(maxLines.coerceAtLeast(1))
}

/** Lines worth showing when the "login/security only" filter is on. */
internal fun loginRelevantLine(line: String): Boolean =
    line.contains("[Login]") || line.contains("[Security]") || line.contains("[Session]")

@Composable
internal fun A2aGatewayLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var filterLogin by remember { mutableStateOf(true) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            lines = withContext(Dispatchers.IO) {
                tailLogLines(A2aGateway.gatewayLogFile(context), 300)
            }
            delay(2_000)
        }
    }
    val shown = remember(lines, filterLogin) {
        if (filterLogin) lines.filter(::loginRelevantLine).takeLast(120) else lines.takeLast(200)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.a2a_view_gateway_log)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = filterLogin, onCheckedChange = { filterLogin = it })
                    Text(
                        text = stringResource(R.string.a2a_log_filter_login),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (shown.isEmpty()) {
                    Text(
                        text = stringResource(R.string.a2a_log_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        shown.forEach { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.a2a_close))
            }
        },
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}
