package com.openminis.app.tools

import android.content.Context
import com.openminis.app.sandbox.SessionWorkspace
import java.io.File
import java.security.MessageDigest

/**
 * [T-known-failure] Remembers tool calls that already failed, so the model cannot
 * silently repeat them.
 *
 * This is the structural half of "the model keeps stepping on the same rake": the
 * first failure is recorded here, and the second identical call is refused by
 * code instead of being retried on the strength of the model's memory. The
 * existing [com.openminis.app.guard.ActionGuard] only catches *consecutive
 * identical* calls; this catches "same call, later in the session, after a
 * detour", which is what long sessions actually look like.
 *
 * Deliberately narrow:
 *  - the fingerprint covers the canonical tool name **and the exact arguments**,
 *    so any change (different path, different flag) is a different call;
 *  - only failures count, and only [MIN_FAILURES] of them;
 *  - the record expires after [WINDOW_MS], so a fixed environment is not
 *    permanently blocked;
 *  - a success clears the record.
 *
 * Storage is a small JSON file inside the session workspace, capped at
 * [MAX_ENTRIES]. Failure-tolerant by design: if the file cannot be read or
 * written, the gate simply does not fire — it must never break tool dispatch.
 */
object ToolFailureMemory {

    /** Consecutive-identical failures needed before the gate refuses. */
    const val MIN_FAILURES = 2

    /** How long a recorded failure keeps the gate armed. */
    const val WINDOW_MS = 30 * 60_000L

    const val FILE_NAME = ".tool-failures.json"

    internal const val MAX_ENTRIES = 50

    /** [T-known-failure-near] Jaccard threshold for "same call shape". */
    internal const val NEAR_THRESHOLD = 0.75

    /** Cap for the persisted canonical token source. */
    private const val CANONICAL_CHARS = 400

    /** Keys whose values are prose/payload, not a call identity. */
    private val NON_IDENTITY_KEYS = setOf(
        "tool_title", "tool_input", "content", "text", "body", "prompt",
        "value", "data", "old_string", "new_string",
    )

    /**
     * [T-known-failure-payload] The subset of [NON_IDENTITY_KEYS] that is the
     * OPERATION itself rather than a label: an edit's `old_string`, a write's
     * `content`, an MCP call's `arguments`.
     *
     * Near-clustering compares calls that "differ only in a flag/path", which
     * is why these keys are dropped from the canonical identity. But dropping
     * them from the CLUSTER decision too made a corrected edit indistinguishable
     * from the broken one it replaced: `file_edit` twice with a bad
     * `old_string` clustered, and the third call — the one carrying the fix —
     * was refused as "the small difference did not change the failure cause".
     * Payload must therefore be compared separately: same payload may cluster,
     * a changed payload starts a new attempt.
     */
    private val PAYLOAD_KEYS = NON_IDENTITY_KEYS - setOf("tool_title", "tool_input")

    private val WHITESPACE = Regex("\\s+")

    data class Entry(
        val tool: String,
        val failures: Int,
        val lastError: String,
        val lastAtMs: Long,
        /** [T-known-failure-readable] Bounded argument identity for the refusal. */
        val argsExcerpt: String = "",
        /** [T-known-failure-near] Token-normalized arguments for clustering. */
        val argsCanonical: String = "",
        /** [T-known-failure-near] Path/number-normalized error, for same-cause. */
        val errorClass: String = "",
        /** [T-known-failure-payload] Hash of the operation payload; "" = pre-upgrade record. */
        val payloadSha: String = "",
        /** Transient: matched a near (not exact) call. Never persisted. */
        val nearMatch: Boolean = false,
    )

    /** Stable identity of one call: tool name plus its exact arguments. */
    fun fingerprint(tool: String, argsJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$tool\u0000${argsJson.trim()}".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Where this session keeps its failure ledger. */
    fun ledgerFile(context: Context, sessionId: String): File =
        File(
            SessionWorkspace.base(context.filesDir, SessionWorkspace.ownerSessionId(sessionId)),
            FILE_NAME,
        )

    fun lookup(file: File, tool: String, argsJson: String, nowMs: Long): Entry? {
        val all = readAll(file)
        val exact = all[fingerprint(tool, argsJson)]
        if (exact != null && armed(exact, nowMs)) return exact
        // [T-known-failure-near] The exact key misses when the model changes a
        // flag/path slightly. A same-tool, same-error-class cluster with high
        // argument similarity is the same rake; block it and say WHY.
        val canonical = canonicalArgs(tool, argsJson)
        val payload = payloadSignature(argsJson)
        val near = all.values.firstOrNull { e ->
            e.tool == tool && armed(e, nowMs) && samePayload(e.payloadSha, payload) &&
                e.errorClass.isNotBlank() && e.errorClass == errorClass(e.lastError) &&
                similarity(e.argsCanonical.ifBlank { e.argsExcerpt }, canonical) >= NEAR_THRESHOLD
        } ?: return null
        return near.copy(nearMatch = true)
    }

    private fun armed(entry: Entry, nowMs: Long): Boolean =
        entry.failures >= MIN_FAILURES && nowMs - entry.lastAtMs <= WINDOW_MS

    /** [T-known-failure-near] Records one failure; returns the entry as stored. */
    fun recordFailure(file: File, tool: String, argsJson: String, error: String, nowMs: Long): Entry {
        val key = fingerprint(tool, argsJson)
        val all = readAll(file).toMutableMap()
        val canonical = canonicalArgs(tool, argsJson)
        val payload = payloadSignature(argsJson)
        val errorKey = errorClass(error)
        val exact = all[key]
        // Cluster into a near entry when the exact key is new but the same
        // call shape already failed with the same error class.
        val nearKey = if (exact == null) {
            all.entries.firstOrNull { (_, e) ->
                e.tool == tool && e.errorClass.isNotBlank() &&
                    samePayload(e.payloadSha, payload) &&
                    e.errorClass == errorKey &&
                    similarity(e.argsCanonical.ifBlank { e.argsExcerpt }, canonical) >= NEAR_THRESHOLD
            }?.key
        } else {
            null
        }
        val storeKey = if (exact != null) key else (nearKey ?: key)
        val previous = all[storeKey]
        val entry = Entry(
            tool = tool,
            failures = (previous?.failures ?: 0) + 1,
            lastError = com.openminis.app.util.TextClip.safeTake(
                com.openminis.app.util.SecretMasking.mask(error),
                400,
            ),
            lastAtMs = nowMs,
            argsExcerpt = argsExcerpt(argsJson),
            argsCanonical = canonical,
            errorClass = errorKey,
            payloadSha = payload,
        )
        all[storeKey] = entry
        writeAll(file, all)
        return entry
    }

    /** A success means the call works now — forget the failures. */
    fun clear(file: File, tool: String, argsJson: String) {
        val key = fingerprint(tool, argsJson)
        val all = readAll(file)
        if (!all.containsKey(key)) {
            // [T-known-failure-near] A near variant succeeding proves the
            // clustered failure is stale; drop the similar cluster too.
            val canonical = canonicalArgs(tool, argsJson)
            val stale = all.filterValues { e ->
                e.tool == tool &&
                    similarity(e.argsCanonical.ifBlank { e.argsExcerpt }, canonical) >= NEAR_THRESHOLD
            }.keys
            if (stale.isNotEmpty()) writeAll(file, all - stale)
            return
        }
        val canonical = canonicalArgs(tool, argsJson)
        val stale = all.filterValues { e ->
            e.tool == tool &&
                similarity(e.argsCanonical.ifBlank { e.argsExcerpt }, canonical) >= NEAR_THRESHOLD
        }.keys
        writeAll(file, all - stale - key)
    }

    /** The refusal the model receives instead of a second identical attempt. */
    fun refusalText(entry: Entry, currentArgsExcerpt: String? = null): String = buildString {
        append("[known-failure] ")
        append(entry.tool)
        if (entry.nearMatch) {
            append(" with arguments very close to a call that already failed ")
            append(entry.failures)
            append(" time(s) in this session; not running it again.\n")
            if (entry.argsExcerpt.isNotBlank()) {
                append("Previous call: ").append(entry.argsExcerpt).append('\n')
            }
            if (!currentArgsExcerpt.isNullOrBlank()) {
                append("This call: ").append(currentArgsExcerpt).append('\n')
            }
            append("The small difference did not change the failure cause. Change the approach, ")
            append("not just a flag/path.\nLast error: ")
            append(entry.lastError)
            return@buildString
        }
        if (entry.argsExcerpt.isNotBlank()) {
            append("(").append(entry.argsExcerpt).append(")")
        }
        append(" with these exact arguments already failed ")
        append(entry.failures)
        append(" time(s) in this session; not running it again.\nLast error: ")
        append(entry.lastError)
        append("\nChange the arguments or the approach. If the environment has changed, a call ")
        append("with different arguments will run normally.")
    }

    /**
     * [T-known-failure-readable] A short identity for the refused call. The
     * refusal used to say "these exact arguments" without naming them, so the
     * model knew it was blocked but not WHAT it had passed — it could neither
     * recognize the command nor change the right field. Keeps the first three
     * scalar arguments (path/command/query/...) and never returns multi-line.
     */
    internal fun argsExcerpt(argsJson: String, limit: Int = 140): String {
        val raw = argsJson.trim()
        if (raw.isEmpty()) return ""
        val parsed = runCatching { org.json.JSONObject(raw) }.getOrNull()
        val text = if (parsed != null) {
            val parts = ArrayList<String>(3)
            val keys = parsed.keys()
            while (keys.hasNext() && parts.size < 3) {
                val key = keys.next()
                if (key in NON_IDENTITY_KEYS) continue
                val value = parsed.opt(key)
                val scalar = when (value) {
                    is String -> value
                    is Number, is Boolean -> value.toString()
                    else -> continue
                }.trim()
                if (scalar.isEmpty()) continue
                parts += if (key == "path" || key == "command" || key == "pattern" ||
                    key == "query" || key == "url"
                ) {
                    scalar
                } else {
                    "$key=" + com.openminis.app.util.TextClip.safeTake(scalar, 40)
                }
            }
            parts.joinToString(", ")
        } else {
            raw
        }
        val flat = com.openminis.app.util.SecretMasking.mask(text)
            .replace(WHITESPACE, " ")
            .trim()
        return com.openminis.app.util.TextClip.safeEllipsize(flat, limit)
    }

    // ── near-match clustering ─────────────────────────────────────────────

    /**
     * [T-known-failure-near] Token-normalized identity of the arguments. Path
     * prefixes, punctuation, numbers and case are normalized, so
     * `/src/Main.kt` and `./src/Main.kt` share a cluster while `A.kt` and
     * `B.kt` do not. Secrets are masked FIRST so the persisted token set
     * cannot carry a credential.
     */
    internal fun canonicalArgs(tool: String, argsJson: String): String {
        val raw = argsJson.trim()
        if (raw.isEmpty()) return ""
        val masked = com.openminis.app.util.SecretMasking.mask(raw)
        val parsed = runCatching { org.json.JSONObject(masked) }.getOrNull()
        val source = if (parsed != null) {
            val parts = ArrayList<String>()
            val keys = parsed.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key in NON_IDENTITY_KEYS) continue
                val value = parsed.opt(key)
                val scalar = when (value) {
                    is String -> value
                    is Number, is Boolean -> value.toString()
                    else -> continue
                }.trim()
                if (scalar.isEmpty()) continue
                parts += "$key=$scalar"
            }
            parts.joinToString(" ")
        } else {
            masked
        }
        return tokens(
            "tool=$tool " + com.openminis.app.util.TextClip.safeTake(source, CANONICAL_CHARS),
        ).joinToString(" ")
    }

    /**
     * [T-known-failure-payload] Identity of the OPERATION payload only — the
     * keys [canonicalArgs] deliberately drops, plus every non-scalar value
     * (`multi_edit.edits`, `mcp.arguments`), which the scalar filter dropped
     * too. Two calls may only join the same near cluster when this matches, so
     * changing the thing being edited always reads as a new attempt.
     *
     * "" means "no payload to compare" (a flag-only call, or a pre-upgrade
     * record that never stored one); equal blanks still cluster exactly as
     * before, and a blank-vs-hash pair simply does not match.
     */
    internal fun payloadSignature(argsJson: String): String {
        val raw = argsJson.trim()
        if (raw.isEmpty()) return ""
        // [T-known-failure-payload] Hash the RAW payload, not the masked one:
        // only a digest is persisted (never the text), and masking would make
        // two different passwords — i.e. two different repair attempts — hash
        // identically.
        val parsed = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return ""
        val source = StringBuilder()
        for (key in parsed.keys().asSequence().sorted().toList()) {
            val value = parsed.opt(key)
            val scalar = value is String || value is Number || value is Boolean
            val payload = key in PAYLOAD_KEYS || !scalar
            if (!payload || key == "tool_title") continue
            source.append(key).append('=').append(value?.toString().orEmpty()).append('\u0000')
        }
        if (source.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** [T-known-failure-payload] Same payload (or neither side has one) ⇒ same rake. */
    private fun samePayload(a: String, b: String): Boolean = a == b

    /** Jaccard similarity of the normalized token sets. */
    internal fun similarity(a: String, b: String): Double {        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() && right.isEmpty()) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val inter = left.count { it in right }
        val union = left.size + right.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    private fun tokens(text: String): Set<String> =
        TOKEN.split(text.lowercase())
            .asSequence()
            .filter { it.isNotEmpty() }
            .toSet()

    private val TOKEN = Regex("[^a-z0-9_]+")

    /**
     * [T-known-failure-near] The failure cause, normalized so paths, ids and
     * numbers do not prevent a cluster from matching. Only same-cause near
     * calls are refused; a new error starts a fresh record.
     */
    internal fun errorClass(error: String): String {
        val masked = com.openminis.app.util.SecretMasking.mask(error).lowercase()
        return masked
            .replace(Regex("[\"'][^\"']*[\"']"), "*")
            .replace(Regex("/[^\\s:]+"), "*")
            .replace(Regex("\\b[0-9a-f]{6,}\\b"), "#")
            .replace(Regex("\\b\\d+\\b"), "#")
            .replace(WHITESPACE, " ")
            .trim()
            .take(180)
    }

    /**
     * Gate used by tool dispatch: null means "not blocked, proceed".
     * Never throws — an unreadable ledger must not break dispatch.
     */
    fun refusalFor(
        context: Context,
        sessionId: String,
        tool: String,
        argsJson: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ToolExecutionResult? = runCatching {
        val entry = lookup(ledgerFile(context, sessionId), tool, argsJson, nowMs) ?: return null
        ToolExecutionResult(
            output = refusalText(entry, argsExcerpt(argsJson)),
            success = false,
            errorCode = ToolErrorCode.EXECUTION_FAILED,
            recoveryHint = "This call already failed with these arguments; the same call would fail again.",
            toolTitle = tool,
        )
    }.getOrNull()

    /** Records the outcome of one dispatched call. Never throws. */
    fun recordOutcome(
        context: Context,
        sessionId: String,
        tool: String,
        argsJson: String,
        success: Boolean,
        output: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            val file = ledgerFile(context, sessionId)
            if (success) clear(file, tool, argsJson)
            else recordFailure(file, tool, argsJson, output, nowMs)
        }
    }

    // ── storage ────────────────────────────────────────────────────────────

    private fun readAll(file: File): Map<String, Entry> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = org.json.JSONObject(file.readText())
            val out = LinkedHashMap<String, Entry>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = root.optJSONObject(key) ?: continue
                out[key] = Entry(
                    tool = obj.optString("tool"),
                    failures = obj.optInt("failures"),
                    lastError = obj.optString("lastError"),
                    lastAtMs = obj.optLong("lastAtMs"),
                    argsExcerpt = obj.optString("argsExcerpt"),
                    argsCanonical = obj.optString("argsCanonical"),
                    errorClass = obj.optString("errorClass"),
                    payloadSha = obj.optString("payloadSha"),
                )
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun writeAll(file: File, all: Map<String, Entry>) {
        val trimmed = all.entries
            .sortedByDescending { it.value.lastAtMs }
            .take(MAX_ENTRIES)
        val root = org.json.JSONObject()
        for ((key, entry) in trimmed) {
            root.put(
                key,
                org.json.JSONObject()
                    .put("tool", entry.tool)
                    .put("failures", entry.failures)
                    .put("lastError", entry.lastError)
                    .put("lastAtMs", entry.lastAtMs)
                    .put("argsExcerpt", entry.argsExcerpt)
                    .put("argsCanonical", entry.argsCanonical)
                    .put("errorClass", entry.errorClass)
                    .put("payloadSha", entry.payloadSha),
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(root.toString())
    }
}
