# -*- coding: utf-8 -*-
"""Move bind-mount diagnostics accessors out of PersistentShell."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/sandbox/PersistentShell.kt"
s = open(p, encoding="utf-8").read()

old = (
    '    /** [diag] Read back the mount this shell was started with (frozen at boot). */\n'
    '    fun debugBindMount(linuxPath: String): String? = sessionBindMounts[linuxPath]\n'
    '\n'
    '    /** Copy of the binds this process was started with. */\n'
    '    fun bindSnapshot(): Map<String, String> = sessionBindMounts.toMap()\n'
    '\n'
)
assert old in s, "bind-mount accessors not found"
s = s.replace(old, "")
s = s.replace(
    "    private val sessionBindMounts: Map<String, String>,  // linuxPath -> hostPath",
    "    internal val sessionBindMounts: Map<String, String>,  // linuxPath -> hostPath",
)
open(p, "w", encoding="utf-8", newline="").write(s)
print("bind accessors moved, splitlines now:", len(s.splitlines()))
