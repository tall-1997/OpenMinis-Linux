package com.openminis.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.tools.AgentPlanStore

/**
 * [T-plan-board-sticky] 会话级任务列表看板（taixu StickyPlanBar 形态）：数据来自
 * 模型经 agent_plan 工具写入的 [AgentPlanStore]。挂在输入框上缘（Column 中位于
 * 消息列表 weight(1f) 之后）——收起时单行显示当前步骤 + 进度，点击向上展开
 * 完整列表；展开吃的是列表空间（列表收缩），不遮挡聊天记录；浮动工具条与
 * 工具预览缩略图 overlay 在上方消息区 Box 内，与看板互不重叠。
 *
 * 数据刷新：isStreaming 翻转驱动 + 每 2s 轮询（流式期间工具频繁更新）。
 * 传入真实 context：list() 命中内存缓存无 IO，冷启动从会话文件恢复——
 * 旧实现传 null 只读内存，进程重启后看板空白（用户报告）。
 * 空看板不渲染（return），零占位。
 */
@Composable
fun SessionPlanBoardCard(
    sessionId: String?,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (sessionId.isNullOrBlank()) return
    val context = LocalContext.current
    var plans by remember(sessionId) { mutableStateOf<List<AgentPlanStore.Plan>>(emptyList()) }
    LaunchedEffect(sessionId, isStreaming) {
        while (true) {
            plans = AgentPlanStore.list(sessionId, context)
            kotlinx.coroutines.delay(2_000)
        }
    }
    if (plans.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val completed = plans.count { it.status == "done" }
    val failed = plans.count { it.status == "failed" }
    val allDone = completed == plans.size
    // 收起态标题：首个未完成项（taixu 同款）——一眼看到"现在在干嘛"。
    val activeStep = plans.firstOrNull { it.status == "pending" || it.status == "active" }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        border = BorderStroke(
            1.dp,
            if (allDone) MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (allDone) Icons.Default.CheckCircle else Icons.Outlined.Checklist,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    when {
                        allDone -> stringResource(R.string.plan_board_all_done)
                        activeStep != null -> activeStep.title
                        else -> stringResource(R.string.plan_board_header)
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (failed > 0) "$completed/${plans.size} · ${stringResource(R.string.plan_board_failed_n, failed)}"
                    else "$completed/${plans.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(12.dp),
                )
            }
            // 细进度条（收起/展开都显示）。
            LinearProgressIndicator(
                progress = { if (plans.isEmpty()) 0f else completed.toFloat() / plans.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    plans.forEach { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            when (p.status) {
                                "done" -> Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp),
                                )
                                "failed" -> Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(MaterialTheme.colorScheme.error, CircleShape),
                                )
                                "active" -> Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                                )
                                else -> Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                            CircleShape,
                                        ),
                                )
                            }
                            Text(
                                p.title,
                                style = MaterialTheme.typography.bodyMedium,
                                textDecoration = if (p.status == "done") TextDecoration.LineThrough else null,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
