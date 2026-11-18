#!/usr/bin/env python3
"""[T-standing-rules-not-memory] Structural ratchet: the memory toggle must
never be able to delete the user's standing contract.

Field report: "会话级规则失效" — the session-menu standing rules looked active
but never reached the model. Root cause was one `if`:

    val sessionMem = if (memoryOn) sessionMemoryRepo() else null
    val keptInstructionKeys = if (memoryOn) InstructionDedupe.firstSeen(…) else emptySet()
    val projectResolved = if (memoryOn) ProjectInstructionStore.resolve(…) else null

Standing rules, the app-wide GLOBAL.md block and the project's AGENTS.md layer
are RULES, not memory content; the toggle exists so the model is not fed stale
daily logs / recall, and that job stays. Wiring instructions through it again
would silently reintroduce the silent drop — and a unit test cannot see it,
because the gating lives in a Composable-era builder nobody can instantiate in
a JVM test. Hence a source guard, the same idiom as the CJK and
ThinkingLevel.displayName ratchets.

Exit 1 when the builder gates an instruction source behind `memoryOn`.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
BUILDER = ROOT / "src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelPromptExt.kt"

# An instruction source assigned through a `memoryOn` conditional.
FORBIDDEN = [
    re.compile(r"val\s+sessionMem\s*=\s*if\s*\(\s*memoryOn\s*\)"),
    re.compile(r"val\s+projectResolved\s*=\s*if\s*\(\s*memoryOn\s*\)"),
    re.compile(r"val\s+keptInstructionKeys\s*=\s*if\s*\(\s*memoryOn\s*\)"),
]


def main() -> int:
    if not BUILDER.is_file():
        print(f"check_standing_rules_not_memory: builder not found: {BUILDER}", file=sys.stderr)
        return 2
    text = BUILDER.read_text(encoding="utf-8")
    hits: list[str] = []
    for pattern in FORBIDDEN:
        for match in pattern.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            hits.append(f"  {BUILDER.name}:{line}: {match.group(0).strip()}")

    if hits:
        print("standing-rules gate: memory toggle is gating a RULE source again", file=sys.stderr)
        for h in hits:
            print(h, file=sys.stderr)
        print(
            "\nStanding rules / GLOBAL.md / AGENTS.md are contracts, not memory.\n"
            "Only daily-log and recall payloads may be gated by memoryOn.\n"
            "See [T-standing-rules-not-memory] in the builder.",
            file=sys.stderr,
        )
        return 1

    print("standing-rules gate: OK (instruction sources are not memory-gated)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
