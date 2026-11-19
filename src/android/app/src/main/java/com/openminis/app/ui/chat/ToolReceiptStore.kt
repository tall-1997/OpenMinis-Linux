 package com.openminis.app.ui.chat
 
 import org.json.JSONObject
 
 /**
  * [T-tool-receipt] Durable receipts for MUTATING tool calls.
  *
  * WHY: a `file_edit` (or shell write, git commit) that lands on disk and then
  * loses the process before the UI settles leaves no evidence. The model sees
  * "failed", retries, and the write happens twice. The reference contract is an
  * operations ledger whose states are `accepted / not_sent / unknown` and whose
  * rule is explicit: **unknown is not a retryable failure**.
  *
  * Scope is deliberately narrow:
  * - only MUTATING tools are recorded ([isMutating]); a read costs nothing;
  * - `not_sent` is written BEFORE the call and `accepted` after it, so a crash
  * between them is observable as `unknown`;
  * - the store never decides whether to run a call: it reports what it knows,
  * and the host turns `unknown` into "verify before retrying" guidance.
  *
  * Storage: `<filesDir>/tool-receipts/<safe-session>.jsonl`, append-only, one
  * JSON object per line, trimmed by rewrite, never fatal on IO error.
  *
  * [T-tool-receipt-cache] The file is EVIDENCE; the hot path reads a process
  * projection. Re-reading and re-parsing the whole file on every tool call is
  * quadratic: a1000-call session paid ~2M JSON parses and hundreds of MB of
  * repeated reads on the path the model waits on. One lock per session file
  * guards the projection AND the append/trim, so the two cannot diverge.
  */
 internal object ToolReceiptStore {
 
    internal const val DIR = "tool-receipts"
    
    /** Receipt states. Keep stable: the model-facing advice keys off them. */
    internal const val NOT_SENT = "not_sent"
    internal const val ACCEPTED = "accepted"
    internal const val UNKNOWN = "unknown"
    
    /**
     * [T-tool-receipt-abandon-append] Terminal state for a dispatch that never
     * produced an outcome (cancelled / scope torn down / threw). It closes a
     * `not_sent` line without claiming the call ran or failed.
     */
    internal const val ABANDONED = "abandoned"
    
    /** [T-tool-receipt-conflict] Field carrying the caller's operation id. */
    internal const val OP_ID = "op"
    
    /** Hard cap on the live file; older lines are dropped by rewrite. */
    internal const val MAX_BYTES =2L *1024 *1024
    internal const val TRIM_TO_LINES =1_000
    
    /** Sessions projected at once; the least recently used is dropped. */
    private const val MAX_SESSIONS =32
    
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
     * [T-tool-receipt-cache] Projection of one session file.
     *
     * `open` holds receipts that opened and never closed (the `unknown` set),
     * `ops` maps an operation id to the fingerprint last recorded for it,
     * `ambiguous` remembers ids whose last outcome was NOT a clean success
     * (failed settle / abandoned), and `reported` remembers which open receipts
     * were already announced so the warning is shown once.
     */
    private class State {
        val open = LinkedHashMap<String, JSONObject>()
        val ops = HashMap<String, String>()
        val ambiguous = HashSet<String>()
        val reported = HashSet<String>()
    }
    
    /** LRU so a long-lived process does not pin one projection per session. */
    private val states = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, State>(16,0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, State>,
            ): Boolean = size > MAX_SESSIONS
        },
    )
    
    private val fileLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    
    private fun lockFor(target: java.io.File): Any =
        fileLocks.getOrPut(target.absolutePath) { Any() }
    
    private fun stateFor(target: java.io.File): State =
        synchronized(states) { states.getOrPut(target.absolutePath) { load(target) } }
    
    private fun load(target: java.io.File): State {
        val state = State()
        if (!target.isFile) return state
        runCatching {
            target.readLines(Charsets.UTF_8).forEach { line ->
                if (line.isBlank()) return@forEach
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                val key = o.optString("key", "")
                if (key.isBlank()) return@forEach
                val op = o.optString(OP_ID, "")
                when (o.optString("state")) {
                    NOT_SENT -> state.open[key] = o
                    ACCEPTED -> {
                        state.open.remove(key)
                        if (o.optBoolean("success", false)) state.ambiguous.remove(op) else if (op.isNotBlank()) state.ambiguous.add(op)
                    }
                    ABANDONED -> {
                        state.open.remove(key)
                        if (op.isNotBlank()) state.ambiguous.add(op)
                    }
                }
                if (op.isNotBlank()) state.ops[op] = key
            }
        }
        return state
    }
    
    /**
     * [T-tool-receipt-conflict] Does [opId] already name a DIFFERENT operation
     * whose outcome is not a clean success?
     *
     * A content fingerprint cannot express the contract: [receiptKey] hashes
     * (tool, args), so two different operations always differ.
     *
     * The check fires when the earlier record for this id is still OPEN (outcome
     * unknown) or settled as FAILED/abandoned (side effect possibly landed) and
     * the content differs - replaying then risks a double execution or skipping
     * verification. A CLEAN success with the same id and different args means the
     * provider reused an id (JSON relays that omit `tool_call.id` used to fall
     * back to a positional name that repeats every response); refusing those
     * would disable every mutating tool in the session, so it is not a conflict.
     *
     * Returns the recorded tool name of the conflicting record, or null.
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
        val key = receiptKey(tool, argsJson)
        return synchronized(lockFor(target)) {
            val state = stateFor(target)
            val recorded = state.ops[opId] ?: return@synchronized null
            if (recorded == key) return@synchronized null
            val openRecord = state.open[recorded]
            if (openRecord != null) {
                openRecord.optString("tool").takeIf { it.isNotBlank() }
            } else if (opId in state.ambiguous) {
                "previous " + tool + " attempt" + " (failed or abandoned)"
            } else {
                null
            }
        }
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
        val target = file(filesDir, sessionId)
        val key = receiptKey(tool, argsJson)
        val record = JSONObject()
            .put("key", key)
            .put("tool", tool)
            .put("state", NOT_SENT)
            .put(OP_ID, opId)
            .put("at", System.currentTimeMillis())
        synchronized(lockFor(target)) {
            val state = stateFor(target)
            state.open[key] = record
            if (opId.isNotBlank()) state.ops[opId] = key
            state.reported.remove(key)
            appendLocked(target, record)
        }
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
        val target = file(filesDir, sessionId)
        val key = receiptKey(tool, argsJson)
        val record = JSONObject()
            .put("key", key)
            .put("tool", tool)
            .put("state", ACCEPTED)
            .put(OP_ID, opId)
            .put("success", success)
            .put("at", System.currentTimeMillis())
        synchronized(lockFor(target)) {
            val state = stateFor(target)
            state.open.remove(key)
            state.reported.remove(key)
            if (opId.isNotBlank()) {
                state.ops[opId] = key
                if (success) state.ambiguous.remove(opId) else state.ambiguous.add(opId)
            }
            appendLocked(target, record)
        }
    }
    
    /**
     * [T-tool-receipt-cancel] The call never produced an outcome (cancelled,
     * scope torn down, dispatch threw). Closing the receipt keeps the UNKNOWN
     * signal meaningful: it then means "the process died", not "the user pressed
     * stop once". The id stays marked ambiguous, because a cancelled mutation may
     * still have landed.
     */
    fun abandon(
        filesDir: java.io.File?,
        sessionId: String,
        tool: String,
        argsJson: String,
        opId: String = "",
    ) {
        if (filesDir == null || sessionId.isBlank() || !isMutating(tool)) return
        val target = file(filesDir, sessionId)
        val key = receiptKey(tool, argsJson)
        val record = JSONObject()
            .put("key", key)
            .put("tool", tool)
            .put("state", ABANDONED)
            .put(OP_ID, opId)
            .put("at", System.currentTimeMillis())
        synchronized(lockFor(target)) {
            val state = stateFor(target)
            state.open.remove(key)
            state.reported.remove(key)
            if (opId.isNotBlank()) {
                state.ops[opId] = key
                state.ambiguous.add(opId)
            }
            appendLocked(target, record)
        }
    }
    
    /** Receipts that opened and never settled, i.e. the process died mid-call. */
    fun unsettled(filesDir: java.io.File?, sessionId: String): List<JSONObject> {
        if (filesDir == null || sessionId.isBlank()) return emptyList()
        val target = file(filesDir, sessionId)
        return synchronized(lockFor(target)) {
            stateFor(target).open.values.toList()
        }
    }
    
    /**
     * [T-tool-receipt-report-once] The open receipts not yet announced to the
     * model, WITHOUT consuming them.
     *
     * WHY peek and ack are separate: the caller can drop the notice (a conflict
     * refusal, or a dispatch that throws), and a version that marked on peek
     * would lose the warning forever - which is exactly the double-execution
     * this feature exists to prevent. Callers MUST call [ackUnsettled] only when
     * the notice actually reaches the model.
     */
    fun pendingUnsettled(filesDir: java.io.File?, sessionId: String): List<JSONObject> {
        if (filesDir == null || sessionId.isBlank()) return emptyList()
        val target = file(filesDir, sessionId)
        return synchronized(lockFor(target)) {
            val state = stateFor(target)
            state.open.entries.filter { it.key !in state.reported }.map { it.value }
        }
    }
    
    /** Mark these receipts as announced (call only when they were delivered). */
    fun ackUnsettled(
        filesDir: java.io.File?,
        sessionId: String,
        receipts: List<JSONObject>,
    ) {
        if (filesDir == null || sessionId.isBlank() || receipts.isEmpty()) return
        val target = file(filesDir, sessionId)
        synchronized(lockFor(target)) {
            val state = stateFor(target)
            receipts.forEach { r -> r.optString("key").takeIf { it.isNotBlank() }?.let { state.reported.add(it) } }
        }
    }
    
    /** One-line guidance for the model, or null when nothing new is pending. */
    fun unsettledNotice(filesDir: java.io.File?, sessionId: String): String? {
        val fresh = pendingUnsettled(filesDir, sessionId)
        if (fresh.isEmpty()) return null
        val names = fresh.mapNotNull { it.optString("tool").takeIf { name -> name.isNotBlank() } }
            .distinct()
            .take(3)
        return "[receipt] ${fresh.size} earlier mutating call(s) never reported a result " +
            ("(${names.joinToString(", ")}). Their outcome is UNKNOWN, not failed: verify the ") +
            "effect (read the file / check the process / inspect git status) before repeating them."
    }
    
    private fun appendLocked(target: java.io.File, obj: JSONObject) {
        runCatching {
            target.parentFile?.mkdirs()
            if (target.isFile && target.length() > MAX_BYTES) trimLocked(target)
            target.appendText(obj.toString() + "\n", Charsets.UTF_8)
        }
    }
    
    /**
     * Rewrite the file to its tail, then rebuild the projection from it while
     * KEEPING the announced set: trimming is a storage event, not a new day, and
     * re-announcing every仍然 open receipt on each trim was the bug that made
     * "report once" untrue for long sessions.
     */
    private fun trimLocked(target: java.io.File) {
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
            val announced = synchronized(states) { states[target.absolutePath]?.reported }
            val rebuilt = load(target)
                .also { fresh -> announced?.let { fresh.reported.addAll(it) } }
            synchronized(states) { states[target.absolutePath] = rebuilt }
        }
    }
    
    /** Test seam. */
    internal fun reset(filesDir: java.io.File?, sessionId: String) {
        if (filesDir == null) return
        val target = file(filesDir, sessionId)
        synchronized(states) { states.remove(target.absolutePath) }
        runCatching { target.delete() }
    }
 }
