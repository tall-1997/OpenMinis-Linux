package com.openminis.app.tools

import com.openminis.app.security.ToolAliases
import com.openminis.app.util.Digest
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Stable host-provided anchor; never the provider's call id, UI bubble, or stream attempt. */
internal class ToolReceiptScope private constructor(
    val sessionSha: String,
    val parentSha: String,
    val laneSha: String,
    val ownerSha: String = laneSha,
    val sequenceSha: String = laneSha,
    val stepOrdinal: Int = 0,
    val localResults: Boolean = false,
    val after: ToolReceiptPredecessor? = null,
) {
    /** Independent host-owned conversation; do not pass provider IDs or attempt counters. */
    fun lane(kind: String, identity: String): ToolReceiptScope = child(kind, identity, identity)

    /** Deterministic position in a sequence; cannot bypass its owner's unresolved operations. */
    fun step(kind: String, ordinal: Int): ToolReceiptScope {
        require(ordinal >= 0)
        return ToolReceiptScope(sessionSha, parentSha, receiptDigest(laneSha, kind, ordinal.toString()),
            ownerSha, receiptDigest(laneSha, kind), ordinal, localResults = true)
    }

    fun afterTranscript(resultRowId: String?): ToolReceiptScope = if (resultRowId.isNullOrBlank()) this else
        withPredecessor(ToolReceiptPredecessor.Transcript(receiptDigest("result-row", resultRowId)))

    fun afterLocal(proof: String?): ToolReceiptScope = if (proof == null || !proof.isReceiptHash()) this else
        withPredecessor(ToolReceiptPredecessor.Local(receiptDigest("proof", proof)))

    private fun withPredecessor(previous: ToolReceiptPredecessor) =
        ToolReceiptScope(sessionSha, parentSha, laneSha, ownerSha, sequenceSha, stepOrdinal, localResults, previous)

    fun call(name: String, finalizedArgs: String): ToolReceiptKey? {
        val canonical = ToolAliases.canonical(name)
        if (canonical.isBlank() || canonical.length > 512) return null
        // [T-receipt-large-args] A call identity must never be refused for being
        // big. `file_write` has no content ceiling of its own and a large
        // heredoc/script is an ordinary turn; the old 512 KiB refusal surfaced
        // as "no stable call identity", i.e. the tool never ran and the message
        // blamed the turn instead of the size. Above the normalization window
        // the exact bytes are hashed instead of re-parsed (parsing is the part
        // that costs memory), so the call still gets a stable, replay-detectable
        // identity.
        if (finalizedArgs.length > MAX_NORMALIZED_ARGS_CHARS) {
            val raw = Digest.sha256Hex(finalizedArgs)
            return ToolReceiptKey(
                this,
                receiptDigest(parentSha, laneSha, canonical, raw),
                receiptDigest(parentSha, MAIN_LANE, canonical, raw),
                receiptDigest("operation", canonical, raw),
                false,
            )
        }
        val args = parseReceiptArgs(finalizedArgs) ?: return null
        val originalArgs = receiptArgsSha(args) ?: return null
        val normalized = effectiveReceiptArgs(canonical, args) ?: return null
        val normalizedArgs = if (normalized === args) originalArgs else receiptArgsSha(normalized) ?: return null
        val intent = receiptDigest(parentSha, laneSha, canonical, normalizedArgs)
        val legacy = receiptDigest(parentSha, MAIN_LANE, canonical, originalArgs)
        val operation = receiptDigest("operation", canonical, normalizedArgs)
        return ToolReceiptKey(this, intent, legacy, operation, normalized !== args)
    }

    internal fun child(kind: String, owner: String, instance: String): ToolReceiptScope = ToolReceiptScope(
        sessionSha, parentSha, receiptDigest(laneSha, kind, instance),
        receiptDigest(ownerSha, kind, owner), localResults = true,
    )

    companion object {
        fun userTurn(sessionId: String, userRowId: String?): ToolReceiptScope? =
            root(sessionId, "user-row", userRowId)

        /**
         * [T-receipt-internal-run] A run with no user row of its own — internal
         * Goal execution, a resumed tail whose only user rows are host notes —
         * anchors its receipts on the run identity instead.
         *
         * Without it every receipted tool returned "no stable call identity"
         * for the whole run: `/goal create` in a fresh chat never persists a
         * user row (`internalGoalRun` skips it), so `file_write`,
         * `shell_execute`, MCP and every other mutating tool were refused and
         * a Goal could only read files.
         */
        fun internalRun(sessionId: String, runId: String?): ToolReceiptScope? =
            root(sessionId, "internal-run", runId)

        fun supervisor(sessionId: String, runId: String): ToolReceiptScope? =
            root(sessionId, "supervisor-run", runId)

        private fun root(sessionId: String, kind: String, anchor: String?): ToolReceiptScope? {
            if (sessionId.isBlank() || anchor.isNullOrBlank()) return null
            val session = sessionDigest(sessionId)
            return ToolReceiptScope(session, receiptDigest(session, kind, anchor), MAIN_LANE)
        }

        internal val MAIN_LANE = receiptDigest("main")
        fun sessionDigest(sessionId: String): String = receiptDigest("session", sessionId)
    }
}

internal sealed class ToolReceiptPredecessor(val sha: String) {
    class Transcript(sha: String) : ToolReceiptPredecessor(sha)
    class Local(sha: String) : ToolReceiptPredecessor(sha)
}

internal data class ToolReceiptKey(
    val scope: ToolReceiptScope,
    val logicalSha: String,
    val legacySha: String,
    val operationSha: String,
    val operationNormalized: Boolean = false,
) {
    fun instanceSha(batch: Int): String = if (batch == 0) logicalSha
    else receiptDigest("receipt-instance", logicalSha, batch.toString())
}

internal fun receiptDigest(vararg fields: String): String = Digest.sha256Hex(buildString {
    for (field in fields) append(field.length).append(':').append(field)
})

internal fun parseReceiptArgs(args: String): JSONObject? {
    // nextClean uses NUL as its end sentinel on both Android and the JVM.
    if (args.length > MAX_NORMALIZED_ARGS_CHARS || '\u0000' in args) return null
    return try {
        val parser = org.json.JSONTokener(args)
        (parser.nextValue() as? JSONObject)?.takeIf { parser.nextClean() == '\u0000' }
    } catch (_: Exception) {
        null
    } catch (_: StackOverflowError) {
        null
    }
}

/**
 * [T-receipt-large-args] Largest argument payload the identity layer re-parses
 * for normalization. Real tool arguments come from model output and sit far
 * below this; the value is a memory guard for the parse, not a limit on what a
 * turn may call.
 */
internal const val MAX_NORMALIZED_ARGS_CHARS = 2 * 1024 * 1024

/** The MCP executor and receipt key must see exactly the same effective payload. */
internal fun mcpCallArguments(raw: Any?): JSONObject? = when (raw) {
    null, JSONObject.NULL -> JSONObject()
    is JSONObject -> raw
    is String -> parseReceiptArgs(raw)
    else -> null
}

private val shellExecutionTools = setOf("shell_execute", "env_exec", "su_exec")

private fun effectiveReceiptArgs(canonical: String, args: JSONObject): JSONObject? = when {
    canonical == "mcp" && args.optString("action").trim() == "call" -> mcpCallArguments(args.opt("arguments"))?.let {
        JSONObject().put("action", "call").put("server", args.optString("server").trim())
            .put("tool", args.optString("tool").trim()).put("arguments", it)
    }
    canonical in shellExecutionTools -> JSONObject()
        .put("command", args.optString("command", ""))
        // A workspace can route this to SSH, where cwd changes the effect.
        .put("cwd", args.optString("cwd", "").trim())
    canonical == "ssh_exec" && args.optString("action").trim() == "exec" -> JSONObject()
        .put("action", "exec").put("host", args.optString("host", "").trim())
        .put("command", args.optString("command", "")).put("cwd", args.optString("cwd", "").trim())
    else -> args
}

/** Exact value identity with sorted object keys, stable number spelling, and no display title. */
private fun receiptArgsSha(args: JSONObject): String? = try {
    Digest.sha256Hex(buildString { appendReceiptJson(args, 0) })
} catch (_: Exception) {
    null
} catch (_: StackOverflowError) {
    null
}

private fun StringBuilder.appendReceiptJson(value: Any?, depth: Int) {
    require(depth <= 64)
    when {
        value === null || value === JSONObject.NULL -> append("null")
        value is String -> append(JSONObject.quote(value))
        value is Boolean -> append(value)
        value is Number -> append(BigDecimal(value.toString()).stripTrailingZeros().toString())
        value is JSONObject -> {
            append('{')
            value.keys().asSequence().filterNot { depth == 0 && it == "tool_title" }.sorted()
                .forEachIndexed { i, key ->
                    if (i > 0) append(',')
                    append(JSONObject.quote(key)).append(':')
                    appendReceiptJson(value.get(key), depth + 1)
                }
            append('}')
        }
        value is JSONArray -> {
            append('[')
            for (i in 0 until value.length()) {
                if (i > 0) append(',')
                appendReceiptJson(value.get(i), depth + 1)
            }
            append(']')
        }
        else -> error("Unsupported receipt argument type")
    }
}
