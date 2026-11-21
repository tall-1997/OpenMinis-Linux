package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.ContextSaverRuntime
import com.openminis.app.data.repository.MemoryUsageLedger
import com.openminis.app.i18n.uppercaseForDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Session Token Usage bottom sheet — mirrors iOS `TokenUsageSheet` and uses
 * the standardized chat sheet shell so its header/dismiss behavior matches
 * every other "⋯" menu sheet.
 *
 * Sections (top to bottom):
 *   - Context: progress bar + Context Used / Context Window / Max Output
 *     + per-block breakdown (what the next request is made of, [#25] merged
 *     here from the former standalone ContextUsageSheet — one entry point,
 *     one sheet, no duplicated header stats)
 *   - Thinking (only when the model supports reasoning): On/Off / Level / Supported
 *   - Tokens (Session Total): Input (incl. cache) / Output
 *   - Cache (Session Total): Cache Read / Cache Write
 *   - Agent Loop: Total Loops
 *   - Tool calls (only when the loaded window carries tool_use blocks):
 *     one "name × N" row per tool, busiest first, top 5 + folded remainder
 *     (the UsageLedger tools bucket, [T-pi-tools-bucket])
 *
 * Data loads asynchronously via [ChatViewModel.loadSessionTokenStats] when the
 * sheet appears; we intentionally don't hold a live subscription — token
 * counters change per API call, not per keystroke, so pull-on-open is enough.
 * The context breakdown ([ChatViewModel.buildContextBreakdown]) is a suspend
 * IO call, so it fills in after the first frame; the block list simply stays
 * hidden until it lands rather than flashing zeros.
 */
@Composable
fun TokenUsageSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val session = viewModel.activeSessionId
    var stats by remember(viewModel, session) { mutableStateOf<ChatViewModel.SessionTokenStats?>(null) }
    val modelEntry by viewModel.activeEntryId.collectAsStateWithLifecycle()
    val modelGroup by viewModel.selectedGroupId.collectAsStateWithLifecycle()
    val summary by viewModel.compactSummary.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val memoryEnabled by viewModel.memoryEnabled.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val markerId = viewModel._cachedLatestMarker?.id
    val thinking = remember(viewModel, modelEntry) { viewModel.thinkingInfo() }
    // [T-cache-hit-rate] 最近一轮的缓存命中率（0..1，无真实 usage 时为 null）。
    val lastCacheHitRate by viewModel.lastCacheHitRate.collectAsStateWithLifecycle()
    // [T-tool-result-aging] Latest fold stats from the send path.
    val saver by ContextSaverRuntime.stats.collectAsStateWithLifecycle()
    // [T-token-sheet-recompute] This projection rebuilds the whole request
    // (prompt assembly + a token scan of the entire history). Keying it on the
    // message LIST made every streaming UI batch create a new container and
    // restart the effect, so watching a long reply stream re-ran that full
    // projection ~10 times a second and starved the frame loop. The request
    // only changes when the transcript gains a row: key on its edge, plus the
    // route/marker/memory inputs that genuinely move it.
    val transcriptEdge = messages.size to (messages.lastOrNull()?.id ?: "")
    val breakdownState = remember(
        viewModel, session, modelEntry, modelGroup, summary, markerId, transcriptEdge, streaming,
        memoryEnabled, attachments,
    ) {
        mutableStateOf<ContextBreakdown?>(null)
    }
    val breakdown by breakdownState

    var memoryUsage by remember(viewModel, session) { mutableStateOf(0 to 0) }
    val filesDir = viewModel.context.filesDir
    LaunchedEffect(viewModel, session, streaming) {
        memoryUsage = withContext(Dispatchers.IO) {
            memoryUsageSessionDirectory(filesDir, session)?.let(MemoryUsageLedger::verifiedSummaryFor) ?: (0 to 0)
        }
    }
    LaunchedEffect(viewModel, session) { stats = viewModel.loadSessionTokenStats() }
    LaunchedEffect(breakdownState) {
        try {
            breakdownState.value = viewModel.buildContextBreakdown()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Leave the projected counts unknown; historical usage is a separate ledger.
        }
    }

    StandardChatSheet(
        title = stringResource(R.string.token_usage_sheet_title),
        onDismiss = onDismiss,
        // T148: iOS uses .presentationDetents([.medium]) for the same sheet
        // (AIChatView.swift:508). Match that proportion on Android so the
        // half-screen feel is consistent — the token-usage view holds maybe
        // a screenful of stat rows max and looked overgrown at 90%.
        heightFraction = 0.5f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val s = stats
            val onText = stringResource(R.string.common_on)
            val offText = stringResource(R.string.common_off)
            val yesText = stringResource(R.string.common_yes)
            val noText = stringResource(R.string.common_no)
            StatSection(title = stringResource(R.string.token_usage_section_context)) {
                val projectedUsage = breakdown?.contextUsage
                LinearProgressIndicator(
                    progress = { projectedUsage?.ratio ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                StatRow(
                    stringResource(R.string.token_usage_context_used),
                    projectedUsage?.usedTokens?.let(::formatTokens) ?: "?",
                )
                if (projectedUsage?.known == true) {
                    StatRow(stringResource(R.string.token_usage_context_window), formatTokens(projectedUsage.windowTokens))
                }
                StatRow(
                    stringResource(R.string.token_usage_context_percent),
                    if (projectedUsage?.known == true) "${(projectedUsage.ratio * 100).toInt()}%" else "?",
                )
                breakdown?.let { snapshot ->
                    if (snapshot.windowTokens > 0 || snapshot.maxOutputTokens > 0) {
                        StatRow(stringResource(R.string.token_usage_max_output), formatTokens(snapshot.maxOutputTokens))
                    }
                }
                // [#25] Per-block composition of the next request — the
                // unique payload of the former ContextUsageSheet, largest
                // block first so the dominant cost sits on top.
                breakdown?.let { b ->
                    if (b.blocks.isNotEmpty()) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                        ContextBlockList(b)
                        Text(
                            text = stringResource(R.string.context_usage_note_estimate),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            thinking?.let { t ->
                StatSection(title = stringResource(R.string.token_usage_section_thinking)) {
                    StatRow(stringResource(R.string.token_usage_thinking_label), if (t.enabled) onText else offText)
                    if (t.enabled) StatRow(stringResource(R.string.token_usage_thinking_level), t.level)
                    // [T-thinking-dual-ledger] The session's most recent turn:
                    // what the user requested vs what the response evidenced.
                    // "—" = unknown (pre-ledger rows, no thinking streamed, or
                    // the wire tier could not be named honestly).
                    val lastRequested = s?.thinkingLevelRequested
                    if (s != null && lastRequested != null) {
                        val requestedLevel = com.openminis.app.data.model.ThinkingLevel.decoded(lastRequested)
                        StatRow(
                            stringResource(R.string.token_usage_thinking_requested),
                            requestedLevel.localizedName(LocalContext.current),
                        )
                        StatRow(
                            stringResource(R.string.token_usage_thinking_effective),
                            // Same localized format as the requested row when
                            // the wire tier decodes to a session level; raw
                            // wire value otherwise (it is the provider's own
                            // naming and must not be invented into a level).
                            s.thinkingLevelEffective?.let { raw ->
                                com.openminis.app.data.model.ThinkingLevel.decoded(raw)
                                    ?.localizedName(LocalContext.current) ?: raw
                            } ?: "—",
                        )
                    }
                    StatRow(stringResource(R.string.token_usage_thinking_supported), if (t.supported) yesText else noText)
                }
            }

            StatSection(title = stringResource(R.string.token_usage_section_tokens)) {
                val inputTotal = (s?.input ?: 0L) + (s?.cacheRead ?: 0L) + (s?.cacheWrite ?: 0L)
                StatRow(stringResource(R.string.token_usage_input_with_cache), formatTokens(inputTotal))
                StatRow(stringResource(R.string.token_usage_output), formatTokens(s?.output ?: 0L))
            }

            StatSection(title = stringResource(R.string.token_usage_section_cache)) {
                StatRow(stringResource(R.string.token_usage_cache_read), formatTokens(s?.cacheRead ?: 0L))
                StatRow(stringResource(R.string.token_usage_cache_write), formatTokens(s?.cacheWrite ?: 0L))
                // [T-cache-hit-rate] 最近一轮缓存命中率（session 总数外的实时维度）。
                if (s != null && (s.cacheRead > 0 || s.input > 0)) {
                    StatRow(
                        stringResource(R.string.token_usage_cache_hit_rate),
                        if (lastCacheHitRate > 0.0) {
                            "${(lastCacheHitRate * 100).toInt()}%"
                        } else {
                            "—"
                        },
                    )
                }
            }

            StatSection(title = stringResource(R.string.token_usage_section_agent_loop)) {
                StatRow(stringResource(R.string.token_usage_total_loops), (s?.loopCount ?: 0).toString())
            }
            // [T-pi-tools-bucket] UsageLedger tools bucket: one row per tool the loaded
            // window carries, busiest first ("name × N"). Bounded — top rows plus a folded
            // remainder row for MCP-heavy sessions. Hidden while the map is empty: empty
            // means "no tool_use in memory", never a fabricated zero.
            val toolCounts = s?.toolCallCounts.orEmpty()
            if (toolCounts.isNotEmpty()) {
                StatSection(title = stringResource(R.string.token_usage_section_tool_calls)) {
                    val rows = toolCounts.entries.toList()
                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    rows.take(TOOL_ROWS_SHOWN).forEach { (name, calls) ->
                        StatRow(name, "× $calls")
                    }
                    if (rows.size > TOOL_ROWS_SHOWN) {
                        val remaining = rows.drop(TOOL_ROWS_SHOWN)
                        StatRow(
                            stringResource(
                                R.string.token_usage_tools_more,
                                remaining.size,
                            ),
                            "× ${remaining.sumOf { it.value }}",
                        )
                    }
                }
            }
            // [T-tool-result-aging] What the history-side saver actually did on
            // the most recent request. Zero rows read "—" instead of 0 so an
            // untouched short session looks untouched rather than broken.
            StatSection(title = stringResource(R.string.token_usage_section_context_saver)) {
                StatRow(
                    stringResource(R.string.token_usage_saver_folded),
                    if (saver.aged > 0) saver.aged.toString() else "—",
                )
                StatRow(
                    stringResource(R.string.token_usage_saver_saved),
                    if (saver.savedChars > 0) "≈" + formatTokens(saver.savedChars / ContextSaverRuntime.CHARS_PER_TOKEN) else "—",
                )
            }
            StatSection(title = stringResource(R.string.token_usage_section_memory)) {
                StatRow(
                    stringResource(R.string.token_usage_memory_lines),
                    if (memoryUsage.first > 0) memoryUsage.first.toString() else "—",
                )
                StatRow(
                    stringResource(R.string.token_usage_memory_shown),
                    if (memoryUsage.first > 0) memoryUsage.second.toString() else "—",
                )
            }
        }
    }
}

@Composable
private fun StatSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title.uppercaseForDisplay(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
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

// iOS formatting: "%.1fM" >= 1M, "%.1fK" >= 1k, raw otherwise.
// [T-android-format-converge] Body moved to [formatTokenCount]; this keeps
// the sheet profile — uppercase suffix, one decimal always, normal magnitude.
private fun formatTokens(n: Long): String =
    formatTokenCount(n, uppercaseSuffix = true)

private fun formatTokens(n: Int): String = formatTokens(n.toLong())

/**
 * [#25] Per-block rows of the context breakdown (merged from the former
 * standalone ContextUsageSheet). One row per semantic block, largest-first
 * so the dominant cost is on top; share is vs the estimated block sum.
 */
@Composable
private fun ContextBlockList(data: ContextBreakdown) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        data.blocks
            .sortedByDescending { it.tokens }
            .forEach { block ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(block.key.labelRes()),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${(block.share * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = formatTokens(block.tokens),
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

/** Per-tool rows shown before the calls fold into the "more kinds" remainder row. */
private const val TOOL_ROWS_SHOWN = 5
