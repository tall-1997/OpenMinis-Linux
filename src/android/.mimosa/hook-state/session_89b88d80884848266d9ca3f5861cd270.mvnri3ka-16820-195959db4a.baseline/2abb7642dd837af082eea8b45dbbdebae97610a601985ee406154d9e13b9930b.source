package com.openminis.app.evolution

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneClassifierTest {

    @Test
    fun mapsSessionCategory() {
        assertEquals(SceneTag.BACKEND, SceneClassifier.classify("code", "New Chat"))
        assertEquals(SceneTag.WORKFLOW, SceneClassifier.classify("productivity", null))
        assertEquals(SceneTag.WRITING, SceneClassifier.classify("translation", "hello"))
        assertEquals(SceneTag.GENERAL, SceneClassifier.classify("chat", "hello"))
        assertEquals(SceneTag.GENERAL, SceneClassifier.classify(null, null, null))
    }

    @Test
    fun fallsBackToTitleKeywords() {
        assertEquals(SceneTag.BACKEND, SceneClassifier.classify(null, "gradle compile failed"))
        assertEquals(SceneTag.WORKFLOW, SceneClassifier.classify(null, "设置一个提醒"))
        assertEquals(SceneTag.WRITING, SceneClassifier.classify(null, "翻译合同草稿"))
    }

    @Test
    fun usesTranscriptWhenCategoryIsGeneric() {
        assertEquals(
            SceneTag.BACKEND,
            SceneClassifier.classify("chat", "New Chat", "please debug the gradle compile"),
        )
        assertEquals(
            SceneTag.WORKFLOW,
            SceneClassifier.classify("other", null, "帮我设一个待办提醒"),
        )
    }
}
