 package com.openminis.app.ui.chat
 
 import com.openminis.app.tools.ToolExecutionResult
 
 /**
  * [T-tool-receipt] Dispatch one mutating call under the receipt contract.
  *
  * WHY a separate file: `ChatViewModelToolTurnExec.kt` sits at the800-line cap,
  * and these four steps are one contract - they must not drift into two
  * implementations the way the token meter and the tool pairing did.
  *
  * The order matters:
  *1. the notice describes only a PREVIOUS process's unfinished work, so it is
  * read before this call's own `not_sent` line lands;
  *2. an operation id reused for different content is refused, not merged;
  *3. `not_sent` lands before dispatch, so a crash leaves the `unknown` state;
  *4. a throw abandons the receipt instead of leaving it open forever, and a
  * returned result settles it.
  */
 internal suspend fun ChatViewModel.dispatchWithReceipt(
    ctx: ToolTurnContext,
    call: PlannedToolCall,
    unresolvedReceipt: String?,
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
        return ToolExecutionResult(
            "Error: operation id conflict - this call reuses an id already recorded "
                + "for a different `$conflictedTool` operation. Issue a new id for a new "
                + "operation, or verify the earlier result first.",
            false,
            toolTitle = call.name,
        )
    }
    ToolReceiptStore.begin(context.filesDir, ctx.activeSessionId, call.name, call.argsStr, call.id)
    val rawResult = try {
        dispatch()
    } catch (t: Throwable) {
        ToolReceiptStore.abandon(context.filesDir, ctx.activeSessionId, call.name, call.argsStr, call.id)
        throw t
    }
    ToolReceiptStore.settle(
        context.filesDir, ctx.activeSessionId, call.name, call.argsStr, rawResult.success,
        opId = call.id,
    )
    return if (unresolvedReceipt == null) {
        rawResult
    } else {
        rawResult.copy(output = unresolvedReceipt + "\n\n" + rawResult.output)
    }
 }
 
