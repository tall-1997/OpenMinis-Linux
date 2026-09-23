package com.openminis.app.security

/**
 * Argv tokenizer and expansion-syntax detector.
 *
 * Adapted from XINCODE-Public ExecPolicy.kt (GPL-3.0-or-later)
 * which itself ports Codex (Apache-2.0) quoting rules.
 * https://github.com/kusesad-1122/XINCODE-Public
 */
fun tokenizeCommand(raw: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote = '\u0000'
    var i = 0
    var hasToken = false
    while (i < raw.length) {
        val c = raw[i]
        when {
            c == '\\' && i + 1 < raw.length -> {
                cur.append(raw[i + 1]); i += 2; hasToken = true; continue
            }
            quote != '\u0000' -> {
                if (c == quote) quote = '\u0000' else { cur.append(c); hasToken = true }
            }
            c == '\'' || c == '"' -> quote = c
            c.isWhitespace() -> {
                if (hasToken || cur.isNotEmpty()) {
                    out.add(cur.toString()); cur.clear(); hasToken = false
                }
            }
            else -> { cur.append(c); hasToken = true }
        }
        i++
    }
    if (hasToken || cur.isNotEmpty()) out.add(cur.toString())
    return out
}

val APP_DATA_ROOTS = listOf(
    "/data/data/com.openminis.linux",
    "/data/user/0/com.openminis.linux",
)

fun containsShellExpansionSyntax(raw: String): Boolean {
    if (raw.isEmpty()) return false
    if (raw.contains('$')) return true
    if (raw.contains('`')) return true
    if (raw.contains(';')) return true
    if (raw.contains('|')) return true
    if (raw.contains('&')) return true
    if (raw.contains('>')) return true
    if (raw.contains('\n') || raw.contains('\r')) return true
    if (Regex("\\{[^}\\s]*,[^}\\s]*\\}").containsMatchIn(raw)) return true
    return false
}

/** Split on `;`, `|`, `&`, and newlines, but not inside quotes. */
fun shellSegments(raw: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote = '\u0000'
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (quote != '\u0000') {
            cur.append(c)
            if (c == quote) quote = '\u0000'
            i++
            continue
        }
        if (c == '\\' && i + 1 < raw.length) {
            cur.append(raw[i + 1])
            i += 2
            continue
        }
        if (c == '\'' || c == '"') {
            quote = c
            cur.append(c)
            i++
            continue
        }
        if (c == '\n' || c == '\r' || c == ';' || c == '|' || c == '&') {
            val sepLen = if ((c == '|' || c == '&') && i + 1 < raw.length && raw[i + 1] == c) 2 else 1
            val seg = cur.toString().trim()
            if (seg.isNotEmpty()) out.add(seg)
            cur.clear()
            i += sepLen
            continue
        }
        cur.append(c)
        i++
    }
    val tail = cur.toString().trim()
    if (tail.isNotEmpty()) out.add(tail)
    return out
}

/**
 * Units that must be classified on their own. A `su -c` / `sh -c` payload is
 * split further so `rm /tmp; ls /data/...` is not one fatal string.
 */
fun riskUnits(command: String): List<String> {
    val units = mutableListOf<String>()
    for (segment in shellSegments(command)) {
        val argv = tokenizeCommand(segment)
        val head = argv.firstOrNull()?.substringAfterLast('/')
        val cIdx = argv.indexOf("-c")
        if (cIdx >= 0 && cIdx + 1 < argv.size &&
            (head == "su" || head == "android-su" || head == "sh" || head == "bash")
        ) {
            units += shellSegments(argv[cIdx + 1])
            continue
        }
        units += segment
    }
    return units
}
