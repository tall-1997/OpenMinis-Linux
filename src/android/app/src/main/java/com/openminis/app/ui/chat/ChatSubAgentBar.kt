package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.service.SubAgentActivityTracker
import com.openminis.app.tools.SubAgentRunner
import com.openminis.app.ui.markdown.MarkdownText
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-wave-e-subagent-card] Live roster bar for concurrent spawn_agent
 * members, modelled on the task chips of mature agent clients: each member
 * is a compact chip with up to three single-line rows —
 *   1. identity: phase dot, task title, i/N, stop;
 *   2. live status: phase, in-flight tool, turn, tool-call count;
 *   3. newest output line, on its OWN row.
 * The newest output used to be the last field of the shared status row, where
 * the ellipsis ate it first — exactly the line the chip exists to show.
 *
 * The stop affordance keeps a 48dp touch target independently of its small
 * glyph, so compact visuals do not make the destructive action hard to hit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAgentLiveBar(
    sessionId: String,
    modifier: Modifier = Modifier,
    onStop: (String) -> Unit = {},
) {
    val members by SubAgentActivityTracker.members.collectAsStateWithLifecycle()
    val mine = members.filter { it.parentSessionId == sessionId }
    var detailMemberId by remember(sessionId) { mutableStateOf<String?>(null) }
    // [T-subagent-sheet-survives-finish] The tracker REMOVES a member the
    // moment it finishes (SubAgentActivityTracker.finish), so looking it up
    // live closed the open sheet exactly when the user wanted to read the
    // final output. Keep the last snapshot for the selected id and render it
    // until the user dismisses.
    var detailSnapshot by remember(sessionId) { mutableStateOf<SubAgentActivityTracker.Member?>(null) }
    LaunchedEffect(detailMemberId, mine) {
        if (detailMemberId == null) {
            detailSnapshot = null
        } else {
            mine.firstOrNull { it.id == detailMemberId }?.let { detailSnapshot = it }
        }
    }

    if (mine.isNotEmpty()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(ChatColors.background.copy(alpha = 0.94f))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            mine.forEach { m ->
                key(m.id) {
                    SubAgentChip(
                        member = m,
                        onClick = {
                            detailSnapshot = m
                            detailMemberId = m.id
                        },
                        onStop = { onStop(m.id) },
                    )
                }
            }
        }
    }
    val detailMember = detailMemberId?.let { id ->
        mine.firstOrNull { it.id == id } ?: detailSnapshot?.takeIf { it.id == id }
    }
    if (detailMember != null) {
        SubAgentDetailSheet(
            member = detailMember,
            isActive = mine.any { it.id == detailMember.id },
            onDismiss = { detailMemberId = null },
            onStop = onStop,
        )
    }
}

@Composable
private fun SubAgentChip(
    member: SubAgentActivityTracker.Member,
    onClick: () -> Unit,
    onStop: () -> Unit,
) {
    val phase = member.phase.ifBlank { "思考中" }
    val bg = phaseColor(phase)
    val title = member.title.ifBlank { stringResource(R.string.subagent_fallback_label) }
    Column(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .background(bg, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 5.dp),
    ) {
        // Row 1 — identity: phase dot, task title, i/N, compact stop.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Color.White, CircleShape),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (member.index > 0 && member.total > 0) {
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.subagent_index_of_total, member.index, member.total),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.width(2.dp))
            // Keep the small glyph inside an accessible touch target.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onStop),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.subagent_stop_desc, title),
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        // Keep the live card to identity + activity; full output and counts live in the sheet.
        val status = buildString {
            append(phase)
            member.currentTool.takeIf { it.isNotBlank() }?.let {
                append(" · ").append(subAgentToolTitle(it, ""))
            }
        }
        Text(
            text = status,
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 11.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubAgentDetailSheet(
    member: SubAgentActivityTracker.Member,
    isActive: Boolean,
    onDismiss: () -> Unit,
    onStop: (String) -> Unit = {},
) {
    val phase = if (isActive) member.phase.ifBlank { "思考中" }
        else stringResource(R.string.scheduled_task_state_ended)
    var expandedToolId by remember(member.id) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val tail = member.steps.lastOrNull()
    val liveToolId = member.steps.lastOrNull {
        it.kind == "tool" && (it.status == "running" || it.status == "streaming" || it.status == "pending")
    }?.id
    LaunchedEffect(liveToolId) {
        if (liveToolId != null) expandedToolId = liveToolId
    }
    LaunchedEffect(member.steps.size, tail?.body?.length, tail?.status, phase) {
        val total = listState.layoutInfo.totalItemsCount
        if (total <= 0) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= total - 2) listState.scrollToItem(total - 1)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ChatColors.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            // Header: identity + phase banner, with the destructive stop on
            // the trailing edge — a red text button wedged under the steer
            // input read as a second submit action and invited mis-taps.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = memberLabel(member),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ChatColors.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = listOfNotNull(
                            member.role?.takeIf { it.isNotBlank() },
                            member.model?.takeIf { it.isNotBlank() },
                            if (member.turnCap > 0) {
                                stringResource(
                                    R.string.subagent_turn_of,
                                    member.turnIndex.coerceAtLeast(1),
                                    member.turnCap,
                                )
                            } else null,
                        ).joinToString(" · "),
                        fontSize = 12.sp,
                        color = ChatColors.primaryText.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(enabled = isActive, onClick = { onStop(member.id) }) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.subagent_stop_desc, member.title),
                        tint = Color(0xFFFF3B30),
                    )
                }
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(modifier = Modifier.height(6.dp))
                PhaseBanner(phase = phase, tool = if (isActive) member.currentTool else "")
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "task") {
                    UserTaskBubble(member.title)
                }
                if (member.steps.isEmpty() && isActive) {
                    item(key = "waiting") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TypingIndicator()
                            Text(
                                text = stringResource(R.string.subagent_waiting_first_output),
                                fontSize = 13.sp,
                                color = ChatColors.primaryText.copy(alpha = 0.6f),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
                items(member.steps, key = { it.id }) { step ->
                    when (step.kind) {
                        "thinking" -> ThinkingBlock(
                            block = step.toAssistantBlock(),
                            isStreaming = step.status == "streaming",
                            isLast = step.id == member.steps.lastOrNull { it.kind == "thinking" }?.id,
                        )
                        "tool" -> Column {
                            ToolCallPill(
                                block = step.toAssistantBlock(),
                                onOpenDetail = { id ->
                                    expandedToolId = if (expandedToolId == id) null else id
                                },
                            )
                            if (expandedToolId == step.id) {
                                ToolStepBody(step)
                            }
                        }
                        else -> MarkdownText(
                            markdown = step.body,
                            color = ChatColors.primaryText,
                            style = TextStyle(
                                fontSize = 15.sp,
                                lineHeight = 22.sp,
                                color = ChatColors.primaryText,
                            ),
                        )
                    }
                }
            }
            // Steering row: a supplementary instruction delivered to the
            // RUNNING child at its next turn boundary (SubAgentRunner drains
            // the channel). Refused with an inline message when the lane
            // already ended. Stop lives in the header now.
            if (isActive) SubAgentSteerRow(memberId = member.id)
        }
    }
}

@Composable
private fun SubAgentSteerRow(memberId: String) {
    var text by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // The queued/refused note self-clears — a stale "refused" pinned under
    // the input read as if every later send failed too.
    LaunchedEffect(feedback) {
        if (feedback != null) {
            kotlinx.coroutines.delay(4000)
            feedback = null
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.subagent_steer_hint), fontSize = 13.sp, maxLines = 1) },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(fontSize = 14.sp, color = ChatColors.primaryText),
                singleLine = true,
            )
            Spacer(modifier = Modifier.width(8.dp))
            androidx.compose.material3.TextButton(
                onClick = {
                    val sent = SubAgentActivityTracker.steer(memberId, text)
                    feedback = context.getString(
                        if (sent) R.string.subagent_steer_queued else R.string.subagent_steer_refused,
                    )
                    if (sent) text = ""
                },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.subagent_steer_send))
            }
        }
        feedback?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = it, fontSize = 11.sp, color = ChatColors.primaryText.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun PhaseBanner(phase: String, tool: String) {
    val color = phaseColor(phase)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .border(0.5.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(color, RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(text = phase, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (phase == "思考中" || phase == "回复中" || phase == "执行中") {
            Spacer(modifier = Modifier.padding(start = 4.dp))
            StreamingDotsText()
        }
        if (tool.isNotBlank()) {
            Text(
                text = subAgentToolTitle(tool, ""),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ToolStepBody(step: SubAgentActivityTracker.Step) {
    // [T-android-chat-strings-resource] Display labels only — the when below
    // that feeds phaseColor keeps the raw literals, which are DATA keys the
    // tracker service also emits and compares against.
    val status = when (step.status) {
        "running" -> stringResource(R.string.subagent_status_running)
        "streaming", "pending" -> stringResource(R.string.subagent_status_calling_tool)
        "success" -> stringResource(R.string.subagent_status_done)
        "failed" -> stringResource(R.string.subagent_status_failed)
        else -> step.status
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
            .background(ChatColors.secondaryBg, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Text(
            text = status,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            // Failed reads as error-red, matching the main timeline's tool
            // failure colour — orange was reserved for the in-flight phase.
            color = if (step.status == "failed") ToolErrorColor else phaseColor(
                when (step.status) {
                    "running", "success" -> "执行中"
                    else -> "调用工具"
                },
            ),
        )
        if (step.toolArgs.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = stringResource(R.string.subagent_args_label), fontSize = 11.sp, color = ChatColors.primaryText.copy(alpha = 0.5f))
            Text(
                text = step.toolArgs,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.primaryText,
                modifier = Modifier
                    .heightIn(max = 120.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        if (step.body.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = stringResource(R.string.subagent_result_label), fontSize = 11.sp, color = ChatColors.primaryText.copy(alpha = 0.5f))
            Text(
                text = step.body,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = ChatColors.primaryText,
                modifier = Modifier
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun UserTaskBubble(title: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text = title.ifBlank { stringResource(R.string.subagent_task_fallback) },
            color = ChatColors.primaryText,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(ChatColors.userBubble, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// [T-android-chat-strings-resource] Now composable so the display label can
// come from resources; callers render it directly in composition.
@Composable
private fun memberLabel(member: SubAgentActivityTracker.Member): String =
    if (member.index > 0 && member.total > 0) {
        stringResource(R.string.subagent_index_of_total, member.index, member.total)
    } else {
        member.title.ifBlank { stringResource(R.string.subagent_fallback_label) }
    }

private fun phaseColor(phase: String): Color = when (phase) {
    "调用工具" -> Color(0xFFFF9500)
    "执行中" -> Color(0xFF34C759)
    "回复中" -> Color(0xFF5856D6)
    else -> Color(0xFF007AFF)
}

internal fun subAgentToolTitle(name: String, args: String): String {
    val label = when (name) {
        "shell_execute", "shell_exec", "env_exec" -> "执行命令"
        "file_read" -> "读取文件"
        "file_write" -> "写入文件"
        "file_edit", "multi_edit" -> "编辑文件"
        "grep_source", "grep" -> "搜索代码"
        "web_search", "clientfn_web_search" -> "搜索网页"
        "web_fetch" -> "抓取网页"
        "browser_use" -> "浏览器"
        "memory_get", "memory_write" -> "记忆"
        "spawn_agent", "dispatch_agents" -> "子代理"
        else -> name
    }
    val preview = if (args.isBlank()) "" else SubAgentRunner.previewToolArgs(args).take(42)
    return if (preview.isBlank()) label else "$label · $preview"
}

private fun SubAgentActivityTracker.Step.toAssistantBlock(): AssistantBlock {
    val toolStatus = when (status) {
        "streaming" -> ToolBlockStatus.STREAMING
        "pending" -> ToolBlockStatus.PENDING
        "running" -> ToolBlockStatus.RUNNING
        "success" -> ToolBlockStatus.SUCCESS
        "failed" -> ToolBlockStatus.FAILED
        else -> null
    }
    return AssistantBlock(
        id = id,
        kind = if (kind == "thinking" || kind == "text") kind else "tool_use",
        content = body,
        toolStatus = toolStatus,
        toolTitle = if (kind == "tool") subAgentToolTitle(toolName, toolArgs) else title,
        toolName = toolName,
        toolArgs = toolArgs,
        durationMs = durationMs,
        startTimeMs = startedAt,
    )
}
