package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.BuiltinFreeLane
import com.openminis.app.ui.theme.m3Icon

/** Display order matching iOS. */
internal val providerDisplayOrder = listOf(
    ProviderType.openAI,
    ProviderType.anthropic,
    ProviderType.gemini,
    ProviderType.xAI,
    ProviderType.kimiCode,
    ProviderType.openRouter,
)

/**
 * Add-provider entry for the built-in OpenCode Zen free lane.
 *
 * This is a provider choice, so it belongs at the end of the protocol list
 * rather than in a section of its own — but it is offered only while the
 * instance is missing. A device that still has it (including one where the user
 * built their own Zen provider with a real key, which is never recognised as the
 * free lane) sees no row here and no row on the providers list.
 *
 * Row shape is inherited from [SettingsRow]: 56dp single line, 0.5dp hairline,
 * 14/12dp padding, no divider on the last row of the section. The icon is a
 * registered Filled/Outlined pair so it tracks the dynamic-colour icon mode.
 */
@Composable
internal fun FreeLanePresetRow(builtin: BuiltinFreeLane?, onPick: (BuiltinFreeLane) -> Unit) {
    if (builtin == null) return
    SettingsRow(
        // Brand copy, not UI copy: the lane label is a proper noun, so it comes
        // from the descriptor rather than a per-lane string resource.
        title = builtin.label,
        subtitle = stringResource(R.string.free_lane_preset_subtitle),
        icon = m3Icon(Icons.Filled.CloudDownload),
        onClick = { onPick(builtin) },
        showDivider = false,
    )
}

/**
 * Icon and color per provider type, matching iOS SF Symbols.
 *
 * Brand data rather than screen layout, so it lives beside [providerDisplayOrder]
 * instead of inside AddProviderScreen — that file is pinned at its architecture
 * ceiling, and the pair is also what renders an already-restored instance.
 */
internal fun providerIcon(type: ProviderType): Pair<ImageVector, Color> = when (type) {
    ProviderType.openAI -> Icons.Default.Hub to Color(0xFF4CAF50)           // green
    ProviderType.anthropic -> Icons.Default.AutoAwesome to Color(0xFFAB47BC) // purple
    ProviderType.gemini -> Icons.Default.Diamond to Color(0xFF42A5F5)        // blue
    ProviderType.openRouter -> Icons.Default.AltRoute to Color(0xFF00BCD4)    // cyan
    ProviderType.xAI -> Icons.Default.FlashOn to Color(0xFFFF7043)           // orange — Grok visual cue
    // [T-kimi-oauth] Indigo — matches iOS's Kimi accent.
    ProviderType.kimiCode -> Icons.Default.Terminal to Color(0xFF5C6BC0)
    // [T-android-provider-type-parity] Types that arrive only from an iOS
    // package / newer build; never offered in addableProviderTypes, but the
    // icon helper is also used to render an already-restored instance.
    ProviderType.openAIResponses -> Icons.Default.Hub to Color(0xFF4CAF50)
    ProviderType.antigravity,
    ProviderType.unsupported -> Icons.Default.Cloud to Color(0xFF9E9E9E)
}
