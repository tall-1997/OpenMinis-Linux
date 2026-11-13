#!/usr/bin/env python3
"""Delete the two now-unused context-sheet string keys from every locale.

The standalone ContextUsageSheet was merged into TokenUsageSheet's Context
section, so `chat_menu_context_usage` (the second menu entry) and
`context_usage_sheet_title` (the standalone sheet's title) have no remaining
callers. Keys still referenced by the merged section (context_block_*,
context_usage_note_estimate) are kept untouched.
"""
import re
import sys
from pathlib import Path

KEYS = ("chat_menu_context_usage", "context_usage_sheet_title")
RES = Path("src/android/app/src/main/res")

pattern = re.compile(
    r'^[ \t]*<string name="(?:' + "|".join(KEYS) + r')">.*</string>\n',
    re.MULTILINE,
)

removed_total = 0
for strings in sorted(RES.glob("values*/strings.xml")):
    text = strings.read_text(encoding="utf-8")
    new_text, n = pattern.subn("", text)
    if n:
        strings.write_text(new_text, encoding="utf-8")
        removed_total += n
        print(f"{strings.parent.name}: removed {n}")
    else:
        print(f"{strings.parent.name}: no match (ok)")

# Verify no references remain anywhere in code or resources.
leftover = []
for kt in (Path("src/android") / "app/src/main/java").rglob("*.kt"):
    if any(k in kt.read_text(encoding="utf-8", errors="ignore") for k in KEYS):
        leftover.append(str(kt))
for xml in RES.glob("values*/strings.xml"):
    if any(f'name="{k}"' in xml.read_text(encoding="utf-8") for k in KEYS):
        leftover.append(str(xml))

if leftover:
    print("LEFTOVER REFERENCES:", leftover)
    sys.exit(1)
print(f"OK: {removed_total} string lines removed, zero references remain")
