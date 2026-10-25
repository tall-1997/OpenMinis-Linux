#!/usr/bin/env python3
"""Python mirror of SupervisorSessionRunner loop semantics.

Local verification only (no kotlinc on this device): mirrors the Kotlin loop
shape so the turn accounting, bounce, strike, cap, and halt paths can be
exercised before CI compiles the real thing.
"""
import sys

sys.path.insert(0, "/var/minis/workspace/OpenMinis-Linux/src/android/app/src/test/python-mirror")

from supervisor_mirror import (  # noqa: E402
    Runner,
    FakeSeat,
    Inbox,
    envelope,
)

failures = []


def check(name, cond):
    print(("PASS " if cond else "FAIL ") + name)
    if not cond:
        failures.append(name)


def run_case(name, sup_replies, worker_replies, max_turns=10, **kw):
    sup = FakeSeat(sup_replies)
    worker = FakeSeat(worker_replies)
    inbox = Inbox()
    rounds = []
    terminal = []
    outcome = Runner.run(
        objective="obj",
        context_digest="ctx",
        max_worker_turns=max_turns,
        sup=lambda u, s: sup.speak(u, s),
        worker=lambda u, s: worker.speak(u, s),
        inbox=inbox,
        on_round=lambda n, i, r: rounds.append((n, i, r)),
        on_terminal=lambda s, m: terminal.append((s, m)),
        **kw,
    )
    return outcome, sup, worker, rounds, terminal


# 1. happy path: two rounds then DONE
o, sup, worker, rounds, term = run_case(
    "happy",
    [envelope("do a", "tests pass"), "mid review", envelope("fix b", "tests pass"), "DONE: tests pass and shipped"],
    ["report a", "report b"],
)
check("happy: DONE", o.kind == "DONE")
check("happy: summary", o.summary == "tests pass and shipped")
check("happy: two rounds", len(rounds) == 2 and rounds[0][0] == 1 and rounds[1][0] == 2)
check("happy: worker called twice", len(worker.calls) == 2)
check("happy: terminal DONE", term[0][0] == "done")

# 2. envelope rejection bounces without consuming a worker turn
o, sup, worker, rounds, term = run_case(
    "bounce",
    ["no envelope", envelope("do a", "tests pass"), "DONE: tests pass"],
    ["report a"],
)
check("bounce: DONE", o.kind == "DONE")
check("bounce: worker once", len(worker.calls) == 1)

# 3. rubber stamp twice fails
o, sup, worker, rounds, term = run_case(
    "rubber",
    [envelope("do a", "tests pass"), "DONE: all fine", "DONE: still fine"],
    ["report a"],
)
check("rubber: FAILED", o.kind == "FAILED")
check("rubber: reason mentions rubber", "rubber" in (o.reason or ""))
check("rubber: terminal failed", term[0][0] == "failed")

# 4. cap: after the last allowed round the next supervisor prompt is the
# cap prompt (no third worker round), and a DONE there closes the session.
o, sup, worker, rounds, term = run_case(
    "cap",
    [envelope("do a", "tests pass"), envelope("fix b", "tests pass"), "DONE: tests pass partially"],
    ["r1", "r2"],
    max_turns=2,
)
check("cap: DONE", o.kind == "DONE")
check("cap: worker twice only", len(worker.calls) == 2)
check("cap: cap prompt sent", "预算已耗尽" in sup.calls[2])

# 5. cap with non-DONE final answer force-closes FAILED
o, sup, worker, rounds, term = run_case(
    "cap-fail",
    [envelope("do a", "tests pass"), envelope("fix b", "tests pass"), "cannot finish"],
    ["r1", "r2"],
    max_turns=2,
)
check("cap-fail: FAILED", o.kind == "FAILED")
check("cap-fail: budget reason", "budget" in (o.reason or ""))

# 6. user halt mid-review: USER_HALTED + rethrow
import asyncio  # noqa: E402


async def async_worker(w):
    async def seat(u, s):
        return w.speak(u, s)
    return seat


async def halt_case():
    sup = FakeSeat([envelope("do a", "tests pass")])
    worker = FakeSeat(["report a"])
    terminal = []
    calls = [0]

    async def sup_seat(u, s):
        calls[0] += 1
        if calls[0] > 1:
            raise asyncio.CancelledError("halt")
        return sup.speak(u, s)

    try:
        await Runner.run_async(
            objective="obj", context_digest="", max_worker_turns=10,
            sup=sup_seat, worker=await async_worker(worker),
            inbox=Inbox(), on_terminal=lambda s, m: terminal.append((s, m)),
        )
        return "no-throw", terminal
    except asyncio.CancelledError:
        return "cancelled", terminal


kind, terminal = asyncio.run(halt_case())
check("halt: rethrown", kind == "cancelled")
check("halt: USER_HALTED", terminal and terminal[0][0] == "user_halted")

# 7. inbox reaches the first supervisor turn
sup = FakeSeat([envelope("do a", "tests pass"), "DONE: tests pass"])
worker = FakeSeat(["report a"])
inbox = Inbox()
inbox.push("keep it small")
Runner.run(
    objective="obj", context_digest="", max_worker_turns=10,
    sup=lambda u, s: sup.speak(u, s), worker=lambda u, s: worker.speak(u, s),
    inbox=inbox,
)
check("inbox: first turn carries message", "keep it small" in sup.calls[0])

# 8. recovery intro folds into first turn
sup = FakeSeat([envelope("do a", "tests pass"), "DONE: tests pass"])
worker = FakeSeat(["report a"])
Runner.run(
    objective="obj", context_digest="", max_worker_turns=10,
    sup=lambda u, s: sup.speak(u, s), worker=lambda u, s: worker.speak(u, s),
    inbox=Inbox(), recovery="上次中断于第 7 轮",
)
check("recovery: intro present", "上次中断于第 7 轮" in sup.calls[0])

# 9. worker error fails the session
worker_fail = FakeSeat(["boom"])
worker_fail.fail_with = RuntimeError("provider 500")
o, _, _, _, term = (
    Runner.run(
        objective="obj", context_digest="", max_worker_turns=10,
        sup=lambda u, s: envelope("do a", "tests pass"),
        worker=lambda u, s: worker_fail.speak(u, s),
        inbox=Inbox(),
    ),
    None, None, None, None,
)
check("worker-error: FAILED", o.kind == "FAILED")
check("worker-error: reason has 500", "500" in (o.reason or ""))

# 10. supervisor call budget guard
o, sup, worker, rounds, term = run_case(
    "budget-guard",
    ["no envelope"] * 500,
    [],
)
check("budget-guard: FAILED", o.kind == "FAILED")
check("budget-guard: reason", "call budget" in (o.reason or ""))

print()
if failures:
    print(f"{len(failures)} FAILURES: {failures}")
    sys.exit(1)
print("ALL GREEN")
