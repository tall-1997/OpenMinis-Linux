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

    /** Keys whose values are prose/payload, not a call identity. */
    private val NON_IDENTITY_KEYS = setOf(
        "tool_title", "tool_input", "content", "text", "body", "prompt",
        "value", "data", "old_string", "new_string",
    )

    private val WHITESPACE = Regex("\\s+")

    data class Entry(
        val tool: String,
        val failures: Int,
        val lastError: String,
        val lastAtMs: Long,
        /** [T-known-failure-readable] Bounded argument identity for the refusal. */
        val argsExcerpt: String = "",
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
        val entry = readAll(file)[fingerprint(tool, argsJson)] ?: return null
        if (entry.failures < MIN_FAILURES) return null
        if (nowMs - entry.lastAtMs > WINDOW_MS) return null
        return entry
    }

    /** Records one failure; returns the entry as stored. */
    fun recordFailure(file: File, tool: String, argsJson: String, error: String, nowMs: Long): Entry {
        val key = fingerprint(tool, argsJson)
        val all = readAll(file).toMutableMap()
        val previous = all[key]
        val entry = Entry(
            tool = tool,
            failures = (previous?.failures ?: 0) + 1,
            lastError = com.openminis.app.util.TextClip.safeTake(
                com.openminis.app.util.SecretMasking.mask(error),
                400,
            ),
            lastAtMs = nowMs,
            argsExcerpt = argsExcerpt(argsJson),
        )
        all[key] = entry
        writeAll(file, all)
        return entry
    }

    /** A success means the call works now — forget the failures. */
    fun clear(file: File, tool: String, argsJson: String) {
        val key = fingerprint(tool, argsJson)
        val all = readAll(file)
        if (!all.containsKey(key)) return
        writeAll(file, all - key)
    }

    /** The refusal the model receives instead of a second identical attempt. */
    fun refusalText(entry: Entry): String = buildString {
        append("[known-failure] ")
        append(entry.tool)
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
            output = refusalText(entry),
            success = false,
            errorCode = ToolErrorCode.EXECUTION_FAILED,
            recoveryHint = "Do not repeat this call. Diagnose why it failed, then change the arguments.",
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
                    .put("argsExcerpt", entry.argsExcerpt),
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(root.toString())
    }
}
