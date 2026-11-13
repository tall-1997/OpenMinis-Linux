package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

/**
 * [T-tool-card-semantic-detail v337] TDD RED for the regression the user
 * reported after #336 shipped: "read card only shows a path, not the detail
 * it used to show; grep and glob cards have the same problem".
 *
 * Live evidence (2026-11-13, `minis.db` parts_json, device 2.0.90 with #336
 * dex literals present):
 *   {"type":"tool_use","name":"file_read","description":"Reading /var/minis/workspace/facts.txt",
 *    "input":{"path":"/var/minis/workspace/facts.txt"}}        <- no semantic detail
 *   {"type":"tool_use","name":"file_read","description":"看IAM壳页完整内容",
 *    "input":{"tool_title":"看IAM壳页完整内容","path":"..."}}   <- detail present
 * The ONLY difference is whether the model happened to pass `tool_title`.
 * `resolveToolTitle` filled the gap with an argument-derived path, so the
 * card line became the path itself. #336 fixed "half path" to "full path"
 * and left the collapsed field untouched.
 *
 * v337 separates the two meanings:
 *   title  = semantic label (model `tool_title` > friendly tool name)
 *   detail = full argument summary (path / pattern / command / query)
 * This test pins the contract BEFORE the implementation exists (RED).
 * ASCII-only literals: the CJK ratchet counts per-file string literals and a
 * new test file starts with an allowance of zero.
 */
class ToolCardPresentationTest {

    @Test fun untitledFileReadHasSemanticTitleAndSeparatePathDetail() {
        val p = presentToolCard("file_read", """{"path":"/var/minis/workspace/facts.txt"}""")
        assertEquals("Read File", p.title)
        assertEquals("/var/minis/workspace/facts.txt", p.detail)
        assertFalse("the primary card line must never be a path", p.title.contains("/"))
        assertTrue(p.detail.contains("facts.txt"))
    }

    @Test fun modelTitleStillWinsAndDetailStaysIndependent() {
        val p = presentToolCard(
            "file_read",
            """{"tool_title":"kan IAM shell page","path":"/a/shell.html"}""",
            "Reading /var/min",
        )
        assertEquals("kan IAM shell page", p.title)
        assertEquals("/a/shell.html", p.detail)
        assertFalse(p.title.contains("/"))
    }

    @Test fun untitledGrepHasSemanticTitleAndPatternDetail() {
        val p = presentToolCard("grep", """{"pattern":"resolveToolTitle","path":"/src"}""")
        assertEquals("Grep", p.title)
        assertTrue(p.detail.contains("resolveToolTitle"))
        assertFalse(p.title.contains("resolveToolTitle"))
    }

    @Test fun untitledGlobHasSemanticTitleAndPatternDetail() {
        val p = presentToolCard("glob", """{"pattern":"**/*.kt"}""")
        assertEquals("Glob", p.title)
        assertTrue(p.detail.contains("**/*.kt"))
        assertFalse(p.title.contains("**.kt") || p.title.contains("**/*.kt"))
    }

    @Test fun shellTitleAndCommandDetailAreSeparated() {
        val p = presentToolCard(
            "shell_execute",
            """{"tool_title":"cha kan source tree","command":"ls -la"}""",
        )
        assertEquals("cha kan source tree", p.title)
        assertTrue(p.detail.contains("ls -la"))
        assertFalse(p.title.contains("ls -la"))
    }

    @Test fun historicalDerivedShadowSelfHealsIntoSemanticTitle() {
        // Already-persisted #334/#336 cards carry the old collapsed field.
        // Rendering them must not resurrect the path as the primary line.
        val p = presentToolCard(
            "file_read",
            """{"path":"/old/session/path.kt"}""",
            "Reading /var/min",
        )
        assertEquals("Read File", p.title)
        assertEquals("/old/session/path.kt", p.detail)
        val g = presentToolCard(
            "grep", """{"pattern":"resolveToolTitle"}""", "Grep resolve",
        )
        assertEquals("Grep", g.title)
        assertTrue(g.detail.contains("resolveToolTitle"))
    }

    @Test fun bareNameOrFriendlyPriorIsNotASemanticTitle() {
        val a = presentToolCard("file_read", """{"path":"/a"}""", "file_read")
        assertEquals("Read File", a.title)
        assertEquals("/a", a.detail)
        val b = presentToolCard("file_read", """{"path":"/a"}""", "Read File")
        assertEquals("Read File", b.title)
        assertEquals("/a", b.detail)
    }

    @Test fun streamingPartialArgsNeverFabricateATitleOrDetail() {
        val p = presentToolCard("file_read", """{"path":"/var/min""")
        assertEquals("Read File", p.title)
        assertFalse(p.title.contains("/var/min"))
        assertTrue(p.detail.isEmpty())
    }

    @Test fun emptyArgsStillHaveAReadableTitle() {
        val p = presentToolCard("grep", "{}")
        assertEquals("Grep", p.title)
        assertTrue(p.detail.isEmpty())
    }

    @Test fun modelTitleStartingWithADerivedPrefixIsStillAuthoritative() {
        // A model may legitimately write "Plan the fix" / "Search related
        // docs". Only a PERSISTED prior is subject to derived-shadow
        // self-heal; the model's own tool_title is never second-guessed.
        assertEquals(
            "Plan the fix",
            resolveToolTitle("agent_plan", """{"tool_title":"Plan the fix","title":"v337"}"""),
        )
        // A semantic prior with such a verb phrase is also kept (it is not a
        // derived label), while a genuine #334 shadow self-heals.
        assertEquals("Plan the fix", resolveToolTitle("file_read", """{"path":"/a"}""", "Plan the fix"))
        assertEquals("Read File", resolveToolTitle("file_read", """{"path":"/a"}""", "Reading /var/min"))
    }

    @Test fun resolveToolTitleRemainsCompatibleForExistingCallers() {
        // 16 existing call sites keep working: the settlement still yields one
        // string, now always a semantic/friendly label (never an argument).
        assertEquals("Read File", resolveToolTitle("file_read", """{"path":"/a/b.kt"}"""))
        assertEquals("Grep", resolveToolTitle("grep", """{"pattern":"abc"}"""))
        assertEquals("Glob", resolveToolTitle("glob", """{"pattern":"**/*.kt"}"""))
        assertEquals(
            "cha kan source tree",
            resolveToolTitle("shell_execute", """{"tool_title":"cha kan source tree","command":"ls"}"""),
        )
    }
}
