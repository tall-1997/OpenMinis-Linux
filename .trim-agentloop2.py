# -*- coding: utf-8 -*-
"""Final AgentLoopExt trims: persist backfill delegate, mojibake comment fix, comment compaction."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelAgentLoopExt.kt"
s = open(p, encoding="utf-8").read()

# 1) persist + backfill -> top-level helper (new file written by caller below)
old_persist = (
    "        val assistantDbId = persistAssistantTurnForRun(\n"
    "            checkNotNull(activeRun) { \"Agent loop has no persistence owner\" }, turnParts, lastUsage, turnReasoningContent, blockMeta, accumulatedText,\n"
    "        )\n"
    "        if (activeRun?.isStopped == true) { loopExitedNormally = true; break }\n"
    "        if (assistantDbId != null) {\n"
    "            val lastIdx = agentHistory.indexOfLast { it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null }\n"
    "            if (lastIdx >= 0) {\n"
    "                agentHistory[lastIdx] = agentHistory[lastIdx].copy(dbMessageId = assistantDbId)\n"
    "            }\n"
    "        }\n"
)
new_persist = (
    "        val assistantDbId = persistAssistantTurnForRunAndBackfill(\n"
    "            activeRun, turnParts, blockMeta, lastUsage, turnReasoningContent, accumulatedText, agentHistory,\n"
    "        )\n"
    "        if (activeRun?.isStopped == true) { loopExitedNormally = true; break }\n"
)
assert old_persist in s, "persist block not found"
s = s.replace(old_persist, new_persist)

# 2) compress surrounding comments
pairs = [
    (
        "        // Persist the assistant+tools turn (with full input JSON and thinking).\n"
        "        // Capture the persisted DB id so we can back-fill agentHistory's last\n"
        "        // assistant entry \ufffd\ufffd compact-marker boundary resolution depends on it.\n",
        "        // 持久化本轮 assistant+tools，并回填 agentHistory 的 dbMessageId（compact 边界依赖）。\n",
    ),
    (
        "        // [T-truncated-tool-call-reject] finish_reason=length/max_tokens \ufffd\ufffd\n"
        "        // \ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd \ufffd\ufffd \ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\n"
        "        // ToolCallPreflight\ufffd\ufffd turnFinishReason \ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\n"
        "        // \ufffd\ufffd\ufffd\ufffd\ufffd\ufffd finish reason \ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\ufffd\n",
        "        // [T-truncated-tool-call-reject] finish_reason=length/max_tokens 由 ToolCallPreflight\n"
        "        // 按 turnFinishReason 对三个执行入口统一拒绝，不再各写各的。\n",
    ),
]
for old, new in pairs:
    if old not in s:
        print("WARN comment block not found:", old.splitlines()[0][:60])
        continue
    s = s.replace(old, new)

open(p, "w", encoding="utf-8", newline="").write(s)
print("trimmed; splitlines now:", len(s.splitlines()))
