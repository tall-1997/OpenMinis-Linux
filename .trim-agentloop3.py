# -*- coding: utf-8 -*-
"""Line-index based comment compaction + mojibake repair in AgentLoopExt (final 15 lines)."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelAgentLoopExt.kt"
lines = open(p, encoding="utf-8").read().splitlines(keepends=True)

out = []
i = 0
while i < len(lines):
    l = lines[i]
    if l.strip().startswith("// [T-truncated-tool-call-reject] finish_reason=length/max_tokens"):
        # replace the 4-line (possibly mojibake) block with one clean line
        out.append("        // [T-truncated-tool-call-reject] finish_reason=length/max_tokens 由 ToolCallPreflight 统一拒绝。\n")
        i += 4
        continue
    if l.strip().startswith("// Persist the assistant+tools turn (with full input JSON and thinking)."):
        out.append("        // 持久化本轮 assistant+tools，并回填 agentHistory 的 dbMessageId（compact 边界依赖）。\n")
        i += 3
        continue
    if l.strip() == "// Persist tool results as user-role message (mirrors iOS)":
        out.append("        // tool_result 作为 user 角色消息持久化（镜像 iOS）。\n")
        i += 1
        continue
    if l.strip() == "// Add tool results to history":
        out.append("        // 工具结果进入历史。\n")
        i += 1
        continue
    out.append(l)
    i += 1

s = "".join(out)
open(p, "w", encoding="utf-8", newline="").write(s)
print("compacted; splitlines now:", len(s.splitlines()))
