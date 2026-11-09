#!/usr/bin/env python3
"""[T-toolcall-missing-id] Python mirror of OpenAIToolCallIds.resolveToolCallId.

Kotlin source of truth:
  src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIToolCallIds.kt

Verifies the id-resolution contract that the tool-card input-loss regression
(2026-11-09) violated: an id-less later delta must keep the captured id, and
a started call must never be renamed.
Run: python3 scripts/toolcall_id_resolve_verify.py
"""


def resolve_tool_call_id(current_id: str, delta_id: str, index: int, started: bool) -> str:
    if started:
        return current_id
    if delta_id:
        return delta_id
    if current_id:
        return current_id
    return f"tool-call-{index}"


CASES = [
    # (current, delta, index, started, expected, label)
    ("", "call_abc123", 0, False, "call_abc123", "first delta with real id captures it"),
    ("call_abc123", "", 0, True, "call_abc123", "id-less later delta keeps captured real id (regression case)"),
    ("", "", 0, False, "tool-call-0", "relay without any id derives stable id on first delta"),
    ("tool-call-2", "", 2, True, "tool-call-2", "derived id stays stable on later id-less deltas"),
    ("call_first", "call_second", 1, False, "call_second", "later non-empty id wins before start (pre-regression semantics)"),
    ("tool-call-0", "call_late", 0, True, "tool-call-0", "started call is never renamed even by a late real id"),
]


def main() -> int:
    failures = []
    for current, delta, index, started, expected, label in CASES:
        got = resolve_tool_call_id(current, delta, index, started)
        ok = got == expected
        print(f"  [{'PASS' if ok else 'FAIL'}] {label}: resolve({current!r}, {delta!r}, {index}, started={started}) = {got!r} (want {expected!r})")
        if not ok:
            failures.append(label)
    # Parallel-call distinctness, mirrored from OpenAIToolCallIdTest.
    ids = [resolve_tool_call_id("", "", i, False) for i in range(4)]
    distinct = ids == [f"tool-call-{i}" for i in range(4)]
    print(f"  [{'PASS' if distinct else 'FAIL'}] parallel id-less calls derive distinct stable ids: {ids}")
    if not distinct:
        failures.append("parallel distinctness")
    total = len(CASES) + 1
    passed = total - len(failures)
    print(f"{passed}/{total} green")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
