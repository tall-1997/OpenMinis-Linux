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

/**
 * Split on `;`, `|`, `&`, newlines, and — [T-prefixrule-substitution] — on
 * command-substitution boundaries (a backtick, or `$(`). The old splitter
 * only saw the outer operator, so `echo $(rm -rf /x)` was ONE segment with
 * argv[0] == "echo" and the prefix-rule layer never saw the `rm` token
 * inside the substitution. Splitting at every substitution opener hands the
 * inner command its own segment, where prefix matching can see it. Nesting
 * is not balanced (each `$(` opens a cut); the innermost real command token
 * still surfaces as a segment head.
 */
fun shellSegments(raw: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote = '\u0000'
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (quote != '\u0000') {
            // [T-prefixrule-quoted-substitution] Inside DOUBLE quotes bash still
            // performs command substitution — `echo "$(cmd)"` runs cmd — so the
            // opener must cut a segment here too. Inside SINGLE quotes nothing is
            // special and `$(…)` is literal text, so appending verbatim stays
            // correct there.
            //
            // This was the half the first substitution fix missed: it cut on
            // `$(`/backtick only in the unquoted branch below, so the QUOTED
            // spelling — the idiomatic one, quoted precisely to preserve
            // whitespace — stayed a single `echo` unit and the prefix-rule layer
            // never saw the inner program token. Verified forms that were blind
            // before: `echo "$(cmd)"`, `X="$(cmd)"`, ``echo "`cmd`"``.
            //
            // Known limitation, unchanged from the unquoted path: the
            // substitution's closing `)` is not tracked, so it stays glued to
            // the last token of the inner segment (`… /x)`). Harmless for
            // realistic prefixes (`rm -rf` matches on argv[0..1]); a rule whose
            // LAST token sits under that `)` would miss. Balancing parens would
            // have to interact with nested quoting, which costs more than the
            // gap is worth in a tighten-only layer where over-splitting is safe.
            if (quote == '"') {
                // Backslash escapes `$`, backtick, `"`, `\` and newline inside
                // double quotes — an escaped opener is literal text.
                if (c == '\\' && i + 1 < raw.length) {
                    cur.append(c).append(raw[i + 1])
                    i += 2
                    continue
                }
                if (c == '`' || (c == '$' && i + 1 < raw.length && raw[i + 1] == '(')) {
                    val seg = cur.toString().trim()
                    if (seg.isNotEmpty()) out.add(seg)
                    cur.clear()
                    i += if (c == '$') 2 else 1
                    // `quote` stays '"': the closing quote is still ahead, and
                    // the substitution's own text must keep being appended
                    // verbatim until it arrives.
                    continue
                }
            }
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
        if (c == '`' || (c == '$' && i + 1 < raw.length && raw[i + 1] == '(')) {
            val seg = cur.toString().trim()
            if (seg.isNotEmpty()) out.add(seg)
            cur.clear()
            i += if (c == '$') 2 else 1
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
 *
 * [T-prefixrule-wrapper-unwrap] The same holds for argv[0] WRAPPERS: the old
 * version only unwrapped `su`/`sh`/`bash`, so `env X=1 rm -rf /`,
 * `xargs rm -rf /`, `find . -exec rm -rf {} \;`, `nice -n 19 rm -rf /`,
 * `timeout 5 rm -rf /`, `busybox rm -rf /` and friends all presented a
 * wrapper as argv[0] and the prefix-rule layer never saw the real program
 * token — several wrappers even sit in SAFE_COMMANDS and auto-ran. Every
 * wrapper head now recursively yields an extra unit that starts at the
 * wrapped program, and `find -exec` payloads are lifted into their own
 * units. Over-unwrapping is safe by construction: this layer only tightens.
 */
private val WRAPPER_HEADS = setOf(
    "env", "nice", "timeout", "xargs", "stdbuf", "setsid",
    "nohup", "sudo", "busybox", "strace", "script",
)

/** Index of the wrapped program inside [argv] when [argv] starts with a wrapper. */
private fun wrappedProgramIndex(argv: List<String>): Int {
    val head = argv.firstOrNull()?.substringAfterLast('/') ?: return -1
    var i = 1
    val valueOptions = when (head) {
        "env" -> setOf("-u", "--unset", "-C", "--chdir", "-S", "--split-string")
        "sudo" -> setOf("-u", "--user", "-g", "--group", "-h", "--host", "-p", "--prompt", "-C", "--close-from")
        "nice" -> setOf("-n", "--adjustment")
        "timeout" -> setOf("-k", "--kill-after", "-s", "--signal")
        "stdbuf" -> setOf("-i", "-o", "-e")
        "xargs" -> setOf("-n", "--max-args", "-P", "--max-procs", "-s", "--max-chars", "-I", "--replace", "-L", "--max-lines", "-E", "--eof", "-d", "--delimiter", "-a", "--arg-file")
        else -> emptySet()
    }
    if (head in setOf("env", "sudo", "nice", "timeout", "stdbuf", "xargs")) {
        while (i < argv.size) {
            val arg = argv[i]
            if (arg == "--") { i++; break }
            if (arg in valueOptions) {
                i += 2
                continue
            }
            if (arg.startsWith("-") && arg != "-") { i++; continue }
            if (head == "env" && arg.contains('=')) { i++; continue }
            if (head == "nice" && arg.toIntOrNull() != null) { i++; continue }
            break
        }
    }
    if (head == "timeout" && i < argv.size) i++ // mandatory duration, regardless of spelling
    return if (i < argv.size) i else -1
}

fun riskUnits(command: String): List<String> {
    val units = mutableListOf<String>()
    for (segment in shellSegments(command)) {
        collectRiskUnits(segment, 0, units)
    }
    return units
}

private const val MAX_UNWRAP_DEPTH = 4

private fun collectRiskUnits(segment: String, depth: Int, units: MutableList<String>) {
    val argv = tokenizeCommand(segment)
    val head = argv.firstOrNull()?.substringAfterLast('/')
    val cIdx = argv.indexOf("-c")
    if (cIdx >= 0 && cIdx + 1 < argv.size &&
        (head == "su" || head == "android-su" || head == "sh" || head == "bash" ||
            head == "ash" || head == "dash" || head == "zsh" || head == "ksh")
    ) {
        for (inner in shellSegments(argv[cIdx + 1])) collectRiskUnits(inner, depth, units)
    }
    if (depth < MAX_UNWRAP_DEPTH) {
        if (head in WRAPPER_HEADS) {
            val programIdx = wrappedProgramIndex(argv)
            if (programIdx > 0 && programIdx < argv.size) {
                collectRiskUnits(argv.drop(programIdx).joinToString(" "), depth + 1, units)
            }
        }
        // `find … -exec cmd … {} ;` / `-execdir` — the embedded command gets
        // its own unit so `rm -rf` style prefixes match inside it.
        val execIdx = argv.indexOfFirst { it == "-exec" || it == "-execdir" }
        if (execIdx >= 0 && execIdx + 1 < argv.size) {
            val embedded = argv.drop(execIdx + 1)
                .takeWhile { it != "{}" && it != ";" && it != "\\;" }
                .joinToString(" ")
            if (embedded.isNotBlank()) collectRiskUnits(embedded, depth + 1, units)
        }
    }
    units += segment
}

/**
 * Deletion targets that wipe [path] wholesale: the directory itself, or a bare
 * glob of everything directly inside it. Anything *underneath* it is not a
 * wholesale target — that is the whole point of the distinction.
 */
private fun wholesaleTargets(path: String): Set<String> =
    if (path == "/") {
        setOf("/", "//", "/*", "/.", "/..")
    } else {
        val p = path.trimEnd('/')
        setOf(p, "$p/", "$p/*", "$p/.")
    }

/**
 * Start of the target arguments when [argv] is an `rm` invocation, or -1 when
 * it is not one.
 *
 * Only argv[0] counts as the program, plus wrapper heads that [riskUnits] may
 * not have peeled yet. Scanning for "any token equal to rm" instead would make
 * `echo rm /` look like a root deletion.
 */
private fun rmArgStart(argv: List<String>): Int {
    var offset = 0
    repeat(MAX_UNWRAP_DEPTH + 1) {
        val tok = argv.getOrNull(offset)?.substringAfterLast('/') ?: return -1
        if (tok == "rm") return offset + 1
        if (tok !in WRAPPER_HEADS) return -1
        val rel = wrappedProgramIndex(argv.drop(offset))
        if (rel <= 0) return -1
        offset += rel
    }
    return -1
}

/**
 * True when some `rm` in [command] deletes [path] wholesale — the directory
 * itself, or everything directly inside it — rather than a path underneath it.
 *
 * [T-rm-root-false-positive] Substring matching cannot answer this question,
 * because every absolute path begins with `/`. The old
 * `contains("rm -rf /")` check therefore also fired on routine cleanups like
 * `rm -rf /sdcard/Download/tmp`, `rm -rf /tmp/x; echo done` and
 * `cd /a && rm -rf /a/b`, hard-denying them with exit 126 ("禁止删除根目录")
 * in EVERY permission mode — YOYO included. Both the user and the agent were
 * locked out of ordinary workspace cleanup, and the only workaround was to
 * obscure the command (`cd` into the directory and use relative paths) to slip
 * past a check that was never about the actual target.
 *
 * This walks argv instead. Wrappers (`env`, `sudo`, `busybox`, `timeout`,
 * `xargs`, `find -exec`) are peeled by [riskUnits] and [rmArgStart] before the
 * targets are inspected, so tightening the match does not open a bypass:
 * `busybox rm -rf /` is still caught, `rm -rf /sdcard/x` is not.
 *
 * Targets that cannot be resolved statically (`rm -rf "$DIR"`,
 * `rm -rf $(echo /)`) are deliberately NOT reported. No substring check ever
 * caught those either, and the permission / confirm layers still see the raw
 * command, so this is not a regression — it keeps the refusal honest instead of
 * pretending to cover cases it cannot evaluate.
 */
fun rmDeletesPath(command: String, path: String): Boolean {
    val targets = wholesaleTargets(path)
    for (unit in riskUnits(command)) {
        val argv = tokenizeCommand(unit)
        val start = rmArgStart(argv)
        if (start < 0) continue
        var pastSeparator = false
        for (i in start until argv.size) {
            val arg = argv[i]
            when {
                // After `--` every remaining token is an operand, even one that
                // looks like a flag.
                pastSeparator -> if (arg.trim('"', '\'') in targets) return true
                arg == "--" -> pastSeparator = true
                // Options never name a target: a path operand cannot start with
                // '-', so flags (glued or separate) are simply skipped.
                arg.length > 1 && arg.startsWith("-") -> Unit
                else -> if (arg.trim('"', '\'') in targets) return true
            }
        }
    }
    return false
}

/** `rm` wiping the filesystem root itself (slash, slash-glob, dot, dot-dot). */
fun rmTargetsRoot(command: String): Boolean = rmDeletesPath(command, "/")
