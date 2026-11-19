#!/usr/bin/env python3
"""Check production wiring for durable tool dispatch and in-run read replay.

The Kotlin ToolReceiptDispatchTest and ToolReceiptHistoryTest exercise the
actual persistence, partial batches, consent, and recovery; this fast gate
catches routes that skip those tested boundaries. A provider call ID is not
a durable identity, and an in-memory replay result is not delivery evidence.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / "src/android/app/src/main/java/com/openminis/app"
errors: list[str] = []


def check(cond: bool, msg: str) -> None:
    if not cond:
        errors.append(msg)


def source(path: str) -> str:
    text = (ROOT / path).read_text(encoding="utf-8")
    return re.sub(r"/\*.*?\*/|^[ \t]*//[^\n]*", "", text, flags=re.DOTALL | re.MULTILINE)


def between(text: str, start: str, end: str) -> str:
    _, found, rest = text.partition(start)
    return rest.split(end, 1)[0] if found else ""


def ordered(text: str, *parts: str) -> bool:
    offset = 0
    for part in parts:
        index = text.find(part, offset)
        if index < 0:
            return False
        offset = index + len(part)
    return True


ledger = source("ui/chat/ToolExecutionLedger.kt")
check(ordered(ledger, "entries.putIfAbsent(key, claim)", "val part = produce()"),
      "read replay must atomically claim before invoking its producer")
check("CompletableDeferred" in ledger and "entries.remove(key, claim)" in ledger,
      "read replay must join its owner and release a failed claim")
check("return existing.part.withoutReceiptProof()" in ledger and
      "return part.withoutReceiptProof()" in ledger,
      "a read replay must not introduce transferable receipt evidence")

turn = source("ui/chat/ChatViewModelToolTurnExec.kt")
serial = between(turn, "private suspend fun ChatViewModel.runPlannedCall(",
                 "private suspend fun ChatViewModel.fanOutSpawnCalls(")
fanout = between(turn, "private suspend fun ChatViewModel.fanOutSpawnCalls(",
                 "internal suspend fun ChatViewModel.executePlannedToolTurn(")
check("ToolReceiptScope.userTurn(activeSessionId, receiptUserTurnId)" in turn,
      "the planned turn must use the stable persisted user row")
check(ordered(serial, "val result = executeTool(", "receiptScope = ctx.receiptScope",
              "if (needsToolReceipt(", "produce()", "ctx.ledger.executeOnce("),
      "protected calls must use the durable scope, not the provider-ID cache")
check("onReuse = { prior -> settleReplayedCall(ctx, call.id, prior) }" in serial and
      "toolStatus = settledReplayStatus(part)" in turn,
      "reused read results must settle their UI cards")
check("runPlannedCall(ctx, peer, execution.executedParts" in fanout and
      "executeRunSubAgent(" not in turn and "ledger.reuse(" not in turn,
      "spawn peers must use the same receipt path as ordinary calls")

controller = source("ui/chat/ChatViewModelExecuteToolExt.kt")
execute = between(controller, "internal suspend fun ChatViewModel.executeTool(",
                  "internal fun receiptDispatchName(")
check(ordered(execute, "if (needsToolReceipt(", "receiptScope?.takeIf", ".call(canonical, argsJson)",
              "?: return toolReceiptMissingAnchor()", "ToolReceiptDispatch(toolReceiptStore()).run("),
      "the tool dispatcher must fail closed if a protected call has no stable receipt key")
check("SecurityGateHolder.intercept(" in controller and "toolAuthorizationRefusal(" in execute,
      "the durable dispatcher must retain the original permission gate")

boundary = source("tools/ToolReceiptDispatch.kt")
check(ordered(boundary, "store.prepare(key)", "authorize()?.let", "store.claim(key, callId)",
              "if (claim !is ToolReceiptDecision.Claimed)", "dispatch(delivery)"),
      "authorize first, then atomically claim; rejected or unverified claims cannot dispatch")
check("ToolReceiptState.UNKNOWN -> pendingResult" in boundary and "receiptProof = claim.proof" in boundary,
      "unconfirmed returns cannot become a trusted transcript result")
store = source("tools/ToolReceiptStore.kt")
claim = between(store, "fun claim(", "fun settle(")
check(ordered(claim, "state = ToolReceiptState.UNKNOWN", "write(file, records)", "ToolReceiptDecision.Claimed("),
      "the claim must commit unknown before returning a permit to dispatch")
check("handle.channel.lock().use" in store and "ATOMIC_MOVE, REPLACE_EXISTING" in store,
      "receipt updates must stay locked and atomic across process instances")

for path in ("ChatViewModelGroupChatSpeak.kt", "ChatViewModelSubAgentRun.kt",
             "ChatViewModelSupervisorExt.kt", "ChatViewModelToolReceipts.kt"):
    code = source("ui/chat/" + path)
    check(re.search(r"\bexecuteTool\([^\n]+receiptScope\s*=", code) is not None,
          f"{path} must dispatch nested tools with the owning receipt scope")

if errors:
    print("tool execution contract: FAIL")
    for error in errors:
        print("  - " + error)
    sys.exit(1)
print("tool execution contract: OK (durable claim before dispatch on all lanes; read replay settles UI)")
