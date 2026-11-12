# -*- coding: utf-8 -*-
"""Move refreshHistoryEdges + rememberDigestLines out of ChatViewModel (line ceiling)."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"
lines = open(p, encoding="utf-8").read().splitlines(keepends=True)

def idx_of(sub, hint=0):
    for i in range(hint, len(lines)):
        if sub in lines[i]:
            return i
    raise SystemExit("not found: " + sub)

a = idx_of("internal suspend fun refreshHistoryEdges(")
b = idx_of("internal fun rememberDigestLines(")
c = idx_of("internal fun notePersistedUiRow(", b)
block1 = "".join(lines[a:b])
block2 = "".join(lines[b:c])
assert "seedCounters" in block1 and "retainNewest" in block2

new_file = (
    "package com.openminis.app.ui.chat\n\n"
    "import com.openminis.app.agent.HistoryDigest\n\n"
    "/** 历史窗口边界的 DB 权威刷新（拆分自 ChatViewModel，架构行数上限）。 */\n"
    + block1.replace("internal suspend fun refreshHistoryEdges(", "internal suspend fun ChatViewModel.refreshHistoryEdges(")
    + "\n"
    + block2.replace("internal fun rememberDigestLines(", "internal fun ChatViewModel.rememberDigestLines(")
)
open(r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelHistoryEdges.kt",
     "w", encoding="utf-8", newline="").write(new_file)

s = "".join(lines[:a]) + "".join(lines[c:])
open(p, "w", encoding="utf-8", newline="").write(s)
print("ChatViewModel now:", len(s.splitlines()), "| edges file:", len(new_file.splitlines()))
