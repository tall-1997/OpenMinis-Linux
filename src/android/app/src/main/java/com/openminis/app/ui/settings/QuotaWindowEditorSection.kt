package com.openminis.app.ui.settings

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import com.openminis.app.R
import com.openminis.app.data.GroupRoutingState
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.ui.components.SettingsRowDivider

/**
 * [T-routing-strategy-x-fix] Quota-window editor and fallback-trigger
 * chooser, extracted from [ModelGroupDetailScreen] to keep that file under
 * the architecture gate. Side effects (persist + router sync + snackbar)
 * live in the caller via [applyQuotaWindow] / [onFallbackStrategyChange];
 * these Composables only render and forward edits.
 */
@Composable
internal fun QuotaWindowEditorSection(
    group: ModelGroup,
    quotaTotalText: String,
    onQuotaTotalTextChange: (String) -> Unit,
    quotaPeriodHoursText: String,
    onQuotaPeriodHoursTextChange: (String) -> Unit,
    applyQuotaWindow: () -> Unit,
    focusManager: FocusManager,
) {
    val context = LocalContext.current
    SettingsSection(
        header = stringResource(R.string.model_group_detail_quota_window),
        footer = stringResource(R.string.model_group_detail_quota_window_footer),
    ) {
        if (group.quotaWindowResetAt != null) {
            SettingsCardBlock {
                Text(
                    text = context.getString(
                        R.string.model_group_detail_quota_window_remaining,
                        GroupRoutingState.currentQuotaRemaining(group),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsRowDivider()
        }
        SettingsCardBlock {
            SectionTextField(
                value = quotaTotalText,
                onValueChange = { input -> onQuotaTotalTextChange(input.filter { it.isDigit() }) },
                placeholder = stringResource(R.string.model_group_detail_quota_total_placeholder),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                fieldModifier = Modifier.onFocusChanged { fs ->
                    if (!fs.isFocused) applyQuotaWindow()
                },
            )
        }
        SettingsRowDivider()
        SettingsCardBlock {
            SectionTextField(
                value = quotaPeriodHoursText,
                onValueChange = { input -> onQuotaPeriodHoursTextChange(input.filter { it.isDigit() }) },
                placeholder = stringResource(R.string.model_group_detail_quota_period_placeholder),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                fieldModifier = Modifier.onFocusChanged { fs ->
                    if (!fs.isFocused) applyQuotaWindow()
                },
            )
        }
    }
}

/**
 * [T-routing-strategy-x-fix] Fallback-trigger chooser, extracted from
 * [ModelGroupDetailScreen]. Pure choice UI; the persist callback belongs
 * to the caller.
 */
@Composable
internal fun FallbackTriggerSection(
    group: ModelGroup,
    fallbackStrategy: FallbackStrategy,
    onFallbackStrategyChange: (FallbackStrategy) -> Unit,
) {
    SettingsSection(
        header = stringResource(R.string.model_group_detail_fallback_trigger),
        footer = when (fallbackStrategy) {
            FallbackStrategy.default -> stringResource(R.string.model_group_detail_fallback_default_footer)
            FallbackStrategy.always -> stringResource(R.string.model_group_detail_fallback_always_footer)
        },
    ) {
        SettingsChoiceRow(
            title = stringResource(R.string.common_default),
            selected = fallbackStrategy == FallbackStrategy.default,
            onSelect = { onFallbackStrategyChange(FallbackStrategy.default) },
        )
        SettingsChoiceRow(
            title = stringResource(R.string.model_group_detail_always),
            selected = fallbackStrategy == FallbackStrategy.always,
            onSelect = { onFallbackStrategyChange(FallbackStrategy.always) },
            showDivider = false,
        )
    }
}
