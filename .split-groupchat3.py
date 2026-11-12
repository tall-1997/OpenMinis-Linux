# -*- coding: utf-8 -*-
"""Move speakModel + toolStatus out of ChatViewModelGroupChatExt (bounded KDoc lookback)."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelGroupChatExt.kt"
s = open(p, encoding="utf-8").read()
lines = s.splitlines(keepends=True)

def idx_of(sub, hint=0):
    for i in range(hint, len(lines)):
        if sub in lines[i]:
            return i
    raise SystemExit("not found: " + sub)

def kdoc_within(sig, max_back=6):
    i = sig
    steps = 0
    while i > 0 and steps < max_back:
        if lines[i - 1].strip().startswith("/**"):
            return i - 1 if False else i
        if lines[i - 1].strip().endswith("*/"):
            j = i - 1
            while j > 0 and not lines[j].strip().startswith("/**"):
                j -= 1
            return j
        i -= 1
        steps += 1
    return sig

speak_sig = idx_of("internal suspend fun ChatViewModel.speakModel(")
start = kdoc_within(speak_sig)

clear_sig = idx_of("private suspend fun ChatViewModel.clearGroupQueue(")
end = kdoc_within(clear_sig)

tool_sig = idx_of("private fun toolStatus(")
tool_end = idx_of("private suspend fun ChatViewModel.showGroupStatus(")

speak_block = "".join(lines[start:end])
tool_block = "".join(lines[tool_sig:tool_end])
assert "ToolBlockStatus" in tool_block
assert "speakModel" in speak_block
assert start < end < tool_sig < tool_end

tool_block = tool_block.replace("private fun toolStatus(", "internal fun toolStatus(")

for a, b in sorted([(start, end), (tool_sig, tool_end)], reverse=True):
    lines = lines[:a] + lines[b:]
s2 = "".join(lines)

new_file = (
    "package com.openminis.app.ui.chat\n\n"
    "import com.openminis.app.data.model.AssistantBlock\n"
    "import com.openminis.app.data.model.ToolBlockStatus\n\n"
    "/** 群聊发言人主循环 + 工具状态卡片（拆分自 ChatViewModelGroupChatExt，架构行数上限）。 */\n\n"
    + speak_block + "\n" + tool_block
)
open(r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelGroupChatSpeak.kt",
     "w", encoding="utf-8", newline="").write(new_file)
open(p, "w", encoding="utf-8", newline="").write(s2)
print("GroupChatExt now:", len(s2.splitlines()), "| speak file:", len(new_file.splitlines()))
