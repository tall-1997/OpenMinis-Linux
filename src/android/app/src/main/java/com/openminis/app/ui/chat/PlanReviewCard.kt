package com.openminis.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.tools.ExitPlanModeTool
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton

/**
 * ChatScreen mount point: the pending-review lookup (state + session filter)
 * lives here so the 7.5k-line screen keeps a one-line call. Only the review
 * parked for THIS session renders — other sessions' reviews stay hidden.
 */
@Composable
internal fun PendingPlanReviewCard(
    sessionId: String,
    onDecision: (ExitPlanModeTool.Decision) -> Unit,
) {
    val pendingPlan by PlanReviewGate.pending.collectAsState()
    pendingPlan[sessionId]?.let { pr ->
        PlanReviewCard(
            review = pr,
            onDecision = onDecision,
        )
    }
}

/**
 * Plan-approval card shown while an exit_plan_mode call is parked. Mirrors
 * [AskUserQuestionsCard]: mounted above the composer, height-capped so the
 * decision row stays pinned, and every decision funnels through
 * [onDecision] -> ChatViewModel.resolvePlanReview — the single place where
 * the mode flag and the plan board are touched.
 */
@Composable
fun PlanReviewCard(
    review: PlanReviewGate.PendingReview,
    onDecision: (ExitPlanModeTool.Decision) -> Unit,
    modifier: Modifier = Modifier,
) {
    val submission = review.submission
    var revising by remember(review) { mutableStateOf(false) }
    var feedback by remember(review) { mutableStateOf("") }
    val feedbackFocus = remember { FocusRequester() }
    LaunchedEffect(revising) {
        if (revising) feedbackFocus.requestFocus()
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.plan_review_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    submission.markdown,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (submission.items.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    submission.items.forEachIndexed { i, item ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(
                                "${i + 1}.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (item.description.isNotBlank()) {
                                    Text(
                                        item.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (revising) {
                OutlinedTextField(
                    value = feedback,
                    onValueChange = { feedback = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(feedbackFocus),
                    placeholder = { Text(stringResource(R.string.plan_review_feedback_hint)) },
                    minLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MinisButton(
                        onClick = {
                            onDecision(ExitPlanModeTool.Decision.Revise(feedback))
                        },
                    ) { Text(stringResource(R.string.plan_review_revise_confirm)) }
                    MinisOutlinedButton(
                        onClick = { revising = false },
                    ) { Text(stringResource(R.string.plan_review_cancel)) }
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MinisButton(
                        onClick = {
                            onDecision(ExitPlanModeTool.Decision.Approved(submission.items))
                        },
                    ) { Text(stringResource(R.string.plan_review_approve)) }
                    MinisOutlinedButton(
                        onClick = { revising = true },
                    ) { Text(stringResource(R.string.plan_review_revise)) }
                    MinisOutlinedButton(
                        onClick = { onDecision(ExitPlanModeTool.Decision.Rejected) },
                    ) { Text(stringResource(R.string.plan_review_reject)) }
                }
            }
        }
    }
}
