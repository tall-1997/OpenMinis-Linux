package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.theme.ChatColors

/**
 * The 32×4dp indicator pill shared by every sheet drag handle in the app.
 *
 * Extracted verbatim from the four copies that used to live in
 * ChatModelPickerSheet, UnifiedModelPickerSheet, GoalSheet and
 * StandardChatSheet (CompactDragHandle). The default tint is
 * [ChatColors.secondaryText] at 40% alpha — what the pickers and
 * StandardChatSheet used. GoalSheet passed the M3 token
 * (onSurfaceVariant at the same alpha) instead; the two resolve to the same
 * color under the dynamic palette but differ under the static ones, so the
 * color stays a parameter rather than being silently unified.
 */
@Composable
fun MinisSheetDragPill(
    modifier: Modifier = Modifier,
    color: Color = ChatColors.secondaryText.copy(alpha = 0.4f),
) {
    Box(
        modifier = modifier
            .width(32.dp)
            .height(4.dp)
            .background(
                color = color,
                shape = RoundedCornerShape(2.dp),
            ),
    )
}

/**
 * Slim replacement for [androidx.compose.material3.BottomSheetDefaults.DragHandle]:
 * [MinisSheetDragPill] with 6dp top / 4dp bottom, so the title sits flush with
 * the indicator instead of the Material default's ~44dp whitespace gap above
 * it. Match the slim drag handle used by StandardChatSheet.
 */
@Composable
fun MinisSheetDragHandle(
    modifier: Modifier = Modifier,
    color: Color = ChatColors.secondaryText.copy(alpha = 0.4f),
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        MinisSheetDragPill(color = color)
    }
}
