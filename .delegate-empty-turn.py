# -*- coding: utf-8 -*-
"""Replace the local injectEmptyTurnReminder body with a thin delegate to EmptyTurnRecoveryInject."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelAgentLoopExt.kt"
s = open(p, encoding="utf-8").read()

start_marker = "                fun injectEmptyTurnReminder(): Boolean = when (plan) {"
end_marker = "\n                }\n                // [port-upstream-1.16][T-b13-empty-fallback] In a model group"
start = s.index(start_marker)
end = s.index(end_marker)
old = s[start:end]
assert "NudgeExistingPart" in old and "AppendStandalone" in old, "unexpected body"

replacement = (
    "                fun injectEmptyTurnReminder(): Boolean {\n"
    "                    val (injected, didInject) = injectEmptyTurnReminderImpl(plan, agentHistory)\n"
    "                    if (didInject) didInjectEmptyToolReminder = true\n"
    "                    return injected\n"
    "                }"
)
s = s[:start] + replacement + s[end + 1:]
open(p, "w", encoding="utf-8", newline="").write(s)
print("injectEmptyTurnReminder delegated; splitlines now:", len(s.splitlines()))
