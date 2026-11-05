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
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.openminis.app.ui.DisplayBitmapLimits.limitDisplaySize
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
/**
 * Renders markdown text with full formatting support.
 * Supports: headings, bold, italic, strikethrough, inline code, code blocks (with copy),
 * bullet/numbered/task lists, blockquotes, tables, thematic breaks, and links.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    /**
     * 「▶ 运行」回调：(fence 语言, 代码正文) -> Unit。
     *
     * 为 null（默认）时所有代码块都不显示运行按钮，现有调用点无需改动。
     * 是否显示按钮由 [CodeBlockRunRouter.isSupported] 判定 —— 语言不可识别
     * 就干脆不渲染，而不是给一个点了必失败的按钮。
     */
    onRunCode: ((lang: String, code: String) -> Unit)? = null,
) {
    // [T-android-markdown-async-parse] (#5) parseWithMath is O(n) over the raw
    // text but was remembered per (markdown) INSIDE composition, so every
    // markdown mutation re-parsed synchronously on the frame the state landed
    // — for streaming-sized inputs that stalled the frame, and the un-keyed
    // block loop then re-ran structural diff over every block (O(n²) in
    // practice).
    //
    // Two-phase fix:
    //  a) Short documents (< [ASYNC_PARSE_THRESHOLD_CHARS], where a full
    //     parse is single-digit ms — see MarkdownParser's BoundedText notes)
    //     still parse synchronously so the first frame never flashes empty.
    //     Longer documents parse on Dispatchers.Default and publish when
    //     ready; the initial composition shows an empty block list for that
    //     one frame instead of janking the input path.
    //  b) The block loop keys each BlockContent slot on (index, block type)
    //     — Block data classes carry List fields and are structurally
    //     unstable, so an unkeyed loop could not skip recomposition.
    var parsed by remember(markdown) {
        mutableStateOf(
            if (markdown.length < ASYNC_PARSE_THRESHOLD_CHARS) {
                MarkdownParser.parseWithMath(markdown)
            } else {
                MarkdownParser.ParseResult(emptyList(), emptyList())
            },
        )
    }
    LaunchedEffect(markdown) {
        if (markdown.length >= ASYNC_PARSE_THRESHOLD_CHARS) {
            parsed = withContext(Dispatchers.Default) { MarkdownParser.parseWithMath(markdown) }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((index, block) in parsed.blocks.withIndex()) {
            key(index, block::class) {
                BlockContent(block, color, style, parsed.mathSpans, onRunCode)
            }
        }
    }
}

/**
 * [T-android-markdown-async-parse] Documents shorter than this parse
 * synchronously (imperceptible, no first-frame flash); anything longer is
 * parsed off the main thread. 8K chars ≈ where parseWithMath's line scan +
 * math extraction stops being single-digit-ms (same order as
 * BoundedText.MAX_ICU_INPUT_CHARS).
 */
private const val ASYNC_PARSE_THRESHOLD_CHARS = 8_192

@Composable
private fun BlockContent(
    block: MarkdownParser.Block,
    color: Color,
    baseStyle: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan> = emptyList(),
    onRunCode: ((lang: String, code: String) -> Unit)? = null,
) {
    when (block) {
        is MarkdownParser.Block.Heading -> HeadingBlock(block, color, mathSpans)
        is MarkdownParser.Block.Paragraph -> ParagraphBlock(block.content, color, baseStyle, mathSpans)
        is MarkdownParser.Block.CodeBlock -> CodeBlockView(block, onRunCode)
        is MarkdownParser.Block.Blockquote -> BlockquoteView(block, color, baseStyle, mathSpans)
        is MarkdownParser.Block.BulletList -> BulletListView(block, color, baseStyle, mathSpans)
        is MarkdownParser.Block.NumberedList -> NumberedListView(block, color, baseStyle, mathSpans)
        is MarkdownParser.Block.Table -> TableView(block, color, baseStyle, mathSpans)
        is MarkdownParser.Block.MathBlock -> MathBlockView(latex = block.latex)
        is MarkdownParser.Block.ThematicBreak -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = color.copy(alpha = 0.3f),
        )
        is MarkdownParser.Block.Image -> MinisImageBlock(block)
        is MarkdownParser.Block.Video -> MinisVideoBlock(block)
        is MarkdownParser.Block.Audio -> MinisAudioBlock(block)
    }
}

// -- Heading --

@Composable
private fun HeadingBlock(
    heading: MarkdownParser.Block.Heading,
    color: Color,
    mathSpans: List<MarkdownParser.MathSpan>,
) {
    val (fontSize, fontWeight) = when (heading.level) {
        1 -> 24.sp to FontWeight.Bold
        2 -> 20.sp to FontWeight.Bold
        3 -> 18.sp to FontWeight.SemiBold
        4 -> 16.sp to FontWeight.SemiBold
        5 -> 15.sp to FontWeight.Medium
        else -> 14.sp to FontWeight.Medium
    }
    // (#4) Headings carry inline math through [MathFlowText] now — previously
    // the placeholder sentinel was stripped here and `$x^2$` inside a heading
    // silently vanished.
    MathFlowText(
        content = heading.content,
        color = color,
        style = TextStyle(fontSize = fontSize, fontWeight = fontWeight),
        mathSpans = mathSpans,
    )
}

// -- Paragraph --

@Composable
private fun ParagraphBlock(
    content: String,
    color: Color,
    style: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan> = emptyList(),
) {
    MathFlowText(content = content, color = color, style = style, mathSpans = mathSpans)
}

/** Regex matching the parser's inline-math placeholder sentinel. */
private val MATH_PLACEHOLDER_REGEX = Regex("\\uFFFC" + "MATH(\\d+)" + "\\uFFFC")

/**
 * [T-android-inline-math-flow] (#4 + #21) Core renderer for text that may
 * carry inline-math placeholders (`\uFFFCMATH<n>\uFFFC`), shared by
 * paragraphs, headings, list items, table cells and blockquote children.
 *
 * Three shapes, best available first:
 *
 *  1. No math → single [InlineContent] Text, zero extra cost.
 *  2. Math rendered AND cached → TRUE inline flow: one Text whose annotated
 *     string carries `appendInlineContent` slots at each placeholder, with
 *     [InlineTextContent]s sized to the KaTeX bitmap's exact CSS size
 *     (T206). The formula sits on the text's own line and the paragraph
 *     wraps around it naturally — this replaces the old split-Column whose
 *     fixed 2.dp gaps and forced segment breaks made text+formula read as
 *     stacked blocks (#21). PlaceholderVerticalAlign.TextCenter centers the
 *     bitmap box on the text's ascent/descent midpoint, the closest
 *     approximation to KaTeX's math axis for both simple (`x_i`) and tall
 *     (`\frac{a}{b}`) inline formulas — a tall formula grows its own line
 *     via the placeholder height instead of overlapping the next line.
 *  3. Math not yet rendered → the T208 Layer B split-Column fallback. The
 *     [KaTeXRenderView] instances shown here double as cache warmers: each
 *     settled render bumps [renderEpoch], the cache is re-read, and once
 *     every inline span is cached shape 2 takes over.
 *
 * Display math (`$$ … $$`) never reaches here — the parser promotes it to
 * Block.MathBlock, rendered centered by MathBlockView.
 */
@Composable
private fun MathFlowText(
    content: String,
    color: Color,
    style: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan>,
    modifier: Modifier = Modifier,
) {
    // Fast path: no math placeholders → original single-Text rendering.
    if (mathSpans.isEmpty() || !content.contains('￼')) {
        InlineContent(text = content, color = color, style = style, modifier = modifier)
        return
    }

    val isDark = ChatColors.isDark
    val byPlaceholder = remember(mathSpans) { mathSpans.associateBy { it.placeholder } }
    // Inline spans only — display-mode spans were promoted to Block.MathBlock
    // by MarkdownParser.restoreMath and never appear inside paragraph text.
    val inlineSpans = remember(mathSpans) { mathSpans.filter { !it.isBlock } }

    // Bump whenever an async KaTeX render settles so the cache is re-read.
    var renderEpoch by remember(mathSpans) { mutableIntStateOf(0) }
    val entries = remember(mathSpans, isDark, renderEpoch) {
        inlineSpans.mapNotNull { span ->
            KaTeXRendererCache.cache
                .get(KaTeXRendererCache.cacheKey(span.latex, false, isDark))
                ?.let { span.placeholder to it }
        }.toMap()
    }
    val allReady = inlineSpans.isNotEmpty() && inlineSpans.all { entries.containsKey(it.placeholder) }

    if (allReady) {
        // Shape 2 — true inline flow. `mathInlineTags` maps a placeholder's
        // start index to (placeholder, endExclusive) so [parseInline] can
        // emit InlineTextContent slots without styling the sentinel glyphs.
        val mathInlineTags = remember(content, mathSpans) {
            buildMap {
                MATH_PLACEHOLDER_REGEX.findAll(content).forEach { m ->
                    if (byPlaceholder.containsKey(m.value)) {
                        put(m.range.first, m.value to (m.range.last + 1))
                    }
                }
            }
        }
        val mathSlots = remember(entries, inlineSpans) {
            inlineSpans.mapNotNull { span -> entries[span.placeholder]?.let { span.placeholder to it } }
                .associate { (placeholder, entry) ->
                    placeholder to InlineTextContent(
                        Placeholder(
                            width = entry.cssWidth.sp,
                            height = entry.cssHeight.sp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        ),
                    ) {
                        // Same defensive de-clip recipe as the chat streaming
                        // renderer (T-android-math-fontscale): draw at natural
                        // CSS size, centered in the slot, scaled DOWN to fit
                        // if any residual unit drift would otherwise clip.
                        androidx.compose.foundation.layout.BoxWithConstraints(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            val naturalW = entry.cssWidth.dp
                            val naturalH = entry.cssHeight.dp
                            val fit = minOf(
                                1f,
                                if (naturalW > maxWidth) maxWidth / naturalW else 1f,
                                if (naturalH > maxHeight) maxHeight / naturalH else 1f,
                            )
                            Image(
                                bitmap = entry.bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(naturalW * fit, naturalH * fit),
                            )
                        }
                    }
                }
        }
        InlineContent(
            text = content,
            color = color,
            style = style,
            modifier = modifier,
            mathInlineTags = mathInlineTags,
            mathSlots = mathSlots,
        )
    } else {
        // Shape 3 — warming fallback (T208 Layer B split-Column). Each
        // KaTeXRenderView completes → onRendered → renderEpoch++ → cache
        // re-read → shape 2 once all spans are cached.
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            var cursor = 0
            for (match in MATH_PLACEHOLDER_REGEX.findAll(content)) {
                val span = byPlaceholder[match.value] ?: continue
                if (match.range.first > cursor) {
                    val segment = content.substring(cursor, match.range.first)
                    if (segment.isNotBlank()) {
                        InlineContent(text = segment, color = color, style = style)
                    }
                }
                KaTeXRenderView(
                    latex = span.latex,
                    displayMode = false,
                    onRendered = { renderEpoch++ },
                )
                cursor = match.range.last + 1
            }
            if (cursor < content.length) {
                val tail = content.substring(cursor)
                if (tail.isNotBlank()) {
                    InlineContent(text = tail, color = color, style = style)
                }
            }
        }
    }
}

// -- Code Block --

@Composable
private fun CodeBlockView(
    block: MarkdownParser.Block.CodeBlock,
    onRunCode: ((lang: String, code: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    // [T-android-color-contract] (#8) The teal below is HighlightPalette.Dark
    // .type (VS Code dark+ teal). ChatPalette has NO syntax-teal slot — its
    // codeBlockText is the iOS-parity green — so the literal stays as the
    // unspanned-fallback color with this pointer to the contract: new code
    // colors belong in HighlightPalette (SyntaxHighlighter.kt), not inline
    // literals. Since #16 the highlighter paints every token anyway; this
    // only shows if a future highlighter revision leaves gaps.
    val codeColor = Color(0xFF4EC9B0)
    val bgColor = MaterialTheme.colorScheme.surfaceContainerHighest
    // (#16) MarkdownText's own SyntaxHighlighter finally feeds CodeBlockView.
    // The highlighter bounds its token scan via BoundedText.MAX_ICU_INPUT_
    // CHARS and appends any overflow plain, so remembering per (code,
    // language) is safe for long blocks. Palette tracks the block background
    // luminance — dark+ colors on a light surface would be unreadable (same
    // rule CodeEditorField uses).
    val highlightPalette = remember(bgColor) {
        if (bgColor.luminance() < 0.5f) HighlightPalette.Dark else HighlightPalette.Light
    }
    val highlighted = remember(block.code, block.language, highlightPalette) {
        SyntaxHighlighter.highlight(block.code, block.language, highlightPalette)
    }
    // Button shows only when a handler exists AND the fence language is one
    // the router can actually execute. Empty / unknown fences (java, rust,
    // plain ```) render exactly as before — no button, no dead end.
    // Handler resolves from the explicit parameter first, then the
    // CompositionLocal (LocalMarkdownCodeRunner) that ChatScreen provides
    // once for the whole chat surface.
    val localRunner = LocalMarkdownCodeRunner.current
    val runnable = isRunnableCodeBlock(block.language, onRunCode != null || localRunner != null)
    // Running state is keyed by the code text itself: the VM publishes the
    // set of code blocks currently executing and ChatScreen provides it. A
    // default empty set keeps MarkdownText usable everywhere else.
    val running = if (runnable) {
        LocalCodeBlockRunState.current.contains(block.code)
    } else {
        false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor),
    ) {
        // Header with language label + copy button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language.ifEmpty { "code" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (runnable) {
                if (running) {
                    Box(
                        modifier = Modifier.size(28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    IconButton(
                        onClick = { (onRunCode ?: localRunner)?.invoke(block.language, block.code) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "运行 ${block.language} 代码",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("code", block.code))
                },
                modifier = Modifier.height(28.dp),
            ) {
                Icon(
                    m3Icon(Icons.Default.ContentCopy),
                    contentDescription = "Copy code",
                    modifier = Modifier.height(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Scrollable code content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = highlighted,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = codeColor,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

// -- Blockquote --

@Composable
private fun BlockquoteView(
    block: MarkdownParser.Block.Blockquote,
    color: Color,
    baseStyle: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan> = emptyList(),
) {
    // (#8) Orange accent rule on the leading edge — now reads the palette
    // slot (ChatColors.kt blockquoteBar: iOS systemOrange @ 50% in light,
    // systemOrange-dark variant in dark) instead of a raw 0xFFFF9500 literal,
    // per the [T-android-color-contract]: chat-adjacent surfaces follow the
    // in-app palette so they track the theme override AND the dark palette.
    // The old literal was full-opacity; the palette slot's tuned alpha is the
    // contract value.
    val barColor = LocalChatPalette.current.blockquoteBar
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    color = barColor,
                    topLeft = Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                )
            }
            .padding(start = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Inner content uses onSurfaceVariant so quoted text reads as
            // secondary, matching the iOS quote treatment.
            val quotedColor = MaterialTheme.colorScheme.onSurfaceVariant
            for (inner in block.blocks) {
                BlockContent(inner, quotedColor, baseStyle, mathSpans)
            }
        }
    }
}

// -- Bullet List --

@Composable
private fun BulletListView(
    block: MarkdownParser.Block.BulletList,
    color: Color,
    baseStyle: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan> = emptyList(),
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (item in block.items) {
            Row(modifier = Modifier.fillMaxWidth()) {
                if (item.checked != null) {
                    // Task list item
                    Icon(
                        imageVector = if (item.checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = color.copy(alpha = 0.6f),
                        modifier = Modifier
                            .padding(end = 4.dp, top = 2.dp)
                            .height(18.dp)
                            .width(18.dp),
                    )
                } else {
                    Text(
                        text = "•",
                        color = color,
                        style = baseStyle,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                // (#4) List item content goes through [MathFlowText] —
                // previously InlineContent stripped the placeholder sentinel
                // and inline math inside list items silently vanished.
                MathFlowText(
                    content = item.content,
                    color = color,
                    style = baseStyle,
                    mathSpans = mathSpans,
                    modifier = Modifier.weight(1f),
                )
            }
            // [T-list-item-block-children] Nested blocks (table / code
            // fence collected by MarkdownParser.appendContinuation)
            // render under the item instead of being printed as prose.
            // mathSpans thread through too (#4) so nested paragraphs in
            // list items keep their inline math.
            if (item.children.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item.children.forEach { child ->
                        BlockContent(child, color, baseStyle, mathSpans)
                    }
                }
            }
        }
    }
}

// -- Numbered List --

@Composable
private fun NumberedListView(
    block: MarkdownParser.Block.NumberedList,
    color: Color,
    baseStyle: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan> = emptyList(),
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((index, item) in block.items.withIndex()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${block.startNumber + index}.",
                    color = color,
                    style = baseStyle,
                    modifier = Modifier.padding(end = 8.dp),
                )
                // (#4) see BulletListView — placeholders resolve instead of
                // being stripped.
                MathFlowText(
                    content = item.content,
                    color = color,
                    style = baseStyle,
                    mathSpans = mathSpans,
                    modifier = Modifier.weight(1f),
                )
            }
            // [T-list-item-block-children] see BulletListView above.
            if (item.children.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item.children.forEach { child ->
                        BlockContent(child, color, baseStyle, mathSpans)
                    }
                }
            }
        }
    }
}

// -- Table --

@Composable
private fun TableView(
    table: MarkdownParser.Block.Table,
    color: Color,
    baseStyle: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan> = emptyList(),
) {
    val borderColor = color.copy(alpha = 0.2f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(0.5.dp, borderColor, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp)),
    ) {
        Column {
            // Header row
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .height(IntrinsicSize.Min),
            ) {
                for ((colIdx, header) in table.headers.withIndex()) {
                    val align = table.alignments.getOrElse(colIdx) { MarkdownParser.Alignment.LEFT }
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = when (align) {
                            MarkdownParser.Alignment.CENTER -> Alignment.Center
                            MarkdownParser.Alignment.RIGHT -> Alignment.CenterEnd
                            MarkdownParser.Alignment.LEFT -> Alignment.CenterStart
                        },
                    ) {
                        TableCellContent(
                            text = header,
                            color = color,
                            style = baseStyle.copy(fontWeight = FontWeight.Bold),
                            mathSpans = mathSpans,
                        )
                    }
                }
            }

            HorizontalDivider(color = borderColor)

            // Data rows
            for (row in table.rows) {
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    for ((colIdx, cell) in row.withIndex()) {
                        val align = table.alignments.getOrElse(colIdx) { MarkdownParser.Alignment.LEFT }
                        Box(
                            modifier = Modifier
                                .width(120.dp)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = when (align) {
                                MarkdownParser.Alignment.CENTER -> Alignment.Center
                                MarkdownParser.Alignment.RIGHT -> Alignment.CenterEnd
                                MarkdownParser.Alignment.LEFT -> Alignment.CenterStart
                            },
                        ) {
                            TableCellContent(
                                text = cell,
                                color = color,
                                style = baseStyle,
                                mathSpans = mathSpans,
                            )
                        }
                    }
                }
                HorizontalDivider(color = borderColor)
            }
        }
    }
}

/**
 * T208 Layer D: a table cell that resolves inline-math placeholders the
 * same way [ParagraphBlock] does. Without this, `$x^2$` inside a cell
 * would have its placeholder stripped by [InlineContent]'s cleanup pass
 * and the math would silently disappear.
 *
 * Delegates to [MathFlowText], which now upgrades cells to the true
 * inline-flow path once the formula bitmaps are cached (#21).
 */
@Composable
private fun TableCellContent(
    text: String,
    color: Color,
    style: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan>,
) {
    MathFlowText(content = text, color = color, style = style, mathSpans = mathSpans)
}

// -- Inline Content --

@Composable
private fun InlineContent(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    /**
     * [T-android-inline-math-flow] (#21) Maps a placeholder's start index to
     * (placeholder, endExclusive). When non-empty the placeholders are
     * emitted as [androidx.compose.foundation.text.appendInlineContent] slots
     * (true inline flow) instead of being stripped; [mathSlots] supplies the
     * slot definitions. Empty by default — the no-math / stripping path.
     */
    mathInlineTags: Map<Int, Pair<String, Int>> = emptyMap(),
    mathSlots: Map<String, InlineTextContent> = emptyMap(),
) {
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val inlineCodeColor = MaterialTheme.colorScheme.primary
    val linkColor = MaterialTheme.colorScheme.primary

    // Strip any leftover math placeholders (U+FFFC MATH<n> U+FFFC). The
    // MathFlowText split/warming paths resolve them when math spans are
    // threaded through, but any InlineContent call outside [MathFlowText]
    // doesn't carry the span list — collapsing the placeholder here keeps
    // the U+FFFC sentinel from leaking into the rendered glyph stream.
    // Skipped on the inline-flow path, where the placeholders are the
    // slot anchors.
    val cleaned = remember(text, mathInlineTags) {
        if (mathInlineTags.isEmpty() && text.contains('￼')) {
            text.replace(Regex("\\uFFFCMATH\\d+\\uFFFC"), "")
        } else {
            text
        }
    }

    // T136: route non-http(s) link taps (intent://, mailto:, tel:, …) through
    // BrowserExternalSchemeHandler instead of Compose's default UriHandler,
    // which fires a plain ACTION_VIEW and breaks for `intent://...#Intent;…`
    // URIs. For http(s)/about/file the handler is a no-op and we fall back to
    // a plain ACTION_VIEW to the system browser.
    val context = LocalContext.current
    val linkListener = remember(context) {
        LinkInteractionListener { link ->
            val url = (link as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
            // [T-android-user-initiated-scheme-dispatch] User tapped a link
            // in rendered markdown — dispatch any scheme.
            val handled = com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                .handle(
                    context,
                    url,
                    com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                        .Origin.USER_INITIATED,
                )
            if (handled) return@LinkInteractionListener
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    val annotated = remember(cleaned, color, linkListener, mathInlineTags) {
        parseInline(
            cleaned, color, style, inlineCodeBg, inlineCodeColor, linkColor, linkListener,
            mathInlineTags,
        )
    }

    Text(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = color),
        inlineContent = mathSlots,
    )
}

/**
 * Parse inline markdown: **bold**, *italic*, ~~strikethrough~~, `code`, [links](url)
 *
 * [mathInlineTags] (#21): start-index → (placeholder, endExclusive). At each
 * placeholder the parser emits an InlineTextContent slot instead of raw
 * sentinel glyphs, so styles never span across a formula anchor.
 */
private fun parseInline(
    text: String,
    color: Color,
    baseStyle: TextStyle,
    inlineCodeBg: Color,
    inlineCodeColor: Color,
    linkColor: Color,
    linkListener: LinkInteractionListener? = null,
    mathInlineTags: Map<Int, Pair<String, Int>> = emptyMap(),
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val len = text.length

    while (i < len) {
        // Inline-math placeholder anchor → InlineTextContent slot.
        val mathTag = mathInlineTags[i]
        if (mathTag != null) {
            appendInlineContent(mathTag.first, "￼")
            i = mathTag.second
            continue
        }

        // Escaped character
        if (text[i] == '\\' && i + 1 < len) {
            append(text[i + 1])
            i += 2
            continue
        }

        // Inline code
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = inlineCodeColor,
                    background = inlineCodeBg,
                    fontSize = (baseStyle.fontSize.value * 0.85).sp,
                )) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }

        // Link [text](url)
        if (text[i] == '[') {
            val closeBracket = text.indexOf(']', i + 1)
            if (closeBracket > i && closeBracket + 1 < len && text[closeBracket + 1] == '(') {
                val closeParen = text.indexOf(')', closeBracket + 2)
                if (closeParen > closeBracket) {
                    val linkText = text.substring(i + 1, closeBracket)
                    val url = text.substring(closeBracket + 2, closeParen)
                    withLink(LinkAnnotation.Url(url, linkInteractionListener = linkListener)) {
                        withStyle(SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        )) {
                            append(linkText)
                        }
                    }
                    i = closeParen + 1
                    continue
                }
            }
        }

        // Bold + Italic ***text***
        if (i + 2 < len && text.substring(i, i + 3) == "***") {
            val end = text.indexOf("***", i + 3)
            if (end > i) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 3, end))
                }
                i = end + 3
                continue
            }
        }

        // Bold **text** or __text__
        if (i + 1 < len && (text.substring(i, i + 2) == "**" || text.substring(i, i + 2) == "__")) {
            val marker = text.substring(i, i + 2)
            val end = text.indexOf(marker, i + 2)
            if (end > i) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }

        // Strikethrough ~~text~~
        if (i + 1 < len && text.substring(i, i + 2) == "~~") {
            val end = text.indexOf("~~", i + 2)
            if (end > i) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }

        // Italic *text* or _text_ (single delimiter, not followed by another)
        if ((text[i] == '*' || text[i] == '_') &&
            (i + 1 >= len || text[i + 1] != text[i]) // not ** or __
        ) {
            val delim = text[i]
            val end = text.indexOf(delim, i + 1)
            if (end > i && end > i + 1) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }

        // Regular character
        append(text[i])
        i++
    }
}

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
private fun MinisImageBlock(block: MarkdownParser.Block.Image) {
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
private fun MinisVideoBlock(block: MarkdownParser.Block.Video) {
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
                android.util.Log.w("MdMedia", "video thumbnail failed: ${t.message}")
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

@Composable
private fun MinisAudioBlock(block: MarkdownParser.Block.Audio) {
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
                android.util.Log.w("MdMedia", "audio error what=$what extra=$extra")
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
            delay(200)
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
                    text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
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

private fun formatMs(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
