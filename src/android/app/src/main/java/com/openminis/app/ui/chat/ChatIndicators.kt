package com.openminis.app.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.components.decorativePhase
import com.openminis.app.ui.components.decorativePingPong

// [T-android-split-chat] Self-contained "thinking / streaming" dot indicators
// extracted verbatim from ChatScreen.kt. `internal` so the chat package can
// still reference them. No logic change — code moved as-is.

@Composable
internal fun BouncingDots(color: Color) {
    // [T-android-decorative-anim-perf] Was three infinite transitions read in
    // composition and animated through PADDING — a layout-phase property, so
    // every frame recomposed and re-laid-out the row. Now one shared 30 fps
    // tick, read inside the layer lambda, moving translationY only.
    val tick = rememberDecorativeTick()
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .graphicsLayer {
                        val p = decorativePingPong(decorativePhase(tick.value, 700, i * 120))
                        translationY = -2.dp.toPx() * p
                    }
                    .background(color, CircleShape),
            )
        }
    }
}

// iOS-style streaming "..." after tool title — 3 dots bouncing inline with text
@Composable
internal fun StreamingDotsText() {
    // [T-android-decorative-anim-perf] `Modifier.offset(y = …)` in its
    // non-lambda form is a layout-phase read; the dots re-laid-out the pill
    // title row every frame. Layer translation instead, from the shared tick.
    val tick = rememberDecorativeTick()
    Row {
        repeat(3) { i ->
            Text(
                text = ".",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.graphicsLayer {
                    val p = decorativePingPong(decorativePhase(tick.value, 700, i * 120))
                    translationY = -3.dp.toPx() * p
                },
            )
        }
    }
}

// ─── Compaction progress ──────────────────────────────────────────────────────

/**
 * [T-android-compact-progress] Live status while a compaction runs, with a
 * Cancel affordance.
 *
 * Compaction used to present as a single unchanging flag for however long it
 * took — which on a slow or rate-limited model could be many minutes, and is
 * indistinguishable from a hang. This ticks once a second so the elapsed count
 * visibly moves, and names the split segment when the run had to fall back to
 * summarising halves, so "still working" is legible rather than inferred.
 *
 * [T-android-compact-progress-divider] Drawn as the same centred divider the
 * finished compaction leaves behind ([FallbackInfoBlock], iOS
 * compactDividerRow / systemDividerRow with `isCompactLoading`): a thin rule
 * on each side, a small spinner, the status and Cancel in the middle. The
 * spinner gives way to the fold icon and the "N messages compacted" divider
 * (whose info icon opens the summary) when the run ends. Cancel stays in the
 * row: unlike iOS, the composer has no Stop button while compacting.
 */
@Composable
internal fun CompactProgressIndicator(
    progress: ChatViewModel.CompactProgress,
    onCancel: () -> Unit,
) {
    // Re-reads the clock every second; the changing value is what makes the
    // row demonstrably alive.
    var elapsedSec by remember(progress.startedAtMs) { mutableStateOf(0) }
    LaunchedEffect(progress.startedAtMs) {
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - progress.startedAtMs) / 1000L).toInt()
            kotlinx.coroutines.delay(1000)
        }
    }
    val fg = ChatColors.secondaryText
    val rule = ChatColors.separator

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f).height(1.dp).background(rule))
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                color = fg,
                strokeWidth = 1.5.dp,
            )
            Text(
                text = if (progress.depth > 0) {
                    // Only surfaced once a split actually happened — saying
                    // "segment 1" on the common single-call path would imply a
                    // complexity that isn't there.
                    stringResource(
                        R.string.compact_progress_split,
                        elapsedSec,
                        progress.callsIssued,
                        progress.callBudget,
                    )
                } else {
                    stringResource(R.string.compact_progress, elapsedSec)
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = fg,
                maxLines = 1,
                // [T-compact-progress-cancel-fits] Weighted (not filling) so
                // Cancel - the only way to stop a compaction - is measured
                // first and the status ellipsizes into what is left. Unweighted,
                // a long status (German split text, large font) took the whole
                // width and left Cancel at zero.
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = stringResource(R.string.cancel),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        Box(modifier = Modifier.weight(1f).height(1.dp).background(rule))
    }
}

// ─── Typing Indicator (three dots pulsing) ────────────────────────────────────

/**
 * [T-android-retry-badge] With [retryAttempt] > 0 and a click handler, a
 * yellow "↻ n/[retryMax]" badge leads the line while an automatic retry is in
 * progress; it goes when the retried request answers, the ladder ends or the
 * user stops. The read-only sub-agent transcript passes nothing.
 */
@Composable
internal fun TypingIndicator(
    retryAttempt: Int = 0,
    retryMax: Int = 0,
    onRetryBadgeClick: (() -> Unit)? = null,
) {
    // [T-android-decorative-anim-perf] Shared 30 fps tick instead of a private
    // 90 fps infinite transition; the layer-lambda read below was already right.
    val tick = rememberDecorativeTick()
    // Live Soul name → "<custom name> is thinking…" when the user renamed
    // the assistant in Soul settings. SoulStore.cachedMetadata is a StateFlow
    // that's updated on save (SoulSettingsScreen) and at app start
    // (MinisApp.onCreate via refreshCache); collectAsState makes Compose
    // recompose the indicator immediately when it changes.
    val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val soulName = soulMeta.name.trim().ifEmpty { "Minis Ultra" }

    Row(
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (retryAttempt > 0 && onRetryBadgeClick != null) {
            androidx.compose.foundation.layout.Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                RetryBadge(attempt = retryAttempt, maxAttempts = retryMax, onClick = onRetryBadgeClick)
            }
        }
        Text(
            text = stringResource(R.string.chat_typing_indicator, soulName),
            fontSize = 15.sp,
            color = ChatColors.tertiaryText,
        )
        // Animated bouncing dots
        val dots = listOf(".", ".", ".")
        dots.forEachIndexed { index, dot ->
            Text(
                text = dot,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.tertiaryText,
                modifier = Modifier.graphicsLayer {
                    val p = decorativePingPong(decorativePhase(tick.value, 800, index * 150))
                    translationY = -6.dp.toPx() * p
                },
            )
        }
    }
}
