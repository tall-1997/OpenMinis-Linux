package com.openminis.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.tools.AgentPlanStore
import com.openminis.app.ui.theme.m3Icon

/**
 * [T-plan-board-ui] Live todo-board bar mounted above the composer. The
 * agent_plan tool keeps a session-scoped item list in [AgentPlanStore]; this
 * bar is its always-visible projection so the user sees plan progress without
 * scrolling the transcript.
 *
 * Design notes:
 * - Renders NOTHING on an empty board — zero footprint for chats that never
 *   use the todo tool. A corrupt board file also degrades to hidden, not a
 *   crashed composer (the store throws by design; the tool path surfaces it).
 * - Collapsed: the current step (first active, else first pending), a done/
 *   total counter, and a 4dp progress track. Expanded: the full step list
 *   with per-status glyphs. All-done: green check + completion string.
 * - Reloads are driven by [AgentPlanStore.version], the same signal the
 *   store bumps on every mutation (memory-only boards included), so status
 *   flips appear on the very next frame after a tool call.
 * - Visual idiom follows the former PlanReviewCard: Surface + 16dp corner +
 *   surfaceContainer fill + outlineVariant hairline — the app's card language.
 */
@Composable
internal fun StickyPlanBar(sessionId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version by AgentPlanStore.version.collectAsState()
    val plans = remember(version, sessionId) {
        runCatching { AgentPlanStore.list(sessionId, context) }.getOrDefault(emptyList())
    }
    if (plans.isEmpty()) return

    var expanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val done = plans.count { it.status == "done" }
    val total = plans.size
    val allDone = done == total
    val current = plans.firstOrNull { it.status == "active" }
        ?: plans.firstOrNull { it.status != "done" }
    val headerTitle = if (allDone) {
        stringResource(R.string.plan_board_all_done)
    } else {
        current?.title.orEmpty()
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp).animateContentSize(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.cd_plan_board_toggle),
                ) {
                    expanded = !expanded
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = m3Icon(Icons.Default.CheckCircle),
                    contentDescription = null,
                    tint = if (allDone) ToolCheckColor else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$done/$total",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) {
                        m3Icon(Icons.Default.KeyboardArrowUp)
                    } else {
                        m3Icon(Icons.Default.KeyboardArrowDown)
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { done.toFloat() / total },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                ) {
                    plans.forEach { PlanBoardRow(it) }
                }
            }
        }
    }
}

/** One board item: status glyph + title (strikethrough when done) + note. */
@Composable
private fun PlanBoardRow(plan: AgentPlanStore.Plan) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        when (plan.status) {
            "done" -> Icon(
                imageVector = m3Icon(Icons.Default.CheckCircle),
                contentDescription = null,
                tint = ToolCheckColor,
                modifier = Modifier.size(18.dp),
            )
            "active" -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            "failed" -> Icon(
                imageVector = m3Icon(Icons.Default.Error),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            else -> Icon(
                imageVector = Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = plan.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (plan.status == "active") FontWeight.SemiBold else FontWeight.Medium,
                textDecoration = if (plan.status == "done") TextDecoration.LineThrough else null,
                color = if (plan.status == "done") {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (plan.description.isNotBlank()) {
                Text(
                    text = plan.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
