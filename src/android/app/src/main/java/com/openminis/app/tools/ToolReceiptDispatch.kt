package com.openminis.app.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val receiptExemptObservations = setOf(
    "file_read", "list_dir", "grep", "glob", "read_image", "get_screen_time", "search_sessions", "read_session",
    HistorySearchTool.SEARCH, HistorySearchTool.READ,
)

/** Match MemoryTools' search default; only a known search may use the read-only fast paths. */
internal fun memoryGetIsSearch(args: String): Boolean = try {
    JSONObject(args).optString("action", "search") == "search"
} catch (_: Exception) {
    false
} catch (_: StackOverflowError) {
    false
}

/** Polling may consume a read cursor or mark a report seen. It never re-runs the work. */
internal fun isReceiptPolling(canonical: String, args: String): Boolean = when (canonical) {
    "agent_terminal" -> parseReceiptArgs(args)?.optString("action")?.trim()?.lowercase() in setOf("read", "list")
    "check_agent" -> parseReceiptArgs(args)?.optString("op", "status")?.ifBlank { "status" } in
        setOf("status", "list", "await", "collect")
    else -> false
}

/** Scheduling in a concurrent lane does not prove that a tool is read-only. */
internal fun needsToolReceipt(canonical: String, args: String): Boolean = when (canonical) {
    "memory_get" -> !memoryGetIsSearch(args)
    else -> canonical !in receiptExemptObservations && !isReceiptPolling(canonical, args)
}

internal const val TOOL_RECEIPT_NOTICE = "[tool-receipt]"

internal fun ToolExecutionResult.isReceiptAdvisory(): Boolean =
    errorCode == ToolErrorCode.RECEIPT_UNCONFIRMED || errorCode == ToolErrorCode.RECEIPT_BLOCKED

internal fun ToolExecutionResult.withNotSentNotice(): ToolExecutionResult = if (isReceiptAdvisory()) this else copy(
    output = "$TOOL_RECEIPT_NOTICE Tool not sent. $output", success = false,
    receiptProof = null, notSent = true, deliveryAccepted = false,
)

internal fun toolReceiptMissingAnchor() = ToolExecutionResult(
    "$TOOL_RECEIPT_NOTICE Tool not sent: no stable call identity for this turn.",
    false, errorCode = ToolErrorCode.RECEIPT_BLOCKED, notSent = true,
)

/**
 * [T-receipt-terminal-evidence] Adapters that produce their own delivery
 * evidence. The generic boundary must not overrule them: `mcp` deliberately
 * leaves a transport failure pending (`deliveryAccepted = serverAnswered`),
 * and the shell/script/spawn lanes report executed-vs-not inside their own
 * result construction.
 */
private val selfReportedDeliveryTools = setOf(
    McpNativeTool.NAME,
    "ssh_exec",
    ExecuteCodeTool.NAME,
    CodemodeTool.NAME,
    "shell_exec", "shell_execute", "env_exec", "su_exec",
)

/**
 * [T-receipt-terminal-evidence] An adapter that RETURNED has a known delivery
 * state: the call ran, or the refusal is the adapter's own answer. Only
 * `notSent` (never dispatched), an unmarked timeout (remote answer lost) and a
 * receipt advisory are genuinely unknown.
 *
 * Every other in-process adapter used to leave the receipt at UNKNOWN on a
 * plain business failure — a mistyped `old_string`, a missing file, an HTTP
 * 4xx — which told the model "it may already have occurred, do not repeat"
 * about an edit that certainly did not happen, skipped the known-failure
 * ladder, and pinned an UNKNOWN record forever (they are never recycled), so a
 * long session eventually filled the receipt store and started refusing every
 * other mutating tool.
 */
internal fun normalizeDeliveryEvidence(canonical: String, result: ToolExecutionResult): ToolExecutionResult =
    if (canonical in selfReportedDeliveryTools || SubAgentKind.isSpawnTool(canonical) || result.notSent ||
        result.timedOut || result.deliveryAccepted || result.isReceiptAdvisory()
    ) {
        result
    } else {
        result.copy(deliveryAccepted = true)
    }

/** Execution boundary shared by turn, spawn, script, and detached-lane callers. */
internal class ToolReceiptDispatch(private val store: ToolReceiptStore) {
    suspend fun run(
        key: ToolReceiptKey,
        callId: String,
        authorize: suspend () -> ToolExecutionResult?,
        dispatch: suspend (Delivery) -> ToolExecutionResult,
    ): ToolExecutionResult {
        val prepared = withContext(Dispatchers.IO) { store.prepare(key) }
        if (prepared != ToolReceiptDecision.Ready) return refusal(prepared)
        authorize()?.let { return it.withNotSentNotice() } // The ledger never stores grants.
        val owner = currentCoroutineContext()
        owner.ensureActive()
        // Do not lose the claim to prompt cancellation on the IO dispatcher hop. If
        // cancelled before entering the adapter, the host can still prove not_sent.
        val claim = withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                owner.ensureActive()
                store.claim(key, callId)
            }
        }
        if (claim !is ToolReceiptDecision.Claimed) return refusal(claim)
        val delivery = Delivery(store, key, claim)
        if (!owner.isActive) {
            delivery.notSent()
            owner.ensureActive()
        }
        val result = try {
            dispatch(delivery)
        } catch (e: CancellationException) {
            owner.ensureActive() // Preserve real run cancellation.
            return pendingResult(delivery.state, ToolExecutionResult("", false, timedOut = e is TimeoutCancellationException),
                resultRecorded = delivery.localCompletionRecorded)
        } catch (e: Exception) {
            // [P3-3 final] A swallowed adapter exception must stay attributable:
            // the pending receipt is the ledger's state, but "why pending" needs
            // the original failure logged where the dispatch happened.
            com.openminis.app.logging.AppLogger.error(
                "ToolReceiptDispatch",
                "receipted dispatch failed, staying pending: ${e.javaClass.simpleName}: ${e.message}",
            )
            return pendingResult(delivery.state, resultRecorded = delivery.localCompletionRecorded)
        }
        return when (delivery.state) {
            ToolReceiptState.UNKNOWN -> pendingResult(delivery.state, result)
            ToolReceiptState.NOT_SENT -> result.withNotSentNotice()
            ToolReceiptState.ACCEPTED -> {
                if (!delivery.covers(result)) {
                    return pendingResult(delivery.state, result, resultRecorded = delivery.localCompletionRecorded)
                }
                if (result.success) result.copy(receiptProof = claim.proof) else result.copy(
                    // [T-receipt-fact-not-order] A statement of fact, not an order.
                    // "Do not repeat it automatically" plus NO_REPEAT's "Verify
                    // independently…" made the model stop and emit verification
                    // prose ("验证✓") between tool calls.
                    output = "$TOOL_RECEIPT_NOTICE The tool accepted the call but reported no success; " +
                        "the result above may be incomplete.\n${result.output}",
                    recoveryHint = NO_REPEAT,
                    receiptProof = claim.proof,
                )
            }
        }
    }

    /** Only the dispatch adapter may supply evidence; no inference from generic success or output text. */
    internal class Delivery(
        private val store: ToolReceiptStore,
        val key: ToolReceiptKey,
        private val claim: ToolReceiptDecision.Claimed,
    ) {
        val instanceSha: String get() = claim.instanceSha
        var state: ToolReceiptState = ToolReceiptState.UNKNOWN
            private set

        private var terminalSuccess: Boolean? = null
        var localCompletionRecorded = false
            private set

        suspend fun accepted() = settle(ToolReceiptState.ACCEPTED)
        suspend fun notSent() = settle(ToolReceiptState.NOT_SENT)

        /** Called at the adapter return, before output rendering or other fallible post-processing. */
        suspend fun completed(result: ToolExecutionResult): ToolExecutionResult {
            if (result.notSent) {
                notSent()
            } else if (state != ToolReceiptState.NOT_SENT && result.isTerminalEvidence() &&
                (terminalSuccess == null || terminalSuccess == result.success)
            ) {
                terminalSuccess = result.success
                if (key.scope.localResults) {
                    if (!localCompletionRecorded) {
                        localCompletionRecorded = withContext(NonCancellable + Dispatchers.IO) {
                            store.completeLocal(key, claim, result.success)
                        }
                    }
                    if (localCompletionRecorded) state = ToolReceiptState.ACCEPTED else accepted()
                } else accepted()
            }
            return result
        }

        fun covers(result: ToolExecutionResult): Boolean =
            terminalSuccess == result.success && result.isTerminalEvidence() &&
                (!key.scope.localResults || localCompletionRecorded)

        /**
         * A proven-ended outcome settles: the adapter answered, or the host
         * marked a terminal delivery (executed timeout / business failure /
         * sandbox failure). An uncertain timeout WITHOUT that marker — e.g. a
         * remote reply that never arrived — still stays pending.
         */
        private fun ToolExecutionResult.isTerminalEvidence(): Boolean = !notSent && !isReceiptAdvisory() &&
            (deliveryAccepted || (success && errorCode == null && !timedOut))

        private suspend fun settle(next: ToolReceiptState) {
            if (state != ToolReceiptState.UNKNOWN) return
            // Keep the live evidence even if settlement I/O fails. On-disk UNKNOWN still
            // blocks replay; a real result carrying the proof can settle it after reopen.
            state = next
            withContext(NonCancellable + Dispatchers.IO) { store.settle(key, claim, next) }
        }
    }

    private fun refusal(decision: ToolReceiptDecision): ToolExecutionResult = when (decision) {
        is ToolReceiptDecision.AlreadySent -> pendingResult(decision.state, resultRecorded = decision.resultRecorded, duplicate = true)
        ToolReceiptDecision.UnprovenBatch -> blocked("No verified result establishes a new tool batch.")
        ToolReceiptDecision.Full -> blocked("The receipt store is full; existing delivery records were kept.")
        else -> blocked("The receipt checkpoint could not be verified.")
    }

    private fun blocked(reason: String) = ToolExecutionResult(
        "$TOOL_RECEIPT_NOTICE Tool not sent. $reason", false, errorCode = ToolErrorCode.RECEIPT_BLOCKED, notSent = true,
    )

    private fun pendingResult(
        state: ToolReceiptState,
        original: ToolExecutionResult? = null,
        resultRecorded: Boolean = false,
        duplicate: Boolean = false,
    ): ToolExecutionResult {
        val status = when (state) {
            ToolReceiptState.UNKNOWN -> "Delivery is pending confirmation; it may already have occurred."
            ToolReceiptState.ACCEPTED -> if (resultRecorded) {
                "This call was accepted and its completion was recorded."
            } else {
                "This call was accepted; a completed result was not recorded."
            }
            ToolReceiptState.NOT_SENT -> return blocked("The adapter did not start this call.")
        }
        val attempt = if (duplicate) " This attempt was not sent." else ""
        // [T-receipt-fact-not-order] Facts only — the old tail ("Do not repeat
        // the operation automatically") ordered the model to act on the
        // receipt. The replacement states the SYSTEM's rule, which still
        // discourages a blind retry without telling the model to stop and
        // verify.
        val output = "$TOOL_RECEIPT_NOTICE $status$attempt " +
            "An identical retry in this turn is rejected by the delivery gate." +
            if (original?.output.isNullOrBlank()) "" else "\n${original?.output}"
        return (original ?: ToolExecutionResult(output, false)).copy(
            output = output, success = false, errorCode = ToolErrorCode.RECEIPT_UNCONFIRMED,
            recoveryHint = NO_REPEAT, receiptProof = null, deliveryAccepted = false,
            notSent = duplicate,
        )
    }

    private companion object {
        /** [T-receipt-fact-not-order] State, never a command — see pendingResult. */
        const val NO_REPEAT = "This delivery is uncertain or already accepted; treat its result as unconfirmed."
    }
}
