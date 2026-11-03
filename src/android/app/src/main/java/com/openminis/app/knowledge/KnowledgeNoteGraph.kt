package com.openminis.app.knowledge

import java.security.MessageDigest

/**
 * [T-m8-knowledge-workbench] Pure, deterministic helpers for the knowledge
 * workbench. No Android imports, no shared state: the graph page, the
 * repository and the AI-answer citation check all consume these directly.
 */
object KnowledgeNoteGraph {

    /** Soft cap for the folded search text column. */
    const val DEFAULT_SEARCH_CAP = 2000

    data class NoteLite(val id: String, val title: String, val links: List<String>)

    data class GraphEdge(val from: String, val to: String)

    data class LayoutNode(val id: String, val x: Float, val y: Float)

    /** `[[Title]]` wiki links. Titles cannot contain `[` or `]`. */
    private val WIKI_LINK = Regex("""\[\[([^\[\]]+)\]\]""")

    /**
     * All wiki-link targets in a note body, in first-occurrence order,
     * deduplicated. Whitespace around the title is trimmed and empty targets
     * (`[[]]`) are ignored. Half-open brackets (`[[x`) never match because the
     * regex requires both delimiters.
     */
    fun parseWikiLinks(bodyMd: String): List<String> {
        val seen = LinkedHashSet<String>()
        WIKI_LINK.findAll(bodyMd).forEach { m ->
            val title = m.groupValues[1].trim()
            if (title.isNotEmpty()) seen.add(title)
        }
        return seen.toList()
    }

    /**
     * Graph edges from [[links]] to note ids. Only edges whose target title
     * matches an existing note are kept (no ghost nodes in the graph page);
     * self-links survive as self edges and are left for the renderer to skip.
     */
    fun edgesOf(notes: List<NoteLite>): List<GraphEdge> {
        val byTitle = notes.associateBy { it.title }
        val result = ArrayList<GraphEdge>()
        notes.forEach { n ->
            n.links.forEach { target ->
                byTitle[target]?.let { result.add(GraphEdge(n.id, it.id)) }
            }
        }
        return result
    }

    /** Spring attraction scale: with repulsion k^2/d, balance sits at d = k / cbrt(ATTRACT). */
    private const val ATTRACT = 1.5f

    /**
     * Deterministic force-directed layout: circular seed (radius 0.45 of the
     * short side, centred), then fixed iteration count with Coulomb repulsion
     * k^2/d, Fruchterman-Reingold style attraction d^2/k * [ATTRACT] (edge
     * pairs balance near 0.87k), weak centre gravity and hard bounds
     * clamping. No randomness anywhere — same input, same output.
     */
    fun forceLayout(
        nodes: List<LayoutNode>,
        edges: List<GraphEdge>,
        iterations: Int,
        width: Float,
        height: Float,
    ): List<LayoutNode> {
        if (nodes.isEmpty()) return emptyList()
        val cx = width / 2f
        val cy = height / 2f
        val seedRadius = 0.45f * minOf(width, height)

        val pos = ArrayList<FloatArray>(nodes.size)
        nodes.forEachIndexed { i, _ ->
            if (nodes.size == 1) {
                pos.add(floatArrayOf(cx, cy))
            } else {
                val angle = (2.0 * Math.PI * i) / nodes.size
                pos.add(
                    floatArrayOf(
                        cx + seedRadius * kotlin.math.cos(angle).toFloat(),
                        cy + seedRadius * kotlin.math.sin(angle).toFloat(),
                    ),
                )
            }
        }

        val indexById = nodes.mapIndexed { i, n -> n.id to i }.toMap()
        val adjacency = edges.mapNotNull { e ->
            val a = indexById[e.from]
            val b = indexById[e.to]
            if (a != null && b != null) a to b else null
        }

        // Ideal spacing for n nodes over the canvas.
        val k = kotlin.math.sqrt((width * height) / maxOf(nodes.size, 1).toFloat())
        val k2 = k * k
        val maxStep = 0.1f * minOf(width, height)

        val displacement = Array(nodes.size) { FloatArray(2) }

        repeat(maxOf(iterations, 0)) { step ->
            for (i in displacement.indices) {
                displacement[i][0] = 0f
                displacement[i][1] = 0f
            }

            // Repulsion: F = k^2 / d along the separating axis.
            for (i in pos.indices) {
                for (j in i + 1 until pos.size) {
                    val dx = pos[i][0] - pos[j][0]
                    val dy = pos[i][1] - pos[j][1]
                    val dist2 = dx * dx + dy * dy
                    val dist = if (dist2 < 1f) 1f else kotlin.math.sqrt(dist2)
                    val force = (k2 / dist) / dist // component scale = k^2 / d^2
                    val fx = dx * force
                    val fy = dy * force
                    displacement[i][0] += fx
                    displacement[i][1] += fy
                    displacement[j][0] -= fx
                    displacement[j][1] -= fy
                }
            }

            // Springs: FR-style attraction, edge pairs settle near 0.87k.
            adjacency.forEach { (a, b) ->
                val dx = pos[a][0] - pos[b][0]
                val dy = pos[a][1] - pos[b][1]
                val dist = maxOf(kotlin.math.sqrt(dx * dx + dy * dy), 1f)
                val force = dist * dist / k * ATTRACT
                val fx = (dx / dist) * force
                val fy = (dy / dist) * force
                displacement[a][0] -= fx
                displacement[a][1] -= fy
                displacement[b][0] += fx
                displacement[b][1] += fy
            }

            // Weak centre gravity keeps components on-canvas.
            for (i in pos.indices) {
                displacement[i][0] += (cx - pos[i][0]) * 0.01f
                displacement[i][1] += (cy - pos[i][1]) * 0.01f
            }

            // Cooling: steps shrink linearly to zero over the budget.
            val stepScale = 1f - step.toFloat() / maxOf(iterations, 1)
            val stepLen = maxStep * (0.05f + 0.95f * stepScale)

            for (i in pos.indices) {
                val dx = displacement[i][0]
                val dy = displacement[i][1]
                val len = maxOf(kotlin.math.sqrt(dx * dx + dy * dy), 1f)
                val capped = minOf(len, stepLen)
                pos[i][0] += (dx / len) * capped
                pos[i][1] += (dy / len) * capped
                pos[i][0] = pos[i][0].coerceIn(0f, width)
                pos[i][1] = pos[i][1].coerceIn(0f, height)
            }
        }

        return nodes.mapIndexed { i, n -> LayoutNode(n.id, pos[i][0], pos[i][1]) }
    }

    /** Folded LIKE-searchable text: title first, body below, capped hard. */
    fun buildSearchText(title: String, bodyMd: String, cap: Int = DEFAULT_SEARCH_CAP): String {
        val raw = if (bodyMd.isEmpty()) title else "$title\n$bodyMd"
        return if (raw.length <= cap) raw else raw.take(cap)
    }

    /**
     * Import dedupe key: lowercase hex of the first 6 bytes of
     * SHA-256("title\nbody"). Same title+body imports collide; anything else
     * does not.
     */
    fun dedupeHash(title: String, bodyMd: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest("$title\n$bodyMd".toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
