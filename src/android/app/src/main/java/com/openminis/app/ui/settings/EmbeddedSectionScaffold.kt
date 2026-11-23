package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.components.SectionDesign

/**
 * [T-model-connection] 「模型与连接」聚合页里嵌入一个设置页正文的外壳：
 * 顶部一行行内动作（同步全部 / 添加供应商 / 新建组），下面是宿主页面自己的正文。
 *
 * 被嵌入的页面因此不必各自重写这段布局，也不会画自己的 Scaffold 顶栏；
 * 独立入口（[Routes.MODEL_GROUPS] 等）继续走原来的 Scaffold。
 *
 * [scroll] 有两种形态，由正文自己的滚动容器决定：
 * - `true` — 正文是普通 Column（ProviderListScreen 的 ReorderableColumn），
 *   外壳提供 `verticalScroll`；
 * - `false` — 正文自带滚动（ModelGroupsScreen 的 LazyColumn），
 *   外壳只给一个受约束的剩余高度，避免嵌套滚动。
 */
@Composable
internal fun EmbeddedSectionScaffold(
    actions: @Composable () -> Unit,
    scroll: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SectionDesign.screenBackgroundColor()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) { actions() }
        if (scroll) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        } else {
            Column(modifier = Modifier.weight(1f), content = content)
        }
    }
}
