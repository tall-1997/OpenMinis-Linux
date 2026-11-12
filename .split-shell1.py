# -*- coding: utf-8 -*-
"""Delegate offloadExitPrelude body-building to OffloadShellPrelude."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/sandbox/PersistentShell.kt"
s = open(p, encoding="utf-8").read()

start = s.index('        val signature = names.joinToString(",")')
end = s.index('        val file = File(RootfsManager.getInstance(context).rootfsDir, "etc/minis-offload-exit.sh")')
old = s[start:end]
assert 'appendLine("minis_offload_wrap() {")' in old, "unexpected block layout"
replacement = (
    '        val signature = names.joinToString(",")\n'
    '        if (signature == sourcedOffloadSignature) return ""  // already in this shell\n'
    '        val body = OffloadShellPrelude.buildScript(names)\n'
)
s = s[:start] + replacement + s[end:]
open(p, "w", encoding="utf-8", newline="").write(s)
print("delegated, lines now:", s.count("\n") + 1)
