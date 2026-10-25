package com.openminis.app.evolution

import com.openminis.app.text.BoundedText

/**
 * Injection scenes for LEARNED.md. Untagged / [general] rules always inject.
 * Named after the evolution plan (`backend` / `workflow`); session categories
 * such as `code` / `productivity` map onto these.
 */
enum class SceneTag(val raw: String) {
    GENERAL("general"),
    BACKEND("backend"),
    WORKFLOW("workflow"),
    WRITING("writing");

    companion object {
        fun from(raw: String?): SceneTag =
            entries.find { it.raw.equals(raw, ignoreCase = true) } ?: GENERAL
    }
}

object SceneClassifier {

    private val BACKEND_KW = listOf(
        "kotlin", "python", "git", "compile", "gradle", "api", "server",
        "代码", "编译", "仓库", "报错", "debug", "refactor",
    )
    private val WORKFLOW_KW = listOf(
        "remind", "todo", "calendar", "schedule", "日程", "待办", "提醒", "流程", "闹钟",
    )
    private val WRITING_KW = listOf(
        "translate", "essay", "draft", "写作", "翻译", "润色", "文档",
    )

    fun classify(category: String?, title: String? = null, sample: String? = null): SceneTag {
        when (category?.lowercase()) {
            "code", "analysis", "math", "design" -> return SceneTag.BACKEND
            "productivity", "support", "finance" -> return SceneTag.WORKFLOW
            "writing", "translation", "creative", "education" -> return SceneTag.WRITING
        }
        val hay = BoundedText.icuWindow("${title.orEmpty()} ${sample.orEmpty()}").toString().lowercase()
        if (hay.isBlank()) return SceneTag.GENERAL
        if (BACKEND_KW.any { hay.contains(it) }) return SceneTag.BACKEND
        if (WORKFLOW_KW.any { hay.contains(it) }) return SceneTag.WORKFLOW
        if (WRITING_KW.any { hay.contains(it) }) return SceneTag.WRITING
        return SceneTag.GENERAL
    }
}
