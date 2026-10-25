package com.openminis.app.ui.chat

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
internal fun RenderMdAudio(block: MdBlock.Audio) {
    val context = LocalContext.current
    val colors = currentMdColors()
    val sessionId = LocalMarkdownSessionId.current
    val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
    val filename = remember(block.url) { filenameFromMdUrl(block.url) }

    val player = remember(file?.absolutePath) {
        if (file == null) null else try {
            MediaPlayer().apply { setDataSource(file.absolutePath); prepare() }
        } catch (t: Throwable) {
            android.util.Log.w("MdStream", "audio prepare failed: ${t.message}")
            null
        }
    }
    DisposableEffect(player) {
        onDispose { try { player?.release() } catch (_: Throwable) {} }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    val durationMs = player?.duration ?: 0

    LaunchedEffect(isPlaying) {
        while (isPlaying && player != null) {
            positionMs = try { player.currentPosition } catch (_: Throwable) { 0 }
            if (!player.isPlaying) { isPlaying = false; break }
            delay(200)
        }
    }
    DisposableEffect(player) {
        player?.setOnCompletionListener {
            isPlaying = false
            positionMs = 0
            try { player.seekTo(0) } catch (_: Throwable) {}
        }
        onDispose { try { player?.setOnCompletionListener(null) } catch (_: Throwable) {} }
    }

    val tint = colors.link
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.inlineCodeBg)
            .border(0.5.dp, colors.tableBorder, RoundedCornerShape(10.dp))
            .clickable(enabled = file != null) {
                if (player == null) {
                    file?.let { openMdMediaExternally(context, it, "audio/*") }
                } else {
                    if (isPlaying) { try { player.pause() } catch (_: Throwable) {} ; isPlaying = false }
                    else { try { player.start(); isPlaying = true } catch (_: Throwable) {} }
                }
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Audiotrack,
            contentDescription = null,
            tint = colors.blockquote,
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            MdText(
                text = AnnotatedString(block.alt.ifEmpty { filename }),
                fontSize = 13.sp,
                color = colors.text,
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
                MdText(
                    text = AnnotatedString("${formatMdMediaMs(positionMs)} / ${formatMdMediaMs(durationMs)}"),
                    fontSize = 11.sp,
                    color = colors.blockquote,
                )
            }
        }
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
    }
}

