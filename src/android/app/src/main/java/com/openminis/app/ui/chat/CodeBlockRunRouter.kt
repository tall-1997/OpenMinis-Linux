package com.openminis.app.ui.chat

/**
 * 聊天代码块「▶ 运行」的纯路由层。
 *
 * 这里是唯一决定「一个 fence 语言 → 怎么跑」的地方，且刻意不含任何
 * Compose / Android / 沙箱依赖：路由、文件名、base64、输出格式化都是纯
 * 函数，由 `CodeBlockRunRouterTest` 覆盖。
 *
 * 设计约束：
 * - 文件名只允许 [a-z0-9_.]，绝不使用代码内容拼路径（防路径穿越）。
 * - 写入一律走 base64 中转（`printf %s '<b64>' | base64 -d > file`），
 *   与 `ChatViewModelShellExt.wrapForBash` 同一先例：代码里可以有任何
 *   单引号 / 反斜杠 / 换行 / `$()`，都不会破坏 shell 命令本身。
 * - guest 里没有 `sqlite3` 二进制：SQL 路由改走 `python3` 的 `sqlite3`
 *   标准库（[SQL_RUNNER_PY]，由 VM 扩展在执行前随代码文件一起 base64 落盘），
 *   而不是给用户一个必然失败的按钮。
 */
object CodeBlockRunRouter {

    /** 单次运行回填到聊天流的输出字符上限。 */
    const val MAX_OUTPUT_CHARS = 8_000

    /** 落盘目录：会话工作区下的隐藏子目录。 */
    const val CODEBLOCK_DIR = "/var/minis/workspace/.codeblocks"

    /** SQL runner 的落盘文件名（与代码文件同目录，内容固定、可复用）。 */
    const val SQL_RUNNER_FILE = "_sql_runner.py"

    /**
     * SQL runner：guest 没有 sqlite3 二进制时用 python3 的标准库兜底。
     *
     * - 逐条以 `;` 分割语句（极简 split，够覆盖模型产出的典型 SQL）；
     * - SELECT / PRAGMA / WITH / EXPLAIN 打印结果行（管道分隔，空库打印
     *   `(0 rows)`）；其余语句打印 `OK`；
     * - 每条语句独立 try/except，出错打印 `SQL error: …` 并以 exit 1 结束；
     * - 库固定 `:memory:`，代码块里的 `CREATE TABLE` 在同一次运行内可见。
     *
     * 纯字符串常量：Router 保持零依赖、可在纯 JVM 单测里做形状断言。
     */
    const val SQL_RUNNER_PY: String = """import sqlite3, sys

DB = sqlite3.connect(":memory:")
DB.isolation_level = None


def rows(cursor):
    out = []
    out.append(" | ".join(d[0] for d in cursor.description or []))
    for row in cursor.fetchall():
        out.append(" | ".join("NULL" if v is None else str(v) for v in row))
    return out


def main():
    sql = open(sys.argv[1], "r", encoding="utf-8", errors="replace").read()
    statements = [s.strip() for s in sql.split(";") if s.strip()]
    for stmt in statements:
        cur = DB.execute(stmt)
        if cur.description is not None:
            out = rows(cur)
            if len(out) == 1:
                print("(0 rows)")
            else:
                for line in out:
                    print(line)
        else:
            print("OK")
        print()


try:
    main()
except Exception as exc:
    print("SQL error: " + str(exc))
    sys.exit(1)
"""

    /**
     * 一条可执行的计划。[command] 把「写文件」和「执行」合成一条命令：
     * 同一 session 的命令由 ExecutionCoordinator 的 per-session Mutex 串行化，
     * 合成一条比两条更安全，不会出现「文件还没落盘就执行」的中间态。
     */
    data class RunPlan(
        val fileName: String,
        val command: String,
        /** 展示给用户的语言名（已归一化：python / js / bash / sql）。 */
        val displayLang: String,
    )

    /**
     * 支持的 fence 语言 → 内部 Kind。
     * [interpreter] 是 guest 里实际调用的可执行文件。
     */
    private enum class Kind(
        val display: String,
        val extension: String,
        val interpreter: String,
    ) {
        PYTHON("python", "py", "python3"),
        JS("js", "js", "node"),
        SHELL("bash", "sh", "bash"),
        // guest 无 sqlite3 二进制：统一经 python3 + 标准库 sqlite3 跑。
        // interpreter 只用作「python3 存在性预检」，真正的执行形状在
        // [build] 的 SQL 分支拼装。
        SQL("sql", "sql", "python3"),
    }

    private val LANG_ALIASES: Map<String, Kind> = mapOf(
        "python" to Kind.PYTHON, "py" to Kind.PYTHON,
        "python3" to Kind.PYTHON, "py3" to Kind.PYTHON,
        "js" to Kind.JS, "javascript" to Kind.JS, "node" to Kind.JS,
        "bash" to Kind.SHELL, "sh" to Kind.SHELL,
        "shell" to Kind.SHELL, "zsh" to Kind.SHELL,
        "sql" to Kind.SQL, "sqlite3" to Kind.SQL,
    )

    /**
     * 路由一个代码块。返回 null 表示「不支持运行」——调用方据此不显示按钮。
     *
     * @param lang fence 上的语言标签，可能为空 / 大小写混杂 / 带修饰符。
     * @param code 代码正文。
     * @param timestamp 落盘文件名用的时间戳（调用方注入，方便测试）。
     * @param sqliteAvailable 兼容保留（旧签名），SQL 已不再依赖它。
     */
    fun plan(
        lang: String,
        code: String,
        timestamp: Long,
        @Suppress("UNUSED_PARAMETER") sqliteAvailable: Boolean = true,
    ): RunPlan? {
        val kind = normalizeLang(lang) ?: return null
        if (code.isBlank()) return null

        val fileName = "run_${safeStamp(timestamp)}.${kind.extension}"
        val path = "$CODEBLOCK_DIR/$fileName"
        val command = build(
            dir = CODEBLOCK_DIR,
            path = path,
            payload = base64(code),
            interpreter = kind.interpreter,
            kind = kind,
        )
        return RunPlan(fileName = fileName, command = command, displayLang = kind.display)
    }

    /** UI 用：只判断语言是否可运行，不关心代码内容。 */
    fun isSupported(lang: String): Boolean = normalizeLang(lang) != null

    /**
     * SQL 在 guest 里能不能真跑：始终可以 —— 走 python3 标准库兜底。
     * 保留双参签名以免调用方连锁改动。
     */
    fun isSupported(lang: String, sqliteAvailable: Boolean): Boolean =
        normalizeLang(lang) != null

    /** 归一化后的语言名（用于回填消息的标题）。无法识别时返回 null。 */
    fun displayLang(lang: String): String? = normalizeLang(lang)?.display

    /**
     * fence 语言归一化。空串 / 未知语言 → null。
     * `c++` / `java` / `rust` 这类明确不支持的语言同样返回 null。
     */
    private fun normalizeLang(lang: String): Kind? {
        var cleaned = lang.trim().lowercase()
        // 少数模型会把围栏本身当语言串。
        while (cleaned.startsWith("`")) cleaned = cleaned.drop(1)
        while (cleaned.endsWith("`")) cleaned = cleaned.dropLast(1)
        // `js { ... }` 这类尾随修饰只取首个词；带版本号的后缀
        // （`python3.11`）不猜，直接不识别。
        val head = cleaned.split(' ', '{', '\t')[0].trim()
        return LANG_ALIASES[head]
    }

    /**
     * 组装单条命令。
     *
     * `exit 127` 只允许出现在子 shell 里：PersistentShell 用
     * `{cmd}; echo …_EXIT_$?…` 驱动命令并从标记行读退出码，裸 `exit`
     * 会把常驻 shell 本身杀掉。`ChatViewModelShellExt.wrapForBash` 的注释
     * 记录了同一个坑，本行沿用它的 `( … )` 包裹写法。
     */
    private fun build(
        dir: String,
        path: String,
        payload: String,
        interpreter: String,
        kind: Kind,
    ): String {
        if (kind == Kind.SQL) {
            // SQL：额外落一份固定内容的 runner（纯 python3 标准库），然后
            // `python3 <runner.py> <file.sql>`。runner 内容自身也走 base64，
            // 与代码同一先例；重复写同一路径是幂等的。
            val runnerPath = "$dir/$SQL_RUNNER_FILE"
            val runnerPayload = base64(SQL_RUNNER_PY)
            return "mkdir -p $dir && printf %s '$payload' | base64 -d > $path && " +
                "printf %s '$runnerPayload' | base64 -d > $runnerPath && " +
                "( command -v python3 >/dev/null 2>&1 || " +
                "{ echo '沙箱内缺少 python3，无法运行此类代码块' >&2; exit 127; }; " +
                "python3 $runnerPath $path )"
        }
        val invocation = "$interpreter $path"
        return "mkdir -p $dir && printf %s '$payload' | base64 -d > $path && " +
            "( command -v $interpreter >/dev/null 2>&1 || " +
            "{ echo '沙箱内缺少 $interpreter，无法运行此类代码块' >&2; exit 127; }; " +
            "$invocation )"
    }

    /** 时间戳 → 只含 [a-z0-9] 的短串；负数 / Long.MIN_VALUE 也安全。 */
    private fun safeStamp(timestamp: Long): String {
        val safe = if (timestamp == Long.MIN_VALUE) 0L else
            if (timestamp < 0) -timestamp else timestamp
        return safe.toString(36).lowercase().ifEmpty { "0" }
    }

    /**
     * 标准 base64。手写而非 `android.util.Base64`，是为了让本文件保持
     * 零 Android 依赖、可在纯 JVM 单测里跑。
     * 输出只含 [A-Za-z0-9+/=]，可安全放进单引号 shell 参数。
     */
    internal fun base64(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = (bytes[i].toInt() and 0xFF shl 16) or
                (bytes[i + 1].toInt() and 0xFF shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(CHARS[(n ushr 18) and 0x3F])
            sb.append(CHARS[(n ushr 12) and 0x3F])
            sb.append(CHARS[(n ushr 6) and 0x3F])
            sb.append(CHARS[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                sb.append(CHARS[(n ushr 18) and 0x3F])
                sb.append(CHARS[(n ushr 12) and 0x3F])
                sb.append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(CHARS[(n ushr 18) and 0x3F])
                sb.append(CHARS[(n ushr 12) and 0x3F])
                sb.append(CHARS[(n ushr 6) and 0x3F])
                sb.append('=')
            }
        }
        return sb.toString()
    }

    /**
     * 把输出截断到 [limit]，中间省略并标注。头尾各留一半，让「报错在开头」
     * 和「结果在结尾」都能看到。
     */
    fun truncate(output: String, limit: Int = MAX_OUTPUT_CHARS): String {
        if (limit <= 0 || output.length <= limit) return output
        val headLen = limit / 2
        val tailLen = limit - headLen
        val omitted = output.length - limit
        return output.take(headLen) +
            "\n\n… [已省略 $omitted 个字符] …\n\n" +
            output.takeLast(tailLen)
    }

    /**
     * 回填到聊天流的那条消息正文。
     *
     * 格式：`▶ 运行 <lang>（<耗时>ms，exit=<code>）` + 空行 + 输出围栏。
     * 输出正文若自带 ``` 会提前闭合围栏，这里按 CommonMark 的既定做法把
     * 围栏加长到比正文中最长的反引号串更长（上限 8 个）。
     */
    fun formatOutput(
        displayLang: String,
        durationMs: Long,
        exitCode: Int,
        output: String,
        limit: Int = MAX_OUTPUT_CHARS,
    ): String {
        val body = truncate(output.trimEnd(), limit)
        val fence = fenceFor(body)
        return "▶ 运行 $displayLang（${durationMs}ms，exit=$exitCode）\n\n" +
            "$fence\n$body\n$fence"
    }

    /** 取比正文中最长反引号串更长的一个围栏（上限 8 个）。 */
    private fun fenceFor(body: String): String {
        var longest = 2
        var idx = body.indexOf("``")
        while (idx >= 0) {
            var run = 0
            var j = idx
            while (j < body.length && body[j] == '`') { run++; j++ }
            if (run > longest) longest = run
            idx = body.indexOf("``", j)
        }
        return "`".repeat((longest + 1).coerceAtMost(8))
    }

    private const val CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}