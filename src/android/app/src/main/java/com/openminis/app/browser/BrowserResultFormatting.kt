package com.openminis.app.browser

import org.json.JSONObject

/**
 * [review P2 hardcoded] Max characters of scraped page text echoed verbatim
 * into a tool result — bounds the transcript cost of a huge page fetch.
 */
private const val TEXT_RESULT_MAX_CHARS = 10_000

/**
 * [review P2 hardcoded] Max characters of a control target's label quoted
 * into a click/hover result line.
 */
private const val TARGET_TEXT_PREVIEW_CHARS = 200

// -- Result Formatting --

internal fun formatJsonResult(json: JSONObject): String = buildString {
    when {
        json.optBoolean("clicked") -> {
            val tag = json.optString("tag", "?")
            appendLine("Clicked <$tag>")
            if (json.has("x") && json.has("y")) appendLine("  Position: (${json.optInt("x")}, ${json.optInt("y")})")
            val text = json.optString("text", "")
            if (text.isNotEmpty()) append("  Text: ${text.take(TARGET_TEXT_PREVIEW_CHARS)}")
        }
        json.optBoolean("typed") -> {
            val sel = json.optString("selector", "?")
            val len = json.optInt("length", 0)
            append("Typed $len chars into $sel")
        }
        json.optBoolean("scrolled") -> {
            val dir = json.optString("direction", "?")
            val amt = json.optInt("amount", 0)
            appendLine("Scrolled $dir ${amt}px")
            if (json.has("scrollY")) appendLine("  Scroll Y: ${json.optInt("scrollY")}")
            if (json.has("scrollHeight")) appendLine("  Page height: ${json.optInt("scrollHeight")}")
            if (json.has("viewportHeight")) append("  Viewport height: ${json.optInt("viewportHeight")}")
        }
        json.has("scrolledTo") -> {
            val sel = json.optString("scrolledTo")
            val tag = json.optString("tag", "?")
            append("Scrolled to <$tag> ($sel)")
        }
        json.optBoolean("hovered") -> {
            val tag = json.optString("tag", "?")
            appendLine("Hovered <$tag>")
            val text = json.optString("text", "")
            if (text.isNotEmpty()) append("  Text: ${text.take(TARGET_TEXT_PREVIEW_CHARS)}")
        }
        json.has("text") && json.has("length") -> {
            val title = json.optString("title", "")
            if (title.isNotEmpty()) appendLine("Title: $title")
            val text = json.optString("text", "")
            val len = json.optInt("length", text.length)
            appendLine("Text ($len chars):")
            append(text.take(TEXT_RESULT_MAX_CHARS))
        }
        json.has("count") && json.has("elements") -> {
            val count = json.optInt("count")
            val elements = json.optJSONArray("elements")
            val shown = json.optInt("shown", elements?.length() ?: 0)
            appendLine("Found $count element(s) (showing $shown):")
            if (elements != null) {
                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val idx = el.optInt("index")
                    val tag = el.optString("tag", "?")
                    val text = el.optString("text", "").take(80)
                    val line = buildString {
                        append("  [$idx] <$tag>")
                        val id = el.optString("id", "")
                        if (id.isNotEmpty()) append(" #$id")
                        if (text.isNotEmpty()) append(" \"$text\"")
                        val href = el.optString("href", "")
                        if (href.isNotEmpty()) append(" -> $href")
                    }
                    appendLine(line)
                }
            }
        }
        else -> {
            // Fallback: format each key-value pair
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                appendLine("  $key: ${json.opt(key)}")
            }
        }
    }
}.trimEnd()

internal fun formatBackboneResult(json: JSONObject): String = buildString {
    val nodeCount = json.optInt("nodeCount")
    val depth = json.optInt("depth")
    val merged = json.optInt("merged")
    appendLine("Page backbone: $nodeCount nodes, depth $depth, $merged merged")

    val backbone = json.optJSONArray("backbone")
    if (backbone != null) {
        for (i in 0 until backbone.length()) {
            formatBackboneNode(backbone.getJSONObject(i), 0, this)
        }
    }
}.trimEnd()

internal fun formatBackboneNode(node: JSONObject, indent: Int, sb: StringBuilder) {
    val pad = "  ".repeat(indent)
    val tag = node.optString("tag", "?")
    val sel = node.optString("sel", "")
    val rect = node.optString("rect", "")

    val header = buildString {
        append("$pad<$tag>")
        val id = node.optString("id", "")
        if (id.isNotEmpty()) append(" #$id")
        val cls = node.optString("cls", "")
        if (cls.isNotEmpty()) append(" .${cls.replace(" ", ".")}")
        val role = node.optString("role", "")
        if (role.isNotEmpty()) append(" [$role]")
        append(" | $sel | $rect")
    }
    sb.appendLine(header)

    val text = node.optString("text", "")
    if (text.isNotEmpty()) sb.appendLine("$pad  \"$text\"")
    val href = node.optString("href", "")
    if (href.isNotEmpty()) sb.appendLine("$pad  -> $href")
    val img = node.optString("img", "")
    if (img.isNotEmpty()) sb.appendLine("$pad  img: $img")
    val input = node.optString("input", "")
    if (input.isNotEmpty()) sb.appendLine("$pad  input: $input")

    val children = node.optJSONArray("children")
    if (children != null) {
        for (i in 0 until children.length()) {
            formatBackboneNode(children.getJSONObject(i), indent + 1, sb)
        }
    }
}

internal fun extensionForMimeType(mime: String): String {
    val lower = mime.lowercase().split(";").firstOrNull()?.trim() ?: ""
    return when (lower) {
        "text/html" -> "html"; "text/plain" -> "txt"; "text/css" -> "css"; "text/csv" -> "csv"
        "application/json" -> "json"; "application/xml", "text/xml" -> "xml"
        "application/pdf" -> "pdf"; "image/png" -> "png"; "image/jpeg" -> "jpg"
        "image/gif" -> "gif"; "image/webp" -> "webp"; "image/svg+xml" -> "svg"
        "application/zip" -> "zip"; "application/gzip" -> "gz"
        else -> "bin"
    }
}
