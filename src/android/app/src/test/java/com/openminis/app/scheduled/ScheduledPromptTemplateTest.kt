package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * [GH#201] Pure-Kotlin coverage for the scheduled prompt template: the
 * fire-time date header and every documented variable. No Android/Robolectric
 * needed, so it pins the formatting contract independently of the runner.
 */
class ScheduledPromptTemplateTest {

    private val tz = TimeZone.getTimeZone("Asia/Shanghai")
    private val zh = Locale.SIMPLIFIED_CHINESE
    private val en = Locale.US

    // 2026-11-12 09:30:00 Asia/Shanghai (Thursday)
    private val now = 1_794_447_000_000L
    private val lastRun = now - 86_400_000L

    @Test
    fun substitutesDocumentedVariables() {
        val out = ScheduledPromptTemplate.substitute(
            "date={{date}} time={{time}} dt={{datetime}} wd={{weekday}} tz={{timezone}} last={{last_fired}} ago={{last_fired_ago}} label={{task_label}}",
            now = now,
            lastFiredAt = lastRun,
            taskLabel = "每日简报",
            timeZone = tz,
            locale = zh,
        )
        assertTrue(out, out.contains("date=2026-11-12"))
        assertTrue(out, out.contains("time=09:30"))
        assertTrue(out, out.contains("dt=2026-11-12 09:30"))
        assertTrue(out, out.contains("wd=周四"))
        assertTrue(out, out.contains("tz=Asia/Shanghai"))
        assertTrue(out, out.contains("last=2026-11-11 09:30"))
        assertTrue(out, out.contains("ago=1 天前"))
        assertTrue(out, out.contains("label=每日简报"))
    }

    @Test
    fun unknownVariablesAreLeftVerbatim() {
        val out = ScheduledPromptTemplate.substitute(
            "keep {{unknown}} and {{ date }}",
            now = now,
            timeZone = tz,
            locale = zh,
        )
        assertEquals("keep {{unknown}} and 2026-11-12", out)
    }

    @Test
    fun headerCarriesDateAndPreviousFire() {
        val header = ScheduledPromptTemplate.buildHeader(
            now = now,
            lastFiredAt = lastRun,
            taskLabel = "Morning brief",
            timeZone = tz,
            locale = en,
        )
        assertTrue(header, header.contains("2026-11-12 09:30"))
        assertTrue(header, header.contains("Thu"))
        assertTrue(header, header.contains("2026-11-11 09:30"))
        assertTrue(header, header.contains("Morning brief"))
        assertTrue(header, header.contains("Asia/Shanghai"))
    }

    @Test
    fun firstRunSaysNever() {
        val header = ScheduledPromptTemplate.buildHeader(
            now = now,
            lastFiredAt = null,
            taskLabel = "",
            timeZone = tz,
            locale = zh,
        )
        assertTrue(header, header.contains("从未"))
    }

    @Test
    fun renderPrefixesHeaderAndKeepsBody() {
        val out = ScheduledPromptTemplate.render(
            prompt = "总结 {{date}} 的新闻",
            now = now,
            lastFiredAt = lastRun,
            taskLabel = "简报",
            timeZone = tz,
            locale = zh,
        )
        assertTrue(out.startsWith("[定时任务]"))
        assertTrue(out, out.contains("总结 2026-11-12 的新闻"))
        // Header comes first, body after the blank line.
        assertTrue(out.indexOf("[定时任务]") < out.indexOf("总结 2026-11-12"))
    }
}
