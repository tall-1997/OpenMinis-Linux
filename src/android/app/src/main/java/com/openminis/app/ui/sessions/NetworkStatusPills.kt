// [port-upstream-1.16][FEAT-261021-07, iOS a6e6c10d1] The small status pills
// under the home screen title: whether Tailscale is connected and whether each
// proxy in use answers. All state derivation lives in
// com.openminis.app.network.NetworkStatusMonitor (started by NetworkHub.init);
// this file only renders it, so it stays out of the session list's hot paths.
package com.openminis.app.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.network.NetworkAlertCenter
import com.openminis.app.network.NetworkAlertEvent
import com.openminis.app.network.NetworkHub
import com.openminis.app.network.NetworkStatusMonitor

private val PillGreen = Color(0xFF34C759)
private val PillAmber = Color(0xFFFF9500)

/**
 * [FEAT-261021-07] The home title plus the network pills in one column:
 * MinisCenterTopBar sizes the band to the title content's height, so the
 * pills sit right under the app name. With nothing configured the pills
 * vanish ([NetworkStatusMonitor.wanted]) and this is the plain title again.
 */
@Composable
internal fun HomeTitleWithNetworkPills(title: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        NetworkStatusPills()
    }
}

/** One compact row: the Tailscale pill (while the node is wanted) + one pill per proxy in use. */
@Composable
private fun NetworkStatusPills() {
    val settings by NetworkHub.settings.collectAsStateWithLifecycle()
    if (!NetworkStatusMonitor.wanted(settings)) return
    val ts by NetworkHub.tailscale.collectAsStateWithLifecycle()
    val applying by NetworkHub.applying.collectAsStateWithLifecycle()
    val lastError by NetworkHub.lastError.collectAsStateWithLifecycle()
    val failing by NetworkAlertCenter.failing.collectAsStateWithLifecycle()
    val proxyHealth by NetworkStatusMonitor.proxyHealth.collectAsStateWithLifecycle()
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (settings.nodeWanted) {
            val level = NetworkStatusMonitor.tailscaleLevel(
                ts, applying, lastError, NetworkAlertEvent.Subject.Tailscale in failing.keys,
            )
            val label = tailscalePillLabel(level)
            StatusPill(
                color = tailscalePillColor(level),
                text = label,
                description = stringResource(R.string.network_pill_tailscale) + ": " + label,
            )
        }
        for (proxy in NetworkStatusMonitor.proxiesInUse(settings)) {
            val level = NetworkStatusMonitor.proxyLevel(proxy.id, proxyHealth, failing.keys)
            StatusPill(
                color = proxyPillColor(level),
                text = proxy.displayName,
                description = proxy.displayName + ": " + proxyPillLabel(level),
            )
        }
    }
}

@Composable
private fun StatusPill(color: Color, text: String, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun tailscalePillColor(level: NetworkStatusMonitor.TailscaleLevel): Color = when (level) {
    NetworkStatusMonitor.TailscaleLevel.CONNECTED -> PillGreen
    NetworkStatusMonitor.TailscaleLevel.UNAVAILABLE -> MaterialTheme.colorScheme.error
    NetworkStatusMonitor.TailscaleLevel.NOT_CONNECTED -> MaterialTheme.colorScheme.outline
    else -> PillAmber
}

@Composable
private fun tailscalePillLabel(level: NetworkStatusMonitor.TailscaleLevel): String = stringResource(
    when (level) {
        NetworkStatusMonitor.TailscaleLevel.CONNECTED -> R.string.network_pill_connected
        NetworkStatusMonitor.TailscaleLevel.CONNECTING -> R.string.network_pill_connecting
        NetworkStatusMonitor.TailscaleLevel.NOT_LOGGED_IN -> R.string.network_pill_sign_in
        NetworkStatusMonitor.TailscaleLevel.AWAITING_APPROVAL -> R.string.network_badge_awaiting_approval
        NetworkStatusMonitor.TailscaleLevel.UNAVAILABLE -> R.string.network_badge_unavailable
        NetworkStatusMonitor.TailscaleLevel.NOT_CONNECTED -> R.string.network_badge_not_connected
    },
)

@Composable
private fun proxyPillColor(level: NetworkStatusMonitor.ProxyLevel): Color = when (level) {
    NetworkStatusMonitor.ProxyLevel.AVAILABLE -> PillGreen
    NetworkStatusMonitor.ProxyLevel.UNAVAILABLE -> MaterialTheme.colorScheme.error
    NetworkStatusMonitor.ProxyLevel.CHECKING -> PillAmber
}

@Composable
private fun proxyPillLabel(level: NetworkStatusMonitor.ProxyLevel): String = stringResource(
    when (level) {
        NetworkStatusMonitor.ProxyLevel.AVAILABLE -> R.string.network_badge_available
        NetworkStatusMonitor.ProxyLevel.CHECKING -> R.string.network_pill_connecting
        NetworkStatusMonitor.ProxyLevel.UNAVAILABLE -> R.string.network_badge_unavailable
    },
)
