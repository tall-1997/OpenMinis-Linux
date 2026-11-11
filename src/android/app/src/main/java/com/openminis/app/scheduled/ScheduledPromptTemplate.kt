package com.openminis.app.scheduled

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * [GH#201] 定时任务的当日日期 / 上次触发时间注入 + 模板变量。
 *
 * 背景：无人在场的定时任务最常失败在“模型不知道现在是哪天”。一个每天 9:00
 * 的简报任务，提示词写“整理今天的新闻”，模型只能猜“今天”是哪天；跨天的
 * 任务还会把上次的结论当成本次上下文。这里在**派发前**把时间和上一次触发
 * 时间前置进用户提示词，并支持在提示词里写模板变量：
 *
 * | 变量 | 含义 | 示例 |
 * |---|---|---|
 * | `{{date}}` | 本地日期 | `2026-11-12` |
 * | `{{time}}` | 本地时分 | `09:00` |
 * | `{{datetime}}` | 日期 + 时分 | `2026-11-12 09:00` |
 * | `{{weekday}}` | 星期 | `周三` |
 * | `{{timezone}}` | 时区 id | `Asia/Shanghai` |
 * | `{{last_fired}}` | 上次触发（从未 → `从未`） | `2026-11-11 09:00` |
 * | `{{last_fired_ago}}` | 距上次触发 | `1 天前` |
 * | `{{task_label}}` | 任务名称 | `每日简报` |
 *
 * 未识别的 `{{...}}` 原样保留（用户在提示词里写 Jinja/模板语法时不会被吃掉）。
 * 纯函数 + 注入 clock，方便单测；UI/执行层不需要知道格式化细节。
 */
object ScheduledPromptTemplate {

    /** 前置块上限，避免任务名称异常长时挤占真正的提示词。 */
    private const val MAX_HEADER_CHARS = 600

    /**
     * 渲染最终提示词。
     *
     * @param prompt 用户原始提示词（可含模板变量）。
     * @param now 本次触发时刻，默认当前时间。
     * @param lastFiredAt 上次触发时刻；null = 从未触发（含首次运行）。
     * @param taskLabel 任务名称，供 `{{task_label}}` 与前置块展示。
     */
    fun render(
        prompt: String,
        now: Long = System.currentTimeMillis(),
        lastFiredAt: Long? = null,
        taskLabel: String = "",
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val body = substitute(prompt, now, lastFiredAt, taskLabel, timeZone, locale)
        val header = buildHeader(
            now = now,
            lastFiredAt = lastFiredAt,
            taskLabel = taskLabel,
            timeZone = timeZone,
            locale = locale,
            mentionVariables = prompt.contains("{{"),
        )
        return if (body.isBlank()) header else "$header\n\n$body"
    }

    /** 只做变量替换，不前置时间块（测试/未来自定义模板可复用）。 */
    fun substitute(
        prompt: String,
        now: Long = System.currentTimeMillis(),
        lastFiredAt: Long? = null,
        taskLabel: String = "",
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (prompt.isEmpty()) return prompt
        val values = mapOf(
            "date" to format(now, "yyyy-MM-dd", timeZone, locale),
            "time" to format(now, "HH:mm", timeZone, locale),
            "datetime" to format(now, "yyyy-MM-dd HH:mm", timeZone, locale),
            "weekday" to weekday(now, timeZone, locale),
            "timezone" to timeZone.id,
            "last_fired" to (lastFiredAt?.let { format(it, "yyyy-MM-dd HH:mm", timeZone, locale) } ?: "从未"),
            "last_fired_ago" to (lastFiredAt?.let { ago(it, now, locale) } ?: "从未"),
            "task_label" to taskLabel,
        )
        // 单遍替换：替换进来的值即使包含 "{{date}}" 之类也不会被二次展开。
        val regex = Regex("\\{\\{\\s*([a-zA-Z_]+)\\s*\\}\\}")
        return regex.replace(prompt) { match ->
            values[match.groupValues[1].lowercase()] ?: match.value
        }
    }

    /**
     * 前置块。刻意保持行数少、信息密度高：模型每轮都读它，宁短勿长。
     * 时间用本地时区（与用户看到的日程一致），今天日期永远显式写出，
     * 不依赖模型自己的“现在”。
     */
    fun buildHeader(
        now: Long,
        lastFiredAt: Long?,
        taskLabel: String,
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
        /** True when the prompt actually contains `{{…}}` (adds the explainer line). */
        mentionVariables: Boolean = false,
    ): String {
        val label = taskLabel.trim().take(120)
        val header = buildString {
            append("[定时任务] 当前时间：")
            append(format(now, "yyyy-MM-dd HH:mm", timeZone, locale))
            append(" ")
            append(weekday(now, timeZone, locale))
            append("（")
            append(timeZone.id)
            append("）")
            append("\n上次触发：")
            append(lastFiredAt?.let { format(it, "yyyy-MM-dd HH:mm", timeZone, locale) } ?: "从未（首次运行）")
            if (label.isNotEmpty()) {
                append("\n任务：")
                append(label)
            }
            if (mentionVariables) {
                append("\n提示词中的 {{date}} / {{time}} / {{last_fired}} 已在下方替换为实际值。")
            }
        }
        return if (header.length <= MAX_HEADER_CHARS) header else header.take(MAX_HEADER_CHARS)
    }

    private fun format(at: Long, pattern: String, tz: TimeZone, locale: Locale): String =
        SimpleDateFormat(pattern, locale).apply { timeZone = tz }.format(Date(at))

    private fun weekday(at: Long, tz: TimeZone, locale: Locale): String {
        if (locale.language == "zh") {
            val cal = Calendar.getInstance(tz, locale).apply { timeInMillis = at }
            return when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "周一"
                Calendar.TUESDAY -> "周二"
                Calendar.WEDNESDAY -> "周三"
                Calendar.THURSDAY -> "周四"
                Calendar.FRIDAY -> "周五"
                Calendar.SATURDAY -> "周六"
                else -> "周日"
            }
        }
        return SimpleDateFormat("EEE", locale).apply { timeZone = tz }.format(Date(at))
    }

    /**
     * “距上次触发”的人话描述。只在粗粒度上取整：两次触发间隔内足以让模型
     * 判断“这是新的一天”还是“离上次只隔了几分钟”。
     */
    private fun ago(from: Long, now: Long, locale: Locale): String {
        val delta = (now - from).coerceAtLeast(0L)
        val zh = locale.language == "zh"
        val minutes = delta / 60_000L
        val hours = delta / 3_600_000L
        val days = delta / 86_400_000L
        return when {
            minutes < 1L -> if (zh) "刚刚" else "just now"
            hours < 1L -> if (zh) "$minutes 分钟前" else "$minutes min ago"
            days < 1L -> if (zh) "$hours 小时前" else "$hours h ago"
            else -> if (zh) "$days 天前" else "$days d ago"
        }
    }
}
