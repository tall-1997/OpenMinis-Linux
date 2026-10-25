package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the keyless CN fallback chain parsers. The Sogou and Bing
 * fixtures below are trimmed captures of real result pages (fetched 2026-10,
 * desktop UA), so the regexes are pinned against production markup rather
 * than idealized samples. Baidu is tested with synthetic markup plus the
 * REAL security-wall page it serves to datacenter clients — that wall is
 * the common case, not the exception.
 */
class KeylessSearchSourcesTest {

    // ---- Sogou ----------------------------------------------------------

    private val sogouReal = """
        <h3 class="vr-title"><!--awbg0--><a name="dttl" target="_blank" href="/link?url=hedJjaC291OwOaI" id="sogou_vr_30000000_0"><!--awbg0--><em>Kotlin</em> 1.1 · Kotlin 官方文档 中文版</a></h3><div class="fz-mid space-txt base-ellipsis clamp2" id="cacheresult_summary_0">import <em>kotlin</em>.<em>coroutines</em>.experimental.* fun main (args: Array &lt; String &gt;) { val seq = buildSequence { ...</div>
        <h3 class="vr-title  "  vrcid="title.e1ca646"><a target="_blank" href="https://kotlinlang.org/docs/coroutines-overview.html">Coroutines | Kotlin <em>Documentation</em></a></h3>
    """.trimIndent()

    @Test
    fun `parseSogou extracts real result blocks`() {
        val hits = KeylessSearchSources.parseSogou(sogouReal, max = 8)
        assertEquals(2, hits.size)
        assertEquals("Kotlin 1.1 · Kotlin 官方文档 中文版", hits[0].title)
        assertEquals("https://www.sogou.com/link?url=hedJjaC291OwOaI", hits[0].url)
        assertTrue(hits[0].snippet.contains("buildSequence"))
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", hits[1].url)
    }

    @Test
    fun `parseSogou caps at max and skips blank titles`() {
        val html = buildString {
            repeat(5) { i ->
                append("<h3><a href=\"/link?url=x$i\">Title $i</a></h3>")
            }
            append("<h3><a href=\"/link?url=blank\"><em></em> </a></h3>")
        }
        val hits = KeylessSearchSources.parseSogou(html, max = 3)
        assertEquals(3, hits.size)
        assertEquals("Title 0", hits[0].title)
    }

    @Test
    fun `parseSogou drops sponsored redirects and dedupes`() {
        val html = """
            <h3><a href="https://www.sogou.com/sogou?query=x&pid=ad">推广结果</a></h3>
            <h3><a href="/link?url=dup">同一条</a></h3>
            <h3><a href="/link?url=dup">同一条(重复)</a></h3>
            <h3><a href="javascript:void(0)">坏链接</a></h3>
            <h3><a href="/link?url=ok">正常结果</a></h3>
        """.trimIndent()
        val hits = KeylessSearchSources.parseSogou(html, max = 8)
        assertEquals(2, hits.size)
        assertEquals("同一条", hits[0].title)
        assertEquals("正常结果", hits[1].title)
    }

    @Test
    fun `parseSogou on baidu wall page is empty`() {
        // Cross-feed a foreign page: must yield nothing, never garbage.
        assertEquals(0, KeylessSearchSources.parseSogou(baiduWall, max = 5).size)
    }

    // ---- Bing RSS -------------------------------------------------------

    private val bingReal = """
        <?xml version="1.0" encoding="utf-8" ?><rss version="2.0"><channel><title>必应：kotlin coroutines</title><link>http://www.bing.com:80/search?q=kotlin+coroutines</link><description>搜索结果</description><image><url>http://www.bing.com:80/s/a/rsslogo.gif</url><title>kotlin coroutines</title><link>http://www.bing.com:80/search?q=kotlin+coroutines</link></image><item><title>入门 · Kotlin 官方文档 中文版</title><link>https://book.kotlincn.net/text/getting-started.html</link><description>Kotlin 入门 最新 Kotlin 版本： 2.1.21</description></item><item><title><![CDATA[Coroutines | Kotlin Documentation]]></title><link><![CDATA[https://kotlinlang.org/docs/coroutines-overview.html]]></link></item></channel></rss>
    """.trimIndent()

    @Test
    fun `parseBingRss reads items and skips channel header`() {
        val hits = KeylessSearchSources.parseBingRss(bingReal, max = 8)
        assertEquals(2, hits.size)
        assertEquals("入门 · Kotlin 官方文档 中文版", hits[0].title)
        assertEquals("https://book.kotlincn.net/text/getting-started.html", hits[0].url)
        assertTrue(hits[0].snippet.contains("2.1.21"))
        // CDATA unwrapped in both title and link.
        assertEquals("Coroutines | Kotlin Documentation", hits[1].title)
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", hits[1].url)
        assertEquals("", hits[1].snippet)
    }

    @Test
    fun `parseBingRss caps at max and dedupes links`() {
        val xml = buildString {
            append("<rss><channel>")
            repeat(3) { i -> append("<item><title>T$i</title><link>https://e$i.org</link></item>") }
            append("<item><title>dup</title><link>https://e0.org</link></item>")
            append("</channel></rss>")
        }
        assertEquals(2, KeylessSearchSources.parseBingRss(xml, max = 2).size)
        assertEquals(3, KeylessSearchSources.parseBingRss(xml, max = 8).size)
    }

    // ---- Baidu ------------------------------------------------------------

    private val baiduWall = """
        <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"><title>百度安全验证</title></head><body><div class="timeout hide-callback"><div class="timeout-title">网络不给力，请稍后重试</div></div></body></html>
    """.trimIndent()

    @Test
    fun `parseBaidu detects security wall`() {
        assertEquals(0, KeylessSearchSources.parseBaidu(baiduWall, max = 5).size)
        assertEquals(0, KeylessSearchSources.parseBaidu("<html>wappass.baidu.com/static</html>", 5).size)
    }

    @Test
    fun `parseBaidu extracts titles snippets and caps`() {
        val html = """
            <div class="result c-container">
            <h3 class="t"><a href="http://www.baidu.com/link?url=AAA" target="_blank"><em>Kotlin</em> 协程入门</a></h3>
            <span class="content-right_8Zs40">协程是轻量级线程，支持挂起与恢复。</span>
            </div>
            <h3 class="c-title"><a href="http://www.baidu.com/link?url=BBB">第二条 &amp; 更多</a></h3>
            <div class="c-abstract">摘要内容</div>
            <h3><a href="https://zhuanlan.zhihu.com/p/1">知乎专栏</a></h3>
            <h3><a href="http://www.baidu.com/other">百度自家导航</a></h3>
        """.trimIndent()
        val hits = KeylessSearchSources.parseBaidu(html, max = 2)
        assertEquals(2, hits.size)
        assertEquals("Kotlin 协程入门", hits[0].title)
        assertTrue(hits[0].snippet.contains("轻量级线程"))
        assertEquals("第二条 & 更多", hits[1].title)
        assertTrue(hits[1].snippet.contains("摘要内容"))
        val all = KeylessSearchSources.parseBaidu(html, max = 8)
        // zhihu kept; baidu's own chrome (not /link?url=) dropped.
        assertEquals(3, all.size)
        assertEquals("知乎专栏", all[2].title)
    }

    // ---- shared helpers ---------------------------------------------------

    @Test
    fun `stripTags removes emphasis markers and collapses space`() {
        assertEquals(
            "Kotlin 官方文档",
            KeylessSearchSources.stripTags("<em>Kotlin</em>\n  官方文档"),
        )
        // Tag-shaped junk becomes a separator, not a joiner.
        assertEquals("a b", KeylessSearchSources.stripTags("a<!-- c -->b"))
    }

    @Test
    fun `unescape handles named and numeric entities`() {
        assertEquals(
            "a & b < c > d \" e ' f — g … h © ·",
            KeylessSearchSources.unescape("a &amp; b &lt; c &gt; d &quot; e &#39; f &mdash; g &hellip; h &#169; &middot;"),
        )
    }

    @Test
    fun `absoluteUrl resolves relative and protocol-relative links`() {
        val base = "https://www.sogou.com"
        assertEquals("https://a.org/x", KeylessSearchSources.absoluteUrl("https://a.org/x", base))
        assertEquals("https://www.sogou.com/link?url=x", KeylessSearchSources.absoluteUrl("/link?url=x", base))
        assertEquals("https://b.org/", KeylessSearchSources.absoluteUrl("//b.org/", base))
        assertEquals("javascript:void(0)", KeylessSearchSources.absoluteUrl("javascript:void(0)", base))
    }

    @Test
    fun `chain order is sogou then bing then baidu`() {
        assertEquals(
            listOf("搜狗", "Bing RSS", "百度"),
            KeylessSearchSources.chain.map { it.label },
        )
    }

    @Test
    fun `url builders encode queries`() {
        val (sogou, bing, baidu) = KeylessSearchSources.chain
        assertTrue(sogou.urlFor("华为 手机").contains("%E5%8D%8E%E4%B8%BA"))
        assertTrue(sogou.urlFor("a b").contains("query=a+b"))
        assertTrue(bing.urlFor("kotlin").contains("q=kotlin"))
        assertTrue(bing.urlFor("kotlin").contains("format=rss"))
        assertTrue(baidu.urlFor("kotlin").contains("wd=kotlin"))
    }

    @Test
    fun `desktopHeaders carries desktop UA and referer`() {
        val h = KeylessSearchSources.desktopHeaders("kotlin")
        assertTrue(h["User-Agent"]!!.contains("Windows NT"))
        assertEquals("https://www.bing.com/", h["Referer"])
        assertTrue(h["Accept"]!!.contains("text/html"))
    }
}
