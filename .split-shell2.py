# -*- coding: utf-8 -*-
"""Replace PersistentShell's inline output-head/tail block with ShellDeathTail delegation."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/sandbox/PersistentShell.kt"
s = open(p, encoding="utf-8").read()

start_marker = "    /**\n     * [T-android-shell-death-diagnosability] Captured shell output for"
end_marker = "    /** Exit code of the dead shell process, when known. */"
start = s.index(start_marker)
end = s.index(end_marker)
old = s[start:end]
assert "private fun appendTail" in old and "fun deathTail" in old, "unexpected block"

replacement = (
    "    /** [T-android-shell-death-diagnosability] premature-death capture（拆分：见 [ShellDeathTail]）。 */\n"
    "    private val deathTailStore = ShellDeathTail()\n"
    "\n"
    "    private fun appendTail(text: String) = deathTailStore.append(text)\n"
    "\n"
    "    fun deathTail(): String = deathTailStore.capture()\n"
    "\n"
)
s = s[:start] + replacement + s[end:]

# constants are now owned by ShellDeathTail
s = s.replace("    private const val OUTPUT_HEAD_MAX = 1024\n    private const val OUTPUT_TAIL_MAX = 2048\n", "")
open(p, "w", encoding="utf-8", newline="").write(s)
print("deathTail delegated, lines now:", s.count("\n") + 1)
