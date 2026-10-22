package com.openminis.app.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.ui.chat.ChatViewModelStore
import com.openminis.app.ui.chat.ModelBrandResolver

/**
 * [T-soul-avatar-model-icon] Soul 头像的「模型图标」模式。
 *
 * 单独成文件：SoulSettingsScreen 已到架构门禁的行数 ceiling。
 *
 * 用户可以选择让头像跟随当前会话运行模型的厂商图标 —— 不存具体模型 id，
 * 换了模型头像自动跟着变。手动设置的 emoji / 图片永远优先，这个模式只在
 * 用户显式选择时写入。
 */

/** 当前会话运行中的模型身份。 */
internal data class SoulModelIdentity(
    val brandSlug: String?,
    val displayName: String,
)

/**
 * 注入当前模型身份。用 CompositionLocal 而不是逐层传参：SoulIconGlyph 在
 * 设置预览和聊天气泡头两处渲染，聊天侧调用链很长，而"当前模型"本就是环境
 * 状态，与 LocalContext 同一性质。
 */
internal val LocalSoulModelIdentity = compositionLocalOf<SoulModelIdentity?> { null }

/**
 * 渲染模型厂商图标。
 *
 * 身份来源两级：先取 [LocalSoulModelIdentity]（群聊具名发言者的厂商），
 * 没有再从 [ChatViewModelStore.foregroundViewModel] 推导前台会话当前运行
 * 的模型 —— 主助手消息不带 speakerVendor，PR #126 只接了第一级，导致
 * 主头像永远拿不到身份、退回默认 emoji。两级都拿不到时才退回默认
 * sparkle，不留空框。
 */
@Composable
internal fun SoulModelIconAvatar(sizeDp: Dp, emojiSp: TextUnit) {
    val model = LocalSoulModelIdentity.current ?: foregroundSessionModelIdentity()
    val slug = model?.brandSlug
    if (slug != null) {
        com.openminis.app.ui.chat.ModelBrandIcon(slug = slug, sizeDp = sizeDp)
    } else {
        Text(text = SoulMetadata.DISPLAY_EMOJI, fontSize = emojiSp)
    }
}

/** 前台会话当前运行模型的身份（modelName 经品牌解析器推导）。 */
@Composable
private fun foregroundSessionModelIdentity(): SoulModelIdentity? {
    val vm = ChatViewModelStore.foregroundViewModel() ?: return null
    val name by vm.modelName.collectAsState()
    if (name.isBlank()) return null
    val slug = remember(name) { ModelBrandResolver.resolve(name) } ?: return null
    return SoulModelIdentity(brandSlug = slug, displayName = name)
}
