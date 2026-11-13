package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

/**
 * [T-tool-card-row-single-line v338] The collapsed tool-card row renders ONE
 * line: the semantic headline. The secondary argument line (path / pattern /
 * command) is detail-sheet-only — the user judged it visually noisy in the
 * message list ("卡片下面显示的路径太影响整体观感了，改成不显示").
 *
 * This pins the RENDERING contract, not the resolver contract (that is
 * ToolCardPresentationTest): the presentation still carries both fields, but
 * the collapsed row must consume ONLY [ToolCardPresentation.title]. The
 * detail sheet keeps the full three-tier header (eyebrow / headline /
 * argument line) — the information stays one tap away.
 *
 * The rendering decision is exposed as a pure function so it is
 * JVM-unit-testable without Robolectric: [collapsedRowLabel] mirrors exactly
 * what ChatProcessCards feeds ToolCardLabel for the collapsed row.
 */
class CollapsedRowSingleLineTest {

    @Test fun collapsedRowShowsSemanticTitleOnly() {
        val p = presentToolCard("file_read", """{"path":"/var/minis/workspace/facts.txt"}""")
        assertEquals("Read File", collapsedRowLabel(p))
        assertFalse("collapsed row must not render the path", collapsedRowLabel(p).contains("facts.txt"))
    }

    @Test fun collapsedRowWithModelTitleShowsModelTitleOnly() {
        val p = presentToolCard(
            "file_read",
            """{"tool_title":"kan IAM shell page","path":"/a/shell.html"}""",
        )
        assertEquals("kan IAM shell page", collapsedRowLabel(p))
        assertFalse(collapsedRowLabel(p).contains("shell.html"))
    }

    @Test fun collapsedRowForGrepGlobShellShowsTitleNotArguments() {
        val g = presentToolCard("grep", """{"pattern":"resolveToolTitle","path":"/src"}""")
        assertEquals("Grep", collapsedRowLabel(g))
        assertFalse(collapsedRowLabel(g).contains("resolveToolTitle"))
        val b = presentToolCard("glob", """{"pattern":"**/*.kt"}""")
        assertEquals("Glob", collapsedRowLabel(b))
        val s = presentToolCard(
            "shell_execute",
            """{"tool_title":"cha kan source tree","command":"ls -la"}""",
        )
        assertEquals("cha kan source tree", collapsedRowLabel(s))
        assertFalse(collapsedRowLabel(s).contains("ls -la"))
    }

    @Test fun collapsedRowNeverShowsEmptyText() {
        // Even a tool with no args and no title must render a readable line.
        assertEquals("Grep", collapsedRowLabel(presentToolCard("grep", "{}")))
    }

    @Test fun detailFieldStillCarriesArgumentsForTheDetailSheet() {
        // The resolver contract is untouched: the detail sheet keeps showing
        // the full argument line under the headline. Only the collapsed row
        // stopped consuming it.
        val p = presentToolCard("file_read", """{"path":"/var/minis/workspace/facts.txt"}""")
        assertEquals("/var/minis/workspace/facts.txt", p.detail)
    }
}
