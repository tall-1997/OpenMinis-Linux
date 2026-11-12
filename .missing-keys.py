# -*- coding: utf-8 -*-
"""List new string keys vs base commit and which locales lack them."""
import re
import subprocess
import xml.etree.ElementTree as ET

BASE = "05179c3be43b6f75f5b9decff6e704751389f09e"
RES = "src/android/app/src/main/res"
DEFAULT_FILES = [
    "values/strings.xml", "values/strings_settings.xml", "values/strings_network.xml",
    "values/strings_mcp.xml", "values/strings_backup.xml", "values/strings_scheduled.xml",
]

NAME_RE = re.compile(r'<(?:string|plurals|string-array)\s+name="([^"]+)"')


def keys_at(ref, path):
    try:
        text = subprocess.run(
            ["git", "show", f"{ref}:{path}"], capture_output=True, text=True,
            encoding="utf-8", errors="replace", cwd="D:/minis-worktrees/integration",
        ).stdout
    except Exception:
        return set()
    return set(NAME_RE.findall(text))


def keys_now(path):
    try:
        return set(NAME_RE.findall(open("D:/minis-worktrees/integration/" + path, encoding="utf-8").read()))
    except FileNotFoundError:
        return set()


all_base, all_now = set(), set()
for f in DEFAULT_FILES:
    all_base |= keys_at(BASE, RES + "/" + f)
    all_now |= keys_now(RES + "/" + f)

new_keys = sorted(all_now - all_base)
print(f"new default keys: {len(new_keys)}")
for k in new_keys:
    print("  ", k)

# locale check: which locales miss which new keys
import os
locales = sorted(
    d for d in os.listdir("D:/minis-worktrees/integration/" + RES)
    if d.startswith("values-") and os.path.isdir("D:/minis-worktrees/integration/" + RES + "/" + d)
)
print("\nlocales:", locales)
missing = {}
for loc in locales:
    present = set()
    p = "D:/minis-worktrees/integration/" + RES + "/" + loc
    for fn in os.listdir(p):
        if fn.endswith(".xml"):
            present |= set(NAME_RE.findall(open(p + "/" + fn, encoding="utf-8", errors="replace").read()))
    miss = [k for k in new_keys if k not in present]
    if miss:
        missing[loc] = miss
        print(f"{loc}: missing {len(miss)}")
print("\nlocales missing keys:", len(missing))
