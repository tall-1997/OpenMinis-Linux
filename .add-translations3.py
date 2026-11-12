# -*- coding: utf-8 -*-
"""Add English fallbacks ONLY for keys newly added by this PR (base->head diff), per locale."""
import os
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = "D:/minis-worktrees/integration"
RES = ROOT + "/src/android/app/src/main/res"
BASE = "05179c3be43b6f75f5b9decff6e704751389f09e"
DEFAULT_FILES = [
    "values/strings.xml", "values/strings_settings.xml", "values/strings_network.xml",
    "values/strings_mcp.xml", "values/strings_backup.xml", "values/strings_scheduled.xml",
]
NAME_RE = re.compile(r'<(?:string|plurals|string-array)\s+name="([^"]+)"')


def keys_of_text(text):
    return set(NAME_RE.findall(text))


def keys_at(ref, path):
    out = subprocess.run(
        ["git", "show", f"{ref}:{path}"], capture_output=True, text=True,
        encoding="utf-8", errors="replace", cwd=ROOT,
    ).stdout
    return keys_of_text(out)


def keys_now(path):
    try:
        return keys_of_text(open(ROOT + "/" + path, encoding="utf-8").read())
    except FileNotFoundError:
        return set()


base_keys, now_keys = set(), set()
for f in DEFAULT_FILES:
    base_keys |= keys_at(BASE, RES + "/" + f)
    now_keys |= keys_now(RES + "/" + f)
new_keys = sorted(now_keys - base_keys)
print("new keys to backfill:", len(new_keys))

# definitions of the new keys, from the CURRENT default files
defined = {}
for f in DEFAULT_FILES:
    path = RES + "/" + f
    if not os.path.exists(path):
        continue
    for el in ET.parse(path).getroot():
        if el.tag not in ("string", "plurals", "string-array"):
            continue
        name = el.get("name")
        if name in new_keys and name not in defined:
            defined[name] = "".join(el.itertext()).strip()


def esc(t: str) -> str:
    return (t.replace("&", "&amp;")
             .replace("<", "&lt;")
             .replace(">", "&gt;")
             .replace("\n", "\\n")
             .replace("'", "\\'"))


locales = sorted(
    d for d in os.listdir(RES)
    if d.startswith("values-") and d != "values-night" and os.path.isdir(RES + "/" + d)
)

for loc in locales:
    loc_dir = RES + "/" + loc
    present = set()
    for fn in os.listdir(loc_dir):
        if fn.endswith(".xml"):
            present |= keys_of_text(open(loc_dir + "/" + fn, encoding="utf-8", errors="replace").read())
    missing = [k for k in new_keys if k not in present and k in defined]
    if not missing:
        continue
    target = loc_dir + "/strings.xml"
    text = open(target, encoding="utf-8").read() if os.path.exists(target) else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
    block = "\n    <!-- [T-untranslated] English fallback for keys added by PR #325; pending translation -->\n"
    for k in missing:
        block += f'    <string name="{k}">{esc(defined[k])}</string>\n'
    if "</resources>" in text:
        text = text.replace("</resources>", block + "</resources>", 1)
    else:
        text += block + "</resources>\n"
    open(target, "w", encoding="utf-8", newline="").write(text)
    print(f"{loc}: +{len(missing)} fallbacks")
print("done")
