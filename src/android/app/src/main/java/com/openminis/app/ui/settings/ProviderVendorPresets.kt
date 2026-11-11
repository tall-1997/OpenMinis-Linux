package com.openminis.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ProviderType
import com.openminis.app.ui.components.RowLabel

/**
 * [T-android-vendor-presets] One-tap preset for a mainstream provider vendor,
 * consumed by the vendor-preset chip row in AddProviderScreen's endpoint
 * section (ApiKeyConfigSection).
 *
 * The design deliberately adds NO new [ProviderType]: every vendor here
 * exposes an OpenAI-compatible endpoint, so a preset is expressed as
 * "protocol template (an existing [ProviderType]) + pre-filled base URL".
 * The protocol list in the add flow stays untouched; a preset only saves the
 * user from typing the endpoint and looking up where to get a key.
 *
 * Copy lives in string resources (labels AND docs hints) rather than Kotlin
 * literals: the CJK hardcoded-string ratchet freezes Chinese literals in
 * Kotlin sources, and the add-flow screens resolve user copy through
 * stringResource so it follows the device locale — brand names included
 * (智谱 GLM / 通义千问 read differently in the two locales). Brand names in
 * resource form follow the same "brand copy, not UI copy" exemption
 * FreeLanePresetRow documents, just relocated into values-zh.
 *
 * URLs are each vendor's documented OpenAI-compatible base, copied verbatim
 * from the vendor's own docs. A wrong address is worse than a missing vendor:
 * only vendors whose standard OpenAI-compatible base is publicly documented
 * are listed. [appendV1] pairs with [baseUrl]: every URL here already carries
 * the vendor's full versioned path (or the documented bare host), so the
 * instance must NOT auto-append "/v1" on top — see the DeepSeek vs GLM
 * examples below, where auto-append would 404.
 *
 * @param labelRes   Display name shown on the chip and pre-filled as the
 *   instance label (localized brand name).
 * @param type       Protocol the preset rides — always an existing,
 *   usable [ProviderType]; today every mainstream vendor is OpenAI-compatible.
 * @param baseUrl    Endpoint pre-filled into the custom-base field, verbatim
 *   from the vendor's docs. Null would mean "official endpoint, nothing to
 *   pre-fill" — no preset here needs it yet, but the add flow already treats
 *   an empty base that way.
 * @param appendV1   Value to apply to the "auto append /v1" toggle when the
 *   preset is picked. False for all current presets (the URLs are complete as
 *   written); a future preset pointing at a bare host that expects the /v1
 *   suffix would set true.
 * @param docsHintRes One-line "where to get a key" pointer shown under the
 *   chips while this preset's endpoint is the one filled in.
 */
internal data class VendorPreset(
    @StringRes val labelRes: Int,
    val type: ProviderType,
    val baseUrl: String?,
    val appendV1: Boolean,
    @StringRes val docsHintRes: Int,
)

/**
 * Vendor preset catalog, domestic vendors first (magpie-style preset sweep).
 *
 * Protocol notes:
 *  - Kimi/Moonshot rides [ProviderType.openAI] and points at the Moonshot
 *    OPEN PLATFORM (pay-as-you-go API keys) — deliberately NOT
 *    [ProviderType.kimiCode], which is the Coding Plan subscription with
 *    its own OAuth flow and api.kimi.com/coding endpoint.
 *  - Ollama / LM Studio are local gateways; their keys are optional, and
 *    ProviderInstance.allowsEmptyAPIKey already accepts an empty key for
 *    custom-base OpenAI instances, so the save button enables with the
 *    base URL filled and no key.
 */
internal val vendorPresets: List<VendorPreset> = listOf(
    // DeepSeek — docs: https://api-docs.deepseek.com (base_url
    // https://api.deepseek.com; /v1 exists as the OpenAI-compat alias).
    VendorPreset(
        R.string.add_provider_vendor_preset_deepseek,
        ProviderType.openAI,
        "https://api.deepseek.com",
        appendV1 = false,
        R.string.add_provider_vendor_hint_deepseek,
    ),
    // 智谱 GLM — docs: https://docs.bigmodel.cn (POST /api/paas/v4/chat/completions).
    // appendV1=false is load-bearing: /api/paas/v4/v1/… would 404.
    VendorPreset(
        R.string.add_provider_vendor_preset_zhipu,
        ProviderType.openAI,
        "https://open.bigmodel.cn/api/paas/v4",
        appendV1 = false,
        R.string.add_provider_vendor_hint_zhipu,
    ),
    // Kimi (Moonshot open platform) — docs: https://platform.moonshot.cn/docs
    // (base_url https://api.moonshot.cn/v1).
    VendorPreset(
        R.string.add_provider_vendor_preset_kimi,
        ProviderType.openAI,
        "https://api.moonshot.cn/v1",
        appendV1 = false,
        R.string.add_provider_vendor_hint_kimi,
    ),
    // 通义千问 — DashScope OpenAI-compatible mode,
    // docs: https://help.aliyun.com/zh/model-studio (compatible-mode/v1).
    VendorPreset(
        R.string.add_provider_vendor_preset_qwen,
        ProviderType.openAI,
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        appendV1 = false,
        R.string.add_provider_vendor_hint_qwen,
    ),
    // 火山方舟 — docs: https://www.volcengine.com/docs/82379
    // (base_url https://ark.cn-beijing.volces.com/api/v3; the model name is
    // an endpoint ID or model ID, which the docs hint spells out).
    // appendV1=false is load-bearing: /api/v3/v1/… would 404.
    VendorPreset(
        R.string.add_provider_vendor_preset_ark,
        ProviderType.openAI,
        "https://ark.cn-beijing.volces.com/api/v3",
        appendV1 = false,
        R.string.add_provider_vendor_hint_ark,
    ),
    // MiniMax — docs: https://platform.minimaxi.com/document
    // (base_url https://api.minimax.chat/v1, chatcompletion_v2 under it).
    VendorPreset(
        R.string.add_provider_vendor_preset_minimax,
        ProviderType.openAI,
        "https://api.minimax.chat/v1",
        appendV1 = false,
        R.string.add_provider_vendor_hint_minimax,
    ),
    // SiliconFlow — docs: https://docs.siliconflow.cn
    // (base_url https://api.siliconflow.cn/v1).
    VendorPreset(
        R.string.add_provider_vendor_preset_siliconflow,
        ProviderType.openAI,
        "https://api.siliconflow.cn/v1",
        appendV1 = false,
        R.string.add_provider_vendor_hint_siliconflow,
    ),
    // Groq — docs: https://console.groq.com/docs/openai
    // (base_url https://api.groq.com/openai/v1).
    VendorPreset(
        R.string.add_provider_vendor_preset_groq,
        ProviderType.openAI,
        "https://api.groq.com/openai/v1",
        appendV1 = false,
        R.string.add_provider_vendor_hint_groq,
    ),
    // Together — docs: https://docs.together.ai
    // (base_url https://api.together.xyz/v1).
    VendorPreset(
        R.string.add_provider_vendor_preset_together,
        ProviderType.openAI,
        "https://api.together.xyz/v1",
        appendV1 = false,
        R.string.add_provider_vendor_hint_together,
    ),
    // Mistral — docs: https://docs.mistral.ai
    // (base_url https://api.mistral.ai/v1).
    VendorPreset(
        R.string.add_provider_vendor_preset_mistral,
        ProviderType.openAI,
        "https://api.mistral.ai/v1",
        appendV1 = false,
        R.string.add_provider_vendor_hint_mistral,
    ),
    // Ollama local — docs: https://github.com/ollama/ollama/blob/main/docs/openai.md
    // (OpenAI-compatible base http://localhost:11434/v1; 127.0.0.1 is the same
    // listener written to survive hosts where localhost resolves to ::1).
    VendorPreset(
        R.string.add_provider_vendor_preset_ollama,
        ProviderType.openAI,
        "http://127.0.0.1:11434/v1",
        appendV1 = false,
        R.string.add_provider_vendor_hint_ollama,
    ),
    // LM Studio local — docs: https://lmstudio.ai/docs/app/api
    // (OpenAI-compatible base http://localhost:1234/v1 while the Developer
    // server is running; 127.0.0.1 for the same ::1 reason as Ollama).
    VendorPreset(
        R.string.add_provider_vendor_preset_lmstudio,
        ProviderType.openAI,
        "http://127.0.0.1:1234/v1",
        appendV1 = false,
        R.string.add_provider_vendor_hint_lmstudio,
    ),
)

/**
 * [T-android-vendor-presets] The vendor-preset UI: a horizontal FilterChip row
 * of the catalog's presets for [providerType], rendered inside the endpoint
 * card directly above the base-URL field it feeds. Emits nothing (not even the
 * row label) for a protocol the catalog doesn't cover, so every other
 * protocol's endpoint card stays byte-identical to before.
 *
 * Selection is derived statelessly: a chip is selected while the current base
 * URL equals that preset's endpoint (trailing-slash insensitive), so manual
 * edits simply deselect it. Under the chips sits a one-line caption — the
 * picked vendor's "where to get a key" hint, or, before any pick, what
 * tapping a chip does.
 *
 * Lives here rather than in AddProviderScreen because that file is pinned at
 * its architecture ceiling; the same reason [providerDisplayOrder] and the
 * icon helpers live in AddProviderPresets.kt.
 *
 * @param onPick Invoked with the preset AND its resolved display name — the
 *   caller owns the state writes (base URL, /v1 policy, instance label) and
 *   the post-pick focus jump to the API-key field.
 */
@Composable
internal fun VendorPresetChipSection(
    providerType: ProviderType,
    currentBaseUrl: String,
    onPick: (preset: VendorPreset, presetLabel: String) -> Unit,
) {
    val typePresets = remember(providerType) { vendorPresets.filter { it.type == providerType } }
    if (typePresets.isEmpty()) return
    val matchedPreset = typePresets.firstOrNull { preset ->
        preset.baseUrl != null &&
            currentBaseUrl.trim().trimEnd('/') == preset.baseUrl.trimEnd('/')
    }
    RowLabel(text = stringResource(R.string.add_provider_vendor_presets))
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        typePresets.forEach { preset ->
            val presetLabel = stringResource(preset.labelRes)
            FilterChip(
                selected = preset == matchedPreset,
                onClick = { onPick(preset, presetLabel) },
                label = { Text(presetLabel, maxLines = 1) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        if (matchedPreset != null) {
            stringResource(matchedPreset.docsHintRes)
        } else {
            stringResource(R.string.add_provider_vendor_presets_pick_hint)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
}
