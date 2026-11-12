# -*- coding: utf-8 -*-
"""Move the whole offload-prelude ensure/write block into OffloadShellPrelude."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/sandbox/PersistentShell.kt"
s = open(p, encoding="utf-8").read()

start = s.index("    private fun offloadExitPrelude(): String {")
end = s.index("    suspend fun executeCommand(")
old = s[start:end]
assert "OffloadShellPrelude.buildScript(names)" in old, "unexpected block"

replacement = (
    "    private fun offloadExitPrelude(): String {\n"
    "        val outcome = OffloadShellPrelude.ensure(\n"
    "            rootfsDir = RootfsManager.getInstance(context).rootfsDir,\n"
    "            lastSignature = sourcedOffloadSignature,\n"
    "        )\n"
    "        if (outcome == null) {\n"
    "            // 没有已注册 handler：清空签名，下次有 handler 时重新 source。\n"
    "            if (NativeOffloadServer.registeredHandlers.isEmpty()) sourcedOffloadSignature = null\n"
    "            return \"\"\n"
    "        }\n"
    "        sourcedOffloadSignature = outcome.second\n"
    "        return outcome.first\n"
    "    }\n"
    "\n"
)
s = s[:start] + replacement + s[end:]
open(p, "w", encoding="utf-8", newline="").write(s)
print("offload block extracted, splitlines now:", len(s.splitlines()))
