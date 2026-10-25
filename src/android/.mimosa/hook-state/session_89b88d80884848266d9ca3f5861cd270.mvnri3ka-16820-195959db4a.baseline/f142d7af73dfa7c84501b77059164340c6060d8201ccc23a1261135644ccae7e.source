package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native port of https://github.com/paulp-o/ask-user-questions-mcp
 * (`AskUserQuestion`): structured multiple-choice questions the agent can
 * pose when a choice is genuinely ambiguous.
 */
object AskUserQuestion {
    const val NAME = "ask_user_question"
    const val ALIAS = "AskUserQuestion"

    data class Option(
        val label: String,
        val description: String = "",
    )

    data class Question(
        val question: String,
        val header: String = "",
        val options: List<Option>,
        val multiSelect: Boolean = false,
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = """Ask the user one or more structured questions when a choice is genuinely ambiguous (product direction, mutually exclusive options, missing preference). Do NOT use this to ask permission for routine tool calls — just do those.

Mirrors the ask-user-questions MCP (`AskUserQuestion`). Pass `questions` as a JSON array of 1–4 items. Each item:
  question (string, required)
  header (short label, optional)
  options: 2–4 objects {label, description}
  multiSelect (boolean, default false)

The tool blocks until the user answers. Free-text is allowed via an Other option the UI always offers.""",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short live-status title, e.g. 'Choose backup location'."),
            "questions" to AgentToolParam(
                "string",
                "JSON array of questions (see tool description). Also accepted as a JSON array value.",
            ),
        ),
        required = listOf("questions"),
    )

    fun parse(params: JSONObject): List<Question> {
        val raw = params.opt("questions")
        val array = when (raw) {
            is JSONArray -> raw
            is String -> if (raw.isBlank()) JSONArray() else JSONArray(raw)
            is JSONObject -> JSONArray().put(raw)
            else -> JSONArray()
        }
        val parsed = mutableListOf<Question>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val q = obj.optString("question").trim()
            if (q.isEmpty()) continue
            val optionsArr = obj.optJSONArray("options") ?: JSONArray()
            val options = mutableListOf<Option>()
            for (j in 0 until optionsArr.length()) {
                val o = optionsArr.optJSONObject(j) ?: continue
                val label = o.optString("label").trim()
                if (label.isEmpty()) continue
                options.add(Option(label, o.optString("description").trim()))
            }
            if (options.size < 2) continue
            parsed.add(
                Question(
                    question = q,
                    header = obj.optString("header").trim(),
                    options = options.take(4),
                    multiSelect = obj.optBoolean("multiSelect", false),
                ),
            )
            if (parsed.size >= 4) break
        }
        return parsed
    }

    fun formatAnswers(questions: List<Question>, selections: List<List<String>>): String {
        val root = JSONObject()
        val answers = JSONArray()
        questions.forEachIndexed { i, q ->
            val picked = selections.getOrNull(i).orEmpty()
            answers.put(
                JSONObject().apply {
                    put("question", q.question)
                    put("header", q.header)
                    put("answers", JSONArray(picked))
                },
            )
        }
        root.put("answers", answers)
        return root.toString()
    }
}
