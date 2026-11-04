package com.openminis.app.tools

/**
 * Delivery-evidence gate for the Supervisor-Worker loop
 * (spec: docs/superpowers/specs/2026-10-24-supervisor-worker-loop-design.md §3.9).
 *
 * A DONE verdict that cites acceptance words proves the supervisor read the
 * acceptance criteria — it does not prove the deliverable exists. The
 * evidence gate closes that gap: the review message that carries the DONE
 * line must also carry a `## Delivery` section listing three classes of
 * evidence, and every item is checked before the session may land in DONE:
 *
 *  - `files:` — comma-separated workspace paths, each checked for existence
 *    on disk (through an injected lambda, so this stays pure JVM);
 *  - `tests:` — a note that must match the worker reports the supervisor
 *    actually saw (the report corpus);
 *  - `log:` — a note that must match the same corpus.
 *
 * A review message whose DONE passes the rubber-stamp check but fails this
 * gate earns one evidence strike and is bounced; the second failure ends
 * the session (same two-strike shape as the rubber-stamp rule).
 *
 * This file owns only parsing and checking. It never touches Android types
 * and never does I/O: the caller answers "does this file exist" and hands
 * over the report corpus, which keeps every rule unit-testable on the JVM.
 */
object SupervisorEvidence {

    /** Line marker of the delivery section inside a supervisor review message. */
    const val DELIVERY_MARKER = "## Delivery"

    /** Token length floor: shorter tokens are stopwords and cannot prove a match. */
    const val MIN_TOKEN_LEN = 4

    /**
     * A parsed delivery claim: the three evidence classes, each a list of
     * items exactly as written (normalized, blank items dropped).
     */
    data class DeliveryClaim(
        val files: List<String>,
        val testNotes: List<String>,
        val logNotes: List<String>,
    ) {
        val isEmpty: Boolean get() = files.isEmpty() && testNotes.isEmpty() && logNotes.isEmpty()
    }

    /** Result of checking one claim. */
    sealed class Check {
        /** Every item passed: the delivery is verifiable. */
        data object Pass : Check()

        /** One or more items failed; [reasons] names each failure. */
        data class Fail(val reasons: List<String>) : Check()
    }

    /**
     * Extracts the delivery claim from a review message.
     *
     * The `## Delivery` marker must sit on its own line; the section runs to
     * the next `## ` marker or end of message. Inside it, only lines starting
     * with the three class prefixes are consumed, so prose cannot smuggle in
     * evidence. Returns null when the section (or all three classes) is absent
     * — a DONE without a delivery section is a failed gate, not a pass.
     */
    fun parseDelivery(text: String): DeliveryClaim? {
        val lines = text.lines()
        val start = lines.indexOfFirst { line -> line.trim() == DELIVERY_MARKER }
        if (start < 0) return null
        val end = lines.drop(start + 1).indexOfFirst { line -> line.trim().startsWith("## ") }
        val body = if (end < 0) lines.drop(start + 1) else lines.subList(start + 1, start + 1 + end)

        val files = mutableListOf<String>()
        val tests = mutableListOf<String>()
        val logs = mutableListOf<String>()
        for (line in body) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("files:", ignoreCase = true) ->
                    splitItems(trimmed.substringAfter(':')).forEach { files.add(normalizePath(it)) }
                trimmed.startsWith("tests:", ignoreCase = true) ->
                    splitItems(trimmed.substringAfter(':')).forEach { tests.add(it) }
                trimmed.startsWith("log:", ignoreCase = true) ->
                    splitItems(trimmed.substringAfter(':')).forEach { logs.add(it) }
            }
        }
        val claim = DeliveryClaim(files, tests, logs)
        return if (claim.isEmpty) null else claim
    }

    /**
     * Checks a claim against disk and the report corpus.
     *
     * [fileExists] answers existence for a normalized path — the caller
     * resolves it against the workspace root. [corpus] is the concatenation
     * of every worker report the supervisor saw before emitting the DONE;
     * matching is case-insensitive substring. Test and log notes match when
     * every token of [MIN_TOKEN_LEN] chars or more appears in the corpus, or
     * (for notes with no long token) the whole note appears.
     */
    fun verify(
        claim: DeliveryClaim,
        fileExists: (String) -> Boolean,
        corpus: String,
    ): Check {
        val reasons = mutableListOf<String>()
        if (claim.files.isEmpty()) {
            reasons.add("no files listed")
        } else {
            claim.files.forEach { path ->
                if (!fileExists(path)) reasons.add("file not found: $path")
            }
        }
        if (claim.testNotes.isEmpty()) {
            reasons.add("no test evidence listed")
        } else {
            claim.testNotes.forEachIndexed { i, note -> if (!noteMatches(note, corpus)) reasons.add("test note $i not in reports: $note") }
        }
        if (claim.logNotes.isEmpty()) {
            reasons.add("no log evidence listed")
        } else {
            claim.logNotes.forEachIndexed { i, note -> if (!noteMatches(note, corpus)) reasons.add("log note $i not in reports: $note") }
        }
        return if (reasons.isEmpty()) Check.Pass else Check.Fail(reasons)
    }

    /** Render a bounce note naming each failed item, for the review turn. */
    fun bounceNote(fail: Check.Fail): String {
        return "Evidence check failed for the ## Delivery section of your DONE:\n" +
            fail.reasons.joinToString("\n") { "- $it" } +
            "\nRe-check and resend DONE (fix the ## Delivery section, quoting exact words from the worker report and files that actually exist)."
    }

    private fun noteMatches(note: String, corpus: String): Boolean {
        val lowerCorpus = corpus.lowercase()
        val tokens = note.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= MIN_TOKEN_LEN }
        if (tokens.isEmpty()) {
            val trimmed = note.trim().lowercase()
            return trimmed.isNotEmpty() && lowerCorpus.contains(trimmed)
        }
        return tokens.all { lowerCorpus.contains(it) }
    }

    private fun splitItems(raw: String): List<String> = raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /** Normalizes a claimed path: separators, `./` prefix, double slashes. */
    fun normalizePath(raw: String): String {
        var path = raw.trim().replace('\\', '/')
        while (path.startsWith("./")) path = path.removePrefix("./")
        return path.replace(Regex("/{2,}"), "/")
    }
}
