# -*- coding: utf-8 -*-
"""Replace PersistentShell's private CommandCallback with ShellCommandCallback."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/sandbox/PersistentShell.kt"
s = open(p, encoding="utf-8").read()

start = s.index("    private class CommandCallback(")
end = s.index("    /** 行回调的限速/CR 处理")
old = s[start:end]
assert "fun appendOutput" in old
s = s[:start] + s[end:]
s = s.replace("private var pendingCallback: CommandCallback? = null", "private var pendingCallback: ShellCommandCallback? = null")
s = s.replace("val cb = CommandCallback(", "val cb = ShellCommandCallback(")
open(p, "w", encoding="utf-8", newline="").write(s)
print("CommandCallback extracted, lines now:", s.count("\n") + 1)
