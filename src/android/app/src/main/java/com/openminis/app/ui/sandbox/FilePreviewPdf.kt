package com.openminis.app.ui.sandbox
import com.openminis.app.ui.theme.m3Icon

import com.openminis.app.R
import com.openminis.app.ui.DisplayBitmapLimits
import androidx.compose.ui.res.stringResource
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.openminis.app.ui.components.MinisCenterTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.rememberIosBounceOverscrollEffect
import com.openminis.app.ui.media.InlineAudioPlayer
import com.openminis.app.ui.media.InlineVideoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import com.openminis.app.ui.components.MinisTextButton
// ==================== PDF (native PdfRenderer) ====================

/**
 * T144: in-app PDF viewer via Android's stock [PdfRenderer].
 *
 * [T-android-pdf-lazy-render] (#1) Pages render ON DEMAND. The previous
 * implementation pre-rendered up to 50 full-width pages (1600×~2260
 * ARGB_8888 ≈ 14 MB each) into one `pages` state list inside a single
 * LaunchedEffect — a 50-page document pinned ~700 MB of bitmaps before the
 * first frame and OOM'd outright, while 100-page docs silently truncated.
 *
 * Now the opening effect only reads the page count (header parse, cheap);
 * the LazyColumn composes a [PdfPageImage] item per page, and each item
 * renders its own page the moment it scrolls into composition. Bitmaps live
 * in the item's own state and are dropped when the item leaves composition.
 *
 * Thread-affinity: PdfRenderer is NOT thread-safe. That hazard is sidestepped
 * per render — each [PdfPageImage] opens a short-lived renderer and does
 * open→render→close inside ONE `withContext(Dispatchers.IO)` block with no
 * suspension points inside, so an instance is only ever touched by a single
 * thread. Concurrent page renders each use their own instance on their own
 * file descriptor. The old 50-page cap is gone: with per-item rendering it
 * served no memory purpose (only visible pages exist), and it truncated
 * long documents.
 */
@Composable
internal fun PdfPreview(item: FileItem) {
    var pageCount by remember(item.file) { mutableStateOf<Int?>(null) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        withContext(Dispatchers.IO) {
            try {
                ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        pageCount = renderer.pageCount
                    }
                }
            } catch (e: Exception) {
                AppLogger.warning("FilePreview", "PdfRenderer failed for ${item.name}: ${e.message}")
                error = e.message ?: "Failed to render PDF"
            }
        }
    }

    when {
        error != null -> PdfOpenExternalFallback(item, error!!)
        pageCount == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.filepreview_loading_pdf), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Header-only open succeeded but the document carries zero pages.
        pageCount == 0 -> PdfOpenExternalFallback(item, stringResource(R.string.filepreview_pdf_empty))
        else -> {
            val count = pageCount!!
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(count) { idx ->
                    PdfPageImage(item.file, idx)
                }
            }
        }
    }
}

/** One LazyColumn page: renders its own page bitmap on demand (#1). */
@Composable
private fun PdfPageImage(file: File, pageIdx: Int) {
    var bitmap by remember(file.absolutePath, pageIdx) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file.absolutePath, pageIdx) { mutableStateOf(false) }

    LaunchedEffect(file.absolutePath, pageIdx) {
        val rendered = withContext(Dispatchers.IO) { renderPdfPage(file, pageIdx) }
        if (rendered != null) bitmap = rendered else failed = true
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            // Kept the "Page N" accessibility label of the old renderer.
            contentDescription = stringResource(R.string.filepreview_page_n, pageIdx + 1),
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
    } else if (failed) {
        Box(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.filepreview_page_n, pageIdx + 1),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        // Loading placeholder at roughly one screenful so fling-through
        // doesn't collapse the scroll extent under the user's finger.
        Box(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

/**
 * Render one page at the shared preview width. Self-contained: open, render,
 * close in a single blocking call — call only from Dispatchers.IO (see the
 * thread-affinity note on [PdfPreview]).
 */
private fun renderPdfPage(file: File, pageIdx: Int): Bitmap? = try {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            if (pageIdx >= renderer.pageCount) {
                null
            } else {
                renderer.openPage(pageIdx).use { page ->
                    val targetW = 1600
                    val targetH = (targetW.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }
} catch (e: Exception) {
    AppLogger.warning("FilePreview", "PdfRenderer page $pageIdx failed: ${e.message}")
    null
}
