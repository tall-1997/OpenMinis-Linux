package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.ui.components.SectionCard
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionDivider
import com.openminis.app.ui.components.SectionFooter
import com.openminis.app.ui.components.SectionHeader

/**
 * [T-model-connection] 「默认槽位」段：默认主模型 / 语音输入 / 语音输出 / 视觉 /
 * 压缩回退五个绑定。
 *
 * 从 ModelGroupsScreen 抽成独立段（原先塞在组列表的 LazyColumn 里，要滚到底
 * 才能改）。数据与回调全部由调用方传入，组件自己不读仓库，保证槽位只有一个
 * 写入路径。
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SectionDesign.screenBackgroundColor())
            .verticalScroll(rememberScrollState())
            .padding(bottom = SectionDesign.FirstSectionTopGap),
    ) {
        Spacer(modifier = Modifier.height(SectionDesign.FirstSectionTopGap))
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
