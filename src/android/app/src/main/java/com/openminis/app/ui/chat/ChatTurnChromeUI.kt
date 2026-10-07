package com.openminis.app.ui.chat

// [T-cuplivo-turn-chrome] cuplivo-style turn headers: circular avatar +
// monospace name + monospace timestamp above every bubble, plus the visible
// action row under user bubbles (copy / retry / edit / more) that replaces
// "long-press only" discovery for the common actions.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** `2026-09-17 05:22:16` — cuplivo's zero-padded per-message stamp. Pure for tests. */
internal fun formatTurnTimestamp(ms: Long): String =
    if (ms <= 0L) {
        ""
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    }

@Composable
internal fun TurnAvatar(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(ChatColors.avatarBg, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Assistant turn header: avatar circle + mono name + mono timestamp below. */
@Composable
internal fun AssistantTurnHeader(
    speakerName: String?,
    speakerVendor: String?,
    createdAt: Long,
    avatar: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    ) {
        TurnAvatar { avatar() }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = speakerName?.takeIf { it.isNotBlank() } ?: "",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.primaryText.copy(alpha = 0.85f),
                maxLines = 1,
            )
            val ts = formatTurnTimestamp(createdAt)
            if (ts.isNotEmpty()) {
                Text(
                    text = ts,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ChatColors.metaText,
                )
            }
        }
    }
}

/** User turn header, mirrored: name + timestamp right-aligned, avatar last. */
@Composable
internal fun UserTurnHeader(createdAt: Long) {
    // [T-cuplivo-experimental-layout] Hidden unless the experimental layout is on.
    if (!com.openminis.app.ui.settings.experimentalChatLayoutEnabled(
            androidx.compose.ui.platform.LocalContext.current)) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "User",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText.copy(alpha = 0.85f),
            )
            val ts = formatTurnTimestamp(createdAt)
            if (ts.isNotEmpty()) {
                Text(
                    text = ts,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ChatColors.metaText,
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        TurnAvatar {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = ChatColors.link,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Visible action row under a user bubble (cuplivo: copy/refresh/pencil/more). */
@Composable
internal fun UserActionRow(
    onCopy: () -> Unit,
    onRetry: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onMore: () -> Unit,
) {
    // [T-cuplivo-experimental-layout] Hidden unless the experimental layout is on.
    if (!com.openminis.app.ui.settings.experimentalChatLayoutEnabled(
            androidx.compose.ui.platform.LocalContext.current)) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
    ) {
        ActionIcon(Icons.Default.ContentCopy, "Copy", onCopy)
        if (onRetry != null) ActionIcon(Icons.Default.Refresh, "Retry", onRetry)
        if (onEdit != null) ActionIcon(Icons.Default.Edit, "Edit", onEdit)
        ActionIcon(Icons.Default.MoreHoriz, "More", onMore)
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = ChatColors.secondaryText,
            modifier = Modifier.size(17.dp),
        )
    }
}
