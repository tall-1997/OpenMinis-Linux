# -*- coding: utf-8 -*-
"""Add English fallbacks for new keys to every locale lacking them (ElementTree based)."""
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

# --- collect default string definitions (name -> text) ---
defaults = {}
for f in DEFAULT_FILES:
    path = RES + "/" + f
    if not os.path.exists(path):
        continue
    tree = ET.parse(path)
    for el in tree.getroot():
        if el.tag not in ("string", "plurals", "string-array"):
            continue
        name = el.get("name")
        if not name or el.get("translatable") == "false":
            continue
        text = "".join(el.itertext()).strip()
        defaults[name] = text

print("default definitions:", len(defaults))

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
            present |= set(NAME_RE.findall(open(loc_dir + "/" + fn, encoding="utf-8", errors="replace").read()))
    missing = sorted(k for k in defaults if k not in present)
    if not missing:
        continue
    target = loc_dir + "/strings.xml"
    text = open(target, encoding="utf-8").read() if os.path.exists(target) else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
    block = "\n    <!-- [T-untranslated] English fallback for keys added by PR #325; pending translation -->\n"
    for k in missing:
        val = defaults[k]
        if re.search(r'<(?:string|plurals|string-array)\s+name="' + re.escape(k) + r'"', block):
            continue
        block += f'    <string name="{k}">{esc(val)}</string>\n'
    if "</resources>" in text:
        text = text.replace("</resources>", block + "</resources>", 1)
    else:
        text += block + "</resources>\n"
    open(target, "w", encoding="utf-8", newline="").write(text)
    print(f"{loc}: +{len(missing)} english fallbacks")
print("done")
