package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets

/**
 * Title is centered on the full bar width, not in the leftover gap between
 * the navigation icon and the actions. Side slots are measured and the title
 * is given equal gutters of max(start, end), so a wide action cluster cannot
 * shove the title off-center on a narrow screen, and the title still cannot
 * draw under the icons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinisCenterTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    expandedHeight: Dp = 64.dp,
) {
    Surface(color = colors.containerColor, modifier = modifier) {
        CompositionLocalProvider(LocalContentColor provides colors.titleContentColor) {
            SubcomposeLayout(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(windowInsets)
                    .heightIn(min = expandedHeight),
            ) { constraints ->
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val nav = subcompose("nav") {
                    Box(
                        Modifier.padding(start = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides colors.navigationIconContentColor) {
                            navigationIcon()
                        }
                    }
                }.first().measure(loose)
                val acts = subcompose("actions") {
                    Row(
                        Modifier.padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides colors.actionIconContentColor) {
                            actions()
                        }
                    }
                }.first().measure(loose)
                val side = maxOf(nav.width, acts.width)
                val titleMax = (constraints.maxWidth - side * 2).coerceAtLeast(0)
                val titlePlaceable = subcompose("title") {
                    Box(
                        Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ProvideTextStyle(
                            MaterialTheme.typography.titleLarge.copy(color = colors.titleContentColor),
                        ) {
                            title()
                        }
                    }
                }.first().measure(loose.copy(maxWidth = titleMax))
                val barHeight = maxOf(expandedHeight.roundToPx(), nav.height, acts.height, titlePlaceable.height)
                layout(constraints.maxWidth, barHeight) {
                    nav.placeRelative(0, (barHeight - nav.height) / 2)
                    acts.placeRelative(constraints.maxWidth - acts.width, (barHeight - acts.height) / 2)
                    titlePlaceable.placeRelative(
                        (constraints.maxWidth - titlePlaceable.width) / 2,
                        (barHeight - titlePlaceable.height) / 2,
                    )
                }
            }
        }
    }
}
