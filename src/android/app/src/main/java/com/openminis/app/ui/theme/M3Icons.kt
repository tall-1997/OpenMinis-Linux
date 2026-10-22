package com.openminis.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * M3 Material Symbols 风格：动态取色模式下用 Outlined，非动态用 Default/Filled。
 *
 * material-icons-core 只带了少数 Outlined 变体。不在映射里的图标动态模式下保持原样。
 */
private val m3IconMapping: Map<ImageVector, ImageVector> = mapOf(
    Icons.Filled.Add to Icons.Outlined.Add,
    Icons.Filled.CheckCircle to Icons.Outlined.CheckCircle,
    Icons.Filled.Close to Icons.Outlined.Close,
    Icons.Filled.ContentCopy to Icons.Outlined.ContentCopy,
    Icons.Filled.Delete to Icons.Outlined.Delete,
    Icons.Filled.Description to Icons.Outlined.Description,
    Icons.Filled.Edit to Icons.Outlined.Edit,
    Icons.Filled.Folder to Icons.Outlined.Folder,
    Icons.Filled.KeyboardArrowDown to Icons.Outlined.KeyboardArrowDown,
    Icons.Filled.KeyboardArrowUp to Icons.Outlined.KeyboardArrowUp,
    Icons.Filled.Language to Icons.Outlined.Language,
    Icons.Filled.Lightbulb to Icons.Outlined.Lightbulb,
    Icons.Filled.PlayArrow to Icons.Outlined.PlayArrow,
    Icons.Filled.Psychology to Icons.Outlined.Psychology,
    Icons.Filled.Refresh to Icons.Outlined.Refresh,
    Icons.Filled.Search to Icons.Outlined.Search,
    Icons.Filled.Terminal to Icons.Outlined.Terminal,
)

/**
 * 动态取色 ON → Outlined（M3 Material Symbols 风格）
 * 动态取色 OFF → Default/Filled（Apple SF Symbols 风格）
 *
 * 非 Composable：传 useRounded=true 时匹配 Outlined 对等体，否则原样返回。
 * Composable 调用处用 m3Icon(MaterialTheme.colorScheme, icon) 自动读 LocalUseRoundedIcons。
 */
fun m3Icon(icon: ImageVector, useRounded: Boolean): ImageVector {
    if (!useRounded) return icon
    return m3IconMapping[icon] ?: icon
}

/** Composable 便捷版：自动读 LocalUseRoundedIcons.current 传 bool。 */
@Composable
@ReadOnlyComposable
fun m3Icon(icon: ImageVector): ImageVector = m3Icon(icon, LocalUseRoundedIcons.current)