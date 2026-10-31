package com.openminis.app.cards

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.DisplayBitmapLimits
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [G-batch tool result cards] Compose renderers for the derived cards.
 *
 * Placement: directly below the tool row the user tapped (the detail sheet's
 * per-tool content area), rendered by [ToolCardSection]. Everything here is
 * user-channel only — nothing in this file ever feeds back into the text the
 * model reads; the undo action re-executes the ORIGINAL tool locally.
 *
 * Colour discipline: palette comes exclusively from [ChatColors] and the
 * `isDark` flag on it (the in-app theme override wins over the system
 * setting). The system dark-mode API is deliberately not used anywhere in
 * this package.
 *
 * i18n: visible labels come from the card_* res strings (all 17 locales).
 */
private const val TAG = "ToolCards"

@Composable
fun ToolCardSection(
    toolName: String,
    argsJson: String,
    resultText: String,
    callId: String? = null,
    imagePath: String? = null,
) {
    // Deterministic derivation, remembered per tool call so scrolling the
    // detail sheet does not re-parse the result text every recomposition.
    val card = remember(toolName, argsJson, resultText, imagePath) {
        CardDeriver.derive(toolName, argsJson, resultText, imagePath)
    } ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ChatColors.toolBg)
            .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        when (card) {
            is ToolCard.Facts -> FactsCardBody(card)
            is ToolCard.ListCard -> ListCardBody(card)
            is ToolCard.Media -> MediaCardBody(card)
            is ToolCard.FileCard -> FileCardBody(card)
            is ToolCard.EventCard -> EventCardBody(card)
            is ToolCard.Change -> ChangeCardBody(card)
            is ToolCard.Link -> LinkCardBody(card)
        }
        Spacer(modifier = Modifier.height(2.dp))
        CardActionRow(card, callId)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-type bodies
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FactsCardBody(card: ToolCard.Facts) {
    CardTitle(card.title)
    card.highlight?.let {
        Spacer(Modifier.height(2.dp))
        Text(
            text = it,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = ChatColors.link,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.height(4.dp))
    card.rows.forEach { row ->
        Row(modifier = Modifier.padding(vertical = 1.dp)) {
            Text(
                text = row.label,
                fontSize = 12.sp,
                color = ChatColors.secondaryText,
                modifier = Modifier.width(110.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.value,
                fontSize = 12.sp,
                color = ChatColors.primaryText,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ListCardBody(card: ToolCard.ListCard) {
    CardTitle(card.title, trailing = card.total.toString())
    Spacer(Modifier.height(4.dp))
    card.items.forEach { item ->
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            Text(
                text = item.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.primaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.let {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    color = ChatColors.tertiaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val hidden = card.total - card.items.size
    if (hidden > 0) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.card_list_more, hidden),
            fontSize = 11.sp,
            color = ChatColors.tertiaryText,
        )
    }
}

@Composable
private fun MediaCardBody(card: ToolCard.Media) {
    CardTitle(stringResource(R.string.card_media_title))
    val hostFile = remember(card.path) { resolveHostFile(card.path) }
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        card.path,
    ) {
        value = withContext(Dispatchers.IO) {
            val f = hostFile ?: return@withContext null
            if (!f.exists()) null
            else DisplayBitmapLimits.decodeFileForDisplay(f.absolutePath)
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Spacer(Modifier.height(6.dp))
        val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = card.path.substringAfterLast('/'),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(8.dp))
                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(8.dp)),
        )
    } else {
        FileMetaRow(name = card.path.substringAfterLast('/'), sub = card.path)
    }
}

@Composable
private fun FileCardBody(card: ToolCard.FileCard) {
    CardTitle(
        if (card.isNew) stringResource(R.string.card_file_created)
        else stringResource(R.string.card_file_title),
    )
    Spacer(Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = ChatColors.secondaryText,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = card.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = ChatColors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        card.sizeBytes?.let { size ->
            Spacer(Modifier.width(6.dp))
            Text(
                text = formatBytes(size),
                fontSize = 11.sp,
                color = ChatColors.tertiaryText,
            )
        }
    }
    Text(
        text = card.path,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = ChatColors.tertiaryText,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun EventCardBody(card: ToolCard.EventCard) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = null,
            tint = ChatColors.link,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = card.timeText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = ChatColors.primaryText,
        )
        card.repeat?.let {
            Spacer(Modifier.width(6.dp))
            Text(
                text = it,
                fontSize = 11.sp,
                color = ChatColors.secondaryText,
            )
        }
    }
    Text(
        text = card.title,
        fontSize = 13.sp,
        color = ChatColors.secondaryText,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun ChangeCardBody(card: ToolCard.Change) {
    CardTitle(card.label)
    Spacer(Modifier.height(3.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        card.oldText?.let { old ->
            Text(
                text = old,
                fontSize = 13.sp,
                color = ChatColors.tertiaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = "→", fontSize = 13.sp, color = ChatColors.tertiaryText)
        }
        Text(
            text = card.newText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = ChatColors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LinkCardBody(card: ToolCard.Link) {
    CardTitle(card.domain ?: stringResource(R.string.card_link_title))
    Spacer(Modifier.height(2.dp))
    Text(
        text = card.title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = ChatColors.primaryText,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = card.url,
        fontSize = 11.sp,
        color = ChatColors.link,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Actions — fixed set (open / copy / share / undo), at most 3 per card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CardActionRow(card: ToolCard, callId: String?) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // Undo state: mirrored into the ledger so it survives recomposition and
    // sheet re-opens. Recorded lazily on first composition of an undoable card.
    var undone by remember(callId, card) {
        mutableStateOf(callId != null && UndoLedger.isUndone(callId))
    }
    var undoInFlight by remember(callId) { mutableStateOf(false) }
    if (card is ToolCard.Change && card.undoable && callId != null && card.undoArgsJson != null) {
        LaunchedEffect(callId, card.undoArgsJson) {
            UndoLedger.record(callId, card.tool ?: "", card.undoArgsJson)
        }
    }

    val copyText = remember(card) { card.copyText() }
    val openable = card is ToolCard.Link || card is ToolCard.Media || card is ToolCard.FileCard
    val canUndo = card is ToolCard.Change && card.undoable && card.tool != null &&
        card.undoArgsJson != null && !undone &&
        (callId == null || UndoLedger.undoable(callId))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1) undo first — it is the one action that changes device state and
        //    the reason a Change card exists; never exceed 3 visible actions.
        if (canUndo) {
            CardActionButton(
                label = if (undoInFlight) "…" else stringResource(R.string.card_action_undo),
                icon = Icons.AutoMirrored.Filled.Undo,
                tint = ChatColors.link,
            ) {
                val c = card as ToolCard.Change
                undoInFlight = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            com.openminis.app.device.DeviceTools.execute(
                                c.tool!!,
                                c.undoArgsJson!!,
                                null, // activeSessionId: no device tool scopes its result by session
                                context,
                            )
                        }.getOrElse { "Error: undo failed: ${it.message}" }
                    }
                    undoInFlight = false
                    if (result.startsWith("Error")) {
                        AppLogger.warning(TAG, "undo failed for ${c.tool}: $result")
                        Toast.makeText(context, "Undo failed", Toast.LENGTH_SHORT).show()
                    } else {
                        callId?.let { UndoLedger.markUndone(it) }
                        undone = true
                    }
                }
            }
        }
        if (openable) {
            CardActionButton(
                label = stringResource(R.string.card_action_open),
                icon = Icons.Default.Launch,
                tint = ChatColors.secondaryText,
            ) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { openCardTarget(context, card) }
                    if (!ok) Toast.makeText(context, "Nothing can open this", Toast.LENGTH_SHORT).show()
                }
            }
        }
        CardActionButton(
            label = stringResource(R.string.card_action_copy),
            icon = Icons.Default.ContentCopy,
            tint = ChatColors.secondaryText,
        ) {
            clipboard.setText(AnnotatedString(copyText))
        }
        if (card is ToolCard.Link || card is ToolCard.Media || card is ToolCard.FileCard) {
            CardActionButton(
                label = stringResource(R.string.card_action_share),
                icon = Icons.Default.Share,
                tint = ChatColors.secondaryText,
            ) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { shareCard(context, card) }
                    if (!ok) Toast.makeText(context, "Share failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (undone) {
            Text(
                text = stringResource(R.string.card_action_undone),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.tertiaryText,
            )
        }
    }
}

@Composable
private fun CardActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
        )
    }
}

@Composable
private fun CardTitle(title: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = ChatColors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.let {
            Spacer(Modifier.width(6.dp))
            Text(
                text = it,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.tertiaryText,
            )
        }
    }
}

@Composable
private fun FileMetaRow(name: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = ChatColors.secondaryText,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = name,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sub,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.tertiaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Non-composable helpers (Android-touching, best-effort, never throw)
// ─────────────────────────────────────────────────────────────────────────────

/** What the Copy action puts on the clipboard, per card type. */
internal fun ToolCard.copyText(): String = when (this) {
    is ToolCard.Facts -> (highlight?.let { "$it\n" } ?: "") +
        rows.joinToString("\n") { "${it.label}: ${it.value}" }
    is ToolCard.ListCard -> items.joinToString("\n") { item ->
        item.subtitle?.let { sub -> "${item.title} $sub" } ?: item.title
    }
    is ToolCard.Media -> path
    is ToolCard.FileCard -> path
    is ToolCard.EventCard -> buildString {
        append(title)
        append(" · ").append(timeText)
        repeat?.let { append(" · ").append(it) }
    }
    is ToolCard.Change -> "${label}: ${oldText?.let { old -> "$old → " } ?: ""}$newText"
    is ToolCard.Link -> "$title $url"
}

/** Guest (`/var/minis/...`) or host path → an existing host [java.io.File], or null. */
private fun resolveHostFile(path: String): java.io.File? = runCatching {
    val direct = java.io.File(path)
    if (direct.exists()) return@runCatching direct
    com.openminis.app.sandbox.PRootKernel.resolveHostPath(path)?.takeIf { it.exists() }
}.getOrNull()

private fun contentUriFor(context: android.content.Context, file: java.io.File): Uri? = runCatching {
    val authority = context.packageName + ".fileprovider"
    try {
        androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    } catch (_: IllegalArgumentException) {
        // The file sits outside every declared FileProvider root (e.g. the
        // browser screenshot cache dir). Copy into the declared cache share
        // root — the T207 pattern — and hand out a URI for the copy.
        val staged = java.io.File(context.cacheDir, "share").also { it.mkdirs() }
        val copy = java.io.File(staged, file.name)
        file.inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
        androidx.core.content.FileProvider.getUriForFile(context, authority, copy)
    }
}.getOrNull()

private fun mimeFor(file: java.io.File, fallback: String?): String =
    fallback ?: android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

/** Open the card's target (URL / file via FileProvider). Returns success. */
private fun openCardTarget(context: android.content.Context, card: ToolCard): Boolean = try {
    when (card) {
        is ToolCard.Link -> {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(card.url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }
        is ToolCard.Media, is ToolCard.FileCard -> {
            val path = (card as? ToolCard.Media)?.path ?: (card as ToolCard.FileCard).path
            val file = resolveHostFile(path)
            val uri = file?.let { contentUriFor(context, it) }
            if (file == null || uri == null) {
                false
            } else {
                val mime = (card as? ToolCard.Media)?.mimeType
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mimeFor(file, mime))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }
        }
        else -> false
    }
} catch (t: Throwable) {
    AppLogger.warning(TAG, "open failed: ${t.message}")
    false
}

/** Share the card's content (text or file stream). Returns success. */
private fun shareCard(context: android.content.Context, card: ToolCard): Boolean = try {
    val intent: Intent = when (card) {
        is ToolCard.Media, is ToolCard.FileCard -> {
            val path = (card as? ToolCard.Media)?.path ?: (card as ToolCard.FileCard).path
            val file = resolveHostFile(path)
            val uri = file?.let { contentUriFor(context, it) }
            if (file == null || uri == null) {
                return false
            }
            val mime = (card as? ToolCard.Media)?.mimeType
            Intent(Intent.ACTION_SEND)
                .setType(mimeFor(file, mime))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        else -> Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, card.copyText())
    }
    context.startActivity(
        Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (t: Throwable) {
    AppLogger.warning(TAG, "share failed: ${t.message}")
    false
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 20 -> String.format("%.1f MB", bytes / 1048576.0)
    bytes >= 1 shl 10 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
