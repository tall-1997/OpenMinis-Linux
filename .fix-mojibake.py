# -*- coding: utf-8 -*-
"""Fix a mojibake comment line introduced by an earlier inline-python edit."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/sandbox/PersistentShell.kt"
s = open(p, encoding="utf-8").read()
lines = s.splitlines(keepends=True)
for i, ln in enumerate(lines):
    if "private val lineFeeder = ShellLineFeeder()" in ln:
        j = i - 1
        if j >= 0 and lines[j].strip().startswith("/**"):
            lines[j] = "    /** 行回调的限速/CR 处理（拆分自本文件，见 [ShellLineFeeder]）。 */\n"
        break
open(p, "w", encoding="utf-8", newline="").write("".join(lines))
print("fixed comment before lineFeeder:", "行回调" in open(p, encoding="utf-8").read())
