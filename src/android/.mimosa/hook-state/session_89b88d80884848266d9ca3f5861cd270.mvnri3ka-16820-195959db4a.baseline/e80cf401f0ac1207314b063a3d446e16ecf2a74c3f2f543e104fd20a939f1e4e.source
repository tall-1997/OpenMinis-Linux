package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.settings.LocalSoulModelIdentity
import com.openminis.app.ui.settings.SoulIconGlyph
import com.openminis.app.ui.settings.SoulModelIdentity

/**
 * [T-soul-avatar-model-icon] 聊天气泡头的 Soul 头像。
 *
 * 单独成文件：ChatAssistantMessageUI 已到架构门禁的行数 ceiling，一行都加不了。
 *
 * 「模型图标」模式需要知道当前会话运行的是哪个模型 —— 用
 * [LocalSoulModelIdentity] 注入，渲染器本身只管画。
 */
@Composable
internal fun SoulChatAvatar(
    soulIcon: String,
    speakerVendor: String?,
    namedSpeaker: String?,
    sparkleGradient: Brush,
) {
    val modelIdentity = speakerVendor?.takeIf { it.isNotBlank() }
        ?.let { SoulModelIdentity(brandSlug = it, displayName = namedSpeaker.orEmpty()) }
    CompositionLocalProvider(LocalSoulModelIdentity provides modelIdentity) {
        SoulIconGlyph(
            icon = soulIcon,
            sizeDp = 18.dp,
            emojiSp = 15.sp,
            sparkleTint = sparkleGradient,
        )
    }
}
