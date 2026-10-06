package com.openminis.app.ui.markdown

import com.openminis.app.text.BoundedText

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

    fun highlight(
        code: String,
        language: String,
        palette: HighlightPalette,
    ): AnnotatedString = buildAnnotatedString {
        if (language.isEmpty()) {
            pushStyle(SpanStyle(color = palette.default))
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
                    pushStyle(SpanStyle(color = palette.comment))
                    append(comment)
                    pop()
                }
                string != null -> {
                    pushStyle(SpanStyle(color = palette.string))
                    append(string)
                    pop()
                }
                number != null -> {
                    pushStyle(SpanStyle(color = palette.number))
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
                    pushStyle(SpanStyle(color = color))
                    append(word)
                    pop()
                }
                other != null -> {
                    pushStyle(SpanStyle(color = palette.default))
                    append(other)
                    pop()
                }
            }
        }
        if (code.length > BoundedText.MAX_ICU_INPUT_CHARS) {
            pushStyle(SpanStyle(color = palette.default))
            append(code.substring(BoundedText.MAX_ICU_INPUT_CHARS))
            pop()
        }
    }
}
