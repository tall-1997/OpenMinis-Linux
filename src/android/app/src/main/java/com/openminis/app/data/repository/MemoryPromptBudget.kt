package com.openminis.app.data.repository

/**
 * [T-memory-prompt-budget] Decides which memory fragments fit in the prompt and
 * in what shape.
 *
 * Before this existed the memory block was plain concatenation: GLOBAL, project
 * rules, the session rules and up to three daily logs all went in, each with only
 * its own private cap (200 lines per daily log). A user with a long GLOBAL.md and
 * three busy days could crowd out the session rules — the fragment that actually
 * governs this chat.
 *
 * The budget makes the trade-off explicit and testable:
 *
 *  - fragments are admitted highest-priority first, so the rules that scope the
 *    narrowest context (session) are never displaced by the widest (daily logs);
 *  - a fragment that does not fit may be truncated to its head when that is
 *    better than dropping it (long daily logs are still useful at 2 KB);
 *  - output keeps the callers' original order, because prompt-prefix caching
 *    depends on byte-stable assembly.
 *
 * Pure and total: no I/O, no Android types.
 */
object MemoryPromptBudget {

    /** Characters of memory fragments allowed in one system prompt (~6k tokens). */
    const val DEFAULT_BUDGET_CHARS = 24_000

    /** A truncated fragment shorter than this is not worth including. */
    internal const val MIN_TRUNCATED_CHARS = 512

    internal const val TRUNCATION_MARKER = "\n… [memory budget: truncated]"

    // Narrowest scope first: a rule that governs this chat outranks a rule that
    // governs every chat in the project, which outranks ambient history.
    internal const val PRIORITY_PROJECT = 10
    internal const val PRIORITY_SESSION = 20
    internal const val PRIORITY_APP = 30
    internal const val PRIORITY_LEARNED = 40
    internal const val PRIORITY_DAILY = 50

    data class Fragment(
        /** Stable identity used in logs and tests ("app-global", "session-global", …). */
        val key: String,
        val text: String,
        /** Lower wins. */
        val priority: Int,
        /** Whether a too-large fragment may be truncated rather than dropped. */
        val truncatable: Boolean,
    )

    data class Allocation(
        /** Admitted fragments in the callers' original order. */
        val included: List<Fragment>,
        val droppedKeys: List<String>,
        val truncatedKeys: List<String>,
        val usedChars: Int,
    ) {
        /** The memory block as it goes into the prompt, or null when nothing was admitted. */
        val rendered: String?
            get() = included.takeIf { it.isNotEmpty() }
                ?.joinToString("\n\n") { it.text }
                ?.takeIf { it.isNotBlank() }

        fun describe(): String =
            "used=${usedChars}kept=${included.joinToString(",") { it.key }}" +
                (if (truncatedKeys.isEmpty()) "" else " truncated=${truncatedKeys.joinToString(",")}") +
                (if (droppedKeys.isEmpty()) "" else " dropped=${droppedKeys.joinToString(",")}")
    }

    fun allocate(
        fragments: List<Fragment>,
        budgetChars: Int = DEFAULT_BUDGET_CHARS,
    ): Allocation {
        val budget = budgetChars.coerceAtLeast(0)
        val admitted = HashSet<String>()
        val truncated = ArrayList<String>()
        val dropped = ArrayList<String>()
        var used = 0

        // Stable sort: equal priorities keep the caller's order.
        val byPriority = fragments.sortedBy { it.priority }
        val replacement = HashMap<String, Fragment>()

        for (fragment in byPriority) {
            if (fragment.text.isBlank()) continue
            val remaining = budget - used
            when {
                fragment.text.length <= remaining -> {
                    admitted += fragment.key
                    used += fragment.text.length
                }
                fragment.truncatable -> {
                    // Reserve room for the marker so the accounting stays honest.
                    val head = remaining - TRUNCATION_MARKER.length
                    if (head >= MIN_TRUNCATED_CHARS) {
                        val cut = fragment.text.take(head) + TRUNCATION_MARKER
                        replacement[fragment.key] = fragment.copy(text = cut)
                        admitted += fragment.key
                        truncated += fragment.key
                        used += cut.length
                    } else {
                        dropped += fragment.key
                    }
                }
                else -> dropped += fragment.key
            }
        }

        // Project the decisions back onto the callers' order so the assembled
        // prompt stays byte-stable when nothing changed.
        val included = fragments
            .filter { it.key in admitted }
            .map { replacement[it.key] ?: it }
        return Allocation(
            included = included,
            droppedKeys = fragments.filter { it.key in dropped }.map { it.key },
            truncatedKeys = truncated,
            usedChars = used,
        )
    }
}
