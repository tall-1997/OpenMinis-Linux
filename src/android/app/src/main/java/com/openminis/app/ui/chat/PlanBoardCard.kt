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
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.tools.AgentPlanStore
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-plan-board-sticky] 会话级任务列表看板（taixu StickyPlanBar 形态）：数据来自
 * 模型经 agent_plan 工具写入的 [AgentPlanStore]。挂在输入框上缘（Column 中位于
 * 消息列表 weight(1f) 之后）——收起时单行显示当前步骤 + 进度，点击向上展开
 * 完整列表；展开吃的是列表空间（列表收缩），不遮挡聊天记录；浮动工具条与
 * 工具预览缩略图 overlay 在上方消息区 Box 内，与看板互不重叠。
 *
 * [T-plan-board-style] 视觉语言与过程卡片（ChatProcessRunCardUI）同源：四角统一
 * 12dp 圆角的独立卡片（旧版只圆上两角、下缘切平贴输入框，像被裁掉一截）；底色/
 * 边框走 ChatColors.isDark 双值（深色实底 1C1C1E/38383A，浅色蓝 tint 6%/15%），
 * 状态色全用 iOS 系统色（深浅同值）——不再走 M3 colorScheme，那套不跟随 app 内
 * 调色板，是旧版与整体风格脱节的根因。展开/收起操作点从表头右侧 12dp 小箭头
 * 移到卡片底部整宽 40dp 触控条（[T-plan-board-toggle]），拇指区可及。
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
    // 收起态标题 = 当前任务：并行 active 取最先开始的，完成后自动换下一条（pickCurrentStep）。
    val current = pickCurrentStep(plans)
    // [T-plan-board-autoclear] 轮次任务彻底做完（全 done、无 failed/在飞）后自动
    // 清场隐藏：绿态停留 4s 供确认，复查仍是全 done 才 clear 会话看板——卡片随
    // 空板消失，下一轮 agent_plan 从零开始。failed 不算"彻底做完"，看板保留；
    // 等待窗口内写入的新任务会被复查挡下，不误清新轮次。isStreaming 进 key：
    // 轮次进行中不清，流结束（key 翻转）才起算。
    LaunchedEffect(allDone, isStreaming, sessionId) {
        if (!allDone || plans.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(4_000)
        val latest = AgentPlanStore.list(sessionId, context)
        if (latest.isNotEmpty() && latest.all { it.status == "done" }) {
            AgentPlanStore.clear(sessionId, context)
            plans = emptyList()
        }
    }
    // iOS 系统色深浅同值；卡底/边框按 isDark 双值，与过程卡片逐字同源。
    val accent = Color(0xFF007AFF)
    val doneGreen = Color(0xFF34C759)
    val failRed = Color(0xFFFF3B30)
    val idleGray = Color(0xFF8E8E93)
    val cardFill = if (ChatColors.isDark) Color(0xFF1C1C1E) else Color(0xFF007AFF).copy(alpha = 0.06f)
    val cardStroke = if (ChatColors.isDark) Color(0xFF38383A) else Color(0xFF007AFF).copy(alpha = 0.15f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = cardFill,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, cardStroke),
    ) {
        Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (allDone) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = doneGreen,
                            modifier = Modifier.size(14.dp),
                        )
                    } else {
                        // 状态点：蓝=进行中，灰=下一条待做——收起态一眼看到当前任务。
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(if (current?.status == "active") accent else idleGray, CircleShape),
                        )
                    }
                    Text(
                        when {
                            allDone -> stringResource(R.string.plan_board_all_done)
                            current != null -> current.title
                            else -> stringResource(R.string.plan_board_header)
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = ChatColors.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (failed > 0) "$completed/${plans.size} · ${stringResource(R.string.plan_board_failed_n, failed)}"
                        else "$completed/${plans.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed > 0) failRed else accent,
                    )
                }
                // 细进度条（收起/展开都显示）。
                LinearProgressIndicator(
                    progress = { if (plans.isEmpty()) 0f else completed.toFloat() / plans.size },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(CircleShape),
                    color = if (allDone) doneGreen else accent,
                    trackColor = cardStroke,
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
                                        tint = doneGreen,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    "failed" -> Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(failRed, CircleShape),
                                    )
                                    "active" -> Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(accent, CircleShape),
                                    )
                                    else -> Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(idleGray.copy(alpha = 0.35f), CircleShape),
                                    )
                                }
                                Text(
                                    p.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (p.status == "done") ChatColors.secondaryText else ChatColors.primaryText,
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
            // [T-plan-board-toggle] 展开/收起操作点：整宽触控条沉到卡片底部。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(cardStroke),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    // 列表向上展开：收起态箭头朝上（展开方向），展开态朝下（收回）。
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    tint = idleGray,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    if (expanded) stringResource(R.string.plan_board_collapse)
                    else stringResource(R.string.plan_board_expand),
                    style = MaterialTheme.typography.labelMedium,
                    color = idleGray,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/**
 * [T-plan-board-current] 收起态当前任务选择：并行 `active` 里取
 * [AgentPlanStore.Plan.startedAt] 最早者（旧数据 startedAt 全 0 时回退 position 序）；
 * 无 active 取首个 `pending`（= 下一条要做的——前一条完成时收起态自动换到它）；
 * 两者皆无返回 null（调用方落通用标题）。纯函数，JVM 可测。
 */
internal fun pickCurrentStep(plans: List<AgentPlanStore.Plan>): AgentPlanStore.Plan? {
    val active = plans.filter { it.status == "active" }
    if (active.isNotEmpty()) return active.minWithOrNull(compareBy({ it.startedAt }, { it.position }))
    return plans.firstOrNull { it.status == "pending" }
}
