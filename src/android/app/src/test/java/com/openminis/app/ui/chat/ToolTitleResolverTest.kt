package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

/**
 * [T-tool-title-single-source GH#344 / T-tool-card-detail-regression v337]
 *
 * Successive invariants, each pinned by CI gate
 * scripts/ci/check_tool_title_and_budget_contract.py (sections B/C/D):
 *  #331/#332  a bare snake_case name never reaches the card;
 *  #334       a STREAMING half-path ("Reading /var/min") must never be frozen
 *             into the persisted description;
 *  #336       COMPLETE args are the single authoritative settlement point;
 *  v337       the model's semantic `tool_title` and the argument summary are
 *             SEPARATE fields. A path/pattern/command may never become the
 *             headline — it is rendered as its own secondary line. This is
 *             the regression the user reported after #336 shipped.
 *
 * 2.0.90 (68fab74a) showed the model's `tool_title` verbatim. #334 added
 * argument derivation over PARTIAL JSON; #336 fixed half-path -> full-path but
 * kept the collapsed field, so cards without a model title still showed only
 * a path. Only shell_execute (schema carries tool_title, model supplies it)
 * and the thinking card (not a tool block) survived. v337 routes every tool
 * block through one [presentToolCard] settlement, with the companion
 * [ToolCardPresentationTest] pinning the two-field contract.
 */
class ToolTitleResolverTest {

    // ── Model-supplied semantic title (the detail line) ──────────────────

    @Test fun modelProvidedTitleWins() {
        assertEquals(
            "读取停滞判定与终止策略现状",
            resolveToolTitle("file_read", """{"tool_title":"读取停滞判定与终止策略现状","path":"/a"}"""),
        )
        assertEquals(
            "查看现有源码目录",
            resolveToolTitle("glob", """{"tool_title":"查看现有源码目录","pattern":"**/*.kt"}"""),
        )
    }

    @Test fun modelTitleOnCompleteArgsBeatsStalePrior() {
        assertEquals(
            "读技能避坑说明",
            resolveToolTitle("file_read", """{"tool_title":"读技能避坑说明","path":"/a"}""", "Reading /var/min"),
        )
        assertEquals(
            "查看现有源码目录",
            resolveToolTitle("glob", """{"tool_title":"查看现有源码目录","pattern":"**/*.kt"}""", "Glob **/*"),
        )
    }

    @Test fun bareNameAsToolTitleIsRejected() {
        assertEquals(
            "Read File",
            resolveToolTitle("file_read", """{"tool_title":"file_read","path":"/a/b.kt"}"""),
        )
        assertEquals(
            "Read Image",
            resolveToolTitle("read_image", """{"tool_title":"read_image"}"""),
        )
        assertEquals(
            "Read File",
            resolveToolTitle("file_read", """{"tool_title":"Read File","path":"/a"}"""),
        )
    }

    // ── #334 regression: streaming partial JSON ──────────────────────────

    @Test fun streamingHalfPathNeverBecomesATitle() {
        assertEquals("Read File", resolveToolTitle("file_read", """{"path": "/va"""))
        assertEquals("Read File", resolveToolTitle("file_read", "{\"path\":\""))
        assertEquals("Glob", resolveToolTitle("glob", """{"pattern": "**/*.k"""))
        assertEquals("Grep", resolveToolTitle("grep", """{"pattern": "resolveT"""))
    }

    @Test fun partialToolTitleIsPreviewOnly() {
        assertEquals(
            "读技能避坑说明",
            resolveToolTitle("file_read", """{"tool_title": "读技能避坑说明"""),
        )
        assertEquals("Read File", resolveToolTitle("file_read", """{"path": "/va"""))
        assertEquals("Read File", resolveToolTitle("file_read", """{"path":"/var/minis/a.kt"}""", "Read File"))
    }

    // ── Idempotent settlement / history self-heal ─────────────────────────

    @Test fun completeArgsOverwriteStaleHalfPath() {
        val full = "/var/minis/skills/superpowers/skills/systematic-debugging/SKILL.md"
        assertEquals("Read File", resolveToolTitle("file_read", """{"path":"$full"}""", "Reading /var/min"))
        assertEquals(
            resolveToolTitle("file_read", """{"path":"$full"}""", "Reading /var/min"),
            resolveToolTitle("file_read", """{"path":"$full"}""", "Reading $full"),
        )
        assertEquals(full, presentToolCard("file_read", """{"path":"$full"}""", "Reading /var/min").detail)
    }

    @Test fun historyHalfPathShadowSelfHeals() {
        assertEquals(
            "Read File",
            displayToolTitle("file_read", """{"path":"/old/session/path.kt"}""", "Reading /var/min"),
        )
        assertEquals(
            "Read Image",
            displayToolTitle("read_image", """{"path":"/old/image.png"}""", "Reading /var"),
        )
        assertEquals(
            "Grep",
            displayToolTitle("grep", """{"pattern":"resolveToolTitle"}""", "Grep resolve"),
        )
    }

    @Test fun existingSpecificTitleIsKept() {
        assertEquals(
            "读取停滞判定与终止策略现状",
            resolveToolTitle("file_read", """{"path":"/a"}""", "读取停滞判定与终止策略现状"),
        )
    }

    // ── Argument detail is a SEPARATE line, never the headline ─────────────

    @Test fun untitledFileReadShowsThePathAsDetail() {
        val p = presentToolCard("file_read", """{"path":"/var/minis/workspace/OpenMinis-Linux/README.md"}""")
        assertEquals("Read File", p.title)
        assertEquals("/var/minis/workspace/OpenMinis-Linux/README.md", p.detail)
    }

    @Test fun untitledReadImageShowsThePathAsDetail() {
        val p = presentToolCard("read_image", """{"path":"/var/minis/attachments/chart.png"}""")
        assertEquals("Read Image", p.title)
        assertEquals("/var/minis/attachments/chart.png", p.detail)
    }

    @Test fun readImageSupportsImagePathAlias() {
        assertEquals(
            "/sdcard/a.png",
            presentToolCard("read_image", """{"image_path":"/sdcard/a.png"}""").detail,
        )
    }

    @Test fun historyBareNameIsUpgradedFromArgs() {
        assertEquals(
            "Read File",
            displayToolTitle("file_read", """{"path":"/old/session/path.kt"}""", "file_read"),
        )
        assertEquals(
            "Read Image",
            displayToolTitle("read_image", """{"path":"/old/image.png"}""", "read_image"),
        )
        assertEquals(
            "/old/session/path.kt",
            presentToolCard("file_read", """{"path":"/old/session/path.kt"}""", "file_read").detail,
        )
    }

    @Test fun genericFriendlyLabelIsNeverUpgradedIntoAPath() {
        assertEquals("Read File", resolveToolTitle("file_read", """{"path":"/new/path.kt"}""", "Read File"))
        assertEquals("/new/path.kt", presentToolCard("file_read", """{"path":"/new/path.kt"}""", "Read File").detail)
    }

    @Test fun aliasesRouteThroughTheSingleCanonicalMap() {
        assertEquals("/a", presentToolCard("describe_image", """{"path":"/a"}""").detail)
        assertEquals("${'$'} ls -la", presentToolCard("shell", """{"command":"ls -la"}""").detail)
        assertEquals("/tmp", presentToolCard("ls", """{"path":"/tmp"}""").detail)
        assertEquals("*.kt", presentToolCard("find_files", """{"pattern":"*.kt"}""").detail)
    }

    @Test fun shellAndBrowserSummaries() {
        assertEquals("${'$'} git status", presentToolCard("shell_execute", """{"command":"git status"}""").detail)
        assertEquals(
            "navigate https://example.com",
            presentToolCard("browser_use", """{"action":"navigate","url":"https://example.com"}""").detail,
        )
    }

    @Test fun dirtyJsonNeverCrashesAndNeverReturnsBareName() {
        assertEquals("Read File", resolveToolTitle("file_read", "{not json"))
        assertEquals("My Tool", resolveToolTitle("my_tool", ""))
        assertEquals("Read File", resolveToolTitle("file_read", ""))
        assertEquals("Grep", resolveToolTitle("grep", "{}", "Grep"))
    }

    @Test fun longDetailIsTruncatedWithEllipsisButTitleStaysClean() {
        val longPath = "/" + "a".repeat(200)
        val p = presentToolCard("file_read", """{"path":"$longPath"}""")
        assertEquals("Read File", p.title)
        assertTrue(p.detail.startsWith("/"))
        assertTrue(p.detail.endsWith("…"))
        assertTrue(p.detail.length <= 161)
    }
}
