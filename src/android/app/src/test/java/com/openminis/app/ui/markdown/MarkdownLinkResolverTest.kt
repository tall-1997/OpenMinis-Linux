package com.openminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 预览里的链接要能打开：网页交给应用内浏览器，相对路径按当前文件所在目录
 * 解析成目标文件。解析不能越过允许的根目录。
 */
class MarkdownLinkResolverTest {

    private val root = "/var/minis/workspace"

    @Test
    fun `web links stay web links`() {
        val link = MarkdownLinkResolver.resolve("https://example.com/a", "/var/minis/workspace/docs", root)
        assertEquals(MarkdownLinkResolver.Target.Web("https://example.com/a"), link)
    }

    @Test
    fun `relative links resolve against the file directory`() {
        val link = MarkdownLinkResolver.resolve("../README.md", "/var/minis/workspace/docs", root)
        // [跨平台] Windows 的 File.path 是反斜杠（且无盘符的 /var/... 会保留
        // 词法形式），断言两边都归一化到 invariant 分隔符再比较。
        val file = link as MarkdownLinkResolver.Target.File
        assertEquals(
            java.io.File("/var/minis/workspace/README.md").invariantSeparatorsPath,
            java.io.File(file.absolutePath).invariantSeparatorsPath,
        )
    }

    @Test
    fun `anchor-only links are not files`() {
        assertNull(MarkdownLinkResolver.resolve("#section", "/var/minis/workspace/docs", root))
    }

    @Test
    fun `paths that escape the allowed root are rejected`() {
        assertNull(MarkdownLinkResolver.resolve("../../../../etc/passwd", "/var/minis/workspace/docs", root))
    }

    @Test
    fun `minis links pass through unchanged`() {
        val link = MarkdownLinkResolver.resolve("minis://workspace/a.md", "/var/minis/workspace", root)
        assertEquals(MarkdownLinkResolver.Target.Web("minis://workspace/a.md"), link)
    }
}
