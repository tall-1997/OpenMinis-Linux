package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

/** Green all-done state holds this long before the bar dismisses itself. */
private const val DONE_LINGER_MS = 4_000L

/** An emptied board holds this long so the exit reads as intentional. */
private const val EMPTY_LINGER_MS = 600L

private const val ENTER_ANIM_MS = 200

private const val EXIT_ANIM_MS = 250

/** Margin after the exit tween before the rendered snapshot is dropped. */
private const val EXIT_SETTLE_MS = 400L

/**
 * [T-plan-board-ui] Live todo-board bar mounted above the composer. The
 * agent_plan tool keeps a session-scoped item list in [AgentPlanStore]; this
 * bar is its projection so the user sees plan progress without scrolling
 * the transcript.
 *
 * Design notes:
 * - Rows carry status glyph + title only. The tool's description/trace text
 *   stays out of the UI — the step itself is the information; run detail
 *   belongs to the transcript.
 * - Self-dismissing: an emptied board lingers briefly and eases out; a
 *   fully-done board lingers in its green state, then clears the store
 *   itself so the bar AND the prompt fragment both drop to zero. Items
 *   arriving during a linger cancel the dismissal (the effect restarts).
 * - Renders NOTHING when idle — zero footprint for chats that never use
 *   the todo tool. A corrupt board file also degrades to hidden, not a
 *   crashed composer (the store throws by design; the tool surfaces it).
 * - Collapsed: the current step (first active, else first pending), a
 * done/total counter, and a 4dp progress track. Expanded: the full step
 * list with per-status glyphs. All-done: iOS-green accent.
 * - Reloads are driven by [AgentPlanStore.version], the same signal the
 *   store bumps on every mutation (memory-only boards included), so status
 *   flips appear on the very next frame after a tool call.
 * - Visual idiom: Surface + 16dp corner + surfaceContainer fill +
 *   outlineVariant hairline — the app's card language.
 */
@Composable
internal fun StickyPlanBar(sessionId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version by AgentPlanStore.version.collectAsState()
    val plans = remember(version, sessionId) {
        runCatching { AgentPlanStore.list(sessionId, context) }.getOrDefault(emptyList())
    }

    // Rendered snapshot: the last non-empty board. Held across the dismissal
    // delays so the exit animation fades real content, not a blank shell —
    // dropped only after the exit tween has settled.
    var display by remember(sessionId) { mutableStateOf<List<AgentPlanStore.Plan>>(emptyList()) }
    var visible by remember(sessionId) { mutableStateOf(false) }
    var expanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    // Presence driver. `visible` is read at launch, not keyed: it only ever
    // changes inside this effect, so the launch-time value is authoritative.
    LaunchedEffect(plans) {
        if (plans.isNotEmpty()) {
            if (!visible) expanded = false
            display = plans
            visible = true
        } else if (visible) {
            delay(EMPTY_LINGER_MS)
            visible = false
            delay(EXIT_SETTLE_MS)
            display = emptyList()
        }
    }

    val board = display
    val done = board.count { it.status == "done" }
    val total = board.size
    val allDone = total > 0 && done == total

    // Completion driver: after the green linger, clear the board. That
    // empties the store, which routes through the presence driver above for
    // the final exit — by the time the bar is gone the list is empty too.
    LaunchedEffect(allDone, sessionId) {
        if (allDone) {
            delay(DONE_LINGER_MS)
            AgentPlanStore.clear(sessionId, context)
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(ENTER_ANIM_MS)) + expandVertically(tween(ENTER_ANIM_MS)),
        exit = fadeOut(tween(EXIT_ANIM_MS)) + shrinkVertically(tween(EXIT_ANIM_MS)),
    ) {
        if (board.isNotEmpty()) {
            PlanBoardSurface(
                board = board,
                done = done,
                total = total,
                allDone = allDone,
                expanded = expanded,
                onToggle = {
                    expanded = !expanded
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
        }
    }
}

@Composable
private fun PlanBoardSurface(
    board: List<AgentPlanStore.Plan>,
    done: Int,
    total: Int,
    allDone: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val current = board.firstOrNull { it.status == "active" }
        ?: board.firstOrNull { it.status != "done" }
    val headerTitle = if (allDone) {
        stringResource(R.string.plan_board_all_done)
    } else {
        current?.title.orEmpty()
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp).animateContentSize(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.cd_plan_board_toggle),
                ) { onToggle() },
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
                    color = if (allDone) ToolCheckColor else MaterialTheme.colorScheme.primary,
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
                color = if (allDone) ToolCheckColor else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                ) {
                    board.forEach { PlanBoardRow(it) }
                }
            }
        }
    }
}

/** One board item: status glyph + title (strikethrough when done). */
@Composable
private fun PlanBoardRow(plan: AgentPlanStore.Plan) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (plan.status) {
            "done" -> Icon(
                imageVector = m3Icon(Icons.Default.CheckCircle),
                contentDescription = null,
                tint = ToolCheckColor,
                modifier = Modifier.size(16.dp),
            )
            "active" -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            "failed" -> Icon(
                imageVector = m3Icon(Icons.Default.Error),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            else -> Icon(
                imageVector = Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
