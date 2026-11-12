# -*- coding: utf-8 -*-
"""Add English fallbacks for new keys to every locale that lacks them (CI translation gate)."""
import os
import re
import xml.etree.ElementTree as ET

ROOT = "D:/minis-worktrees/integration"
RES = ROOT + "/src/android/app/src/main/res"
DEFAULT_FILES = [
    "values/strings.xml", "values/strings_settings.xml", "values/strings_network.xml",
    "values/strings_mcp.xml", "values/strings_backup.xml", "values/strings_scheduled.xml",
]
NAME_RE = re.compile(r'<(?:string|plurals|string-array)\s+name="([^"]+)"')

# --- 1) collect default definitions (key -> raw xml text) from the default files ---
defaults = {}
for f in DEFAULT_FILES:
    path = RES + "/" + f
    if not os.path.exists(path):
        continue
    text = open(path, encoding="utf-8").read()
    # crude but adequate: split on resource tags, keeping the full element text
    for m in re.finditer(r'<(string|plurals|string-array)\s+name="([^"]+)"[^>]*?/>|'
                         r'<(string|plurals|string-array)\s+name="([^"]+)"[^>]*?>.*?</\1>',
                         text, re.S):
        raw = m.group(0)
        key = m.group(2) or m.group(4)
        if key and ('translatable="false"' not in raw.split(">", 1)[0]):
            defaults[key] = raw

print("default definitions:", len(defaults))

# --- 2) every locale missing these keys gets an English fallback line before </resources> ---
locales = sorted(
    d for d in os.listdir(RES)
    if d.startswith("values-") and d != "values-night" and os.path.isdir(RES + "/" + d)
)

for loc in locales:
    loc_dir = RES + "/" + loc
    present = set()
    for fn in os.listdir(loc_dir):
        if fn.endswith(".xml"):
            present |= set(NAME_RE.findall(open(loc_dir + "/" + fn, encoding="utf-8", errors="replace").read()))
    missing = sorted(k for k in defaults if k not in present)
    if not missing:
        continue
    target = loc_dir + "/strings.xml"
    text = open(target, encoding="utf-8").read() if os.path.exists(target) else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
    block = "\n    <!-- [T-untranslated] english fallback for keys added by PR #325; pending translation -->\n"
    for k in missing:
        raw = defaults[k].strip()
        # ensure it is a single-line <string ...>; multi-line elements are flattened
        raw = re.sub(r"\s*\n\s*", " ", raw)
        block += "    " + raw + "\n"
    if "</resources>" in text:
        text = text.replace("</resources>", block + "</resources>", 1)
    else:
        text += block + "</resources>\n"
    open(target, "w", encoding="utf-8", newline="").write(text)
    print(f"{loc}: +{len(missing)} english fallbacks")
print("done")
