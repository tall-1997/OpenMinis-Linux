# -*- coding: utf-8 -*-
"""Extract the live session-preview push into a helper; move textDeltaThrottleMs out."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelAgentLoopExt.kt"
lines = open(p, encoding="utf-8").read().splitlines(keepends=True)

# 1) live preview block: find the `run {` that follows the dispatch log line, replace through its closing brace
def idx_of(sub, hint=0):
    for i in range(hint, len(lines)):
        if sub in lines[i]:
            return i
    raise SystemExit("not found: " + sub)

start = idx_of("val livePreviewParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)")
# back up to the enclosing `run {` line
s = start
while s > 0 and lines[s - 1].strip() != "run {":
    s -= 1
s -= 1
# find matching close: the first line that is exactly "        }" after the block start
e = start
while e < len(lines) and lines[e].strip() != "}":
    e += 1
block = "".join(lines[s:e + 1])
assert "updateSessionPreview" in block
lines = lines[:s] + ["        pushLiveSessionPreview()\n"] + lines[e + 1:]

# 2) move the local textDeltaThrottleMs pure function out (delegate to ChatStreamingThrottle)
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
