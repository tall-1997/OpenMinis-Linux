# -*- coding: utf-8 -*-
"""Move speakModel (with its KDoc) + toolStatus (exact line range) out of ChatViewModelGroupChatExt."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelGroupChatExt.kt"
s = open(p, encoding="utf-8").read()
lines = s.splitlines(keepends=True)

def idx_of(sub, hint=0):
    for i in range(hint, len(lines)):
        if sub in lines[i]:
            return i
    raise SystemExit("not found: " + sub)

speak_sig = idx_of("internal suspend fun ChatViewModel.speakModel(")
start = speak_sig
while start > 0 and not lines[start].strip().startswith("/**"):
    start -= 1
clear_sig = idx_of("private suspend fun ChatViewModel.clearGroupQueue(")
end = clear_sig
while end > 0 and not lines[end].strip().startswith("/**"):
    end -= 1

tool_sig = idx_of("private fun toolStatus(")
# toolStatus has no KDoc of its own: previous lines are the tail of thinkingStatus.
tool_start = tool_sig
tool_end = idx_of("private suspend fun ChatViewModel.showGroupStatus(")

speak_block = "".join(lines[start:end])
tool_block = "".join(lines[tool_start:tool_end])
assert "ToolBlockStatus" in tool_block, tool_block[:200]
assert "speakModel" in speak_block

tool_block = tool_block.replace("private fun toolStatus(", "internal fun toolStatus(")

for a, b in sorted([(start, end), (tool_start, tool_end)], reverse=True):
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
print("sanity: speakModel in new file:", "fun ChatViewModel.speakModel" in new_file,
      "| toolStatus kept:", "fun toolStatus(" in s2)
