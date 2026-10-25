package com.openminis.app.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisSheetDragPill
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.m3Icon
import kotlinx.coroutines.launch

/**
 * Standardized half-screen modal sheet used by every popup launched from the
 * chat input "⋯" menu. Mirrors [CompactSummarySheet]: 90% screen height by
 * default, the same header row (optional leading action / centered title /
 * close button), 0.5dp separator, and a body slot that fills the rest. The
 * body stays independent — each call site supplies its own [content].
 *
 * Uses a compact custom drag handle: the Material3 default reserves ~22dp of
 * padding above and below the indicator, which produced too much whitespace
 * between the indicator and the title — this version tightens it to 6dp / 4dp.
 *
 * [heightFraction] lets a caller request a smaller detent — for example
 * [TokenUsageSheet] passes 0.5f to match iOS's `.medium` detent
 * (AIChatView.swift:508). The fraction is clamped to (0, 1] so callers can't
 * accidentally collapse the sheet to nothing.
 *
 * [closeOnStart] / [showExpandButton] are for browser-style chrome: close on
 * the left, expand-to-fullscreen on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandardChatSheet(
    title: String,
    onDismiss: () -> Unit,
    leadingAction: (@Composable () -> Unit)? = null,
    heightFraction: Float = 0.9f,
    closeOnStart: Boolean = false,
    showExpandButton: Boolean = false,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val baseFraction = heightFraction.coerceIn(0.1f, 1f)
    // Drag-to-resize state: fractionAnim is the live height (0.5..1) so the
    // sheet follows the finger; on release we snap to an anchor with a spring.
    val fractionAnim = remember { Animatable(baseFraction) }
    var expanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val sheetHeight = (configuration.screenHeightDp * fractionAnim.value).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ChatColors.background,
        dragHandle = {
            CompactDragHandle(
                onVerticalDrag = { dy ->
                    // Finger up (dy<0) grows the sheet; down shrinks it. Clamp so
                    // it can't shrink below a usable sliver while deciding.
                    val next = (fractionAnim.value - dy / screenHeightPx).coerceIn(0.5f, 1f)
                    scope.launch { fractionAnim.snapTo(next) }
                },
                onDragEnd = {
                    when {
                        // Dragged (nearly) to the top -> expand fullscreen.
                        fractionAnim.value >= 0.97f -> {
                            expanded = true
                            scope.launch { fractionAnim.animateTo(1f, spring(stiffness = Spring.StiffnessMedium)) }
                        }
                        // Released above the base detent -> collapse back to it.
                        fractionAnim.value >= baseFraction -> {
                            expanded = false
                            scope.launch { fractionAnim.animateTo(baseFraction, spring(stiffness = Spring.StiffnessMedium)) }
                        }
                        // Dragged past the base detent -> original behaviour: dismiss.
                        else -> {
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                if (!sheetState.isVisible) onDismiss()
                            }
                        }
                    }
                },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight),
        ) {
            StandardChatSheetHeader(
                title = title,
                onDismiss = onDismiss,
                leadingAction = leadingAction,
                closeOnStart = closeOnStart,
                expanded = expanded,
                onToggleExpand = if (showExpandButton) {
                    {
                        val next = !expanded
                        expanded = next
                        scope.launch {
                            fractionAnim.animateTo(
                                if (next) 1f else baseFraction,
                                spring(stiffness = Spring.StiffnessMedium),
                            )
                        }
                    }
                } else {
                    null
                },
            )
            HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator)
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

/**
 * Slim replacement for [androidx.compose.material3.BottomSheetDefaults.DragHandle].
 * Same 32×4 indicator pill, with a taller touch target (10dp top + 12dp bottom)
 * so the vertical drag is easy to grab. Drag up to expand fullscreen; drag down
 * past the base detent to dismiss (see [StandardChatSheet]).
 */
@Composable
private fun CompactDragHandle(
    onVerticalDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        onVerticalDrag(dragAmount)
                    },
                    onDragEnd = { onDragEnd() },
                )
            }
            .padding(top = 10.dp, bottom = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Same 32×4 indicator pill as every other sheet — shared
        // [MinisSheetDragPill]; the drag-gestures wrapper above stays local
        // to this sheet.
        MinisSheetDragPill()
    }
}

/**
 * Shared header row used by all chat sheets. Default: optional leading slot,
 * centered title, close on the right. [closeOnStart] moves close to the left
 * (browser chrome); [onToggleExpand] puts a fullscreen control on the right.
 * 48.dp spacers keep the title optically centered when a side is empty.
 */
@Composable
fun StandardChatSheetHeader(
    title: String,
    onDismiss: () -> Unit,
    leadingAction: (@Composable () -> Unit)? = null,
    closeOnStart: Boolean = false,
    expanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (closeOnStart) {
            SheetCloseButton(onDismiss)
            if (leadingAction != null) leadingAction()
        } else if (leadingAction != null) {
            leadingAction()
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = ChatColors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f, fill = false),
        )
        Spacer(modifier = Modifier.weight(1f))
        if (onToggleExpand != null) {
            IconButton(onClick = onToggleExpand) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    contentDescription = stringResource(
                        if (expanded) R.string.standard_sheet_collapse else R.string.standard_sheet_expand,
                    ),
                    tint = ChatColors.secondaryText,
                )
            }
        } else if (closeOnStart) {
            Spacer(modifier = Modifier.size(48.dp))
        }
        if (!closeOnStart) {
            SheetCloseButton(onDismiss)
        }
    }
}

@Composable
private fun SheetCloseButton(onDismiss: () -> Unit) {
    IconButton(onClick = onDismiss) {
        Icon(
            imageVector = m3Icon(Icons.Default.Close),
            contentDescription = stringResource(R.string.standard_sheet_close),
            tint = ChatColors.secondaryText,
        )
    }
}
