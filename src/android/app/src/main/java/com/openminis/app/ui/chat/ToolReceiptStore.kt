package com.openminis.app.ui.chat

import org.json.JSONObject

/**
 * [T-tool-receipt] Three-state durable receipts for MUTATING tool calls.
 *
 * WHY this exists: a `file_edit` (or shell write, git commit, terminal input)
 * that lands on disk and then loses the process before the UI settles leaves no
 * evidence. The model sees "failed", retries, and the write happens twice — the
 * classic at-least-once duplication. Codex-harness solves this with an
 * operations ledger whose states are `accepted / not_sent / unknown` and whose
 * rule is explicit: **unknown is not a retryable failure**.
 *
 * Scope is deliberately narrow:
 *  - only MUTATING tools are recorded ([isMutating]); a read costs nothing;
 *  - a receipt is written BEFORE the call (`not_sent`) and settled AFTER it
 *    (`accepted`), so a crash between them is observable as `unknown`;
 *  - the store never decides whether to run a call. It reports what it knows,
 *    and the host turns `unknown` into "verify before retrying" guidance. That
 *    keeps the semantic decision in one place and the storage dumb.
 *
 * Storage: `<filesDir>/tool-receipts/<safe-session>.jsonl`, same append-only
 * shape as [SessionJournal] and [WorkingMemoryStore]: one JSON object per line,
 * trimmed by rewrite, never fatal on IO error.
 */
internal object ToolReceiptStore {

    internal const val DIR = "tool-receipts"

    /** Receipt states. Keep stable: the model-facing advice keys off them. */
    internal const val NOT_SENT = "not_sent"
    internal const val ACCEPTED = "accepted"
    internal const val UNKNOWN = "unknown"

    /** [T-tool-receipt-abandon-append] Terminal state for a dispatch that never
     * produced an outcome (cancelled / scope torn down / threw). It closes a
     * `not_sent` line without claiming the call ran or failed. */
    internal const val ABANDONED = "abandoned"

 /** [T-tool-receipt-conflict] Field carrying the caller's operation id. */
 internal const val OP_ID = "op"

    /** Hard cap on the live file; older lines are dropped by rewrite. */
    internal const val MAX_BYTES = 2L * 1024 * 1024
    internal const val TRIM_TO_LINES = 1_000

    /**
     * Tools whose effect outlives the call. Read-only tools are absent on
     * purpose: a duplicated read costs tokens, a duplicated write costs data.
     */
    private val MUTATING = setOf(
        "file_write", "file_edit", "multi_edit", "shell_execute", "env_exec",
        "su_exec", "terminal", "terminal_write", "git", "ssh", "job_start",
        "cronjob", "memory_write", "web_app_shortcut",
    )

    /** [T-tool-receipt] True when this call must leave a receipt. */
    fun isMutating(tool: String): Boolean {
        // Canonicalize first: aliases (shell_exec, save_memory) must not slip
        // through as "different, therefore read-only" calls.
        val canonical = runCatching {
            com.openminis.app.security.ToolAliases.canonical(tool)
        }.getOrDefault(tool)
        return canonical in MUTATING || tool in MUTATING
    }

    private fun safeSession(sessionId: String): String =
        sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "_" }

    internal fun file(filesDir: java.io.File, sessionId: String): java.io.File =
        java.io.File(java.io.File(filesDir, DIR), "${safeSession(sessionId)}.jsonl")

    /** Stable identity for one attempted call. */
    internal fun receiptKey(tool: String, argsJson: String): String =
        com.openminis.app.tools.ToolFailureMemory.fingerprint(tool, argsJson)

    /**
     * [T-tool-receipt-conflict] Does [opId] already name a DIFFERENT operation?
     *
     * The contract is about the OPERATION ID, not the call's content. A
     * content fingerprint cannot express it: [receiptKey] hashes (tool, args),
     * so two different operations always differ and the same operation always
     * matches - a conflict is invisible from the key alone. Reusing an id for
     * different content is the case a replay must be REFUSED rather than
     * merged: the caller would either re-run a mutation it believes already
     * landed, or skip verification on one that did not.
     *
     * Returns the recorded tool name of the conflicting record, or null. A
     * blank id, a missing file or a read-only tool are all no-conflict -
     * the check must never be the reason a call is refused.
     */
    fun conflictOf(
        filesDir: java.io.File?,
        sessionId: String,
        opId: String,
        tool: String,
        argsJson: String,
    ): String? {
        if (filesDir == null || sessionId.isBlank() || opId.isBlank()) return null
        if (!isMutating(tool)) return null
        val target = file(filesDir, sessionId)
        if (!target.isFile) return null
        val key = receiptKey(tool, argsJson)
        val parsed = runCatching {
            target.readLines(Charsets.UTF_8).mapNotNull { line ->
                if (line.isBlank()) null
                else runCatching { JSONObject(line) }.getOrNull()
            }
        }.getOrDefault(emptyList())
        val sameOp = parsed.filter { o -> o.optString(OP_ID) == opId }
        val conflicting = sameOp.firstOrNull { o -> o.optString("key") != key }
        return conflicting?.optString("tool")?.takeIf { it.isNotBlank() }
    }
    
    /**
     * Record `not_sent` BEFORE dispatch. A crash after this line and before
     * [settle] is exactly the `unknown` state the caller must verify.
     */
    fun begin(
        filesDir: java.io.File?,
        sessionId: String,
        tool: String,
        argsJson: String,
        opId: String = "",
    ) {
        if (filesDir == null || sessionId.isBlank() || !isMutating(tool)) return
        append(
            filesDir, sessionId,
            JSONObject()
                .put("key", receiptKey(tool, argsJson))
                .put("tool", tool)
                .put("state", NOT_SENT)
        .put(OP_ID, opId)
                .put("at", System.currentTimeMillis()),
        )
    }

    /** Record the terminal state after the call returned. */
    fun settle(
        filesDir: java.io.File?,
        sessionId: String,
        tool: String,
        argsJson: String,
        success: Boolean,
        opId: String = "",
    ) {
        if (filesDir == null || sessionId.isBlank() || !isMutating(tool)) return
        append(
            filesDir, sessionId,
            JSONObject()
                .put("key", receiptKey(tool, argsJson))
                .put("tool", tool)
                .put("state", ACCEPTED)
        .put(OP_ID, opId)
                .put("success", success)
                .put("at", System.currentTimeMillis()),
        )
    }

    /**
     * [T-tool-receipt] Receipts that opened and never settled, i.e. the process
     * died mid-call. Callers turn these into "verify before retrying" guidance
     * instead of a bare failure.
     *
     * Reachable from production through [unsettledNotice], which the dispatch
     * preflight prepends to the result the model sees. Keeping the raw list
     * public lets a test pin the storage contract without a ViewModel.
     */
    /**
     * [T-tool-receipt-cancel] The call never produced an outcome (cancelled,
     * scope torn down, dispatch threw). Clearing its `not_sent` line keeps the
     * UNKNOWN signal meaningful: it now means "the process died", not "the user
     * pressed stop once". Without this, one cancelled `file_edit` made every
     * later tool result in the session carry a stale warning.
     */
    fun abandon(
        filesDir: java.io.File?,
        sessionId: String,
        tool: String,
        argsJson: String,
        opId: String = "",
    ) {
        if (filesDir == null || sessionId.isBlank() || !isMutating(tool)) return
        // [T-tool-receipt-abandon-append] Append a closer; never rewrite.
        // Rewriting here raced a concurrent `begin`/`settle` on the same file
        // (subagent fan-out settles in parallel) and could delete an append
        // that landed between the read and the move. The file is append-only
        // by contract, and [unsettled] treats this state as a closer.
        append(
            filesDir, sessionId,
            JSONObject()
                .put("key", receiptKey(tool, argsJson))
                .put("tool", tool)
                .put("state", ABANDONED)
        .put(OP_ID, opId)
                .put("at", System.currentTimeMillis()),
        )
    }

    fun unsettled(filesDir: java.io.File?, sessionId: String): List<JSONObject> {
        if (filesDir == null || sessionId.isBlank()) return emptyList()
        val target = file(filesDir, sessionId)
        if (!target.isFile) return emptyList()
        return runCatching {
            val open = LinkedHashMap<String, JSONObject>()
            target.readLines(Charsets.UTF_8).forEach { line ->
                if (line.isBlank()) return@forEach
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                val key = o.optString("key", "")
                if (key.isBlank()) return@forEach
                when (o.optString("state")) {
                    NOT_SENT -> open[key] = o
                    ACCEPTED, ABANDONED -> open.remove(key)
                }
            }
            open.values.toList()
        }.getOrDefault(emptyList())
    }

    private fun append(filesDir: java.io.File, sessionId: String, obj: JSONObject) {
        runCatching {
            val target = file(filesDir, sessionId)
            target.parentFile?.mkdirs()
            if (target.isFile && target.length() > MAX_BYTES) trim(target)
            target.appendText(obj.toString() + "\n", Charsets.UTF_8)
        }
    }

    private fun trim(target: java.io.File) {
        runCatching {
            val kept = target.readLines(Charsets.UTF_8)
                .filter { it.isNotBlank() }
                .takeLast(TRIM_TO_LINES)
            val tmp = java.io.File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(kept.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    /**
     * [T-tool-receipt] One-line guidance for the model when a previous process
     * died mid-call, or null when nothing is pending.
     *
     * The wording is the contract: `unknown` is NOT a retryable failure, so the
     * model is told to verify the effect before repeating the call. This is the
     * only place the states become model-visible; the storage layer stays dumb.
     */
    fun unsettledNotice(filesDir: java.io.File?, sessionId: String): String? {
        val open = unsettled(filesDir, sessionId)
        if (open.isEmpty()) return null
        val names = open.mapNotNull { it.optString("tool").takeIf { n -> n.isNotBlank() } }
            .distinct()
            .take(3)
        return "[receipt] ${open.size} earlier mutating call(s) never reported a result " +
            "(${names.joinToString(", ")}). Their outcome is UNKNOWN, not failed: verify the " +
            "effect (read the file / check the process / inspect git status) before repeating them."
    }

    /** Test seam. */
    internal fun reset(filesDir: java.io.File?, sessionId: String) {
        if (filesDir == null) return
        runCatching { file(filesDir, sessionId).delete() }
    }
}
