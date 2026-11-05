// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.openminis.app.R
import com.openminis.app.network.NetworkAlertCenter
import com.openminis.app.network.NetworkAlertEvent
import com.openminis.app.network.NetworkHub
import com.openminis.app.navigation.Routes
import com.openminis.app.ui.navigation.safeNavigate

/**
 * [FEAT-261021-05, iOS e720112cf] The floating banner at the top of the app
 * while a proxy or Tailscale keeps failing ([NetworkAlertCenter]). A tap
 * opens that setting - the proxy's editor, or the Tailscale page - where the
 * user decides whether to keep using it; the close button snoozes it for
 * this subject.
 */
@Composable
fun NetworkAlertBannerHost(navController: NavController) {
    val banner by NetworkAlertCenter.banner.collectAsStateWithLifecycle()
    // Keeps the last event while the exit animation runs.
    val shown = remember { mutableStateOf<NetworkAlertEvent?>(null) }
    banner?.let { shown.value = it }
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = banner != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            shown.value?.let { e ->
                NetworkAlertBanner(
                    event = e,
                    onOpen = {
                        val taken = NetworkAlertCenter.takeBanner() ?: return@NetworkAlertBanner
                        when (val s = taken.subject) {
                            is NetworkAlertEvent.Subject.Proxy -> {
                                NetworkHub.pendingProxyEditor.value = s.id
                                navController.safeNavigate(Routes.NETWORK_PROXIES)
                            }
                            NetworkAlertEvent.Subject.Tailscale -> navController.safeNavigate(Routes.NETWORK_TAILSCALE)
                        }
                    },
                    onDismiss = { NetworkAlertCenter.dismissBanner() },
                )
            }
        }
    }
}

@Composable
private fun NetworkAlertBanner(event: NetworkAlertEvent, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val settings by NetworkHub.settings.collectAsStateWithLifecycle()
    val title = when (val s = event.subject) {
        is NetworkAlertEvent.Subject.Proxy -> stringResource(
            R.string.network_alert_proxy_title,
            settings.proxies.firstOrNull { it.id == s.id }?.displayName ?: s.id,
        )
        NetworkAlertEvent.Subject.Tailscale -> stringResource(R.string.network_alert_tailscale_title)
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xF2FF9500),
        shadowElevation = 6.dp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 480.dp).fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.weight(1f).clickable(onClick = onOpen).padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(10.dp))
                Column {
                    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.network_alert_banner_body, event.failures, failureReason(event.failureClass)),
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.network_alert_dismiss),
                    tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Short, user-facing reason for a nethub failure class (iOS NetworkAlertCenter.reason). */
@Composable
internal fun failureReason(failureClass: String): String = stringResource(
    when (failureClass) {
        "timeout" -> R.string.network_reason_timeout
        "dns" -> R.string.network_reason_dns
        "refused" -> R.string.network_reason_refused
        "unreachable" -> R.string.network_reason_unreachable
        "proxy-auth" -> R.string.network_reason_proxy_auth
        "tls" -> R.string.network_reason_tls
        "closed" -> R.string.network_reason_closed
        else -> R.string.network_reason_other
    },
)

/** Shown on the Tailscale page and in a proxy's editor while it keeps failing. */
@Composable
internal fun NetworkFailureNote(event: NetworkAlertEvent, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFF9500), modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                stringResource(R.string.network_alert_note, event.failures, failureReason(event.failureClass)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        if (event.detail.isNotEmpty()) {
            Text(
                event.detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
