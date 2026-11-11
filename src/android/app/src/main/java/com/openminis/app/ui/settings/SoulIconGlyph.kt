package com.openminis.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.openminis.app.agent.SoulIcon
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.ui.theme.m3Icon

/**
 * [T-android-soul-custom-icon] Shared identity-avatar renderer used by the
 * Settings preview, the chat bubble header / split empty state, and the
 * session list. Extracted from SoulSettingsScreen.kt to keep that screen
 * under its architecture line ceiling.
 *
 * [GH#299] `icon` can be an emoji, the model sentinel, a legacy inline data
 * URI, or the migrated `file:soul-icons/soul.png` reference. Image values that
 * fail to decode (missing file, over-cap legacy URI) fall back to the default
 * sparkle instead of being printed as text.
 */
@Composable
internal fun SoulIconGlyph(
    icon: String,
    sizeDp: Dp,
    emojiSp: TextUnit,
    sparkleTint: Brush? = null,
) {
    val context = LocalContext.current
    val bitmap = remember(icon, context) { SoulIcon.decode(icon, context) }
    val textGlyph =
        if (SoulIcon.isFileRef(icon) || SoulIcon.isInlineImageDataUri(icon)) "" else icon
    when {
        // Rounded rectangle, NOT a circle: at 18dp in the chat header a circle
        // eats the corners of a small avatar. Clipping here is also what makes
        // an opaque image acceptable at all — the refusal that used to guard
        // against "opaque rectangle reads as a broken tile" was a presentation
        // concern, and this is the presentation fix.
        bitmap != null -> Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(sizeDp)
                .clip(RoundedCornerShape(sizeDp * SoulIcon.CORNER_RADIUS_FRACTION)),
        )
        // [T-soul-avatar-model-icon] 哨兵判断必须在 isNotEmpty() 之前，否则被当文字渲染。
        SoulIcon.isModelIcon(icon) -> SoulModelIconAvatar(sizeDp = sizeDp, emojiSp = emojiSp)
        textGlyph.isNotEmpty() -> Text(text = textGlyph, fontSize = emojiSp)
        // Unset: byte-for-byte the previous sparkle, so a user who never
        // touches this sees no visual change at all.
        sparkleTint != null -> Icon(
            imageVector = m3Icon(Icons.Filled.AutoAwesome),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(sizeDp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(brush = sparkleTint, blendMode = BlendMode.SrcIn)
                },
        )
        else -> Text(text = SoulMetadata.DISPLAY_EMOJI, fontSize = emojiSp)
    }
}
