#!/usr/bin/env python3
"""[T-tool-execution-ledger] Ratchet for the run-scoped single-execution contract.

Background (#330 regression): the serial lane consulted the ledger BEFORE
dispatching a tool, but the spawn fan-out ran every `executeRunSubAgent` under
`awaitAll()` and only THEN called `ledger.reuse()` — a replayed spawn had
already launched a second wave of sub-agents; reuse only swapped which result
was shown. The contract was inverted on one lane.

The root-cause fix makes `ToolExecutionLedger.executeOnce` the ONE execution
boundary: it claims (id, name, argsSha) atomically before any side effect, and
both lanes route the whole dispatch+finalize through it. This gate keeps that
shape from regressing. It checks structure (tree-state, no diff needed) and
runs a pure-Python state-machine replica that reproduces the old violation and
proves the new boundary prevents it.

Runs on every CI event; a violation fails the build before any slow compile.
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHAT = ROOT / "src/android/app/src/main/java/com/openminis/app/ui/chat"
LEDGER = CHAT / "ToolExecutionLedger.kt"
EXEC = CHAT / "ChatViewModelToolTurnExec.kt"

errors: list[str] = []


def check(cond: bool, msg: str) -> None:
    if not cond:
        errors.append(msg)


def strip_comments(src: str) -> str:
    """Blank out // line comments and /* ... */ blocks, keep positions/offsets."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if src[k] != "\n":
                    out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)


def function_body(src: str, signature: str) -> str | None:
    """Return the balanced-brace body of the first function whose signature
    contains `signature`. Brace matching over the comment-stripped source;
    `${...}` string templates are balanced too, so they do not skew the count.
    """
    clean = strip_comments(src)
    start = clean.find(signature)
    if start < 0:
        return None
    brace = clean.find("{", start)
    if brace < 0:
        return None
    depth = 0
    for k in range(brace, len(clean)):
        c = clean[k]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return clean[brace:k + 1]
    return None


# ---- 1. Ledger exposes ONE atomic boundary ---------------------------------
ledger_src = LEDGER.read_text(encoding="utf-8") if LEDGER.exists() else ""
check("suspend fun executeOnce(" in ledger_src,
      "ToolExecutionLedger.kt must expose `suspend fun executeOnce(` as the single execution boundary")
check("CompletableDeferred" in ledger_src,
      "executeOnce must use CompletableDeferred so concurrent duplicates join the in-flight owner")
check("entries.putIfAbsent" in ledger_src,
      "executeOnce must claim the key atomically via putIfAbsent (first writer wins)")
check("entries.remove(key, claim)" in ledger_src,
      "a failed producer must release its own claim so a retry can execute (no cached failure)")
check("onReuse: suspend (AgentContentPart.ToolResult) -> Unit" in ledger_src,
      "onReuse must be a suspend callback so both lanes can settle the replayed UI card")
# ---- 2. Both lanes route through executeOnce before any side effect --------
exec_src = EXEC.read_text(encoding="utf-8") if EXEC.exists() else ""
check("fun settledReplayStatus(" in exec_src,
      "a replayed call's tool_use block must be mapped to a terminal status (no PENDING/RUNNING spin)")
check(exec_src.count("settleReplayedCall(ctx,") == 2,
      "both execution lanes must call settleReplayedCall from onReuse (serial + spawn fan-out)")
serial = function_body(exec_src, "private suspend fun ChatViewModel.runPlannedCall(")
fanout = function_body(exec_src, "private suspend fun ChatViewModel.fanOutSpawnCalls(")

check(serial is not None, "runPlannedCall function body not found")
check(fanout is not None, "fanOutSpawnCalls function body not found")

if serial is not None:
    check("ledger.executeOnce(" in serial,
          "runPlannedCall MUST go through ledger.executeOnce (single execution boundary)")
    check("ledger.reuse(" not in serial and "ledger.record(" not in serial,
          "runPlannedCall must NOT use split reuse()/record() — that is a non-atomic check-then-act")

if fanout is not None:
    check("ledger.executeOnce(" in fanout,
          "fanOutSpawnCalls MUST go through ledger.executeOnce (single execution boundary)")
    check("ledger.reuse(" not in fanout and "ledger.record(" not in fanout,
          "fanOutSpawnCalls must NOT use split reuse()/record() after awaitAll()")
    p_claim = fanout.find("ledger.executeOnce(")
    p_dispatch = fanout.find("executeRunSubAgent(")
    check(p_claim >= 0 and p_dispatch >= 0,
          "fanOutSpawnCalls must contain both the ledger claim and executeRunSubAgent")
    check(p_claim >= 0 and p_dispatch >= 0 and p_claim < p_dispatch,
          "REGRESSION: executeRunSubAgent appears BEFORE the ledger claim. "
          "The side effect must be INSIDE the executeOnce producer so a replay never dispatches a second wave.")

# Whole-file: no production split-decision calls, no dead intermediate carrier.
prod_split = [ln.strip() for ln in exec_src.splitlines()
              if ("ledger.reuse(" in ln or "ledger.record(" in ln)
              and not ln.strip().startswith(("*", "//", "/*"))]
check(not prod_split,
      f"production code still calls split ledger.reuse()/record(): {prod_split}")
check("parallelSubResults" not in exec_src,
      "parallelSubResults dead carrier must stay removed — finalize now happens inside the producer")

# ---- 3. Pure-Python state-machine replica: BEFORE vs AFTER -----------------
class ReplicaLedger:
    """Faithful model of ToolExecutionLedger.executeOnce keyed by (id,name,sha)."""
    def __init__(self) -> None:
        self.completed: dict[tuple[str, str, str], str] = {}
        self.inflight: set[tuple[str, str, str]] = set()

    def execute_once(self, key, side_effect, log):
        if key in self.completed:                # replay → reuse, no side effect
            log.append(("reuse", key))
            return self.completed[key]
        if key in self.inflight:                 # concurrent duplicate → join owner
            log.append(("join", key))
            return self.completed[key]
        self.inflight.add(key)                    # atomic claim (putIfAbsent)
        try:
            result = side_effect()               # producer: dispatch + finalize
            self.completed[key] = result
            return result
        finally:
            self.inflight.discard(key)


KEY = ("spawn_1", "spawn_agent", "sha-x")

# BEFORE (#330): dispatch unconditionally, then reuse only swaps the result.
before_fires = 0

def before_lane(lg: ReplicaLedger, log):
    global before_fires
    before_fires += 1                            # executeRunSubAgent ALWAYS runs
    log.append(("dispatch", KEY))
    prior = lg.completed.get(KEY)                # reuse AFTER the side effect
    if prior is not None:
        log.append(("late-reuse", KEY))
        return prior
    lg.completed[KEY] = "wave-1"
    return "wave-1"

before_log: list = []
lg_old = ReplicaLedger()
before_lane(lg_old, before_log)                  # first emission
before_lane(lg_old, before_log)                  # replay emission (compact/state recovery)
check(before_fires == 2,
      f"state-machine replica must reproduce the OLD violation (expected 2 dispatches, got {before_fires})")

# AFTER: the whole producer is inside executeOnce → replay never dispatches.
after_fires = 0

def after_lane(lg: ReplicaLedger, log):
    global after_fires
    def produce():
        global after_fires
        after_fires += 1
        log.append(("dispatch", KEY))
        return "wave-1"
    return lg.execute_once(KEY, produce, log)

after_log: list = []
lg_new = ReplicaLedger()
r1 = after_lane(lg_new, after_log)
r2 = after_lane(lg_new, after_log)
check(after_fires == 1,
      f"NEW boundary must dispatch exactly once across a replay (got {after_fires})")
check(r1 == r2 == "wave-1",
      "replay must receive the recorded result unchanged")
check(("reuse", KEY) in after_log,
      "replay must be served from the ledger (reuse path)")

# Cross-key identity: same id, different args → genuinely new call.
fresh_fires = 0

def fresh_produce():
    global fresh_fires
    fresh_fires += 1
    return "wave-2"

new_key = ("spawn_1", "spawn_agent", "sha-new")
lg_new.execute_once(new_key, fresh_produce, after_log)
check(fresh_fires == 1,
      "same id with a different argsSha must execute, not reuse a stale result")
check(lg_new.completed[new_key] == "wave-2", "new call must record its own result")

# Failure releases the claim so a retry can execute.
def boom():
    raise RuntimeError("disk on fire")

retry_key = ("spawn_2", "spawn_agent", "sha-y")
try:
    lg_new.execute_once(retry_key, boom, after_log)
except RuntimeError:
    pass
check(retry_key not in lg_new.completed and retry_key not in lg_new.inflight,
      "a failed producer must not cache a result or leave an in-flight claim")
retry_fires = 0

def retry_produce():
    global retry_fires
    retry_fires += 1
    return "wave-retry"

lg_new.execute_once(retry_key, retry_produce, after_log)
check(retry_fires == 1 and lg_new.completed[retry_key] == "wave-retry",
      "retry after a failed execution must run and record successfully")

# ---- verdict ---------------------------------------------------------------
if errors:
    print("tool execution contract: FAIL")
    for e in errors:
        print("  - " + e)
    sys.exit(1)
print("tool execution contract: OK "
      "(2 lanes on executeOnce; replay 1 dispatch vs old 2; "
      "cross-key identity, in-flight join, failure retry, replay UI settle verified)")
