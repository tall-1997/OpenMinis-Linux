package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

/**
 * [T-tool-title-single-source GH#344 / T-tool-card-detail-regression]
 *
 * Three successive invariants, each pinned by CI gate
 * scripts/ci/check_tool_title_and_budget_contract.py (section C):
 *  #331/#332  a bare snake_case name never reaches the card;
 *  #334       a STREAMING half-path ("Reading /var/min") must never be frozen
 *             into the persisted description;
 *  this fix   COMPLETE args are the single authoritative settlement point, so
 *             already-persisted half-path shadows self-heal on render.
 *
 * 2.0.90 (68fab74a) had no resolver: the card showed the model's `tool_title`
 * verbatim, which worked only while the model reliably supplied it. #334 added
 * argument derivation over PARTIAL JSON and step 2 trusted that derived shadow
 * forever — every tool without a model title collapsed to a truncated path.
 * Only shell_execute (schema carries tool_title, model supplies it) and the
 * thinking card (not a tool block) survived; every other tool block routes
 * through this one resolver, so these tests cover the whole surface.
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
        // A #334 half-path shadow can survive in a persisted block; the model's
        // own title in the complete args must still win (step 1, no prior trust).
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

    // ── #334 regression: streaming partial JSON ──────────────────────────

    @Test fun streamingHalfPathNeverBecomesATitle() {
        // The exact bug: `{"path": "/va` used to derive "Reading /va".
        assertEquals("Read File", resolveToolTitle("file_read", """{"path": "/va"""))
        assertEquals("Read File", resolveToolTitle("file_read", "{\"path\":\""))
        assertEquals("Glob", resolveToolTitle("glob", """{"pattern": "**/*.k"""))
        assertEquals("Grep", resolveToolTitle("grep", """{"pattern": "resolveT"""))
    }

    @Test fun partialToolTitleIsPreviewOnly() {
        // A streaming `tool_title` is the model's own words — a legitimate
        // transient preview. Partial PATH args never fabricate a title; the
        // friendly label is replaced by the full derivation once args complete.
        assertEquals(
            "读技能避坑说明",
            resolveToolTitle("file_read", """{"tool_title": "读技能避坑说明"""),
        )
        assertEquals("Read File", resolveToolTitle("file_read", """{"path": "/va"""))
        assertEquals(
            "Reading /var/minis/a.kt",
            resolveToolTitle("file_read", """{"path":"/var/minis/a.kt"}""", "Read File"),
        )
    }

    // ── Idempotent settlement / history self-heal ─────────────────────────

    @Test fun completeArgsOverwriteStaleHalfPath() {
        val full = "/var/minis/skills/superpowers/skills/systematic-debugging/SKILL.md"
        assertEquals(
            "Reading $full",
            resolveToolTitle("file_read", """{"path":"$full"}""", "Reading /var/min"),
        )
        // Settlement is idempotent: shadow and full title converge to one value.
        assertEquals(
            resolveToolTitle("file_read", """{"path":"$full"}""", "Reading /var/min"),
            resolveToolTitle("file_read", """{"path":"$full"}""", "Reading $full"),
        )
    }

    @Test fun historyHalfPathShadowSelfHeals() {
        // 2026-11-13 session DB: 12 cards with description="Reading /var/min…"
        // but a FULL path still present in `input`. Rendering must recompute.
        assertEquals(
            "Reading /old/session/path.kt",
            displayToolTitle("file_read", """{"path":"/old/session/path.kt"}""", "Reading /var/min"),
        )
        assertEquals(
            "Viewing /old/image.png",
            displayToolTitle("read_image", """{"path":"/old/image.png"}""", "Reading /var"),
        )
        assertEquals(
            "Grep resolveToolTitle",
            displayToolTitle("grep", """{"pattern":"resolveToolTitle"}""", "Grep resolve"),
        )
    }

    @Test fun existingSpecificTitleIsKept() {
        // A genuine semantic prior (no derived-label prefix) is preserved —
        // e.g. an online tool that strips tool_title before persisting args.
        assertEquals(
            "读取停滞判定与终止策略现状",
            resolveToolTitle("file_read", """{"path":"/a"}""", "读取停滞判定与终止策略现状"),
        )
    }

    // ── Derivation still works on COMPLETE args for every tool ────────────

    @Test fun untitledFileReadShowsThePath() {
        assertEquals(
            "Reading /var/minis/workspace/OpenMinis-Linux/README.md",
            resolveToolTitle("file_read", """{"path":"/var/minis/workspace/OpenMinis-Linux/README.md"}"""),
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
        assertEquals("Grep", resolveToolTitle("grep", "{}", "Grep"))
    }

    @Test fun longPathIsTruncatedWithEllipsis() {
        val longPath = "/" + "a".repeat(200)
        val title = resolveToolTitle("file_read", """{"path":"$longPath"}""")
        assertTrue(title.startsWith("Reading /"))
        assertTrue(title.endsWith("…"))
        assertTrue(title.length <= 110)
    }
}
