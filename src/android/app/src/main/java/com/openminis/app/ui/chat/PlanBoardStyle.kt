package com.openminis.app.ui.chat

import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.tools.AgentPlanStore
import com.openminis.app.ui.components.MinisSheetDragPill
import com.openminis.app.ui.theme.m3Icon

/**
 * [T-android-planboard-restyle] The agent_plan board segment of
 * [PlanGoalCard], extracted from it so the card file keeps shrinking and the
 * board's look lives beside the style sources it mirrors. A restyle, not a
 * redesign — state, presence and linger stay in PlanGoalCard:
 *
 * - **Tool identity.** The summary row wears the SAME icon + accent the chat
 *   flow gives the agent_plan tool — [toolIconFor] / [toolAccentColor], the
 *   Dashboard glyph in indigo — instead of the generic check circle it used
 *   to borrow from the done state, so the board reads as that tool's surface.
 *   Every glyph goes through [m3Icon] so the filled/outlined theme switch
 *   reaches the board too (the pending circle previously bypassed it).
 * - **One-hand toggle.** The board boots collapsed to a one-line summary
 *   ("2/5 · current step", [planBoardSummary]) and the always-visible
 *   affordance sits at the BOTTOM center: the shared 32x4dp
 *   [MinisSheetDragPill] with a direction chevron — the same handle language
 *   as every sheet in the app, reachable one-handed above the composer. Tap
 *   toggles; the growth animates through the card shell's animateContentSize
 *   (rows are few, no virtualization needed).
 * - **Colors.** Tokens or ChatToolFormatting accents only, zero new literals:
 *   active accent = agent_plan indigo, done = the shared green
 *   ([ToolCheckColor]), failed = error token, pending = onSurfaceVariant.
 */

/** The step the summary names: the active one, else the first unfinished. */
internal fun planBoardCurrentStep(board: List<AgentPlanStore.Plan>): AgentPlanStore.Plan? =
    board.firstOrNull { it.status == "active" }
        ?: board.firstOrNull { it.status != "done" }

/**
 * Collapsed-bar summary text: count leads ("2/5"), the current step follows
 * after a middle dot; the count alone when there is no title to show.
 */
internal fun planBoardSummary(done: Int, total: Int, title: String?): String {
    val count = "$done/$total"
    return if (title.isNullOrBlank()) count else "$count · $title"
}

/**
 * The board segment: summary row + hairline progress bar, the full row list
 * when [expanded], and the bottom-center toggle control in BOTH states.
 * Signature (and the [AgentPlanStore.Plan] model) unchanged from the
 * pre-restyle PlanGoalCard version — the card calls it exactly as before.
 */
@Composable
internal fun PlanBoardBody(
    board: List<AgentPlanStore.Plan>,
    done: Int,
    total: Int,
    allDone: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val accent = if (allDone) ToolCheckColor else toolAccentColor("agent_plan")
    val title: String? = if (allDone) {
        stringResource(R.string.plan_board_all_done)
    } else {
        planBoardCurrentStep(board)?.title
    }
    val toggleLabel = stringResource(R.string.cd_plan_board_toggle)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(
                role = Role.Button,
                onClickLabel = toggleLabel,
            ) { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = m3Icon(toolIconFor("agent_plan")),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            // One summary string from [planBoardSummary] (the testable display
            // source); the leading count span wears the accent, the rest of
            // the line stays onSurface. Ellipsis only ever eats the title.
            val summary = planBoardSummary(done, total, title)
            val onSurface = MaterialTheme.colorScheme.onSurface
            val countLength = "$done/$total".length
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = accent)) {
                        append(summary.take(countLength))
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = onSurface)) {
                        append(summary.drop(countLength))
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { done.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = accent,
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
        PlanBoardToggleControl(
            expanded = expanded,
            onClickLabel = toggleLabel,
            onToggle = onToggle,
        )
    }
}

/**
 * The one-hand toggle: the app's 32x4dp sheet drag pill with a direction
 * chevron, centered on a full-width >=32dp tap strip at the BOTTOM of the
 * board. Present in both states so the very same spot expands and collapses.
 * Tap toggles; a vertical swipe also works directionally — up expands,
 * down collapses (the swipe-to-expand gesture) — and a drag is consumed here so
 * it never leaks into a stray click.
 */
@Composable
private fun PlanBoardToggleControl(
    expanded: Boolean,
    onClickLabel: String,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(expanded) {
                // Directional swipe: fire once per gesture past a 24dp
                // threshold. Keyed on `expanded` so the closure reads the
                // live state instead of a stale capture.
                var accumulated = 0f
                var fired = false
                val threshold = 24.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { accumulated = 0f; fired = false },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (!fired) {
                            accumulated += dragAmount
                            if (accumulated < -threshold && !expanded) {
                                onToggle()
                                fired = true
                            } else if (accumulated > threshold && expanded) {
                                onToggle()
                                fired = true
                            }
                        }
                    },
                )
            }
            .clickable(role = Role.Button, onClickLabel = onClickLabel) { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // M3-token tint, matching GoalSheet's drag handle choice for
            // M3-styled surfaces (onSurfaceVariant at the shared 40% alpha).
            MinisSheetDragPill(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
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
                modifier = Modifier.size(14.dp),
            )
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
                imageVector = m3Icon(Icons.Default.RadioButtonUnchecked),
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
