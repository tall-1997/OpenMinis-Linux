package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.ui.RoutingStrategyUi
import com.openminis.app.ui.theme.m3Icon

/**
 * [T-routing-progressive-disclosure] 路由策略卡：常用三种直接给，其余四种折叠进
 * 「更多策略」；当前选中项属于折叠档时默认展开，避免「已选但看不见」。
 *
 * 从 ModelGroupDetailScreen 抽出以守住架构行数上限（只做搬移，行为不变）。
 */
@Composable
internal fun RoutingStrategyCard(
    selected: RoutingStrategy,
    groupId: String,
    onSelect: (RoutingStrategy) -> Unit,
) {
    val common = remember {
        listOf(
            RoutingStrategy.fallback,
            RoutingStrategy.loadBalance,
            RoutingStrategy.roundRobin,
        )
    }
    val advanced = remember {
        listOf(
            RoutingStrategy.weighted,
            RoutingStrategy.leastUsed,
            RoutingStrategy.quotaWindow,
            RoutingStrategy.smart,
        )
    }
    var showAdvanced by remember(groupId) { mutableStateOf(selected in advanced) }
    val visible = if (showAdvanced) common + advanced else common

    SettingsSection(
        header = stringResource(R.string.model_group_detail_routing_strategy),
        footer = stringResource(RoutingStrategyUi.footerRes(selected)),
    ) {
        visible.forEach { candidate ->
            SettingsChoiceRow(
                title = stringResource(RoutingStrategyUi.titleRes(candidate)),
                selected = selected == candidate,
                onSelect = { onSelect(candidate) },
                showDivider = true,
            )
        }
        SettingsRow(
            title = stringResource(
                if (showAdvanced) {
                    R.string.model_group_detail_fewer_strategies
                } else {
                    R.string.model_group_detail_more_strategies
                },
            ),
            onClick = { showAdvanced = !showAdvanced },
            showChevron = false,
            showDivider = false,
            trailing = {
                Icon(
                    m3Icon(
                        if (showAdvanced) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
        )
    }
}
