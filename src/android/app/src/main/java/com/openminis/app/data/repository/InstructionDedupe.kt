package com.openminis.app.data.repository

/**
 * [T-memory-instruction-dedupe] Renders identical instruction content once,
 * across scopes.
 *
 * Mirrors DSH's `agent-instructions` rule ("identical trimmed content renders
 * once") and codex's habit of collapsing duplicate `AGENTS.md` layers. OpenMinis
 * has three instruction sources a user maintains by hand, and copying the same
 * rules into `GLOBAL.md` and `AGENTS.md` used to send them to the model twice —
 * wasted tokens at best, two competing "authorities" at worst.
 *
 * Comparison is on the *body* (the rules text itself), not the rendered fragment:
 * each layer prepends its own header and envelope, so fragment-level equality
 * would never match.
 *
 * Callers pass candidates **narrowest scope first**, so a duplicate is kept as
 * the more specific layer — the same precedence the prompt already states.
 *
 * Pure and total.
 */
object InstructionDedupe {

    /**
     * Returns the keys whose bodies are unique, in the order given.
     *
     * Whitespace is normalised before comparison so a re-indented copy still
     * counts as a duplicate; null and blank bodies contribute nothing.
     */
    fun firstSeen(candidates: List<Pair<String, String?>>): Set<String> {
        val seen = HashSet<String>()
        val kept = LinkedHashSet<String>()
        for ((key, body) in candidates) {
            val normalized = normalize(body) ?: continue
            if (seen.add(normalized)) kept += key
        }
        return kept
    }

    /** Collapses runs of whitespace and trims; null when nothing is left. */
    fun normalize(body: String?): String? {
        val collapsed = body?.trim()?.replace(WHITESPACE, " ")
        return collapsed?.takeIf { it.isNotEmpty() }
    }

    private val WHITESPACE = Regex("\\s+")
}
