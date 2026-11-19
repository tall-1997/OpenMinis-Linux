 package com.openminis.app.ui.chat
 
 import com.openminis.app.tools.ToolExecutionResult
 
 /**
  * [T-tool-receipt] Dispatch one mutating call under the receipt contract.
  *
  * WHY a separate file: `ChatViewModelToolTurnExec.kt` sits at the800-line cap,
  * and these steps are ONE contract - they must not drift into two
  * implementations the way the token meter and the tool pairing did.
  *
  * The order matters:
  *1. the pending notice is peeked first (never consumed here): it describes
  * only work a PREVIOUS process left unfinished;
  *2. an operation id reused for different content is refused, not merged;
  *3. the journal `tool/call` line lands BEFORE the side effect, so a crash
  * mid-call leaves a call with no result - the shape the log exists for;
  *4. `not_sent` lands before dispatch, so a crash leaves the `unknown` state;
  *5. a throw abandons the receipt; a returned result settles it;
  *6. the notice is ACKNOWLEDGED only once it is actually part of the result.
  */
 internal suspend fun ChatViewModel.dispatchWithReceipt(
    ctx: ToolTurnContext,
    call: PlannedToolCall,
    pendingReceipts: List<org.json.JSONObject>,
    dispatch: suspend () -> ToolExecutionResult,
 ): ToolExecutionResult {
    val conflictedTool = ToolReceiptStore.conflictOf(
        filesDir = context.filesDir,
        sessionId = ctx.activeSessionId,
        opId = call.id,
        tool = call.name,
        argsJson = call.argsStr,
    )
    if (conflictedTool != null) {
        // The refusal still carries the notice, and it is still acknowledged:
        // this string is delivered, so the "outcome unknown" warning has been
        // seen and must not be repeated.
        val notice = if (pendingReceipts.isEmpty()) "" else receiptNotice(pendingReceipts) + "\n\n"
        if (pendingReceipts.isNotEmpty()) ackUnsettledReceipts(ctx, pendingReceipts)
        return ToolExecutionResult(
            notice +
                "Error: operation id conflict - this call reuses an id already recorded "
                + "for a different `${conflictedTool}` operation. Issue a new id for a new "
                + "operation, or verify the earlier result first.",
            false,
            toolTitle = call.name,
        )
    }
    recordToolCallJournal(ctx.turn, call.name, call.argsStr)
    ToolReceiptStore.begin(context.filesDir, ctx.activeSessionId, call.name, call.argsStr, call.id)
    val rawResult = try {
        dispatch()
    } catch (t: Throwable) {
        // The call may still have landed: the receipt is closed as abandoned and
        // the id stays ambiguous, so a later different-args reuse is a conflict.
        ToolReceiptStore.abandon(context.filesDir, ctx.activeSessionId, call.name, call.argsStr, call.id)
        throw t
    }
    ToolReceiptStore.settle(
        context.filesDir, ctx.activeSessionId, call.name, call.argsStr, rawResult.success,
        opId = call.id,
    )
    recordToolResultJournal(ctx.turn, call.name, call.argsStr, rawResult.success)
    if (pendingReceipts.isEmpty()) return rawResult
    ackUnsettledReceipts(ctx, pendingReceipts)
    return rawResult.copy(output = receiptNotice(pendingReceipts) + "\n\n" + rawResult.output)
 }
 
 /** [T-tool-receipt-report-once] The model-facing notice for these receipts. */
 private fun receiptNotice(receipts: List<org.json.JSONObject>): String {
    val names = receipts.mapNotNull { it.optString("tool").takeIf { n -> n.isNotBlank() } }
        .distinct()
        .take(3)
    return "[receipt] ${receipts.size} earlier mutating call(s) never reported a result " +
        ("(${names.joinToString(", ")}). Their outcome is UNKNOWN, not failed: verify the ") +
        "effect (read the file / check the process / inspect git status) before repeating them."
 }
 
 /** [T-tool-receipt-report-once] Mark delivered receipts as announced. */
 private fun ChatViewModel.ackUnsettledReceipts(
    ctx: ToolTurnContext,
    receipts: List<org.json.JSONObject>,
 ) {
    if (receipts.isEmpty()) return
    ToolReceiptStore.ackUnsettled(context.filesDir, ctx.activeSessionId, receipts)
 }
 
