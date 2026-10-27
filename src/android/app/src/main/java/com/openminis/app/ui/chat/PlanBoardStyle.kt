package com.openminis.app.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.tools.AgentPlanStore

/**
 * [T-plan-card-visual-redesign] The agent_plan board segment of
 * [PlanGoalCard]. State, presence and linger stay in PlanGoalCard; this file
 * owns how the board looks and how the toggle is reached.
 *
 * What changed from the [T-android-planboard-restyle] version, and why:
 *
 * - **No dedicated toggle strip.** The board used to spend a 32dp full-width
 *   strip at its bottom on the sheet drag pill + chevron, which made a
 *   one-line summary card as tall as two lines and put the affordance in the
 *   middle of the screen — the worst spot for a thumb. The chevron now rides
 *   the END of the summary row (iOS disclosure position) and the WHOLE row is
 *   the target: a >=44dp iOS-sized hit area that expands and collapses from
 *   the same place. Swipe still works: up expands, down collapses. Nothing is
 *   lost but ~30dp of dead chrome.
 * - **No progress bar.** "3/5" already states progress exactly; a 4dp M3
 *   LinearProgressIndicator under it repeated the number in a heavier visual
 *   weight than the row it described, and read as an underline on the title.
 *   The count span is the progress indicator now.
 * - **One accent, from the theme.** The board wore the chat tool card's
 *   agent_plan identity (Dashboard glyph, hardcoded indigo #5856D6) which
 *   competed with the app accent (#5A8AD2, or the wallpaper-derived primary
 *   under dynamic color). Identity is the checklist glyph; color is
 *   `colorScheme.primary` (green once every step is done), so the card
 *   follows the same accent as the rest of the surface it floats on.
 * - **Bespoke glyphs, one optical weight.** See [PlanBoardGlyphs]: stroke-only
 *   outlines instead of Material filled discs, drawn at a weight that reads
 *   like a line of UI text. The old CheckCircle/Error/RadioButtonUnchecked
 *   trio mixed a solid disc, a heavy circle and a thin ring in one column.
 * - **Row rhythm.** Rows are 30dp with a hairline rule between the summary and
 *   the list, so the expanded card reads as one grouped list instead of text
 *   stacked under a bar.
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
 * The board segment: summary row (identity glyph + "2/5 · current step" +
 * disclosure chevron), the row list when [expanded]. Signature unchanged from
 * the pre-redesign version — the card calls it exactly as before.
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
    val accent = if (allDone) ToolCheckColor else MaterialTheme.colorScheme.primary
    val title: String? = if (allDone) {
        stringResource(R.string.plan_board_all_done)
    } else {
        planBoardCurrentStep(board)?.title
    }
    val toggleLabel = stringResource(R.string.cd_plan_board_toggle)
    // 200ms/standard easing: long enough to read as a direction change,
    // short enough that a second tap never lands mid-rotation.
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "planChevronRotation",
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // iOS minimum row height: the summary line is both the label
                // and the toggle, so it has to be finger-sized.
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(10.dp))
                .pointerInput(expanded) {
                    // Directional swipe, one fire per gesture past 24dp.
                    // Keyed on `expanded` so the closure reads live state.
                    var accumulated = 0f
                    var fired = false
                    val threshold = 24.dp.toPx()
                    detectVerticalDragGestures(
                        onDragStart = {
                            accumulated = 0f
                            fired = false
                        },
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
                .clickable(role = Role.Button, onClickLabel = toggleLabel) { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (allDone) PlanBoardGlyphs.CheckCircle else PlanBoardGlyphs.Checklist,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            // One string from [planBoardSummary] (the testable display source):
            // the count wears the accent at semibold, the title drops to
            // onSurfaceVariant so the eye lands on progress first. Ellipsis
            // only ever eats the title.
            val summary = planBoardSummary(done, total, title)
            val countLength = "$done/$total".length
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = accent)) {
                        append(summary.take(countLength))
                    }
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        append(summary.drop(countLength))
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = PlanBoardGlyphs.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = chevronRotation },
            )
        }
        if (expanded) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                thickness = 1.dp,
                color = planCardHairline,
            )
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                board.forEach { PlanBoardRow(it) }
            }
        }
    }
}

/**
 * Hairline between stacked card segments and between the board summary and its
 * rows: the card-border token, thinned so the outer edge stays the strongest
 * line on the surface. Full-strength outlineVariant inside the card competed
 * with the border and cut the card into boxes.
 */
internal val planCardHairline: Color
    @Composable get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

/** One board item: status glyph + title (strikethrough when done). */
@Composable
private fun PlanBoardRow(plan: AgentPlanStore.Plan) {
    val done = plan.status == "done"
    val active = plan.status == "active"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed 14dp slot so every title starts on the same x no matter which
        // glyph the state draws.
        Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Icon(
                    imageVector = PlanBoardGlyphs.Check,
                    contentDescription = null,
                    tint = ToolCheckColor,
                    modifier = Modifier.size(13.dp),
                )
                active -> CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 1.6.dp,
                )
                plan.status == "failed" -> Icon(
                    imageVector = PlanBoardGlyphs.XCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                else -> Icon(
                    imageVector = PlanBoardGlyphs.Ring,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        // 14dp glyph slot + 12dp = 26dp, the same text origin the summary
        // row and the goal row land on, so the card reads as one column.
        Spacer(Modifier.width(12.dp))
        Text(
            text = plan.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = when {
                active -> FontWeight.SemiBold
                plan.status == "failed" -> FontWeight.Medium
                else -> FontWeight.Normal
            },
            textDecoration = if (done) TextDecoration.LineThrough else null,
            color = if (done) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}