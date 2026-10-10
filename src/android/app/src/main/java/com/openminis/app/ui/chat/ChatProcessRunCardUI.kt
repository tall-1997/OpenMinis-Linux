package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

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
    listState: LazyListState,
    onToggle: () -> Unit,
    onOpenTool: (String) -> Unit,
) {
    // [T-process-card-visual] Failure tints the ROWS only — the red error
    // row at the card bottom and the failed tool rows carry the failure.
    // The card accent stays blue even after a failed step, so a later
    // successful run does not repaint the whole pill red.
    val accent = Color(0xFF007AFF)
    // [T-process-card-dark] 深色不再用蓝叠黑：低 alpha 蓝叠纯黑底永远读不出
    // （12% 蓝 = RGB(0,13,30)，和背景一个样）。改实色抬升面 + iOS 分隔线色，
    // 蓝只留在图标/文字/进度上。浅色保持蓝 tint 家族不变。
    val cardFill = if (ChatColors.isDark) Color(0xFF1C1C1E) else Color(0xFF007AFF).copy(alpha = 0.06f)
    val cardStroke = if (ChatColors.isDark) Color(0xFF38383A) else Color(0xFF007AFF).copy(alpha = 0.15f)
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
            .background(cardFill, cardShape)
            .border(0.5.dp, cardStroke, cardShape)
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
                    "thinking" -> ProcessRunThinkingRow(block, listState)
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
        // [T-process-card-meta-position] Meta always rides the trailing
        // edge — collapsed and expanded must agree on where the count
        // lives, or the toggle reads as the meta jumping sides. The card
        // is full-width in both states, so weight() is always bounded.
        Spacer(modifier = Modifier.weight(1f))
        val toolCount = item.blocks.count { it.kind == "tool_use" }
        // [T-process-card-live-duration] 运行中也显示耗时（在飞块墙钟）。
        val durationSuffix = formatProcessDuration(rememberLiveProcessDurationMs(item))?.let { " · $it" } ?: ""
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
private fun ProcessRunThinkingRow(block: AssistantBlock, listState: LazyListState) {
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
    // [T-thinking-scroll-state] rememberSaveable：条目滚出视口随 item key 存档，滚回不重置。
    // [T-thinking-row-default-open] 初始即展开（有内容时）：旧行为在流结束 500ms
    // 后自动折回首行思考，且卡片折叠时行离开组合失档、再展开恒为折叠态——
    // 结果卡片展开后第一行永远是工具调用，思考内容看不见（用户报告）。
    // 现在思考完成后行保持展开（行只在卡片展开时组合，行高已封顶），
    // 卡片再展开时已完成思考也直接展开；手动点按仍可收起（userPinned 接管）。
    var expanded by rememberSaveable(block.id) { mutableStateOf(block.content.isNotBlank()) }
    var userPinned by rememberSaveable(block.id) { mutableStateOf(false) }
    // [T-thinking-collapse-jump] 折叠防跳转守卫（见 ChatProcessRowGuards）。
    val collapseGuard = rememberThinkingCollapseGuard(listState)
    LaunchedEffect(live, hasContent) {
        if (live && hasContent && !userPinned) expanded = true
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { collapseGuard.onHeaderPositioned(it.positionInRoot().y) }
                .clickable(enabled = hasContent) {
                    userPinned = true
                    if (expanded) collapseGuard.armCollapse()
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
            if (hasContent) ThinkingCharCountBadge(block.content.length)
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
            // [T-thinking-tail-follow] 没动过就随流式输出滚到最新；上滑接管；
            // 滚回底部恢复跟随（见 ChatProcessRowGuards）。
            val thinkScroll = rememberTailFollowingScroll("${block.id}-t", block.content.length)
            Text(
                text = block.content.takeLast(8000),
                fontSize = 11.sp,
                color = Color(0xFF8E8E93).copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(thinkScroll)
                    .padding(start = 30.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
            )
            // [T-mlkit-stream-translate] 实时离线翻译覆盖层：原文下方灰蓝小字，
            // 句级缓冲随思考更新；null = 未开启/引擎不可用（零渲染）。
            block.translatedContent?.takeIf { it.isNotBlank() }?.let { translated ->
                // [T-translate-isolated] 原文/译文隔离：细分隔线，两段各自成块。
                HorizontalDivider(
                    modifier = Modifier.padding(start = 30.dp, end = 12.dp, top = 4.dp),
                    thickness = 0.75.dp,
                    color = Color(0xFF8E8E93).copy(alpha = 0.25f),
                )
                val trScroll = rememberTailFollowingScroll("${block.id}-t-tr", translated.length)
                Text(
                    text = translated.takeLast(4000),
                    fontSize = 11.sp,
                    color = Color(0xFF32ADE6).copy(alpha = 0.9f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(trScroll)
                        .padding(start = 30.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
                )
            }
        }
    }
}
