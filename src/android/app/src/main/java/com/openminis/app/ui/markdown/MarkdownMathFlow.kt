package com.openminis.app.ui.markdown

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors

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
internal fun MathFlowText(
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
