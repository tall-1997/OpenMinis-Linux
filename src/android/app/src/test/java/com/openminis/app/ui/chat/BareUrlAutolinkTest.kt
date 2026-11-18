package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-chatlink-bare-url-autolink] Root cause of the field report "链接不是蓝色
 * 可点击状态，点不进去"（screenshot 368.jpg, session 1bd04f03）:
 *
 * The model emitted the file link as a BARE URL in bold markers —
 * `**minis://attachments/uploads/susu.wiki_______-3.4.txt**` — not the
 * `[label](url)` form. parseInline had no autolink branch: the bold branch
 * consumed the run, painted bold dark text with no "url" annotation, and the
 * tap died before ChatLinkResolver ever ran. Older chats looked fine only
 * because those models emitted proper `[text](url)` links.
 *
 * These tests pin the autolink branch: bare minis/http(s) URLs become "url"
 * annotated spans, CJK punctuation is trimmed, parenthesized paths survive,
 * code and existing link forms are untouched.
 */
class BareUrlAutolinkTest {

    /** Plain-JVM Color: parseInline only styles, never resolves to a device. */
    private fun testColors() = MdColors(
        text = Color.Black, codeText = Color.Black, codeBg = Color.White,
        inlineCodeText = Color.Black, inlineCodeBg = Color.White,
        link = Color.Blue, blockquote = Color.Gray,
        divider = Color.Gray, tableBorder = Color.Gray, tableHeaderBg = Color.White,
    )

    private fun urlsOf(text: String): List<String> {
        val s = parseInline(text, testColors())
        return s.getAnnotations(0, 0, s.length)
            .filter { it.tag == "url" }
            .map { it.data }
    }

    @Test
    fun `bare minis url becomes an annotated url span`() {
        val got = urlsOf("完整脚本文件地址如下：minis://attachments/uploads/susu.wiki_______-3.4.txt 请查收")
        assertEquals(listOf("minis://attachments/uploads/susu.wiki_______-3.4.txt"), got)
    }

    @Test
    fun `bare url inside bold renders as a link — the field case`() {
        // **url** → appendRecursive re-enters the parser with the markers
        // stripped, so the inner bare URL must still get the url annotation.
        val got = urlsOf("**minis://attachments/uploads/susu.wiki_______-3.4.txt**（已更新为 v3.5）")
        assertEquals(listOf("minis://attachments/uploads/susu.wiki_______-3.4.txt"), got)
    }

    @Test
    fun `trailing CJK punctuation is not part of the url`() {
        val got = urlsOf("见 https://example.com/a.txt。然后继续")
        assertEquals(listOf("https://example.com/a.txt"), got)
    }

    @Test
    fun `trailing ASCII period after a sentence is trimmed`() {
        val got = urlsOf("open http://example.com/path.")
        assertEquals(listOf("http://example.com/path"), got)
    }

    @Test
    fun `parenthesized path stays inside the url`() {
        val got = urlsOf("see https://en.wikipedia.org/wiki/Go_(programming_language) for details")
        assertEquals(listOf("https://en.wikipedia.org/wiki/Go_(programming_language)"), got)
    }

    @Test
    fun `unbalanced closing paren terminates the url`() {
        val got = urlsOf("(see http://example.com/a) done")
        assertEquals(listOf("http://example.com/a"), got)
    }

    @Test
    fun `markdown link form still works unchanged`() {
        val got = urlsOf("[点此安装](minis://attachments/uploads/x.txt)")
        assertEquals(listOf("minis://attachments/uploads/x.txt"), got)
    }

    @Test
    fun `inline code url is NOT linkified`() {
        val got = urlsOf("`minis://attachments/uploads/x.txt`")
        assertTrue(got.isEmpty())
    }

    @Test
    fun `text that merely contains a colon-scheme-like prefix stays plain`() {
        // No scheme match → no annotation.
        assertTrue(urlsOf("this is not a url: ftp://example.com").isEmpty())
    }

    @Test
    fun `chinese filename in a bare url survives`() {
        val got = urlsOf("minis://attachments/报告_最终版.pdf")
        assertEquals(listOf("minis://attachments/报告_最终版.pdf"), got)
    }

    @Test
    fun `chinese filename followed by CJK period terminates cleanly`() {
        val got = urlsOf("文件在 minis://attachments/报告.pdf。请查收")
        assertEquals(listOf("minis://attachments/报告.pdf"), got)
    }

    @Test
    fun `two urls on one line both linkify`() {
        val got = urlsOf("a minis://attachments/one.txt b https://two.com/path c")
        assertEquals(listOf("minis://attachments/one.txt", "https://two.com/path"), got)
    }
}
