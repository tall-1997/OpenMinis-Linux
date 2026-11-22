package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.data.repository.AutoModelGroups
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.SectionDesign

/**
 * [T-model-connection] 「模型与连接」聚合页：设置里原本并列的「供应商管理」和
 * 「模型组」两个入口收成一个页面，用分段控件切换两段内容。
 *
 * 两个标签页直接复用 [ProviderListScreen] / [ModelGroupsScreen] 的正文
 * （embedded = true：它们不再画自己的 Scaffold 顶栏），因此不复制任何列表或
 * 配置逻辑，顶栏动作（同步全部、添加供应商、新建组）也作为行内操作条保留。
 */
@Composable
fun ModelConnectionScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
    onProviderClick: (String) -> Unit,
    onVoiceServiceClick: (String) -> Unit,
    onGroupClick: (String) -> Unit,
    onAddAgentLoopModels: () -> Unit,
    onAddAgentLoopGroups: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    // 槽位段的数据与回调都由本页持有：组件内部不再读仓库，保证单一写入路径。
    val config by providerRepository.config.collectAsStateWithLifecycle()
    val groups = AutoModelGroups.userGroups(config)

    SettingsScaffold(
        title = stringResource(R.string.model_connection_title),
        onBack = onBack,
        scrollable = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SectionDesign.screenBackgroundColor()),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SegmentedButton(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                ) { Text(stringResource(R.string.settings_manage_providers)) }
                SegmentedButton(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                ) { Text(stringResource(R.string.settings_model_groups)) }
                SegmentedButton(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                ) { Text(stringResource(R.string.model_groups_defaults)) }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (tab) {
                    0 -> ProviderListScreen(
                        providerRepository = providerRepository,
                        onBack = onBack,
                        onAddProvider = onAddProvider,
                        onProviderClick = onProviderClick,
                        onVoiceServiceClick = onVoiceServiceClick,
                        embedded = true,
                    )
                    1 -> ModelGroupsScreen(
                        providerRepository = providerRepository,
                        onBack = onBack,
                        onGroupClick = onGroupClick,
                        onAddAgentLoopModels = onAddAgentLoopModels,
                        onAddAgentLoopGroups = onAddAgentLoopGroups,
                        embedded = true,
                    )
                    else -> DefaultSlotsContent(
                        config = config,
                        groups = groups,
                        onPrimaryChange = { providerRepository.defaultPrimaryGroupId = it },
                        onVoiceInputChange = { providerRepository.voiceInputGroupId = it },
                        onVoiceOutputChange = { providerRepository.voiceOutputGroupId = it },
                        onVisionChange = { providerRepository.visionGroupId = it },
                        onCompactFallbackChange = { providerRepository.compactFallbackGroupId = it },
                    )
                }
            }
        }
    }
}
