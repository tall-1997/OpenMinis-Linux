package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [#25] Context-usage sheet — "what is the next request made of".
 *
 * Mirrors [TokenUsageSheet] in shell and data discipline: standardized chat
 * sheet chrome, pull-on-open refresh (no live subscription — the numbers
 * only change at request boundaries). The breakdown itself is computed by
 * [ChatViewModel.buildContextBreakdown] from the ViewModel's existing
 * assembly/estimation capabilities; this file is presentation only.
 *
 * Layout:
 *   - header: progress bar + Used / Window / Max Output (existing policy
 *     values, not the block sum);
 *   - block list: name + estimated tokens + share of the estimated total;
 *   - footer note: the ±10% rough-estimate caveat.
 */
@Composable
fun ContextUsageSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    // Sheet-local StateFlow per the data-flow convention ("data via
    // StateFlow, refresh once on open"): LaunchedEffect fills it exactly
    // once per composition; collectors stay lifecycle-aware.
    val state = remember { MutableStateFlow<ContextBreakdown?>(null) }
    val breakdown by state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        state.value = runCatching { viewModel.buildContextBreakdown() }.getOrNull()
    }

    StandardChatSheet(
        title = stringResource(R.string.context_usage_sheet_title),
        onDismiss = onDismiss,
        // Same half-screen detent as TokenUsageSheet — both are stat sheets
        // of comparable height.
        heightFraction = 0.5f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val data = breakdown
            if (data == null) {
                // First frames before the IO refresh lands (and the rare
                // failure case): an empty placeholder beats a flash of zeros.
                Box(Modifier.fillMaxWidth().height(120.dp))
            } else {
                ContextUsageSummary(data)
                ContextBlockList(data)
                Text(
                    text = stringResource(R.string.context_usage_note_estimate),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Progress bar + the three policy totals (used / window / max output). */
@Composable
private fun ContextUsageSummary(data: ContextBreakdown) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Fill ratio prefers the REAL reported reading (contextTokensForPolicy)
        // and falls back to the block-sum estimate before the first usage chunk.
        val used = if (data.usedTokens > 0) data.usedTokens else data.totalTokens
        val ratio = if (data.windowTokens > 0) {
            (used.toFloat() / data.windowTokens).coerceIn(0f, 1f)
        } else {
            0f
        }
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.fillMaxWidth(),
        )
        StatRow(
            label = stringResource(R.string.token_usage_context_used),
            value = formatTokenCount(used.toLong(), uppercaseSuffix = true) +
                if (data.windowTokens > 0) {
                    " / " + formatTokenCount(data.windowTokens.toLong(), uppercaseSuffix = true) +
                        " (${(ratio * 100).toInt()}%)"
                } else {
                    ""
                },
        )
        if (data.maxOutputTokens > 0) {
            StatRow(
                label = stringResource(R.string.token_usage_max_output),
                value = formatTokenCount(data.maxOutputTokens.toLong(), uppercaseSuffix = true),
            )
        }
    }
}

/** One row per context block, largest-first so the dominant cost is on top. */
@Composable
private fun ContextBlockList(data: ContextBreakdown) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        data.blocks
            .sortedByDescending { it.tokens }
            .forEach { block ->
                ContextBlockRow(
                    label = stringResource(block.key.labelRes()),
                    tokens = block.tokens,
                    share = block.share,
                )
            }
    }
}

@Composable
private fun ContextBlockRow(label: String, tokens: Int, share: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "${(share * 100).toInt()}%",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = formatTokenCount(tokens.toLong(), uppercaseSuffix = true),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Localized name per semantic block (UI mapping only — the core stays resource-free). */
private fun ContextBlockKey.labelRes(): Int = when (this) {
    ContextBlockKey.SYSTEM_TOOLS -> R.string.context_block_system
    ContextBlockKey.SKILLS_MCPS -> R.string.context_block_skills_mcps
    ContextBlockKey.MEMORY -> R.string.context_block_memory
    ContextBlockKey.RUNTIME_TAIL -> R.string.context_block_runtime
    ContextBlockKey.GOAL_PLAN -> R.string.context_block_goal_plan
    ContextBlockKey.HISTORY -> R.string.context_block_history
    ContextBlockKey.PENDING_ATTACHMENTS -> R.string.context_block_attachments
}

/**
 * Process-level visibility flag for the context-usage sheet. ChatScreen.kt
 * and ChatViewModel.kt sit exactly at their architecture-policy ceilings, so
 * neither can take a new MutableState param / StateFlow member without
 * shrinking first. A tiny process-level holder (same precedent as
 * ContextSaverRuntime.stats, collected by TokenUsageSheet) lets the menu
 * entry in ChatTopBarSection and the host in ChatSheetsHost talk without
 * touching either pinned file.
 */
internal object ContextUsageSheetState {
    private val _visible = MutableStateFlow(false)

    /** Collect in ChatSheetsHost via collectAsStateWithLifecycle. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun show() {
        _visible.value = true
    }

    fun hide() {
        _visible.value = false
    }
}
