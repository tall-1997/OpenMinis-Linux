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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import com.openminis.app.goal.GoalManager
import com.openminis.app.goal.GoalSheetSignal
import com.openminis.app.tools.AgentPlanStore
import kotlinx.coroutines.delay

/**
 * Green all-done state holds this long before the bar dismisses itself. The
 * store retires a finished board after the same window (its own constant, not
 * a copy), so a run that finishes with no card mounted still cleans up.
 */
private val DONE_LINGER_MS = AgentPlanStore.DONE_LINGER_MS

/** An emptied board holds this long so the exit reads as intentional. */
private const val EMPTY_LINGER_MS = 600L

private const val ENTER_ANIM_MS = 200

private const val EXIT_ANIM_MS = 250

/** Margin after the exit tween before the rendered snapshot is dropped. */
private const val EXIT_SETTLE_MS = 400L

/** Identifies the goal whose header already exited — "id@updatedAt". */
private fun goalKey(goal: SessionGoalEntity): String = "${goal.sessionId}@${goal.updatedAt}"

/** Compact token count: 1234 → "1.2k", 1234567 → "1.2M". Shared with [GoalSheet]. */
internal fun formatGoalTokens(value: Long): String = when {
    value >= 1_000_000L -> trimZero(value / 100_000.0) + "M"
    value >= 1_000L -> trimZero(value / 100.0) + "k"
    else -> value.toString()
}

private fun trimZero(scaled: Double): String =
    (if (scaled >= 100.0) scaled.toInt().toString() else "%.1f".format(scaled)).removeSuffix(".0")

/**
 * [T-plan-board-ui] [T-goal-card] Live card above the composer, one surface
 * with two independent segments:
 *
 * - **Goal header row** — the session's [GoalManager] row (status glyph,
 *   objective, token badge). Tapping it opens the Goal sheet. A completed
 *   goal holds its green state for [DONE_LINGER_MS], then the row eases out
 *   WITHOUT deleting the DB row: the record stays readable from the sheet
 *   and /goal show, while [com.openminis.app.goal.goalMount] only mounts
 *   ACTIVE goals into requests, so a completed row never steers turns. The
 *   exited row is pinned by its `id@updatedAt` key and does not come back
 *   on unrelated version bumps; any new or changed goal row shows again.
 * - **Plan board** — the agent_plan todo list, restyled in
 *   [T-plan-card-visual-redesign] onto the app's own language
 *   (PlanBoardStyle.kt, PlanBoardIcons.kt): it boots COLLAPSED to a one-line
 *   summary ("2/5 · current step") whose trailing chevron — and the whole
 *   >=44dp row — toggles the list (swipe up/down still works), so the board
 *   costs one text line when idle instead of a summary line plus a progress
 *   bar plus a full-width toggle strip. Rows carry status glyph + title only;
 *   an emptied board lingers and eases out, a fully-done board holds green
 *   then clears the store itself.
 *
 * Both segments share one card shell (Surface + 16dp corner +
 * surfaceContainer fill + outlineVariant hairline — the app's card
 * language) separated by a thinned hairline ([planCardHairline]) when both
 * are present, so the outer border stays the strongest line on the surface.
 * Renders NOTHING when idle; a corrupt board file or a failed goal read
 * degrade to a hidden segment, not a crashed composer. Reloads are driven by
 * [AgentPlanStore.version] and [GoalManager.version] — the same signals the
 * writers bump — so status flips appear on the very next frame.
 */
@Composable
internal fun PlanGoalCard(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sid = viewModel.activeSessionId
    val planVersion by AgentPlanStore.version.collectAsStateWithLifecycle()
    val goalVersion by GoalManager.version.collectAsStateWithLifecycle()
    val plans = remember(planVersion, sid) {
        runCatching { AgentPlanStore.list(sid, context) }.getOrDefault(emptyList())
    }
    var goal by remember(sid) { mutableStateOf<SessionGoalEntity?>(null) }
    LaunchedEffect(goalVersion, sid) {
        goal = viewModel.currentGoal()
    }

    // ── board segment: rendered snapshot + presence, #134 semantics verbatim ──
    var boardDisplay by remember(sid) { mutableStateOf<List<AgentPlanStore.Plan>>(emptyList()) }
    var boardVisible by remember(sid) { mutableStateOf(false) }
    var expanded by rememberSaveable(sid) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    // Presence driver. `boardVisible` is read at launch, not keyed: it only
    // ever changes inside this effect, so the launch-time value is authoritative.
    LaunchedEffect(plans) {
        if (plans.isNotEmpty()) {
            if (!boardVisible) expanded = false
            boardDisplay = plans
            boardVisible = true
        } else if (boardVisible) {
            delay(EMPTY_LINGER_MS)
            boardVisible = false
            delay(EXIT_SETTLE_MS)
            boardDisplay = emptyList()
        }
    }

    val board = boardDisplay
    val done = board.count { it.status == "done" }
    val total = board.size
    val allDone = total > 0 && done == total

    // Completion driver: after the green linger, clear the board. That
    // empties the store, which routes through the presence driver above for
    // the final exit — by the time the bar is gone the list is empty too.
    LaunchedEffect(allDone, sid) {
        if (allDone) {
            delay(DONE_LINGER_MS)
            AgentPlanStore.clear(sid, context)
        }
    }

    // ── goal segment: same two-driver shape, mirrored for the header row ──
    var goalDisplay by remember(sid) { mutableStateOf<SessionGoalEntity?>(null) }
    var headerVisible by remember(sid) { mutableStateOf(false) }
    var dismissedGoalKey by remember(sid) { mutableStateOf<String?>(null) }

    // Presence driver: a live (not-yet-dismissed) row shows the header; a
    // vanished or dismissed row exits through the same linger path as the
    // board. Keying on dismissedGoalKey re-runs this after a completion
    // dismissal so the snapshot is cleaned up.
    LaunchedEffect(goal, sid, dismissedGoalKey) {
        // Snapshot: `goal` is a delegated property, so the smart cast the
        // assignment needs must come from a local val.
        val snapshot = goal
        if (snapshot != null && goalKey(snapshot) != dismissedGoalKey) {
            goalDisplay = snapshot
            headerVisible = true
        } else if (headerVisible) {
            delay(EMPTY_LINGER_MS)
            headerVisible = false
            delay(EXIT_SETTLE_MS)
            goalDisplay = null
        } else {
            goalDisplay = null
        }
    }

    // Completion driver: the green linger, then pin the row as dismissed.
    // The DB row stays (record + sheet visibility); only the header exits.
    val goalComplete = goalDisplay?.status == GoalStatus.COMPLETE
    LaunchedEffect(goalComplete, sid) {
        if (goalComplete) {
            delay(DONE_LINGER_MS)
            goalDisplay?.let { dismissedGoalKey = goalKey(it) }
        }
    }

    val cardVisible = headerVisible || boardVisible
    AnimatedVisibility(
        visible = cardVisible,
        modifier = modifier,
        enter = fadeIn(tween(ENTER_ANIM_MS)) + expandVertically(tween(ENTER_ANIM_MS)),
        exit = fadeOut(tween(EXIT_ANIM_MS)) + shrinkVertically(tween(EXIT_ANIM_MS)),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            // 2dp vertical: the rows already carry the iOS 44dp touch height,
            // so card padding on top of it was pure height. The card now reads
            // as an inset-grouped table — rows run to the card's inner edge.
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 2.dp).animateContentSize(),
            ) {
                AnimatedVisibility(
                    visible = headerVisible,
                    enter = fadeIn(tween(ENTER_ANIM_MS)) + expandVertically(tween(ENTER_ANIM_MS)),
                    exit = fadeOut(tween(EXIT_ANIM_MS)) + shrinkVertically(tween(EXIT_ANIM_MS)),
                ) {
                    goalDisplay?.let { goal ->
                        GoalHeaderRow(
                            goal = goal,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                GoalSheetSignal.open()
                            },
                        )
                    }
                }
                if (headerVisible && boardVisible) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = planCardHairline,
                    )
                }
                AnimatedVisibility(
                    visible = boardVisible,
                    enter = fadeIn(tween(ENTER_ANIM_MS)) + expandVertically(tween(ENTER_ANIM_MS)),
                    exit = fadeOut(tween(EXIT_ANIM_MS)) + shrinkVertically(tween(EXIT_ANIM_MS)),
                ) {
                    if (board.isNotEmpty()) {
                        PlanBoardBody(
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
        }
    }
}

/** The goal segment: status glyph + objective + token badge, opens the sheet. */
@Composable
private fun GoalHeaderRow(goal: SessionGoalEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.cd_goal_header),
            ) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val statusDescription = when (goal.status) {
            GoalStatus.ACTIVE -> stringResource(R.string.cd_goal_status_active)
            GoalStatus.PAUSED -> stringResource(R.string.cd_goal_status_paused)
            GoalStatus.COMPLETE -> stringResource(R.string.cd_goal_status_complete)
            GoalStatus.BLOCKED -> stringResource(R.string.cd_goal_status_blocked)
            GoalStatus.BUDGET_LIMITED -> stringResource(R.string.cd_goal_status_budget)
            else -> stringResource(R.string.cd_goal_status_active)
        }
        when (goal.status) {
            GoalStatus.ACTIVE -> CircularProgressIndicator(
                modifier = Modifier.size(17.dp),
                strokeWidth = 1.8.dp,
            )
            GoalStatus.COMPLETE -> Icon(
                imageVector = PlanBoardGlyphs.CheckCircle,
                contentDescription = statusDescription,
                tint = ToolCheckColor,
                modifier = Modifier.size(17.dp),
            )
            GoalStatus.PAUSED -> Icon(
                imageVector = PlanBoardGlyphs.Pause,
                contentDescription = statusDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            GoalStatus.BLOCKED -> Icon(
                imageVector = PlanBoardGlyphs.XCircle,
                contentDescription = statusDescription,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(17.dp),
            )
            GoalStatus.BUDGET_LIMITED -> Icon(
                imageVector = PlanBoardGlyphs.AlertCircle,
                contentDescription = statusDescription,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = goal.objective,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        val badge = goal.tokenBudget?.let { "${formatGoalTokens(goal.tokensUsed)}/${formatGoalTokens(it)}" }
            ?: formatGoalTokens(goal.tokensUsed)
        Text(
            text = badge,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = when (goal.status) {
                GoalStatus.COMPLETE -> ToolCheckColor
                GoalStatus.BLOCKED, GoalStatus.BUDGET_LIMITED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
        )
    }
}

// [T-plan-card-visual-redesign] PlanBoardBody / PlanBoardRow and the
// collapsed-summary visuals moved verbatim-plus-restyle to PlanBoardStyle.kt
// and PlanBoardIcons.kt; this file keeps the state, presence and linger
// drivers (unchanged) and the goal header segment.
