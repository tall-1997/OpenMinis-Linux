package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.ui.components.SectionCard
import com.openminis.app.ui.components.SectionDivider
import com.openminis.app.ui.components.SectionFooter
import com.openminis.app.ui.components.SectionHeader

/**
 * [T-model-connection] 「默认槽位」段：默认主模型 / 语音输入 / 语音输出 / 视觉 /
 * 压缩回退五个绑定。
 *
 * 只有内容，没有滚动容器、也不带外部间距：两处宿主各自决定怎么滚、上面留多少空——
 * 聚合页把它放进 `verticalScroll` 的第三段，独立的模型组页把它作为 LazyColumn
 * 的一个 item。同一份槽位 UI 只有一处实现，不会在两个入口之间漂移。
 */
@Composable
internal fun DefaultSlotsContent(
    config: ProviderConfig,
    groups: List<ModelGroup>,
    onPrimaryChange: (String?) -> Unit,
    onVoiceInputChange: (String?) -> Unit,
    onVoiceOutputChange: (String?) -> Unit,
    onVisionChange: (String?) -> Unit,
    onCompactFallbackChange: (String?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(text = stringResource(R.string.model_groups_defaults))
        SectionCard {
            // Default Sub 不展示：Sub-agents 走 MultiAgentSettings，
            // defaultSubGroupId 仅为标题生成兜底保留。
            SlotPickerField(
                label = stringResource(R.string.model_groups_default_primary),
                groups = groups,
                entries = config.modelEntries,
                instances = config.instances,
                selectedId = config.defaultPrimaryGroupId,
                onSelect = onPrimaryChange,
            )
            SectionDivider()
            SlotPickerField(
                label = stringResource(R.string.model_groups_voice_input),
                groups = groups,
                entries = config.modelEntries,
                instances = config.instances,
                selectedId = config.voiceInputGroupId,
                onSelect = onVoiceInputChange,
            )
            SectionDivider()
            SlotPickerField(
                label = stringResource(R.string.model_groups_voice_output),
                groups = groups,
                entries = config.modelEntries,
                instances = config.instances,
                selectedId = config.voiceOutputGroupId,
                onSelect = onVoiceOutputChange,
            )
            SectionDivider()
            SlotPickerField(
                label = stringResource(R.string.model_groups_vision),
                groups = groups,
                entries = config.modelEntries,
                instances = config.instances,
                selectedId = config.visionGroupId,
                onSelect = onVisionChange,
            )
            SectionDivider()
            SlotPickerField(
                label = stringResource(R.string.model_groups_compact_fallback),
                groups = groups,
                entries = config.modelEntries,
                instances = config.instances,
                selectedId = config.compactFallbackGroupId,
                onSelect = onCompactFallbackChange,
            )
        }
        SectionFooter(
            text = stringResource(R.string.model_groups_primary_is_used_for_main_agent_tasks_sub) +
                "\n" + stringResource(R.string.model_groups_compact_fallback_footer),
        )
    }
}
