package com.openminis.app.ui.chat
import com.openminis.app.ui.theme.m3Icon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.security.InterceptEvent
import com.openminis.app.service.ApprovalGate

@Composable
fun ChatGateBanners(
    approvals: Map<String, ApprovalGate.ApprovalRequest>,
    intercepts: List<InterceptEvent>,
    onApprove: (String) -> Unit,
    onApproveAll: (String) -> Unit,
    onDeny: (String) -> Unit,
    onDismissIntercept: (String) -> Unit,
) {
    if (approvals.isEmpty() && intercepts.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        approvals.values.sortedBy { it.timestamp }.forEach { req ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.gate_approval_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    Text(
                        "${req.toolName}: ${req.preview}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { onApprove(req.id) }) {
                            Text(stringResource(R.string.gate_approval_approve))
                        }
                        // [T-session-allow-all] Approve this request and
                        // stop asking for the rest of the session.
                        OutlinedButton(onClick = { onApproveAll(req.id) }) {
                            Text(stringResource(R.string.gate_approval_allow_all))
                        }
                        OutlinedButton(onClick = { onDeny(req.id) }) {
                            Text(stringResource(R.string.gate_approval_deny))
                        }
                    }
                }
            }
        }
        intercepts.forEach { event ->
            val title = if (event.kind == InterceptEvent.Kind.DENIED) {
                stringResource(R.string.gate_intercept_denied)
            } else {
                stringResource(R.string.gate_intercept_rejected)
            }
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Block,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "${event.toolName}: ${event.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    IconButton(onClick = { onDismissIntercept(event.id) }) {
                        Icon(
                            m3Icon(Icons.Default.Close),
                            contentDescription = stringResource(R.string.gate_intercept_dismiss),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}
