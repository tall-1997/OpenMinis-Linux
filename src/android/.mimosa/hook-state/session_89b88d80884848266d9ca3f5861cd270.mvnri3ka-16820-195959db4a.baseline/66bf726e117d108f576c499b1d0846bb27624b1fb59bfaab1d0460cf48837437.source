package com.openminis.app.ui.chat

import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Text composable that draws rounded-rect backgrounds for inline code spans. */
@Composable
internal fun MdText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = BaseFontSize,
    lineHeight: TextUnit = BaseLineHeight,
    fontWeight: FontWeight? = null,
    color: Color = currentMdColors().text,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    inlineContent: Map<String, androidx.compose.foundation.text.InlineTextContent> = emptyMap(),
    /**
     * Marks this text as a self-contained selection unit — set by table cells
     * so a long-press grabs exactly the cell. See [TextShard.isAtomicUnit].
     */
    isAtomicSelectionUnit: Boolean = false,
) {
    var layoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    // MinisTextKit registration: when this MdText is inside a markdown
    // fragment that supplied a shard id (LocalShardId) AND a controller
    // (LocalMinisSelectionController), publish a TextShard so the controller
    // can hit-test, highlight, and copy through this text node.
    // Use a single-cell array (no snapshot state) — coordinatesProvider's
    // closure reads the current value lazily, so this doesn't need to
    // invalidate any composable when the layout coords change. The
    // previous mutableStateOf wrapper turned every onGloballyPositioned
    // pass into a state write, forcing this MdText to recompose on every
    // scroll frame even when no selection was active — measurable
    // contributor to scroll jank.
    val layoutCoordinatesHolder = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val baseShardId = LocalShardId.current
    // [T-android-markdown-longtext-selection-broken] Disambiguate this MdText
    // from its siblings within the same fragment so each registers a distinct
    // TextShard (the registry is keyed by id; same-id registrations overwrite
    // each other, leaving only the last text node selectable). The allocator
    // assigns a stable per-slot index on first composition; we suffix it onto
    // the fragment's base shardId. Falls back to the bare base id when no
    // allocator is in scope (non-chat callers that render a single MdText).
    val allocator = LocalShardSubIndexAllocator.current
    val subIndex = remember(baseShardId) { allocator?.next() ?: 0 }
    val shardId = remember(baseShardId, subIndex) {
        baseShardId?.let {
            if (allocator == null) it
            else it.copy(shardId = "${it.shardId}#$subIndex")
        }
    }
    val selectionController = LocalMinisSelectionController.current
    val currentShard = remember(shardId, layoutResult, text, isAtomicSelectionUnit) {
        val sid = shardId
        val result = layoutResult
        if (sid == null || result == null) null else buildTextShard(
            id = sid,
            plainText = text.text,
            layoutResult = result,
            coordinatesProvider = { layoutCoordinatesHolder[0] },
            isAtomicUnit = isAtomicSelectionUnit,
        )
    }
    RegisterSelectionShard(currentShard)
    val selectionHighlightColor = currentSelectionHighlightColor()
    val selectionState = selectionController?.selection
    val cornerPx = with(androidx.compose.ui.platform.LocalDensity.current) { InlineCodeCornerRadius.toPx() }
    // Inset each inline-code rect: more on the top because the line box has extra leading
    // above the glyphs (font metrics ascent > visual cap-height), so an even inset would
    // visually look top-heavy. Larger top inset also keeps a clear gap when an inline-code
    // span wraps across two adjacent lines.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inlineCodeTopInsetPx = with(density) { 4.5.dp.toPx() }
    val inlineCodeBottomInsetPx = with(density) { 1.5.dp.toPx() }
    val inlineCodeBg = currentMdColors().inlineCodeBg
    val urlClickHandler = LocalMarkdownUrlClickHandler.current
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val hasUrlAnnotation = remember(text) { text.getStringAnnotations("url", 0, text.length).isNotEmpty() }
    val hasInlineCodeAnnotation = remember(text) { text.getStringAnnotations("inline_code", 0, text.length).isNotEmpty() }
    val translateInk = LocalTranslateInk.current
    val translateWidth = remember { intArrayOf(0) }
    val translateToken = remember(translateInk) { translateInk?.alloc() ?: -1 }
    val translateModifier = if (translateInk != null) {
        Modifier.layout { measurable, constraints ->
            translateWidth[0] = constraints.maxWidth
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    } else {
        Modifier
    }

    // [T-android-stream-fade] When this MdText is the streaming last block,
    // overlay a fade-in alpha on each freshly-appended word range. Off by
    // default (LocalAppendOnlyFade=false) so cold-loaded history and
    // completed messages render fully opaque without per-frame work.
    val fadeEnabled = LocalAppendOnlyFade.current
    val fadeController = if (fadeEnabled) rememberFadeController() else null
    if (fadeController != null) {
        // Ingest synchronously during composition (not in a LaunchedEffect):
        // the moment a recomposition delivers grown text, slice the new
        // suffix into fade ranges so the SAME frame renders them at α=0.
        // Deferring to a LaunchedEffect committed the opaque text first,
        // making the fade invisible. ingest() is a cheap prefix-diff and
        // no-ops when text is unchanged, so calling it every compose is safe.
        fadeController.ingest(text.text)
        FadeFrameDriver(fadeController)
    }
    // overlay() reads the SnapshotStateMap of alphas; tick() writes it each
    // frame, so this expression re-runs (recomposing only THIS MdText) on
    // every animation frame. When no ranges are active overlay() returns the
    // base text unchanged.
    val effectiveText = fadeController?.overlay(text, color) ?: text
    val tapModifier = if (hasUrlAnnotation || hasInlineCodeAnnotation) {
        Modifier.pointerInput(text) {
            detectTapGestures { pos ->
                val result = layoutResult ?: return@detectTapGestures
                val offset = result.getOffsetForPosition(pos)
                // URL wins over inline code if both annotations cover this offset.
                val urlAnn = if (urlClickHandler != null) {
                    text.getStringAnnotations("url", offset, offset).firstOrNull()
                } else null
                if (urlAnn != null) {
                    urlClickHandler?.invoke(urlAnn.item)
                    return@detectTapGestures
                }
                val codeAnn = text.getStringAnnotations("inline_code", offset, offset).firstOrNull()
                if (codeAnn != null) {
                    val snippet = text.text.substring(codeAnn.start, codeAnn.end)
                    if (snippet.isNotEmpty()) {
                        clipboardManager.setText(AnnotatedString(snippet))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val preview = if (snippet.length > 40) snippet.take(37) + "…" else snippet
                        Toast.makeText(context, "Copied: $preview", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    } else Modifier
    Text(
        text = effectiveText,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inlineContent,
        onTextLayout = { result ->
            layoutResult = result
            if (translateInk != null && translateToken >= 0) {
                translateInk.reportText(translateToken, translateWidth[0], result)
            }
        },
        modifier = modifier
            .then(translateModifier)
            .then(tapModifier)
            .onGloballyPositioned { layoutCoordinatesHolder[0] = it }
            .drawBehind {
                // MinisTextKit selection highlight (drawn UNDER the glyphs).
                val result0 = layoutResult
                val shardId0 = shardId
                val sel = selectionState?.value
                if (result0 != null && shardId0 != null && sel != null) {
                    drawSelectionForShard(
                        shardId = shardId0,
                        result = result0,
                        selection = sel,
                        controller = selectionController,
                        color = selectionHighlightColor,
                    )
                }
            }
            .drawBehind {
            val result = layoutResult ?: return@drawBehind
            // Guard against stale layout during streaming: the AnnotatedString `text`
            // in the closure can be one recomposition ahead of the laid-out text in
            // `result`, and maxLines can clip the tail. Clamp all offsets/line indices
            // to the layout's actual extents before calling get*Line* APIs — otherwise
            // getLineStart(lineCount) throws IllegalArgumentException and crashes the
            // draw phase (see #StreamingMd crash on Pixel).
            val laidOutText = result.layoutInput.text.text
            val maxOffset = laidOutText.length
            val lineCount = result.lineCount
            if (maxOffset == 0 || lineCount == 0) return@drawBehind
            val annotations = text.getStringAnnotations("inline_code", 0, text.length)
            for (ann in annotations) {
                val startOffset = ann.start.coerceIn(0, maxOffset)
                val endOffset = ann.end.coerceIn(0, maxOffset)
                if (endOffset <= startOffset) continue
                val startLine = result.getLineForOffset(startOffset).coerceIn(0, lineCount - 1)
                val endLine = result.getLineForOffset(endOffset - 1).coerceIn(0, lineCount - 1)
                if (endLine < startLine) {
                    android.util.Log.d(
                        "StreamingMd",
                        "skip inline_code: endLine<startLine annStart=${ann.start} annEnd=${ann.end} " +
                            "clamped=[$startOffset,$endOffset) lineCount=$lineCount maxOffset=$maxOffset"
                    )
                    continue
                }
                for (line in startLine..endLine) {
                    val lineStart = if (line == startLine) startOffset else result.getLineStart(line)
                    val lineEnd = if (line == endLine) endOffset else result.getLineEnd(line)
                    if (lineEnd <= lineStart) continue
                    // T299: walk per-character via getBoundingBox and take
                    // min(left)/max(right) directly. T265 used
                    // getPathForRange(lineStart, lineEnd).getBounds() which
                    // was correct for bidi-pure runs but regressed when an
                    // inline code span itself contains an internal space and
                    // sits inside CJK prose (e.g. prose like "put it next to
                    // `Hermes Agent notes.md` and `Minis tutorial.md`"). The
                    // path returned for that range can include zero-width
                    // sub-paths at run boundaries; getBounds()'s union then
                    // expands left to a coordinate from a sibling run, which
                    // ends up painted behind the surrounding prose instead
                    // of the code. Per-character boxes are immune to that
                    // because each box is a single character's tight
                    // rectangle. iOS uses fillBackgroundRectArray which has
                    // the same per-glyph guarantee.
                    var left = Float.POSITIVE_INFINITY
                    var right = Float.NEGATIVE_INFINITY
                    for (offset in lineStart until lineEnd) {
                        val box = result.getBoundingBox(offset)
                        // getBoundingBox returns a 0-width rect for offsets
                        // that fall on a soft line break; skip those so the
                        // accumulator doesn't pick up a stray edge.
                        if (box.width <= 0f) continue
                        if (box.left < left) left = box.left
                        if (box.right > right) right = box.right
                    }
                    if (!left.isFinite() || right <= left) continue
                    val top = result.getLineTop(line) + inlineCodeTopInsetPx
                    val bottom = result.getLineBottom(line) - inlineCodeBottomInsetPx
                    drawRoundRect(
                        color = inlineCodeBg,
                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
                    )
                }
            }
        },
    )
}

