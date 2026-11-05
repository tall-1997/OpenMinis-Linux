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
internal fun InlineContent(
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
