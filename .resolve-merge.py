# -*- coding: utf-8 -*-
"""Resolve merge conflicts: strings.xml union (dedupe keys), Kotlin/JSON take origin/main side."""
import re
import subprocess

BASE = r"D:\minis-worktrees\integration"

conflicts = subprocess.run(
    ["git", "-C", BASE, "diff", "--name-only", "--diff-filter=U"],
    capture_output=True, text=True).stdout.splitlines()

BLOCK = re.compile(r"<<<<<<< HEAD\n(.*?)=======\n(.*?)>>>>>>> origin/main\n", re.S)
KEY = re.compile(r'<string name="([^"]+)"')


def union(ours, theirs):
    lines, seen = [], set()
    for line in (ours + theirs).splitlines():
        m = KEY.search(line)
        if m:
            if m.group(1) in seen:
                continue
            seen.add(m.group(1))
        lines.append(line)
    return "\n".join(lines) + "\n"


def theirs(ours, theirs_text):
    return theirs_text


resolved_files = []
for rel in conflicts:
    p = BASE + "/" + rel
    text = open(p, encoding="utf-8").read()
    if not BLOCK.search(text):
        print("NO BLOCK (skip):", rel)
        continue
    if rel.endswith("strings.xml"):
        resolved = BLOCK.sub(lambda m: union(m.group(1), m.group(2)), text)
        mode = "union"
    else:
        resolved = BLOCK.sub(lambda m: theirs(m.group(1), m.group(2)), text)
        mode = "theirs"
    open(p, "w", encoding="utf-8", newline="").write(resolved)
    resolved_files.append(rel)
    print(f"{mode}-resolved: {rel} | remaining markers: {resolved.count('<<<<<<<')}")

print("done:", len(resolved_files), "of", len(conflicts))
