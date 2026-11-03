package com.openminis.app.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-m8-knowledge-workbench] Pure-function contract for the knowledge
 * workbench: wiki-link parsing, graph edges, deterministic force layout,
 * search-text folding and dedupe hashing. These functions must stay
 * deterministic and side-effect free so the graph page and the repository can
 * both consume them without shared mutable state.
 */
class KnowledgeNoteGraphTest {

    // ---- parseWikiLinks -------------------------------------------------

    @Test
    fun `single wiki link is parsed`() {
        assertEquals(listOf("Home"), KnowledgeNoteGraph.parseWikiLinks("see [[Home]]"))
    }

    @Test
    fun `multiple distinct links keep order`() {
        assertEquals(
            listOf("Alpha", "Beta"),
            KnowledgeNoteGraph.parseWikiLinks("[[Alpha]] and [[Beta]]"),
        )
    }

    @Test
    fun `duplicates collapse to first occurrence`() {
        assertEquals(
            listOf("Alpha", "Beta"),
            KnowledgeNoteGraph.parseWikiLinks("[[Alpha]] [[Beta]] [[Alpha]]"),
        )
    }

    @Test
    fun `links across newlines and inline are found`() {
        assertEquals(
            listOf("X", "Y"),
            KnowledgeNoteGraph.parseWikiLinks("a [[X]] b\nsecond line [[Y]] end"),
        )
    }

    @Test
    fun `empty bracket pair is ignored`() {
        assertEquals(emptyList<String>(), KnowledgeNoteGraph.parseWikiLinks("[[]]"))
    }

    @Test
    fun `half-open bracket is not parsed`() {
        assertEquals(emptyList<String>(), KnowledgeNoteGraph.parseWikiLinks("[[X and Y"))
    }

    @Test
    fun `no links returns empty`() {
        assertEquals(emptyList<String>(), KnowledgeNoteGraph.parseWikiLinks("plain text"))
    }

    // ---- edgesOf --------------------------------------------------------

    private fun note(id: String, title: String, links: List<String>) =
        KnowledgeNoteGraph.NoteLite(id = id, title = title, links = links)

    @Test
    fun `edge to an existing note title is kept`() {
        val notes = listOf(
            note("a", "Alpha", listOf("Beta")),
            note("b", "Beta", emptyList()),
        )
        assertEquals(listOf(KnowledgeNoteGraph.GraphEdge("a", "b")), KnowledgeNoteGraph.edgesOf(notes))
    }

    @Test
    fun `edge to a missing title is dropped`() {
        val notes = listOf(note("a", "Alpha", listOf("Ghost")))
        assertEquals(emptyList<KnowledgeNoteGraph.GraphEdge>(), KnowledgeNoteGraph.edgesOf(notes))
    }

    @Test
    fun `bidirectional edges resolve both ways`() {
        val notes = listOf(
            note("a", "Alpha", listOf("Beta")),
            note("b", "Beta", listOf("Alpha")),
        )
        val edges = KnowledgeNoteGraph.edgesOf(notes)
        assertEquals(2, edges.size)
        assertTrue(edges.contains(KnowledgeNoteGraph.GraphEdge("a", "b")))
        assertTrue(edges.contains(KnowledgeNoteGraph.GraphEdge("b", "a")))
    }

    @Test
    fun `self link produces a self edge`() {
        val notes = listOf(note("a", "Alpha", listOf("Alpha")))
        assertEquals(
            listOf(KnowledgeNoteGraph.GraphEdge("a", "a")),
            KnowledgeNoteGraph.edgesOf(notes),
        )
    }

    // ---- forceLayout ----------------------------------------------------

    private fun distance(a: KnowledgeNoteGraph.LayoutNode, b: KnowledgeNoteGraph.LayoutNode): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    @Test
    fun `connected pair converges closer than initial circle placement`() {
        val nodes = listOf(
            KnowledgeNoteGraph.LayoutNode("a", 0f, 0f),
            KnowledgeNoteGraph.LayoutNode("b", 0f, 0f),
        )
        val edges = listOf(KnowledgeNoteGraph.GraphEdge("a", "b"))
        val laid = KnowledgeNoteGraph.forceLayout(nodes, edges, iterations = 120, width = 800f, height = 800f)
        // Circle seed places an edge-free pair on opposite sides: diameter ≈
        // 0.9 * 800 = 720. A spring edge must beat that noticeably.
        assertTrue(distance(laid[0], laid[1]) < 720f)
    }

    @Test
    fun `unconnected pair is pushed apart`() {
        val nodes = listOf(
            KnowledgeNoteGraph.LayoutNode("a", 0f, 0f),
            KnowledgeNoteGraph.LayoutNode("b", 0f, 0f),
        )
        val laid = KnowledgeNoteGraph.forceLayout(nodes, emptyList(), iterations = 120, width = 800f, height = 800f)
        assertTrue(distance(laid[0], laid[1]) > 400f)
    }

    @Test
    fun `layout stays inside bounds`() {
        val nodes = (1..6).map {
            KnowledgeNoteGraph.LayoutNode("n$it", 0f, 0f)
        }
        val edges = listOf(
            KnowledgeNoteGraph.GraphEdge("n1", "n2"),
            KnowledgeNoteGraph.GraphEdge("n2", "n3"),
        )
        val laid = KnowledgeNoteGraph.forceLayout(nodes, edges, iterations = 80, width = 600f, height = 400f)
        laid.forEach { n ->
            assertTrue("x=${n.x} out of [0,600]", n.x in 0f..600f)
            assertTrue("y=${n.y} out of [0,400]", n.y in 0f..400f)
        }
    }

    @Test
    fun `layout is deterministic`() {
        val nodes = (1..4).map { KnowledgeNoteGraph.LayoutNode("n$it", 0f, 0f) }
        val edges = listOf(KnowledgeNoteGraph.GraphEdge("n1", "n2"))
        val a = KnowledgeNoteGraph.forceLayout(nodes, edges, iterations = 60, width = 500f, height = 500f)
        val b = KnowledgeNoteGraph.forceLayout(nodes, edges, iterations = 60, width = 500f, height = 500f)
        a.zip(b).forEach { (x, y) ->
            assertEquals(x.id, y.id)
            assertEquals(x.x, y.x, 0.0001f)
            assertEquals(x.y, y.y, 0.0001f)
        }
    }

    // ---- buildSearchText ------------------------------------------------

    @Test
    fun `short text keeps title and body`() {
        assertEquals("Home\nbody", KnowledgeNoteGraph.buildSearchText("Home", "body"))
    }

    @Test
    fun `long text folds to cap`() {
        val body = "x".repeat(5000)
        val folded = KnowledgeNoteGraph.buildSearchText("T", body, cap = 2000)
        assertEquals(2000, folded.length)
        assertTrue(folded.startsWith("T\n"))
    }

    @Test
    fun `empty body keeps title only`() {
        assertEquals("T", KnowledgeNoteGraph.buildSearchText("T", ""))
    }

    @Test
    fun `default cap is two thousand`() {
        assertEquals(2000, KnowledgeNoteGraph.DEFAULT_SEARCH_CAP)
    }

    // ---- dedupeHash -----------------------------------------------------

    @Test
    fun `hash is lowercase hex of length twelve`() {
        val h = KnowledgeNoteGraph.dedupeHash("Home", "body")
        assertEquals(12, h.length)
        assertTrue(h.matches(Regex("[0-9a-f]{12}")))
    }

    @Test
    fun `hash matches known sha256 prefix`() {
        assertEquals("2ab47a3db040", KnowledgeNoteGraph.dedupeHash("Home", "body"))
        assertEquals("604666bfe0c4", KnowledgeNoteGraph.dedupeHash("Home", "body2"))
        assertEquals("c0cde77fa8fe", KnowledgeNoteGraph.dedupeHash("B", ""))
    }

    @Test
    fun `hash differs across content`() {
        assertTrue(
            KnowledgeNoteGraph.dedupeHash("A", "1") != KnowledgeNoteGraph.dedupeHash("B", "1"),
        )
    }
}
