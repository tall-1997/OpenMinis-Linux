#!/usr/bin/env python3
"""CJK hardcoded-string ratchet for app sources.

The l10n gate (`check_new_untranslated.py`) only covers keys that were added
to `strings.xml`; it cannot see a Chinese literal typed directly into Kotlin.
This gate counts Chinese characters inside Kotlin string literals under
`src/android/app/src/main/java` (the whole app module — UI and non-UI alike)
and freezes the current debt in a baseline.

Scope note: this used to watch only `com/openminis/app/ui/`, which let
non-UI layers hoard Chinese copy (e.g. tools/ prompts whose own KDoc said
the file sits outside the CJK gate). The gate now covers every app source,
so that escape hatch is closed.

Rules:
  * a file absent from the baseline may not contain CJK literals;
  * an existing file may not grow its count;
  * a decrease must be recorded with `--update-baseline` (the ceiling only
    tightens, mirroring the architecture policy).

Comments and char literals are ignored; line comments, block comments and
triple-quoted strings are handled so a Chinese explanation in KDoc does not
trip the gate. Files that are not valid UTF-8 are read with replacement
characters (matching the architecture policy) instead of crashing the scan.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCAN_ROOT = ROOT / "src" / "android" / "app" / "src" / "main" / "java"
BASELINE = Path(__file__).with_name("cjk-baseline.json")
CJK = re.compile(r"[\u4e00-\u9fff]")


def _string_literal(text: str, start: int, quote: str) -> tuple[str, int]:
    """Return (content, index after closing delimiter) for one Kotlin string."""
    i = start + len(quote)
    out: list[str] = []
    while i < len(text):
        if text.startswith(quote, i):
            return "".join(out), i + len(quote)
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            out.append(text[i:i + 2])
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out), i


def count_cjk_literals(path: Path) -> int:
    text = path.read_text(encoding="utf-8", errors="replace")
    i = 0
    hits = 0
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            nl = text.find("\n", i)
            i = n if nl < 0 else nl + 1
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        if text.startswith('"""', i):
            content, i = _string_literal(text, i, '"""')
            if CJK.search(content):
                hits += 1
            continue
        if text[i] == '"':
            content, i = _string_literal(text, i, '"')
            if CJK.search(content):
                hits += 1
            continue
        i += 1
    return hits


def scan() -> dict[str, int]:
    counts: dict[str, int] = {}
    if not SCAN_ROOT.is_dir():
        return counts
    for path in sorted(SCAN_ROOT.rglob("*.kt")):
        hits = count_cjk_literals(path)
        if hits:
            counts[path.relative_to(ROOT).as_posix()] = hits
    return counts


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--update-baseline", action="store_true")
    args = parser.parse_args()

    actual = scan()
    if args.update_baseline:
        data: dict = {}
        if BASELINE.exists():
            try:
                data = json.loads(BASELINE.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                data = {}
        data["files"] = actual  # metadata keys (e.g. "comment") are preserved
        BASELINE.write_text(
            json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(f"cjk ratchet baseline updated: {len(actual)} files, {sum(actual.values())} literals")
        return 0

    baseline = json.loads(BASELINE.read_text(encoding="utf-8"))["files"]
    violations: list[str] = []
    for rel, hits in actual.items():
        allowed = baseline.get(rel, 0)
        if hits > allowed:
            violations.append(f"{rel}: {hits} hardcoded CJK literals > baseline {allowed}")
    for rel, allowed in baseline.items():
        if actual.get(rel, 0) < allowed:
            violations.append(
                f"{rel}: shrank to {actual.get(rel, 0)} but baseline is {allowed}; "
                "run --update-baseline"
            )
    if violations:
        print("CJK hardcoded-string ratchet failed:", file=sys.stderr)
        for v in violations:
            print(f"  - {v}", file=sys.stderr)
        return 1
    print(
        "CJK hardcoded-string ratchet: OK "
        f"({len(actual)} files, {sum(actual.values())} literals, "
        f"{len(baseline)} baselined)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
