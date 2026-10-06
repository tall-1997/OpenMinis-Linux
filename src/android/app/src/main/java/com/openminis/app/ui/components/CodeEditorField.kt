package com.openminis.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.markdown.HighlightPalette
import com.openminis.app.ui.markdown.SyntaxHighlighter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.verticalScroll

/**
 * A compact code editor surface: line-number gutter, monospace text, syntax
 * highlighting and search-match painting — built on a plain [BasicTextField]
 * so IME behaviour, selection and caret scrolling stay stock.
 *
 * Layout contract: the gutter Column and the text field share ONE vertical
 * [ScrollState], so they cannot drift apart. With [softWrap] disabled every
 * logical line is exactly one visual row of a fixed height, which is what
 * keeps gutter numbers aligned; with soft wrap on the gutter is hidden
 * because wrapped rows would break the 1:1 mapping.
 *
 * Highlighting runs through a [VisualTransformation] with an identity offset
 * mapping — colours never shift cursor or selection positions.
 */
@Composable
fun CodeEditorField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    language: String = "text",
    softWrap: Boolean = false,
    searchMatches: List<IntRange> = emptyList(),
    currentMatchIndex: Int = -1,
    verticalScrollState: ScrollState = rememberScrollState(),
    horizontalScrollState: ScrollState = rememberScrollState(),
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val palette = if (isDark) {
        HighlightPalette.Dark
    } else {
        HighlightPalette.Light
    }
    val baseColor = MaterialTheme.colorScheme.onSurface

    val annotated = remember(value.text, language, palette, baseColor, searchMatches, currentMatchIndex) {
        buildEditorAnnotated(value.text, language, palette, baseColor, searchMatches, currentMatchIndex)
    }
    val transformation = remember(annotated, value.text) {
        PrecomputedTransformation(annotated, value.text)
    }

    val editorStyle = remember(baseColor) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = EditorLineHeightSp,
            color = baseColor,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
    }

    Row(modifier = modifier.fillMaxWidth()) {
        if (!softWrap) {
            LineNumberGutter(
                text = value.text,
                scrollState = verticalScrollState,
                currentLine = CodeEditorLogic.lineOf(value.text, value.selection.start),
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            var fieldModifier: Modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(verticalScrollState)
            if (!softWrap) {
                fieldModifier = fieldModifier.horizontalScroll(horizontalScrollState)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = fieldModifier.padding(end = 8.dp),
                textStyle = editorStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = false,
                visualTransformation = transformation,
            )
        }
    }
}

/** Line height shared by gutter rows and editor lines — must stay identical. */
private val EditorLineHeightSp = 20.sp

@Composable
private fun LineNumberGutter(
    text: String,
    scrollState: ScrollState,
    currentLine: Int,
) {
    val count = CodeEditorLogic.lineCount(text)
    val activeColor = MaterialTheme.colorScheme.onSurface
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = EditorLineHeightSp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    Column(
        modifier = Modifier
            .width(44.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .verticalScroll(scrollState),
    ) {
        for (i in 0 until count) {
            Text(
                text = (i + 1).toString(),
                style = style,
                color = if (i == currentLine) activeColor else idleColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

/**
 * Paints the whole document once per content change: syntax colours plus
 * search backgrounds. Falls back to the raw text if the framework hands us
 * something newer than the precomputed snapshot (defensive identity check).
 */
private class PrecomputedTransformation(
    private val annotated: AnnotatedString,
    private val sourceText: String,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        if (text.text == sourceText) {
            TransformedText(annotated, OffsetMapping.Identity)
        } else {
            TransformedText(text, OffsetMapping.Identity)
        }
}

private fun buildEditorAnnotated(
    text: String,
    language: String,
    palette: HighlightPalette,
    baseColor: Color,
    matches: List<IntRange>,
    currentMatchIndex: Int,
): AnnotatedString {
    val base = if (language.isEmpty() || language == "text") {
        AnnotatedString(text, listOf(AnnotatedString.Range(SpanStyle(color = baseColor), 0, text.length)))
    } else {
        SyntaxHighlighter.highlight(text, language, palette)
    }
    if (matches.isEmpty()) return base
    val styles = base.spanStyles.toMutableList()
    matches.forEachIndexed { i, range ->
        val start = range.first.coerceIn(0, base.length)
        val end = (range.last + 1).coerceIn(start, base.length)
        if (end <= start) return@forEachIndexed
        val background = if (i == currentMatchIndex) {
            Color(0xE6FF9632) // current: solid orange
        } else {
            Color(0x59FFD60A) // others: translucent yellow
        }
        styles += AnnotatedString.Range(
            SpanStyle(background = background, color = Color(0xFF000000)),
            start,
            end,
        )
    }
    return AnnotatedString(base.text, styles, base.paragraphStyles)
}

/**
 * Search strip shown above the editor body: query field, "n/m" counter and
 * prev/next/close buttons. Stateless — the caller owns query and matches.
 */
@Composable
fun CodeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchIndex: Int,
    matchCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.code_editor_search_hint)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = if (query.isEmpty()) {
                ""
            } else if (matchCount == 0) {
                stringResource(R.string.code_editor_no_matches)
            } else {
                stringResource(R.string.code_editor_matches, matchIndex + 1, matchCount)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = onPrev, enabled = matchCount > 0) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.code_editor_prev_match),
            )
        }
        IconButton(onClick = onNext, enabled = matchCount > 0) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.code_editor_next_match),
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.code_editor_close_search),
            )
        }
    }
}
