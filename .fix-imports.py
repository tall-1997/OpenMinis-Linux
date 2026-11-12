# -*- coding: utf-8 -*-
"""Fix imports of split-out files by copying the import block of their source file."""
import re

base = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app"

pairs = [
    # (new file, source file)
    (base + "/ui/chat/ChatViewModelGroupChatSpeak.kt", base + "/ui/chat/ChatViewModelGroupChatExt.kt"),
    (base + "/ui/chat/ChatViewModelSubAgentRun.kt", base + "/ui/chat/ChatViewModelSubAgentExt.kt"),
    (base + "/ui/navigation/AppNavigationHosts.kt", base + "/ui/navigation/AppNavigation.kt"),
    (base + "/ui/settings/ProviderOAuthBlock.kt", base + "/ui/settings/ProviderDetailScreen.kt"),
]

def import_block(path):
    lines = open(path, encoding="utf-8").read().splitlines()
    imports = [l for l in lines if l.startswith("import ")]
    return imports

for new_path, src_path in pairs:
    text = open(new_path, encoding="utf-8").read()
    lines = text.splitlines()
    # drop existing import lines
    lines = [l for l in lines if not l.startswith("import ")]
    # find package line index
    pkg_idx = next(i for i, l in enumerate(lines) if l.startswith("package "))
    imports = import_block(src_path)
    # avoid importing the source file's own package symbols unnecessarily; keep all (extra = warnings only)
    block = [""] + imports + [""]
    out = lines[:pkg_idx + 1] + block + lines[pkg_idx + 1:]
    open(new_path, "w", encoding="utf-8", newline="").write("\n".join(out) + "\n")
    print(f"{new_path.split('/')[-1]}: imports={len(imports)}")

# ShellLineFeeder needs the sandbox.kernel TokenBucket
p = base + "/sandbox/ShellLineFeeder.kt"
s = open(p, encoding="utf-8").read()
if "import com.openminis.app.sandbox.kernel.TokenBucket" not in s:
    s = s.replace("package com.openminis.app.sandbox\n", "package com.openminis.app.sandbox\n\nimport com.openminis.app.sandbox.kernel.TokenBucket\n", 1)
open(p, "w", encoding="utf-8", newline="").write(s)
print("ShellLineFeeder: TokenBucket import added")
