# -*- coding: utf-8 -*-
"""Move runOneSubAgentInner out of ChatViewModelSubAgentExt (bounded KDoc lookback)."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelSubAgentExt.kt"
s = open(p, encoding="utf-8").read()
lines = s.splitlines(keepends=True)

def idx_of(sub, hint=0):
    for i in range(hint, len(lines)):
        if sub in lines[i]:
            return i
    raise SystemExit("not found: " + sub)

def kdoc_near(sig, max_back=6):
    i = sig
    steps = 0
    while i > 0 and steps < max_back:
        if lines[i - 1].strip().startswith("/**"):
            return i - 1
        if lines[i - 1].strip().endswith("*/"):
            j = i - 1
            while j > 0 and not lines[j].strip().startswith("/**"):
                j -= 1
            return j
        if lines[i - 1].strip() not in ("",) and not lines[i - 1].strip().startswith("*"):
            return sig
        i -= 1
        steps += 1
    return sig

sig = idx_of("private suspend fun ChatViewModel.runOneSubAgentInner(")
start = kdoc_near(sig*1) if False else kdoc_near(sig) if False else sig
# explicit: runOneSubAgentInner has no KDoc of its own
start = sig
tool_end_sig = idx_of("private fun isUpstreamTruncation(")
end = kdoc_near(tool_end_sig)

block = "".join(lines[start:end])
assert "runOneSubAgentInner" in block, "block wrong"
assert len(block.splitlines()) > 100, "block too small: %d" % len(block.splitlines())

s_after = s.replace("    private fun pickRetryEntry(", "    internal fun pickRetryEntry(")
new_block = block.replace(
    "private suspend fun ChatViewModel.runOneSubAgentInner(",
    "internal suspend fun ChatViewModel.runOneSubAgentInner(",
)
new_file = (
    "package com.openminis.app.ui.chat\n\n"
    "import com.openminis.app.data.model.AssistantBlock\n"
    "import com.openminis.app.data.model.ModelEntry\n"
    "import com.openminis.app.data.model.ToolExecutionResult\n"
    "import com.openminis.app.tools.SubAgentTokenBudget\n\n"
    "/** 单个子代理 lane 的执行主循环（拆分自 ChatViewModelSubAgentExt，架构行数上限）。 */\n\n"
    + new_block
)
open(r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelSubAgentRun.kt",
     "w", encoding="utf-8", newline="").write(new_file)

rest = s_after.splitlines(keepends=True)
en = None
for i in range(sig, len(rest)):
    if "private fun isUpstreamTruncation(" in rest[i]:
        en = i
        break
assert en is not None
while en > 0 and not rest[en].strip().startswith("/**"):
    en -= 1
if not rest[en].strip().startswith("/**"):
    en = None
    for i in range(sig, len(rest)):
        if "private fun isUpstreamTruncation(" in rest[i]:
            en = i
            break
s2 = "".join(rest[:start]) + "".join(rest[en:])
open(p, "w", encoding="utf-8", newline="").write(s2)
print("SubAgentExt now:", len(s2.splitlines()), "| run file:", len(new_file.splitlines()))
