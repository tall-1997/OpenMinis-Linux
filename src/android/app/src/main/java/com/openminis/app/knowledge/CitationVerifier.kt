package com.openminis.app.knowledge

/**
 * [T-m8-knowledge-workbench] Citation verification for AI answers over
 * knowledge segments. The prompt builder numbers retrieved segments [1]..[k];
 * a reply citing numbers outside that range means the model invented a
 * source, so the UI re-asks once with [correctionPrompt] appended instead of
 * showing a fake reference.
 */
object CitationVerifier {

    sealed class Check {
        /** All citations are within range (empty range never yields Ok). */
        data class Ok(val citations: List<Int>) : Check()

        /** At least one number is invalid; [bad] lists the offenders. */
        data class OutOfRange(val bad: List<Int>, val citations: List<Int>) : Check()

        /** No [n] citation syntax at all. */
        object None : Check()
    }

    /**
     * `[n]` or `[n,m,...]` citation syntax. Lookbehind rejects `[[n]]` wiki
     * style and glued `12[3]`; markdown links `[x](url)` never match because
     * only digits/commas are accepted inside the brackets.
     */
    private val CITATION = Regex("""(?<![\d,\[])\[(\d+(?:,\d+)*)]""")

    fun verify(replyText: String, segmentCount: Int): Check {
        val citations = LinkedHashSet<Int>()
        CITATION.findAll(replyText).forEach { m ->
            m.groupValues[1].split(',').forEach { part ->
                citations.add(part.toInt())
            }
        }
        if (citations.isEmpty()) return Check.None
        val bad = citations.filter { it < 1 || it > segmentCount }
        val ordered = citations.toList()
        return if (bad.isEmpty()) {
            Check.Ok(ordered)
        } else {
            Check.OutOfRange(bad = bad, citations = ordered)
        }
    }

    /**
     * Instruction appended to the user question for the one-shot re-ask:
     * names every invalid number and the valid range. Language-agnostic
     * punctuation-free core so any locale can render it.
     */
    fun correctionPrompt(bad: List<Int>, segmentCount: Int): String {
        val numbers = bad.joinToString(",")
        return "Some citations in your previous answer do not match any source: " +
            "[$numbers]. Only sources 1..$segmentCount exist. " +
            "Rewrite the answer citing only existing sources."
    }
}
