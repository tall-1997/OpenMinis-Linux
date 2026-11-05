package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.tools.AgentReportStore

/**
 * [T-work-report-ui] Shows what the run is waiting for.
 *
 * The stage existed only as data and as prompt text, so a run that had stopped to
 * ask something looked exactly like a run that was still thinking. This renders
 * [WorkStageBannerModel] above the conversation: one line, the reason, nothing to
 * dismiss — it disappears by itself when the stage moves on.
 *
 * Rendering no new strings on purpose: the detail line is the model's own words
 * (the question, the summary or the blocker). A translated label would need 17
 * locales for a word the reason already implies, so the banner carries an icon
 * and the text as written.
 */
@Composable
internal fun WorkStageBanner(sessionId: String, modifier: Modifier = Modifier) {
    // Re-reads whenever the store is written; the report itself is tiny.
    val version by AgentReportStore.version.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val model = remember(sessionId, version) {
        val report = runCatching { AgentReportStore.latest(sessionId, context) }.getOrNull()
        report?.let {
            WorkStageBannerModel.of(
                stage = AgentReportStore.stageOf(it),
                summary = it.summary,
                question = it.question,
                blocker = it.blocker,
            )
        }
    } ?: return

    val container = when (model.kind) {
        WorkStageBannerModel.Kind.ANSWER -> MaterialTheme.colorScheme.primaryContainer
        WorkStageBannerModel.Kind.REVIEW -> MaterialTheme.colorScheme.secondaryContainer
        WorkStageBannerModel.Kind.BLOCKED -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (model.kind) {
        WorkStageBannerModel.Kind.ANSWER -> MaterialTheme.colorScheme.onPrimaryContainer
        WorkStageBannerModel.Kind.REVIEW -> MaterialTheme.colorScheme.onSecondaryContainer
        WorkStageBannerModel.Kind.BLOCKED -> MaterialTheme.colorScheme.onErrorContainer
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Assignment,
            contentDescription = null,
            tint = content,
        )
        Text(
            text = model.detail,
            style = MaterialTheme.typography.bodySmall,
            color = content,
        )
    }
}
