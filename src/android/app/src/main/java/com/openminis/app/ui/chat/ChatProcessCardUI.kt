package com.openminis.app.ui.chat

// [T-cuplivo-process-card] Unified turn-process card: thinking + tool items
// share ONE lavender container with a left icon column and vertical connector
// lines, mirroring cuplivo/Kelivo's process timeline. The chat list stays
// FLAT (one LazyColumn item per block — scroll anchoring during streaming
// depends on it), so the "single card" look is painted per-item by
// [ProcessCardSegment]: first item draws the top rounded corners, last the
// bottom ones, and each segment bleeds 1dp into the LazyColumn spacedBy(2)
// gap so the background reads continuous.

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors

/** Position of a process item inside its turn's contiguous process run. */
enum class ProcessSegmentPos { Only, First, Middle, Last }

/**
 * Assign segment positions to every contiguous run of process items
 * (thinking / tool_use). Pure + generic so JVM tests can exercise the
 * run-detection without Compose or FlatChatItem.
 */
internal fun <T> assignProcessPositions(
    items: List<T>,
    isProcess: (T) -> Boolean,
    withPos: (T, ProcessSegmentPos) -> T,
): List<T> {
    val out = items.toMutableList()
    var i = 0
    while (i < out.size) {
        if (!isProcess(out[i])) {
            i++
            continue
        }
        var j = i
        while (j < out.size && isProcess(out[j])) j++
        for (k in i until j) {
            val pos = when {
                j - i == 1 -> ProcessSegmentPos.Only
                k == i -> ProcessSegmentPos.First
                k == j - 1 -> ProcessSegmentPos.Last
                else -> ProcessSegmentPos.Middle
            }
            out[k] = withPos(out[k], pos)
        }
        i = j
    }
    return out
}

// ─── Shared card metrics (wrapper + scaffold MUST agree) ────────────────────
private val CardPad = 12.dp
private val IconColW = 24.dp
private val IconSize = 20.dp
private val ItemTop = 10.dp
private val CardRadius = 20.dp
private val Bleed = 1.dp

/**
 * Paints one slice of the shared lavender process card behind [content].
 * Connector line x = CardPad + IconColW/2 — the scaffold centers its icon in
 * exactly that column, so the line threads the icon column across items.
 */
@Composable
internal fun ProcessCardSegment(
    position: ProcessSegmentPos,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // [T-cuplivo-experimental-layout] Pass-through when the experimental
    // layout is off — each block keeps drawing its own legacy card/pill.
    if (!com.openminis.app.ui.settings.experimentalChatLayoutEnabled(
            androidx.compose.ui.platform.LocalContext.current)) {
        content()
        return
    }
    val bg = ChatColors.processCardBg
    val line = ChatColors.processCardLine
    val bleedTop = position == ProcessSegmentPos.Middle || position == ProcessSegmentPos.Last
    val bleedBottom = position == ProcessSegmentPos.Middle || position == ProcessSegmentPos.First
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val bt = if (bleedTop) Bleed.toPx() else 0f
                val bb = if (bleedBottom) Bleed.toPx() else 0f
                val r = CardRadius.toPx()
                val round = CornerRadius(r, r)
                val zero = CornerRadius.Zero
                val shape = RoundRect(
                    left = 0f,
                    top = -bt,
                    right = size.width,
                    bottom = size.height + bb,
                    topLeftCornerRadius = if (bleedTop) zero else round,
                    topRightCornerRadius = if (bleedTop) zero else round,
                    bottomRightCornerRadius = if (bleedBottom) zero else round,
                    bottomLeftCornerRadius = if (bleedBottom) zero else round,
                )
                drawPath(Path().apply { addRoundRect(shape) }, color = bg)
                if (position != ProcessSegmentPos.Only) {
                    val x = (CardPad + IconColW / 2).toPx()
                    val iconTop = ItemTop.toPx()
                    val iconBottom = iconTop + IconSize.toPx()
                    val yStart = when (position) {
                        ProcessSegmentPos.First -> iconBottom + 4.dp.toPx()
                        else -> -bt
                    }
                    val yEnd = when (position) {
                        ProcessSegmentPos.Last -> iconTop - 2.dp.toPx()
                        else -> size.height + bb
                    }
                    drawLine(
                        color = line,
                        start = Offset(x, yStart),
                        end = Offset(x, yEnd),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            },
    ) { content() }
}

/**
 * One timeline item inside the process card: icon column + title + mono meta
 * + optional chevron, with an indented expandable body.
 */
@Composable
internal fun ProcessItemScaffold(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    meta: String? = null,
    chevron: ImageVector? = null,
    onHeaderClick: (() -> Unit)? = null,
    onHeaderLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    body: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ItemTop, bottom = 10.dp, start = CardPad, end = CardPad),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onHeaderClick != null || onHeaderLongClick != null) {
                        Modifier.combinedClickable(
                            onClick = { onHeaderClick?.invoke() },
                            onLongClick = onHeaderLongClick,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            Box(Modifier.width(IconColW), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(IconSize))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText.copy(alpha = 0.88f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (meta != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = meta,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ChatColors.metaText,
                    softWrap = false,
                    maxLines = 1,
                )
            }
            trailing?.invoke()
            if (chevron != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = chevron,
                    contentDescription = null,
                    tint = ChatColors.metaText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (body != null) {
            Column(modifier = Modifier.padding(start = IconColW + 8.dp, top = 6.dp)) { body() }
        }
    }
}

// ─── Inline bodies (file chip / command output) ─────────────────────────────

internal data class ToolInlineInfo(
    val pathLine: String? = null,
    val chipName: String? = null,
    val command: String? = null,
    val outputTail: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = pathLine == null && command == null
}

/**
 * Pure extraction of the cuplivo-style inline summary from a tool block:
 * file tools → `/path · N lines` + filename chip; shell tools → command line
 * + last output lines. JSON parse failures degrade to empty, never throw.
 */
internal fun toolInlineInfo(block: AssistantBlock): ToolInlineInfo {
    val name = block.toolName.lowercase()
    val isFile = name.contains("write") || name.contains("edit") ||
        name.contains("read") || name.contains("file")
    val isShell = name.contains("shell") || name.contains("exec") ||
        name.contains("bash") || name.contains("command") || name.contains("terminal")
    if (!isFile && !isShell) return ToolInlineInfo()
    var path: String? = null
    var content: String? = null
    var command: String? = null
    try {
        val o = org.json.JSONObject(block.toolArgs)
        path = listOf("path", "file_path", "filePath", "file")
            .firstNotNullOfOrNull { o.optString(it).takeIf { s -> s.isNotEmpty() } }
        content = o.optString("content").takeIf { it.isNotEmpty() }
            ?: o.optString("new_string").takeIf { it.isNotEmpty() }
        command = o.optString("command").takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        // Malformed args (streaming partial JSON) → no inline body.
    }
    if (isFile && path != null) {
        val lines = content?.split('\n')?.size ?: 0
        return ToolInlineInfo(
            pathLine = if (lines > 0) "$path · $lines lines" else path,
            chipName = path.substringAfterLast('/').takeIf { it.isNotEmpty() },
        )
    }
    if (isShell) {
        return ToolInlineInfo(
            command = command,
            outputTail = block.content.lines()
                .map { it.trimEnd() }
                .filter { it.isNotBlank() }
                .takeLast(4),
        )
    }
    return ToolInlineInfo()
}

@Composable
internal fun ProcessFileBody(info: ToolInlineInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (info.pathLine != null) {
            Text(
                text = info.pathLine,
                fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.metaText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (info.chipName != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(ChatColors.inputIconBg, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = ChatColors.metaText,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = info.chipName,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ChatColors.primaryText.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun ProcessCommandBody(info: ToolInlineInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (info.command != null) {
            Text(
                text = info.command,
                fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.primaryText.copy(alpha = 0.8f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        info.outputTail.forEach { line ->
            Text(
                text = line,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.metaText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
