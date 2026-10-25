package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * Anchor geometry for [AnchorPopup]. Extracted verbatim from the inline
 * PopupPositionProvider implementations in ChatScreen.kt — only the
 * differing constants are parameterized.
 */
internal sealed interface AnchorSpec {
    /**
     * Centre the popup horizontally on the anchor and place its bottom edge
     * [gap] px above the anchor's top edge (never below y=0).
     */
    data class AboveCentered(val gap: Int) : AnchorSpec

    /**
     * Right-anchor the popup: its right edge sits [rightInset] px left of the
     * anchor's right edge and its top edge [topInset] px below the anchor's
     * top edge (never above y=0).
     */
    data class RightOfTop(val rightInset: Int, val topInset: Int) : AnchorSpec
}

/**
 * Popup shell for composer-anchored overlays (slash menu, mention picker,
 * move-to capsule). Pure move from ChatScreen.kt: position math per anchor
 * form is unchanged; properties and dismiss handling stay at the call sites.
 */
@Composable
internal fun AnchorPopup(
    anchor: AnchorSpec,
    onDismissRequest: () -> Unit,
    properties: PopupProperties,
    content: @Composable () -> Unit,
) {
    Popup(
        popupPositionProvider = remember(anchor) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: androidx.compose.ui.unit.IntRect,
                    windowSize: androidx.compose.ui.unit.IntSize,
                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                    popupContentSize: androidx.compose.ui.unit.IntSize,
                ): androidx.compose.ui.unit.IntOffset {
                    return when (anchor) {
                        is AnchorSpec.AboveCentered -> {
                            val gap = anchor.gap
                            val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                                .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                            val y = (anchorBounds.top - popupContentSize.height - gap)
                                .coerceAtLeast(0)
                            androidx.compose.ui.unit.IntOffset(x, y)
                        }
                        is AnchorSpec.RightOfTop -> {
                            val x = (anchorBounds.right - popupContentSize.width - anchor.rightInset)
                                .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                            val y = (anchorBounds.top + anchor.topInset).coerceAtLeast(0)
                            androidx.compose.ui.unit.IntOffset(x, y)
                        }
                    }
                }
            }
        },
        onDismissRequest = onDismissRequest,
        properties = properties,
        content = content,
    )
}
