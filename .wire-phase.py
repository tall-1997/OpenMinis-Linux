# -*- coding: utf-8 -*-
"""Wire StreamPhaseReport into ChatViewModelAgentLoopExt (replace inline TTFB clocks/logs)."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelAgentLoopExt.kt"
s = open(p, encoding="utf-8").read()

# 1) declarations: three nanos vars -> one phase report object
old_decl = (
    "            // [GH#521] Per-attempt phase clocks: request \u2192 headers (`Started`)\n"
    "            // \u2192 first real token. The two numbers are logged separately so\n"
    "            // \"slow upload\", \"slow gateway headers\" and \"headers then silent\"\n"
    "            // stop hiding behind one mislabelled `ttfbMs`. Declared outside\n"
    "            // the try so the error path can report the phase it died in.\n"
    "            var attemptStartNanos = 0L\n"
    "            var headersAtNanos = 0L\n"
    "            var firstTokenAtNanos = 0L\n"
)
new_decl = "            // [GH#521] Per-attempt phase clocks (see [StreamPhaseReport]).\n            val streamPhase = StreamPhaseReport()\n"
assert old_decl in s, "decl block not found"
s = s.replace(old_decl, new_decl)

# 2) reset before each request
old_reset = (
    "                attemptStartNanos = System.nanoTime()\n"
    "                headersAtNanos = 0L\n"
    "                firstTokenAtNanos = 0L\n"
)
assert old_reset in s
s = s.replace(old_reset, "                streamPhase.startAttempt()\n")

# 3) per-chunk recording
old_chunk = (
    "            // [GH#521] Phase clocks. `Started` = headers; the first\n"
    "            // content-bearing chunk is the real first token (usage/finished\n"
    "            // markers do not count).\n"
    "            val chunkAt = System.nanoTime()\n"
    "            if (chunk is LLMStreamChunk.Started) {\n"
    "                if (headersAtNanos == 0L) headersAtNanos = chunkAt\n"
    "            } else if (firstTokenAtNanos == 0L && chunk.countsAsFirstTokenProgress()) {\n"
    "                firstTokenAtNanos = chunkAt\n"
    "            }\n"
)
assert old_chunk in s
s = s.replace(old_chunk, "            streamPhase.onChunk(chunk)\n")

# 4) success log block
old_success_start = "                // [GH#521] Separate the two numbers users and logs need:"
idx = s.index(old_success_start)
end_marker = "                com.openminis.app.agent.GenerationRunLifecycle.succeeded(context, generationRunId)"
end = s.index(end_marker)
s = s[:idx] + "                streamPhase.logSuccess(turn, activeSessionId, currentProvider.model.id)\n" + s[end:]

# 5) failure log block
old_fail_start = "                // [GH#521] Report the phase the attempt died in. headersMs=0"
idx2 = s.index(old_fail_start)
end2_marker = "                val isRateLimit = actual is com.openminis.app.data.model.LLMError.RateLimited"
end2 = s.index(end2_marker)
s = s[:idx2] + "                streamPhase.logFailure(turn, actual)\n" + s[end2:]

open(p, "w", encoding="utf-8", newline="").write(s)
print("wired StreamPhaseReport; splitlines now:", len(s.splitlines()))
