package com.openminis.app.ui.markdown

import com.openminis.app.text.BoundedText

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Color roles the highlighter paints with. Dark defaults match the VS Code
 * dark+ scheme used since the first version; Light mirrors VS Code light+
 * so the reusable code editor stays legible under the light app theme.
 */
data class HighlightPalette(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val type: Color,
    val default: Color,
) {
    companion object {
        val Dark = HighlightPalette(
            keyword = Color(0xFF569CD6),   // blue
            string = Color(0xFFCE9178),    // orange
            comment = Color(0xFF6A9955),   // green
            number = Color(0xFFB5CEA8),    // light green
            type = Color(0xFF4EC9B0),      // teal
            default = Color(0xFFD4D4D4),   // light gray
        )
        val Light = HighlightPalette(
            keyword = Color(0xFF0000FF),
            string = Color(0xFFA31515),
            comment = Color(0xFF008000),
            number = Color(0xFF098658),
            type = Color(0xFF267F99),
            default = Color(0xFF1F1F1F),
        )
    }
}

/**
 * Lightweight syntax highlighter for code blocks.
 * Applies keyword/string/comment/number coloring for common languages.
 */
object SyntaxHighlighter {

    private val commonKeywords = setOf(
        "if", "else", "for", "while", "return", "break", "continue", "switch", "case",
        "default", "try", "catch", "finally", "throw", "new", "delete", "typeof", "instanceof",
        "class", "interface", "enum", "struct", "func", "fun", "fn", "def", "async", "await",
        "import", "export", "from", "package", "module", "use", "pub", "private", "public",
        "protected", "static", "final", "const", "let", "var", "val", "mut",
        "true", "false", "null", "nil", "None", "self", "this", "super",
        "in", "is", "as", "not", "and", "or", "with", "yield", "lambda",
        "override", "abstract", "sealed", "data", "object", "companion",
        "suspend", "inline", "when", "where", "guard", "defer", "do",
    )

    private val typeKeywords = setOf(
        "int", "float", "double", "string", "bool", "boolean", "void", "char", "byte",
        "long", "short", "String", "Int", "Float", "Double", "Bool", "Boolean",
        "List", "Map", "Set", "Array", "HashMap", "ArrayList", "Optional",
    )

    // Token regex: strings, comments, numbers, words
    private val TOKEN_REGEX = Regex(
        """(//[^\n]*|#[^\n]*|/\*[\s\S]*?\*/)|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)|(\b\d+\.?\d*\b)|(\b[a-zA-Z_]\w*\b)|([^\s\w]+|\s+)"""
    )

    /** Legacy entry point — keeps the original dark colors. */
    fun highlight(code: String, language: String): AnnotatedString =
        highlight(code, language, HighlightPalette.Dark)

    /**
     * [T-cili-gold-pink-highlight] 小黄鱼 skin path: every token paints with
     * the SAME gold-pink gradient brush [skinBrush] (deep gold -> pink, coordinates
     * relative to the whole Text layout so the sweep reads as one continuous
     * gradient across the block). Syntax tiers survive as per-role ALPHA on
     * the shared brush — comments fade to ~80%, strings to ~95%, everything
     * else stays solid — instead of separate hues that would fight the skin.
     * [T-cili-highlight-deeper] user: 对话内容高亮部分必须颜色加深 — all tier
     * alphas raised (0.55→0.80 comments, 0.85→0.95 strings, 0.90/0.95→1.0
     * numbers/body) AND the brush itself re-based to deeper stops, so every
     * token reads clearly against dark code backgrounds.
     * `skinBrush == null` keeps the stock palette untouched.
     */
    fun highlight(
        code: String,
        language: String,
        palette: HighlightPalette,
        skinBrush: Brush? = null,
    ): AnnotatedString = buildAnnotatedString {
        val plain = { color: Color -> SpanStyle(color = color) }
        val faded = { alpha: Float ->
            if (skinBrush != null) {
                SpanStyle(brush = skinBrush, alpha = alpha)
            } else null
        }
        fun tier(color: Color, alpha: Float = 1f): SpanStyle = faded(alpha) ?: plain(color)
        if (language.isEmpty()) {
            pushStyle(tier(palette.default))
            append(code)
            pop()
            return@buildAnnotatedString
        }

        val scan = if (code.length <= BoundedText.MAX_ICU_INPUT_CHARS) code
            else code.substring(0, BoundedText.MAX_ICU_INPUT_CHARS)
        for (match in TOKEN_REGEX.findAll(scan)) {
            val comment = match.groups[1]?.value
            val string = match.groups[2]?.value
            val number = match.groups[3]?.value
            val word = match.groups[4]?.value
            val other = match.groups[5]?.value

            when {
                comment != null -> {
                    pushStyle(tier(palette.comment, alpha = 0.80f))
                    append(comment)
                    pop()
                }
                string != null -> {
                    pushStyle(tier(palette.string, alpha = 0.95f))
                    append(string)
                    pop()
                }
                number != null -> {
                    pushStyle(tier(palette.number, alpha = 1f))
                    append(number)
                    pop()
                }
                word != null -> {
                    val color = when {
                        word in commonKeywords -> palette.keyword
                        word in typeKeywords -> palette.type
                        word.first().isUpperCase() -> palette.type
                        else -> palette.default
                    }
                    pushStyle(tier(color, alpha = 1f))
                    append(word)
                    pop()
                }
                other != null -> {
                    pushStyle(tier(palette.default, alpha = 1f))
                    append(other)
                    pop()
                }
            }
        }
        if (code.length > BoundedText.MAX_ICU_INPUT_CHARS) {
            pushStyle(tier(palette.default))
            append(code.substring(BoundedText.MAX_ICU_INPUT_CHARS))
            pop()
        }
    }
}
