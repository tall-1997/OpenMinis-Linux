package com.openminis.app.ui.chat

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.decorPulse

// [T-android-split-chat] Self-contained "thinking / streaming" dot indicators
// extracted verbatim from ChatScreen.kt. `internal` so the chat package can
// still reference them. No logic change — code moved as-is.
// (`BouncingDots` was dropped from this file: zero callers, the live indicator
// is `TypingIndicator` below. The stale [T-android-split-chat] note in
// ChatAssistantMessageUI.kt that still names it is just a comment.)

// iOS-style streaming "..." after tool title — 3 dots bouncing inline with text
@Composable
internal fun StreamingDotsText() {
    Row {
        repeat(3) { i ->
            // [T-decor-shared-clock] 余弦弹跳 0→-3dp，700ms 全周期，
            // 错峰 i×120ms（原 tween(350, delayMillis = i*120, Reverse)）。
            val offset = decorPulse(
                min = 0f,
                max = -3f,
                periodMs = 700,
                staggerFraction = i * (120f / 700f),
            )
            Text(
                text = ".",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.offset(y = offset.dp),
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
 */
@Composable
internal fun CompactProgressIndicator(
    progress: ChatViewModel.CompactProgress,
    onCancel: () -> Unit,
) {
    var elapsedSec by remember(progress.startedAtMs) { mutableStateOf(0) }
    LaunchedEffect(progress.startedAtMs) {
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - progress.startedAtMs) / 1000L).toInt()
            kotlinx.coroutines.delay(1000)
        }
    }

    // 呼吸动画：缩放+透明度脉冲，避免用户以为压缩过程卡住。
    // [T-decor-shared-clock] 共享时钟余弦脉冲（原 900ms Linear Reverse 三角波，
    // 换成平滑包络；量化后实际周期 2000ms）。
    val scale = decorPulse(min = 0.85f, max = 1.05f, periodMs = 1800)
    val alpha = decorPulse(min = 0.4f, max = 1.0f, periodMs = 1800)

    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier
                .size(18.dp)
                .scale(scale)
                .alpha(alpha),
            imageVector = Icons.Default.HourglassEmpty,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = when (progress.stageKind) {
                "fallback" -> stringResource(
                    R.string.compact_progress_fallback,
                    progress.stage,
                    progress.stageBudget,
                    progress.modelLabel.ifBlank { "fallback" },
                    elapsedSec,
                )
                "session-retry" -> stringResource(
                    R.string.compact_progress_session_retry,
                    progress.stage,
                    progress.stageBudget,
                    progress.modelLabel.ifBlank { "session" },
                    elapsedSec,
                )
                else -> stringResource(
                    R.string.compact_progress_session,
                    progress.stage.coerceAtLeast(1),
                    progress.stageBudget.coerceAtLeast(2),
                    progress.modelLabel.ifBlank { "session" },
                    elapsedSec,
                )
            },
            fontSize = 14.sp,
            color = ChatColors.tertiaryText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.cancel),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onCancel)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ─── Typing Indicator (three dots pulsing) ────────────────────────────────────

@Composable
internal fun TypingIndicator() {
    // [T-decor-shared-clock] 弹跳点走共享时钟（原 400ms Linear Reverse 三角波，
    // 换成平滑余弦包络，错峰 index×150ms）。
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
        Text(
            text = stringResource(R.string.chat_typing_indicator, soulName),
            fontSize = 15.sp,
            color = ChatColors.tertiaryText,
        )
        // Animated bouncing dots
        val dots = listOf(".", ".", ".")
        dots.forEachIndexed { index, dot ->
            val offsetY = decorPulse(
                min = 0f,
                max = -6f,
                periodMs = 800,
                staggerFraction = index * (150f / 800f),
            )
            Text(
                text = dot,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.tertiaryText,
                modifier = Modifier.graphicsLayer { translationY = offsetY },
            )
        }
    }
}
