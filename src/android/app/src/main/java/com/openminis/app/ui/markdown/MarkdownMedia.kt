package com.openminis.app.ui.markdown
import com.openminis.app.ui.theme.m3Icon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.DisplayBitmapLimits.limitDisplaySize
import com.openminis.app.util.Format
import com.openminis.app.ui.theme.LocalChatPalette
import com.openminis.app.ui.theme.ChatColors
// [T-android-markdown-no-chat-dep] (#15) The two symbols below are the ONLY
// remaining markdown→chat imports besides the run-router in
// CodeBlockRunState.kt. They cannot be sunk into this package without
// editing chat files (Wave 5): LocalMarkdownSessionId is PROVIDED by
// ChatScreen (ui/chat/ChatScreen.kt:1919) — a markdown-local CompositionLocal
// would read null there and silently regress session-scoped media resolution
// — and resolveMdMediaFile is chat's internal session-aware resolver whose
// call chain (ChatLinkResolver.minisPathCandidates / PRootKernel) is chat
// territory. Parameterizing MarkdownText(sessionId=…) is not viable either:
// this composable's chat caller (ChatSubAgentBar) is outside this wave and
// would keep rendering with the default null. Report documents the retained
// dependency.
import com.openminis.app.ui.chat.LocalMarkdownSessionId
import com.openminis.app.ui.chat.resolveMdMediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// ─── Media blocks (inline image / video / audio) ────────────────────────────

// [T-minis-decode-unify] The old local resolver (resolveMediaFile) used the
// GLOBAL bindMounts map (last-writer-wins across sessions) plus URLDecoder
// ('+' → space, no double-decode tolerance). The image/video/audio blocks
// below now share the session-aware resolveMdMediaFile with the streaming
// renderer, so every surface resolves a minis:// URL the same way a link
// TAP does.

private fun filenameFromUrl(url: String): String {
    val stripped = url.substringBefore('?')
    val last = stripped.substringAfterLast('/')
    // [T-minis-decode-unify] same decode as resolution: URLDecoder would
    // turn a literal '+' in the filename into a space in the DISPLAYED name.
    return com.openminis.app.ui.chat.ChatLinkResolver.minisPathCandidates(last).first()
}

/**
 * Share a resolved media file via the system chooser (system player, file
 * manager, etc.). Used when tapping an inline video card — simpler than
 * bundling ExoPlayer for a fullscreen experience.
 */
private fun openMediaExternally(context: Context, file: File, mime: String) {
    val authority = context.packageName + ".fileprovider"
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    } catch (_: Throwable) {
        Uri.fromFile(file)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is android.app.Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    val chooser = Intent.createChooser(intent, file.name).apply {
        if (context !is android.app.Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    try { context.startActivity(chooser) } catch (_: Throwable) { /* no handler */ }
}

// -- Image block --

/**
 * Markdown-package stand-in for chat's internal BrokenImagePlaceholder
 * (#15 of the fix wave): the placeholder is a pure leaf visual, so it is
 * replicated here instead of importing chat's copy. Visual parity — toolBg
 * fill, broken-image glyph, alt text — is kept via the shared theme palette.
 */
@Composable
private fun BrokenImagePlaceholder(alt: String?) {
    val palette = LocalChatPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .background(palette.toolBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = palette.secondaryText,
            )
            Text(
                text = alt?.takeIf { it.isNotBlank() } ?: "Image not available",
                fontSize = 12.sp,
                color = palette.secondaryText,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun MinisImageBlock(block: MarkdownParser.Block.Image) {
    val surfaceBg = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val context = LocalContext.current
    // [T-minis-decode-unify] Resolve through the SAME session-aware resolver
    // the streaming renderer uses (StreamingMarkdownRenderBlock). Raw
    // AsyncImage(model = url) routes through MinisImageFetcher → the GLOBAL
    // bindMounts map, which is last-writer-wins across sessions: on a
    // multi-session device an old message's image resolves against whichever
    // session booted its shell most recently and renders broken until the
    // user re-enters this chat. LocalMarkdownSessionId is provided by
    // ChatScreen; outside chat it is null and the global resolver answers
    // (previous behavior, e.g. the skills screen).
    val sessionId = LocalMarkdownSessionId.current
    val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
    // [T-android-canvas-large-bitmap-crash] Cap the DECODE size like the
    // streaming path: without an explicit request size Coil decodes at the
    // image's intrinsic size, and a very tall PNG (215 MB bitmap in the
    // field report) crashes RecordingCanvas from ThreadedRenderer.draw.
    val imageRequest = remember(file, block.url) {
        ImageRequest.Builder(context)
            .data(file ?: block.url)
            .limitDisplaySize()
            .build()
    }
    SubcomposeAsyncImage(
        model = imageRequest,
        contentDescription = block.alt.ifEmpty { filenameFromUrl(block.url) },
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceBg)
            .border(0.5.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp),
    ) {
        when (painter.state) {
            // T148 parity with the streaming renderer: a gone/broken file
            // shows a placeholder instead of a silent blank gap.
            is AsyncImagePainter.State.Error -> BrokenImagePlaceholder(alt = block.alt)
            else -> SubcomposeAsyncImageContent()
        }
    }
}

// -- Video block (thumbnail card + tap to open system player) --

@Composable
internal fun MinisVideoBlock(block: MarkdownParser.Block.Video) {
    val context = LocalContext.current
    // [T-minis-decode-unify] session-aware resolution, same as the image
    // block — the old local resolveMediaFile used the global bindMounts
    // resolver plus URLDecoder ('+' → space).
    val sessionId = LocalMarkdownSessionId.current
    val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
    val filename = remember(block.url) { filenameFromUrl(block.url) }
    val surfaceBg = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val captionColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Generate a thumbnail frame off the main thread via MediaMetadataRetriever.
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = file?.absolutePath) {
        val f = file ?: run {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(f.absolutePath)
                val bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                bmp
            } catch (t: Throwable) {
                AppLogger.warning("MdMedia", "video thumbnail failed: ${t.message}")
                null
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceBg)
            .border(0.5.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable {
                file?.let { openMediaExternally(context, it, "video/*") }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 280.dp),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = thumbnail
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = block.alt.ifEmpty { filename },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Icon(
                imageVector = Icons.Filled.PlayCircleFilled,
                contentDescription = "Play video",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.width(56.dp).height(56.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                tint = captionColor,
                modifier = Modifier.width(14.dp).height(14.dp),
            )
            Text(
                text = filename,
                style = MaterialTheme.typography.bodySmall,
                color = captionColor,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

// -- Audio block (inline play/pause + progress) --

/**
 * [review P2 hardcoded] Position-poll cadence while audio plays: 5 fps is
 * smooth enough for a progress bar and cheap enough for a per-tick
 * `currentPosition` binder call.
 */
private const val PLAYBACK_POSITION_POLL_MS = 200L

@Composable
internal fun MinisAudioBlock(block: MarkdownParser.Block.Audio) {
    val context = LocalContext.current
    // [T-minis-decode-unify] session-aware resolution, same as the image block.
    val sessionId = LocalMarkdownSessionId.current
    val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
    val filename = remember(block.url) { filenameFromUrl(block.url) }

    // Dedicated MediaPlayer per card. Released on DisposableEffect dispose.
    //
    // (#3) The blocking prepare() used to run inside remember — a synchronous
    // main-thread demux/decode of the whole file header on every composition
    // of an audio card. setDataSource (cheap fd open) stays in remember;
    // prepare moved to prepareAsync + OnPreparedListener in the effect below.
    // UI timing stays equivalent: play waits for [prepared], and the
    // duration/time label appear once the listener reports them.
    var prepared by remember(file?.absolutePath) { mutableStateOf(false) }
    var durationMs by remember(file?.absolutePath) { mutableStateOf(0) }
    val player = remember(file?.absolutePath) {
        if (file == null) null else try {
            MediaPlayer().apply { setDataSource(file.absolutePath) }
        } catch (_: Throwable) { null }
    }
    DisposableEffect(player) {
        player?.apply {
            setOnPreparedListener { mp ->
                durationMs = runCatching { mp.duration }.getOrDefault(0)
                prepared = true
            }
            setOnErrorListener { _, what, extra ->
                AppLogger.warning("MdMedia", "audio error what=$what extra=$extra")
                prepared = false
                true
            }
            try { prepareAsync() } catch (_: Throwable) { }
        }
        onDispose { try { player?.release() } catch (_: Throwable) {} }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }

    // Poll position while playing to drive the progress bar.
    LaunchedEffect(isPlaying) {
        while (isPlaying && player != null) {
            positionMs = try { player.currentPosition } catch (_: Throwable) { 0 }
            if (!player.isPlaying) { isPlaying = false; break }
            delay(PLAYBACK_POSITION_POLL_MS)
        }
    }
    DisposableEffect(player) {
        val listener = MediaPlayer.OnCompletionListener {
            isPlaying = false
            positionMs = 0
            try { player?.seekTo(0) } catch (_: Throwable) {}
        }
        player?.setOnCompletionListener(listener)
        onDispose { try { player?.setOnCompletionListener(null) } catch (_: Throwable) {} }
    }

    val tint = MaterialTheme.colorScheme.primary
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(0.5.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(enabled = file != null) {
                if (player == null) {
                    file?.let { openMediaExternally(context, it, "audio/*") }
                } else {
                    if (isPlaying) {
                        try { player.pause() } catch (_: Throwable) {}
                        isPlaying = false
                    } else if (prepared) {
                        // (#3) start() before the async prepare completed would
                        // throw IllegalStateException — gate on prepared.
                        try { player.start(); isPlaying = true } catch (_: Throwable) {}
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading icon
        Icon(
            imageVector = Icons.Filled.Audiotrack,
            contentDescription = null,
            tint = subtle,
            modifier = Modifier.width(18.dp).height(18.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                text = block.alt.ifEmpty { filename },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(3.dp),
                color = tint,
                trackColor = tint.copy(alpha = 0.2f),
            )
            if (durationMs > 0) {
                Text(
                    text = "${Format.formatMediaClock(positionMs)} / ${Format.formatMediaClock(durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = subtle,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = tint,
            modifier = Modifier.width(28.dp).height(28.dp),
        )
    }
}
