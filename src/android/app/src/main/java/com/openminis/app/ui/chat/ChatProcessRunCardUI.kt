package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R

/**
 * [T-process-run-card] The unified process card for one assistant turn.
 *
 * Replaces the old AssistantProcessSummary fold bar + the in-list live
 * thinking row + the in-flight tool pill trio with ONE card that owns the
 * whole run:
 *
 *  - collapsed bar: "工作过程 · 已调用 N 个工具 · 47s" (title, tool count,
 *    duration, chevron) — same visual family as the old fold bar;
 *  - expanded (auto while the turn is live and no reply text has arrived,
 *    manual tap otherwise): live phase header ("正在思考中…" /
 *    "正在调用 shell_execute…"), the one-line task description, then
 *    thinking/tool entries appended in chronological order, each tool
 *    row opening its ToolDetailSheet via [onOpenTool];
 *  - the turn error rides INSIDE the card as a red row so a failed run
 *    still reads as one process, not a card plus a detached banner.
 *
 * The card renders at the visual TOP of the turn (emitted right after
 * AssistantHeader; the list is reverse-laid-out) so a running turn shows
 * its live state where the reply will appear, not below it.
 */
@Composable
internal fun ProcessRunCard(
    item: FlatChatItem.ProcessRunCard,
    onToggle: () -> Unit,
    onOpenTool: (String) -> Unit,
) {
    val accent = if (item.hasFailure || item.errorText.isNotEmpty()) {
        Color(0xFFFF3B30)
    } else {
        Color(0xFF007AFF)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(accent.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .border(0.5.dp, accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)),
    ) {
        ProcessRunCardHeader(
            item = item,
            accent = accent,
            onToggle = onToggle,
        )
        if (item.expanded) {
            if (item.taskDescription.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.chat_process_card_task_label) +
                        " · " + item.taskDescription,
                    fontSize = 11.sp,
                    color = accent.copy(alpha = 0.55f),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            item.blocks.forEach { block ->
                when (block.kind) {
                    "thinking" -> ProcessRunThinkingRow(block)
                    "tool_use" -> ProcessRunToolRow(
                        block = block,
                        onOpenTool = onOpenTool,
                    )
                }
            }
            if (item.errorText.isNotEmpty()) {
                ProcessRunErrorRow(item.errorText)
            }
        }
    }
}

@Composable
private fun ProcessRunCardHeader(
    item: FlatChatItem.ProcessRunCard,
    accent: Color,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val headerIcon = when (item.phaseKind) {
            ProcessPhaseKind.THINKING -> Icons.Default.Psychology
            ProcessPhaseKind.TOOL -> toolIconFor(item.phaseToolName)
            ProcessPhaseKind.REPLYING -> Icons.Default.EditNote
            ProcessPhaseKind.DONE -> Icons.Default.CheckCircle
        }
        Icon(
            imageVector = headerIcon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.chat_process_card_title),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Spacer(modifier = Modifier.width(8.dp))
        // Live phase text (spinner + verb) while running; the done state
        // folds into the right-side meta so the bar stays one line.
        if (item.isRunning) {
            val phaseText = when (item.phaseKind) {
                ProcessPhaseKind.THINKING -> stringResource(R.string.chat_process_card_phase_thinking)
                ProcessPhaseKind.TOOL -> stringResource(
                    R.string.chat_process_card_phase_tool,
                    toolDisplayName(item.phaseToolName),
                )
                ProcessPhaseKind.REPLYING -> stringResource(R.string.chat_process_card_phase_replying)
                ProcessPhaseKind.DONE -> stringResource(R.string.chat_process_card_phase_done)
            }
            CircularProgressIndicator(
                strokeWidth = 1.2.dp,
                color = accent,
                modifier = Modifier.size(11.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = phaseText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = accent.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        val toolCount = item.blocks.count { it.kind == "tool_use" }
        val durationSuffix = formatProcessDuration(item.totalMs)?.let { " · $it" } ?: ""
        Text(
            text = stringResource(
                R.string.chat_process_card_tool_count,
                toolCount,
            ) + durationSuffix,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = accent.copy(alpha = 0.6f),
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = if (item.expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (item.expanded) "Collapse" else "Expand",
            tint = accent.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun ProcessRunThinkingRow(block: AssistantBlock) {
    val live = block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Psychology,
            contentDescription = null,
            tint = Color(0xFF8E8E93),
            modifier = Modifier.size(12.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.chat_process_card_thinking_row),
            fontSize = 12.sp,
            color = Color(0xFF8E8E93),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (live) {
            CircularProgressIndicator(
                strokeWidth = 1.2.dp,
                color = Color(0xFF8E8E93),
                modifier = Modifier.size(11.dp),
            )
        } else if (block.durationMs > 0) {
            Text(
                text = formatProcessDuration(block.durationMs).orEmpty(),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF8E8E93).copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ProcessRunToolRow(
    block: AssistantBlock,
    onOpenTool: (String) -> Unit,
) {
    val isRunning = isInFlightProcessTool(block)
    val isFailed = block.toolStatus == ToolBlockStatus.FAILED ||
        block.toolStatus == ToolBlockStatus.TIMEOUT
    val isCancelled = block.toolStatus == ToolBlockStatus.CANCELLED
    val isDone = block.toolStatus == ToolBlockStatus.SUCCESS
    val iconTint = when {
        isFailed -> ToolErrorColor
        isCancelled -> ToolCancelColor
        isDone -> ToolCheckColor
        else -> toolAccentColor(block.toolName)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenTool(block.id) }
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Icon(
            imageVector = toolIconFor(block.toolName),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(12.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        val title = block.toolTitle.ifEmpty { block.toolName }.ifEmpty { "tool" }
        Text(
            text = title,
            fontSize = 12.sp,
            color = if (isFailed) ToolErrorColor else Color(0xFF3C3C43),
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
        Text(
            text = stringResource(R.string.chat_process_card_view),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF007AFF).copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}

@Composable
private fun ProcessRunErrorRow(errorText: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = ToolErrorColor,
            modifier = Modifier.size(13.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.chat_process_card_error_row) + "\n" + errorText,
            fontSize = 12.sp,
            color = ToolErrorColor,
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
    Spacer(modifier = Modifier.height(2.dp))
}
