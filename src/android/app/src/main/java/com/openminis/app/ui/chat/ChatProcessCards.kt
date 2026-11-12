package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.m3Icon
import kotlinx.coroutines.delay

/**
 * [T-provider-detail-ceiling-analogue] Process-card composables extracted from
 * ChatAssistantMessageUI when its architecture ceiling left no room for the
 * reasoning-translation UI. Pure display, no state beyond local toggles.
 */

@Composable
internal fun ProcessSummaryBar(
    thinkingCount: Int,
    toolCount: Int,
    expanded: Boolean,
    hasFailure: Boolean,
    totalMs: Long = 0L,
    steps: List<ProcessStepRef> = emptyList(),
    running: Boolean = false,
    activity: String = "",
    activityTitle: String = "",
    startedAtMs: Long = 0L,
    onToggle: () -> Unit,
    onOpenStep: (String) -> Unit = {},
) {
    // [P-process-card] Outlined capsule (collapsed) that grows into a card
    // listing every step (expanded). Replaces the centred hairline divider:
    // the reference treats a process run as one tappable object sitting
    // between reply paragraphs, not as a section rule.
    // [P-process-card] Reuse the thinking card's look so enabling folding is
    // not a visual break: same blue, same 12dp radius, same 6%/15% tint and
    // 0.5dp hairline, same 12×8 padding as ThinkingBlock. The old 22dp
    // backgroundless capsule looked nothing like the blocks it replaced.
    val thinkingBlue = Color(0xFF007AFF)
    val shape = RoundedCornerShape(12.dp)
    // [P-process-card] Failure tints ONLY the failed tool rows inside the
    // card (user direction): the header keeps its normal blue title — the
    // Error icon shape alone signals that something in the run failed.
    val iconTint = thinkingBlue
    // A running card ticks once a second so the duration walks. Bounded by
    // the run: the moment it settles, running flips false and the loop ends.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    val duration = if (running && startedAtMs > 0L) {
        formatProcessDuration((nowMs - startedAtMs).coerceAtLeast(0L))
    } else {
        formatProcessDuration(totalMs)
    }
    val header = processCardHeader(
        thinkingCount, toolCount, running, expanded, activity, activityTitle, duration,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.5.dp)
            .background(thinkingBlue.copy(alpha = 0.06f), shape)
            .border(0.5.dp, thinkingBlue.copy(alpha = 0.15f), shape)
            .clip(shape)
            .clickable(onClick = onToggle)
            // [P-process-card] Animate the expand/collapse height change:
            // without this the row list appears in one frame and the whole
            // screen visibly jumps (user: "让用户感觉整个屏幕闪一下"). A
            // gentle spring keeps the card growing/shrinking in place while
            // the list reflows around it.
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.5.dp,
                    color = iconTint,
                )
            } else {
                Icon(
                    imageVector = if (hasFailure) m3Icon(Icons.Default.Error) else m3Icon(Icons.Default.CheckCircle),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = header,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = thinkingBlue,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (expanded) m3Icon(Icons.Default.KeyboardArrowUp) else m3Icon(Icons.Default.KeyboardArrowDown),
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = thinkingBlue.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded && steps.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                thickness = 0.5.dp,
                color = thinkingBlue.copy(alpha = 0.15f),
            )
            // [P-process-card] Compact step rows, NOT the unfolded blocks:
            // the full ThinkingBlock / ToolCallPill each carry their own
            // bubble background and 36dp pill height, so an expanded card
            // was a stack of mismatched bubbles (user: "长短不一、背景颜色
            // 不统一、卡片过大"). Inside the card every step is one tight
            // row — icon (from the SAME toolIconFor/toolAccentColor mapping
            // the unfolded pills use, never invented), title, duration,
            // 查看 for tools, expand chevron for thinking — separated by
            // hairlines, no per-row background. The card is the only bubble.
            steps.forEachIndexed { idx, step ->
                if (idx > 0) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = thinkingBlue.copy(alpha = 0.10f),
                    )
                }
                ProcessStepRow(step = step, onOpen = { onOpenStep(step.id) })
            }
        }
    }
}

/**
 * [P-process-card] One compact row inside an expanded process card.
 *
 * Tool row: the tool's own icon (toolIconFor — the same mapping the
 * unfolded pill uses) in its accent color, title, duration, and a 查看
 * link that opens the shared detail sheet. A FAILED tool tints its icon
 * AND title in the error colour — the only place failure is coloured.
 *
 * Thinking row: the Psychology icon, 思考, char count, duration, and a
 * chevron that unfolds the reasoning text inline (300dp cap, scrollable).
 * A running thinking step auto-expands and settles 500ms after it
 * finishes; a step the user collapsed by hand is never reopened by the
 * card expanding — the two layers are independent, keyed on step.id.
 */
@Composable
private fun ProcessStepRow(step: ProcessStepRef, onOpen: () -> Unit) {
    val thinkingBlue = Color(0xFF007AFF)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val isThinking = step.kind == "thinking"
    val isFailed = !isThinking && step.status == ToolBlockStatus.FAILED
    val block = step.block

    var stepExpanded by remember(step.id) { mutableStateOf(step.running) }
    var userTouched by remember(step.id) { mutableStateOf(false) }
    LaunchedEffect(step.id, step.running) {
        if (!step.running && !userTouched) {
            delay(500)
            stepExpanded = false
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (isThinking) {
                        userTouched = true
                        stepExpanded = !stepExpanded
                    } else {
                        onOpen()
                    }
                }
                .padding(vertical = 5.dp),
        ) {
            if (isThinking) {
                Icon(
                    imageVector = m3Icon(Icons.Default.Psychology),
                    contentDescription = null,
                    tint = thinkingBlue,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Icon(
                    imageVector = m3Icon(toolIconFor(block.toolName)),
                    contentDescription = null,
                    tint = if (isFailed) ToolErrorColor else toolAccentColor(block.toolName),
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isThinking) stringResource(R.string.chat_thinking_header)
                    else displayToolTitle(block.toolName, block.toolArgs, block.toolTitle),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (isFailed) ToolErrorColor else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isThinking) {
                val charCount = block.content.length
                if (charCount > 0) {
                    Text(
                        text = if (charCount >= 1000) "${charCount / 1000}K" else "$charCount",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = muted,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }
            formatProcessDuration(step.durationMs)?.let { dur ->
                Text(
                    text = dur,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = muted,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            if (!isThinking) {
                Text(
                    text = stringResource(R.string.chat_process_step_view),
                    fontSize = 12.sp,
                    color = thinkingBlue,
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Icon(
                imageVector = if (isThinking) {
                    if (stepExpanded) m3Icon(Icons.Default.KeyboardArrowUp) else m3Icon(Icons.Default.KeyboardArrowDown)
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = null,
                tint = if (isThinking) thinkingBlue.copy(alpha = 0.5f) else muted,
                modifier = Modifier.size(14.dp),
            )
        }
        if (isThinking) {
            AnimatedVisibility(visible = stepExpanded && block.content.isNotEmpty()) {
                val scrollState = rememberScrollState()
                val isTruncated = block.content.length > 8000
                val displayContent = visibleThinkingTail(block.content, 8000)
                ThinkingAutoFollow(
                    blockId = block.id,
                    contentVersionValue = block.content.length,
                    isStreaming = step.running,
                    scrollState = scrollState,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, top = 2.dp, bottom = 6.dp)
                        .heightIn(max = 300.dp)
                        .verticalScroll(scrollState),
                ) {
                    if (isTruncated) {
                        Text(
                            text = stringResource(
                                R.string.thinking_truncated_hint,
                                displayContent.length / 1000,
                                block.content.length / 1000,
                            ),
                            fontSize = 11.sp,
                            color = muted.copy(alpha = 0.75f),
                        )
                    }
                    Text(
                        text = displayContent,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = muted,
                    )
                }
            }
        }
    }
}
