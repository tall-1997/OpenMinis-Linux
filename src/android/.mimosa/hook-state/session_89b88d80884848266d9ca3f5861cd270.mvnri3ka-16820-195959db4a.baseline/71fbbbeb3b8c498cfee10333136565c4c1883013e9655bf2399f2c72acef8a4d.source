package com.openminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserQuestionTest {

    @Test
    fun `parses JSON-array questions string`() {
        val params = JSONObject().put(
            "questions",
            """[{"question":"Where?","header":"Dest","options":[{"label":"Phone"},{"label":"NAS"}],"multiSelect":false}]""",
        )
        val qs = AskUserQuestion.parse(params)
        assertEquals(1, qs.size)
        assertEquals("Where?", qs[0].question)
        assertEquals("Dest", qs[0].header)
        assertEquals(listOf("Phone", "NAS"), qs[0].options.map { it.label })
    }

    @Test
    fun `rejects questions with fewer than two options`() {
        val params = JSONObject().put(
            "questions",
            """[{"question":"Only one?","options":[{"label":"A"}]}]""",
        )
        assertTrue(AskUserQuestion.parse(params).isEmpty())
    }

    @Test
    fun `formatAnswers round-trips selected labels`() {
        val q = AskUserQuestion.Question(
            question = "Where?",
            header = "Dest",
            options = listOf(
                AskUserQuestion.Option("Phone"),
                AskUserQuestion.Option("NAS"),
            ),
        )
        val json = AskUserQuestion.formatAnswers(listOf(q), listOf(listOf("Phone", "Downloads")))
        val obj = JSONObject(json)
        val answers = obj.getJSONArray("answers").getJSONObject(0)
        assertEquals("Where?", answers.getString("question"))
        assertEquals("Phone", answers.getJSONArray("answers").getString(0))
        assertEquals("Downloads", answers.getJSONArray("answers").getString(1))
    }
}
