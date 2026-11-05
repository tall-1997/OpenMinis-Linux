package com.openminis.app.ui.components
import com.openminis.app.ui.theme.m3Icon

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.sandbox.saveBitmapToGallery
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * One image in an [ImageGalleryViewer]. [model] is anything Coil's
 * `AsyncImage` accepts (Uri, File, String URL, etc.); [caption] is the
 * filename/alt-text shown in the bottom capsule, hidden when blank.
 */
data class ImageGalleryItem(
    val model: Any,
    val caption: String? = null,
)

/**
 * Fullscreen swipeable gallery — mirrors the iOS MessageImageGallery
 * (src/ios/Views/Chat/Media/MessageImageGallery.swift): HorizontalPager
 * over pinch-zoom/pan pages, immersive dialog chrome (system bars hidden,
 * tap toggles), bottom caption capsule, and Copy / Share / Save actions
 * bound to the currently visible page.
 *
 * Edge cases:
 *  - `items.size == 1` renders correctly (HorizontalPager with one page,
 *    no horizontal-swipe artefacts).
 *  - `startIndex` is coerced into bounds.
 *  - When a page is zoomed (`scale > 1f`), pan-pointer input consumes
 *    horizontal gestures, so the pager does not flip mid-zoom — matches
 *    iOS UIScrollView-blocks-TabView-swipe behavior.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageGalleryViewer(
    items: List<ImageGalleryItem>,
    startIndex: Int = 0,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) {
        // Defensive: don't render an empty pager — just dismiss.
        DisposableEffect(Unit) {
            onDismiss()
            onDispose { }
        }
        return
    }

    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // Hide system bars on entry, restore on exit. Same pattern as the
    // single-image FullscreenImageViewer — now the shared
    // ActivityImmersiveSystemBarsEffect in DialogWindowUtils (T169 capture/
    // restore semantics; safe here because the viewer takes over the whole
    // screen and never overlaps another flag writer).
    ActivityImmersiveSystemBarsEffect(view)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Apply immersive flags to the dialog's own window too — shared
        // DialogWindowImmersiveEffect (NO_LIMITS + hide-bars flavor).
        DialogWindowImmersiveEffect(
            darkTheme = false,
            hideBars = true,
            layoutNoLimits = true,
        )

        val pagerState = rememberPagerState(
            initialPage = startIndex.coerceIn(0, items.size - 1),
            pageCount = { items.size },
        )
        // [T-android-gallery-async-siblings] FilePreviewScreen now composes
        // this viewer with a SINGLE-ITEM list first and swaps in the full
        // sibling list once a background enumeration finishes (#18 of the
        // fix wave). initialPage was computed against the 1-item list, so a
        // tapped image that isn't alphabetically first would open on page 0
        // after the swap. One-shot: when the page count first GROWS, jump to
        // the documented startIndex — before the user has had any chance to
        // swipe, so this never fights a manual page change.
        var jumpedToStartIndex by remember { mutableStateOf(false) }
        LaunchedEffect(items.size, startIndex) {
            if (!jumpedToStartIndex && items.size > 1) {
                runCatching {
                    pagerState.scrollToPage(startIndex.coerceIn(0, items.size - 1))
                }
                jumpedToStartIndex = true
            }
        }
        var showChrome by remember { mutableStateOf(true) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            // Pager is the bottom-most surface so per-page pointer input
            // (pinch / pan / double-tap) wins over the dialog's outer
            // click-to-dismiss when applicable.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // Default behaviour: drag with at least 1 finger pages
                // unless the page composable consumes the gesture first
                // (we do that when zoomed, see below).
            ) { page ->
                GalleryPage(
                    item = items[page],
                    onTapChrome = { showChrome = !showChrome },
                )
            }

            // ── Close button ────────────────────────────────────────
            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp),
                ) {
                    Icon(
                        m3Icon(Icons.Default.Close),
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            // ── Bottom caption + actions ────────────────────────────
            val currentItem = items.getOrNull(pagerState.currentPage) ?: items[0]
            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Box(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp),
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Caption capsule — matches iOS GalleryPage caption
                        // (MessageImageGallery.swift L110-123). Hidden when
                        // the item has no caption (e.g. raw markdown image
                        // without alt text).
                        if (!currentItem.caption.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 12.dp)
                                    .background(
                                        color = Color.Black.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(14.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = currentItem.caption,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        val pillShape = RoundedCornerShape(32.dp)
                        Row(
                            modifier = Modifier
                                .background(color = Color(0xFF3A3A3C), shape = pillShape)
                                .border(
                                    width = 1.dp,
                                    color = Color.White.copy(alpha = 0.15f),
                                    shape = pillShape,
                                )
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ImageActionButton(
                                icon = Icons.Outlined.ContentCopy,
                                label = stringResource(R.string.image_action_copy),
                                onClick = { copyBitmapToClipboard(context, scope, currentItem.model) },
                            )
                            var sharing by remember { mutableStateOf(false) }
                            ImageActionButton(
                                icon = Icons.Outlined.Share,
                                label = stringResource(R.string.image_action_share),
                                onClick = onClick@{
                                    if (sharing) return@onClick
                                    sharing = true
                                    scope.launch {
                                        try {
                                            shareImage(context, currentItem.model)
                                        } finally {
                                            sharing = false
                                        }
                                    }
                                },
                            )
                            val savedToAlbumMsg = stringResource(R.string.image_saved_to_album_toast)
                            val saveFailedMsg = stringResource(R.string.image_save_failed_toast)
                            ImageActionButton(
                                icon = Icons.Outlined.Download,
                                label = stringResource(R.string.image_action_save),
                                onClick = {
                                    scope.launch {
                                        val bmp = loadBitmap(context, currentItem.model)
                                        val saved = bmp != null && saveBitmapToGallery(context, bmp)
                                        Toast.makeText(
                                            context,
                                            if (saved) savedToAlbumMsg else saveFailedMsg,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One page inside [ImageGalleryViewer]. Owns its own zoom/pan state so the
 * pager remembers per-page transform independently — flipping to the next
 * page resets the previous page's zoom when its composition leaves.
 */
@Composable
private fun GalleryPage(
    item: ImageGalleryItem,
    onTapChrome: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = item.model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                )
                // When zoomed, this pointerInput intercepts horizontal pan
                // so the parent pager doesn't change pages while the user
                // is panning around inside a magnified image. Mirrors iOS
                // UIScrollView naturally blocking the parent TabView swipe.
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f; offsetX = 0f; offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        },
                        onTap = { onTapChrome() },
                    )
                },
        )
    }
}

// The private findDialogWindowForGallery walker moved to DialogWindowUtils.kt
// (findDialogWindow) — shared with FullscreenImageViewer, InlineMediaPlayer
// and the web-preview pair.
