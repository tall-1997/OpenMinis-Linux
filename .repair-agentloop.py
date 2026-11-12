# -*- coding: utf-8 -*-
"""Repair the broken live-preview extraction region and the removed throttle helper call."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelAgentLoopExt.kt"
s = open(p, encoding="utf-8").read()

pairs = [
    # 1) restore the truncated comment and drop the orphan brace left by the bad span
    (
        "        // [T-android-session-last-message-live-tool-call] Push a live\n"
        "        // preview to the session list NOW, before the (possibly long-\n"
        "        pushLiveSessionPreview(\n"
        "            allToolBlocks = allToolBlocks,\n"
        "            turnStartBlockIndex = turnStartBlockIndex,\n"
        "            toolInputMap = toolInputMap,\n"
        "        )\n"
        "        }\n",
        "        // [T-android-session-last-message-live-tool-call] Push a live preview to the\n"
        "        // session list NOW, before the (possibly long-running) tool dispatch; the\n"
        "        // list row otherwise lags one turn behind.\n"
        "        pushLiveSessionPreview(\n"
        "            allToolBlocks = allToolBlocks,\n"
        "            turnStartBlockIndex = turnStartBlockIndex,\n"
        "            toolInputMap = toolInputMap,\n"
        "        )\n",
    ),
    # 2) repair the mojibake comment
    (
        "        // [T-truncated-tool-call-reject] finish_reason=length/max_tokens \ufffd\ufffd ToolCallPreflight \u0374\u03d2\u0372\u03c5\u03b3\u03b8\u03ba\u03bf\u03c2\n",
        "        // [T-truncated-tool-call-reject] finish_reason=length/max_tokens 由 ToolCallPreflight 统一拒绝。\n",
    ),
]

for old, new in pairs:
    if old not in s:
        print("WARN pair not found:", old.splitlines()[0][:60])
        continue
    s = s.replace(old, new)

# 3) the local textDeltaThrottleMs was removed: call the throttle directly at its use site.
s = s.replace(
    "textDeltaThrottleMs(",
    "StreamingThrottle.throttleFor(",
)
open(p, "w", encoding="utf-8", newline="").write(s)
print("repaired; splitlines now:", len(s.splitlines()))
