package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay

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
    // [T-process-card-visual] Failure tints the ROWS only — the red error
    // row at the card bottom and the failed tool rows carry the failure.
    // The card accent stays blue even after a failed step, so a later
    // successful run does not repaint the whole pill red.
    val accent = Color(0xFF007AFF)
    val cardBackground = Color(0xFF007AFF)
    // [T-process-card-visual] One shape for both states — the collapsed
    // state is just the folded card, not a separate pill form.
    val cardShape = RoundedCornerShape(12.dp)
    // [T-process-card-pill-width] One width for both states — the folded
    // card is the same card with its height collapsed, not a shrinking
    // pill. A width jump on toggle reads as a different element.
    val cardWidth = Modifier.fillMaxWidth()
    Column(
        modifier = cardWidth
            .padding(vertical = 4.dp)
            .background(cardBackground.copy(alpha = 0.06f), cardShape)
            .border(0.5.dp, cardBackground.copy(alpha = 0.15f), cardShape)
            .clip(cardShape),
    ) {
        ProcessRunCardHeader(
            item = item,
            accent = accent,
            onToggle = onToggle,
        )
        if (item.expanded) {
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
            // Collapsed: compact but not squashed — vertical 8dp keeps the
            // folded strip readable (~32dp tall with the 11sp line), while
            // expanded keeps the spec-6.1 header padding (~46dp tap target).
            .padding(
                horizontal = if (item.expanded) 16.dp else 12.dp,
                vertical = if (item.expanded) 12.dp else 8.dp,
            ),
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
        // [T-process-card-visual] No static "工作过程" title — the bar leads
        // with the live phase verb while running and folds straight into
        // the right-side tool-count meta when done. One line, no filler.
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
        if (item.expanded) {
            // Expanded: push the count meta to the trailing edge of the
            // full-width card. Collapsed: fixed gap — the pill hugs its
            // content, and weight() needs a bounded row anyway.
            Spacer(modifier = Modifier.weight(1f))
        } else {
            Spacer(modifier = Modifier.width(6.dp))
        }
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
    // [T-thinking-auto-fold] Live thinking auto-expands so the stream is
    // readable while it runs; 500ms after the stream ends the row folds
    // itself back. One manual tap pins the row to user control — both
    // auto behaviors stand down from then on. Re-expanding the collapsed
    // CARD starts this row folded: the remember state dies with the
    // composition when the card folds, so the initial state below is the
    // only thing that comes back. Only the tail window of the content is
    // laid out — same layout-cost guard as the standalone thinking block,
    // which matters because the flat list rebuilds per streaming tick.
    val hasContent = block.content.isNotBlank()
    var expanded by remember(block.id) { mutableStateOf(false) }
    var userPinned by remember(block.id) { mutableStateOf(false) }
    LaunchedEffect(live, hasContent) {
        if (live && hasContent && !userPinned) expanded = true
    }
    LaunchedEffect(live, userPinned) {
        if (!live && !userPinned && expanded) {
            delay(500)
            expanded = false
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasContent) {
                    userPinned = true
                    expanded = !expanded
                }
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
            if (hasContent) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color(0xFF8E8E93).copy(alpha = 0.6f),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        if (expanded && hasContent) {
            // Tail window only (8000 chars) — a huge thinking block must
            // not re-measure its full text on every streaming tick.
            // [T-process-card-thinking-height] 行高封顶 + 可滚动：8000 字符
            // 尾窗排版出来仍有约 200 行高，随思考输出把卡片无限拉长；封顶后
            // 卡片高度恒定，内容在框内滚动。
            Text(
                text = block.content.takeLast(8000),
                fontSize = 11.sp,
                color = Color(0xFF8E8E93).copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 30.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
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
