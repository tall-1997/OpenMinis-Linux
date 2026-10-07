package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CodeBlockRunRouter] 的纯规则测试。
 *
 * 这层刻意不碰 Compose / Android / 沙箱，所以能在纯 JVM 单测里跑完 ——
 * 路由映射、文件名安全、base64 正确性、输出截断全部钉死。
 */
class CodeBlockRunRouterTest {

    // -- 语言路由 --

    @Test
    fun `python aliases all route to python3`() {
        for (lang in listOf("python", "py", "python3", "PY", "  Python3  ")) {
            val plan = CodeBlockRunRouter.plan(lang, "print(1)", 1_000L)
            assertNotNull("expected $lang to be supported", plan)
            assertTrue("expected python3 invocation in: " + plan!!.command,
                plan.command.contains("python3 /var/minis/workspace/.codeblocks/"))
            assertEquals("python", plan.displayLang)
            assertTrue(plan.fileName.endsWith(".py"))
        }
    }

    @Test
    fun `js aliases route to node`() {
        for (lang in listOf("js", "javascript", "node", "JS")) {
            val plan = CodeBlockRunRouter.plan(lang, "console.log(1)", 1L)
            assertNotNull("expected $lang to be supported", plan)
            assertTrue(plan!!.command.contains("node /var/minis/workspace/.codeblocks/"))
            assertEquals("js", plan.displayLang)
        }
    }

    @Test
    fun `shell aliases route to bash`() {
        for (lang in listOf("bash", "sh", "shell", "zsh", "SH")) {
            val plan = CodeBlockRunRouter.plan(lang, "echo hi", 1L)
            assertNotNull("expected $lang to be supported", plan)
            assertTrue(plan!!.command.contains("bash /var/minis/workspace/.codeblocks/"))
            assertEquals("bash", plan.displayLang)
        }
    }

    @Test
    fun `sql routes to python3 with the persisted sql runner`() {
        val plan = CodeBlockRunRouter.plan("sql", "select 1;", 1L)
        assertNotNull(plan)
        val cmd = plan!!.command
        // 主源码已改：guest 无 sqlite3 二进制，sql 统一落盘 _sql_runner.py
        // 后用 python3 标准库 sqlite3 跑 :memory:。
        assertTrue("expected python3 in: " + cmd, cmd.contains("python3 "))
        assertTrue("expected _sql_runner.py in: " + cmd, cmd.contains(CodeBlockRunRouter.SQL_RUNNER_FILE))
        assertTrue("expected .sql file in: " + cmd, cmd.contains(".sql"))
        assertEquals("sql", plan.displayLang)
        // runner 落盘先于执行（与 python 分支同一 write-then-run 形状）
        val runnerWriteAt = cmd.indexOf("/${CodeBlockRunRouter.SQL_RUNNER_FILE}")
        val runAt = cmd.lastIndexOf("python3 ")
        assertTrue("runner write must precede run", runnerWriteAt in 0 until runAt)
    }

    @Test
    fun `sql plan works regardless of the legacy sqliteAvailable flag`() {
        // 兼容签名保留但不再影响 SQL 路由
        assertNotNull(CodeBlockRunRouter.plan("sql", "select 1;", 1L, sqliteAvailable = false))
        assertNotNull(CodeBlockRunRouter.plan("sql", "select 1;", 1L, sqliteAvailable = true))
        assertTrue(CodeBlockRunRouter.isSupported("sql", sqliteAvailable = false))
        assertTrue(CodeBlockRunRouter.isSupported("sqlite3", sqliteAvailable = false))
    }

    @Test
    fun `sql runner payload is a self contained python sqlite script`() {
        val runner = CodeBlockRunRouter.SQL_RUNNER_PY
        assertTrue("must import the stdlib sqlite3", runner.contains("import sqlite3"))
        assertTrue("must run in-memory", runner.contains("connect(\":memory:\")"))
        assertTrue("must surface errors as SQL error:", runner.contains("SQL error: "))
        // runner 自身通过 base64 落盘，且 base64 输出可安全进单引号参数
        val encoded = CodeBlockRunRouter.base64(runner)
        assertFalse("single quote leaked", encoded.contains("'"))
        val cmd = CodeBlockRunRouter.plan("sql", "select 1;", 1L)!!.command
        assertTrue("runner bytes must be embedded", cmd.contains(encoded))
    }

    @Test
    fun `unsupported languages return null`() {
        for (lang in listOf("", "   ", "java", "rust", "c++", "kotlin", "html", "json", "yaml")) {
            assertNull("expected '$lang' to be unsupported",
                CodeBlockRunRouter.plan(lang, "print(1)", 1L))
        }
    }

    @Test
    fun `fence noise and modifiers are tolerated`() {
        assertNotNull(CodeBlockRunRouter.plan("```python", "print(1)", 1L))
        assertNotNull(CodeBlockRunRouter.plan("js { hi }", "console.log(1)", 1L))
        assertNotNull(CodeBlockRunRouter.plan("  ```bash```  ", "echo hi", 1L))
        // 带版本号的后缀不识别 —— 不能把「能跑」建立在猜测上。
        assertNull(CodeBlockRunRouter.plan("python3.11", "print(1)", 1L))
    }

    @Test
    fun `blank code is not runnable`() {
        assertNull(CodeBlockRunRouter.plan("python", "", 1L))
        assertNull(CodeBlockRunRouter.plan("python", "   \n\t ", 1L))
    }

    // -- 文件名安全 --

    @Test
    fun `file name only uses safe characters`() {
        val plan = CodeBlockRunRouter.plan("python", "print(1)", 1_752_000_000_000L)!!
        val stem = plan.fileName.removeSuffix(".py")
        assertTrue("unsafe file name: " + plan.fileName,
            plan.fileName.matches(Regex("[a-z0-9_]+\\.[a-z]+")))
        assertTrue(stem.isNotEmpty())
    }

    @Test
    fun `negative and MIN_VALUE timestamps stay safe and distinct`() {
        for (ts in listOf(0L, -1L, -1_752_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val plan = CodeBlockRunRouter.plan("python", "print(1)", ts)!!
            assertTrue("unsafe for ts=$ts: " + plan.fileName,
                plan.fileName.matches(Regex("[a-z0-9_]+\\.py")))
        }
    }

    @Test
    fun `distinct timestamps do not collide`() {
        val a = CodeBlockRunRouter.plan("python", "x", 1_752_000_000_000L)!!.fileName
        val b = CodeBlockRunRouter.plan("python", "x", 1_752_000_000_001L)!!.fileName
        assertTrue("collision: $a vs $b", a != b)
    }

    // -- base64 --

    @Test
    fun `base64 matches reference vectors`() {
        assertEquals("", CodeBlockRunRouter.base64(""))
        assertEquals("YQ==", CodeBlockRunRouter.base64("a"))
        assertEquals("YWI=", CodeBlockRunRouter.base64("ab"))
        assertEquals("YWJj", CodeBlockRunRouter.base64("abc"))
        assertEquals("YWJjZA==", CodeBlockRunRouter.base64("abcd"))
        assertEquals("cHJpbnQoImhpIik=", CodeBlockRunRouter.base64("print(\"hi\")"))
    }

    @Test
    fun `base64 output is shell-single-quote safe`() {
        val nasty = listOf("'", "\"", "`", "$", "\\", "\n", "\t", ";", "|", "&", "()").joinToString("x")
        val encoded = CodeBlockRunRouter.base64(nasty)
        assertFalse("single quote leaked: " + encoded, encoded.contains('\''))
        assertTrue(encoded.matches(Regex("[A-Za-z0-9+/=]+")))
    }

    @Test
    fun `command never embeds raw user code`() {
        val evil = "print('\"; DROP TABLE users; --')"
        val plan = CodeBlockRunRouter.plan("python", evil, 1L)!!
        assertFalse("raw code leaked into command", plan.command.contains("DROP TABLE"))
        assertFalse("raw quote leaked into command", plan.command.contains("''"))
        assertTrue(plan.command.contains(CodeBlockRunRouter.base64(evil)))
    }

    @Test
    fun `command writes the file before running it`() {
        val plan = CodeBlockRunRouter.plan("python", "print(1)", 1L)!!
        val writeAt = plan.command.indexOf("base64 -d >")
        val runAt = plan.command.indexOf("python3 /var/minis/workspace/.codeblocks/")
        assertTrue("write must precede run", writeAt in 0 until runAt)
    }

    @Test
    fun `missing interpreter does not kill the persistent shell`() {
        // exit 127 必须留在子 shell 里，否则 PersistentShell 读不到退出码标记。
        val plan = CodeBlockRunRouter.plan("python", "print(1)", 1L)!!
        assertTrue("must wrap in a subshell", plan.command.contains("( command -v python3"))
        assertTrue(plan.command.contains("exit 127; }"))
    }

    // -- 输出格式化 --

    @Test
    fun `short output is untouched`() {
        val out = "hello\nworld"
        assertEquals(out, CodeBlockRunRouter.truncate(out))
    }

    @Test
    fun `long output is truncated in the middle with a marker`() {
        val out = "A".repeat(5_000) + "B".repeat(5_000)
        val cut = CodeBlockRunRouter.truncate(out, limit = 100)
        assertTrue("too long: " + cut.length, cut.length < out.length)
        assertTrue("missing head", cut.startsWith("A"))
        assertTrue("missing tail", cut.endsWith("B"))
        assertTrue("missing marker: " + cut, cut.contains("已省略"))
        // 省略标注本身也占字符，所以结果会略微超过 limit，这是有意的。
        assertTrue(cut.length > 100)
    }

    @Test
    fun `formatOutput renders the header and exit code`() {
        val text = CodeBlockRunRouter.formatOutput("python", 123L, 0, "ok")
        assertTrue(text.startsWith("▶ 运行 python（123ms，exit=0）"))
        assertTrue(text.contains("ok"))
    }

    @Test
    fun `formatOutput failure exit code is surfaced`() {
        val text = CodeBlockRunRouter.formatOutput("bash", 7L, 127, "boom")
        assertTrue(text.contains("exit=127"))
        assertTrue(text.contains("boom"))
    }

    @Test
    fun `fence grows past backticks inside the output`() {
        val text = CodeBlockRunRouter.formatOutput("md", 1L, 0, "before ``` inner ``` after")
        val lines = text.lines()
        val closing = lines.last { it.isNotBlank() }
        assertTrue("fence must exceed 3 backticks: $closing", closing.length > 3)
        assertTrue(closing.all { it == '`' })
    }

    @Test
    fun `formatOutput respects the limit`() {
        val out = "Z".repeat(20_000)
        val text = CodeBlockRunRouter.formatOutput("js", 5L, 0, out)
        assertTrue("must be truncated", text.contains("已省略"))
        // 截断后仍保留头尾各 4000 字符，但完整的 20000 字符正文必须消失。
        assertFalse("full body leaked", text.contains("Z".repeat(20_000)))
        assertTrue("head kept", text.contains("Z".repeat(1_000)))
    }

    @Test
    fun `isSupported mirrors plan for every alias`() {
        for (lang in listOf("python", "py", "python3", "js", "javascript", "node",
                             "bash", "sh", "shell", "zsh", "sql", "sqlite3")) {
            assertTrue(lang, CodeBlockRunRouter.isSupported(lang))
            assertEquals(lang, CodeBlockRunRouter.plan(lang, "x", 1L) != null,
                CodeBlockRunRouter.isSupported(lang))
        }
        for (lang in listOf("", "java", "rust")) {
            assertFalse(lang, CodeBlockRunRouter.isSupported(lang))
        }
    }

    // -- CodeBlockRunTexts 文案（ChatViewModelCodeRunExt.kt） --

    @Test
    fun `code block run texts carry the coordinator-pinned wording`() {
        assertTrue(CodeBlockRunTexts.busyHint().contains("正在回复中"))
        assertTrue(CodeBlockRunTexts.deniedHint("原因X").startsWith("无法运行该代码块："))
        assertTrue(CodeBlockRunTexts.deniedHint("原因X").endsWith("原因X"))
        assertTrue(CodeBlockRunTexts.needConfirmHint("R").contains("运行按钮不会弹窗"))
        assertTrue(CodeBlockRunTexts.needConfirmHint("R").contains("R"))
        assertTrue(CodeBlockRunTexts.unsupportedHint("lua").contains("lua"))
    }
}