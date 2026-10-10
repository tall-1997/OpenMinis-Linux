package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-process-run-card] One tool entry inside the process card.
 *
 * Extracted from ChatProcessRunCardUI.kt to keep that file under the
 * architecture ratchet — the ML Kit translation overlay grew it past
 * the new-file limit.
 */
@Composable
internal fun ProcessRunToolRow(
    block: AssistantBlock,
    onOpenTool: (String) -> Unit,
) {
    val isRunning = isInFlightProcessTool(block)
    val isFailed = block.toolStatus == ToolBlockStatus.FAILED ||
        block.toolStatus == ToolBlockStatus.TIMEOUT
    val isCancelled = block.toolStatus == ToolBlockStatus.CANCELLED
    val isDone = block.toolStatus == ToolBlockStatus.SUCCESS
    // [T-agent-ui-design-system] Muted row (spec 6.2 .row.muted): a tool
    // that finished with no viewable output is a pure status row — no
    // "查看" affordance, not clickable, name in the process gray.
    // Failed/cancelled rows stay tappable (error detail is the point).
    val isMuted = isDone && block.content.isBlank()
    val iconTint = when {
        isFailed -> ToolErrorColor
        isCancelled -> ToolCancelColor
        isDone -> ToolCheckColor
        else -> toolAccentColor(block.toolName)
    }
    // Halo marks rows carrying a live/terminal state (spec 6.2 dot+halo);
    // muted rows render the bare icon.
    val showHalo = isRunning || isFailed || isCancelled || (isDone && !isMuted)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isMuted) { onOpenTool(block.id) }
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        ToolStatusHaloIcon(
            icon = toolIconFor(block.toolName),
            tint = iconTint,
            halo = showHalo,
        )
        Spacer(modifier = Modifier.width(6.dp))
        val title = block.toolTitle.ifEmpty { block.toolName }.ifEmpty { "tool" }
        Text(
            text = title,
            fontSize = 12.sp,
            color = when {
                isFailed -> ToolErrorColor
                isMuted -> Color(0xFF8E8E93)
                // iOS .label: light 3C3C43 / dark EBEBF5 — follows the in-app
                // theme override (ChatColors.isDark), not the system setting.
                else -> if (ChatColors.isDark) Color(0xFFEBEBF5) else Color(0xFF3C3C43)
            },
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (isRunning) {
            CircularProgressIndicator(
                strokeWidth = 1.2.dp,
                color = iconTint,
                modifier = Modifier.size(11.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        } else if (block.durationMs > 0) {
            Text(
                text = formatProcessDuration(block.durationMs).orEmpty(),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF8E8E93).copy(alpha = 0.7f),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        if (!isMuted) {
            Text(
                text = stringResource(R.string.chat_process_card_view),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF007AFF).copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}
