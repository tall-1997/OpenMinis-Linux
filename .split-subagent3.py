# -*- coding: utf-8 -*-
"""Move runOneSubAgentInner out of ChatViewModelSubAgentExt (exact signature boundaries)."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelSubAgentExt.kt"
lines = open(p, encoding="utf-8").read().splitlines(keepends=True)

def idx_of(sub, hint=0):
    for i in range(hint, len(lines)):
        if sub in lines[i]:
            return i
    raise SystemExit("not found: " + sub)

sig = idx_of("private suspend fun ChatViewModel.runOneSubAgentInner(")
end = idx_of("private fun isUpstreamTruncation(")
block = "".join(lines[sig:end])
assert "runOneSubAgentInner" in block and len(block.splitlines()) > 100

new_file = (
    "package com.openminis.app.ui.chat\n\n"
    "import com.openminis.app.data.model.AssistantBlock\n"
    "import com.openminis.app.data.model.ModelEntry\n"
    "import com.openminis.app.data.model.ToolExecutionResult\n"
    "import com.openminis.app.tools.SubAgentTokenBudget\n\n"
    "/** 单个子代理 lane 的执行主循环（拆分自 ChatViewModelSubAgentExt，架构行数上限）。 */\n\n"
    + block.replace(
        "private suspend fun ChatViewModel.runOneSubAgentInner(",
        "internal suspend fun ChatViewModel.runOneSubAgentInner(",
    )
)
open(r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelSubAgentRun.kt",
     "w", encoding="utf-8", newline="").write(new_file)

s2 = "".join(lines[:sig]) + "".join(lines[end:])
s2 = s2.replace("    private fun pickRetryEntry(", "    internal fun pickRetryEntry(")
open(p, "w", encoding="utf-8", newline="").write(s2)
print("SubAgentExt now:", len(s2.splitlines()), "| run file:", len(new_file.splitlines()))
