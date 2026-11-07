package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.ui.theme.IosIndigo
import kotlin.math.roundToInt

/**
 * Discrete-step max-context slider that mirrors iOS `ContextLimitSlider`
 * (ModelGroupDetailView.swift, fa77f493). Step labels:
 * 32K / 64K / 128K / 200K / 400K / 1M / Unlimited.
 *
 * Fixed 7-stop ladder, always shown verbatim regardless of which models (if
 * any) are in the group. The previous behaviour clipped the ladder to the
 * largest member-declared context window, but model metadata is unreliable
 * (e.g. image-output / router models report no window and fall through to
 * the 128K heuristic default) and the per-model field is already enforced
 * at runtime when assembling requests via `min(groupLimit, modelLimit)`.
 * Keeping the ladder fixed makes the UI a pure user-intent slider —
 * "I want this group to cap context at N" — and removes the entire family
 * of bugs where adding/removing a model would silently shrink the picker.
 *
 * The "Unlimited" stop maps to `Int.MAX_VALUE` in the binding; the runtime
 * consumer treats anything `>= Int.MAX_VALUE` as "no override, use the
 * model's native window".
 */
internal const val CONTEXT_LIMIT_UNLIMITED_SENTINEL: Int = Int.MAX_VALUE

internal val CONTEXT_LIMIT_PRESETS = listOf(
    32_000,
    64_000,
    128_000,
    200_000,
    400_000,
    1_000_000,
)

internal fun formatPresetLabel(tokens: Int): String =
    if (tokens >= 1_000_000) "${tokens / 1_000_000}M" else "${tokens / 1_000}K"

@Composable
internal fun ContextLimitSlider(
    value: Int?,
    unlimitedLabel: String,
    maxContextLabel: String,
    onValueChange: (Int) -> Unit,
) {
    data class Step(val label: String, val tokens: Int)
    val steps = remember(unlimitedLabel) {
        CONTEXT_LIMIT_PRESETS.map { Step(formatPresetLabel(it), it) } +
            Step(unlimitedLabel, CONTEXT_LIMIT_UNLIMITED_SENTINEL)
    }
    // Track the selected stop by index. Snap to nearest stop <= value
    // so legacy saved values (e.g. 350K) land on the next stop down (200K).
    var selectedIndex by remember(steps, value) {
        mutableStateOf(
            when {
                value == null -> steps.lastIndex
                value >= CONTEXT_LIMIT_UNLIMITED_SENTINEL -> steps.lastIndex
                else -> steps.indexOfLast { it.tokens <= value }.takeIf { it >= 0 } ?: 0
            }
        )
    }
    val currentLabel: String = run {
        val v = value
        when {
            v == null -> steps.last().label
            v >= CONTEXT_LIMIT_UNLIMITED_SENTINEL -> unlimitedLabel
            else -> steps.firstOrNull { it.tokens == v }?.label ?: "${v / 1000}K"
        }
    }
    SettingsCardBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = maxContextLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = selectedIndex.toFloat(),
            onValueChange = { newIndex ->
                val idx = newIndex.roundToInt().coerceIn(0, steps.lastIndex)
                if (idx != selectedIndex) {
                    selectedIndex = idx
                    onValueChange(steps[idx].tokens)
                }
            },
            valueRange = 0f..steps.lastIndex.toFloat().coerceAtLeast(1f),
            steps = (steps.size - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            Text(
                steps.first().label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                unlimitedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * [T-android-ctx-slider-cap] Toggle + slider section extracted from
 * [ModelGroupDetailScreen] to keep that file under the architecture gate.
 * Rendering and the restore/stash decision live here; persistence goes
 * through [onPersist] with (tokens, lastTokens) so the caller owns the
 * repository write.
 */
@Composable
internal fun ContextLimitSection(
    group: ModelGroup,
    contextLimitTokens: Int?,
    onContextLimitTokensChange: (Int?) -> Unit,
    onPersist: (Int?, Int?) -> Unit,
) {
    val contextEnabled = contextLimitTokens != null
    // T-android-ctx-slider-cap (port iOS fa77f493): the slider
    // is now a fixed 7-stop ladder decoupled from member-model
    // metadata. We no longer derive an `unlimitedTokens` cap
    // from `max(member.contextWindow)`, because that field is
    // unreliable (image-output / router models report no
    // window, falling through to the 128K heuristic default)
    // and silently shrunk the picker. Per-model enforcement
    // still happens at runtime via the consumer of
    // `contextLimitTokens` (min(groupLimit, modelLimit)).
    SettingsSwitchRow(
        title = stringResource(R.string.model_group_detail_limit_context_window),
        checked = contextEnabled,
        onCheckedChange = { on ->
            if (on) {
                // Restore last user-selected value verbatim,
                // defaulting to 128K for first-time activation.
                val restored = group.lastContextLimitTokens ?: 128_000
                onContextLimitTokensChange(restored)
                onPersist(restored, restored)
            } else {
                // Stash the current value into lastContextLimitTokens
                // before nulling contextLimitTokens, so toggle ON
                // can restore it (even after navigating away).
                val stash = contextLimitTokens
                onContextLimitTokensChange(null)
                onPersist(null, stash ?: group.lastContextLimitTokens)
            }
        },
        icon = Icons.Default.Memory,
        iconColor = IosIndigo,
        showDivider = contextEnabled,
    )
    if (contextEnabled) {
        ContextLimitSlider(
            value = contextLimitTokens,
            unlimitedLabel = stringResource(R.string.model_group_detail_unlimited),
            maxContextLabel = stringResource(R.string.model_group_detail_max_context),
            onValueChange = { newTokens ->
                onContextLimitTokensChange(newTokens)
                onPersist(newTokens, newTokens)
            },
        )
    }
}
