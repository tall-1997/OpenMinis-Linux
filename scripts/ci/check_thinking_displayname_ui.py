#!/usr/bin/env python3
"""
[T-thinking-level-i18n] UI 层禁止消费 ThinkingLevel.displayName。

background: 数据层 displayName 是硬编码英文（serialisation/debug 用途），
四个 UI bug（多智能体设置页/Token Usage 面板/系统提示 toast）都源于
UI 直接消费它。localizedName() 是唯一合法的显示通道。

门禁形态：ratchet（白名单只紧不松），仿 check_cjk_literals.py。
规则：ui/ 层 Kotlin 文件禁止出现 `.displayName`（启发式：同文件 import
ThinkingLevel 且行内 .displayName，且行不含 allow-displayname 注释）。
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ANDROID = (ROOT / "src" / "android").resolve()
BASELINE = ROOT / "scripts" / "ci" / "thinking-displayname-baseline.json"
UI_ROOTS = ("ui", "automation/ui", "quick", "cards")

IMPORT_RE = re.compile(r"^\s*import\s+[\w.]*ThinkingLevel\b", re.M)
VIOLATION_RE = re.compile(r"\.\s*displayName\b")
# The bare .displayName regex alone would flag legitimate
# model.displayName / entry.displayName consumers inside the same file.
# A violation needs the line to be ABOUT a thinking level.
THINKING_HINT_RE = re.compile(r"thinking|level", re.I)


def is_ui_path(rel: pathlib.PurePath) -> bool:
    parts = rel.parts
    try:
        i = parts.index("openminis")
    except ValueError:
        return False
    for root in UI_ROOTS:
        sub = tuple(root.split("/"))
        if parts[i + 2 : i + 2 + len(sub)] == sub:
            return True
    return False


def scan():
    violations = {}
    for full in sorted(ANDROID.rglob("*.kt")):
        if any(part in ("build", ".gradle") for part in full.parts):
            continue
        rel = full.relative_to(ANDROID)
        if not is_ui_path(rel):
            continue
        try:
            text = full.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        if not IMPORT_RE.search(text):
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            if (
                VIOLATION_RE.search(line)
                and THINKING_HINT_RE.search(line)
                and "allow-displayname" not in line
            ):
                violations.setdefault(str(rel), []).append(lineno)
    return violations


def main():
    violations = scan()
    baseline = {}
    if BASELINE.exists():
        baseline = json.loads(BASELINE.read_text(encoding="utf-8"))

    new_violations = {}
    for path, lines in violations.items():
        allowed = set(baseline.get(path, []))
        fresh = [l for l in lines if l not in allowed]
        if fresh:
            new_violations[path] = fresh

    if new_violations:
        print(f"ThinkingLevel.displayName in UI layer: {len(new_violations)} file(s) with NEW violations")
        for path, lines in sorted(new_violations.items()):
            print(f"  - {path}: lines {lines}")
        print("\nUI must use ThinkingLevel.localizedName(context) — displayName is")
        print("hard-coded English (serialisation/debug only). If a debug surface")
        print("genuinely needs it, add 'allow-displayname' to the line, or")
        print("(never) extend the baseline.")
        return 1

    if baseline:
        cleaned = {p: l for p, l in violations.items() if l}
        if cleaned != baseline:
            BASELINE.write_text(json.dumps(cleaned, indent=2, sort_keys=True), encoding="utf-8")
            print(f"baseline tightened: {len(cleaned)} file(s)")
    print("ThinkingLevel displayName UI gate: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
