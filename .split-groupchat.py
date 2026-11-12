# -*- coding: utf-8 -*-
"""Move speakModel + toolStatus out of ChatViewModelGroupChatExt into a new file (correct span removal)."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelGroupChatExt.kt"
s = open(p, encoding="utf-8").read()
lines = s.splitlines(keepends=True)

def find(pred, start=0):
    for i in range(start, len(lines)):
        if pred(lines[i]):
            return i
    raise SystemExit("not found")

def kdoc_start(idx):
    i = idx
    while i > 0 and not lines[i].strip().startswith("/**"):
        i -= 1
    return i

sig = find(lambda l: "internal suspend fun ChatViewModel.speakModel(" in l)
start = kdoc_start(sig)
end = kdoc_start(find(lambda l: "private suspend fun ChatViewModel.clearGroupQueue(" in l))
speak_block = "".join(lines[start:end])

ts = find(lambda l: "private fun toolStatus(" in l)
ts_start = kdoc_start(ts)
ts_end = kdoc_start(find(lambda l: "private suspend fun ChatViewModel.showGroupStatus(" in l))
tool_block = "".join(lines[ts_start:ts_end])
assert "ToolBlockStatus" in tool_block

tool_block = tool_block.replace("private fun toolStatus(", "internal fun toolStatus(")

# remove spans from the back
for a, b in sorted([(start, end), (ts_start, ts_end)], reverse=True):
    lines = lines[:a] + lines[b:]
s2 = "".join(lines)

new_file = (
    "package com.openminis.app.ui.chat\n\n"
    "import com.openminis.app.data.model.AssistantBlock\n"
    "import com.openminis.app.data.model.ToolBlockStatus\n"
    "import com.openminis.app.data.model.LLMMessage\n"
    "import com.openminis.app.data.model.LLMStreamChunk\n\n"
    "/** 群聊发言人主循环 + 工具状态卡片（拆分自 ChatViewModelGroupChatExt，架构行数上限）。 */\n\n"
    + speak_block + "\n" + tool_block
)
open(r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelGroupChatSpeak.kt",
     "w", encoding="utf-8", newline="").write(new_file)
open(p, "w", encoding="utf-8", newline="").write(s2)
print("GroupChatExt now:", len(s2.splitlines()), "| speak file:", len(new_file.splitlines()))
