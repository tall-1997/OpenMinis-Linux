// [OpenMinis#311] Provider-level force-thinking switch for Anthropic relays.
package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger

/**
 * [OpenMinis#311] Provider-level "force extended thinking" switch.
 *
 * Anthropic-compatible relays (DeepSeek / Bailian / a self-hosted gateway
 * speaking /v1/messages) often expose model ids whose catalog entry says
 * `supportsReasoning = false`. Without this switch the capability clamp turns
 * every thinking level into OFF and the user cannot force the correct
 * `thinking:{type:"enabled"}` field out at all.
 *
 * Extracted from ProviderDetailScreen for the architecture-policy line ceiling.
 */
@Composable
internal fun ForceThinkingSection(
    instance: ProviderInstance,
    providerRepository: ProviderRepository,
) {
    SettingsSection(
        header = stringResource(R.string.provider_detail_force_thinking),
        footer = stringResource(R.string.provider_detail_force_thinking_footer),
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.provider_detail_force_thinking),
            checked = instance.forceThinkingEnabled,
            onCheckedChange = { on ->
                providerRepository.updateInstance(instance.copy(forceThinkingEnabled = on))
                AppLogger.info("ProviderDetail", "Set forceThinkingEnabled=$on for ${instance.id}")
            },
            showDivider = false,
        )
    }
}
