package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentPreviewArgsTest {

    @Test
    fun prefersCommandPathQueryUrl() {
        assertEquals("ls -la", SubAgentRunner.previewToolArgs("""{"command":"ls -la"}"""))
        assertEquals("/tmp/a.txt", SubAgentRunner.previewToolArgs("""{"path":"/tmp/a.txt"}"""))
        assertEquals("openminis", SubAgentRunner.previewToolArgs("""{"query":"openminis"}"""))
        assertEquals("https://example.com", SubAgentRunner.previewToolArgs("""{"url":"https://example.com"}"""))
    }

    @Test
    fun truncatesLongArgs() {
        val long = "c".repeat(200)
        val preview = SubAgentRunner.previewToolArgs("""{"command":"$long"}""")
        assertEquals(80, preview.length)
        assertTrue(preview.all { it == 'c' })
    }

    @Test
    fun composeOutputPutsTraceBeforeReport() {
        val out = SubAgentRunner.composeOutput(
            report = "found 2 files",
            timeline = "- turn 1: file_read `/tmp/a`\n  → hello",
        )
        assertTrue(out.startsWith("## Trace"))
        assertTrue(out.contains("## Report"))
        assertTrue(out.contains("found 2 files"))
        assertTrue(out.indexOf("## Trace") < out.indexOf("## Report"))
    }

    @Test
    fun composeOutputEmptyFallback() {
        assertEquals("(sub-agent finished with empty output)", SubAgentRunner.composeOutput("", ""))
    }

    @Test
    fun cardStepKeepsReportAndDropsTrace() {
        val out = SubAgentRunner.composeOutput(
            report = "found 2 files",
            timeline = "- turn 1: file_read `/tmp/a`\n  → hello",
        )
        assertEquals("found 2 files", SubAgentRunner.cardStep(out))
        assertEquals("已结束", SubAgentRunner.cardStep("## Trace\n- turn 1: file_read"))
        assertEquals("plain failure", SubAgentRunner.cardStep("plain failure"))
    }
}
