package com.openminis.app.accessibility

import org.junit.Assert.assertTrue
import org.junit.Test

class A11yScriptRecorderTest {
    @Test
    fun markdownContainsCliAndDisclaimer() {
        val steps = listOf(
            A11yScriptRecorder.Step(
                kind = A11yScriptRecorder.Step.Kind.TAP,
                packageName = "com.example",
                text = "OK",
                viewId = "com.example:id/ok",
                contentDescription = null,
                xy = "10,20",
                atMs = 1L,
            ),
            A11yScriptRecorder.Step(
                kind = A11yScriptRecorder.Step.Kind.INPUT,
                packageName = "com.example",
                text = "hello",
                viewId = null,
                contentDescription = null,
                xy = null,
                atMs = 2L,
            ),
        )
        val md = A11yScriptRecorder.toSkillMarkdown("recorded-login", steps)
        assertTrue(md.contains("Not a malware preset"))
        assertTrue(md.contains("android-a11y-cli tap id"))
        assertTrue(md.contains("android-a11y-cli input text"))
        assertTrue(md.contains("name: recorded-login"))
    }

    @Test
    fun shellQuoteEscapesQuotes() {
        assertTrue(A11yScriptRecorder.shellQuote("a\"b").contains("\\\""))
    }
}
