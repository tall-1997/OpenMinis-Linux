# -*- coding: utf-8 -*-
"""Move runOneSubAgentInner out of ChatViewModelSubAgentExt into ChatViewModelSubAgentRun.kt."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelSubAgentExt.kt"
s = open(p, encoding="utf-8").read()
lines = s.splitlines(keepends=True)

def idx_of(sub, hint=0):
    for i in range(hint, len(lines)):
        if sub in lines[i]:
            return i
    raise SystemExit("not found: " + sub)

sig = idx_of("private suspend fun ChatViewModel.runOneSubAgentInner(")
start = sig
while start > 0 and not lines[start - 1].strip().startswith("/**"):
    if lines[start - 1].strip() == "" or lines[start - 1].strip().startswith("*"):
        start -= 1
        continue
    break
if not lines[start].strip().startswith("/**"):
    start = sig

end = idx_of("private fun isUpstreamTruncation(")
while end > 0 and not lines[end].strip().startswith("/**"):
    end -= 1
if not lines[end].strip().startswith("/**"):
    end = idx_of("private fun isUpstreamTruncation(")

block = "".join(lines[start:end])
assert "runOneSubAgentInner" in block and len(block.splitlines()) > 100

# out-of-file visibility for the rotated-retry helper it calls
s_after = s
s_after = s_after.replace("    private fun pickRetryEntry(", "    internal fun pickRetryEntry(")

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
# recompute span in the mutated text (pickRetryEntry change may shift chars, not lines)
st = sig
en = None
for i in range(sig, len(rest)):
    if "private fun isUpstreamTruncation(" in rest[i]:
        en = i
        break
assert en is not None
while en > 0 and not rest[en].strip().startswith("/**"):
    en -= 1
s2 = "".join(rest[:start]) + "".join(rest[en:])
open(p, "w", encoding="utf-8", newline="").write(s2)
print("SubAgentExt now:", len(s2.splitlines()), "| run file:", len(new_file.splitlines()))
