package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

/**
 * [T-tool-title-single-source GH#344] Regression: file_read / read_image
 * cards showed raw snake_case names (2026-11-12 17:48Z screenshot) because
 * executeTool fell back to the canonical name, finalizePlannedCall's
 * `ifEmpty` kept that non-empty bare name, and the renderer had no args
 * fallback. The resolver must produce a concrete title at every lifecycle
 * stage and on history restore.
 */
class ToolTitleResolverTest {

    @Test fun untitledFileReadShowsThePath() {
        val args = """{"path":"/var/minis/workspace/OpenMinis-Linux/README.md"}"""
        assertEquals(
            "Reading /var/minis/workspace/OpenMinis-Linux/README.md",
            resolveToolTitle("file_read", args),
        )
    }

    @Test fun untitledReadImageShowsThePath() {
        assertEquals(
            "Viewing /var/minis/attachments/chart.png",
            resolveToolTitle("read_image", """{"path":"/var/minis/attachments/chart.png"}"""),
        )
    }

    @Test fun readImageSupportsImagePathAlias() {
        assertEquals(
            "Viewing /sdcard/a.png",
            resolveToolTitle("read_image", """{"image_path":"/sdcard/a.png"}"""),
        )
    }

    @Test fun modelProvidedTitleWins() {
        assertEquals(
            "读取停滞判定与终止策略现状",
            resolveToolTitle("file_read", """{"tool_title":"读取停滞判定与终止策略现状","path":"/a"}"""),
        )
    }

    @Test fun bareNameAsToolTitleIsRejected() {
        // Some tool schemas default tool_title to NAME — that is not a title.
        assertEquals(
            "Reading /a/b.kt",
            resolveToolTitle("file_read", """{"tool_title":"file_read","path":"/a/b.kt"}"""),
        )
        assertEquals(
            "Read Image",
            resolveToolTitle("read_image", """{"tool_title":"read_image"}"""),
        )
    }

    @Test fun streamingPartialArgsStillProduceATitle() {
        assertEquals(
            "Reading /va",
            resolveToolTitle("file_read", """{"path": "/va"""),
        )
        assertEquals(
            "Read File",
            resolveToolTitle("file_read", "{\"path\":\""),
        )
    }

    @Test fun existingSpecificTitleIsKept() {
        assertEquals(
            "读取停滞判定与终止策略现状",
            resolveToolTitle("file_read", """{"path":"/a"}""", "读取停滞判定与终止策略现状"),
        )
    }

    @Test fun historyBareNameIsUpgradedFromArgs() {
        // Restore persisted the bare name; rendering must reconstruct a real title.
        assertEquals(
            "Reading /old/session/path.kt",
            displayToolTitle("file_read", """{"path":"/old/session/path.kt"}""", "file_read"),
        )
        assertEquals(
            "Viewing /old/image.png",
            displayToolTitle("read_image", """{"path":"/old/image.png"}""", "read_image"),
        )
    }

    @Test fun genericFriendlyLabelIsUpgradedFromArgs() {
        assertEquals(
            "Reading /new/path.kt",
            resolveToolTitle("file_read", """{"path":"/new/path.kt"}""", "Read File"),
        )
    }

    @Test fun aliasesRouteThroughTheSingleCanonicalMap() {
        assertEquals("Viewing /a", resolveToolTitle("describe_image", """{"path":"/a"}"""))
        assertEquals("${'$'} ls -la", resolveToolTitle("shell", """{"command":"ls -la"}"""))
        assertEquals("Listing /tmp", resolveToolTitle("ls", """{"path":"/tmp"}"""))
        assertEquals("Glob *.kt", resolveToolTitle("find_files", """{"pattern":"*.kt"}"""))
    }

    @Test fun shellAndBrowserSummaries() {
        assertEquals("${'$'} git status", resolveToolTitle("shell_execute", """{"command":"git status"}"""))
        assertEquals(
            "navigate https://example.com",
            resolveToolTitle("browser_use", """{"action":"navigate","url":"https://example.com"}"""),
        )
    }

    @Test fun dirtyJsonNeverCrashesAndNeverReturnsBareName() {
        assertEquals("Read File", resolveToolTitle("file_read", "{not json"))
        assertEquals("My Tool", resolveToolTitle("my_tool", ""))
        assertEquals("Read File", resolveToolTitle("file_read", ""))
    }

    @Test fun longPathIsTruncatedWithEllipsis() {
        val longPath = "/" + "a".repeat(200)
        val title = resolveToolTitle("file_read", """{"path":"$longPath"}""")
        assertTrue(title.startsWith("Reading /"))
        assertTrue(title.endsWith("…"))
        assertTrue(title.length <= 110)
    }
}
