package com.openminis.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.ui.theme.m3Icon

/**
 * [T-startup-degraded-extended] Floating banner while the app runs with a
 * degraded startup behind it: the CORE subsystem block failed is a different,
 * full-screen path (MainActivity's [com.openminis.app.ui.StartupFailureScreen],
 * reached only while `subsystemsInitialized` is false) — this host covers the
 * EXTENDED block, which fails AFTER the flag already went true. That failure
 * used to be recorded into `subsystemInitFailure` and then read by nothing:
 * the only consumer sat inside the not-initialized branch, so the degraded
 * state was invisible and the log line was the sole trace.
 *
 * The banner mirrors [NetworkAlertBannerHost]'s shape — an overlay on top of
 * every screen, dismissible for the rest of the process. A restart re-runs
 * startup and re-evaluates, so no persistence is needed.
 */
@Composable
fun DegradedStartupBannerHost() {
    val app = LocalContext.current.applicationContext as? MinisApp ?: return
    val failure by app.subsystemInitFailureFlow.collectAsStateWithLifecycle()
    // Dismissal is process-scoped: the cause is fixed for this boot, so
    // hiding it for the session is enough (a restart re-evaluates anyway).
    var dismissed by remember { mutableStateOf(false) }
    // Keeps the last cause while the exit animation runs.
    val shown = remember { mutableStateOf<String?>(null) }
    failure?.let { shown.value = it }
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = failure != null && !dismissed,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            shown.value?.let { cause ->
                DegradedStartupBanner(cause, onDismiss = { dismissed = true })
            }
        }
    }
}

@Composable
private fun DegradedStartupBanner(cause: String, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xF2FF9500),
        shadowElevation = 6.dp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = 480.dp).fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.weight(1f).padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Icon(
                    m3Icon(Icons.Filled.Warning),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column {
                    Text(
                        stringResource(R.string.degraded_startup_title),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.degraded_startup_body),
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                    Text(
                        stringResource(R.string.degraded_startup_cause, cause),
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    m3Icon(Icons.Filled.Close),
                    contentDescription = stringResource(R.string.degraded_startup_dismiss),
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
