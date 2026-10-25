package com.openminis.app.security

/**
 * Guest `/data` is not the phone's `/data`. `/sdcard` is a placeholder until
 * All Files Access is granted. `rm` of a missing path exits 0, so reject the
 * command instead of letting it succeed silently. Host `su` / `android-su`
 * segments are skipped: those paths are real on the host.
 */
object GuestMountPolicy {
    fun rejection(command: String, sdcardMounted: Boolean): String? {
        val units = guestUnits(command)
        if (units.isEmpty()) return null
        val hits = units.mapNotNull { unmountedPath(it, sdcardMounted) }
        // Mixed commands still have allowed work. Hard-deny only when every
        // guest unit touches an unmounted path; the runner rewrites the bad
        // units so the rest still execute.
        if (hits.isEmpty()) return null
        if (hits.size < units.size && rewriteCompound(command, sdcardMounted) != command) return null
        val hit = hits.first()
        return "拒绝在未挂载的客户机路径上执行命令（$hit）。" +
            "客户机 /data 不是手机 /data；/sdcard 需要「所有文件访问」才会挂载。" +
            "请改用 /var/minis/workspace，或用 android-su 访问真实宿主路径。"
    }

    /**
     * Replace only the guest units that touch an unmounted path. Separators
     * (`;`, `&&`, `||`, `|`) stay, so `echo AAA; ls /data; echo BBB` still
     * prints both echoes. An all-bad command is left unchanged so [rejection]
     * can deny it.
     */
    fun rewriteCompound(command: String, sdcardMounted: Boolean): String {
        val units = guestUnits(command)
        val hits = units.count { unmountedPath(it, sdcardMounted) != null }
        if (hits == 0 || hits == units.size) return command
        return rewriteKeepingSeparators(command, sdcardMounted)
    }

    private fun rewriteKeepingSeparators(raw: String, sdcardMounted: Boolean): String {
        val out = StringBuilder()
        val cur = StringBuilder()
        var quote = '\u0000'
        var i = 0
        fun flushSegment() {
            val seg = cur.toString()
            val trimmed = seg.trim()
            val nested = rewriteNestedPayload(trimmed, sdcardMounted)
            if (nested != null) {
                out.append(seg.takeWhile { it.isWhitespace() }).append(nested)
            } else if (trimmed.isNotEmpty() && unmountedPath(trimmed, sdcardMounted) != null) {
                val msg = "拒绝在未挂载的客户机路径上执行。客户机 /data 不是手机 /data；请改用 /var/minis/workspace，或用 android-su 访问真实宿主路径。"
                out.append("(echo '").append(msg.replace("'", "")).append("' >&2; false)")
            } else {
                out.append(seg)
            }
            cur.setLength(0)
        }
        while (i < raw.length) {
            val c = raw[i]
            if (quote != '\u0000') {
                cur.append(c)
                if (c == quote) quote = '\u0000'
                i++
                continue
            }
            if (c == '\\' && i + 1 < raw.length) {
                cur.append(raw[i])
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
                flushSegment()
                val sepLen = if ((c == '|' || c == '&') && i + 1 < raw.length && raw[i + 1] == c) 2 else 1
                out.append(raw, i, i + sepLen)
                i += sepLen
                continue
            }
            cur.append(c)
            i++
        }
        flushSegment()
        return out.toString()
    }

    private fun rewriteNestedPayload(segment: String, sdcardMounted: Boolean): String? {
        val argv = tokenizeCommand(segment)
        val head = argv.firstOrNull()?.substringAfterLast('/') ?: return null
        if (head != "sh" && head != "bash") return null
        val cIdx = argv.indexOf("-c")
        if (cIdx < 0 || cIdx + 1 >= argv.size) return null
        val payload = argv[cIdx + 1]
        val rewritten = rewriteKeepingSeparators(payload, sdcardMounted)
        if (rewritten == payload) return null
        val escaped = rewritten.replace("'", "'\\''")
        val single = "'$payload'"
        val double = "\"$payload\""
        return when {
            segment.contains(single) -> segment.replace(single, "'$escaped'")
            segment.contains(double) -> segment.replace(double, "'$escaped'")
            else -> null
        }
    }

    internal fun guestUnits(command: String): List<String> {
        val units = mutableListOf<String>()
        for (segment in shellSegments(command)) {
            val argv = tokenizeCommand(segment)
            val head = argv.firstOrNull()?.substringAfterLast('/')
            val cIdx = argv.indexOf("-c")
            if (cIdx >= 0 && cIdx + 1 < argv.size && (head == "su" || head == "android-su")) {
                continue
            }
            if (cIdx >= 0 && cIdx + 1 < argv.size && (head == "sh" || head == "bash")) {
                units += shellSegments(argv[cIdx + 1])
                continue
            }
            units += segment
        }
        return units
    }

    private fun unmountedPath(unit: String, sdcardMounted: Boolean): String? {
        for (token in tokenizeCommand(unit)) {
            val path = token.removeSuffix("/")
            if (path == "/data" || path.startsWith("/data/")) return "/data"
            if (!sdcardMounted && isSdcard(path)) return "/sdcard"
        }
        return null
    }

    private fun isSdcard(path: String): Boolean =
        path == "/sdcard" || path.startsWith("/sdcard/") ||
            path == "/storage/emulated/0" || path.startsWith("/storage/emulated/0/")
}
