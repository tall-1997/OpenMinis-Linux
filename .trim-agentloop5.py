# -*- coding: utf-8 -*-
"""Extract the live session-preview push (parameterised) and drop the local textDeltaThrottleMs."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelAgentLoopExt.kt"
lines = open(p, encoding="utf-8").read().splitlines(keepends=True)

def idx_of(sub, hint=0):
    for i in range(hint, len(lines)):
        if sub in lines[i]:
            return i
    raise SystemExit("not found: " + sub)

start = idx_of("val livePreviewParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)")
s = start
while s > 0 and lines[s - 1].strip() != "run {":
    s -= 1
s -= 1
e = start
while e < len(lines) and lines[e].strip() != "}":
    e += 1
block = "".join(lines[s:e + 1])
assert "updateSessionPreview" in block
lines = lines[:s] + [
    "        pushLiveSessionPreview(\n"
    "            allToolBlocks = allToolBlocks,\n"
    "            turnStartBlockIndex = turnStartBlockIndex,\n"
    "            toolInputMap = toolInputMap,\n"
    "        )\n",
] + lines[e + 1:]

s2 = "".join(lines)
old_fn = (
    "    fun textDeltaThrottleMs(len: Int, unflushedChars: Int): Long =\n"
    "        StreamingThrottle.throttleFor(len, unflushedChars, firstTierMs = 150L)\n"
)
if old_fn in s2:
    s2 = s2.replace(old_fn, "")
else:
    print("WARN textDeltaThrottleMs shape differs")

open(p, "w", encoding="utf-8", newline="").write(s2)
print("AgentLoopExt splitlines now:", len(s2.splitlines()))
