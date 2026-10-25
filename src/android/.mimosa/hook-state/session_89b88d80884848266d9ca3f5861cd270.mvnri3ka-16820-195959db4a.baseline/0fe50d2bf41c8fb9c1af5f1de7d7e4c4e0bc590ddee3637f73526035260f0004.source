package com.openminis.app.data

/**
 * When plan discussion is AUTO, skip short acknowledgements / chitchat and
 * only open the board for task-shaped user messages.
 */
object PlanDiscussionTrigger {

    private val CHITCHAT = Regex(
        """^(ok|okay|thanks|thank you|thx|got it|continue|yes|no|yep|yeah|hi|hello|lol|lmao|继续|好的|谢谢|嗯+|哈+|你好)[\s!！.。?？]*$""",
        RegexOption.IGNORE_CASE,
    )

    private val TASKISH = Regex(
        """(?i)(implement|fix|refactor|design|plan|architect|migrate|debug|reproduce|optimize|add |build |rewrite|方案|设计|计划|实现|修复|重构|迁移|优化|帮我|为什么|怎么|改一下|排查|复现)""",
    )

    fun shouldRun(mode: PlanDiscussionPrefs.Mode, userText: String): Boolean {
        val text = userText.trim()
        if (text.isEmpty()) return false
        return when (mode) {
            PlanDiscussionPrefs.Mode.OFF -> false
            PlanDiscussionPrefs.Mode.ALWAYS -> true
            PlanDiscussionPrefs.Mode.AUTO -> looksLikeTask(text)
        }
    }

    fun looksLikeTask(text: String): Boolean {
        val t = text.trim()
        if (t.length < 24) return false
        if (CHITCHAT.matches(t)) return false
        if (t.length >= 80) return true
        return TASKISH.containsMatchIn(t)
    }
}
